import XCTest
@testable import TennisVisionCore

/// Known pixels; not a claim of physical bounce accuracy.
final class FeltOwnershipTests:XCTestCase {
    let w=320,h=240
    func disk(_ p:inout [UInt32],_ x:Double,_ y:Double,_ r:Double,_ color:UInt32){
        for py in 0..<h{for px in 0..<w where hypot(Double(px)-x,Double(py)-y)<=r{p[py*w+px]=color}}
    }
    func image(clipped:Bool=false,smallOutside:Bool=false)->[UInt32]{
        var p=Array(repeating:UInt32(0xff8b8884),count:w*h)
        if clipped{disk(&p,320,110,55,0xffc7df43);disk(&p,290,110,8,0xff303030);disk(&p,290,110,3,0xffc7df43);disk(&p,290,169,3,0xffc7df43)}
        if smallOutside{disk(&p,90,150,3,0xffc7df43)};return p
    }
    func testClippedLargeBallCannotBecomeAnInnerTinyBall(){
        let core=LocalVision()
        for i in 0..<10{let r=core.process(image(clipped:true),width:w,height:h,time:Double(i)/60)
            XCTAssertNil(r.observed,"A felt island inside an incomplete silhouette is not a separate ball");XCTAssertNil(r.impact)
        }
    }
    func testSeparateSmallBallOutsideClippedSilhouetteIsStillVisible(){
        let r=LocalVision().process(image(clipped:true,smallOutside:true),width:w,height:h,time:0)
        XCTAssertNotNil(r.observed);if let b=r.observed{XCTAssertEqual(b.x,90,accuracy:1);XCTAssertEqual(b.y,150,accuracy:1)}
    }
    func testSmallFeltPatchSurroundedByCurrentGripCannotScore(){
        let core=LocalVision();var seen=0
        for (i,y) in [90.0,105,120,140,160,140,120,105,90].enumerated(){
            var p=image();for py in 65...185{for px in 120...180{p[py*w+px]=0xffbc8b7b}}
            disk(&p,150,y,3,0xffc7df43)
            let r=core.process(p,width:w,height:h,time:Double(i)/60)
            if let b=r.observed{seen+=1;XCTAssertTrue(b.nearHand,"Use the present grip even for a tiny patch")}
            XCTAssertNil(r.impact,"A carried patch is not a floor bounce")
        };XCTAssertGreaterThanOrEqual(seen,8,"Do not pass by losing the visible patch")
    }
    func testWholeVisibleBallImmediatelyReplacesOldTinyFragment(){
        let core=LocalVision()
        for i in 0..<4{var p=image();disk(&p,150,130,3,0xffc7df43);_ = core.process(p,width:w,height:h,time:Double(i)/60)}
        var p=image();for py in 65...115{for px in 105...195{p[py*w+px]=0xffbc8b7b}}
        disk(&p,150,130,36,0xffc7df43)
        let r=core.process(p,width:w,height:h,time:4.0/60)
        XCTAssertNotNil(r.observed,"A tiny prior cannot lock out its resolved owner")
        if let b=r.observed{XCTAssertGreaterThanOrEqual(b.radius,30);XCTAssertTrue(b.nearHand)};XCTAssertNil(r.impact)
    }
    func testSmallFreeBallOverUniformPinkWoodDoesNotInheritAHand(){
        let core=LocalVision();var seen=0
        for (i,y) in [90.0,100,112,126,142,160,142,126,112].enumerated(){
            var p=Array(repeating:UInt32(0xff987e73),count:w*h);disk(&p,150,y,3,0xffc7df43)
            let r=core.process(p,width:w,height:h,time:Double(i)/60)
            if let b=r.observed{seen+=1;XCTAssertFalse(b.nearHand,"Uniform warm background alone is not a bounded hand")}
        };XCTAssertGreaterThanOrEqual(seen,8,"Do not pass by hiding the small ball")
    }
}
