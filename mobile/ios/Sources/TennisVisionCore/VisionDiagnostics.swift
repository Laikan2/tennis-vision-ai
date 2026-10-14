import Foundation

/// Public, synthetic images with known pixel locations; never mixed with live sessions.
public enum VisionDiagnostics {
    public static let width=640,height=360
    public struct Case: Codable {
        public var name: String,diameter: Double,background: UInt32
        public var chroma=false,clutter=false,bounce=false,blur=1.0
    }
    public struct Measurement: Codable {
        public var name: String,frames: Int,located: Int,wrong: Int,contacts: [VisionCall],maxError: Double,elapsedMs: Double
    }
    public struct Report: Codable {
        public var version="0.3.0",kind="synthetic-pixel-fixtures-not-real-tennis",date=Date()
        public var results: [Measurement],passed: Bool
    }
    public static var cases: [Case] {
        var cases=[Case]()
        for d in [3.0,4,6] {for (label,color) in [("white",UInt32(0xffe8e8e8)),("dark",0xff394b59),("green",0xff73943e),("muted",0xff849c87)] {
            cases.append(Case(name:"\(Int(d))px_\(label)_420",diameter:d,background:color,chroma:true))
        }}
        cases.append(Case(name:"small_clutter",diameter:3,background:0xff394b59,chroma:true,clutter:true))
        cases.append(Case(name:"small_bounce_review",diameter:4,background:0xff394b59,chroma:true,bounce:true))
        cases.append(Case(name:"blur3",diameter:4,background:0xff394b59,chroma:true,blur:3))
        return cases
    }
    public static func spot(_ p: inout [UInt32],x: Double,y: Double,diameter: Double,blur: Double=1,color: UInt32=0xffc7eb4d) {
        let rx=diameter/2*blur,ry=diameter/2
        for py in max(0,Int(floor(y-ry-1)))...min(height-1,Int(ceil(y+ry+1))) {for px in max(0,Int(floor(x-rx-1)))...min(width-1,Int(ceil(x+rx+1))) {
            var inside=0
            for sy in 0..<8{for sx in 0..<8{let dx=(Double(px)+(Double(sx)+0.5)/8-x)/rx,dy=(Double(py)+(Double(sy)+0.5)/8-y)/ry;if dx*dx+dy*dy<=1{inside+=1}}}
            if inside==0{continue};let old=p[py*width+px],a=Double(inside)/64
            func channel(_ shift: UInt32) -> UInt32{
                let background=Double((old>>shift)&255),foreground=Double((color>>shift)&255)
                let mixed=background*(1-a)+foreground*a
                return UInt32(max(0,min(255,Int(mixed.rounded()))))
            }
            p[py*width+px]=0xff000000 | (channel(16)<<16) | (channel(8)<<8) | channel(0)
        }}
    }
    public static func chroma420(_ p: inout [UInt32]) {
        for y in stride(from:0,to:height,by:2){for x in stride(from:0,to:width,by:2){
            let ids=[y*width+x,y*width+x+1,(y+1)*width+x,(y+1)*width+x+1];var ys=[Double](),u=0.0,v=0.0
            for i in ids{let c=p[i],r=Double((c>>16)&255),g=Double((c>>8)&255),b=Double(c&255),l=0.299*r+0.587*g+0.114*b;ys.append(l);u+=(b-l)/4;v+=(r-l)/4}
            for k in 0..<4{
                let noise=Double((ids[k]*37+17)%7-3)
                func value(_ n: Double) -> UInt32{UInt32(max(0,min(255,Int(n.rounded()))))}
                let r=value(ys[k]+v+noise),b=value(ys[k]+u+noise),g=value(ys[k]-(0.299*v+0.114*u)/0.587+noise)
                p[ids[k]]=0xff000000 | (r<<16) | (g<<8) | b
            }
        }}
    }
    public static func frame(_ c: Case,index i: Int) -> ([UInt32],VisionPoint) {
        let x=75.2+Double(i)*28.65,y=c.bounce ? 270.35-Double(abs(i-8))*16:112.3+Double(i)*3.35
        var pixels=Array(repeating:c.background,count:width*height)
        spot(&pixels,x:x,y:y,diameter:c.diameter,blur:c.blur)
        if c.clutter{spot(&pixels,x:550,y:85,diameter:18)};if c.chroma{chroma420(&pixels)}
        return (pixels,VisionPoint(x-0.5,y-0.5))
    }
    public static func evaluate(_ c: Case) -> Measurement {
        var settings=VisionSettings();settings.polygon=[VisionPoint(0.15,0.2),VisionPoint(0.75,0.2),VisionPoint(0.75,0.95),VisionPoint(0.15,0.95)]
        settings.surface=c.bounce ? .floor:.track
        let core=LocalVision(settings:settings);var located=0,wrong=0,maxError=0.0,contacts=[VisionCall](),elapsed=0.0
        for i in 0..<16 {
            let (pixels,known)=frame(c,index:i),started=ProcessInfo.processInfo.systemUptime
            let result=core.process(pixels,width:width,height:height,time:Double(i)/60)
            elapsed+=(ProcessInfo.processInfo.systemUptime-started)*1000
            if let b=result.observed{let error=hypot(b.x-known.x,b.y-known.y);if error<=max(3,c.diameter*0.6){located+=1;maxError=max(maxError,error)}else{wrong+=1}}
            if let event=result.impact{contacts.append(event.call)}
        }
        return Measurement(name:c.name,frames:16,located:located,wrong:wrong,contacts:contacts,maxError:maxError,elapsedMs:elapsed)
    }
    public static func run() -> Report {
        let results=cases.map(evaluate)
        let passed=results.allSatisfy{$0.located>=13 && $0.wrong<=($0.name=="small_clutter" ? 2:0) && ($0.name=="small_bounce_review" ? $0.contacts==[.review]:$0.contacts.isEmpty)}
        return Report(results:results,passed:passed)
    }
}
