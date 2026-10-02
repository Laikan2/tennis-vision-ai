package ai.tennisvision.camera

import java.net.URI
import java.net.URLDecoder

data class PairingConfig(
  val host: String,
  val port: Int = 8890,
  val path: String = "galaxy",
  val fps: Int = 60,
  val width: Int = 1920,
  val height: Int = 1080,
  val bitrate: Int = 12_000_000,
  val latencyMs: Int = 40,
) {
  init {
    val labels = host.split('.')
    require(host.length in 1..253 && labels.all { hostLabel.matches(it) }) {
      "IP ou nome do computador inválido."
    }
    if (host.all { it.isDigit() || it == '.' }) {
      require(labels.size == 4 && labels.all { it.toIntOrNull()?.let { part -> part in 0..255 } == true }) {
        "IP do computador inválido."
      }
    }
    require(port in 1..65535) { "Porta inválida. Use um valor de 1 a 65535." }
    require(pathPattern.matches(path)) { "Use letras, números, hífen ou sublinhado no caminho." }
    require(fps in 1..240 && width in 320..7680 && height in 240..4320 &&
      bitrate in 500_000..100_000_000) { "Perfil de vídeo inválido." }
    require(latencyMs in 20..1000) { "Buffer SRT inválido." }
  }

  fun toSrtUrl(): String =
    "srt://$host:$port?streamid=publish:$path&latency=$latencyMs&pkt_size=1316"

  companion object {
    private val hostLabel = Regex("^[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?$")
    private val pathPattern = Regex("^[A-Za-z0-9_-]{1,64}$")

    fun parse(value: String): PairingConfig {
      require(value.length <= 4096) { "QR Code muito longo." }
      val uri = URI(value.trim())
      require(uri.scheme == "tennisvision" && uri.host == "pair" &&
        uri.userInfo == null && uri.port == -1 && uri.path.isNullOrEmpty() &&
        uri.fragment == null) {
        "Este QR Code não pertence ao Tennis Vision."
      }
      val query = linkedMapOf<String, String>()
      uri.rawQuery.orEmpty().split('&').filter(String::isNotEmpty).forEach { part ->
        val pieces = part.split('=', limit = 2)
        val key = URLDecoder.decode(pieces[0], "UTF-8")
        val field = URLDecoder.decode(pieces.getOrElse(1) { "" }, "UTF-8")
        require(!query.containsKey(key)) { "O QR Code contém campos repetidos." }
        query[key] = field
      }
      require(query.getOrDefault("transport", "srt") == "srt") { "O app transmite por SRT." }
      fun number(key: String, default: Int): Int = query[key]?.let {
        it.toIntOrNull() ?: throw IllegalArgumentException("Valor inválido para $key.")
      } ?: default
      return PairingConfig(
        host = query["host"]?.trim().orEmpty(),
        path = query["path"]?.trim().orEmpty(),
        port = number("port", 8890), fps = number("fps", 60),
        width = number("width", 1920), height = number("height", 1080),
        bitrate = number("bitrate", 12_000_000),
        latencyMs = number("latency", 40),
      )
    }
  }
}
