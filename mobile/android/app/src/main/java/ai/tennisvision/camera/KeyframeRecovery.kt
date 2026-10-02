package ai.tennisvision.camera

/** Dependent AVC frames must wait for a new independent frame after a queue reset. */
class KeyframeRecovery {
  private var waiting = false
  var discarded = 0L
    private set

  fun request() { waiting = true }
  fun reset() { waiting = false; discarded = 0 }
  fun accepts(keyframe: Boolean): Boolean {
    if (!waiting) return true
    if (keyframe) { waiting = false; return true }
    discarded++
    return false
  }
}
