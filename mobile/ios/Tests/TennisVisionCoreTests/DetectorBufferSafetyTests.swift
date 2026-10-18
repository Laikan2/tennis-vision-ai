import XCTest
@testable import TennisVisionCore

final class DetectorBufferSafetyTests:XCTestCase {
    func testBorrowedBuffersHandleDimensionChangesResetAndOddFrameEdges(){
        let core=LocalVision()
        for (width,height) in [(1,1),(2,2),(81,83),(639,359),(720,1280),(1280,720),(3,5)] {
            let pixels=(0..<width*height).map{i->UInt32 in
                i%7==0 ? 0xffc7eb4d:0xffa58b58
            }
            for frame in 0..<3 {
                let r=core.process(pixels,width:width,height:height,time:Double(frame)/60)
                XCTAssertFalse(r.message.contains("inválido"))
            }
            core.reset()
        }
    }
    func testBorrowDoesNotMutateInputOrRetainAnUnsafePointerAcrossFrames(){
        let core=LocalVision()
        var first=[UInt32](repeating:0xff394b59,count:640*360)
        VisionDiagnostics.spot(&first,x:160,y:250,diameter:16)
        let preserved=first
        let initial=core.process(first,width:640,height:360,time:0)
        XCTAssertNotNil(initial.observed)
        for frame in 1...8 {
            var next=[UInt32](repeating:0xff394b59,count:first.count)
            VisionDiagnostics.spot(&next,x:160+Double(frame)*3,y:250,diameter:16)
            let copy=next
            _=core.process(next,width:640,height:360,time:Double(frame)/60)
            XCTAssertEqual(next,copy)
        }
        XCTAssertEqual(first,preserved)
    }
}
