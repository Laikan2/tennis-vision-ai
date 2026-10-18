import XCTest
@testable import TennisVisionCore

final class SessionSettingsTests:XCTestCase {
    func testTogglingImpactVisibilityOrAudioDoesNotInvalidateTheVisibleReference(){
        var original=VisionSettings();original.polygon=[VisionPoint(0,0),VisionPoint(1,0),VisionPoint(1,1),VisionPoint(0,1)]
        var preference=original;preference.marks=false;preference.sound=false
        XCTAssertTrue(preference.usesSameVisibleArea(as:original))
        preference.marks=true;XCTAssertTrue(preference.usesSameVisibleArea(as:original))
        preference.surface = .wall;XCTAssertFalse(preference.usesSameVisibleArea(as:original))
        preference=original;preference.polygon[0].x=0.1;XCTAssertFalse(preference.usesSameVisibleArea(as:original))
    }
    func testOnlyGeometryIsRemovedWhenANewSessionStarts(){
        var value=VisionSettings()
        value.polygon=[VisionPoint(0.1,0.2),VisionPoint(0.8,0.2),VisionPoint(0.8,0.9),VisionPoint(0.1,0.9)]
        value.floorLine=[VisionPoint(0,0.4),VisionPoint(1,0.4)]
        value.floorPolygon=value.polygon;value.wallPolygon=value.polygon
        value.surface = .wall;value.court = .half;value.floorCourt = .full
        value.sound=false;value.marks=false
        let cleared=value.withoutSessionArea
        XCTAssertTrue(cleared.polygon.isEmpty);XCTAssertTrue(cleared.floorLine.isEmpty)
        XCTAssertNil(cleared.floorPolygon);XCTAssertNil(cleared.wallPolygon);XCTAssertNil(cleared.floorCourt)
        XCTAssertEqual(cleared.court,.custom);XCTAssertEqual(cleared.surface,.wall)
        XCTAssertFalse(cleared.sound);XCTAssertFalse(cleared.marks)
        XCTAssertEqual(cleared,cleared.withoutSessionArea)
        XCTAssertEqual(value.polygon.count,4,"Clearing an archive copy must not mutate an active camera's area")
    }
    func testSerializedPersistentPreferencesCannotRestoreAGeometry(){
        var old=VisionSettings();old.polygon=[VisionPoint(0,0),VisionPoint(1,0),VisionPoint(1,1),VisionPoint(0,1)]
        old.floorPolygon=old.polygon;old.wallPolygon=old.polygon;old.sound=false
        let encoded=try! JSONEncoder().encode(old.withoutSessionArea)
        let restored=try! JSONDecoder().decode(VisionSettings.self,from:encoded)
        XCTAssertFalse(VisionGeometry.valid(restored.polygon))
        XCTAssertTrue(restored.selectingSurface(.wall).polygon.isEmpty)
        XCTAssertTrue(restored.selectingSurface(.floor).polygon.isEmpty)
        XCTAssertFalse(restored.sound)
    }
}
