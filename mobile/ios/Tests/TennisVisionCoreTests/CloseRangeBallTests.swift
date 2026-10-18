import XCTest
@testable import TennisVisionCore

final class CloseRangeBallTests:XCTestCase {
    let w=720,h=960
    func frame(_ y:Double,_ radius:Double,hand:Bool=false,color:UInt32=0xff755d18)->[UInt32] {
        let x=360.0;var p=Array(repeating:UInt32(0xff88705a),count:w*h)
        if hand{for py in max(0,Int(y-radius*1.9))...min(h-1,Int(y+radius*0.4)){for px in Int(x-radius*1.3)...Int(x+radius*1.3){p[py*w+px]=0xffb47b65}}}
        for py in max(0,Int(y-radius))...min(h-1,Int(y+radius)){for px in Int(x-radius)...Int(x+radius){if hypot(Double(px)-x,Double(py)-y)<=radius{p[py*w+px]=color}}}
        return p
    }
    func testLargeWarmAndGreenBallKeepWholeSilhouette(){
        for color in [UInt32(0xff755d18),0xffc7df43]{
            let b=LocalVision().process(frame(400,140,color:color),width:w,height:h,time:0).observed
            XCTAssertNotNil(b,"A 280px ball must not become small felt fragments")
            if let b{XCTAssertEqual(b.x,360,accuracy:6);XCTAssertEqual(b.y,400,accuracy:6);XCTAssertEqual(b.radius,140,accuracy:8)}
        }
    }
    func testMovingCloseGripOverWarmFloorCannotScore(){
        var settings=VisionSettings();settings.polygon=[VisionPoint(0.1,0.1),VisionPoint(0.9,0.1),VisionPoint(0.9,0.95),VisionPoint(0.1,0.95)]
        settings.floorLine=[VisionPoint(0,0.05),VisionPoint(1,0.05)]
        let core=LocalVision(settings:settings);var seen=0,held=0
        for (i,y) in [380.0,430,480,530,580,630,580,530,480,430,380].enumerated(){
            let result=core.process(frame(y,90,hand:true),width:w,height:h,time:Double(i)/60)
            if let b=result.observed{seen+=1;if b.nearHand{held+=1};XCTAssertEqual(b.x,360,accuracy:8);XCTAssertEqual(b.y,y,accuracy:8)}
            XCTAssertNil(result.impact,"A carried turn is not floor contact")
        }
        XCTAssertGreaterThanOrEqual(seen,8,"Do not pass by losing the ball")
        XCTAssertGreaterThanOrEqual(held,3,"Visible moving grip must be represented")
    }
    func testHighlightInsideLargeUnderexposedFeltIsNotTinyResolvedBall(){
        var p=frame(400,70,color:0xff352a0a)
        for y in 396...404{for x in 356...364{if hypot(Double(x)-360,Double(y)-400)<=4{p[y*w+x]=0xff75601a}}}
        let b=LocalVision().process(p,width:w,height:h,time:0).observed
        XCTAssertTrue(b==nil || b!.radius>=50 && b!.occluded,"An 8px highlight is not the 140px silhouette")
    }
    func testIsolatedTinyWarmBallIsNotRejectedByHighlightGuard(){
        let b=LocalVision().process(frame(400,4,color:0xff75601a),width:w,height:h,time:0).observed
        XCTAssertNotNil(b);if let b{XCTAssertEqual(b.x,360,accuracy:1);XCTAssertEqual(b.y,400,accuracy:1)}
    }
}
