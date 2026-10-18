import XCTest
@testable import TennisVisionCore

final class ImpactLabelTests:XCTestCase {
    func testAdjacentLabelsStayInsideImageWithoutCoveringContactMarkers(){
        let viewport=CGRect(x:2,y:2,width:316,height:236)
        let anchors=[CGPoint(x:230,y:172),CGPoint(x:276,y:172),CGPoint(x:140,y:172)]
        var occupied=anchors.map{CGRect(x:$0.x-9,y:$0.y-9,width:18,height:18)}
        for (i,p) in anchors.enumerated(){
            let box=VisionOverlayLayout.place(viewport:viewport,size:CGSize(width:i==0 ? 145:115,height:18),preferred:CGPoint(x:p.x+7,y:p.y-22),gap:3,occupied:occupied)
            XCTAssertNotNil(box)
            guard let box else{continue}
            XCTAssertTrue(viewport.contains(box));XCTAssertFalse(occupied.contains{$0.intersects(box)})
            occupied.append(box)
        }
        XCTAssertEqual(occupied.count,6)
    }
    func testMostRecentLabelWinsWhenOnlyOneFits(){
        let viewport=CGRect(x:0,y:0,width:120,height:20),size=CGSize(width:116,height:18)
        let newest=VisionOverlayLayout.place(viewport:viewport,size:size,preferred:CGPoint(x:118,y:0),gap:3,occupied:[])
        XCTAssertNotNil(newest)
        guard let newest else{return}
        XCTAssertTrue(viewport.contains(newest))
        XCTAssertNil(VisionOverlayLayout.place(viewport:viewport,size:size,preferred:.zero,gap:3,occupied:[newest]))
    }
    func testTooWideLabelIsHiddenRatherThanClippedOutsideImage(){
        XCTAssertNil(VisionOverlayLayout.place(viewport:CGRect(x:0,y:0,width:120,height:20),size:CGSize(width:121,height:18),preferred:.zero,gap:3,occupied:[]))
    }
}
