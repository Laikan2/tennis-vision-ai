import XCTest
@testable import TennisVisionCore

/// Kotlin and Swift consume the same known pixels/calls. This is not a camera test.
final class AndroidParityTests: XCTestCase {
    private struct Corpus: Decodable {var cases:[Scenario]}
    private struct Scenario: Decodable {
        var name:String,family:String,action:String?,skin:String?,background:String?
        var radius:Int?,fps:Int?,outside:Bool?,fingers:Bool?,diameter:Double?,dx:Double?,blur:Double?,phase:Double?,hideContact:Bool?,bounce:Bool?
        var calls:[VisionCall],minLocated:Int
    }
    func testSharedAndroidGreenBallHandFallFastAndContactScenarios() throws {
        let url=try XCTUnwrap(Bundle.module.url(forResource:"vision-cases",withExtension:"json",subdirectory:"Fixtures"))
        let corpus=try JSONDecoder().decode(Corpus.self,from:Data(contentsOf:url))
        XCTAssertEqual(corpus.cases.count,41)
        for c in corpus.cases {
            let w=c.family=="fast" ? 1280:640,h=c.family=="fast" ? 720:360
            var settings=VisionSettings()
            settings.polygon=c.family=="fast" ? [VisionPoint(0.15,0.4),VisionPoint(0.75,0.4),VisionPoint(0.75,0.95),VisionPoint(0.15,0.95)]:[VisionPoint(0.20,0.3),VisionPoint(0.72,0.3),VisionPoint(0.72,0.94),VisionPoint(0.20,0.94)]
            let line=c.family=="fast" ? 0.3:0.26
            settings.floorLine=[VisionPoint(0,line),VisionPoint(1,line)]
            let engine=LocalVision(settings:settings),fps=Double(c.fps ?? 60)
            let turn=[90,102,118,138,162,190,224,264,296,264,224,190,162,138]
            let ys: [Int]
            switch c.action {
            case "release":ys=Array(repeating:90,count:10)+turn
            case "carry":ys=turn
            case "fall":ys=Array(repeating:90,count:10)+Array(turn.prefix(9))+Array(repeating:-100,count:8)
            default:ys=Array(repeating:90,count:c.family=="fast" ? 12:20)
            }
            var found=0,wrong=0,hits=[VisionImpact]()
            for i in ys.indices {
                var pixels=Array(repeating:UInt32(c.background ?? "ff315372",radix:16)!,count:w*h)
                let x:Double,y:Double,rx:Double,ry:Double,visible:Bool
                if c.family=="fast" {
                    let t=Double(i)-5-(c.phase ?? 0)
                    x=(c.outside==true ? 1080:640)+(c.dx ?? 40)*t
                    y=c.bounce==false ? 370:540-32*abs(t)
                    ry=(c.diameter ?? 6)/2;rx=ry*(c.blur ?? 1)
                    visible = !(c.hideContact==true && i==5) && x-rx>=1 && x+rx<Double(w)-1
                }else{
                    x=c.outside==true ? 560:320;y=Double(ys[i]);rx=Double(c.radius ?? 6);ry=rx
                    visible=y>0 && !["hidden","no_ball"].contains(c.action ?? "")
                    let held=["hold","carry","fingers","hidden","no_ball"].contains(c.action ?? "") || i<10
                    let hy=held ? Int(y):90,reach=max(12,Int(rx)*3),skin=UInt32(c.skin ?? "ffbc8062",radix:16)!
                    for py in max(0,hy-reach)...min(h-1,hy+reach){for px in Int(x)-reach...Int(x)+reach{pixels[py*w+px]=skin}}
                }
                if visible {
                    for py in max(0,Int(floor(y-ry)))...min(h-1,Int(ceil(y+ry))){for px in max(0,Int(floor(x-rx)))...min(w-1,Int(ceil(x+rx))){
                        let dx=(Double(px)-x)/rx,dy=(Double(py)-y)/ry
                        if dx*dx+dy*dy<=1{pixels[py*w+px]=0xffc7eb4d}
                    }}
                    if c.action=="fingers" || c.fingers==true && i<10 {
                        for py in Int(y-ry)...Int(y+ry){for px in Int(x)-1...Int(x)+1{pixels[py*w+px]=UInt32(c.skin ?? "ffbc8062",radix:16)!}}
                    }
                }
                let r=engine.process(pixels,width:w,height:h,time:Double(i)/fps)
                if let b=r.observed {
                    if visible && hypot(b.x-x,b.y-y)<=max(3,ry*0.6){found+=1}else{wrong+=1}
                }
                if let hit=r.impact{hits.append(hit)}
            }
            XCTAssertGreaterThanOrEqual(found,c.minLocated,c.name)
            XCTAssertEqual(wrong,0,c.name)
            XCTAssertEqual(hits.map{$0.call},c.calls,c.name)
            if let hit=hits.first {
                let knownX=c.outside==true ? (c.family=="fast" ? 1080.0:560):(c.family=="fast" ? 640:320)
                let knownY=c.family=="fast" ? 540+(c.diameter ?? 6)/2:296+Double(c.radius ?? 6)+0.5
                XCTAssertLessThanOrEqual(hypot(hit.point.x*Double(w)-knownX,hit.point.y*Double(h)-knownY),3,c.name)
            }
            print("MOBILE_PARITY \(c.name) located=\(found) wrong=\(wrong) calls=\(hits.map{$0.call.rawValue})")
        }
    }
    func testUncalibratedFreeAreaCannotBecomeCertainOut() {
        XCTAssertEqual(VisionSettings().court,.custom)
        let line=[VisionPoint(0,0.6),VisionPoint(1,0.9)]
        XCTAssertFalse(VisionGeometry.onFloor(VisionPoint(0.9,0.85),line))
        XCTAssertTrue(VisionGeometry.onFloor(VisionPoint(0.1,0.85),line))
        XCTAssertEqual(VisionGeometry.onFloor(VisionPoint(0.1,0.85),line),VisionGeometry.onFloor(VisionPoint(0.1,0.85),line.reversed()))
    }
}
