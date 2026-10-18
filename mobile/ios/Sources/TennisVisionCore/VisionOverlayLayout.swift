import Foundation

/// A bounded callout search. Call newest first; contact markers are never moved.
public enum VisionOverlayLayout {
    public static func place(viewport:CGRect,size:CGSize,preferred:CGPoint,gap:CGFloat,occupied:[CGRect]) -> CGRect? {
        guard size.width>0,size.height>0,size.width<=viewport.width,size.height<=viewport.height else{return nil}
        let rows=min(12,Int(ceil(viewport.height/(size.height+gap))))
        for row in 0...rows {
            let shifts=row==0 ? [0]:[-row,row]
            for shift in shifts {for left in [preferred.x,preferred.x-size.width-2*gap] {
                let x=max(viewport.minX,min(left,viewport.maxX-size.width))
                let y=max(viewport.minY,min(preferred.y+CGFloat(shift)*(size.height+gap),viewport.maxY-size.height))
                let box=CGRect(x:x,y:y,width:size.width,height:size.height)
                if !occupied.contains(where:{$0.intersects(box.insetBy(dx:-gap,dy:-gap))}){return box}
            }}
        }
        return nil
    }
}
