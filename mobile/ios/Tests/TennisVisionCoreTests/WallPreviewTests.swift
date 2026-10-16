import XCTest
@testable import TennisVisionCore

final class WallPreviewTests:XCTestCase {
    func pixels(_ x:Double?,_ y:Double=180,_ r:Double=6,w:Int=640,h:Int=360,rx:Double?=nil)->[UInt32]{
        var p=Array(repeating:UInt32(0xff315372),count:w*h);let sx=rx ?? r
        if let x{
            for py in max(0,Int(y-r))...min(h-1,Int(y+r)) {for px in max(0,Int(x-sx))...min(w-1,Int(x+sx)){
                let dx=(Double(px)-x)/sx,dy=(Double(py)-y)/r
                if dx*dx+dy*dy<=1{p[py*w+px]=0xffc7eb4d}
            }}
        };return p
    }
    func hits(_ vx:Double,_ vy:Double,_ ux:Double,_ uy:Double,surface:VisionSurface = .wall)->[VisionImpact]{
        var settings=VisionSettings();settings.surface=surface;settings.polygon=[VisionPoint(0.1,0.1),VisionPoint(0.9,0.1),VisionPoint(0.9,0.95),VisionPoint(0.1,0.95)]
        let core=LocalVision(settings:settings)
        return (0...8).compactMap{i in
            let dt=Double(i)-5.4,x=350+dt*(dt<0 ? vx:ux),y=220+dt*(dt<0 ? vy:uy)
            return core.process(pixels(x,y),width:640,height:360,time:Double(i)/60).impact
        }
    }
    func testVerticalWallReturnIsLocatedAndRetainsReview(){
        let events=hits(0,24,0,-24);XCTAssertEqual(events.count,1)
        if let hit=events.first{XCTAssertEqual(hit.call,.review);XCTAssertEqual(hit.point.y*360,220,accuracy:2);XCTAssertNotNil(hit.speed)}
    }
    func testGlancingDiagonalReturnIsLocatedBetweenObservedFrames(){
        let events=hits(30,18,-18,30);XCTAssertEqual(events.count,1)
        if let hit=events.first{XCTAssertEqual(hit.point.x*640,350,accuracy:2);XCTAssertEqual(hit.point.y*360,220,accuracy:2)}
    }
    func testHorizontalReturnIsInterpolatedRatherThanMarkedBeforeTheWall(){
        let events=hits(35,0,-35,0);XCTAssertEqual(events.count,1)
        if let hit=events.first{XCTAssertEqual(hit.point.x*640,350,accuracy:2)}
    }
    func testStraightFlightCannotCreateWallContact(){XCTAssertTrue(hits(30,8,30,8).isEmpty)}
    func testWallReturnCannotCreateAFloorBounce(){XCTAssertTrue(hits(30,18,-18,30,surface:.floor).isEmpty)}
    func testDisappearanceWithoutObservedReturnCannotCreateContact(){
        var settings=VisionSettings();settings.surface = .wall;let core=LocalVision(settings:settings)
        for i in 0...9{XCTAssertNil(core.process(pixels(i<6 ? 100+Double(i)*35:nil),width:640,height:360,time:Double(i)/60).impact)}
    }
    func testPreviewSeesBallBeforeTrainingAndReturnsFullImageCoordinates(){
        let result=VisionPreview().process(pixels(500,300,12,w:1280,h:720),width:1280,height:720,time:0,area:VisionSettings())
        XCTAssertNotNil(result.observed);XCTAssertNil(result.impact);XCTAssertNil(result.speed)
        if let b=result.observed{XCTAssertEqual(b.x,500,accuracy:2);XCTAssertEqual(b.y,300,accuracy:2);XCTAssertEqual(b.diameterX,25,accuracy:3)}
    }
    func testPreviewCannotScoreEvenAKnownCompleteBounce(){
        let preview=VisionPreview()
        for (i,y) in [90,115,150,190,240,290,240,190,150,115].enumerated(){
            let r=preview.process(pixels(500,Double(y),12,w:1280,h:720),width:1280,height:720,time:Double(i)/60,area:VisionSettings())
            XCTAssertNotNil(r.observed);XCTAssertNil(r.impact);XCTAssertNil(r.speed)
        }
    }
    func testPreviewScalingKeepsSmallSignalUncertain(){
        let preview=VisionPreview()
        _ = preview.process(pixels(100,180,4,w:1280,h:720),width:1280,height:720,time:0,area:VisionSettings())
        let b=preview.process(pixels(112,180,4,w:1280,h:720),width:1280,height:720,time:1.0/60,area:VisionSettings()).observed
        XCTAssertNotNil(b);if let b{XCTAssertFalse(b.resolved);XCTAssertTrue(b.colorUncertain)}
    }
    func testMissingBallDoesNotKeepObservedOutline(){
        let preview=VisionPreview()
        XCTAssertNotNil(preview.process(pixels(500,180,12,w:1280,h:720),width:1280,height:720,time:0,area:VisionSettings()).observed)
        XCTAssertNil(preview.process(pixels(nil,w:1280,h:720),width:1280,height:720,time:1.0/60,area:VisionSettings()).observed)
    }
    func testBlurHasBothContourDimensions(){
        let b=LocalVision().process(pixels(300,180,6,rx:14),width:640,height:360,time:0).observed
        XCTAssertNotNil(b);if let b{XCTAssertTrue(b.blurred);XCTAssertGreaterThan(b.diameterX,b.diameterY*1.8)}
    }
    func testCarriedBallCannotCreateWallMark(){
        var settings=VisionSettings();settings.surface = .wall;let core=LocalVision(settings:settings)
        for (i,y) in [70,95,120,145,170,195,170,145,120,95].enumerated(){
            var p=Array(repeating:UInt32(0xff315372),count:640*360)
            for py in y-16...y+16{for px in 304...336{p[py*640+px]=0xffbd8466}}
            for py in y-6...y+6{for px in 314...326{if hypot(Double(px)-320,Double(py-y))<=6{p[py*640+px]=0xffc7eb4d}}}
            XCTAssertNil(core.process(p,width:640,height:360,time:Double(i)/60).impact)
        }
    }
    func testIncoherentTracksCannotCreateAWallPosition(){
        var settings=VisionSettings();settings.surface = .wall;let core=LocalVision(settings:settings)
        let positions=[VisionPoint(100,100),VisionPoint(120,100),VisionPoint(180,180),VisionPoint(120,140),VisionPoint(100,140)]
        for (i,p) in positions.enumerated(){XCTAssertNil(core.process(pixels(p.x,p.y),width:640,height:360,time:Double(i)/60).impact)}
    }
}
