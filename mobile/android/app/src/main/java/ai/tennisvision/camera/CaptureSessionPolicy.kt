package ai.tennisvision.camera

/** A smooth regular preview does not require starting the constrained recording pipeline. */
object CaptureSessionPolicy {
  fun usesHighSpeed(profile: StreamProfile, recording: Boolean) = profile.highSpeed && recording

  fun range(profile: StreamProfile, recording: Boolean, available: List<Pair<Int, Int>>, maxPreviewFps: Int = 60): Pair<Int, Int> {
    if (usesHighSpeed(profile, recording)) {
      val fixed = profile.fps to profile.fps
      require(fixed in available) { "A câmera não confirmou ${profile.fps} fps fixos nesta resolução." }
      return fixed
    }
    if (!profile.highSpeed) return profile.fpsLower to profile.fps
    // Preview before Transmitir stays at an ordinary supported rate. The recording
    // session still requests exactly 120/240 fps and feeds the hardware encoder.
    return available.filter { it.first > 0 && it.second in 15..minOf(60, maxPreviewFps) }
      .maxWithOrNull(compareBy<Pair<Int, Int>> { it.second }.thenBy { it.first })
      ?: error("Não há uma taxa de prévia compatível nesta câmera.")
  }
}
