import XCTest
@testable import TennisVisionCore

final class PairingTests: XCTestCase {
    private let uri="tennisvision://pair?host=192.168.15.2&port=8554&path=iphone&transport=rtsp&fps=60&width=1920&height=1080&bitrate=12000000"
    func testWindowsQR() throws {
        let configuration=try PairingConfiguration.parse(uri)
        XCTAssertEqual(configuration.rtspURL,"rtsp://192.168.15.2:8554/iphone")
        XCTAssertEqual(configuration.profile,.standard)
    }
    func testAndroidQRIsRejected() { XCTAssertThrowsError(try PairingConfiguration.parse(uri.replacingOccurrences(of:"transport=rtsp",with:"transport=srt"))) }
    func testDuplicateDestinationIsRejected() { XCTAssertThrowsError(try PairingConfiguration.parse(uri+"&host=attacker.example")) }
    func testURLAuthorityAndFragmentsAreRejected() {
        for value in [uri+"#ignored",uri.replacingOccurrences(of:"//pair",with:"//user@pair"),
                      uri.replacingOccurrences(of:"//pair?",with:"//pair/other?")] {
            XCTAssertThrowsError(try PairingConfiguration.parse(value))
        }
    }
    func testInvalidHostPortAndPathAreRejected() {
        for host in ["","192.168.1.999","192.168.1","https://192.168.1.2","pc\r\nOPTIONS","-pc","pc..local"] {
            XCTAssertThrowsError(try PairingConfiguration(host:host))
        }
        XCTAssertThrowsError(try PairingConfiguration(host:"pc.local",port:65536))
        XCTAssertThrowsError(try PairingConfiguration(host:"pc.local",path:"../galaxy"))
    }
    func testSupportedHighSpeedQRPreservesRequestedRate() throws {
        let high=try PairingConfiguration.parse(uri.replacingOccurrences(of:"fps=60",with:"fps=240"))
        XCTAssertEqual(high.profile.fps,240)
    }
    func testUnboundedAndNonCanonicalParametersAreRejected() {
        for field in ["fps=100000","fps=060","fps=-1","fps=120.0"] {
            XCTAssertThrowsError(try PairingConfiguration.parse(uri.replacingOccurrences(of:"fps=60",with:field)))
        }
        XCTAssertThrowsError(try PairingConfiguration.parse(uri.replacingOccurrences(of:"bitrate=12000000",with:"bitrate=999999999")))
    }
    func testPersistedSettingsAreRevalidated() throws {
        let configuration=try PairingConfiguration.parse(uri)
        let data=try JSONEncoder().encode(configuration)
        XCTAssertEqual(try PairingConfiguration.restore(data),configuration)
        let corrupted=Data(String(data:data,encoding:.utf8)!.replacingOccurrences(of:"192.168.15.2",with:"127.0.0.999").utf8)
        XCTAssertThrowsError(try PairingConfiguration.restore(corrupted))
    }
}

