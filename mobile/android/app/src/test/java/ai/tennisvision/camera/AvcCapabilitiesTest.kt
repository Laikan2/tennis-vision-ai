package ai.tennisvision.camera

import android.media.MediaCodecInfo
import android.media.MediaCodecInfo.CodecProfileLevel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Run Android's AVC level tables, not just a mock that accepts the requested FPS. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AvcCapabilitiesTest {
  private fun chipCapabilities(): MediaCodecInfo.CodecCapabilities {
    val caps = MediaCodecInfo.CodecCapabilities.createFromProfileLevel("video/avc",
      CodecProfileLevel.AVCProfileHigh, CodecProfileLevel.AVCLevel52)!!
    caps.profileLevels = arrayOf(CodecProfileLevel().apply {
      profile = CodecProfileLevel.AVCProfileBaseline; level = CodecProfileLevel.AVCLevel42
    }, CodecProfileLevel().apply {
      profile = CodecProfileLevel.AVCProfileHigh; level = CodecProfileLevel.AVCLevel52
    })
    caps.colorFormats = intArrayOf(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
    return caps
  }

  @Test fun androidLimitsSelectHigh52For1080p240InsteadOfBaseline42() {
    val profile = StreamProfile("1080p240", 1920, 1080, 240, 40_000_000, highSpeed = true)
    val mode = AvcEncoderModes.forCapabilities(chipCapabilities(), profile, profile.bitrate)
    assertNotNull(mode)
    assertEquals(CodecProfileLevel.AVCProfileHigh, mode!!.profile)
    assertEquals(CodecProfileLevel.AVCLevel52, mode.level)
  }

  @Test fun androidLimitsKeepBaselineFor1080p60() {
    val profile = StreamProfile.stableProfiles.first()
    val mode = AvcEncoderModes.forCapabilities(chipCapabilities(), profile, profile.bitrate)
    assertNotNull(mode)
    assertEquals(CodecProfileLevel.AVCProfileBaseline, mode!!.profile)
  }
}
