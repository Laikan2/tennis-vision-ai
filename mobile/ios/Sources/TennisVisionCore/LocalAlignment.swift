import Foundation

struct VisionAlignment {
    var dx=0,dy=0,reliable=false,large=false
    private struct Feature {var x: Int,y: Int,quadrant: Int}
    private struct Score {var error: Double,support: Double,quadrants: Int}
    static func estimate(_ now: [UInt32],_ before: [UInt32],width: Int,height: Int,lightShift: Int,landmarks: [VisionPoint]) -> VisionAlignment {
        guard before.count==width*height,now.count==before.count,width>=80,height>=80 else{return VisionAlignment()}
        let margin=14;var features=[Feature]()
        func gradient(_ x: Int,_ y: Int) -> Int {
            let i=y*width+x
            return max(abs(visionLight(before[i-2])-visionLight(before[i+2])),abs(visionLight(before[i-2*width])-visionLight(before[i+2*width])))
        }
        func feature(_ x: Int,_ y: Int) -> Feature {Feature(x:x,y:y,quadrant:(x>=width/2 ? 1:0)+(y>=height/2 ? 2:0))}
        for y in stride(from:margin,to:height-margin,by:max(8,height/12)){for x in stride(from:margin,to:width-margin,by:max(8,width/20)){
            if gradient(x,y)>=20{features.append(feature(x,y))}
        }}
        if VisionGeometry.valid(landmarks) {for edge in 0..<4 {
            let a=landmarks[edge],b=landmarks[(edge+1)%4]
            for step in 0...8 {
                let f=Double(step)/8,px=max(margin,min(width-margin-1,Int((a.x+(b.x-a.x)*f)*Double(width))))
                let py=max(margin,min(height-margin-1,Int((a.y+(b.y-a.y)*f)*Double(height))))
                var best=0,bx=px,by=py
                for y in stride(from:py-4,through:py+4,by:2){for x in stride(from:px-4,through:px+4,by:2){
                    let g=gradient(x,y);if g>best{best=g;bx=x;by=y}
                }}
                if best>=20 && !features.contains(where:{abs($0.x-bx)<4 && abs($0.y-by)<4}){features.append(feature(bx,by))}
            }
        }}
        guard features.count>=16,Set(features.map{$0.quadrant}).count>=3 else{return VisionAlignment()}
        func score(_ dx: Int,_ dy: Int) -> Score {
            var total=0.0,support=0,counts=Array(repeating:0,count:4),supported=Array(repeating:0,count:4)
            for f in features {
                let old=f.y*width+f.x,current=(f.y+dy)*width+f.x+dx
                var difference=abs(visionLight(now[current])-visionLight(before[old])-lightShift)
                difference+=abs(visionLight(now[current-1])-visionLight(before[old-1])-lightShift)
                difference+=abs(visionLight(now[current+width])-visionLight(before[old+width])-lightShift)
                let error=Double(difference)/3;total+=min(error,30);counts[f.quadrant]+=1
                if error<=8{support+=1;supported[f.quadrant]+=1}
            }
            let quadrants=(0..<4).filter{counts[$0]>=3 && Double(supported[$0])>=Double(counts[$0])*0.55}.count
            return Score(error:total/Double(features.count),support:Double(support)/Double(features.count),quadrants:quadrants)
        }
        let zero=score(0,0)
        if zero.error<3 && zero.support>=0.70 && zero.quadrants>=3{return VisionAlignment(reliable:true)}
        var best=zero,dx=0,dy=0
        for y in -8...8{for x in -8...8{let s=score(x,y);if s.error<best.error{best=s;dx=x;dy=y}}}
        let coarseX=dx,coarseY=dy
        for y in coarseY-1...coarseY+1{for x in coarseX-1...coarseX+1{let s=score(x,y);if s.error<best.error{best=s;dx=x;dy=y}}}
        if dx==0 && dy==0 || best.error>=zero.error*0.65{return VisionAlignment(reliable:zero.support>=0.55 && zero.quadrants>=3)}
        guard best.support>=0.72,best.quadrants>=3,best.error<10 else{return VisionAlignment(large:features.count>=32 && zero.support<0.30 && best.support<0.55)}
        if abs(dx)>=8 || abs(dy)>=8{return VisionAlignment(large:true)}
        return VisionAlignment(dx:dx,dy:dy,reliable:true)
    }
}
