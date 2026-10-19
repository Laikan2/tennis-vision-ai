import XCTest
@testable import TennisVisionCore

/// Known projected arcs; real phone/contact precision still needs physical tests.
final class SixtyFpsFloorImpactTests:XCTestCase {
    let w=320,h=240
    var settings:VisionSettings{var s=VisionSettings();s.polygon=[VisionPoint(0.1,0.1),VisionPoint(0.9,0.1),VisionPoint(0.9,0.96),VisionPoint(0.1,0.96)];s.floorLine=[VisionPoint(0,0.1),VisionPoint(1,0.1)];return s}
    func frame(_ y:Double?,radius:Double=24,hand:Bool=false)->[UInt32]{
        var p=Array(repeating:UInt32(0xff8b8884),count:w*h);guard let y else{return p}
        if hand{for py in 0..<h{for px in 0..<w where Double(px)>=150-radius*1.3 && Double(px)<=150+radius*1.3 && Double(py)>=y-radius*1.9 && Double(py)<=y+radius*0.4{p[py*w+px]=0xffb47b65}}}
        for py in 0..<h{for px in 0..<w where hypot(Double(px)-150,Double(py)-y)<=radius{p[py*w+px]=0xffc7df43}};return p
    }
    func arc(_ t:Double,rebound:Bool=true)->Double{
        let dt=t-0.3
        if dt<=0{return 170+240*dt+400*dt*dt}
        return rebound ? 170-168*dt+400*dt*dt : 170+240*dt+400*dt*dt
    }
    func run(_ fps:Int,config:VisionSettings?=nil,hand:Bool=false,rebound:Bool=true)->([VisionResult],[VisionImpact]){
        let core=LocalVision(settings:config ?? settings)
        let results=(0...Int(Double(fps)*0.52)).map{i in let t=Double(i)/Double(fps);return core.process(frame(arc(t,rebound:rebound),hand:hand),width:w,height:h,time:t)}
        return (results,results.compactMap{$0.impact})
    }
    func testVisibleParabolicBounceAt60FPSProducesMarkAndIncomingSpeed(){
        let (frames,hits)=run(60)
        XCTAssertGreaterThanOrEqual(frames.filter{$0.observed != nil}.count,frames.count-1)
        XCTAssertEqual(hits.count,1);guard let hit=hits.first else{return}
        XCTAssertEqual(hit.call,.inside);XCTAssertEqual(hit.time,0.3,accuracy:0.035);XCTAssertEqual(hit.point.y*Double(h),194.5,accuracy:3)
        XCTAssertNotNil(hit.speed);if let speed=hit.speed{XCTAssertTrue((0.9...1.3).contains(speed))}
    }
    func testSameProjectedArcDoesNotDependOnFrameCount(){
        for fps in [30,120,240]{let (frames,hits)=run(fps)
            XCTAssertGreaterThanOrEqual(frames.filter{$0.observed != nil}.count,frames.count-1);XCTAssertEqual(hits.count,1,"\(fps) FPS")
            if let hit=hits.first{XCTAssertEqual(hit.call,.inside)}
        }
    }
    func testFallingWithoutObservedReturnDoesNotCreateContact(){XCTAssertTrue(run(60,rebound:false).1.isEmpty)}
    func testSameArcCarriedByHandCannotScore(){
        let (frames,hits)=run(60,hand:true);XCTAssertGreaterThanOrEqual(frames.filter{$0.observed != nil}.count,frames.count-1);XCTAssertTrue(hits.isEmpty)
    }
    func testSameTurnClearlyAboveMarkedFloorCannotScore(){var s=settings;s.floorLine=[VisionPoint(0,0.95),VisionPoint(1,0.95)];XCTAssertTrue(run(60,config:s).1.isEmpty)}
    func testLongWindowWithoutFloorReferenceCannotInventContact(){var s=settings;s.polygon=[];s.floorLine=[];XCTAssertTrue(run(60,config:s).1.isEmpty)}
    func testShortLossNearContactKeepsOnlyUncertainCandidate(){
        let core=LocalVision(settings:settings);var hits=[VisionImpact]()
        for i in 0...24{let t=Double(i)/60,y:Double?=(9...15).contains(i) ? nil:180-abs(t-0.2)*600
            if let hit=core.process(frame(y,radius:6),width:w,height:h,time:t).impact{hits.append(hit)}}
        XCTAssertEqual(hits.count,1);if let hit=hits.first{XCTAssertEqual(hit.call,.review);XCTAssertGreaterThanOrEqual(hit.uncertainty,30)}
    }
    func testLongLossCannotJoinUnrelatedLegs(){
        let core=LocalVision(settings:settings)
        for i in 0...28{let t=Double(i)/60,y:Double?=(9...19).contains(i) ? nil:180-abs(t-0.2)*600
            XCTAssertNil(core.process(frame(y,radius:6),width:w,height:h,time:t).impact)}
    }
    func testSlowBounceOutsideAreaKeepsActualMarkAndOUT(){
        var s=settings;s.polygon=[VisionPoint(0.02,0.1),VisionPoint(0.2,0.1),VisionPoint(0.2,0.96),VisionPoint(0.02,0.96)]
        let hits=run(60,config:s).1;XCTAssertEqual(hits.count,1)
        if let hit=hits.first{XCTAssertEqual(hit.call,.outside);XCTAssertEqual(hit.point.x*Double(w),150,accuracy:1);XCTAssertEqual(hit.point.y*Double(h),194.5,accuracy:3)}
    }
    func testContactBetweenFramesUsesObservedLocalPeak(){
        for fps in [60,120,240]{for phase in [1.0/3,2.0/3]{
            let offset=phase/Double(fps),core=LocalVision(settings:settings);var hits=[VisionImpact]()
            for i in 0...Int(Double(fps)*0.58){let t=Double(i)/Double(fps)
                if let hit=core.process(frame(arc(t-offset),radius:20),width:w,height:h,time:t).impact{hits.append(hit)}}
            XCTAssertEqual(hits.count,1,"\(fps) FPS phase \(phase)")
            if let hit=hits.first{XCTAssertEqual(hit.call,.inside);XCTAssertEqual(hit.time,0.3+offset,accuracy:1/Double(fps));XCTAssertEqual(hit.point.y*Double(h),190.5,accuracy:3)}
        }}
    }
    func testKeepingHistoryDoesNotExtendPredictionPast60ms(){
        let core=LocalVision(settings:settings)
        for i in 0...15{let t=Double(i)/60,y:Double?=i>=9 ? nil:180-abs(t-0.2)*600
            let r=core.process(frame(y,radius:6),width:w,height:h,time:t)
            if i>=12{XCTAssertNil(r.predicted)};XCTAssertNil(r.impact)
        }
    }
}
