import XCTest
@testable import TennisVisionCore
final class LineVoiceTests:XCTestCase {
    func testUncertainAndRejectedContactsStaySilent(){
        for call in [VisionCall.review,.unmarked,.rejected]{XCTAssertNil(LineVoicePolicy.voice(for:call,outEnabled:true,inEnabled:true))}
    }
    func testIndependentPreferences(){
        XCTAssertEqual(LineVoicePolicy.voice(for:.outside,outEnabled:true,inEnabled:false),.outside)
        XCTAssertNil(LineVoicePolicy.voice(for:.inside,outEnabled:true,inEnabled:false))
        XCTAssertEqual(LineVoicePolicy.voice(for:.inside,outEnabled:false,inEnabled:true),.inside)
        XCTAssertNil(LineVoicePolicy.voice(for:.outside,outEnabled:false,inEnabled:true))
    }
    func testOutHasPriorityWithoutQueuedCalls(){
        XCTAssertTrue(LineVoicePolicy.canStart(.outside,current:.inside))
        XCTAssertFalse(LineVoicePolicy.canStart(.inside,current:.outside))
        XCTAssertFalse(LineVoicePolicy.canStart(.outside,current:.outside))
        XCTAssertTrue(LineVoicePolicy.canStart(.inside,current:nil))
    }
}
