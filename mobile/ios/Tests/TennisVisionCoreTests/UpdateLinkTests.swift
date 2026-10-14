import XCTest
@testable import TennisVisionCore
final class UpdateLinkTests:XCTestCase {
    func testOfficialMobilePackageOpensAltStoreImporterWithExactUrl() throws {
        let download=URL(string:"https://github.com/Laikan2/tennis-vision-releases/releases/download/mobile-v0.6.4/Tennis-Vision-Camera-iOS-v0.6.4-AltStore.ipa")!
        let link=try XCTUnwrap(UpdateSource.installURL(for:download))
        let parts=try XCTUnwrap(URLComponents(url:link,resolvingAgainstBaseURL:false))
        XCTAssertEqual(parts.scheme,"altstore");XCTAssertEqual(parts.host,"install")
        XCTAssertEqual(parts.queryItems?.first?.name,"url");XCTAssertEqual(parts.queryItems?.first?.value,download.absoluteString)
    }
    func testUnrelatedAndAmbiguousInstallUrlsAreRejected(){
        let valid="https://github.com/Laikan2/tennis-vision-releases/releases/download/mobile-v0.6.4/Tennis.ipa"
        for url in [valid+"?token=bad",valid+"#redirect",valid.replacingOccurrences(of:"github.com",with:"github.com.evil.example"),valid.replacingOccurrences(of:"https:",with:"http:"),valid.replacingOccurrences(of:"/mobile-v",with:"/extra/mobile-v"),valid.replacingOccurrences(of:".ipa",with:".apk")]{
            XCTAssertNil(UpdateSource.installURL(for:URL(string:url)!),url)
        }
    }
}
