import Foundation

// Local green-tennis-ball analysis. No network, model download or PC dependency.
public struct VisionPoint: Codable, Equatable, Sendable {
    public var x: Double; public var y: Double
    public init(_ x: Double,_ y: Double) { self.x=x;self.y=y }
}
public enum VisionSurface: String, Codable, CaseIterable, Sendable { case floor, wall, track }
public enum VisionCourt: String, Codable, CaseIterable, Sendable { case custom, full, half }
public enum VisionCall: String, Codable, Sendable { case inside, outside, review, unmarked, rejected }
public struct VisionSettings: Codable, Equatable, Sendable {
    public var polygon=[VisionPoint](), floorLine=[VisionPoint]()
    public var surface=VisionSurface.floor, court=VisionCourt.custom
    public var sound=true, marks=true
    // Optional fields allow archives from earlier versions to decode unchanged.
    public var floorPolygon: [VisionPoint]?,wallPolygon: [VisionPoint]?,floorCourt: VisionCourt?
    public init() {}
}
public struct VisionBall: Sendable {
    public var x: Double,y: Double,radius: Double,time: Double,confidence: Double
    public var nearHand=false, blurred=false, luminance=0.0, motionSupport=1.0, occluded=false, colorUncertain=false
    public var diameterX=0.0,diameterY=0.0,distinctFelt=false,feltChroma=0.0,greenDominant=false
    public var contrastFelt=false,patterned=false
    public var resolved: Bool { !occluded && !colorUncertain && !patterned && radius>=2.5 && confidence>=0.5 }
    var supportsBallColor: Bool { greenDominant || feltChroma>=107 || contrastFelt }
}
public struct VisionImpact: Codable, Identifiable, Sendable {
    public var id: Int,time: Double,point: VisionPoint,radius: Double,call: VisionCall
    public var speed: Double?,uncertainty: Double,reason: String,surface: VisionSurface
    public var reviewed: VisionCall?
    public var cameraOffset=VisionPoint(0,0),evidenceOffset=VisionPoint(0,0)
    public var decision: VisionCall { reviewed ?? call }
}
public struct VisionResult: Sendable {
    public var observed: VisionBall?,predicted: VisionPoint?,trail=[VisionPoint](),impact: VisionImpact?
    public var movingCamera=false,speed: Double?,message="Bola não localizada"
    public var area=VisionSettings(),cameraOffset=VisionPoint(0,0)
    public var areaNeedsConfirmation=false
    public var diagnostics=VisionTrackingDiagnostics()
}
public enum VisionGeometry {
    public static func valid(_ p:[VisionPoint])->Bool{validShape(p,margin:0)}
    public static func projectedPolygon(_ p:[VisionPoint])->Bool{validShape(p,margin:0.035)}
    private static func validShape(_ p: [VisionPoint],margin:Double) -> Bool {
        guard p.count==4,p.allSatisfy({$0.x.isFinite && $0.y.isFinite && $0.x >= -margin && $0.x <= 1+margin && $0.y >= -margin && $0.y <= 1+margin}) else{return false}
        let turns=(0..<4).map { i -> Double in
            let a=p[i],b=p[(i+1)%4],c=p[(i+2)%4];return (b.x-a.x)*(c.y-b.y)-(b.y-a.y)*(c.x-b.x)
        }
        var twiceArea=0.0
        for i in 0..<4{let next=(i+1)%4;twiceArea+=p[i].x*p[next].y-p[next].x*p[i].y}
        let area=abs(twiceArea)/2
        return area>=0.005 && (turns.allSatisfy{$0>0.00001} || turns.allSatisfy{$0 < -0.00001})
    }
    public static func projectedFloorLine(_ p:[VisionPoint])->Bool {
        p.count==2 && p.allSatisfy{$0.x.isFinite && $0.y.isFinite} && abs(p[1].x-p[0].x)>0.2
    }
    public static func validLine(_ p: [VisionPoint]) -> Bool {
        projectedFloorLine(p) && p.allSatisfy{(0...1).contains($0.x) && (0...1).contains($0.y)}
    }
    public static func floorDistance(_ p: VisionPoint,_ line: [VisionPoint],_ width: Int,_ height: Int) -> Double? {
        guard projectedFloorLine(line) else{return nil};let a=line[0],b=line[1],w=Double(width),h=Double(height)
        let slope=(b.y-a.y)*h/((b.x-a.x)*w),y=(a.y+(p.x-a.x)*(b.y-a.y)/(b.x-a.x))*h
        return (p.y*h-y)/sqrt(1+slope*slope)
    }
    public static func onFloor(_ p: VisionPoint,_ line: [VisionPoint]) -> Bool {
        guard projectedFloorLine(line) else{return false}
        let a=line[0],b=line[1],y=a.y+(p.x-a.x)*(b.y-a.y)/(b.x-a.x)
        return p.y>y+0.01
    }
    public static func excludedFloorRegion(_ line:[VisionPoint])->[VisionPoint] {
        guard projectedFloorLine(line) else{return []};let a=line[0],b=line[1]
        func signed(_ p:VisionPoint)->Double{p.y-a.y-(p.x-a.x)*(b.y-a.y)/(b.x-a.x)}
        let box=[VisionPoint(0,0),VisionPoint(1,0),VisionPoint(1,1),VisionPoint(0,1)]
        var clipped=[VisionPoint]()
        for i in box.indices {
            let p=box[i],q=box[(i+1)%box.count],d=signed(p),e=signed(q)
            if d<0{clipped.append(p)}
            if (d<0) != (e<0){let t=d/(d-e);clipped.append(VisionPoint(p.x+(q.x-p.x)*t,p.y+(q.y-p.y)*t))}
        }
        return clipped.count>=3 ? clipped:[]
    }
    public static func classify(_ p: VisionPoint,radius: Double,uncertainty: Double,polygon: [VisionPoint],width: Int,height: Int) -> VisionCall {
        guard projectedPolygon(polygon) else{return .unmarked}
        let x=p.x*Double(width),y=p.y*Double(height);var inside=false,distance=Double.infinity
        let v=polygon.map{VisionPoint($0.x*Double(width),$0.y*Double(height))}
        for i in 0..<4 {
            let a=v[i],b=v[(i+1)%4],dx=b.x-a.x,dy=b.y-a.y
            if (a.y>y) != (b.y>y),x<(b.x-a.x)*(y-a.y)/(b.y-a.y)+a.x {inside.toggle()}
            let t=min(1,max(0,((x-a.x)*dx+(y-a.y)*dy)/(dx*dx+dy*dy)))
            distance=min(distance,hypot(x-a.x-t*dx,y-a.y-t*dy))
        }
        let signed=inside ? -distance:distance
        if signed+uncertainty<radius{return .inside};if signed-uncertainty>radius{return .outside};return .review
    }
    public static func impactPoint(_ event: VisionImpact,offset: VisionPoint) -> VisionPoint {
        VisionPoint(event.point.x+offset.x-event.cameraOffset.x,event.point.y+offset.y-event.cameraOffset.y)
    }
    public static func guides(_ p: [VisionPoint],court: VisionCourt) -> [[VisionPoint]] {
        guard projectedPolygon(p),court != .custom else{return []}
        func project(_ u: Double,_ v: Double) -> VisionPoint? {
            let dx1=p[1].x-p[2].x,dx2=p[3].x-p[2].x,dy1=p[1].y-p[2].y,dy2=p[3].y-p[2].y
            let dx3=p[0].x-p[1].x+p[2].x-p[3].x,dy3=p[0].y-p[1].y+p[2].y-p[3].y,det=dx1*dy2-dx2*dy1
            guard abs(det)>1e-10 else{return nil};let g=(dx3*dy2-dx2*dy3)/det,h=(dx1*dy3-dx3*dy1)/det,d=g*u+h*v+1
            guard abs(d)>1e-10 else{return nil}
            return VisionPoint(((p[1].x-p[0].x+g*p[1].x)*u+(p[3].x-p[0].x+h*p[3].x)*v+p[0].x)/d,
                ((p[1].y-p[0].y+g*p[1].y)*u+(p[3].y-p[0].y+h*p[3].y)*v+p[0].y)/d)
        }
        let singles=(10.97-8.23)/(2*10.97),service=court == .full ? (11.885-6.4)/23.77:6.4/11.885
        var lines=[(singles,0.0,singles,1.0),(1-singles,0.0,1-singles,1.0),(singles,service,1-singles,service)]
        if court == .full {lines += [(0,0.5,1,0.5),(singles,1-service,1-singles,1-service),(0.5,service,0.5,1-service)]}
        else {lines += [(0.5,0,0.5,service)]}
        return lines.compactMap { line in guard let a=project(line.0,line.1),let b=project(line.2,line.3) else{return nil};return [a,b] }
    }
}

