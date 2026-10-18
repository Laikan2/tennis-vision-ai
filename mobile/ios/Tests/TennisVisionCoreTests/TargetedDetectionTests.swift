import XCTest
@testable import TennisVisionCore

/// Known pixels and trajectories, not a physical match accuracy claim.
final class TargetedDetectionTests:XCTestCase {
    let w=320,h=240
    var settings:VisionSettings {
        var s=VisionSettings();s.polygon=[VisionPoint(0.2,0.35),VisionPoint(0.7,0.35),VisionPoint(0.7,0.95),VisionPoint(0.2,0.95)]
        s.floorLine=[VisionPoint(0,0.3),VisionPoint(1,0.3)];return s
    }
    func frame(x:Double=150,y:Double=130,r:Double=12,seam:Int=0,hand:Bool=false)->[UInt32] {
        var p=Array(repeating:UInt32(0xff8b8884),count:w*h)
        if hand {for py in max(0,Int(y-r*0.4))...min(h-1,Int(y+r*2.5)) {for px in max(0,Int(x-r*1.9))...min(w-1,Int(x+r*1.9)) {p[py*w+px]=0xffb78063}}}
        for py in max(0,Int(y-r))...min(h-1,Int(y+r)) {for px in max(0,Int(x-r))...min(w-1,Int(x+r)) {
            if hypot(Double(px)-x,Double(py)-y)<=r {p[py*w+px]=seam>0 && abs(Double(px)-x)<Double(seam)*0.5 ? 0xff373737:0xffc7df43}
        }};return p
    }
    func testNarrowDarkPrintedSeamDoesNotSplitOneHeldBallIntoAmbiguousTargets(){
        for r in [12.0,25,38] {for seam in [1,3] {
            let core=LocalVision(settings:settings);var seen=0
            for i in 0..<12 {let result=core.process(frame(r:r,seam:seam,hand:true),width:w,height:h,time:Double(i)/60)
                if let b=result.observed {XCTAssertEqual(b.x,150,accuracy:2);XCTAssertEqual(b.y,130,accuracy:2);seen+=1};XCTAssertNil(result.impact)
            };XCTAssertEqual(seen,12,"radius=\(r) seam=\(seam)")
        }}
    }
    func testTwoSeparateRoundBallsRemainAmbiguous(){
        let core=LocalVision(settings:settings)
        for i in 0..<8 {var p=frame(x:130);let q=frame(x:159)
            for k in p.indices where q[k] != 0xff8b8884 {p[k]=q[k]}
            let r=core.process(p,width:w,height:h,time:Double(i)/60);XCTAssertNil(r.observed);XCTAssertNil(r.impact)
        }
    }
    func testResolvedCloseBallAboveOldShortDimensionLimitIsVisible(){
        let b=LocalVision(settings:settings).process(frame(r:53),width:w,height:h,time:0).observed
        XCTAssertNotNil(b);if let b {XCTAssertEqual(b.x,150,accuracy:2);XCTAssertEqual(b.y,130,accuracy:2)}
    }
    func testVividFeltIsNotVetoedByASeparateDullGreenLeaf(){
        var p=frame();for y in 54...66 {for x in 44...56 {if hypot(Double(x)-50,Double(y)-60)<=6{p[y*w+x]=0xff3f4928}}}
        let b=LocalVision(settings:settings).process(p,width:w,height:h,time:0).observed
        XCTAssertNotNil(b);if let b {XCTAssertEqual(b.x,150,accuracy:2);XCTAssertEqual(b.y,130,accuracy:2)}
    }
    func testIsolatedStationaryLowChromaWarmFragmentDoesNotAcquire(){
        var p=Array(repeating:UInt32(0xff918c84),count:w*h)
        for y in 94...106{for x in 144...156{if hypot(Double(x)-150,Double(y)-100)<=6{p[y*w+x]=0xffac9866}}}
        let core=LocalVision(settings:settings)
        for i in 0..<12{let r=core.process(p,width:w,height:h,time:Double(i)/60);XCTAssertNil(r.observed);XCTAssertNil(r.impact)}
    }
    func testResolvedDesaturatedGreenBallIsVisibleStationaryAndMovingSlowly(){
        for step in [0.0,1.0]{let core=LocalVision(settings:settings)
            for i in 0..<20{let x=100+Double(i)*step;var p=frame(x:x)
                for k in p.indices where p[k]==0xffc7df43{p[k]=0xff78966e}
                let r=core.process(p,width:w,height:h,time:Double(i)/60)
                XCTAssertNotNil(r.observed,"Desaturated green step=\(step) frame=\(i)")
                if let b=r.observed{XCTAssertEqual(b.x,x,accuracy:1);XCTAssertEqual(b.y,130,accuracy:1)};XCTAssertNil(r.impact)
            }
        }
    }
    func testSameLowDisplacementBounceCountsOnceAtEachCaptureRate(){
        for fps in [30,60,120,240] {let core=LocalVision(settings:settings);var hits=[VisionImpact]()
            for i in 0...Int(Double(fps)*0.4) {let t=Double(i)/Double(fps),y=180-abs(t-0.2)*120
                if let hit=core.process(frame(y:y,r:6),width:w,height:h,time:t).impact {hits.append(hit)}
            };XCTAssertEqual(hits.count,1,"\(fps) FPS observed bounce")
            if let hit=hits.first {XCTAssertEqual(hit.call,.inside);XCTAssertEqual(hit.point.y*Double(h),186.5,accuracy:2)}
        }
    }
    func testExtendedHistoryDoesNotScoreJitterStationaryBallOrStraightFall(){
        for mode in 0...2 {let core=LocalVision(settings:settings)
            for i in 0..<100 {let y=mode==0 ? 180:mode==1 ? 180+sin(Double(i)*0.6)*1.2:130+Double(i)*0.5
                XCTAssertNil(core.process(frame(y:y,r:6),width:w,height:h,time:Double(i)/240).impact)
            }
        }
    }
    func testLowDisplacementCarriedTurnAndTurnAboveFloorDoNotScore(){
        for hand in [true,false] {var s=settings
            if !hand{s.floorLine=[VisionPoint(0,0.85),VisionPoint(1,0.85)]}
            let core=LocalVision(settings:s)
            for i in 0..<97 {let t=Double(i)/240,y=180-abs(t-0.2)*120
                XCTAssertNil(core.process(frame(y:y,r:6,hand:hand),width:w,height:h,time:t).impact)
            }
        }
    }
    func testMixedWoodFloorAndNeutralWallAreNotAHandAtContact(){
        let core=LocalVision(settings:settings);var hits=[VisionImpact]()
        for (i,y) in [145,154,165,174,180,174,165,154,145].enumerated(){var p=frame(y:Double(y),r:6)
            for py in 172..<h{for px in 0..<w where p[py*w+px]==0xff8b8884{p[py*w+px]=0xffbc9062}}
            if let hit=core.process(p,width:w,height:h,time:Double(i)/60).impact{hits.append(hit)}
        };XCTAssertEqual(hits.count,1);if let hit=hits.first{XCTAssertEqual(hit.call,.inside)}
    }
}
