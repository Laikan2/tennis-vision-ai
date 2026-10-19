import XCTest
@testable import TennisVisionCore

/// Release must not inherit a hand forever over a two-tone warm floor.
final class ReleasedBallTests:XCTestCase {
    let w=720,h=960
    func frame(_ y:Double,hand:Bool)->[UInt32] {
        let x=360.0,r=24.0
        var p=(0..<w*h).map{UInt32($0%w<360 ? 0xff88705a:0xff84909f)}
        if hand{for py in Int(y-r*1.9)...Int(y+r*0.4){for px in Int(x-r*1.3)...Int(x+r*1.3){p[py*w+px]=0xffb47b65}}}
        for py in Int(y-r)...Int(y+r){for px in Int(x-r)...Int(x+r){if hypot(Double(px)-x,Double(py)-y)<=r{p[py*w+px]=0xffc7df43}}}
        return p
    }
    func run(_ heldFirst:Bool)->[VisionResult] {
        var settings=VisionSettings()
        settings.polygon=[VisionPoint(0.1,0.1),VisionPoint(0.9,0.1),VisionPoint(0.9,0.95),VisionPoint(0.1,0.95)]
        settings.floorLine=[VisionPoint(0,0.05),VisionPoint(1,0.05)]
        let core=LocalVision(settings:settings);var i=0
        if heldFirst{for _ in 0..<5{_ = core.process(frame(250,hand:true),width:w,height:h,time:Double(i)/60);i+=1}}
        return [300.0,350,400,450,500,550,500,450,400,350,300].map{y in
            let result=core.process(frame(y,hand:false),width:w,height:h,time:Double(i)/60);i+=1;return result
        }
    }
    func testAFreeBallStillHasABounceOverTheSameFloor(){
        let free=run(false)
        XCTAssertGreaterThanOrEqual(free.filter{$0.observed != nil}.count,9)
        XCTAssertEqual(free.filter{$0.impact != nil}.count,1)
    }
    func testAReleasedBallClearsHandStateAndCanBounce(){
        let released=run(true)
        XCTAssertGreaterThanOrEqual(released.filter{$0.observed != nil}.count,9,"Do not pass by losing the ball")
        XCTAssertFalse(released.dropFirst(3).contains{$0.observed?.nearHand == true},"A removed hand cannot stay attached")
        XCTAssertEqual(released.filter{$0.impact != nil}.count,1)
    }
}
