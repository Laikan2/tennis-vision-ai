package ai.tennisvision.camera

/** Measures delivered observations using timestamps; never substitutes the requested FPS. */
class FrameRateMeter {
  private var first = -1L
  private var last = -1L
  private var count = 0

  @Synchronized fun reset() { first = -1L; last = -1L; count = 0 }

  @Synchronized fun frame(timestampNs: Long): Double? {
    if (timestampNs < 0 || timestampNs <= last) return null
    if (first < 0) { first = timestampNs; last = timestampNs; return null }
    last = timestampNs
    count++
    val elapsed = timestampNs - first
    if (elapsed < 1_000_000_000L) return null
    val fps = count * 1_000_000_000.0 / elapsed
    first = timestampNs
    count = 0
    return fps
  }
}
