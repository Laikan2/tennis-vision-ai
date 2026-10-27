import XCTest
@testable import TennisVisionCore

final class ContactClockTests:XCTestCase {
    func testNextBounceDoesNotEraseEarlierMarkersBeforeChosenDuration() {
        var clock=VisionContactClock()
        clock.record(1,at:100);clock.record(2,at:102);clock.record(3,at:104)
        XCTAssertEqual(clock.arrivals.count,3)
        XCTAssertTrue(VisionContactClock.visible(arrival:clock.arrivals[1],now:104.9,duration:5))
        XCTAssertFalse(VisionContactClock.visible(arrival:clock.arrivals[1],now:105,duration:5))
        XCTAssertTrue(VisionContactClock.visible(arrival:clock.arrivals[2],now:105,duration:5))
        XCTAssertTrue(VisionContactClock.visible(arrival:clock.arrivals[1],now:1000,duration:0))
    }
    func testReDrawingTheSameEventDoesNotRestartItsLifetime() {
        var clock=VisionContactClock();clock.record(7,at:100);clock.record(7,at:103)
        XCTAssertEqual(clock.arrivals[7],100)
        clock.reset();XCTAssertTrue(clock.arrivals.isEmpty)
        XCTAssertFalse(VisionContactClock.visible(arrival:nil,now:100,duration:5))
    }
}

