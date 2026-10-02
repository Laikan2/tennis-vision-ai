package ai.tennisvision.camera

/** Preserve brightness when moving either way between short exposure settings. */
object ManualExposure {
  fun iso(currentIso: Int, currentExposureNs: Long, targetExposureNs: Long,
          minimum: Int, maximum: Int): Int {
    require(targetExposureNs > 0 && minimum > 0 && maximum >= minimum)
    val measuredIso = currentIso.takeIf { it > 0 } ?: minimum
    val measuredExposure = currentExposureNs.takeIf { it > 0 } ?: targetExposureNs
    return (measuredIso.toDouble() * measuredExposure / targetExposureNs)
      .coerceIn(minimum.toDouble(), maximum.toDouble()).toInt()
  }
}
