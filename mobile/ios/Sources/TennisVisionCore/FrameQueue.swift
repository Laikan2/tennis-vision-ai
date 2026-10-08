import Foundation

public struct QueueOffer {
    public let accepted: Bool
    public let needsKeyframe: Bool
}

/// Keep complete H.264 access units. After a discard, never send dependent frames until a fresh IDR.
public final class FrameQueue {
    private let lock = NSLock()
    private var frames = [EncodedFrame]()
    private var waiting = true
    private var dropped = 0
    private var byteCount = 0
    public let maxFrames: Int
    public let maxAge: Double
    public let maxBytes: Int
    public init(maxFrames: Int = 8, maxAge: Double = 0.08, maxBytes: Int = 2_000_000) {
        self.maxFrames = max(1,maxFrames); self.maxAge = max(0.01,maxAge); self.maxBytes = max(1024,maxBytes)
    }
    public var droppedFrames: Int { lock.lock(); defer{lock.unlock()}; return dropped }
    public var count: Int { lock.lock(); defer{lock.unlock()}; return frames.count }
    public var needsKeyframe: Bool { lock.lock(); defer{lock.unlock()}; return waiting }
    public func reset() {
        lock.lock(); defer{lock.unlock()}; frames.removeAll(); byteCount=0; waiting=true
    }
    public func offer(_ frame: EncodedFrame) -> QueueOffer {
        lock.lock(); defer{lock.unlock()}
        guard frame.pts.isFinite, frame.createdAt.isFinite, !frame.nals.isEmpty, frame.bytes <= maxBytes else {
            dropped += frames.count+1; frames.removeAll(); byteCount=0; waiting=true
            return QueueOffer(accepted:false,needsKeyframe:true)
        }
        if frames.count >= maxFrames || byteCount+frame.bytes > maxBytes ||
            frames.first.map({frame.createdAt-$0.createdAt > maxAge}) == true {
            dropped += frames.count; frames.removeAll(); byteCount=0; waiting=true
        }
        if waiting && !frame.keyframe { dropped += 1; return QueueOffer(accepted:false,needsKeyframe:true) }
        waiting=false; frames.append(frame); byteCount+=frame.bytes
        return QueueOffer(accepted:true,needsKeyframe:false)
    }
    public func take(now: Double) -> EncodedFrame? {
        lock.lock(); defer{lock.unlock()}
        guard let first=frames.first else{return nil}
        guard now >= first.createdAt, now-first.createdAt <= maxAge else {
            dropped+=frames.count; frames.removeAll(); byteCount=0; waiting=true; return nil
        }
        frames.removeFirst(); byteCount-=first.bytes; return first
    }
}

public struct FrameRateMeter {
    private var observations = [Double]()
    public init() {}
    public mutating func observe(_ time: Double) {
        guard time.isFinite, observations.last.map({time > $0}) ?? true else{return}
        observations.append(time)
        observations.removeAll(where:{$0 < time-1.5})
    }
    public mutating func value(now: Double) -> Double {
        guard now.isFinite, let last=observations.last, now >= last, now-last < 1 else{return 0}
        observations.removeAll(where:{$0 < now-1.5})
        guard observations.count >= 2, let first=observations.first, last > first else{return 0}
        return Double(observations.count-1)/(last-first)
    }
}
