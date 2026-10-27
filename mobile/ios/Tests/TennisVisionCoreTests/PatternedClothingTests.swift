import XCTest
@testable import TennisVisionCore

final class PatternedClothingTests:XCTestCase {
    let w=320,h=240
    func disk(_ p:inout [UInt32],_ x:Double,_ y:Double,_ r:Double,_ color:UInt32) {
        for py in 0..<h{for px in 0..<w where hypot(Double(px)-x,Double(py)-y)<=r{p[py*w+px]=color}}
    }
    func scene(_ y:Double,ball:Bool=false)->[UInt32] {
        var p=Array(repeating:UInt32(0xffa89988),count:w*h)
        for py in 50...205{for px in 20...90{p[py*w+px]=0xff242631}}
        for (dx,dy) in [(0,0),(-13,-9),(14,-7),(-11,13),(13,13)]{disk(&p,55+Double(dx),y+Double(dy),dx==0 ? 4:1.5,0xffb5cf39)}
        if ball{disk(&p,200,100,5,0xff847c28)};return p
    }
    func testDimBallWinsOverPatternedGarment() throws {
        let r=LocalVision().process(scene(130,ball:true),width:w,height:h,time:0)
        let b=try XCTUnwrap(r.observed);XCTAssertEqual(b.x,200,accuracy:2)
        XCTAssertGreaterThan(r.diagnostics.textureRejected,0)
    }
    func testGarmentReversalsAreNotBallContacts() {
        for surface in [VisionSurface.floor,.wall] {
            var s=VisionSettings();s.surface=surface;let core=LocalVision(settings:s)
            for (i,y) in [100,108,118,130,144,130,118,108,100].enumerated(){
                let r=core.process(scene(Double(y)),width:w,height:h,time:Double(i)/60)
                XCTAssertNil(r.observed);XCTAssertNil(r.impact)
            }
        }
    }
    func testBallReacquiresAfterSeparatingFromGarment() {
        let core=LocalVision();var seen=0
        for i in 0...10{var p=scene(130);let x=95+Double(i)*10;disk(&p,x,100,5,0xff847c28)
            let r=core.process(p,width:w,height:h,time:Double(i)/60)
            if let b=r.observed{XCTAssertEqual(b.x,x,accuracy:2);seen+=1}
        };XCTAssertGreaterThanOrEqual(seen,8)
    }
}
