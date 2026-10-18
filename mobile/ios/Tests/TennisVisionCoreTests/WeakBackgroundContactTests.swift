import XCTest
@testable import TennisVisionCore

/// Known negative pixels motivated by false marks on a warm wooden floor.
final class WeakBackgroundContactTests:XCTestCase {
    let w=320,h=240
    var settings:VisionSettings {
        var s=VisionSettings();s.polygon=[VisionPoint(0.2,0.3),VisionPoint(0.7,0.3),VisionPoint(0.7,0.95),VisionPoint(0.2,0.95)]
        s.floorLine=[VisionPoint(0,0.25),VisionPoint(1,0.25)];return s
    }
    func frame(x:Double,y:Double,r:Double=3,color:UInt32=0xffac9866)->[UInt32] {
        var p=Array(repeating:UInt32(0xff394b59),count:w*h)
        for py in max(0,Int(y-r))...min(h-1,Int(ceil(y+r))){for px in max(0,Int(x-r))...min(w-1,Int(ceil(x+r))){
            if hypot(Double(px)-x,Double(py)-y)<=r{p[py*w+px]=color}
        }};return p
    }
    func testMovingWarmFloorFragmentsCannotAcquireOrCreateReviewContacts(){
        for r in [2.0,3,4]{let core=LocalVision(settings:settings)
            for (i,y) in [70,85,105,130,160,195,160,130,105,85].enumerated(){
                let result=core.process(frame(x:150,y:Double(y),r:r),width:w,height:h,time:Double(i)/60)
                XCTAssertNil(result.observed,"Warm fragment radius=\(r) frame=\(i) must not be a ball")
                XCTAssertNil(result.impact,"REVIEW is still a contact claim")
            }
        }
    }
    func testWarmFragmentsCannotMakeWallContactCandidatesEither(){
        var s=settings;s.surface = .wall;let core=LocalVision(settings:s)
        for (i,x) in [70,85,105,130,160,195,160,130,105,85].enumerated(){
            let result=core.process(frame(x:Double(x),y:150),width:w,height:h,time:Double(i)/60)
            XCTAssertNil(result.observed);XCTAssertNil(result.impact)
        }
    }
    func testWarmYellowFeltStillProducesKnownInAndOut(){
        for (x,call) in [(140.0,VisionCall.inside),(285.0,.outside)]{let core=LocalVision(settings:settings);var hits=[VisionImpact]()
            for (i,y) in [70,85,105,130,160,195,160,130,105,85].enumerated(){
                if let hit=core.process(frame(x:x,y:Double(y),r:6,color:0xffbd930e),width:w,height:h,time:Double(i)/60).impact{hits.append(hit)}
            };XCTAssertEqual(hits.count,1);XCTAssertEqual(hits.first?.call,call)
        }
    }
    func testBriefWeakColourCanContinueOnlyAnAlreadySupportedTrajectory(){
        var s=settings;s.surface = .track;let core=LocalVision(settings:s)
        for i in 0..<18{let color:UInt32=(6...7).contains(i) ? 0xffac9866:0xffc7eb4d
            let result=core.process(frame(x:70+Double(i)*3,y:120,r:6,color:color),width:w,height:h,time:Double(i)/60)
            XCTAssertNotNil(result.observed,"Supported trajectory frame=\(i)");XCTAssertNil(result.impact)
        }
    }
    func testWeakColourCannotKeepAnUnconfirmedTrackAliveIndefinitely(){
        var s=settings;s.surface = .track;let core=LocalVision(settings:s)
        for i in 0..<30{let color:UInt32=i<5 ? 0xffc7eb4d:0xffac9866
            let result=core.process(frame(x:70+Double(i)*3,y:120,r:6,color:color),width:w,height:h,time:Double(i)/60)
            if i>=12{XCTAssertNil(result.observed,"Weak continuation must expire at frame=\(i)")}
            if i>=5{XCTAssertNil(result.speed,"Uncertain colour must not produce speed")}
            XCTAssertNil(result.impact)
        }
    }
    func testColourLostOnTheOutgoingLegCannotCertifyAContact(){
        let core=LocalVision(settings:settings)
        for i in 0..<21{let y=180-Double(abs(i-8))*2,color:UInt32=i<=8 ? 0xffc7eb4d:0xffac9866
            XCTAssertNil(core.process(frame(x:150,y:y,color:color),width:w,height:h,time:Double(i)/60).impact)
        }
    }
}
