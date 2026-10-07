package ai.tennisvision.camera

import kotlin.math.max

data class StabilitySample(val elapsedMs: Long, val captureFps: Double, val encodedFps: Double,
                           val sentFps: Double, val dropped: Long, val queueMs: Long,
                           val batteryCelsius: Float, val thermal: Int)
data class StabilityResult(val requestedFps: Int, val captureFps: Double, val encodedFps: Double,
                           val sentFps: Double, val dropped: Long, val peakQueueMs: Long,
                           val peakTemperature: Float, val stable: Boolean, val reason: String)

/** Warm-up is excluded; the result describes this device/profile, not a promise. */
class StabilityProbe(private val fps: Int, private val network: Boolean, val startedMs: Long,
                     private val warmupMs: Long = 5_000, private val measurementMs: Long = 20_000) {
  private val samples = mutableListOf<StabilitySample>()
  fun add(sample: StabilitySample) {
    if (sample.elapsedMs >= warmupMs) samples.add(sample)
  }
  fun remaining(nowMs: Long) = max(0, warmupMs + measurementMs - (nowMs-startedMs))
  fun result(): StabilityResult {
    fun median(values: List<Double>): Double {
      val v=values.sorted(); if(v.isEmpty())return 0.0
      return if(v.size%2==1)v[v.size/2] else (v[v.size/2-1]+v[v.size/2])/2
    }
    val capture=median(samples.map { it.captureFps })
    val encoded=median(samples.map { it.encodedFps })
    val sent=median(samples.map { it.sentFps })
    val dropped=(samples.lastOrNull()?.dropped ?: 0)-(samples.firstOrNull()?.dropped ?: 0)
    val queue=samples.maxOfOrNull { it.queueMs } ?: 0
    val thermal=samples.maxOfOrNull { it.thermal } ?: 0
    val reason=when {
      samples.size<10 -> "Teste incompleto. Repita sem interromper a captura."
      capture<fps*.9 -> "A captura não sustentou os FPS escolhidos. Teste outro perfil ou mais luz."
      encoded<fps*.9 -> "O H.264 não sustentou a captura. Teste menor resolução."
      samples.count { it.captureFps<fps*.8 || it.encodedFps<fps*.8 }>samples.size/5 -> "O vídeo oscila durante o teste. Confira aquecimento e iluminação."
      thermal>=2 -> "Aparelho aquecendo. Repita após esfriar antes de sessões longas."
      network && (sent<fps*.85 || dropped>fps || queue>250) -> "Captura estável, mas o envio perdeu fluidez. Confira o Wi-Fi e o receptor."
      else -> "Perfil estável neste teste. Faça também um teste longo com bolas rápidas."
    }
    return StabilityResult(fps,capture,encoded,sent,dropped,queue,samples.maxOfOrNull { it.batteryCelsius } ?: 0f,
      reason.startsWith("Perfil estável"),reason)
  }
}

object ImageAdvice {
  fun describe(exposureNs: Long, iso: Int, fps: Int, focusLocked: Boolean): String {
    if(exposureNs<=0)return "Aguarde a leitura real da câmera. Use boa iluminação e toque no plano onde a bola vai quicar."
    val denominator=1_000_000_000.0/exposureNs
    return listOfNotNull(
      if(denominator<500) "Exposição longa (1/${denominator.toInt()} s): bolas rápidas podem borrar. Ative obturador rápido e aumente a luz." else "Exposição curta: melhor para movimento rápido.",
      if(iso>=1600) "ISO $iso: pouca luz pode esconder a bola no ruído." else null,
      if(exposureNs>1_000_000_000L/fps) "A exposição excede o tempo de um quadro a $fps fps." else null,
      if(focusLocked) "Foco travado. Confira se está no piso/parede, não na sua mão." else "Toque no piso/parede para focar; trave o foco quando o plano estiver nítido."
    ).joinToString("\n")
  }
}
