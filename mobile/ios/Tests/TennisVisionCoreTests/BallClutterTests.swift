import XCTest
@testable import TennisVisionCore
final class BallClutterTests:XCTestCase {
    func ball(_ t:Double,_ x:Double=100)->VisionBall{
        var b=VisionBall(x:x,y:120,radius:12,time:t,confidence:0.85)
        b.luminance=150;b.distinctFelt=true;b.greenDominant=true;return b
    }
    func testLearnsOnlyUnresolvedStationaryFragments(){
        let cache=BallClutter()
        for i in 0..<35{
            let t=Double(i)/60;var fragment=ball(t);fragment.radius=1.5
            var held=ball(t,330);held.nearHand=true
            let result=cache.filter([fragment,ball(t,220),held],time:t)
            if i>=20{XCTAssertTrue(result[0].patterned)}
            XCTAssertFalse(result[1].patterned);XCTAssertFalse(result[2].patterned)
        }
        var moved=ball(0.6,120);moved.radius=1.5
        XCTAssertFalse(cache.filter([moved],time:0.6)[0].patterned)
        cache.reset();moved.x=100
        XCTAssertFalse(cache.filter([moved],time:0.7)[0].patterned)
    }
    func testReacquiresBesideUnresolvedClutter(){
        let recovery=BallReacquisition();var still=ball(0,20);still.radius=1.5;still.motionSupport=0
        var fragment=ball(0,400);fragment.radius=1.5
        XCTAssertNil(recovery.choose([ball(0),fragment],still:still,backgroundStill:true,width:640))
        fragment.x=402;fragment.time=1/60
        XCTAssertEqual(recovery.choose([ball(1/60,110),fragment],still:still,backgroundStill:true,width:640)?.x,110)
    }
    func testEqualTargetsAndStaleSampleAreNotGuessed(){
        let recovery=BallReacquisition()
        _=recovery.choose([ball(0),ball(0,400)],still:nil,backgroundStill:true,width:640)
        XCTAssertNil(recovery.choose([ball(1/60,110),ball(1/60,410)],still:nil,backgroundStill:true,width:640))
        XCTAssertNil(recovery.choose([ball(0.3,120)],still:nil,backgroundStill:true,width:640))
        recovery.reset();XCTAssertNil(recovery.choose([ball(0.32,130)],still:nil,backgroundStill:true,width:640))
    }
    func testHeldBallCanBeRecovered(){
        let recovery=BallReacquisition();var held=ball(0);held.nearHand=true
        XCTAssertNil(recovery.choose([held],still:nil,backgroundStill:true,width:640))
        held.time=1/60
        XCTAssertEqual(recovery.choose([held],still:nil,backgroundStill:true,width:640)?.nearHand,true)
    }
}
