import Foundation

/// Detect on this frame at reduced cost while scoring is paused. Never emits contacts.
public final class VisionPreview {
    private let core:LocalVision
    private var storage=[UInt32]()
    public init(){var s=VisionSettings();s.surface = .track;core=LocalVision(settings:s)}
    public func reset(){core.reset()}
    public func process(_ pixels:[UInt32],width:Int,height:Int,time:Double,area:VisionSettings,offset:VisionPoint=VisionPoint(0,0))->VisionResult {
        guard width>0,height>0,width<=8192,height<=8192,pixels.count==width*height else{return VisionResult(message:"Prévia inválida")}
        let scale=min(1,640.0/Double(max(width,height)))
        let w=max(1,Int(Double(width)*scale)),h=max(1,Int(Double(height)*scale))
        if storage.count != w*h{storage=Array(repeating:0,count:w*h)}
        for y in 0..<h{let sy=min(height-1,(2*y+1)*height/(2*h));for x in 0..<w{
            let sx=min(width-1,(2*x+1)*width/(2*w));storage[y*w+x]=pixels[sy*width+sx]
        }}
        let detected=core.process(storage,width:w,height:h,time:time),sx=Double(width)/Double(w),sy=Double(height)/Double(h)
        func point(_ p:VisionPoint)->VisionPoint{VisionPoint((p.x+0.5)*sx-0.5,(p.y+0.5)*sy-0.5)}
        var observed=detected.observed
        if var b=observed {
            let wasResolved=b.resolved,p=point(VisionPoint(b.x,b.y));b.x=p.x;b.y=p.y;b.radius*=min(sx,sy)
            b.diameterX*=sx;b.diameterY*=sy;b.colorUncertain=b.colorUncertain || !wasResolved;observed=b
        }
        return VisionResult(observed:observed,predicted:detected.predicted.map(point),trail:detected.trail.map(point),
            message:observed==nil ? "Prévia procurando bola · inicie para contar impactos":"Bola visível · inicie o treino para contar impactos",area:area,cameraOffset:offset)
    }
}
