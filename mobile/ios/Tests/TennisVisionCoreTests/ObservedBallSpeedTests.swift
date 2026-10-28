import XCTest
@testable import TennisVisionCore

final class ObservedBallSpeedTests:XCTestCase {
    func path()->[VisionBall]{(0...6).map{i in
        VisionBall(x:Double(i)*10,y:100,radius:6,time:Double(i)/60,confidence:0.9)
    }}
    func testBriefBlurAtContactUsesRecentCleanIncomingSpeed(){
        var p=path();p[6].blurred=true
        XCTAssertNil(ObservedBallSpeed.current(p))
        XCTAssertEqual(ObservedBallSpeed.incoming(p,contactTime:0.1) ?? -1,12.06,accuracy:0.001)
    }
    func testOutgoingLegNeverCancelsIncomingSpeed(){
        var p=path();p[6].x=40 // direction changed after the true contact time
        XCTAssertEqual(ObservedBallSpeed.incoming(p,contactTime:0.085) ?? -1,12.06,accuracy:0.001)
    }
    func testNeverRecoversAcrossHandOcclusionUnresolvedOrLongBlur(){
        for kind in 0...4{var p=path();p[6].blurred=true
            if kind==0{p[6].nearHand=true};if kind==1{p[6].occluded=true}
            if kind==2{p[6].colorUncertain=true};if kind==3{p[5].nearHand=true}
            if kind==4{p[4].blurred=true;p[5].blurred=true}
            XCTAssertNil(ObservedBallSpeed.incoming(p,contactTime:0.1))
        }
        XCTAssertNil(ObservedBallSpeed.incoming(path(),contactTime:0.16))
    }
    func testChangingScaleAndTooSmallBallCannotProduceSpeed(){
        var p=path();p[6].radius=12;XCTAssertNil(ObservedBallSpeed.incoming(p,contactTime:0.1))
        p=path().map{b in var q=b;q.radius=2;return q};XCTAssertNil(ObservedBallSpeed.incoming(p,contactTime:0.1))
    }
}
