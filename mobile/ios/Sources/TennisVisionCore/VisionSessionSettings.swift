import Foundation

extension VisionSettings {
    public func usesSameVisibleArea(as other: VisionSettings) -> Bool {
        polygon==other.polygon && floorLine==other.floorLine && surface==other.surface && court==other.court
    }
    /// Persist user preferences, never geometry from a camera's previous position.
    public var withoutSessionArea: VisionSettings {
        var value=self
        value.polygon=[];value.floorLine=[];value.floorPolygon=nil;value.wallPolygon=nil
        value.court = .custom;value.floorCourt=nil
        return value
    }
}
