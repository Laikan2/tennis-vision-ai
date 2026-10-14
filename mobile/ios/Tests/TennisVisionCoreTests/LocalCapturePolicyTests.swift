import XCTest
@testable import TennisVisionCore
final class LocalCapturePolicyTests:XCTestCase {
    private func p(_ h:Int,_ fps:Int)->CaptureProfile{CaptureProfile(width:h==720 ? 1280:1920,height:h,fps:fps,bitrate:8_000_000)}
    func testHighRatesOnlyComeFromCameraFormats(){
        let available=[p(720,240),p(720,120),p(720,60),p(1080,60)]
        XCTAssertEqual(LocalCapturePolicy.select(available,height:720,rate:240)?.fps,240)
        XCTAssertEqual(LocalCapturePolicy.select(available,height:1080,rate:120)?.height,720)
        XCTAssertNil(LocalCapturePolicy.select([p(1080,60)],height:1080,rate:240))
        XCTAssertNil(LocalCapturePolicy.select(available,height:720,rate:90))
    }
    func testStableChoiceKeepsDetailAndDoesNotClaimHighFps(){
        let available=[p(720,240),p(720,60),p(1080,30)]
        XCTAssertEqual(LocalCapturePolicy.select(available,height:1080,rate:60),p(1080,30))
        XCTAssertEqual(LocalCapturePolicy.select(available,height:720,rate:0),p(720,240))
        XCTAssertEqual(LocalCapturePolicy.select(available,height:720,rate:30),nil)
    }
}
