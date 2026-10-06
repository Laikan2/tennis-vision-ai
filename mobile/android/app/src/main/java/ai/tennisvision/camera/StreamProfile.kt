package ai.tennisvision.camera

data class StreamProfile(
  val label: String,
  val width: Int,
  val height: Int,
  val fps: Int,
  val bitrate: Int,
  val cameraId: String = "0",
  val highSpeed: Boolean = false,
  val fpsLower: Int = fps,
  val encoderName: String? = null,
) {
  companion object {
    val stableProfiles = listOf(
      StreamProfile("1080p · 60 fps · 12 Mbps", 1920, 1080, 60, 12_000_000),
      StreamProfile("720p · 60 fps · 8 Mbps", 1280, 720, 60, 8_000_000),
      StreamProfile("1080p · 30 fps · 8 Mbps", 1920, 1080, 30, 8_000_000),
      StreamProfile("720p · 30 fps · 5 Mbps", 1280, 720, 30, 5_000_000),
    )
  }
}

data class HighSpeedMode(val width: Int, val height: Int, val lower: Int, val upper: Int)

object ProfileCatalog {
  fun closest(profiles: List<StreamProfile>, width: Int, height: Int, fps: Int): Int {
    require(profiles.isNotEmpty())
    val exact = profiles.indexOfFirst { it.width == width && it.height == height && it.fps == fps }
    if (exact >= 0) return exact
    val rates = profiles.map { it.fps }.distinct()
    val rate = rates.filter { it <= fps }.maxOrNull() ?: rates.min()
    return profiles.withIndex().filter { it.value.fps == rate }.minBy {
      kotlin.math.abs(it.value.width.toLong() * it.value.height - width.toLong() * height)
    }.index
  }

  fun recommended(profiles: List<StreamProfile>, fast: Boolean): Int {
    require(profiles.isNotEmpty())
    val candidates = profiles.withIndex().filter {
      if (fast) it.value.fps <= 120 else !it.value.highSpeed && it.value.fps <= 60
    }.ifEmpty { profiles.withIndex().toList() }
    return candidates.maxWith(compareBy<IndexedValue<StreamProfile>> { it.value.fps }
      .thenBy { if (fast) -it.value.width * it.value.height else it.value.width * it.value.height }).index
  }

  fun highSpeedProfiles(
    modes: List<HighSpeedMode>, cameraId: String,
    encoderFor: (Int, Int, Int) -> String?,
  ): List<StreamProfile> = modes.asSequence()
    // A variable 30–240 range does not promise 240 frames. Only offer fixed rates.
    .filter { it.lower == it.upper && it.upper in setOf(120, 240) }
    .filter { it.width in 320..1920 && it.height in 240..1080 && it.width % 2 == 0 && it.height % 2 == 0 }
    .distinctBy { Triple(it.width, it.height, it.upper) }
    .mapNotNull { mode ->
      val encoder = encoderFor(mode.width, mode.height, mode.upper) ?: return@mapNotNull null
      val bitrate = when {
        mode.height >= 1080 -> if (mode.upper == 240) 40_000_000 else 24_000_000
        else -> if (mode.upper == 240) 24_000_000 else 16_000_000
      }
      StreamProfile("${mode.height}p · ${mode.upper} fps · ${bitrate / 1_000_000} Mbps",
        mode.width, mode.height, mode.upper, bitrate, cameraId, true, mode.lower, encoder)
    }.sortedWith(compareByDescending<StreamProfile> { it.fps }.thenByDescending { it.width * it.height })
    .toList()
}
