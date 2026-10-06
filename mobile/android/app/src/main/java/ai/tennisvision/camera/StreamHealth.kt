package ai.tennisvision.camera

/** Report a stalled source separately from a low requested frame rate. */
object StreamHealth {
  fun stalled(lastFrameMs: Long, nowMs: Long, active: Boolean): Boolean =
    active && lastFrameMs > 0 && nowMs - lastFrameMs > 3_000

  // Recording replaces the preview session. Its old callback must not start
  // a 3-second timeout while Camera2 is changing sensor modes. Encoded video
  // also proves that the sensor is running if a vendor batches its callbacks.
  fun captureStalled(lastCaptureMs: Long, startedMs: Long, lastEncodedMs: Long,
                     nowMs: Long, active: Boolean, recording: Boolean): Boolean {
    if (!active) return false
    if (!recording) return stalled(lastCaptureMs, nowMs, true)
    if (startedMs <= 0 || nowMs - startedMs <= 8_000) return false
    val progress = maxOf(lastCaptureMs, lastEncodedMs).takeIf { it >= startedMs } ?: startedMs
    return nowMs - progress > 3_000
  }

  // Camera2 can continue delivering callbacks while MediaCodec stops producing
  // video. Give session configuration a startup window, then watch its output.
  fun encoderStalled(startedMs: Long, lastFrameMs: Long, nowMs: Long, active: Boolean): Boolean =
    active && startedMs > 0 && nowMs - startedMs > 8_000 &&
      (lastFrameMs <= 0 || nowMs - lastFrameMs > 3_000)

  fun queueMs(bytes: Long, bitrate: Long): Long =
    if (bytes > 0 && bitrate > 0) (bytes.toDouble() * 8_000 / bitrate).toLong() else 0
}
