import XCTest
@testable import TennisVisionCore

final class PerspectiveFloorFitTests:XCTestCase {
    func observations(_ transverse:Bool=true)->[VisionBall] {
        (0...20).map{i in
            let t=Double(i-10)/60,radius=26+75*abs(t)
            let y=transverse ? 360-850*abs(t)+120*t*t:360
            var b=VisionBall(x:320+(20+80*t)*radius/30,y:240+(y-240)*radius/30,radius:radius,time:Double(i)/60,confidence:0.85)
            b.luminance=150;b.feltChroma=150;b.greenDominant=true;return b
        }
    }
    func testTransverseReboundSurvivesPerspectiveScaleChange(){
        let p=observations()
        XCTAssertNil(ObservedContactFit.find(p,surface:.floor))
        guard let fit=PerspectiveFloorFit.find(p,width:640,height:480) else{return XCTFail("Missing transverse floor fit")}
        XCTAssertEqual(fit.time,10/60.0,accuracy:0.015)
        XCTAssertEqual(fit.x,320+20*26/30.0,accuracy:3)
        XCTAssertEqual(fit.y,240+120*26/30.0,accuracy:5)
        XCTAssertGreaterThan(fit.error,1)
    }
    func testPureDepthCannotCertifyFloor(){XCTAssertNil(PerspectiveFloorFit.find(observations(false),width:640,height:480))}
    func testRenderedPerspectiveBounceHasOneLocalizedInAndSpeed(){
        let w=640,h=480
        var settings=VisionSettings()
        settings.polygon=[VisionPoint(0.05,0.05),VisionPoint(0.95,0.05),VisionPoint(0.95,0.95),VisionPoint(0.05,0.95)]
        settings.floorLine=VisionFloorBoundaryDraft.wholeImage
        let core=LocalVision(settings:settings);var hits=[VisionImpact]()
        for i in 0..<10{_=core.process([UInt32](repeating:0xff333344,count:w*h),width:w,height:h,time:Double(i)/60)}
        for b in observations(){
            var pixels=[UInt32](repeating:0xff333344,count:w*h)
            for y in max(0,Int(b.y-b.radius))...min(h-1,Int(b.y+b.radius)){
                for x in max(0,Int(b.x-b.radius))...min(w-1,Int(b.x+b.radius)){
                    if hypot(Double(x)-b.x,Double(y)-b.y)<=b.radius{pixels[y*w+x]=0xffc7eb4d}
                }
            }
            if let hit=core.process(pixels,width:w,height:h,time:b.time+10/60).impact{hits.append(hit)}
        }
        XCTAssertEqual(hits.count,1)
        guard let hit=hits.first else{return}
        XCTAssertEqual(hit.call,.inside);XCTAssertNotNil(hit.speed)
        XCTAssertEqual(hit.point.x,(320+20*26/30.0)/Double(w),accuracy:0.02)
        XCTAssertEqual(hit.point.y,(240+120*26/30.0+26)/Double(h),accuracy:0.03)
    }
    func testHandGapAndSizeFlickerBreakSupport(){
        let p=observations()
        var hand=p;hand[10].nearHand=true
        XCTAssertNil(PerspectiveFloorFit.find(hand,width:640,height:480))
        let gap=p.enumerated().map{i,b->VisionBall in var q=b;if i>=10{q.time+=0.09};return q}
        XCTAssertNil(PerspectiveFloorFit.find(gap,width:640,height:480))
        let flicker=p.enumerated().map{i,b->VisionBall in var q=b;q.radius *= i%2==0 ? 1:1.3;return q}
        XCTAssertNil(PerspectiveFloorFit.find(flicker,width:640,height:480))
    }
}
