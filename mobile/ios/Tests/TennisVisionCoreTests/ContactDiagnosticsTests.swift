import XCTest
@testable import TennisVisionCore

final class ContactDiagnosticsTests:XCTestCase {
    let w=640,h=360
    let polygon=[VisionPoint(0.1,0.1),VisionPoint(0.9,0.1),VisionPoint(0.9,0.95),VisionPoint(0.1,0.95)]
    func frame(_ x:Double,_ y:Double,_ r:Double=6)->[UInt32]{
        (0..<w*h).map{i in hypot(Double(i%w)-x,Double(i/w)-y)<=r ? 0xffc7eb4d:0xff315372}
    }
    func testFloorCannotBeReportedAsWall() {
        for surface in [VisionSurface.floor,.wall] {
            var s=VisionSettings();s.surface=surface;s.polygon=polygon;s.floorLine=[VisionPoint(0,0.4),VisionPoint(1,0.4)]
            let core=LocalVision(settings:s);var hits=[VisionImpact](),rejected=false
            for (i,y) in [100,125,150,175,200,175,150,125,100].enumerated(){
                let r=core.process(frame(300,Double(y)),width:w,height:h,time:Double(i)/60)
                if let hit=r.impact{hits.append(hit)};if r.diagnostics.contactReason.contains("modo parede"){rejected=true}
            }
            if surface == .floor{XCTAssertEqual(hits.count,1);XCTAssertEqual(hits.first?.evidence,.confirmedFloor)}
            else{XCTAssertTrue(hits.isEmpty);XCTAssertTrue(rejected)}
        }
    }
    func testFastBallRetainsIdentityAndWallCandidate() throws {
        var s=VisionSettings();s.surface = .wall;s.polygon=polygon
        let core=LocalVision(settings:s);var hits=[VisionImpact](),ids=Set<Int>(),seen=0
        for (i,x) in [100,220,340,460,580,460,340,220,100].enumerated(){
            let r=core.process(frame(Double(x),180,4),width:w,height:h,time:Double(i)/60)
            if r.observed != nil{seen+=1;if let id=r.diagnostics.trackId{ids.insert(id)}}
            if let hit=r.impact{hits.append(hit)}
        }
        XCTAssertEqual(seen,9);XCTAssertEqual(ids.count,1);XCTAssertEqual(hits.count,1)
        let hit=try XCTUnwrap(hits.first);XCTAssertEqual(hit.evidence,.candidate);XCTAssertEqual(hit.point.x*Double(w),580,accuracy:2)
        XCTAssertTrue((180...220).contains(try XCTUnwrap(hit.speed)))
    }
    func testPredictionExpiresAndNewTrackIdIsIssued() throws {
        var s=VisionSettings();s.surface = .track;let core=LocalVision(settings:s)
        let first=core.process(frame(100,150),width:w,height:h,time:0).diagnostics.trackId
        _=core.process(frame(108,150),width:w,height:h,time:0.016)
        let empty=Array(repeating:UInt32(0xff315372),count:w*h)
        XCTAssertNotNil(core.process(empty,width:w,height:h,time:0.032).predicted)
        let gone=core.process(empty,width:w,height:h,time:0.20);XCTAssertNil(gone.predicted);XCTAssertNil(gone.impact)
        let next=core.process(frame(108,150),width:w,height:h,time:0.23)
        XCTAssertNotNil(next.observed);XCTAssertNotEqual(first,next.diagnostics.trackId)
    }
}