@inline(__always) func visionLight(_ c: UInt32) -> Int { (Int((c>>16)&255)+2*Int((c>>8)&255)+Int(c&255))/4 }
@inline(__always) private func strongGreen(_ c: UInt32) -> Bool {
    let r=Int((c>>16)&255),g=Int((c>>8)&255),b=Int(c&255)
    return g>=45 && r*100>g*42 && ((g*100>=r*86 && g*100>b*132 && g-b>18) || (g*100>=r*70 && g*100<=r*90 && g*10>b*24 && g-b>=40))
}
@inline(__always) private func weakGreen(_ c: UInt32) -> Bool {
    let r=Int((c>>16)&255),g=Int((c>>8)&255),b=Int(c&255)
    return g>=45 && r*100>g*55 && g*100>=r*92 && g*100>b*114 && g-b>16
}
@inline(__always) private func skinColor(_ c: UInt32) -> Bool {
    let r=Int((c>>16)&255),g=Int((c>>8)&255),b=Int(c&255)
    return r>=32 && g>=18 && b>=10 && r*100>g*112 && g*100>b*108 && r-g>7
}
@inline(__always) private func gripSkin(_ c: UInt32) -> Bool {
    let r=Int((c>>16)&255),g=Int((c>>8)&255),b=Int(c&255)
    // Red/pink skin has less yellow cast than warm timber or shaded felt.
    return r>=32 && g>=18 && b>=10 && r*100>g*110 && r*100>b*120 && r-g>10 && g-b<=max(10,(r-g)*4/5)
}
final class VisionDetector {
    private var mask=[UInt8](),strong=[UInt8](),chroma=[UInt8](),previous=[UInt32](),oldGreen=[UInt8](),rawGreen=[UInt8]()
    private var queue=[Int](),cutoffs=[Int](),weakCutoffs=[Int]()
    private var dimensions=(0,0)
    var alignment=VisionAlignment()
    func reset(){previous=[]}
    // Keep pointer ownership in detect(); this synchronous pure kernel cannot
    // retain a buffer. A separate function also bounds Swift type-checking work.
    private static func segment(pixels:UnsafePointer<UInt32>,previous:UnsafePointer<UInt32>,
        mask:UnsafeMutablePointer<UInt8>,strong:UnsafeMutablePointer<UInt8>,chroma:UnsafeMutablePointer<UInt8>,
        rawGreen:UnsafeMutablePointer<UInt8>,oldGreen:UnsafePointer<UInt8>,queue:UnsafeMutablePointer<Int>,
        width:Int,height:Int,count:Int,previousCount:Int,time:Double,prior:VisionBall?,lightShift:Int,columns:Int,
        moving:Bool,cutoffs:[Int],weakCutoffs:[Int])->([VisionBall],Bool) {
        let search=prior.map{max(14,$0.radius*3)} ?? 0
        for i in 0..<count {
            let c=pixels[i],s=strongGreen(c);strong[i]=s ? 1:0
            let red=Int((c>>16)&255),green=Int((c>>8)&255),blue=Int(c&255)
            chroma[i]=UInt8(max(0,green-blue)*255/max(1,max(red,green)))
            var selected=s || weakGreen(c);rawGreen[i]=selected ? 1:0
            if selected {
                let y=i/width,x=i-y*width,old=previousCount==count ? previous[i]:0
                let tracked=prior.map{abs(Double(x)-$0.x)<=search && abs(Double(y)-$0.y)<=search && Double(visionLight(c)-lightShift)>=$0.luminance*0.82} ?? false
                if !tracked && (previousCount==0 || oldGreen[i]==1 && visionLight(c)-visionLight(old)-lightShift<=8) {
                    let tile=(y>>3)*columns+(x>>3),cutoff=s ? cutoffs[tile]:max(cutoffs[tile],weakCutoffs[tile])
                    if visionLight(c)<cutoff{selected=false}
                }
            }
            mask[i]=selected ? 1:0
        }
        var found=[VisionBall](),bounds=[[Int]](),masses=[Int](),clippedFelt=[[Int]]()
        // Every accepted component needs a strong felt pixel. Seed from those
        // pixels only; weak-only wall/compression regions cannot produce a ball.
        // Flood fill still includes the same weak edge pixels around each seed.
        // Saturated felt is extracted before low-chroma expansion can connect
        // it to a warm floor. The second scan retains faint distant components;
        // every pixel is consumed at most once across both floods.
        for pass in 0...1 {for seed in 0..<count where mask[seed]==1 && strong[seed]==1 && (pass==1 || chroma[seed]>=82) {
            var head=0,tail=1,xmin=width,xmax=0,ymin=height,ymax=0,sxmin=width,sxmax = -1,symin=height,symax = -1
            var sx=0.0,sy=0.0,light=0.0,chromaSum=0,redSum=0,greenSum=0,blueSum=0,wx=0.0,wy=0.0,weights=0.0,changed=0,strongCount=0
            queue[0]=seed;mask[seed]=0
            while head<tail {
                let i=queue[head],x=i%width,y=i/width;head+=1
                xmin=min(xmin,x);xmax=max(xmax,x);ymin=min(ymin,y);ymax=max(ymax,y)
                sx+=Double(x);sy+=Double(y);light+=Double(visionLight(pixels[i]))
                chromaSum+=Int(chroma[i])
                redSum+=Int((pixels[i]>>16)&255);greenSum+=Int((pixels[i]>>8)&255);blueSum+=Int(pixels[i]&255)
                if strong[i]==1{strongCount+=1;sxmin=min(sxmin,x);sxmax=max(sxmax,x);symin=min(symin,y);symax=max(symax,y)}
                let weight=Double(max(1,Int((pixels[i]>>8)&255)-Int(pixels[i]&255)))
                weights+=weight;wx+=Double(x)*weight;wy+=Double(y)*weight
                if previousCount==count && abs(visionLight(pixels[i])-visionLight(previous[i])-lightShift)>12{changed+=1}
                func add(_ n: Int){if mask[n]==1 && (pass==1 || chroma[n]>=71 || abs(n%width-seed%width)<=4 && abs(n/width-seed/width)<=4){mask[n]=0;queue[tail]=n;tail+=1}}
                if x>0{add(i-1)};if x+1<width{add(i+1)};if y>0{add(i-width)};if y+1<height{add(i+width)}
            }
            let w=xmax-xmin+1,h=ymax-ymin+1,fill=Double(tail)/Double(w*h),aspect=Double(max(w,h))/Double(min(w,h))
            let closeRound=aspect<=1.65 && (0.55...0.90).contains(fill) && Double(strongCount)>=Double(tail)*0.65
            let saturated=Double(chromaSum)/Double(tail)>=107 && Double(strongCount)>=Double(tail)*0.65 && Double(redSum)>=Double(greenSum)*0.78
            // A printed mark may isolate felt inside a border-clipped ball.
            // Remember the incomplete parent before discarding its silhouette.
            if saturated && min(w,h)>=12 && Double(max(w,h))<=Double(min(width,height))*0.8 && aspect<=4 && fill>=0.35 && (xmin==0 || ymin==0 || xmax==width-1 || ymax==height-1){clippedFelt.append([xmin,ymin,xmax,ymax])}
            let closePartial=aspect<=1.65 && (0.40...0.55).contains(fill) && min(w,h)>=16 && saturated
            let seamFragment=(1.65...2.35).contains(aspect) && (0.65...0.85).contains(fill) && min(w,h)>=12 && saturated
            let close=closeRound || closePartial || seamFragment
            guard strongCount>0,tail>=3,Double(tail)<=Double(count)*(close ? 0.12:0.025),min(w,h)>=2,Double(max(w,h))<=Double(min(width,height))*(close ? 0.60:0.23),aspect<=8,fill>=0.35 else{continue}
            if min(w,h)>=6 && fill>0.94{continue}
            let x=min(w,h)<9 ? wx/weights:sx/Double(tail),y=min(w,h)<9 ? wy/weights:sy/Double(tail),radius=Double(min(w,h))/2
            let weak=Double(strongCount)<Double(tail)*0.25,halo=weak || (min(w,h)>=5 && min(sxmax-sxmin+1,symax-symin+1)<5)
            if weak && (max(w,h)>12 || aspect>4){continue}
            if xmin==0 || ymin==0 || xmax==width-1 || ymax==height-1{continue}
            if radius<=12 {
                // A highlight on a large, underexposed yellow ball is not a
                // separate tiny ball. Do not derive a contact from that patch.
                let reach=max(16,Int(ceil(radius*4)));var samples=0,dimFelt=0,warmFelt=0
                for py in stride(from:max(0,Int(y)-reach),through:min(height-1,Int(y)+reach),by:2){for px in stride(from:max(0,Int(x)-reach),through:min(width-1,Int(x)+reach),by:2){
                    if hypot(Double(px)-x,Double(py)-y)<radius*1.5{continue};samples+=1
                    let c=pixels[py*width+px],r=Int((c>>16)&255),g=Int((c>>8)&255),b=Int(c&255)
                    if g>=18 && g*100>=r*70 && g*100<=r*90 && g*10>b*24 && g-b>=16{warmFelt+=1;if g<45{dimFelt+=1}}
                }}
                if samples>0 && Double(warmFelt)>Double(samples)*0.40 && Double(dimFelt)>Double(samples)*0.25{continue}
            }
            let followed=prior.map{time-$0.time<0.14 && hypot(x-$0.x,y-$0.y)<max(30,$0.radius*6)} ?? false
            if aspect>4.5 && Double(changed)<Double(tail)*0.25 && !followed{continue}
            var skin=0,movingSkin=0,ring=0,closeRing=0,closeGrip=0,surroundChroma=0,surroundCount=0;let reach=max(6,Int(radius*2.4))
            let compact:Bool=radius>=2.5 && aspect<=1.9 && (0.50...0.90).contains(fill)
            let warm:Bool=Double(chromaSum)/Double(tail)<107 && Double(greenSum)<Double(redSum)*1.05
            let yellow:Bool=Double(greenSum)>=Double(redSum)*0.75 && Double(greenSum)>=Double(blueSum)*1.8
            let needsContrast:Bool=compact && warm && yellow && Double(strongCount)>=Double(tail)*0.55
            for py in stride(from:max(0,Int(y)-reach),through:min(height-1,Int(y)+reach),by:2){for px in stride(from:max(0,Int(x)-reach),through:min(width-1,Int(x)+reach),by:2){
                let distance=hypot(Double(px)-x,Double(py)-y)
                if distance<radius*1.2{continue};ring+=1
                let i=py*width+px,c=pixels[i]
                if needsContrast && distance>=radius*1.8 {let r=Int((c>>16)&255),g=Int((c>>8)&255),b=Int(c&255);surroundChroma+=max(0,g-b)*255/max(1,max(r,g));surroundCount+=1}
                if distance<=radius*1.8 {closeRing+=1;if gripSkin(c) && strong[i]==0{closeGrip+=1}}
                if skinColor(c){skin+=1;if previousCount==count && !strongGreen(previous[i]) && abs(visionLight(c)-visionLight(previous[i])-lightShift)>12{movingSkin+=1}}
            }}
            if closePartial && (ring==0 || Double(skin)<Double(ring)*0.12 || Double(skin)>Double(ring)*0.85){continue}
            var ball=VisionBall(x:x,y:y,radius:radius,time:time,confidence:halo ? min(fill,0.45):min(fill,min(w,h)<5 ? 0.6:0.95))
            ball.diameterX=Double(w);ball.diameterY=Double(h)
            ball.distinctFelt=closePartial || radius>=2.5 && aspect<=1.6 && fill>=0.55 && saturated;ball.occluded=closePartial || seamFragment
            ball.feltChroma=Double(chromaSum)/Double(tail)
            ball.greenDominant=Double(greenSum)>=Double(redSum)*1.05
            // Compact olive felt must separate from its local surround. Absolute
            // chroma alone favours vivid garment fragments over a dim real ball.
            ball.contrastFelt=needsContrast && surroundCount>0 &&
                Double(chromaSum)/Double(tail)-Double(surroundChroma)/Double(max(1,surroundCount))>=32
            var skinSides=0
            for side in [-1.0,1.0]{for vertical in [-0.5,0.5,2.0]{
                let px=min(width-1,max(0,Int((x+side*Double(reach)).rounded()))),py=min(height-1,max(0,Int((y+vertical*radius).rounded())))
                if skinColor(pixels[py*width+px]){skinSides+=1}
            }}
            let boundedGrip=Double(skin)>Double(ring)*0.30 && Double(skin)<Double(ring)*0.80 && skinSides<=1
            // Pink-toned wood may surround a tiny free ball. A small grip must
            // end in a wider lateral context; uniform colour is not a hand.
            var smallGripBounded=false
            if radius<6 && closeRing>=4 && Double(closeGrip)>=Double(closeRing)*0.08 {
                let wider=max(24,radius*12);var gripSides=0
                for side in [-1.0,1.0]{for vertical in [-0.5,0.5,2.0]{
                    let px=min(width-1,max(0,Int((x+side*wider).rounded())))
                    let py=min(height-1,max(0,Int((y+vertical*radius).rounded())))
                    if gripSkin(pixels[py*width+px]){gripSides+=1}
                }}
                smallGripBounded=gripSides<=1
            }
            let closeGripPresent=closeRing>=4 && Double(closeGrip)>=Double(closeRing)*0.08 && (radius<6 ? smallGripBounded:Double(closeGrip)<=Double(closeRing)*0.65)
            // Warm floor colours cannot keep a previous hand attached after release.
            // Each observation needs present grip, partial silhouette or moving skin.
            ball.nearHand=closePartial || closeGripPresent || ring>0 && Double(skin)>Double(ring)*0.18 && (Double(movingSkin)>Double(ring)*0.12 || boundedGrip)
            ball.blurred=aspect>1.6;ball.luminance=light/Double(tail);ball.motionSupport=previousCount==0 ? 0:Double(changed)/Double(tail);ball.colorUncertain=halo
            found.append(ball);bounds.append([xmin,ymin,xmax,ymax]);masses.append(tail)
        }}
        if (2...48).contains(found.count) {
            var consumed=Array(repeating:false,count:found.count),joined=[VisionBall]()
            for i in found.indices where !consumed[i] {
                var match: Int?,best=Double.infinity,matchSeam=false
                if i+1<found.count {for j in i+1..<found.count where !consumed[j] {
                    let a=found[i],b=found[j],aa=bounds[i],bb=bounds[j]
                    let w=max(aa[2],bb[2])-min(aa[0],bb[0])+1,h=max(aa[3],bb[3])-min(aa[1],bb[1])+1
                    let fill=Double(masses[i]+masses[j])/Double(w*h)
                    guard min(w,h)>=6,Double(max(w,h))<=Double(min(width,height))*0.60,Double(max(w,h))<=Double(min(w,h))*1.55,(0.40...0.90).contains(fill) else{continue}
                    let dx=max(0,max(aa[0],bb[0])-min(aa[2],bb[2])-1),dy=max(0,max(aa[1],bb[1])-min(aa[3],bb[3])-1)
                    if Double(max(dx,dy))>max(4,Double(min(w,h))*0.22){continue}
                    var bridge=0
                    for k in 0...4{let f=0.35+Double(k)*0.075,px=Int(a.x+(b.x-a.x)*f),py=Int(a.y+(b.y-a.y)*f);if skinColor(pixels[py*width+px]){bridge+=1}}
                    var inkBridge=0
                    let centerDistance=max(1,hypot(a.x-b.x,a.y-b.y))
                    for k in 0...4 {
                        let across=Double(k-2)*Double(min(w,h))*0.04
                        let px=Int(((a.x+b.x)*0.5-(b.y-a.y)/centerDistance*across).rounded()),py=Int(((a.y+b.y)*0.5+(b.x-a.x)/centerDistance*across).rounded()),color=pixels[py*width+px]
                        let r=Int((color>>16)&255),g=Int((color>>8)&255),b=Int(color&255),high=max(r,max(g,b)),low=min(r,min(g,b))
                        if Double(visionLight(color))<min(a.luminance,found[j].luminance)*0.65 && Double(high-low)<=max(12,Double(high)*0.25){inkBridge+=1}
                    }
                    let seam=inkBridge>=3 && a.feltChroma>=107 && b.feltChroma>=107 && Double(max(w,h))<=Double(min(w,h))*1.30 && Double(max(dx,dy))<=max(2,Double(min(w,h))*0.14) && fill>=0.55
                    let distance=hypot(a.x-b.x,a.y-b.y)
                    if (seam || bridge>=3 && Double(max(w,h))<=Double(min(width,height))*0.15) && distance<best{best=distance;match=j;matchSeam=seam}
                }}
                guard let j=match else{joined.append(found[i]);continue};consumed[j]=true
                let a=found[i],b=found[j],aa=bounds[i],bb=bounds[j],weight=Double(masses[i])/Double(masses[i]+masses[j])
                let cx=matchSeam ? Double(min(aa[0],bb[0])+max(aa[2],bb[2]))*0.5:a.x*weight+b.x*(1-weight)
                let cy=matchSeam ? Double(min(aa[1],bb[1])+max(aa[3],bb[3]))*0.5:a.y*weight+b.y*(1-weight)
                var c=VisionBall(x:cx,y:cy,radius:Double(min(max(aa[2],bb[2])-min(aa[0],bb[0])+1,max(aa[3],bb[3])-min(aa[1],bb[1])+1))/2,time:time,confidence:min(matchSeam ? 0.8:0.6,min(a.confidence,b.confidence)))
                c.diameterX=Double(max(aa[2],bb[2])-min(aa[0],bb[0])+1);c.diameterY=Double(max(aa[3],bb[3])-min(aa[1],bb[1])+1)
                c.nearHand = !matchSeam || a.nearHand || b.nearHand;c.occluded = !matchSeam;c.luminance=a.luminance*weight+b.luminance*(1-weight)
                c.motionSupport=max(a.motionSupport,b.motionSupport);c.distinctFelt=matchSeam;c.feltChroma=a.feltChroma*weight+b.feltChroma*(1-weight);c.greenDominant=a.greenDominant && b.greenDominant;c.contrastFelt=a.contrastFelt && b.contrastFelt
                // Re-evaluate local contrast for muted felt split by a finger.
                // The joined observation stays occluded and cannot count contact.
                if !matchSeam && !c.supportsBallColor && c.feltChroma>=75 {
                    let reach=max(8,Int(ceil(c.radius*3)));var sum=0,n=0
                    for py in stride(from:max(0,Int(c.y)-reach),through:min(height-1,Int(c.y)+reach),by:2){for px in stride(from:max(0,Int(c.x)-reach),through:min(width-1,Int(c.x)+reach),by:2){
                        if hypot(Double(px)-c.x,Double(py)-c.y)<c.radius*1.8{continue}
                        let color=pixels[py*width+px],red=Int((color>>16)&255),green=Int((color>>8)&255),blue=Int(color&255)
                        sum+=max(0,green-blue)*255/max(1,max(red,green));n+=1
                    }}
                    if n>0 && c.feltChroma-Double(sum)/Double(n)>=32{c.contrastFelt=true}
                }
                joined.append(c)
            }
            found=joined
        }
        found.removeAll{b in b.radius<=12 && clippedFelt.contains{box in
            let margin=max(max(6,b.radius*3),Double(min(box[2]-box[0]+1,box[3]-box[1]+1))*0.35)
            return b.x>=Double(box[0])-margin && b.x<=Double(box[2])+margin && b.y>=Double(box[1])-margin && b.y<=Double(box[3])+margin}}
        return (found,moving)
    }
    func detect(_ pixels: [UInt32],width: Int,height: Int,time: Double,prior: VisionBall?,landmarks: [VisionPoint]) -> ([VisionBall],Bool) {
        let count=width*height
        if dimensions.0 != width || dimensions.1 != height {
            dimensions=(width,height);mask=Array(repeating:0,count:count);strong=mask;chroma=mask;oldGreen=mask;rawGreen=mask
            queue=Array(repeating:0,count:count);previous=[]
        }
        var moving=false,lightShift=0
        if previous.count==count {
            var samples=[Int]();for y in stride(from:8,to:height,by:16){for x in stride(from:8,to:width,by:16){samples.append(visionLight(pixels[y*width+x])-visionLight(previous[y*width+x]))}}
            samples.sort();lightShift=samples.isEmpty ? 0:samples[samples.count/2]
            var changed=0,border=0,borderChanged=0,counts=Array(repeating:0,count:4),changes=Array(repeating:0,count:4)
            for y in stride(from:8,to:height,by:16){for x in stride(from:8,to:width,by:16){
                let i=y*width+x,q=(x>=width/2 ? 1:0)+(y>=height/2 ? 2:0),edge=x<width*15/100 || x>width*85/100 || y<height*15/100 || y>height*85/100
                counts[q]+=1;if edge{border+=1}
                if abs(visionLight(pixels[i])-visionLight(previous[i])-lightShift)>24 {changed+=1;changes[q]+=1;if edge{borderChanged+=1}}
            }}
            alignment=VisionAlignment.estimate(pixels,previous,width:width,height:height,lightShift:lightShift,landmarks:landmarks)
            moving=Double(changed)>Double(samples.count)*0.28 && Double(borderChanged)>Double(border)*0.30 && (0..<4).filter{Double(changes[$0])>Double(counts[$0])*0.30}.count>=3 && !alignment.reliable
        } else{alignment=VisionAlignment()}
        let cols=(width+7)/8,rows=(height+7)/8
        // Samples 64px away can still be inside a close tennis ball. Resolve
        // its wider surroundings before treating its own felt as a background.
        let outerReach=max(64,min(width,height)/3)
        let contrastReaches=outerReach==64 ? [24,64]:[24,64,outerReach]
        if cutoffs.count != cols*rows {cutoffs=Array(repeating:-1,count:cols*rows);weakCutoffs=cutoffs}
        for gy in 0..<rows {for gx in 0..<cols {
            let x=min(width-1,gx*8+4),y=min(height-1,gy*8+4);var lowest=Int.max,weakLowest=Int.max,outer=false,weakOuter=false
            for reach in contrastReaches {
                var sum=0,greens=0,weakSum=0,weakCount=0
                let indices=[y*width+max(0,x-reach),y*width+min(width-1,x+reach),max(0,y-reach)*width+x,min(height-1,y+reach)*width+x]
                for i in indices {let c=pixels[i],s=strongGreen(c),l=visionLight(c);if s{greens+=1;sum+=l};if s || weakGreen(c){weakCount+=1;weakSum+=l}}
                if greens>=2{lowest=min(lowest,sum/greens)};if weakCount>=2{weakLowest=min(weakLowest,weakSum/weakCount)}
                if reach==outerReach{outer=greens>=2;weakOuter=weakCount>=2}
            }
            cutoffs[gy*cols+gx]=outer && lowest != Int.max ? lowest+12:-1
            weakCutoffs[gy*cols+gx]=weakOuter && weakLowest != Int.max ? weakLowest+12:-1
        }}
        // Borrow each buffer once for this synchronous frame. Repeated writes to
        // stored Swift Arrays otherwise repeat uniqueness/exclusivity work for
        // every pixel and every flood-fill neighbour. The original arrays stay
        // alive for the entire borrow; pointers never escape this call.
        let previousCount=previous.count
        let localCutoffs=cutoffs,localWeakCutoffs=weakCutoffs
        var detected=pixels.withUnsafeBufferPointer { pixelBuffer -> ([VisionBall],Bool) in
        let pixels=pixelBuffer.baseAddress!
        return previous.withUnsafeBufferPointer { previousBuffer -> ([VisionBall],Bool) in
        let previous=previousBuffer.baseAddress ?? pixels
        return mask.withUnsafeMutableBufferPointer { maskBuffer -> ([VisionBall],Bool) in
        let mask=maskBuffer.baseAddress!
        return strong.withUnsafeMutableBufferPointer { strongBuffer -> ([VisionBall],Bool) in
        let strong=strongBuffer.baseAddress!
        return chroma.withUnsafeMutableBufferPointer { chromaBuffer -> ([VisionBall],Bool) in
        let chroma=chromaBuffer.baseAddress!
        return rawGreen.withUnsafeMutableBufferPointer { rawBuffer -> ([VisionBall],Bool) in
        let rawGreen=rawBuffer.baseAddress!
        return oldGreen.withUnsafeBufferPointer { oldBuffer -> ([VisionBall],Bool) in
        let oldGreen=oldBuffer.baseAddress!
        return queue.withUnsafeMutableBufferPointer { queueBuffer -> ([VisionBall],Bool) in
        let queue=queueBuffer.baseAddress!
        return Self.segment(pixels:pixels,previous:previous,mask:mask,strong:strong,chroma:chroma,
            rawGreen:rawGreen,oldGreen:oldGreen,queue:queue,width:width,height:height,count:count,
            previousCount:previousCount,time:time,prior:prior,lightShift:lightShift,columns:cols,
            moving:moving,cutoffs:localCutoffs,weakCutoffs:localWeakCutoffs)
        }}}}}}}}
        // A cluster of similarly sized fragments is texture, not one ball.
        // Keep this bounded on busy backgrounds and keep the raw candidates
        // available to diagnostics rather than turning them into contact evidence.
        let texture=Array(detected.0.filter{$0.radius<=12 && $0.supportsBallColor}.prefix(192))
        for i in detected.0.indices {
            let b=detected.0[i];if b.radius>12 || b.occluded{continue}
            let reach=max(24,b.radius*6);var neighbours=0,mass=0.0
            for q in texture {
                if (q.x==b.x && q.y==b.y) || q.radius>b.radius*2.5 || hypot(q.x-b.x,q.y-b.y)>reach{continue}
                neighbours+=1;mass+=q.radius*q.radius
                if neighbours>=3 && mass>=b.radius*b.radius*0.35{break}
            }
            if neighbours>=3 && mass>=b.radius*b.radius*0.35{detected.0[i].patterned=true}
        }
        previous=pixels;swap(&oldGreen,&rawGreen)
        return detected
    }
}

