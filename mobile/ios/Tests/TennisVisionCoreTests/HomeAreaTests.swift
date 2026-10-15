import XCTest
@testable import TennisVisionCore

final class HomeAreaTests:XCTestCase {
    let floor=[VisionPoint(0.1,0.6),VisionPoint(0.9,0.6),VisionPoint(0.9,0.95),VisionPoint(0.1,0.95)]
    let wall=[VisionPoint(0.2,0.1),VisionPoint(0.8,0.1),VisionPoint(0.8,0.5),VisionPoint(0.2,0.5)]
    let boundary=[VisionPoint(0.05,0.55),VisionPoint(0.95,0.55)]
    func testFloorNeedsAreaAndBoundaryBeforeSaving(){
        var setup=VisionHomeAreaSetup(settings:VisionSettings(),surface:.floor)
        XCTAssertFalse(setup.accept([floor[0],floor[1]]));XCTAssertNil(setup.completedSettings)
        XCTAssertTrue(setup.accept(floor));XCTAssertEqual(setup.stage,.floorBoundary);XCTAssertNil(setup.completedSettings)
        XCTAssertFalse(setup.accept([boundary[0],boundary[0]]));XCTAssertEqual(setup.stage,.floorBoundary)
        XCTAssertTrue(setup.accept(boundary));XCTAssertEqual(setup.completedSettings?.polygon,floor)
        XCTAssertEqual(setup.completedSettings?.floorLine,boundary);XCTAssertFalse(setup.accept(boundary))
    }
    func testWallDoesNotOverwriteFloorOrItsLine(){
        var original=VisionSettings();original.polygon=floor;original.floorLine=boundary
        var setup=VisionHomeAreaSetup(settings:original,surface:.wall)
        XCTAssertTrue(setup.accept(wall))
        let saved=setup.completedSettings!
        XCTAssertEqual(saved.surface,.wall);XCTAssertEqual(saved.polygon,wall);XCTAssertEqual(saved.floorLine,boundary)
        let returned=saved.selectingSurface(.floor)
        XCTAssertEqual(returned.polygon,floor);XCTAssertEqual(returned.selectingSurface(.wall).polygon,wall)
        XCTAssertEqual(original.polygon,floor);XCTAssertNil(original.wallPolygon)
    }
    func testCancelDuringFloorStepCannotChangeWall(){
        var original=VisionSettings();original.surface = .wall;original.polygon=wall
        var draft=VisionHomeAreaSetup(settings:original,surface:.floor)
        XCTAssertTrue(draft.accept(floor));XCTAssertNil(draft.completedSettings)
        XCTAssertEqual(original.surface,.wall);XCTAssertEqual(original.polygon,wall);XCTAssertNil(original.floorPolygon)
    }
    func testCourtLayoutAndTrackingRoundTripKeepEachArea(){
        var s=VisionSettings();s.polygon=floor;s.court = .half;s.floorLine=boundary
        var wallSetup=VisionHomeAreaSetup(settings:s,surface:.wall);XCTAssertTrue(wallSetup.accept(wall))
        let track=wallSetup.completedSettings!.selectingSurface(.track)
        XCTAssertEqual(track.selectingSurface(.floor).court,.half)
        XCTAssertEqual(track.selectingSurface(.floor).polygon,floor)
        XCTAssertEqual(track.selectingSurface(.wall).polygon,wall)
    }
    func testLegacyArchiveDecodesAndKeepsItsCurrentSurface(){
        let data=Data(#"{"polygon":[{"x":0.2,"y":0.1},{"x":0.8,"y":0.1},{"x":0.8,"y":0.5},{"x":0.2,"y":0.5}],"floorLine":[],"surface":"wall","court":"custom","sound":true,"marks":true}"#.utf8)
        let s=try! JSONDecoder().decode(VisionSettings.self,from:data)
        XCTAssertEqual(s.polygon,wall);XCTAssertNil(s.floorPolygon);XCTAssertNil(s.wallPolygon)
        XCTAssertEqual(s.selectingSurface(.floor).selectingSurface(.wall).polygon,wall)
    }
    func testStoredSurfaceAreasSurviveJsonRoundTrip(){
        var s=VisionSettings();s.polygon=floor;s.floorLine=boundary
        var setup=VisionHomeAreaSetup(settings:s,surface:.wall);XCTAssertTrue(setup.accept(wall))
        let saved=setup.completedSettings!,data=try! JSONEncoder().encode(saved)
        let restored=try! JSONDecoder().decode(VisionSettings.self,from:data)
        XCTAssertEqual(saved,restored);XCTAssertEqual(restored.selectingSurface(.floor).polygon,floor)
    }
    func testCameraTranslationRebasesBothAreasAndBoundary(){
        var s=VisionSettings();s.polygon=floor;s.floorLine=boundary
        var setup=VisionHomeAreaSetup(settings:s,surface:.wall);XCTAssertTrue(setup.accept(wall))
        let shifted=setup.completedSettings!.translated(by:VisionPoint(0.01,-0.02))
        XCTAssertEqual(shifted.polygon[0].x,0.21,accuracy:1e-8)
        XCTAssertEqual(shifted.selectingSurface(.floor).polygon[0].y,0.58,accuracy:1e-8)
        XCTAssertEqual(shifted.floorLine[0].y,0.53,accuracy:1e-8)
    }
}
