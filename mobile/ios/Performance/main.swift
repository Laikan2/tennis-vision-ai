import Foundation
import TennisVisionCore

// Known generated pixels, not personal footage. Generation is outside timings.
struct Sample:Codable {
    var name:String,width:Int,height:Int,frames:Int,medianMs:Double,p95Ms:Double
    var observed:Int,contacts:Int,signatures:[String]
}
func fixture(width:Int,height:Int,scene:Int,frame:Int)->[UInt32] {
    var pixels=[UInt32](repeating:0xff394b59,count:width*height)
    for y in 0..<height {for x in 0..<width {
        let noise=(x*17+y*31)%13-6
        let base: (Int,Int,Int)=scene==1 ? (165,139,88):scene==2 ? (115,148,62):(57,75,89)
        func c(_ n:Int)->UInt32{UInt32(max(0,min(255,n+noise)))}
        pixels[y*width+x]=0xff000000 | c(base.0)<<16 | c(base.1)<<8 | c(base.2)
        if scene==2 && (abs(x-width/5)<2 || abs(y-height*3/5)<2){pixels[y*width+x]=0xffe8e8e8}
    }}
    if scene != 3 {
        let radius=scene==1 ? 18:6
        let bx=width/3+frame*5,by=height*2/3+(frame<6 ? frame*3:(12-frame)*3)
        for y in by-radius...by+radius {for x in bx-radius...bx+radius where (x-bx)*(x-bx)+(y-by)*(y-by)<=radius*radius {
            pixels[y*width+x]=0xffc7eb4d
        }}
    }
    return pixels
}
func signature(_ r:VisionResult)->String {
    func d(_ v:Double)->String{String(v.bitPattern,radix:16)}
    var parts=[r.message,r.movingCamera ? "moving":"still",r.areaNeedsConfirmation ? "check":"stable",d(r.cameraOffset.x),d(r.cameraOffset.y)]
    if let b=r.observed {
        parts += ["ball",d(b.x),d(b.y),d(b.radius),d(b.confidence),d(b.diameterX),d(b.diameterY),d(b.motionSupport),d(b.luminance),d(b.feltChroma),String(b.nearHand),String(b.occluded),String(b.blurred),String(b.colorUncertain),String(b.distinctFelt),String(b.greenDominant)]
    }
    if let p=r.predicted{parts += ["prediction",d(p.x),d(p.y)]}
    if let speed=r.speed{parts += ["speed",d(speed)]}
    for p in r.trail{parts += [d(p.x),d(p.y)]}
    if let e=r.impact{parts += ["impact",String(e.id),d(e.time),d(e.point.x),d(e.point.y),d(e.radius),d(e.uncertainty),e.call.rawValue,e.reason,e.speed.map(d) ?? "nil"]}
    return parts.joined(separator:"|")
}
var results=[Sample]()
for (width,height) in [(1280,720),(720,1280),(1920,1080)] {
    for scene in 0...3 {
        let names=["small-ball","warm-floor","green-court","negative"]
        let frames=(0..<12).map{fixture(width:width,height:height,scene:scene,frame:$0)}
        var durations=[Double](),signatures=[String](),observed=0,contacts=0
        for round in 0..<3 {
            let core=LocalVision()
            for (index,pixels) in frames.enumerated() {
                let start=ProcessInfo.processInfo.systemUptime
                let result=core.process(pixels,width:width,height:height,time:Double(index)/60)
                let elapsed=(ProcessInfo.processInfo.systemUptime-start)*1000
                if round>0{durations.append(elapsed)}
                if round==0{signatures.append(signature(result));observed += result.observed == nil ? 0:1;contacts += result.impact == nil ? 0:1}
            }
        }
        durations.sort()
        results.append(Sample(name:names[scene],width:width,height:height,frames:durations.count,
            medianMs:durations[durations.count/2],p95Ms:durations[min(durations.count-1,Int(Double(durations.count)*0.95))],
            observed:observed,contacts:contacts,signatures:signatures))
    }
}
let payload=try JSONEncoder().encode(results)
if CommandLine.arguments.count>1{try payload.write(to:URL(fileURLWithPath:CommandLine.arguments[1]))}
print(String(data:payload,encoding:.utf8)!)
