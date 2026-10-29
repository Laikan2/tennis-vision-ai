import Foundation

/// A brief obstruction suspends scoring, not the user's training session.
/// Scoring resumes only when distributed samples match the saved view again.
final class VisionSceneReference {
    private var reference=[Int](),stableSince=0.0
    private(set) var blocked=false
    private(set) var needsConfirmation=false
    // An invalid saved area blocks automatic line calls, not every future
    // contact. Only the actual motion/settling interval blocks contact evidence.
    private(set) var contactsBlocked=false
    func reset(){reference=[];blocked=false;needsConfirmation=false;contactsBlocked=false;stableSince=0}
    func update(_ pixels:[UInt32],width:Int,height:Int,time:Double,changed:Bool,compensated:Bool=false){
        var sample=[Int]();sample.reserveCapacity(240*3)
        for y in 0..<12{for x in 0..<20{
            let px=min(width-1,(2*x+1)*width/40),py=min(height-1,(2*y+1)*height/24),c=pixels[py*width+px]
            sample += [Int((c>>16)&255),Int((c>>8)&255),Int(c&255)]
        }}
        if reference.isEmpty{reference=sample;return}
        if changed{blocked=true;contactsBlocked=true;stableSince=time}
        guard blocked else{contactsBlocked=false;if compensated{reference=sample};return}
        contactsBlocked=time-stableSince<0.65
        guard time-stableSince>=0.25 else{return}
        var shifts=[Int]()
        for i in stride(from:0,to:sample.count,by:3){shifts.append((sample[i]+2*sample[i+1]+sample[i+2]-reference[i]-2*reference[i+1]-reference[i+2])/4)}
        shifts.sort();let shift=shifts[shifts.count/2]
        var counts=Array(repeating:0,count:4)
        for y in 0..<12{for x in 0..<20{
            let i=(y*20+x)*3,q=(x>=10 ? 1:0)+(y>=6 ? 2:0)
            if (0..<3).allSatisfy({abs(sample[i+$0]-reference[i+$0]-shift)<=24}){counts[q]+=1}
        }}
        if counts.reduce(0,+)>=168 && counts.filter({$0>=36}).count>=3{
            blocked=false;needsConfirmation=false;contactsBlocked=false;reference=sample
        }else{needsConfirmation=needsConfirmation || time-stableSince>=0.65}
    }
}
