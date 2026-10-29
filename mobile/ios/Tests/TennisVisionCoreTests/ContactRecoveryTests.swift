import XCTest
@testable import TennisVisionCore

final class ContactRecoveryTests:XCTestCase {
    let w=320,h=240
    var area:[VisionPoint]{[VisionPoint(0.1,0.1),VisionPoint(0.9,0.1),VisionPoint(0.9,0.95),VisionPoint(0.1,0.95)]}
    func room()->[UInt32]{
        var pixels=[UInt32]();pixels.reserveCapacity(w*h)
        for y in 0..<h{for x in 0..<w{
            let cell=(x/40+y/30)%3
            let v=UInt32(60+cell*45)
            let color=UInt32(0xff000000) | (v<<16) | (v<<8) | v
            pixels.append(color)
        }}
        return pixels
    }
    func image(x:Int=160,y:Int=120,ball:Bool=false)->[UInt32]{
        (0..<w*h).map{i in ball && hypot(Double(i%w-x),Double(i/w-y))<=6 ? 0xffc7eb4d:0xff587082}
    }
    func testStableNewViewRetainsContactAndSpeedWithoutUsingOldAreaForInOut(){
        for surface in [VisionSurface.floor,.wall] {
            var settings=VisionSettings();settings.surface=surface;settings.polygon=area
            settings.floorLine=surface == .floor ? [VisionPoint(0,0.1),VisionPoint(1,0.1)]:[]
            let core=LocalVision(settings:settings)
            _=core.process(room(),width:w,height:h,time:0)
            XCTAssertTrue(core.process(image(),width:w,height:h,time:0.1).movingCamera)
            let stable=core.process(image(),width:w,height:h,time:1)
            XCTAssertFalse(stable.movingCamera);XCTAssertTrue(stable.areaNeedsConfirmation)
            let coordinates=surface == .floor ? [70,85,105,130,160,195,160,130,105,85]:[80,100,120,140,160,140,120,100,80]
            var hits=[VisionImpact]()
            for (i,c) in coordinates.enumerated(){
                let p=image(x:surface == .wall ? c:160,y:surface == .floor ? c:120,ball:true)
                if let hit=core.process(p,width:w,height:h,time:1.1+Double(i)/30).impact{hits.append(hit)}
            }
            XCTAssertEqual(hits.count,1);guard let hit=hits.first else{continue}
            XCTAssertEqual(hit.call,.review);XCTAssertNotNil(hit.speed);XCTAssertTrue(hit.reason.contains("Área"))
            XCTAssertEqual(hit.point.x*Double(w),160,accuracy:3)
            XCTAssertEqual(hit.point.y*Double(h),surface == .floor ? 201:120,accuracy:5)
            XCTAssertNil(LineVoicePolicy.voice(for:hit.call,outEnabled:true,inEnabled:true))
        }
    }
    func testSettledObstructionDoesNotBecomeContact(){
        var settings=VisionSettings();settings.polygon=area
        let core=LocalVision(settings:settings);_=core.process(room(),width:w,height:h,time:0)
        for i in 1...120{XCTAssertNil(core.process(image(),width:w,height:h,time:Double(i)/60).impact)}
    }
}

