import XCTest
#if canImport(AVFoundation)
import AVFoundation
#endif
@testable import TennisVisionCore
final class LineVoiceTests:XCTestCase {
#if canImport(AVFoundation)
    func testAppleDecoderReadsBothActualVoiceAssets() throws {
        let root=URL(fileURLWithPath:#filePath).deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
        for name in ["IN","OUT"] {
            let file=try AVAudioFile(forReading:root.appendingPathComponent("App/Resources/\(name).mp3"))
            XCTAssertEqual(file.processingFormat.channelCount,1)
            let buffer=try XCTUnwrap(AVAudioPCMBuffer(pcmFormat:file.processingFormat,frameCapacity:AVAudioFrameCount(file.length)))
            try file.read(into:buffer)
            let duration=Double(buffer.frameLength)/file.processingFormat.sampleRate
            XCTAssertGreaterThan(duration,0.2);XCTAssertLessThan(duration,1.2)
            let channel=try XCTUnwrap(buffer.floatChannelData?[0])
            let peak=(0..<Int(buffer.frameLength)).map{abs(channel[$0])}.max() ?? 0
            XCTAssertGreaterThan(peak,0.05);XCTAssertLessThan(peak,0.99)
        }
    }
#endif
    func testUncertainAndRejectedContactsStaySilent(){
        for call in [VisionCall.review,.unmarked,.rejected]{XCTAssertNil(LineVoicePolicy.voice(for:call,outEnabled:true,inEnabled:true))}
    }
    func testIndependentPreferences(){
        XCTAssertEqual(LineVoicePolicy.voice(for:.outside,outEnabled:true,inEnabled:false),.outside)
        XCTAssertNil(LineVoicePolicy.voice(for:.inside,outEnabled:true,inEnabled:false))
        XCTAssertEqual(LineVoicePolicy.voice(for:.inside,outEnabled:false,inEnabled:true),.inside)
        XCTAssertNil(LineVoicePolicy.voice(for:.outside,outEnabled:false,inEnabled:true))
    }
    func testOutHasPriorityWithoutQueuedCalls(){
        XCTAssertTrue(LineVoicePolicy.canStart(.outside,current:.inside))
        XCTAssertFalse(LineVoicePolicy.canStart(.inside,current:.outside))
        XCTAssertFalse(LineVoicePolicy.canStart(.outside,current:.outside))
        XCTAssertTrue(LineVoicePolicy.canStart(.inside,current:nil))
    }
}
