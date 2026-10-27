import Foundation

/// Arrival time belongs to a session mark, independently of its short pulse animation.
public struct VisionContactClock {
    public private(set) var arrivals=[Int:TimeInterval]()
    public init(){}
    public mutating func record(_ id:Int,at time:TimeInterval) {
        guard time.isFinite,arrivals[id]==nil,arrivals.count<2000 else{return}
        arrivals[id]=time
    }
    public mutating func reset(){arrivals.removeAll()}
    public static func visible(arrival:TimeInterval?,now:TimeInterval,duration:Int)->Bool {
        if duration==0{return true}
        guard let arrival,arrival.isFinite,now.isFinite,now>=arrival else{return false}
        return now-arrival<Double(duration)
    }
}