public final class LocalVision {
    public var settings: VisionSettings
    private let detector=VisionDetector()
    private let scene=VisionSceneReference()
    private var path=[VisionBall](),lastFrame = -1.0,lastImpact = -10.0,unstableUntil = -1.0,serial=0
    private var width=0,height=0,lost: VisionBall?,acquisition: VisionBall?,tiny: VisionBall?,motionAcquisition: VisionBall?
    private var floorBoundaryNoticeUntil = -1.0
    private var trackSerial=0,usableCandidates=0
    private var debugCandidates=[VisionBall](),contactReason="Aguardando trajetória",rejectedTurn=false
    private func diagnosed(_ result:VisionResult)->VisionResult {
        var r=result
        let state:String
        if r.movingCamera{state="CÂMERA INSTÁVEL"}
        else if let hit=r.impact{state=hit.evidence.rawValue}
        else if rejectedTurn{state="DIREÇÃO ALTERADA · SEM CONTATO"}
        else if r.observed?.nearHand==true{state="NA MÃO · SEM IMPACTO"}
        else if r.observed != nil{state="OBSERVADO"}
        else if r.predicted != nil{state="PREVISTO · SEM MEDIÇÃO"}
        else if lost != nil{state="RASTREAMENTO PERDIDO"}
        else{state="BUSCANDO"}
        r.diagnostics=VisionTrackingDiagnostics(trackId:path.isEmpty ? nil:trackSerial,state:state,
            candidates:debugCandidates.count,usable:usableCandidates,textureRejected:debugCandidates.filter{$0.patterned}.count,
            contactReason:contactReason,samples:Array(debugCandidates.prefix(12)))
        return r
    }
    private func rejectContact(_ reason:String)->VisionImpact?{contactReason=reason;rejectedTurn=true;return nil}
    public private(set) var offset=VisionPoint(0,0)
    public init(settings: VisionSettings=VisionSettings()){self.settings=settings}
    public var activeSettings: VisionSettings {
        settings.translated(by:offset)
    }
    public func clearTrack(){path=[];lost=nil;acquisition=nil;tiny=nil;motionAcquisition=nil;lastImpact = -10}
    public func reset(){clearTrack();detector.reset();scene.reset();lastFrame = -1;unstableUntil = -1;floorBoundaryNoticeUntil = -1;offset=VisionPoint(0,0)}
    public func process(_ pixels: [UInt32],width w: Int,height h: Int,time: Double) -> VisionResult {
        guard w>0,h>0,w<=8192,h<=8192,pixels.count==w*h,time.isFinite else{return VisionResult(message:"Quadro ou timestamp inválido · descartado")}
        if width != w || height != h{reset();width=w;height=h}
        guard time>lastFrame else{return VisionResult(message:"Timestamp repetido · quadro descartado")}
        if lastFrame>=0 && time-lastFrame>0.25{path=[]};lastFrame=time
        contactReason=settings.surface == .track ? "Contagem desligada · somente rastrear":"Aguardando descida/aproximação e retorno observados";rejectedTurn=false
        let (components,cameraMoved)=detector.detect(pixels,width:w,height:h,time:time,prior:path.last,landmarks:activeSettings.polygon)
        debugCandidates=components
        let motion=detector.alignment
        var sceneChanged=cameraMoved || motion.large
        if motion.reliable && (motion.dx != 0 || motion.dy != 0) {
            let next=VisionPoint(offset.x+Double(motion.dx)/Double(w),offset.y+Double(motion.dy)/Double(h))
            if abs(next.x)<0.035 && abs(next.y)<0.035{offset=next;for i in path.indices{path[i].x+=Double(motion.dx);path[i].y+=Double(motion.dy)};if lost != nil{lost!.x+=Double(motion.dx);lost!.y+=Double(motion.dy)}}
            else{unstableUntil=time+0.25;clearTrack();sceneChanged=true}
        }
        if cameraMoved || motion.large{unstableUntil=time+0.25;clearTrack()}
        scene.update(pixels,width:w,height:h,time:time,changed:sceneChanged,compensated:motion.reliable && !sceneChanged && (motion.dx != 0 || motion.dy != 0))
        let blocked=time<unstableUntil || scene.blocked
        // Warm wood can pass the broad yellow mask. Motion alone does not turn
        // those low-chroma components into a tennis ball. A weak observation
        // may bridge a short gap only near an already colour-supported track.
        let anchor=path.last(where:{$0.supportsBallColor}),last=path.last
        var weakReference: VisionPoint?
        if let anchor,let last,time-anchor.time<=0.10,time-last.time<=0.10 {
            weakReference=VisionPoint(last.x,last.y)
            if path.count>=2 {
                let before=path[path.count-2],dt=last.time-before.time
                if dt>0{let f=min(2,max(0,(time-last.time)/dt));weakReference=VisionPoint(last.x+(last.x-before.x)*f,last.y+(last.y-before.y)*f)}
            }
        }
        let candidates=components.filter{b in
            !b.patterned && (b.supportsBallColor || (weakReference.map{hypot(b.x-$0.x,b.y-$0.y)<=max(12,(last?.radius ?? 0)*3)} ?? false))
        }
        usableCandidates=candidates.count
        var previous=path.last
        // The resolved silhouette owns an old tiny printed/highlight island.
        // Reset the old trajectory before accepting that ownership change.
        if let fragment=previous,fragment.radius<=6,candidates.filter({b in b.resolved && b.distinctFelt && b.radius>=max(16,fragment.radius*4) && hypot(b.x-fragment.x,b.y-fragment.y)<=b.radius*1.15}).count==1 {
            path=[];previous=nil;lost=nil;acquisition=nil;tiny=nil;motionAcquisition=nil
        }
        let still=previous.flatMap{b in !b.nearHand && !b.occluded && path.suffix(4).allSatisfy{hypot($0.x-b.x,$0.y-b.y)<max(2,b.radius*0.5)} ? b:nil}
        let alternatives=candidates.filter{b in b.motionSupport>=0.25 && !b.nearHand && (still.map{hypot(b.x-$0.x,b.y-$0.y)>max(12,$0.radius*3)} ?? true)}
        let alternative=alternatives.count==1 ? alternatives[0]:nil,pending=motionAcquisition;motionAcquisition=alternative
        let backgroundStill=still.map{s in candidates.contains{$0.motionSupport<0.20 && hypot($0.x-s.x,$0.y-s.y)<max(3,s.radius)}} ?? false
        var confirmed=false
        if backgroundStill,let a=alternative,let p=pending{confirmed=(0.001...0.10).contains(time-p.time) && (0.45...2.2).contains(a.radius/p.radius) && (2...Double(w)*0.23).contains(hypot(a.x-p.x,a.y-p.y))}
        if confirmed{path=[];previous=nil;lost=nil;acquisition=nil;tiny=pending}
        let prior2=path.count>=2 ? path[path.count-2]:nil
        var prediction=previous.map{VisionPoint($0.x,$0.y)}
        if let p=previous,let q=prior2,p.time>q.time{let f=min(2,max(0,(time-p.time)/(p.time-q.time)));prediction=VisionPoint(p.x+(p.x-q.x)*f,p.y+(p.y-q.y)*f)}
        let eligible=candidates.filter{b in previous.map{ (($0.nearHand || $0.occluded) ? (0.35...3.5):(0.48...2.1)).contains(b.radius/$0.radius)} ?? true}
        var acquiring=eligible
        if previous==nil {
            let moving=eligible.filter{$0.motionSupport>=0.25};if !moving.isEmpty{acquiring=moving}
            let felt=acquiring.filter{$0.distinctFelt},resolved=acquiring.filter{$0.resolved}
            let vivid=felt.filter{$0.feltChroma>=140}
            if !vivid.isEmpty{acquiring=vivid}else if !felt.isEmpty{acquiring=felt}else if !resolved.isEmpty{acquiring=resolved}
            if acquiring.count>1{let moving=acquiring.filter{$0.motionSupport>=0.25};if !moving.isEmpty{acquiring=moving}}
            if acquiring.contains(where:{$0.radius>=max(16,Double(min(w,h))*0.08) && $0.confidence>=0.60}){acquiring=acquiring.filter{$0.radius>=4}}
        }
        var ranked=[(VisionBall,Double)]()
        for b in acquiring {
            guard let p=previous else{ranked.append((b,-b.confidence*b.radius));continue}
            let dt=time-p.time,step=prior2.map{hypot(p.x-$0.x,p.y-$0.y)*dt/(p.time-$0.time)} ?? 0
            let timeGate=Double(w)*min(0.23,0.12*max(1,dt*60))
            let uniqueFast=prior2==nil && acquiring.count==1 && p.resolved && b.resolved && b.motionSupport>=0.25 &&
                (0.75...1.33).contains(b.radius/p.radius) && abs(b.feltChroma-p.feltChroma)<35 && (0.65...1.5).contains(b.luminance/max(1,p.luminance))
            let predictedFast=prior2 != nil && b.resolved && b.motionSupport>=0.25 && dt<=0.075 &&
                (prediction.map{hypot(b.x-$0.x,b.y-$0.y)<max(12,step*0.35+p.radius*2)} ?? false)
            let gate=p.nearHand ? Double(w)*0.36:uniqueFast ? Double(w)*0.28:predictedFast ? min(Double(w)*0.5,max(timeGate,step*1.5+p.radius*4)):min(Double(w)*0.23,max(timeGate,step*1.8+p.radius*4))
            if hypot(b.x-p.x,b.y-p.y)<=gate,let predicted=prediction{ranked.append((b,hypot(b.x-predicted.x,b.y-predicted.y)+abs(b.radius-p.radius)*4))}
        }
        if previous?.nearHand==true || previous?.occluded==true{let clean=ranked.filter{$0.0.resolved};if !clean.isEmpty{ranked=clean}}
        ranked.sort{$0.1<$1.1};var ball=ranked.first?.0
        if ranked.count>1 && (previous==nil || abs(ranked[1].1-ranked[0].1)<max(4,(ball?.radius ?? 1)*1.5)){ball=nil}
        if previous==nil,let b=ball,(b.radius<2.5 || b.colorUncertain || b.feltChroma<82 && !b.greenDominant),!b.occluded,!confirmed {
            let pending=tiny;tiny=b
            if let t=pending {
                let distance=hypot(b.x-t.x,b.y-t.y)
                if !(0.001...0.10).contains(time-t.time) || b.motionSupport<0.25 || distance<max(2,b.radius) || distance>Double(w)*0.15 || !(0.6...1.65).contains(b.radius/t.radius){ball=nil}
            } else{ball=nil}
        } else if ball != nil{tiny=nil}
        if previous==nil,let b=ball,let l=lost,time-l.time<0.65 {
            let near=hypot(b.x-l.x,b.y-l.y)<max(Double(w)*0.16,l.radius*10)
            let consistent=acquisition.map{time-$0.time<0.075 && (0.6...1.65).contains(b.radius/$0.radius) && hypot(b.x-$0.x,b.y-$0.y)<Double(w)*0.12} ?? false
            if !near && (!consistent || b.motionSupport<0.20){acquisition=b;ball=nil}
        }
        guard var b=ball else {
            let age=previous.map{time-$0.time} ?? 1
            // Retain a clean observed descending floor leg briefly; prediction
            // still expires after 60 ms and no missing sample is manufactured.
            let hold=canBridgeFloorLoss() ? 0.16:0.10
            if age>hold{lost=previous ?? lost;path=[]}
            return diagnosed(VisionResult(predicted:age<0.06 ? prediction:nil,trail:path.suffix(16).map{VisionPoint($0.x,$0.y)},movingCamera:blocked,message:scene.needsConfirmation ? "Treino ativo · confira a área; impactos suspensos":blocked ? "Treino ativo · aguardando imagem estável":candidates.count>1 ? "Alvos verdes ambíguos · aguardando continuidade":"Bola não localizada · sem inventar posição",area:activeSettings,cameraOffset:offset,areaNeedsConfirmation:scene.needsConfirmation))
        }
        if !b.supportsBallColor{b.colorUncertain=true}
        if path.isEmpty{trackSerial+=1}
        path.append(b);lost=nil;acquisition=nil
        while path.count>160 || (path.first.map{time-$0.time>0.6} ?? false){path.removeFirst()}
        let impact = !blocked ? contact():nil,speed=velocity(path)
        let message: String
        if scene.needsConfirmation{message="Treino ativo · confira a área em Marcar área; impactos suspensos"}
        else if blocked{message="Treino ativo · aguardando a imagem estabilizar; impactos suspensos"}
        else if time<floorBoundaryNoticeUntil{message="Retorno acima do piso marcado · ajuste o limite ou use Toda a imagem é piso"}
        else if b.occluded{message="Bola parcialmente oculta · contato suspenso"}
        else if !b.resolved{message="Bola ≈\(Int(b.radius*2)) px · detalhe incerto · revise IN/OUT"}
        else if b.nearHand{message="Bola perto da mão · impacto suspenso"}
        else if b.blurred{message="Borrão observado · velocidade indisponível"}
        else{message="Bola observada"}
        return diagnosed(VisionResult(observed:b,trail:path.suffix(16).map{VisionPoint($0.x,$0.y)},impact:impact,movingCamera:blocked,speed:speed,message:message,area:activeSettings,cameraOffset:offset,areaNeedsConfirmation:scene.needsConfirmation))
    }
    private func velocity(_ p: [VisionBall]) -> Double? {
        guard p.count>=3,let b=p.last,!b.blurred,!b.nearHand,b.resolved else{return nil}
        var start=p.count-1
        // A time-supported interval also works at 240 FPS, where four samples
        // cover only 12.5 ms. Never cross missing, hidden or hand-held evidence.
        while start>0 {
            let prior=p[start-1],gap=p[start].time-prior.time
            if prior.blurred || prior.nearHand || !prior.resolved || !(0.000001...0.075).contains(gap) || b.time-prior.time>0.22{break}
            start-=1
            if p.count-start>=3 && b.time-p[start].time>=0.02{break}
        }
        let supported=Array(p[start...]);guard supported.count>=3,let a=supported.first else{return nil}
        let dt=b.time-a.time,rs=supported.map{$0.radius}.sorted(),r=rs[rs.count/2]
        guard (0.015...0.22).contains(dt),r>=3,rs.last!/rs.first!<=1.35 else{return nil}
        let speed=hypot(b.x-a.x,b.y-a.y)/dt*0.067/(r*2)*3.6
        return speed.isFinite && (0.8...250).contains(speed) ? speed:nil
    }
    private func contact() -> VisionImpact? {
        guard settings.surface != .track,path.count>=5 else{return nil}
        let simple=settings.surface == .floor ? floorEvidence():wallEvidence()
        let robust=simple==nil && (settings.surface == .wall || floorReferenceKnown()) ? ObservedContactFit.find(path,surface:settings.surface):nil
        let depth=simple==nil && robust==nil && (settings.surface == .wall || floorReferenceKnown()) ? DepthContactFit.find(path,width:width,height:height):nil
        guard let p=simple ?? robust?.support ?? depth?.support else{
            contactReason=path.contains{$0.nearHand || $0.occluded} ? "Mão/oclusão na trajetória · aguardando voo livre":"Sem retorno coerente observado dos dois lados do contato";return nil
        }
        let a=p[0],b=p[1],c=p[2],d=p[3],e=p[4],evidence=path.filter{$0.time>=p[0].time && $0.time<=p[4].time}
        // Even REVIEW needs ball-colour evidence on both observed legs.
        // One coloured sample at the turn cannot certify a whole trajectory.
        guard evidence.contains(where:{$0.time<c.time && $0.supportsBallColor}),
              evidence.contains(where:{$0.time>c.time && $0.supportsBallColor}) else{return rejectContact("Cor/forma sem suporte nos dois lados do retorno")}
        if c.time-lastImpact<0.18 || e.time-a.time>0.65 || c.nearHand || (d.nearHand && e.nearHand) || zip(evidence,evidence.dropFirst()).contains(where:{$0.1.time-$0.0.time>0.25}){return rejectContact("Retorno duplicado, ocluso ou sem continuidade temporal")}
        let travel=max(3,c.radius*0.7)
        let floorTurn=c.y-a.y>travel && c.y-e.y>travel && b.y>=a.y && d.y>=e.y && c.y>=b.y && c.y>=d.y
        let incoming=VisionPoint((b.x-a.x)/(b.time-a.time),(b.y-a.y)/(b.time-a.time))
        let outgoing=VisionPoint((e.x-d.x)/(e.time-d.time),(e.y-d.y)/(e.time-d.time))
        let sa=hypot(incoming.x,incoming.y),sb=hypot(outgoing.x,outgoing.y)
        let wallTurn=sa>20 && sb>20 && (incoming.x*outgoing.x+incoming.y*outgoing.y)/(sa*sb) < 0.35
        if robust==nil && depth==nil && (settings.surface == .floor && !floorTurn || settings.surface == .wall && !wallTurn){return nil}
        // Outer curved legs establish a slow rebound, but locate its mark
        // using continuous samples at the observed peak whenever available.
        let local=e.time-a.time>0.200001 ? localFloorPeak(evidence):nil
        let fit=(robust ?? depth).map{Fit(x:$0.x,y:$0.y,time:$0.time,error:$0.error)} ?? (settings.surface == .floor ? (local.flatMap{floorContact($0)} ?? floorContact(p)):wallContact(p))
        if settings.surface == .wall && fit==nil{return nil}
        let t=fit?.time ?? c.time
        // Tiny colour fragments are common on textured walls and compression edges.
        // A REVIEW is still a contact claim: require a coherent observed trajectory.
        if !c.resolved {
            let radii=p.map{$0.radius},lights=p.map{$0.luminance}
            if radii.max()!/radii.min()!>1.65 || lights.max()!/max(1,lights.min()!)>1.8 || (settings.surface == .floor && fit==nil){return nil}
        }
        let point=VisionPoint((fit?.x ?? c.x)/Double(width),((fit?.y ?? c.y)+(settings.surface == .floor && depth==nil ? c.radius:0))/Double(height))
        let intervals=zip(evidence,evidence.dropFirst()).map{$0.1.time-$0.0.time}.sorted(),sparse=intervals.last!>0.075 || intervals.last!>intervals[1]*1.55+0.001
        let missing=sparse ? max(max(hypot(c.x-b.x,c.y-b.y),hypot(d.x-c.x,d.y-c.y))*0.5,max(sa,sb)*intervals.last!*0.5):0
        let localization=settings.surface == .floor ? floorLocalizationNoise(evidence,turn:c.time):0
        let uncertainty=max(2,c.radius*0.3+(c.blurred ? c.radius*0.6:0)+(fit?.error ?? 0)+missing+localization),area=activeSettings
        // The whole-image floor preset is not a measured physical separator.
        if settings.surface == .wall,settings.floorLine != VisionFloorBoundaryDraft.wholeImage,
           let distance=VisionGeometry.floorDistance(point,area.floorLine,width,height),distance>uncertainty+c.radius{
            return rejectContact("Retorno sobre o piso no modo parede · selecione Chão")
        }
        let floorDistance=settings.surface == .floor && settings.court == .custom ? VisionGeometry.floorDistance(point,area.floorLine,width,height):nil
        if let dist=floorDistance,dist+uncertainty<0{floorBoundaryNoticeUntil=lastFrame+0.8;return rejectContact("Retorno acima do limite do piso · sem evidência de contato com o chão")}
        var call=VisionGeometry.classify(point,radius:c.radius,uncertainty:uncertainty,polygon:area.polygon,width:width,height:height)
        let courtKnown=settings.court != .custom && VisionGeometry.projectedPolygon(area.polygon)
        let floorKnown=settings.surface != .floor || courtKnown || VisionGeometry.onFloor(point,area.floorLine)
        if call != .unmarked && (!floorKnown || sparse || settings.surface == .floor && fit==nil || evidence.contains(where:{$0.nearHand || !$0.resolved}) || settings.surface == .wall || depth != nil){call = .review}
        let reason: String
        if depth != nil{reason="Retorno em profundidade observado pelo tamanho da bola. Centro e contato são estimados; confirme a superfície na revisão."}
        else if settings.surface == .wall{reason="Mudança de direção observada; contato com a parede estimado entre quadros. Confirme na revisão."}
        else if evidence.contains(where:{!$0.resolved}){reason="Detalhe insuficiente na bola. Confira contato e linha."}
        else if !floorKnown{reason="Contato com o piso não comprovado. Confira a superfície e a marca."}
        else if sparse{reason="Quadros ausentes ou espaçados. Posição estimada; revise."}
        else if fit==nil{reason="Instante do contato incerto; confirme na revisão."}
        else if evidence.contains(where:{$0.nearHand}){reason="Possível mão na trajetória. Confirme o contato."}
        else if call == .review{reason="A incerteza alcança a linha. Revise o contato."}
        else if call == .unmarked{reason="Quicada candidata; marque a área para IN/OUT."}
        else{reason="Descida e retorno compatíveis com quicada; contato estimado."}
        let incomingSpeed=velocity(path.filter{$0.time<=c.time})
        serial+=1;lastImpact=t;floorBoundaryNoticeUntil = -1;contactReason=reason;path.removeAll{$0.time<=c.time}
        return VisionImpact(id:serial,time:t,point:point,radius:c.radius,call:call,speed:incomingSpeed,uncertainty:uncertainty,reason:reason,surface:settings.surface,cameraOffset:offset,evidenceOffset:offset)
    }
    private struct Fit {var x: Double,y: Double,time: Double,error: Double}
    private func floorReferenceKnown()->Bool {
        let area=activeSettings
        return settings.surface == .floor && (VisionGeometry.projectedFloorLine(area.floorLine) || settings.court != .custom && VisionGeometry.projectedPolygon(area.polygon))
    }
    private func cleanFloorSample(_ p:VisionBall)->Bool{p.resolved && p.supportsBallColor && !p.nearHand && !p.blurred}
    private func canBridgeFloorLoss()->Bool {
        guard floorReferenceKnown(),path.count>=3 else{return false}
        let p=Array(path.suffix(3)),a=p[0],b=p[2],r=p.map{$0.radius}
        return p.allSatisfy{cleanFloorSample($0)} && r.max()!/r.min()!<=1.35 &&
            zip(p,p.dropFirst()).allSatisfy{(0.000001...0.075).contains($0.1.time-$0.0.time) && $0.1.y >= $0.0.y-0.75} && b.y-a.y>max(2,b.radius*0.3)
    }
    private func localFloorPeak(_ points:[VisionBall])->[VisionBall]? {
        guard let peak=points.indices.max(by:{points[$0].y<points[$1].y}),peak>=2,peak+2<points.count else{return nil}
        let p=Array(points[(peak-2)...(peak+2)])
        if zip(p,p.dropFirst()).contains(where:{$0.1.time-$0.0.time>0.075}) || p[2].y<=p[0].y || p[2].y<=p[4].y{return nil}
        return p
    }
    private func wallEvidence()->[VisionBall]? {
        // Time/displacement support preserves a visible return at 120/240 FPS;
        // every supporting sample remains observed, not a predicted position.
        for stride in 1...max(1,(path.count-1)/4) {
            let start=path.count-1-stride*4
            if start<0{break}
            let p=(0...4).map{path[start+$0*stride]},a=p[0],b=p[1],c=p[2],d=p[3],e=p[4]
            if e.time-a.time>0.240001{break}
            let raw=Array(path[start...]),radii=raw.map{$0.radius}
            if raw.contains(where:{$0.nearHand || $0.occluded}) || radii.max()!/radii.min()!>1.65{continue}
            let travel=max(3,c.radius*0.5)
            if hypot(c.x-a.x,c.y-a.y)<travel || hypot(c.x-e.x,c.y-e.y)<travel{continue}
            let vx=(b.x-a.x)/(b.time-a.time),vy=(b.y-a.y)/(b.time-a.time)
            let ux=(e.x-d.x)/(e.time-d.time),uy=(e.y-d.y)/(e.time-d.time),sa=hypot(vx,vy),sb=hypot(ux,uy)
            if sa<=20 || sb<=20 || (vx*ux+vy*uy)/(sa*sb)>=0.35{continue}
            guard let fit=wallContact(p) else{continue}
            let error=raw.map{q -> Double in
                let before=q.time<=fit.time,anchor=before ? a:e
                return hypot(q.x-anchor.x-(before ? vx:ux)*(q.time-anchor.time),q.y-anchor.y-(before ? vy:uy)*(q.time-anchor.time))
            }.max() ?? .infinity
            if error>max(2,c.radius*0.35){continue}
            return p
        }
        return nil
    }
    private func floorEvidence()->[VisionBall]? {
        // Bound physical time instead of fixed frame count. Only marked-floor,
        // clean, coherent observations can support the longer rebound window.
        for stride in 1...((path.count-1)/4) {
            let start=path.count-1-stride*4,a=path[start],b=path[start+stride],c=path[start+stride*2],d=path[start+stride*3],e=path.last!
            let span=e.time-a.time;if span>0.420001{break}
            let travel=max(3,c.radius*0.7)
            if c.y-a.y<=travel || c.y-e.y<=travel || b.y<a.y || d.y<e.y || c.y<b.y || c.y<d.y{continue}
            if stride>1 {
                let raw=path[start...],extended=span>0.200001
                if c.nearHand || raw.contains(where:{$0.nearHand || $0.occluded}){continue}
                let rs=raw.map{$0.radius}
                if extended && (!floorReferenceKnown() || raw.contains(where:{!cleanFloorSample($0)}) || rs.max()!/rs.min()!>1.35){continue}
                let middle=start+stride*2
                let noisy=(start..<middle).contains(where:{path[$0+1].y<path[$0].y-0.75}) || (middle..<path.count-1).contains(where:{path[$0+1].y>path[$0].y+0.75})
                let factor=extended ? 0.7:0.9
                if (noisy || extended) && (!coherentFloorLeg(Array(path[start...middle]),radius:c.radius,descending:true,factor:factor) || !coherentFloorLeg(Array(path[middle...]),radius:c.radius,descending:false,factor:factor)){continue}
            };return [a,b,c,d,e]
        };return nil
    }
    private struct FloorLegSupport{var displacement:Double,error:Double,rms:Double}
    private func floorLegSupport(_ points:[VisionBall])->FloorLegSupport? {
        guard points.count>=3,let first=points.first,let last=points.last,last.time>first.time else{return nil}
        let span=last.time-first.time,n=Double(points.count)
        let mt=points.reduce(0){$0+$1.time-first.time}/n,my=points.reduce(0){$0+$1.y}/n
        let den=points.reduce(0){let t=$1.time-first.time-mt;return $0+t*t};guard den>1e-12 else{return nil}
        let slope=points.reduce(0){$0+($1.time-first.time-mt)*($1.y-my)}/den
        let errors=points.map{abs($0.y-my-slope*($0.time-first.time-mt))}
        return FloorLegSupport(displacement:slope*span,error:errors.max() ?? 0,rms:sqrt(errors.reduce(0){$0+$1*$1}/n))
    }
    private func coherentFloorLeg(_ points:[VisionBall],radius:Double,descending:Bool,factor:Double=0.9)->Bool {
        guard let support=floorLegSupport(points) else{return false}
        let progress=support.displacement*(descending ? 1 : -1)
        return progress>=max(5,radius*factor) && support.error<=max(2,radius*0.35) && support.rms<=max(0.75,progress*0.18)
    }
    private func floorLocalizationNoise(_ points:[VisionBall],turn:Double)->Double {
        let before=points.filter{$0.time<=turn},after=points.filter{$0.time>=turn}
        let noisy=zip(before,before.dropFirst()).contains{$0.1.y<$0.0.y-0.75} || zip(after,after.dropFirst()).contains{$0.1.y>$0.0.y+0.75}
        if !noisy{return 0}
        return max(floorLegSupport(before)?.error ?? 0,floorLegSupport(after)?.error ?? 0)
    }
    private func wallContact(_ p:[VisionBall])->Fit? {
        let a=p[0],b=p[1],c=p[2],d=p[3],e=p[4]
        let vx=(b.x-a.x)/(b.time-a.time),vy=(b.y-a.y)/(b.time-a.time)
        let ux=(e.x-d.x)/(e.time-d.time),uy=(e.y-d.y)/(e.time-d.time)
        let bx=a.x+vx*(c.time-a.time),by=a.y+vy*(c.time-a.time)
        let ax=d.x+ux*(c.time-d.time),ay=d.y+uy*(c.time-d.time)
        let dx=vx-ux,dy=vy-uy,den=dx*dx+dy*dy
        guard den>0.000001 else{return nil}
        let t=c.time+((ax-bx)*dx+(ay-by)*dy)/den
        guard t.isFinite,t>=b.time,t<=d.time else{return nil}
        let x1=a.x+vx*(t-a.time),y1=a.y+vy*(t-a.time),x2=d.x+ux*(t-d.time),y2=d.y+uy*(t-d.time)
        let mx=c.time<=t ? bx:ax,my=c.time<=t ? by:ay
        let error=hypot(mx-c.x,my-c.y)+hypot(x1-x2,y1-y2)*0.5
        guard error.isFinite,error<=max(4,c.radius*2) else{return nil}
        return Fit(x:(x1+x2)/2,y:(y1+y2)/2,time:t,error:error)
    }
    private func floorContact(_ p: [VisionBall]) -> Fit? {
        let a=p[0],b=p[1],c=p[2],d=p[3],e=p[4],up=(b.y-a.y)/(b.time-a.time),down=(e.y-d.y)/(e.time-d.time)
        guard up>0,down<0 else{return nil}
        let before=a.y+up*(c.time-a.time),after=d.y+down*(c.time-d.time),t=c.time+(after-before)/(up-down)
        guard t.isFinite,t>=b.time,t<=d.time else{return nil}
        let vx=(b.x-a.x)/(b.time-a.time),ux=(e.x-d.x)/(e.time-d.time),x1=a.x+vx*(t-a.time),x2=d.x+ux*(t-d.time),y=a.y+up*(t-a.time)
        let mx=c.time<=t ? a.x+vx*(c.time-a.time):d.x+ux*(c.time-d.time),my=c.time<=t ? before:after
        let error=hypot(mx-c.x,my-c.y)+abs(x1-x2)*0.5
        guard error.isFinite,error<=max(c.radius*4,8) else{return nil}
        if error>max(2,c.radius*0.35){return Fit(x:c.x,y:c.y,time:c.time,error:error)}
        return Fit(x:(x1+x2)/2,y:max(y,c.y),time:t,error:error)
    }
}
