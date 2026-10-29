import Foundation

/// Fits a transverse floor rebound after removing apparent-size scaling.
/// Pure optical-depth returns cannot pass; normal area/uncertainty checks still apply.
enum PerspectiveFloorFit {
    static func find(_ history:[VisionBall],width:Int,height:Int)->ObservedContactFit.Fit? {
        guard let end=history.last?.time else{return nil}
        let p=Array(history.reversed().prefix{end-$0.time<=0.42 && $0.resolved && $0.supportsBallColor &&
            !$0.nearHand && !$0.blurred && $0.radius>=8}.reversed())
        guard p.count>=9 else{return nil}
        let radii=p.map{$0.radius}.sorted()
        guard (1.12...1.8).contains(radii.last!/radii.first!) else{return nil}
        if zip(p,p.dropFirst()).contains(where:{!(0.000001...0.050001).contains($0.1.time-$0.0.time) || !(0.85...1.18).contains($0.1.radius/$0.0.radius)}){return nil}
        let lights=p.map{$0.luminance};if lights.max()!/max(1,lights.min()!)>1.4{return nil}
        let reference=radii[radii.count/2],cx=Double(width)*0.5,cy=Double(height)*0.5
        let normalized=p.map{sample->VisionBall in
            var b=sample;b.x=cx+(b.x-cx)*reference/b.radius;b.y=cy+(b.y-cy)*reference/b.radius;b.radius=reference;return b
        }
        guard let fit=ObservedContactFit.find(normalized,surface:.floor),
              let before=p.last(where:{$0.time<=fit.time}),let after=p.first(where:{$0.time>=fit.time}) else{return nil}
        let t=after.time>before.time ? (fit.time-before.time)/(after.time-before.time):0
        let radius=before.radius+(after.radius-before.radius)*t
        let x=cx+(fit.x-cx)*radius/reference,y=cy+(fit.y-cy)*radius/reference
        guard (0...Double(width)).contains(x),(0...Double(height)).contains(y) else{return nil}
        let error=fit.error*radius/reference+hypot(x-cx,y-cy)/radius+1
        guard error<=radius*0.75 else{return nil}
        let support=fit.support.map{sample in p.min{abs($0.time-sample.time)<abs($1.time-sample.time)}!}
        return ObservedContactFit.Fit(x:x,y:y,time:fit.time,error:error,support:support)
    }
}
