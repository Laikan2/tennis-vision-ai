import Foundation

/// Learns unresolved or elongated static fragments, preserving clean still balls.
final class BallClutter {
    private struct Mark{var x:Double,y:Double,radius:Double,since:Double,last:Double,count:Int}
    private var marks=[Mark]()
    func reset(){marks=[]}
    func filter(_ candidates:[VisionBall],time:Double)->[VisionBall] {
        let old=marks.filter{time-$0.last<=0.12};var next=[Mark]()
        let result=candidates.map{b->VisionBall in
            if b.nearHand || b.occluded || b.resolved && !b.blurred{return b}
            let nearest=old.min{hypot($0.x-b.x,$0.y-b.y)<hypot($1.x-b.x,$1.y-b.y)}
            var mark=Mark(x:b.x,y:b.y,radius:b.radius,since:time,last:time,count:1)
            if var m=nearest,hypot(m.x-b.x,m.y-b.y)<=max(3,b.radius*0.65),(0.60...1.65).contains(b.radius/m.radius){
                m.last=time;m.count+=1;mark=m
            }
            if next.count<96{next.append(mark)}
            var value=b;if mark.count>=8 && time-mark.since>=0.30{value.patterned=true};return value
        }
        marks=next;return result
    }
}
final class BallReacquisition {
    private var pending=[VisionBall]()
    func reset(){pending=[]}
    func choose(_ candidates:[VisionBall],still:VisionBall?,backgroundStill:Bool,width:Int)->VisionBall? {
        let current=Array(candidates.filter{b in b.motionSupport>=0.25 && !b.patterned &&
            (still.map{hypot(b.x-$0.x,b.y-$0.y)>max(12,$0.radius*3)} ?? true)}.prefix(24))
        let supported=backgroundStill ? current.filter{b in pending.contains{p in
            (0.001...0.10).contains(b.time-p.time) && (min(b.radius,p.radius)<3 ? 0.45...2.2:0.60...1.65).contains(b.radius/p.radius) &&
            (!b.resolved || !p.resolved || (0.65...1.5).contains(b.luminance/max(1,p.luminance))) &&
            ((2...Double(width)*0.28).contains(hypot(b.x-p.x,b.y-p.y)) || b.nearHand && b.radius>=8)
        }}:[]
        pending=current
        func score(_ b:VisionBall)->Double{b.confidence+(b.distinctFelt ? 0.30:0)+(b.nearHand && b.radius>=8 ? 0.25:0)}
        let resolved=supported.filter{$0.resolved}
        let ordered=(resolved.isEmpty ? supported:resolved).sorted{score($0)>score($1)}
        guard let first=ordered.first,ordered.count==1 || score(first)-score(ordered[1])>=0.15 else{return nil}
        return first
    }
}
