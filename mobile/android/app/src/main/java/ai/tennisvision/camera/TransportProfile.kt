package ai.tennisvision.camera

import kotlin.math.ceil

enum class TransportProfile(val label: String, val latencyMs: Int, val queueMs: Int) {
  FAST("Mínimo atraso · rede local", 40, 80),
  BALANCED("Equilibrado · Wi-Fi variável", 80, 150),
  STABLE("Mais tolerante · rede instável", 160, 250);

  fun queueFrames(fps: Int): Int = maxOf(3, ceil(fps * queueMs / 1000.0).toInt())

  companion object {
    fun forLatency(value: Int) = entries.minBy { kotlin.math.abs(it.latencyMs - value) }
  }
}
