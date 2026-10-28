import XCTest
@testable import TennisVisionCore

final class ContactCadenceTests:XCTestCase {
    func bounce(outside:Bool,nearLine:Bool=false)->[VisionImpact] {
        let w=320,h=240,right=nearLine ? 0.39:outside ? 0.2:0.9
        var settings=VisionSettings()
        settings.polygon=[VisionPoint(0.02,0.1),VisionPoint(right,0.1),VisionPoint(right,0.96),VisionPoint(0.02,0.96)]
        settings.floorLine=[VisionPoint(0,0.1),VisionPoint(1,0.1)]
        let core=LocalVision(settings:settings);var hits=[VisionImpact](),t=0.0
        for i in 0...24 {
            let dt=t-0.3,y=dt<=0 ? 170+240*dt+400*dt*dt:170-168*dt+400*dt*dt
            var pixels=[UInt32](repeating:0xff8b8884,count:w*h)
            for py in 0..<h{for px in 0..<w where hypot(Double(px)-150,Double(py)-y)<=24{pixels[py*w+px]=0xffc7df43}}
            if let hit=core.process(pixels,width:w,height:h,time:t).impact{hits.append(hit)}
            t += i%2==0 ? 1.0/60:1.0/30
        };return hits
    }
    func testBoundedDeliveryJitterDoesNotForceClearFloorContactsToReview(){
        for outside in [false,true]{let hits=bounce(outside:outside)
            XCTAssertEqual(hits.count,1);guard let hit=hits.first else{continue}
            XCTAssertEqual(hit.call,outside ? .outside:.inside)
            XCTAssertEqual(hit.time,0.3,accuracy:0.035);XCTAssertEqual(hit.point.y*240,194.5,accuracy:4)
            XCTAssertNotNil(hit.speed)
        }
    }
    func testTimingTolerancePreservesLineUncertainty(){let hits=bounce(outside:false,nearLine:true)
        XCTAssertEqual(hits.count,1);XCTAssertEqual(hits.first?.call,.review)
    }
}
