import XCTest
@testable import TennisVisionCore

final class ContactSurfaceRecoveryTests:XCTestCase {
    let w=320,h=240
    let region=[VisionPoint(0.08,0.08),VisionPoint(0.92,0.08),VisionPoint(0.92,0.95),VisionPoint(0.08,0.95)]
    func frame(_ x:Double,_ y:Double,r:Double=12,hand:Bool=false)->[UInt32] {
        var p=Array(repeating:UInt32(0xff8b8884),count:w*h)
        if hand {for py in 0..<h{for px in 0..<w{if abs(Double(px)-x)<r*1.3 && Double(py)>y-r*1.9 && Double(py)<y+r*0.4{p[py*w+px]=0xffb47b65}}}}
        for py in 0..<h{for px in 0..<w{if hypot(Double(px)-x,Double(py)-y)<=r{p[py*w+px]=0xffc7df43}}}
        return p
    }
    func wall(_ fps:Int,hand:Bool=false,crossing:Bool=false,slow:Bool=false)->[VisionImpact] {
        var s=VisionSettings();s.surface = .wall;s.polygon=region
        let core=LocalVision(settings:s);var hits=[VisionImpact]()
        for i in 0...Int(Double(fps)*0.4) {
            let t=Double(i)/Double(fps),dt=t-0.2,x=160+(dt<=0 || crossing ? dt*(slow ? 75:150) : -dt*(slow ? 65:115))
            if let hit=core.process(frame(x,110,r:slow ? 6:12,hand:hand),width:w,height:h,time:t).impact{hits.append(hit)}
        }
        return hits
    }
    func testWallReturnUsesTimeAndDisplacementAtEveryFrameRate() {
        for fps in [30,60,120,240] {
            let hits=wall(fps);XCTAssertEqual(hits.count,1,"\(fps) FPS")
            guard let hit=hits.first else{continue};XCTAssertEqual(hit.call,.review)
            XCTAssertEqual(hit.point.x*Double(w),160,accuracy:2);XCTAssertEqual(hit.point.y*Double(h),110,accuracy:1)
            XCTAssertEqual(hit.time,0.2,accuracy:1/Double(fps));XCTAssertNotNil(hit.speed)
        }
    }
    func testHandHeldTurnAndStraightPassCannotCreateWallContact() {
        for fps in [60,240]{XCTAssertTrue(wall(fps,hand:true).isEmpty);XCTAssertTrue(wall(fps,crossing:true).isEmpty)}
    }
    func testSlowCleanWallReturnIsNotLostToAPixelSpeedCutoff() {
        for fps in [30,60,120,240]{let hits=wall(fps,slow:true);XCTAssertEqual(hits.count,1,"\(fps) FPS");XCTAssertEqual(hits.first?.call,.review);XCTAssertNotNil(hits.first?.speed)}
    }
    func testWholeImageFloorRestoresBounceExcludedByWrongDiagonal() {
        func run(_ line:[VisionPoint],court:VisionCourt = .custom)->[VisionImpact] {
            var s=VisionSettings();s.polygon=region;s.floorLine=line;s.court=court
            let core=LocalVision(settings:s);var hits=[VisionImpact]()
            for i in 0...24{let t=Double(i)/60;if let hit=core.process(frame(170,110-abs(t-0.2)*350,r:6),width:w,height:h,time:t).impact{hits.append(hit)}}
            return hits
        }
        XCTAssertTrue(run([VisionPoint(0.05,0.05),VisionPoint(0.85,0.95)]).isEmpty)
        for court in [VisionCourt.custom,.full,.half] {
            let hits=run(court == .custom ? VisionFloorBoundaryDraft.wholeImage:[],court:court)
            XCTAssertEqual(hits.count,1);XCTAssertEqual(hits.first?.call,.inside)
            if let hit=hits.first{XCTAssertEqual(hit.point.y*Double(h),116.5,accuracy:2);XCTAssertNotNil(hit.speed)}
        }
    }
    func testFloorShadeMatchesExcludedSideAndWholeFloorHasNoShade() {
        XCTAssertTrue(VisionGeometry.excludedFloorRegion(VisionFloorBoundaryDraft.wholeImage).isEmpty)
        let line=[VisionPoint(0.05,0.05),VisionPoint(0.85,0.95)],shade=VisionGeometry.excludedFloorRegion(line)
        XCTAssertGreaterThanOrEqual(shade.count,3)
        XCTAssertTrue(shade.allSatisfy{(0...1).contains($0.x) && (0...1).contains($0.y)})
        XCTAssertTrue(shade.allSatisfy{(VisionGeometry.floorDistance($0,line,w,h) ?? .infinity)<=0.00001})
        XCTAssertTrue(VisionGeometry.excludedFloorRegion([VisionPoint(0.5,0.1),VisionPoint(0.5,0.9)]).isEmpty)
    }
    func testCompensatedFloorBoundaryRemainsUsableOutsideViewport() {
        var s=VisionSettings();s.polygon=region;s.floorLine=[VisionPoint(0,0),VisionPoint(1,0)]
        let a=s.translated(by:VisionPoint(0.01,-0.01))
        XCTAssertFalse(VisionGeometry.validLine(a.floorLine))
        XCTAssertTrue(VisionGeometry.onFloor(VisionPoint(0.5,0.6),a.floorLine))
        XCTAssertNotNil(VisionGeometry.floorDistance(VisionPoint(0.5,0.6),a.floorLine,w,h))
        s.polygon=[VisionPoint(0,0),VisionPoint(1,0),VisionPoint(1,1),VisionPoint(0,1)]
        let full=s.translated(by:VisionPoint(0.01,-0.01))
        XCTAssertFalse(VisionGeometry.valid(full.polygon));XCTAssertTrue(VisionGeometry.projectedPolygon(full.polygon))
        XCTAssertEqual(VisionGeometry.classify(VisionPoint(0.5,0.6),radius:6,uncertainty:2,polygon:full.polygon,width:w,height:h),.inside)
    }
}
