import Foundation

/// Apparent lateral speed from a 67 mm ball; never a calibrated 3-D speed.
enum ObservedBallSpeed {
    static func current(_ p: [VisionBall]) -> Double? {
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

    /// Measure only the incoming leg. A brief visible blur at the turn may use
    /// the last clean incoming interval, at most 50 ms old; never bridge a hand,
    /// occlusion, unresolved colour, long loss or a changing apparent diameter.
    static func incoming(_ history:[VisionBall],contactTime:Double)->Double? {
        var p=history.filter{$0.time<=contactTime+1e-9}
        guard let end=p.last,contactTime-end.time<=0.050001 else{return nil}
        if let speed=current(p){return speed}
        var removed=0
        while let last=p.last,last.blurred,last.resolved,!last.nearHand,removed<2 {
            p.removeLast();removed+=1
            guard let observed=p.last,contactTime-observed.time<=0.050001 else{return nil}
            if let speed=current(p){return speed}
        }
        return nil
    }
}
