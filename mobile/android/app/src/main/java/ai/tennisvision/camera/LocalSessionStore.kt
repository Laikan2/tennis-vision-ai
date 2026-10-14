package ai.tennisvision.camera

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class LocalSession(val id: String = UUID.randomUUID().toString(),
  val started: Long = System.currentTimeMillis(), val events: List<LocalImpact> = emptyList())

/** Separate namespace: never overwrites PC pairing, stream profiles, or recordings. */
class LocalSessionStore(context: Context) {
  private val prefs = context.getSharedPreferences("standalone-settings-v1", Context.MODE_PRIVATE)
  private val directory = File(context.filesDir,"local-sessions").apply { mkdirs() }
  var warning: String? = null
    private set
  fun settings(camera: String): LocalSettings = runCatching {
    val j = JSONObject(prefs.getString("area:$camera", "{}") ?: "{}")
    fun points(key: String): List<CourtPoint> {
      val array=j.optJSONArray(key) ?: return emptyList()
      return (0 until array.length()).map { i -> array.getJSONArray(i).let { CourtPoint(it.getDouble(0),it.getDouble(1)) } }
    }
    LocalSettings(points("polygon").takeIf(LocalGeometry::valid) ?: emptyList(),
      points("floor").takeIf(LocalGeometry::validFloorLine) ?: emptyList(),
      runCatching { ContactSurface.valueOf(j.optString("surface","FLOOR")) }.getOrDefault(ContactSurface.FLOOR),
      prefs.getBoolean("sound",true),prefs.getBoolean("marks",true),
      runCatching { CourtLayout.valueOf(j.optString("court","CUSTOM")) }.getOrDefault(CourtLayout.CUSTOM))
  }.getOrElse { warning="Configuração de área inválida; marque novamente."; LocalSettings() }

  fun saveSettings(camera: String, settings: LocalSettings) {
    fun points(items: List<CourtPoint>) = JSONArray().apply { items.forEach { put(JSONArray().put(it.x).put(it.y)) } }
    val j=JSONObject().put("polygon",points(settings.polygon)).put("floor",points(settings.floorLine)).put("surface",settings.surface.name).put("court",settings.court.name)
    prefs.edit().putString("area:$camera",j.toString()).putBoolean("sound",settings.sound).putBoolean("marks",settings.marks).apply()
  }
  fun current(): LocalSession {
    val id = prefs.getString("current",null) ?: return LocalSession()
    return load(id) ?: LocalSession()
  }
  fun history(): List<LocalSession> = directory.listFiles()?.filter { it.extension == "json" }
    ?.sortedByDescending { it.lastModified() }?.take(100)?.mapNotNull { load(it.nameWithoutExtension) }.orEmpty()
  fun load(id: String): LocalSession? = runCatching {
    require(Regex("[a-fA-F0-9-]{36}").matches(id))
    val j=JSONObject(AtomicFile(File(directory,"$id.json")).openRead().bufferedReader().use { it.readText() })
    val items=j.getJSONArray("events")
    LocalSession(j.getString("id"),j.getLong("started"),(0 until items.length()).map { i ->
      val e=items.getJSONObject(i)
      LocalImpact(e.getLong("id"),e.getDouble("time"),CourtPoint(e.getDouble("x"),e.getDouble("y")),e.getDouble("radius"),
        LineCall.valueOf(e.getString("call")),if (e.isNull("speed")) null else e.getDouble("speed"),e.getDouble("uncertainty"),
        e.getString("reason"),ContactSurface.valueOf(e.getString("surface")),
        e.optString("reviewed").takeIf { it.isNotEmpty() && it != "null" }?.let { LineCall.valueOf(it) },
        CourtPoint(e.optDouble("camera_dx",0.0),e.optDouble("camera_dy",0.0)),
        CourtPoint(e.optDouble("evidence_dx",0.0),e.optDouble("evidence_dy",0.0)))
    })
  }.getOrElse { warning="Não foi possível ler uma sessão. O arquivo original foi preservado."; null }

  fun json(session: LocalSession): String = JSONObject().put("schema",1).put("id",session.id).put("started",session.started)
    .put("method","green_color_motion_v2; background translation; monocular contact estimates; speed from 67 mm diameter")
    .put("events",JSONArray().apply { session.events.forEach { e ->
      put(JSONObject().put("id",e.id).put("time",e.time).put("x",e.point.x).put("y",e.point.y).put("radius",e.radius)
        .put("call",e.call.name).put("reviewed",e.reviewed?.name ?: JSONObject.NULL).put("speed",e.speedKmh ?: JSONObject.NULL)
        .put("uncertainty",e.uncertainty).put("reason",e.reason).put("surface",e.surface.name)
        .put("camera_dx",e.cameraOffset.x).put("camera_dy",e.cameraOffset.y)
        .put("evidence_dx",e.evidenceOffset.x).put("evidence_dy",e.evidenceOffset.y))
    } }).toString(2)

  @Synchronized fun save(session: LocalSession) {
    val atomic=AtomicFile(File(directory,"${session.id}.json"))
    val output=atomic.startWrite()
    try { output.write(json(session).toByteArray(Charsets.UTF_8)); atomic.finishWrite(output) }
    catch (error: Exception) { atomic.failWrite(output); throw error }
    check(prefs.edit().putString("current",session.id).commit()) { "Não salvou a sessão atual." }
  }
}
