package ai.tennisvision.camera

fun LocalSettings.rememberingArea(): LocalSettings = when(surface) {
  ContactSurface.FLOOR -> copy(floorPolygon=polygon,floorCourt=court)
  ContactSurface.WALL -> copy(wallPolygon=polygon)
  ContactSurface.TRACK -> this
}
fun LocalSettings.selectingSurface(target: ContactSurface): LocalSettings {
  var value=rememberingArea()
  if(surface==ContactSurface.TRACK && floorPolygon==null && wallPolygon==null)
    value=value.copy(floorPolygon=polygon,floorCourt=court)
  return when(target) {
    ContactSurface.FLOOR -> value.copy(surface=target,polygon=value.floorPolygon.orEmpty(),court=value.floorCourt ?: CourtLayout.CUSTOM)
    ContactSurface.WALL -> value.copy(surface=target,polygon=value.wallPolygon.orEmpty(),court=CourtLayout.CUSTOM)
    ContactSurface.TRACK -> value.copy(surface=target)
  }
}
fun LocalSettings.translated(offset: CourtPoint): LocalSettings {
  fun move(p: List<CourtPoint>)=p.map { CourtPoint(it.x+offset.x,it.y+offset.y) }
  return copy(polygon=move(polygon),floorLine=move(floorLine),floorPolygon=floorPolygon?.let(::move),wallPolygon=wallPolygon?.let(::move))
}

/** Nothing is persisted until every required home step is valid. */
class HomeAreaSetup(settings: LocalSettings,val surface: ContactSurface) {
  enum class Stage { AREA, FLOOR_BOUNDARY, COMPLETE }
  var stage=Stage.AREA;private set
  var settings=settings.selectingSurface(surface).copy(court=CourtLayout.CUSTOM);private set
  init { require(surface!=ContactSurface.TRACK) }
  fun accept(points: List<CourtPoint>): Boolean {
    when(stage) {
      Stage.AREA -> {
        if(!LocalGeometry.valid(points)) return false
        settings=settings.copy(polygon=points.toList())
        stage=if(surface==ContactSurface.FLOOR) Stage.FLOOR_BOUNDARY else Stage.COMPLETE
      }
      Stage.FLOOR_BOUNDARY -> {
        if(!LocalGeometry.validFloorLine(points)) return false
        settings=settings.copy(floorLine=points.toList());stage=Stage.COMPLETE
      }
      Stage.COMPLETE -> return false
    }
    if(stage==Stage.COMPLETE) settings=settings.rememberingArea()
    return true
  }
  val completedSettings get()=settings.takeIf { stage==Stage.COMPLETE }
}
