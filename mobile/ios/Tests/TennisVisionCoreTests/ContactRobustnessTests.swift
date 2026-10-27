import XCTest
@testable import TennisVisionCore

final class ContactRobustnessTests:XCTestCase {
    let w=640,h=480
    func pixels(_ x:Double,_ y:Double,radius:Double=8)->[UInt32] {
        var p=Array(repeating:UInt32(0xff587082),count:w*h)
        for py in max(0,Int(y-radius))...min(h-1,Int(y+radius)) {
            for px in max(0,Int(x-radius))...min(w-1,Int(x+radius)) where hypot(Double(px)-x,Double(py)-y)<=radius {p[py*w+px]=0xffc7eb4d}
        }
        return p
    }
    func testObservedReboundsWithLocalizationNoiseProduceOneCorrectMark() {
        for fps in [30,60,120] {for noise in [0.0,1.5,3.0] {for vout in [200.0,500.0] {
            var s=VisionSettings();s.polygon=[VisionPoint(0.1,0.1),VisionPoint(0.9,0.1),VisionPoint(0.9,0.95),VisionPoint(0.1,0.95)]
            s.floorLine=[VisionPoint(0,0.12),VisionPoint(1,0.12)]
            let core=LocalVision(settings:s);var hits=[VisionImpact]();let contact=0.203
            for i in 0...Int(Double(fps)*0.38) {
                let t=Double(i)/Double(fps),dt=t-contact,j=sin(Double(i)*2.4)*noise
                let y=340+(dt<=0 ? 700*dt : -vout*dt)+400*dt*dt+j
                let result=core.process(pixels(240+240*t,y),width:w,height:h,time:t)
                if let hit=result.impact{hits.append(hit)}
            }
            XCTAssertEqual(hits.count,1,"fps=\(fps), noise=\(noise), rebound=\(vout)")
            if let hit=hits.first{XCTAssertLessThan(hypot(hit.point.x*Double(w)-(240+240*contact),hit.point.y*Double(h)-348),10)}
        }}}
    }
    func testContactBelowFrameIsNotCountedOrMovedToImageEdge() {
        var s=VisionSettings()
        s.polygon=[VisionPoint(0.1,0.1),VisionPoint(0.9,0.1),VisionPoint(0.9,0.95),VisionPoint(0.1,0.95)]
        s.floorLine=[VisionPoint(0,0.12),VisionPoint(1,0.12)]
        let core=LocalVision(settings:s)
        let results=(0...24).map { i in
            let t=Double(i)/60,d=t-0.203
            return core.process(pixels(240+240*t,476+(d<0 ? 700*d : -500*d)+400*d*d),width:w,height:h,time:t)
        }
        XCTAssertGreaterThanOrEqual(results.filter{$0.observed != nil}.count,12)
        XCTAssertTrue(results.allSatisfy{$0.impact==nil},"No invisible or clamped mark")
        XCTAssertTrue(results.contains{$0.message.contains("fora da imagem")})
        core.reset()
        XCTAssertFalse(core.process(pixels(200,200),width:w,height:h,time:1).message.contains("fora da imagem"))
    }
    func samples(_ fps:Int=60,hand:Bool=false,occluded:Bool=false,curve:(Double)->(Double,Double))->[VisionBall] {
        (0...Int(Double(fps)*0.31)).map { i in
            let t=Double(i)/Double(fps),p=curve(t)
            var b=VisionBall(x:p.0,y:p.1,radius:8,time:t,confidence:0.9)
            b.nearHand=hand;b.occluded=occluded;b.greenDominant=true;return b
        }
    }
    func testNoisyAsymmetricFloorFitAtEveryRate() {
        for fps in [30,60,120,240] {
            let points=samples(fps){t in let d=t-0.17;return (240+240*t,300+(d<0 ? 700*d : -200*d)+400*d*d+sin(t*Double(fps)*2.4)*3)}
            let f=ObservedContactFit.find(points,surface:.floor);XCTAssertNotNil(f)
            if let f{XCTAssertEqual(f.time,0.17,accuracy:0.015);XCTAssertEqual(f.y,300,accuracy:4);XCTAssertEqual(f.x,280.8,accuracy:4)}
        }
    }
    func testGlancingWallReturnUsesBothObservedLegs() {
        let p=samples{t in let d=t-0.17;return (300+(d<0 ? 650*d : -300*d)+sin(t*150)*1.5,230+100*d+400*d*d)}
        let f=ObservedContactFit.find(p,surface:.wall);XCTAssertNotNil(f)
        if let f{XCTAssertEqual(f.x,300,accuracy:4);XCTAssertEqual(f.y,230,accuracy:4)}
    }
    func testSmoothFlightRoundTurnsStaticJitterAndHeldBallCannotScore() {
        let curves:[(Double)->(Double,Double)]=[
            {t in (200+300*t,220+700*t*t)},
            {t in (200+300*t,300-400*(t-0.15)*(t-0.15))},
            {t in (300+30*cos(t*8),230+30*sin(t*8))},
            {t in (250+sin(t*150)*3,300+sin(t*180)*3)},
            {_ in (250,300)}
        ]
        for curve in curves {for surface in [VisionSurface.floor,.wall] {
            XCTAssertNil(ObservedContactFit.find(samples(curve:curve),surface:surface))
        }}
        let curve:(Double)->(Double,Double)={t in let d=t-0.17;return (250,300+(d<0 ? 700*d : -400*d))}
        XCTAssertNil(ObservedContactFit.find(samples(hand:true,curve:curve),surface:.floor))
        XCTAssertNil(ObservedContactFit.find(samples(occluded:true,curve:curve),surface:.floor))
        XCTAssertNil(ObservedContactFit.find(samples(curve:curve).filter{$0.time<0.10 || $0.time>0.23},surface:.floor))
        XCTAssertNil(ObservedContactFit.find(samples(curve:curve).filter{$0.time<0.17},surface:.floor))
    }
}

