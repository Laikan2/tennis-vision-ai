import XCTest
@testable import TennisVisionCore

final class IndoorRegressionTests:XCTestCase {
    let w=320,h=240
    func pixels(x:Int=160,y:Int=120,r:Int=12,ball:Bool=true)->[UInt32] {
        var p=Array(repeating:UInt32(0xff9a9590),count:w*h)
        if ball{for py in max(0,y-r)...min(h-1,y+r){for px in max(0,x-r)...min(w-1,x+r){
            if hypot(Double(px-x),Double(py-y))<=Double(r){
                let shade=min(1,max(0,Double(px-x+r)/Double(2*r)))
                let red=UInt32(134+82*shade),green=UInt32(98+81*shade),blue=UInt32(4+25*shade)
                p[py*w+px]=0xff000000 | (red<<16) | (green<<8) | blue
            }
        }}}
        return p
    }
    func testWarmYellowFeltRemainsCenteredWhileMoving(){
        let core=LocalVision();var located=0
        for i in 0..<20{let x=50+i*10
            if let b=core.process(pixels(x:x),width:w,height:h,time:Double(i)/60).observed,hypot(b.x-Double(x),b.y-120)<3{located+=1}
        }
        XCTAssertGreaterThanOrEqual(located,19)
    }
    func testCloseBallIsVisibleBeforeThrowing(){
        let b=LocalVision().process(pixels(r:42),width:w,height:h,time:0).observed
        XCTAssertNotNil(b);if let b{XCTAssertEqual(b.x,160,accuracy:3);XCTAssertEqual(b.y,120,accuracy:3)}
    }
    func testWarmFeltFloorDecisionsAreKnown(){
        for (x,call) in [(140,VisionCall.inside),(285,.outside)]{
            var s=VisionSettings();s.polygon=[VisionPoint(0.2,0.3),VisionPoint(0.7,0.3),VisionPoint(0.7,0.95),VisionPoint(0.2,0.95)]
            s.floorLine=[VisionPoint(0,0.3),VisionPoint(1,0.3)]
            let core=LocalVision(settings:s)
            let hits=[70,85,105,130,160,195,160,130,105,85].enumerated().compactMap{i,y in core.process(pixels(x:x,y:y,r:6),width:w,height:h,time:Double(i)/30).impact}
            XCTAssertEqual(hits.count,1);XCTAssertEqual(hits.first?.call,call)
        }
    }
    func testTinyWallFragmentsDoNotVetoAResolvedWarmBall(){
        var p=pixels(r:42)
        for y in 35...39{for x in 35...39{if hypot(Double(x)-37,Double(y)-37)<=2{p[y*w+x]=0xffc7eb4d}}}
        let b=LocalVision().process(p,width:w,height:h,time:0).observed
        XCTAssertNotNil(b);if let b{XCTAssertEqual(b.x,160,accuracy:3)}
    }
    func testDisconnectedTinyFragmentsDoNotAccumulateReviewContacts(){
        var s=VisionSettings();s.court = .full;s.polygon=[VisionPoint(0.1,0.1),VisionPoint(0.9,0.1),VisionPoint(0.9,0.95),VisionPoint(0.1,0.95)]
        let core=LocalVision(settings:s),ys=[70,85,105,130,160,195,160,130,105,85],xs=[95,100,105,110,115,120,180,240,245,250]
        for i in ys.indices{var p=pixels(ball:false)
            for y in ys[i]...ys[i]+1{for x in xs[i]...xs[i]+1{p[y*w+x]=0xffc7eb4d}}
            XCTAssertNil(core.process(p,width:w,height:h,time:Double(i)/60).impact)
        }
    }
    func testSkinWoodAndWarmRectanglesDoNotScore(){
        for color:UInt32 in [0xffbc8062,0xffbc9062,0xff3b2921,0xffb8920a]{
            let core=LocalVision()
            for i in 0..<12{var p=pixels(ball:false);for y in 75...100{for x in 30+i*12...55+i*12{p[y*w+x]=color}}
                XCTAssertNil(core.process(p,width:w,height:h,time:Double(i)/30).impact)
            }
        }
    }
    var room:[UInt32]{
        var p=[UInt32]();p.reserveCapacity(w*h)
        for y in 0..<h{for x in 0..<w{
            let cell=(x/40+y/30)%3
            let v=UInt32(60+cell*45)
            p.append(UInt32(0xff000000) | (v<<16) | (v<<8) | v)
        }}
        return p
    }
    func testObstructionRecoversOnlyWhenOriginalViewReturns(){
        let g=VisionSceneReference(),p=room,hand=Array(repeating:UInt32(0xffbc8062),count:w*h)
        g.update(p,width:w,height:h,time:0,changed:false)
        g.update(hand,width:w,height:h,time:0.1,changed:true);XCTAssertTrue(g.blocked)
        g.update(hand,width:w,height:h,time:1,changed:false);XCTAssertTrue(g.blocked);XCTAssertTrue(g.needsConfirmation)
        g.update(p,width:w,height:h,time:1.1,changed:false);XCTAssertFalse(g.blocked);XCTAssertFalse(g.needsConfirmation)
    }
    func testDifferentViewCannotResumeInOutJustBecauseItStoppedMoving(){
        let g=VisionSceneReference(),p=room,changed=Array(room.reversed())
        g.update(p,width:w,height:h,time:0,changed:false);g.update(changed,width:w,height:h,time:0.1,changed:true)
        g.update(changed,width:w,height:h,time:1,changed:false);XCTAssertTrue(g.blocked);XCTAssertTrue(g.needsConfirmation)
        g.reset();g.update(changed,width:w,height:h,time:2,changed:false);XCTAssertFalse(g.blocked)
    }
    func testPlayerInPartOfTheFrameDoesNotPreventRecovery(){
        let g=VisionSceneReference(),p=room
        g.update(p,width:w,height:h,time:0,changed:false)
        g.update(Array(repeating:UInt32(0xffbc8062),count:w*h),width:w,height:h,time:0.1,changed:true)
        var restored=p;for y in 60...140{for x in 120...200{restored[y*w+x]=0xffbc8062}}
        g.update(restored,width:w,height:h,time:0.5,changed:false);XCTAssertFalse(g.blocked)
    }
    func testImpactRemainsSuppressedDuringSceneRecovery(){
        let core=LocalVision(),p=room
        _ = core.process(p,width:w,height:h,time:0)
        let covered=core.process(Array(repeating:UInt32(0xffbc8062),count:w*h),width:w,height:h,time:0.1)
        XCTAssertTrue(covered.movingCamera);XCTAssertNil(covered.impact)
        XCTAssertNil(core.process(p,width:w,height:h,time:1).impact)
        let stable=core.process(p,width:w,height:h,time:1.5)
        XCTAssertFalse(stable.movingCamera);XCTAssertFalse(stable.areaNeedsConfirmation)
    }
}

