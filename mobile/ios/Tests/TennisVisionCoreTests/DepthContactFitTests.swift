import XCTest
@testable import TennisVisionCore

final class DepthContactFitTests:XCTestCase {
    let w=640,h=480
    func observation(_ t:Double,rebound:Bool=true)->VisionBall {
        let d=t-0.17,z=2+(d<=0 || !rebound ? 4*d : -4*d)+2*d*d
        var b=VisionBall(x:320+50/z,y:240+20/z,radius:40/z,time:t,confidence:0.9)
        b.luminance=160;b.greenDominant=true;return b
    }
    func pixels(_ b:VisionBall)->[UInt32] {
        var p=Array(repeating:UInt32(0xff587082),count:w*h)
        for y in max(0,Int(b.y-b.radius))...min(h-1,Int(b.y+b.radius)) {
            for x in max(0,Int(b.x-b.radius))...min(w-1,Int(b.x+b.radius)) where hypot(Double(x)-b.x,Double(y)-b.y)<=b.radius {p[y*w+x]=0xffc7eb4d}
        }
        return p
    }
    func testRadiusReturnWithoutVerticalImageBounceCreatesOnlyCandidate() {
        for surface in [VisionSurface.floor,.wall] {
            var s=VisionSettings();s.surface=surface
            s.polygon=[VisionPoint(0.1,0.1),VisionPoint(0.9,0.1),VisionPoint(0.9,0.9),VisionPoint(0.1,0.9)]
            if surface == .floor{s.floorLine=[VisionPoint(0,0.1),VisionPoint(1,0.1)]}
            let core=LocalVision(settings:s);var hits=[VisionImpact]()
            for i in 0...21 {
                let t=Double(i)/60
                if let hit=core.process(pixels(observation(t)),width:w,height:h,time:t).impact{hits.append(hit)}
            }
            XCTAssertEqual(hits.count,1);guard let hit=hits.first else{continue}
            XCTAssertEqual(hit.call,.review);XCTAssertTrue(hit.reason.contains("profundidade"))
            XCTAssertEqual(hit.time,0.17,accuracy:0.03)
            XCTAssertEqual(hit.point.x*Double(w),345,accuracy:4);XCTAssertEqual(hit.point.y*Double(h),250,accuracy:4)
            XCTAssertGreaterThanOrEqual(hit.uncertainty,20)
        }
    }
    func testHandOcclusionDisappearanceAndAbruptSizeJitterCannotCreateDepthContact() {
        let p=(0...20).map{observation(Double($0)/60)}
        XCTAssertNotNil(DepthContactFit.find(p,width:w,height:h))
        XCTAssertNil(DepthContactFit.find(p.map{var b=$0;b.nearHand=true;return b},width:w,height:h))
        XCTAssertNil(DepthContactFit.find(p.map{var b=$0;b.occluded=true;return b},width:w,height:h))
        XCTAssertNil(DepthContactFit.find((0...20).map{observation(Double($0)/60,rebound:false)},width:w,height:h))
        XCTAssertNil(DepthContactFit.find(p.enumerated().map{i,v in var b=v;b.radius=i==10 ? 9:24;return b},width:w,height:h))
        XCTAssertNil(DepthContactFit.find(p.map{var b=$0;b.radius=24;return b},width:w,height:h))
    }
}

