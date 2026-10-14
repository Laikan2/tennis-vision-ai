import Foundation

// Local green-tennis-ball analysis. No network, model download or PC dependency.
public struct VisionPoint: Codable, Equatable {
    public var x: Double; public var y: Double
    public init(_ x: Double,_ y: Double) { self.x=x;self.y=y }
}
public enum VisionSurface: String, Codable, CaseIterable { case floor, wall, track }
public enum VisionCourt: String, Codable, CaseIterable { case custom, full, half }
public enum VisionCall: String, Codable { case inside, outside, review, unmarked, rejected }
public struct VisionSettings: Codable, Equatable {
    public var polygon=[VisionPoint](), floorLine=[VisionPoint]()
    public var surface=VisionSurface.floor, court=VisionCourt.full
    public var sound=true, marks=true
    public init() {}
}
public struct VisionBall {
    public var x: Double,y: Double,radius: Double,time: Double,confidence: Double
    public var nearHand=false, blurred=false, luminance=0.0, motionSupport=1.0, occluded=false, colorUncertain=false
    public var resolved: Bool { !occluded && !colorUncertain && radius>=2.5 && confidence>=0.5 }
}
public struct VisionImpact: Codable, Identifiable {
    public var id: Int,time: Double,point: VisionPoint,radius: Double,call: VisionCall
    public var speed: Double?,uncertainty: Double,reason: String,surface: VisionSurface
    public var reviewed: VisionCall?
    public var cameraOffset=VisionPoint(0,0),evidenceOffset=VisionPoint(0,0)
    public var decision: VisionCall { reviewed ?? call }
}
public struct VisionResult {
    public var observed: VisionBall?,predicted: VisionPoint?,trail=[VisionPoint](),impact: VisionImpact?
    public var movingCamera=false,speed: Double?,message="Bola não localizada"
    public var area=VisionSettings(),cameraOffset=VisionPoint(0,0)
}
public enum VisionGeometry {
    public static func valid(_ p: [VisionPoint]) -> Bool {
        guard p.count==4,p.allSatisfy({$0.x.isFinite && $0.y.isFinite && (0...1).contains($0.x) && (0...1).contains($0.y)}) else{return false}
        let turns=(0..<4).map { i -> Double in
            let a=p[i],b=p[(i+1)%4],c=p[(i+2)%4];return (b.x-a.x)*(c.y-b.y)-(b.y-a.y)*(c.x-b.x)
        }
        var twiceArea=0.0
        for i in 0..<4{let next=(i+1)%4;twiceArea+=p[i].x*p[next].y-p[next].x*p[i].y}
        let area=abs(twiceArea)/2
        return area>=0.005 && (turns.allSatisfy{$0>0.00001} || turns.allSatisfy{$0 < -0.00001})
    }
    public static func validLine(_ p: [VisionPoint]) -> Bool {
        p.count==2 && p.allSatisfy{(0...1).contains($0.x) && (0...1).contains($0.y)} && abs(p[1].x-p[0].x)>0.2
    }
    public static func floorDistance(_ p: VisionPoint,_ line: [VisionPoint],_ width: Int,_ height: Int) -> Double? {
        guard validLine(line) else{return nil};let a=line[0],b=line[1],w=Double(width),h=Double(height)
        let slope=(b.y-a.y)*h/((b.x-a.x)*w),y=(a.y+(p.x-a.x)*(b.y-a.y)/(b.x-a.x))*h
        return (p.y*h-y)/sqrt(1+slope*slope)
    }
    public static func classify(_ p: VisionPoint,radius: Double,uncertainty: Double,polygon: [VisionPoint],width: Int,height: Int) -> VisionCall {
        guard valid(polygon) else{return .unmarked}
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
        guard valid(p),court != .custom else{return []}
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
    return g>=45 && r*100>g*42 && g*100>=r*86 && g*100>b*132 && g-b>18
}
@inline(__always) private func weakGreen(_ c: UInt32) -> Bool {
    let r=Int((c>>16)&255),g=Int((c>>8)&255),b=Int(c&255)
    return g>=45 && r*100>g*55 && g*100>=r*92 && g*100>b*114 && g-b>16
}
@inline(__always) private func skinColor(_ c: UInt32) -> Bool {
    let r=Int((c>>16)&255),g=Int((c>>8)&255),b=Int(c&255)
    return r>=32 && g>=18 && b>=10 && r*100>g*112 && g*100>b*108 && r-g>7
}
final class VisionDetector {
    private var mask=[UInt8](),strong=[UInt8](),previous=[UInt32](),oldGreen=[UInt8](),rawGreen=[UInt8]()
    private var queue=[Int](),cutoffs=[Int](),weakCutoffs=[Int]()
    private var dimensions=(0,0)
    var alignment=VisionAlignment()
    func reset(){previous=[]}
    func detect(_ pixels: [UInt32],width: Int,height: Int,time: Double,prior: VisionBall?,landmarks: [VisionPoint]) -> ([VisionBall],Bool) {
        let count=width*height
        if dimensions.0 != width || dimensions.1 != height {
            dimensions=(width,height);mask=Array(repeating:0,count:count);strong=mask;oldGreen=mask;rawGreen=mask
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
        if cutoffs.count != cols*rows {cutoffs=Array(repeating:-1,count:cols*rows);weakCutoffs=cutoffs}
        for gy in 0..<rows {for gx in 0..<cols {
            let x=min(width-1,gx*8+4),y=min(height-1,gy*8+4);var lowest=Int.max,weakLowest=Int.max,outer=false,weakOuter=false
            for reach in [24,64] {
                var sum=0,greens=0,weakSum=0,weakCount=0
                let indices=[y*width+max(0,x-reach),y*width+min(width-1,x+reach),max(0,y-reach)*width+x,min(height-1,y+reach)*width+x]
                for i in indices {let c=pixels[i],s=strongGreen(c),l=visionLight(c);if s{greens+=1;sum+=l};if s || weakGreen(c){weakCount+=1;weakSum+=l}}
                if greens>=2{lowest=min(lowest,sum/greens)};if weakCount>=2{weakLowest=min(weakLowest,weakSum/weakCount)}
                if reach==64{outer=greens>=2;weakOuter=weakCount>=2}
            }
            cutoffs[gy*cols+gx]=outer && lowest != Int.max ? lowest+12:-1
            weakCutoffs[gy*cols+gx]=weakOuter && weakLowest != Int.max ? weakLowest+12:-1
        }}
        let search=prior.map{max(14,$0.radius*3)} ?? 0
        for i in 0..<count {
            let c=pixels[i],s=strongGreen(c);strong[i]=s ? 1:0
            var selected=s || weakGreen(c);rawGreen[i]=selected ? 1:0
            if selected {
                let y=i/width,x=i-y*width,old=previous.count==count ? previous[i]:0
                let tracked=prior.map{abs(Double(x)-$0.x)<=search && abs(Double(y)-$0.y)<=search && Double(visionLight(c)-lightShift)>=$0.luminance*0.82} ?? false
                if !tracked && (previous.isEmpty || oldGreen[i]==1 && visionLight(c)-visionLight(old)-lightShift<=8) {
                    let tile=(y>>3)*cols+(x>>3),cutoff=s ? cutoffs[tile]:max(cutoffs[tile],weakCutoffs[tile])
                    if visionLight(c)<cutoff{selected=false}
                }
            }
            mask[i]=selected ? 1:0
        }
        var found=[VisionBall](),bounds=[[Int]](),masses=[Int]()
        for seed in 0..<count where mask[seed]==1 {
            var head=0,tail=1,xmin=width,xmax=0,ymin=height,ymax=0,sxmin=width,sxmax = -1,symin=height,symax = -1
            var sx=0.0,sy=0.0,light=0.0,wx=0.0,wy=0.0,weights=0.0,changed=0,strongCount=0
            queue[0]=seed;mask[seed]=0
            while head<tail {
                let i=queue[head],x=i%width,y=i/width;head+=1
                xmin=min(xmin,x);xmax=max(xmax,x);ymin=min(ymin,y);ymax=max(ymax,y)
                sx+=Double(x);sy+=Double(y);light+=Double(visionLight(pixels[i]))
                if strong[i]==1{strongCount+=1;sxmin=min(sxmin,x);sxmax=max(sxmax,x);symin=min(symin,y);symax=max(symax,y)}
                let weight=Double(max(1,Int((pixels[i]>>8)&255)-Int(pixels[i]&255)))
                weights+=weight;wx+=Double(x)*weight;wy+=Double(y)*weight
                if previous.count==count && abs(visionLight(pixels[i])-visionLight(previous[i])-lightShift)>12{changed+=1}
                func add(_ n: Int){if mask[n]==1{mask[n]=0;queue[tail]=n;tail+=1}}
                if x>0{add(i-1)};if x+1<width{add(i+1)};if y>0{add(i-width)};if y+1<height{add(i+width)}
            }
            let w=xmax-xmin+1,h=ymax-ymin+1,fill=Double(tail)/Double(w*h),aspect=Double(max(w,h))/Double(min(w,h))
            guard strongCount>0,tail>=3,Double(tail)<=Double(count)*0.025,min(w,h)>=2,Double(max(w,h))<=Double(min(width,height))*0.23,aspect<=8,fill>=0.35 else{continue}
            if min(w,h)>=6 && fill>0.94{continue}
            let x=min(w,h)<9 ? wx/weights:sx/Double(tail),y=min(w,h)<9 ? wy/weights:sy/Double(tail),radius=Double(min(w,h))/2
            let weak=Double(strongCount)<Double(tail)*0.25,halo=weak || (min(w,h)>=5 && min(sxmax-sxmin+1,symax-symin+1)<5)
            if weak && (max(w,h)>12 || aspect>4){continue}
            if xmin==0 || ymin==0 || xmax==width-1 || ymax==height-1{continue}
            let followed=prior.map{time-$0.time<0.14 && hypot(x-$0.x,y-$0.y)<max(30,$0.radius*6)} ?? false
            if aspect>4.5 && Double(changed)<Double(tail)*0.25 && !followed{continue}
            var skin=0,movingSkin=0,ring=0;let reach=max(6,Int(radius*2.4))
            for py in stride(from:max(0,Int(y)-reach),through:min(height-1,Int(y)+reach),by:2){for px in stride(from:max(0,Int(x)-reach),through:min(width-1,Int(x)+reach),by:2){
                if hypot(Double(px)-x,Double(py)-y)<radius*1.2{continue};ring+=1
                let i=py*width+px,c=pixels[i]
                if skinColor(c){skin+=1;if previous.count==count && !strongGreen(previous[i]) && abs(visionLight(c)-visionLight(previous[i])-lightShift)>12{movingSkin+=1}}
            }}
            var ball=VisionBall(x:x,y:y,radius:radius,time:time,confidence:halo ? min(fill,0.45):min(fill,min(w,h)<5 ? 0.6:0.95))
            ball.nearHand=ring>0 && Double(skin)>Double(ring)*0.18 && (Double(movingSkin)>Double(ring)*0.12 || prior?.nearHand==true && Double(skin)<Double(ring)*0.85)
            ball.blurred=aspect>1.6;ball.luminance=light/Double(tail);ball.motionSupport=previous.isEmpty ? 0:Double(changed)/Double(tail);ball.colorUncertain=halo
            found.append(ball);bounds.append([xmin,ymin,xmax,ymax]);masses.append(tail)
        }
        if (2...48).contains(found.count) {
            var consumed=Array(repeating:false,count:found.count),joined=[VisionBall]()
            for i in found.indices where !consumed[i] {
                var match: Int?,best=Double.infinity
                if i+1<found.count {for j in i+1..<found.count where !consumed[j] {
                    let a=found[i],b=found[j],aa=bounds[i],bb=bounds[j]
                    let w=max(aa[2],bb[2])-min(aa[0],bb[0])+1,h=max(aa[3],bb[3])-min(aa[1],bb[1])+1
                    let fill=Double(masses[i]+masses[j])/Double(w*h)
                    guard min(w,h)>=6,Double(max(w,h))<=Double(min(width,height))*0.15,Double(max(w,h))<=Double(min(w,h))*1.55,(0.40...0.90).contains(fill) else{continue}
                    let dx=max(0,max(aa[0],bb[0])-min(aa[2],bb[2])-1),dy=max(0,max(aa[1],bb[1])-min(aa[3],bb[3])-1)
                    if Double(max(dx,dy))>max(4,Double(min(w,h))*0.22){continue}
                    var bridge=0
                    for k in 0...4{let f=0.35+Double(k)*0.075,px=Int(a.x+(b.x-a.x)*f),py=Int(a.y+(b.y-a.y)*f);if skinColor(pixels[py*width+px]){bridge+=1}}
                    let distance=hypot(a.x-b.x,a.y-b.y);if bridge>=3 && distance<best{best=distance;match=j}
                }}
                guard let j=match else{joined.append(found[i]);continue};consumed[j]=true
                let a=found[i],b=found[j],aa=bounds[i],bb=bounds[j],weight=Double(masses[i])/Double(masses[i]+masses[j])
                var c=VisionBall(x:a.x*weight+b.x*(1-weight),y:a.y*weight+b.y*(1-weight),radius:Double(min(max(aa[2],bb[2])-min(aa[0],bb[0])+1,max(aa[3],bb[3])-min(aa[1],bb[1])+1))/2,time:time,confidence:min(0.6,min(a.confidence,b.confidence)))
                c.nearHand=true;c.occluded=true;c.luminance=a.luminance*weight+b.luminance*(1-weight);c.motionSupport=max(a.motionSupport,b.motionSupport);joined.append(c)
            }
            found=joined
        }
        previous=pixels;swap(&oldGreen,&rawGreen)
        return (found,moving)
    }
}

public final class LocalVision {
    public var settings: VisionSettings
    private let detector=VisionDetector()
    private var path=[VisionBall](),lastFrame = -1.0,lastImpact = -10.0,unstableUntil = -1.0,serial=0
    private var width=0,height=0,lost: VisionBall?,acquisition: VisionBall?,tiny: VisionBall?,motionAcquisition: VisionBall?
    public private(set) var offset=VisionPoint(0,0)
    public init(settings: VisionSettings=VisionSettings()){self.settings=settings}
    public var activeSettings: VisionSettings {
        var s=settings;s.polygon=s.polygon.map{VisionPoint($0.x+offset.x,$0.y+offset.y)};s.floorLine=s.floorLine.map{VisionPoint($0.x+offset.x,$0.y+offset.y)};return s
    }
    public func clearTrack(){path=[];lost=nil;acquisition=nil;tiny=nil;motionAcquisition=nil;lastImpact = -10}
    public func reset(){clearTrack();detector.reset();lastFrame = -1;unstableUntil = -1;offset=VisionPoint(0,0)}
    public func process(_ pixels: [UInt32],width w: Int,height h: Int,time: Double) -> VisionResult {
        guard w>0,h>0,w<=8192,h<=8192,pixels.count==w*h,time.isFinite,time>lastFrame else{return VisionResult(message:"Quadro ou timestamp inválido · descartado")}
        if width != w || height != h{reset();width=w;height=h}
        if lastFrame>=0 && time-lastFrame>0.25{path=[]};lastFrame=time
        let (candidates,cameraMoved)=detector.detect(pixels,width:w,height:h,time:time,prior:path.last,landmarks:activeSettings.polygon)
        let motion=detector.alignment
        if motion.reliable && (motion.dx != 0 || motion.dy != 0) {
            let next=VisionPoint(offset.x+Double(motion.dx)/Double(w),offset.y+Double(motion.dy)/Double(h))
            if abs(next.x)<0.035 && abs(next.y)<0.035{offset=next;for i in path.indices{path[i].x+=Double(motion.dx);path[i].y+=Double(motion.dy)};if lost != nil{lost!.x+=Double(motion.dx);lost!.y+=Double(motion.dy)}}
            else{unstableUntil=time+0.25;clearTrack()}
        }
        if cameraMoved || motion.large{unstableUntil=time+0.25;clearTrack()}
        var previous=path.last
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
            let resolved=acquiring.filter{$0.resolved};if !resolved.isEmpty{acquiring=resolved}
            if acquiring.count>1{let moving=acquiring.filter{$0.motionSupport>=0.25};if !moving.isEmpty{acquiring=moving}}
        }
        var ranked=[(VisionBall,Double)]()
        for b in acquiring {
            guard let p=previous else{ranked.append((b,-b.confidence*b.radius));continue}
            let dt=time-p.time,step=prior2.map{hypot(p.x-$0.x,p.y-$0.y)*dt/(p.time-$0.time)} ?? 0
            let timeGate=Double(w)*min(0.23,0.12*max(1,dt*60)),gate=p.nearHand ? Double(w)*0.36:min(Double(w)*0.23,max(timeGate,step*1.8+p.radius*4))
            if hypot(b.x-p.x,b.y-p.y)<=gate,let predicted=prediction{ranked.append((b,hypot(b.x-predicted.x,b.y-predicted.y)+abs(b.radius-p.radius)*4))}
        }
        if previous?.nearHand==true || previous?.occluded==true{let clean=ranked.filter{$0.0.resolved};if !clean.isEmpty{ranked=clean}}
        ranked.sort{$0.1<$1.1};var ball=ranked.first?.0
        if ranked.count>1 && (previous==nil || abs(ranked[1].1-ranked[0].1)<max(4,(ball?.radius ?? 1)*1.5)){ball=nil}
        if previous==nil,let b=ball,(b.radius<2.5 || b.colorUncertain),!b.occluded,!confirmed {
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
        guard let b=ball else {
            let age=previous.map{time-$0.time} ?? 1
            if age>0.10{lost=previous ?? lost;path=[]}
            return VisionResult(predicted:age<0.06 ? prediction:nil,trail:path.suffix(16).map{VisionPoint($0.x,$0.y)},movingCamera:time<unstableUntil,message:candidates.count>1 ? "Alvos verdes ambíguos · aguardando continuidade":"Bola não localizada · sem inventar posição",area:activeSettings,cameraOffset:offset)
        }
        path.append(b);lost=nil;acquisition=nil
        while path.count>40 || (path.first.map{time-$0.time>0.6} ?? false){path.removeFirst()}
        let impact=time>=unstableUntil ? contact():nil,speed=velocity(Array(path.suffix(4)))
        let message: String
        if time<unstableUntil{message="Câmera mudou · confira os quatro cantos"}
        else if b.occluded{message="Bola parcialmente oculta · contato suspenso"}
        else if !b.resolved{message="Bola ≈\(Int(b.radius*2)) px · detalhe incerto · revise IN/OUT"}
        else if b.nearHand{message="Bola perto da mão · impacto suspenso"}
        else if b.blurred{message="Borrão observado · velocidade indisponível"}
        else{message="Bola observada"}
        return VisionResult(observed:b,trail:path.suffix(16).map{VisionPoint($0.x,$0.y)},impact:impact,movingCamera:time<unstableUntil,speed:speed,message:message,area:activeSettings,cameraOffset:offset)
    }
    private func velocity(_ p: [VisionBall]) -> Double? {
        guard p.count>=3,p.allSatisfy({!$0.blurred && !$0.nearHand && $0.resolved}),let a=p.first,let b=p.last else{return nil}
        let dt=b.time-a.time,rs=p.map{$0.radius}.sorted(),r=rs[rs.count/2]
        guard (0.015...0.22).contains(dt),r>=3,rs.last!/rs.first!<=1.35 else{return nil}
        let speed=hypot(b.x-a.x,b.y-a.y)/dt*0.067/(r*2)*3.6
        return speed.isFinite && (0.8...250).contains(speed) ? speed:nil
    }
    private func contact() -> VisionImpact? {
        guard settings.surface != .track,path.count>=5 else{return nil}
        let p=Array(path.suffix(5)),a=p[0],b=p[1],c=p[2],d=p[3],e=p[4]
        if c.time-lastImpact<0.18 || e.time-a.time>0.65 || c.nearHand || (d.nearHand && e.nearHand) || zip(p,p.dropFirst()).contains(where:{$0.1.time-$0.0.time>0.25}){return nil}
        let before=VisionPoint((c.x-a.x)/(c.time-a.time),(c.y-a.y)/(c.time-a.time)),after=VisionPoint((e.x-c.x)/(e.time-c.time),(e.y-c.y)/(e.time-c.time)),travel=max(3,c.radius*0.7)
        let floorTurn=c.y-a.y>travel && c.y-e.y>travel && b.y>=a.y && d.y>=e.y && c.y>=b.y && c.y>=d.y
        let sa=hypot(before.x,before.y),sb=hypot(after.x,after.y)
        let wallTurn=sa>80 && sb>80 && (before.x*after.x+before.y*after.y)/(sa*sb) < -0.5 && abs(before.x)>abs(before.y)*0.8 && abs(after.x)>abs(after.y)*0.8
        if settings.surface == .floor && !floorTurn || settings.surface == .wall && !wallTurn{return nil}
        let fit=settings.surface == .floor ? floorContact(p):nil,t=fit?.time ?? c.time
        let point=VisionPoint((fit?.x ?? c.x)/Double(width),((fit?.y ?? c.y)+(settings.surface == .floor ? c.radius:0))/Double(height))
        let intervals=zip(p,p.dropFirst()).map{$0.1.time-$0.0.time}.sorted(),sparse=intervals.last!>0.075 || intervals.last!>intervals[1]*1.55+0.001
        let missing=sparse ? max(hypot(c.x-b.x,c.y-b.y),hypot(d.x-c.x,d.y-c.y))*0.5:0
        let uncertainty=max(2,c.radius*0.3+(c.blurred ? c.radius*0.6:0)+(fit?.error ?? 0)+missing),area=activeSettings
        let floorDistance=settings.surface == .floor && settings.court == .custom ? VisionGeometry.floorDistance(point,area.floorLine,width,height):nil
        if let dist=floorDistance,dist+uncertainty<0{return nil}
        var call=VisionGeometry.classify(point,radius:c.radius,uncertainty:uncertainty,polygon:area.polygon,width:width,height:height)
        let courtKnown=settings.court != .custom && VisionGeometry.valid(area.polygon)
        let floorKnown=settings.surface != .floor || courtKnown || (floorDistance.map{$0>0.01*Double(height)} ?? false)
        if call != .unmarked && (!floorKnown || sparse || settings.surface == .floor && fit==nil || p.contains(where:{$0.nearHand || !$0.resolved}) || settings.surface == .wall){call = .review}
        let reason: String
        if settings.surface == .wall{reason="Retorno lateral observado; confirme contato com a parede."}
        else if p.contains(where:{!$0.resolved}){reason="Detalhe insuficiente na bola. Confira contato e linha."}
        else if !floorKnown{reason="Contato com o piso não comprovado. Confira a superfície e a marca."}
        else if sparse{reason="Quadros ausentes ou espaçados. Posição estimada; revise."}
        else if fit==nil{reason="Instante do contato incerto; confirme na revisão."}
        else if p.contains(where:{$0.nearHand}){reason="Possível mão na trajetória. Confirme o contato."}
        else if call == .review{reason="A incerteza alcança a linha. Revise o contato."}
        else if call == .unmarked{reason="Quicada candidata; marque a área para IN/OUT."}
        else{reason="Descida e retorno compatíveis com quicada; contato estimado."}
        serial+=1;lastImpact=t;path.removeAll{$0.time<=c.time}
        return VisionImpact(id:serial,time:t,point:point,radius:c.radius,call:call,speed:velocity(Array(p.prefix(3))),uncertainty:uncertainty,reason:reason,surface:settings.surface,cameraOffset:offset,evidenceOffset:offset)
    }
    private struct Fit {var x: Double,y: Double,time: Double,error: Double}
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
