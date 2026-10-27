import Foundation

/// Fits a velocity change to observed samples only; missing positions are never fabricated.
enum ObservedContactFit {
    struct Fit {var x:Double,y:Double,time:Double,error:Double,support:[VisionBall]}
    static func find(_ history:[VisionBall],surface:VisionSurface)->Fit? {
        guard let end=history.last?.time else{return nil}
        // Nine real samples at 20 analyzed FPS need 400 ms of history.
        // The number of fit points and hypotheses remains bounded below.
        let clean=Array(history.reversed().prefix{end-$0.time<=0.42 && $0.resolved && $0.supportsBallColor && !$0.nearHand && !$0.blurred}.reversed())
        guard clean.count>=9,let first=clean.first,let last=clean.last,last.time-first.time>=0.07 else{return nil}
        guard !zip(clean,clean.dropFirst()).contains(where:{$0.1.time-$0.0.time<=0 || $0.1.time-$0.0.time>0.075}) else{return nil}
        let radii=clean.map{$0.radius}.sorted(),radius=radii[radii.count/2]
        guard radii.last!/radii.first!<=1.35 else{return nil}
        let points=clean.count<=40 ? clean:(0...39).map{clean[$0*(clean.count-1)/39]}
        let peak=points.indices.max{points[$0].y<points[$1].y}!,travel=max(5,radius*0.9)
        if surface == .floor && (peak<3 || peak>points.count-4 || points[peak].y-points[0].y<travel || points[peak].y-points.last!.y<travel){return nil}
        let origin=points[0].time,span=points.last!.time-origin
        guard let smooth=fit(points,origin:origin,scale:span,hasTurn:false) else{return nil}
        var best:Fit?,cost=Double.infinity
        for i in 0...24 {
            let time=points[3].time+(points[points.count-4].time-points[3].time)*Double(i)/24
            if surface == .floor && abs(time-points[peak].time)>0.045{continue}
            let before=points.filter{$0.time<time},after=points.filter{$0.time>time}
            guard before.count>=3,after.count>=3,let model=fit(points,origin:time,scale:span,hasTurn:true) else{continue}
            if model.rms>max(1.2,radius*0.30) || model.error>max(3.5,radius*0.55) || model.rms*model.rms>=smooth.rms*smooth.rms*0.40{continue}
            let vx=model.x[1]/span,vy=model.y[1]/span,ux=(model.x[1]+model.x[3])/span,uy=(model.y[1]+model.y[3])/span
            let incoming=hypot(vx,vy),outgoing=hypot(ux,uy)
            if incoming<20 || outgoing<20{continue}
            if surface == .floor {
                if vy<20 || uy > -20 || model.y[2]/(span*span) < -max(50,abs(vy-uy)/span*0.1){continue}
            } else if (vx*ux+vy*uy)/(incoming*outgoing)>=0.35{continue}
            if hypot(model.x[0]-before[0].x,model.y[0]-before[0].y)<travel ||
                hypot(model.x[0]-after.last!.x,model.y[0]-after.last!.y)<travel{continue}
            if model.rms<cost {
                cost=model.rms
                let c=points.min{abs($0.time-time)<abs($1.time-time)}!
                best=Fit(x:model.x[0],y:model.y[0],time:time,error:model.error,
                         support:[before[0],before[before.count/2],c,after[after.count/2],after.last!])
            }
        }
        return best
    }
    private struct Model {var x:[Double],y:[Double],rms:Double,error:Double}
    private static func fit(_ points:[VisionBall],origin:Double,scale:Double,hasTurn:Bool)->Model? {
        let count=hasTurn ? 4:3
        let rows=points.map { p->[Double] in
            let t=(p.time-origin)/scale
            return hasTurn ? [1,t,t*t,max(0,t)]:[1,t,t*t]
        }
        func solve(_ value:(VisionBall)->Double)->[Double]? {
            var matrix=Array(repeating:Array(repeating:0.0,count:count+1),count:count)
            for i in points.indices {for j in 0..<count {
                for k in 0..<count {matrix[j][k]+=rows[i][j]*rows[i][k]}
                matrix[j][count]+=rows[i][j]*value(points[i])
            }}
            for j in 0..<count {
                let pivot=(j..<count).max{abs(matrix[$0][j])<abs(matrix[$1][j])}!
                guard abs(matrix[pivot][j])>=1e-10 else{return nil}
                matrix.swapAt(j,pivot)
                let divisor=matrix[j][j];for k in j...count{matrix[j][k]/=divisor}
                for i in 0..<count where i != j {
                    let multiplier=matrix[i][j]
                    for k in j...count{matrix[i][k]-=multiplier*matrix[j][k]}
                }
            }
            let result=(0..<count).map{matrix[$0][count]}
            return result.allSatisfy{$0.isFinite} ? result:nil
        }
        guard let x=solve({$0.x}),let y=solve({$0.y}) else{return nil}
        var squared=0.0,error=0.0
        for i in points.indices {
            var px=0.0,py=0.0
            for j in 0..<count{px+=rows[i][j]*x[j];py+=rows[i][j]*y[j]}
            let e=hypot(px-points[i].x,py-points[i].y);squared+=e*e;error=max(error,e)
        }
        return Model(x:x,y:y,rms:sqrt(squared/Double(points.count)),error:error)
    }
}

