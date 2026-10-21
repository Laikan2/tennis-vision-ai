import Foundation

public enum VisionFloorBoundaryDraft {
    public static var wholeImage:[VisionPoint]{[VisionPoint(0,0),VisionPoint(1,0)]}
    public static func hint(_ points:[VisionPoint])->String {
        if points.count<2{return "\(points.count)/2 pontos · marque onde a parede encontra o chão, ou use Toda a imagem é piso se não há parede visível."}
        if !VisionGeometry.validLine(points){return "Os pontos estão próximos demais na horizontal. Toque mais à esquerda ou à direita para ajustar um extremo; depois salve."}
        return "O piso fica ABAIXO da linha; a região sombreada não conta quicadas. Toque em Salvar área ou, se só aparece chão, em Toda a imagem é piso."
    }
    public static func placing(_ point:VisionPoint,in points:[VisionPoint],width:Double,height:Double)->[VisionPoint] {
        guard point.x.isFinite,point.y.isFinite,(0...1).contains(point.x),(0...1).contains(point.y),width>0,height>0 else{return points}
        if points.count<2{return points+[point]}
        var updated=Array(points.prefix(2))
        let nearest=updated.indices.min{a,b in
            hypot((updated[a].x-point.x)*width,(updated[a].y-point.y)*height)<hypot((updated[b].x-point.x)*width,(updated[b].y-point.y)*height)
        }!
        updated[nearest]=point;return updated
    }
}

public extension VisionSettings {
    func rememberingArea() -> VisionSettings {
        var value=self
        if surface == .floor {value.floorPolygon=polygon;value.floorCourt=court}
        else if surface == .wall {value.wallPolygon=polygon}
        return value
    }
    func selectingSurface(_ target: VisionSurface) -> VisionSettings {
        var value=rememberingArea()
        // A legacy tracking-only area has no surface record. Retain it as floor.
        if surface == .track && floorPolygon == nil && wallPolygon == nil {
            value.floorPolygon=polygon;value.floorCourt=court
        }
        value.surface=target
        if target == .floor {value.polygon=value.floorPolygon ?? [];value.court=value.floorCourt ?? .custom}
        else if target == .wall {value.polygon=value.wallPolygon ?? [];value.court = .custom}
        return value
    }
    func translated(by offset: VisionPoint) -> VisionSettings {
        func move(_ p:[VisionPoint])->[VisionPoint]{p.map{VisionPoint($0.x+offset.x,$0.y+offset.y)}}
        var value=self;value.polygon=move(polygon);value.floorLine=move(floorLine)
        value.floorPolygon=floorPolygon.map(move);value.wallPolygon=wallPolygon.map(move)
        return value
    }
}

/// A home setup is a draft until both required floor steps have succeeded.
public struct VisionHomeAreaSetup {
    public enum Stage {case area,floorBoundary,complete}
    public let surface: VisionSurface
    public private(set) var stage=Stage.area
    public private(set) var settings: VisionSettings
    public init(settings:VisionSettings,surface:VisionSurface){
        precondition(surface != .track)
        self.surface=surface;self.settings=settings.selectingSurface(surface);self.settings.court = .custom
    }
    @discardableResult public mutating func accept(_ points:[VisionPoint])->Bool {
        switch stage {
        case .area:
            guard VisionGeometry.valid(points) else{return false}
            settings.polygon=points
            stage=surface == .floor ? .floorBoundary:.complete
        case .floorBoundary:
            guard VisionGeometry.validLine(points) else{return false}
            settings.floorLine=points;stage = .complete
        case .complete:return false
        }
        if stage == .complete {settings=settings.rememberingArea()}
        return true
    }
    public var completedSettings:VisionSettings?{stage == .complete ? settings:nil}
}
