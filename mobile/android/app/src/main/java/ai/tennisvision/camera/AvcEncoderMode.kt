package ai.tennisvision.camera

import android.media.MediaCodecInfo
import android.media.MediaFormat

data class AvcEncoderMode(val profile: Int, val level: Int)

/** Capability limits can differ between AVC Baseline and High on the same chip. */
object AvcEncoderModes {
  fun choose(modes: List<AvcEncoderMode>, supports: (AvcEncoderMode) -> Boolean): AvcEncoderMode? {
    val order = listOf(MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline,
      MediaCodecInfo.CodecProfileLevel.AVCProfileConstrainedBaseline,
      MediaCodecInfo.CodecProfileLevel.AVCProfileMain,
      MediaCodecInfo.CodecProfileLevel.AVCProfileHigh,
      MediaCodecInfo.CodecProfileLevel.AVCProfileConstrainedHigh)
    return modes.sortedWith(compareBy<AvcEncoderMode> {
      order.indexOf(it.profile).takeIf { index -> index >= 0 } ?: Int.MAX_VALUE
    }.thenBy { it.level }).firstOrNull(supports)
  }

  fun forCapabilities(caps: MediaCodecInfo.CodecCapabilities, config: StreamProfile, bitrate: Int): AvcEncoderMode? =
    choose(caps.profileLevels.map { AvcEncoderMode(it.profile, it.level) }) { mode ->
      runCatching {
        val format = MediaFormat.createVideoFormat("video/avc", config.width, config.height).apply {
          setInteger(MediaFormat.KEY_FRAME_RATE, config.fps)
          setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
          setInteger(MediaFormat.KEY_PROFILE, mode.profile)
          setInteger(MediaFormat.KEY_LEVEL, mode.level)
          setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        }
        val limits = MediaCodecInfo.CodecCapabilities.createFromProfileLevel("video/avc", mode.profile, mode.level)
        // Check this advertised profile/level, rather than the union of all modes.
        limits != null && limits.isFormatSupported(format) && caps.isFormatSupported(format)
      }.getOrDefault(false)
    }
}
