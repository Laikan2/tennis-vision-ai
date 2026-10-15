import Foundation

public enum LineVoice: String, CaseIterable, Sendable { case inside="IN", outside="OUT" }
public enum LineVoicePolicy {
    public static func voice(for call: VisionCall, outEnabled: Bool, inEnabled: Bool) -> LineVoice? {
        switch call {
        case .outside: return outEnabled ? .outside:nil
        case .inside: return inEnabled ? .inside:nil
        default: return nil
        }
    }
    // Calls never form a delayed queue. OUT can interrupt IN; repeated calls wait for silence.
    public static func canStart(_ next: LineVoice, current: LineVoice?) -> Bool {
        current == nil || (next == .outside && current == .inside)
    }
}