final class H264Tests: XCTestCase {
    func testAVCCMultipleNALsAndLengthSizes() throws {
        XCTAssertEqual(try AVCC.nals(Data([0,0,0,2,0x65,9,0,0,0,2,0x61,8])),[Data([0x65,9]),Data([0x61,8])])
        XCTAssertEqual(try AVCC.nals(Data([2,0x65,9]),lengthSize:1),[Data([0x65,9])])
    }
    func testMalformedH264IsRejected() {
        for data in [Data(),Data([0,0,0]),Data([0,0,0,0]),Data([0,0,0,4,0x65,9]),Data([0,0,0,1,0])] {
            XCTAssertThrowsError(try AVCC.nals(data))
        }
        XCTAssertThrowsError(try AVCC.nals(Data([1,0x65]),lengthSize:8))
    }
    func testTimestampUsesCaptureTimeAt240FPSAndWraps() throws {
        XCTAssertEqual(try RTPPacketizer.timestamp(pts:100+1.0/240,origin:100),375)
        XCTAssertEqual(try RTPPacketizer.timestamp(pts:100+1.0/60,origin:100),1500)
        XCTAssertEqual(try RTPPacketizer.timestamp(pts:Double(UInt32.max)/90000+1,origin:0),89999)
        XCTAssertThrowsError(try RTPPacketizer.timestamp(pts:.nan,origin:0))
        XCTAssertThrowsError(try RTPPacketizer.timestamp(pts:1,origin:2))
    }
    func testSequenceWrapAndMarkerOnlyOnLastNAL() {
        var packetizer=RTPPacketizer(sequence:65535,ssrc:0x10203040)
        let packets=packetizer.packets(nals:[Data([0x67,1]),Data([0x65,2])],timestamp:0x01020304)
        XCTAssertEqual(Array(packets[0].prefix(12)),[0x80,0x60,0xff,0xff,1,2,3,4,0x10,0x20,0x30,0x40])
        XCTAssertEqual(Array(packets[1].prefix(4)),[0x80,0xe0,0,0])
        XCTAssertEqual(packetizer.sequence,1)
    }
    func testFragmentationCanReconstructOriginalNAL() {
        let nal=Data([0x65])+Data((0..<1000).map{UInt8($0%251)})
        var packetizer=RTPPacketizer(sequence:8,ssrc:1,maxPacketBytes:200)
        let packets=packetizer.packets(nals:[nal],timestamp:0)
        XCTAssertTrue(packets.count > 1)
        XCTAssertTrue(packets.allSatisfy{$0.count <= 200})
        XCTAssertEqual(packets.first![12],0x7c)
        XCTAssertEqual(packets.first![13],0x85)
        XCTAssertEqual(packets.last![13],0x45)
        XCTAssertEqual(packets.dropLast().filter{$0[1]&0x80 != 0}.count,0)
        let rebuilt=packets.reduce(Data([0x65])){$0+Data($1.dropFirst(14))}
        XCTAssertEqual(rebuilt,nal)
        let wire=RTPPacketizer.interleave(packets)
        XCTAssertEqual(Array(wire.prefix(4)),[0x24,0,0,200])
    }
}

final class RTSPTests: XCTestCase {
    private func response(_ sequence: Int, _ body: String="") -> Data {
        Data("RTSP/1.0 200 OK\r\nCSeq: \(sequence)\r\nContent-Length: \(body.utf8.count)\r\n\r\n\(body)".utf8)
    }
    func testResponsesCanBeFragmentedAtEveryByte() throws {
        var decoder=RTSPDecoder(); var responses=[RTSPResponse]()
        for byte in response(7,"v=0\r\n") { responses+=try decoder.append(Data([byte])) }
        XCTAssertEqual(responses.count,1); XCTAssertEqual(responses[0].sequence,7)
        XCTAssertEqual(String(data:responses[0].body,encoding:.utf8),"v=0\r\n")
    }
    func testCoalescedResponsesAndRTCPNormalizeBufferIndices() throws {
        var decoder=RTSPDecoder()
        let rtcp=Data([0x24,1,0,4,0x80,200,0,0])
        let responses=try decoder.append(response(1)+rtcp+response(2)+rtcp+response(3))
        XCTAssertEqual(responses.map(\.sequence),[1,2,3])
    }
    func testMalformedResponsesFailWithoutUnboundedBuffering() {
        for text in ["HTTP/1.1 200 OK\r\nCSeq: 1\r\n\r\n",
                     "RTSP/1.0 200 OK\r\nCSeq: 1\r\nCSeq: 2\r\n\r\n",
                     "RTSP/1.0 200 OK\r\nCSeq: 1\r\nContent-Length: -1\r\n\r\n",
                     "RTSP/1.0 200 OK\r\nCSeq: 1\r\nContent-Length: huge\r\n\r\n",
                     "RTSP/1.0 200 OK\r\nCSeq: 1\r\nContent-Length: 99999\r\n\r\n",
                     "RTSP/1.0 200 OK\r\n\r\n"] {
            var decoder=RTSPDecoder(); XCTAssertThrowsError(try decoder.append(Data(text.utf8)))
        }
        var decoder=RTSPDecoder(); XCTAssertThrowsError(try decoder.append(Data(repeating:65,count:16385)))
    }
    func testSDPAdvertisesActualRequestedFPSAndParameterSets() throws {
        let sps=Data([0x67,0x64,0,0x2a]),pps=Data([0x68,1,2])
        let sdp=try RTSPMessages.sdp(sps:sps,pps:pps,fps:120)
        XCTAssertTrue(sdp.contains("a=framerate:120\r\n"))
        XCTAssertTrue(sdp.contains("profile-level-id=64002a"))
        XCTAssertTrue(sdp.contains(sps.base64EncodedString()+","+pps.base64EncodedString()))
        XCTAssertThrowsError(try RTSPMessages.sdp(sps:pps,pps:sps,fps:60))
    }
    func testRequestContentLengthCountsUTF8Bytes() {
        let request=RTSPMessages.request("ANNOUNCE",url:"rtsp://pc:8554/iphone",sequence:2,body:"Tênis")
        XCTAssertTrue(String(data:request,encoding:.utf8)!.contains("Content-Length: 6\r\n"))
    }
}

