import Foundation

public enum VisionContactEvidence:String,Sendable {case confirmedFloor="CONFIRMED_FLOOR",confirmedWall="CONFIRMED_WALL",candidate="CANDIDATE",rejected="REJECTED"}
public extension VisionImpact {
    var evidence:VisionContactEvidence {
        if decision == .rejected{return .rejected}
        if decision == .inside || decision == .outside{return surface == .wall ? .confirmedWall:.confirmedFloor}
        return .candidate
    }
}
public struct VisionTrackingDiagnostics:Sendable {
    public var trackId:Int?,state="BUSCANDO",candidates=0,usable=0,textureRejected=0
    public var contactReason="Aguardando trajetória",samples=[VisionBall]()
    public var summary:String{"ID \(trackId.map(String.init) ?? "—") · \(state) · \(usable)/\(candidates) alvos · \(textureRejected) texturas descartadas\n\(contactReason)"}
}
