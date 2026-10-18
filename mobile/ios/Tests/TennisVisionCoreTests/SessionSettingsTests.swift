import XCTest
@testable import TennisVisionCore

final class SessionSettingsTests:XCTestCase {
    func testLateEmptyOrPriorSurfaceFrameCannotEraseSavedFloorOrWall(){
        var current=VisionSettings()
        current.polygon=[VisionPoint(0.2,0.1),VisionPoint(0.8,0.1),VisionPoint(0.8,0.5),VisionPoint(0.2,0.5)]
        current.wallPolygon=current.polygon;current.surface = .wall
        current.floorPolygon=[VisionPoint(0.1,0.6),VisionPoint(0.9,0.6),VisionPoint(0.9,0.95),VisionPoint(0.1,0.95)]
        current.floorCourt = .custom
        for stale in [VisionSettings(),current.selectingSurface(.floor)] {
            var frame=VisionResult();frame.area=stale;frame.cameraOffset=VisionPoint(0.02,0.03);frame.areaNeedsConfirmation=true
            let visible=frame.referenced(to:current)
            XCTAssertEqual(visible.area,current);XCTAssertEqual(visible.cameraOffset,VisionPoint(0,0))
            XCTAssertFalse(visible.areaNeedsConfirmation)
            XCTAssertEqual(visible.area.selectingSurface(.floor).polygon,current.floorPolygon)
        }
        var missingFloor=current;missingFloor.floorPolygon=nil
        XCTAssertFalse(current.acceptsFrameArea(missingFloor,offset:VisionPoint(0,0)))
    }
    func testCurrentTranslatedFrameKeepsGeometryAndLatestPreferences(){
        var current=VisionSettings();current.polygon=[VisionPoint(0.1,0.5),VisionPoint(0.9,0.5),VisionPoint(0.9,0.9),VisionPoint(0.1,0.9)]
        current=current.rememberingArea();current.marks=false;current.sound=false
        let offset=VisionPoint(0.03,-0.02)
        var frame=VisionResult();frame.area=current.translated(by:offset);frame.area.marks=true;frame.area.sound=true;frame.cameraOffset=offset
        let visible=frame.referenced(to:current)
        XCTAssertEqual(visible.area,current.translated(by:offset));XCTAssertEqual(visible.cameraOffset,offset)
        XCTAssertFalse(current.acceptsFrameArea(frame.area,offset:VisionPoint(.nan,0)))
    }
    func testStaleAreaContactIsNotCountedAsACurrentContact(){
        var current=VisionSettings();current.surface = .wall
        var frame=VisionResult();frame.impact=VisionImpact(id:1,time:1,point:VisionPoint(0.2,0.8),radius:5,call:.outside,speed:nil,uncertainty:1,reason:"Old floor event",surface:.floor)
        XCTAssertNil(frame.referenced(to:current).impact)
        XCTAssertNotNil(frame.referenced(to:VisionSettings()).impact)
    }
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
