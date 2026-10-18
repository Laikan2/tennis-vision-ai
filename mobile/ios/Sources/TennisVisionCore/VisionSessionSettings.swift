import Foundation

extension VisionSettings {
    /// Match geometry, not preferences, before accepting an asynchronously produced frame.
    public func acceptsFrameArea(_ area:VisionSettings,offset:VisionPoint) -> Bool {
        guard offset.x.isFinite,offset.y.isFinite else{return false}
        let expected=translated(by:offset)
        func same(_ a:[VisionPoint]?,_ b:[VisionPoint]?) -> Bool {
            guard let a,let b else{return a==nil && b==nil}
            return a.count==b.count && zip(a,b).allSatisfy{pair in abs(pair.0.x-pair.1.x)<0.000001 && abs(pair.0.y-pair.1.y)<0.000001}
        }
        return expected.surface==area.surface && expected.court==area.court && expected.floorCourt==area.floorCourt &&
            same(expected.polygon,area.polygon) && same(expected.floorLine,area.floorLine) &&
            same(expected.floorPolygon,area.floorPolygon) && same(expected.wallPolygon,area.wallPolygon)
    }
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

extension VisionResult {
    public func referenced(to settings:VisionSettings) -> VisionResult {
        var value=self
        if settings.acceptsFrameArea(area,offset:cameraOffset){value.area=settings.translated(by:cameraOffset)}
        else{
            value.area=settings;value.cameraOffset=VisionPoint(0,0);value.impact=nil;value.areaNeedsConfirmation=false
            value.message="Atualizando área · aguardando quadro atual"
        }
        return value
    }
}
