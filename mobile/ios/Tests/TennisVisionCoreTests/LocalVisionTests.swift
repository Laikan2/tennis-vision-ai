import XCTest
@testable import TennisVisionCore

final class LocalVisionTests: XCTestCase {
    private let w=640,h=360
    private let area=[VisionPoint(0.2,0.3),VisionPoint(0.7,0.3),VisionPoint(0.7,0.95),VisionPoint(0.2,0.95)]
    private func pixels(_ x: Int?,_ y: Int=180,_ radius: Int=6,_ bg: UInt32=0xff315372) -> [UInt32] {
        var p=Array(repeating:bg,count:w*h)
        if let x{for py in max(0,y-radius)...min(h-1,y+radius){for px in max(0,x-radius)...min(w-1,x+radius){if hypot(Double(px-x),Double(py-y))<=Double(radius){p[py*w+px]=0xffc7eb4d}}}}
        return p
    }
    func testSmallBallOnSampledChroma420Backgrounds() {
        for c in VisionDiagnostics.cases where !c.clutter && !c.bounce {
            let r=VisionDiagnostics.evaluate(c);print("VISION_FIXTURE \(r.name) \(r.located)/\(r.frames) wrong=\(r.wrong)")
            XCTAssertGreaterThanOrEqual(r.located,14,c.name);XCTAssertEqual(r.wrong,0,c.name);XCTAssertTrue(r.contacts.isEmpty,c.name)
        }
    }
    func testSmallMovingBallCanRecoverBesideStaticObject() {
        let c=VisionDiagnostics.cases.first{$0.clutter}!,r=VisionDiagnostics.evaluate(c)
        XCTAssertGreaterThanOrEqual(r.located,13);XCTAssertLessThanOrEqual(r.wrong,2)
    }
    func testTinyContactIsReviewAndHasNoSpeed() {
        let c=VisionDiagnostics.cases.first{$0.bounce}!,r=VisionDiagnostics.evaluate(c)
        XCTAssertEqual(r.contacts,[.review])
    }
    func testKnownInOutContactsAndNoDuplicates() {
        for (x,call) in [(280,VisionCall.inside),(580,.outside)] {
            var settings=VisionSettings();settings.polygon=area
            let core=LocalVision(settings:settings);var hits=[VisionImpact]()
            for (i,y) in [140,160,185,215,245,280,245,215,185,160].enumerated(){if let event=core.process(pixels(x,y),width:w,height:h,time:Double(i)/60).impact{hits.append(event)}}
            XCTAssertEqual(hits.count,1);XCTAssertEqual(hits.first?.call,call)
            XCTAssertEqual(hits.first!.point.x,Double(x)/Double(w),accuracy:0.01)
        }
    }
    func testFallNeedsAnObservedReturn() {
        let core=LocalVision()
        for (i,y) in [100,130,160,195,230,270,310].enumerated(){XCTAssertNil(core.process(pixels(320,y),width:w,height:h,time:Double(i)/60).impact)}
    }
    func testCarriedBallDoesNotScore() {
        var settings=VisionSettings();settings.polygon=area
        let core=LocalVision(settings:settings)
        for (i,y) in [110,130,150,180,210,240,210,180,150,130].enumerated(){
            var p=pixels(nil)
            for py in y-24...y+24{for px in 290...350{p[py*w+px]=0xffbd8466}}
            let ball=pixels(320,y,10)
            for py in y-10...y+10{for px in 310...330{if ball[py*w+px]==0xffc7eb4d{p[py*w+px]=0xffc7eb4d}}}
            let r=core.process(p,width:w,height:h,time:Double(i)/60);XCTAssertNotNil(r.observed);XCTAssertNil(r.impact)
        }
    }
    func testFingerSplitIsMarkedOccluded() {
        let core=LocalVision()
        var p=pixels(320,180,12)
        for y in 166...194{for x in 318...322{p[y*w+x]=0xffbd8466}}
        let r=core.process(p,width:w,height:h,time:0)
        XCTAssertNotNil(r.observed);XCTAssertTrue(r.observed?.occluded==true);XCTAssertNil(r.speed);XCTAssertNil(r.impact)
    }
    func testStaticTwoPixelReflectionDoesNotStartATrack() {
        let core=LocalVision();var p=pixels(nil)
        for y in 200...201{for x in 400...401{p[y*w+x]=0xffc7eb4d}}
        for i in 0..<20{let r=core.process(p,width:w,height:h,time:Double(i)/60);XCTAssertNil(r.observed);XCTAssertNil(r.predicted);XCTAssertNil(r.impact)}
    }
    func testPredictionsNeverCreateAnImpactAndExpire() {
        let core=LocalVision()
        for i in 0..<5{_ = core.process(pixels(100+i*10),width:w,height:h,time:Double(i)/60)}
        var r=core.process(pixels(nil),width:w,height:h,time:5.0/60);XCTAssertNil(r.observed);XCTAssertNotNil(r.predicted);XCTAssertNil(r.impact)
        r=core.process(pixels(nil),width:w,height:h,time:0.25);XCTAssertNil(r.predicted);XCTAssertTrue(r.trail.isEmpty)
    }
    func testTimestampRepetitionAndInvalidFramesAreDiscarded() {
        let core=LocalVision();_ = core.process(pixels(320),width:w,height:h,time:1)
        XCTAssertNil(core.process(pixels(320),width:w,height:h,time:1).observed)
        XCTAssertNil(core.process([],width:w,height:h,time:2).observed)
        XCTAssertNil(core.process(pixels(320),width:w,height:h,time:.nan).observed)
    }
    func testLineFootprintAndUncertainty() {
        XCTAssertEqual(VisionGeometry.classify(VisionPoint(0.701,0.5),radius:6,uncertainty:0,polygon:area,width:w,height:h),.inside)
        XCTAssertEqual(VisionGeometry.classify(VisionPoint(0.71,0.5),radius:6,uncertainty:4,polygon:area,width:w,height:h),.review)
        XCTAssertEqual(VisionGeometry.classify(VisionPoint(0.85,0.5),radius:6,uncertainty:2,polygon:area,width:w,height:h),.outside)
    }
    func testAreaValidationAndPersistence() throws {
        XCTAssertTrue(VisionGeometry.valid(area));XCTAssertFalse(VisionGeometry.valid([area[0],area[2],area[1],area[3]]))
        var settings=VisionSettings();settings.polygon=area;settings.surface = .wall;settings.sound=false
        XCTAssertEqual(try JSONDecoder().decode(VisionSettings.self,from:JSONEncoder().encode(settings)),settings)
        XCTAssertEqual(VisionGeometry.guides(area,court:.half).count,4)
    }
    func testNoiseAndWhiteGlareDoNotBecomeGreenBall() {
        let core=LocalVision()
        for i in 0..<16{var p=pixels(nil,180,6,0xff8b8b8b);VisionDiagnostics.spot(&p,x:Double(60+i*25),y:180,diameter:4,color:0xffffffff);let r=core.process(p,width:w,height:h,time:Double(i)/60);XCTAssertNil(r.observed);XCTAssertNil(r.impact)}
    }
}
