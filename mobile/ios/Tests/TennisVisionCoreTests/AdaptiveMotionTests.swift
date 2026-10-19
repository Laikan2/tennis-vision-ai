import XCTest
@testable import TennisVisionCore

/// Known pixel motion and timestamps; not a physical accuracy claim.
final class AdaptiveMotionTests:XCTestCase {
    let w=320,h=240
    var settings:VisionSettings{var s=VisionSettings();s.polygon=[VisionPoint(0.1,0.2),VisionPoint(0.9,0.2),VisionPoint(0.9,0.96),VisionPoint(0.1,0.96)];s.floorLine=[VisionPoint(0,0.1),VisionPoint(1,0.1)];return s}
    func frame(x:Double=150,y:Double)->[UInt32]{
        var p=Array(repeating:UInt32(0xff8b8884),count:w*h)
        for py in 0..<h{for px in 0..<w where hypot(Double(px)-x,Double(py)-y)<=6{p[py*w+px]=0xffc7df43}};return p
    }
    func testProjectedSpeedUsesEnoughObservedTimeAtEveryCaptureRate(){
        for fps in [30,60,120,240]{
            let core=LocalVision(settings:settings);var last:VisionResult?
            for i in 0...Int(Double(fps)*0.25){let t=Double(i)/Double(fps);last=core.process(frame(x:60+600*t,y:120),width:w,height:h,time:t)}
            XCTAssertNotNil(last?.observed,"\(fps) FPS must still observe the ball")
            XCTAssertNotNil(last?.speed,"\(fps) FPS needs a time-supported projected speed")
            if let speed=last?.speed{XCTAssertTrue((9.8...12.5).contains(speed),"Known projected speed ~11.1 km/h")}
        }
    }
    func testFastObservedBounceKeepsIncomingSpeedAt240FPS(){
        let core=LocalVision(settings:settings);var hits=[VisionImpact]()
        for i in 0...72{let t=Double(i)/240;if let hit=core.process(frame(y:200-abs(t-0.15)*600),width:w,height:h,time:t).impact{hits.append(hit)}}
        XCTAssertEqual(hits.count,1);guard let hit=hits.first else{return};XCTAssertEqual(hit.call,.inside)
        XCTAssertNotNil(hit.speed,"An observed incoming leg exists before the short contact window")
        if let speed=hit.speed{XCTAssertTrue((9.8...12.5).contains(speed))}
    }
    func testSlowBounceWithSmallLocalizationNoiseIsNotLostAt240FPS(){
        for fps in [30,60,120,240]{for turn in [0.2,0.208333333]{
            let core=LocalVision(settings:settings);var hits=[VisionImpact]()
            for i in 0...Int(Double(fps)*0.4){let t=Double(i)/Double(fps),noise=i%2==0 ? 1.2 : -1.2
                if let hit=core.process(frame(y:180-abs(t-turn)*120+noise),width:w,height:h,time:t).impact{hits.append(hit)}}
            XCTAssertEqual(hits.count,1,"\(fps) FPS, turn=\(turn): preserve both observed legs")
            if let hit=hits.first{XCTAssertEqual(hit.call,.inside)}
        }}
    }
    func testSpeedCannotBridgeMissingObservedInterval(){
        let core=LocalVision(settings:settings)
        for t in [0.0,1.0/60,2.0/60]{_ = core.process(frame(x:60+600*t,y:120),width:w,height:h,time:t)}
        for t in [0.125,0.129,0.133]{let r=core.process(frame(x:60+600*t,y:120),width:w,height:h,time:t)
            XCTAssertNotNil(r.observed);XCTAssertNil(r.speed,"Do not bridge missing samples to display speed")}
        XCTAssertNotNil(core.process(frame(x:150,y:120),width:w,height:h,time:0.15).speed,"Fresh observations recover the estimate")
    }
    func testJitterAndJitteringMonotonicFallStillCannotCreateABounce(){
        for mode in 0...1{let core=LocalVision(settings:settings)
            for i in 0..<100{let noise=i%2==0 ? 1.8 : -1.8,y=mode==0 ? 170+noise : 120+Double(i)*0.5+noise
                XCTAssertNil(core.process(frame(y:y),width:w,height:h,time:Double(i)/240).impact)}
        }
    }
}