final class QueueTests: XCTestCase {
    private func frame(_ time: Double, key: Bool=false, size: Int=2) -> EncodedFrame {
        EncodedFrame(nals:[Data([key ? 0x65 : 0x61])+Data(repeating:8,count:size-1)],
            pts:time,keyframe:key,createdAt:time)
    }
    func testStartsAtKeyframeAndPreservesOrdering() {
        let queue=FrameQueue()
        XCTAssertFalse(queue.offer(frame(1)).accepted)
        XCTAssertTrue(queue.offer(frame(1.01,key:true)).accepted)
        XCTAssertTrue(queue.offer(frame(1.02)).accepted)
        XCTAssertEqual(queue.take(now:1.03)?.pts,1.01)
        XCTAssertEqual(queue.take(now:1.03)?.pts,1.02)
    }
    func testOverflowDropsDependenciesUntilFreshKeyframe() {
        let queue=FrameQueue(maxFrames:2)
        _=queue.offer(frame(1,key:true)); _=queue.offer(frame(1.01))
        XCTAssertFalse(queue.offer(frame(1.02)).accepted)
        XCTAssertEqual(queue.count,0); XCTAssertEqual(queue.droppedFrames,3)
        XCTAssertFalse(queue.offer(frame(1.03)).accepted)
        XCTAssertTrue(queue.offer(frame(1.04,key:true)).accepted)
        XCTAssertEqual(queue.take(now:1.05)?.pts,1.04)
    }
    func testLateFramesCannotAccumulateDelay() {
        let queue=FrameQueue(maxAge:0.08)
        _=queue.offer(frame(1,key:true))
        XCTAssertNil(queue.take(now:1.09))
        XCTAssertTrue(queue.needsKeyframe)
        XCTAssertFalse(queue.offer(frame(1.1)).accepted)
    }
    func testBytesAndInvalidTimestampsAreBounded() {
        let queue=FrameQueue(maxBytes:1024)
        XCTAssertFalse(queue.offer(frame(1,key:true,size:1025)).accepted)
        XCTAssertFalse(queue.offer(frame(.nan,key:true)).accepted)
        XCTAssertEqual(queue.count,0)
    }
    func testFPSUsesObservationsAndGoesToZeroOnStall() {
        var meter=FrameRateMeter()
        for index in 0..<121 { meter.observe(Double(index)/120) }
        XCTAssertEqual(meter.value(now:1.01),120,accuracy:0.01)
        meter.observe(.nan); meter.observe(0)
        XCTAssertEqual(meter.value(now:1.02),120,accuracy:0.01)
        XCTAssertEqual(meter.value(now:2.1),0)
    }
}

