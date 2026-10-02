package ai.tennisvision.camera

/** Report a stalled source separately from a low requested frame rate. */
object StreamHealth {
  fun stalled(lastFrameMs: Long, nowMs: Long, active: Boolean): Boolean =
    active && lastFrameMs > 0 && nowMs - lastFrameMs > 3_000

  fun queueMs(bytes: Long, bitrate: Long): Long =
    if (bytes > 0 && bitrate > 0) (bytes.toDouble() * 8_000 / bitrate).toLong() else 0
}
