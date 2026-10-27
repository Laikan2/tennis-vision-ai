import Foundation

/// Radius-based depth returns remain review candidates without a measured surface plane.
enum DepthContactFit {
    static func find(_ history:[VisionBall],width:Int,height:Int)->ObservedContactFit.Fit? {
        guard let end=history.last?.time else{return nil}
        var p=Array(history.reversed().prefix{end-$0.time<=0.32 && $0.resolved && $0.supportsBallColor && !$0.nearHand && !$0.blurred && $0.radius>=8}.reversed())
        guard p.count>=9,let peak=p.indices.min(by:{p[$0].radius<p[$1].radius}),peak>=3,peak<=p.count-4 else{return nil}
        let minimum=p[peak].radius
        var start=peak,stop=peak
        while start>0 && p[start-1].radius<=minimum*1.8{start-=1}
        while stop<p.count-1 && p[stop+1].radius<=minimum*1.8{stop+=1}
        p=Array(p[start...stop])
        guard p.count>=9,p.last?.time==end else{return nil}
        if zip(p,p.dropFirst()).contains(where:{
            let dt=$0.1.time-$0.0.time,ratio=$0.1.radius/$0.0.radius
            return !(0.000001...0.050001).contains(dt) || !(0.78...1.28).contains(ratio)
        }){return nil}
        if p[0].radius<minimum*1.16 || p.last!.radius<minimum*1.16{return nil}
        let lights=p.map{$0.luminance};if lights.max()!/max(1,lights.min()!)>1.4{return nil}
        let radii=p.map{$0.radius}.sorted(),reference=radii[radii.count/2]
        let projected=p.map{sample->VisionBall in
            var b=sample;b.x=(sample.x-Double(width)*0.5)*reference/sample.radius
            b.y=reference*reference/sample.radius;b.radius=6;return b
        }
        guard let fit=ObservedContactFit.find(projected,surface:.floor),
              let before=p.last(where:{$0.time<=fit.time}),let after=p.first(where:{$0.time>=fit.time}) else{return nil}
        let fraction=after.time>before.time ? (fit.time-before.time)/(after.time-before.time):0
        let x=before.x+(after.x-before.x)*fraction,y=before.y+(after.y-before.y)*fraction
        guard (0...Double(width)).contains(x),(0...Double(height)).contains(y) else{return nil}
        let support=fit.support.map{sample in p.min{abs($0.time-sample.time)<abs($1.time-sample.time)}!}
        return ObservedContactFit.Fit(x:x,y:y,time:fit.time,error:max(minimum,fit.error),support:support)
    }
}