final class MobileExperienceTests: XCTestCase {
    func testAutomaticUsesRealFormatsAndPrefersResolutionAtSameRate() {
        let p60=CaptureProfile.standard
        let p120=CaptureProfile(width:1280,height:720,fps:120,bitrate:20_000_000)
        let full120=CaptureProfile(width:1920,height:1080,fps:120,bitrate:20_000_000)
        XCTAssertEqual(CameraPolicy.best([p60,p120,full120]),full120)
        XCTAssertEqual(CameraPolicy.best([p60])?.fps,60)
        XCTAssertNil(CameraPolicy.best([]))
        XCTAssertEqual(CameraPolicy.next(after:full120,in:[p60,p120,full120]),p120)
        XCTAssertNil(CameraPolicy.next(after:p60,in:[p60,p120,full120]))
    }
    func testBriefSlowdownDoesNotAdaptButSustainedStarvationDoes() {
        var health=ProfileHealth()
        for _ in 0..<20 { XCTAssertFalse(health.sample(elapsed:4,requested:120,capture:0,encoded:0)) }
        for _ in 0..<5 { XCTAssertFalse(health.sample(elapsed:15,requested:120,capture:60,encoded:60)) }
        XCTAssertFalse(health.sample(elapsed:20,requested:120,capture:120,encoded:118))
        for _ in 0..<5 { XCTAssertFalse(health.sample(elapsed:25,requested:120,capture:60,encoded:60)) }
        XCTAssertTrue(health.sample(elapsed:31,requested:120,capture:60,encoded:60))
    }
    func testNewQRPreservesPanelPairingAndOldQRStillWorks() throws {
        let base="tennisvision://pair?host=192.168.1.2&transport=rtsp"
        let legacy=try PairingConfiguration.parse(base)
        XCTAssertNil(legacy.panelURL)
        let paired=try PairingConfiguration.parse(base+"&panelPort=8894&panelToken="+String(repeating:"a",count:64))
        XCTAssertEqual(paired.panelURL?.absoluteString,"http://192.168.1.2:8894")
        XCTAssertEqual(try PairingConfiguration.restore(JSONEncoder().encode(paired)),paired)
    }
    func testIncompleteOrUnsafePanelParametersAreRejected() {
        let base="tennisvision://pair?host=192.168.1.2&transport=rtsp"
        for suffix in ["&panelPort=8894","&panelToken=x","&panelPort=0&panelToken="+String(repeating:"a",count:64),
                       "&panelPort=8894&panelToken="+String(repeating:"é",count:64)] {
            XCTAssertThrowsError(try PairingConfiguration.parse(base+suffix))
        }
    }
    func testFeedSelectsCompatibleReleaseAndComparesVersionsNumerically() throws {
        let data=Data(#"{"apps":[{"bundleIdentifier":"ai.tennisvision.camera.ios","versions":[{"version":"0.10.0","buildVersion":"10","downloadURL":"https://github.com/Laikan2/tennis-vision-releases/releases/download/v1/app.ipa","size":1000,"minOSVersion":"17.4"}]}]}"#.utf8)
        let latest=try UpdateSource.latest(data,os:"18.0")
        XCTAssertTrue(latest.newer(than:"0.9.0"))
        XCTAssertFalse(latest.newer(than:"0.10.0"))
        XCTAssertThrowsError(try UpdateSource.latest(data,os:"16.0"))
        let foreign=Data(String(data:data,encoding:.utf8)!.replacingOccurrences(of:"https://github.com/Laikan2",with:"https://other.example/Laikan2").utf8)
        XCTAssertThrowsError(try UpdateSource.latest(foreign,os:"18.0"))
    }
}
