package ai.tennisvision.camera

import android.media.MediaCodecInfo.CodecProfileLevel
import org.junit.Assert.*
import org.junit.Test

class AvcEncoderModesTest {
  private val baseline = AvcEncoderMode(CodecProfileLevel.AVCProfileBaseline, CodecProfileLevel.AVCLevel41)
  private val fast = AvcEncoderMode(CodecProfileLevel.AVCProfileHigh, CodecProfileLevel.AVCLevel52)

  @Test fun fastRecordingDoesNotForceBaselineWhenOnlyHighSupportsItsRate() {
    assertEquals(fast, AvcEncoderModes.choose(listOf(baseline, fast)) { it == fast })
  }

  @Test fun prefersBaselineWhenTheEntireRequestedFormatIsSupported() {
    assertEquals(baseline, AvcEncoderModes.choose(listOf(fast, baseline)) { true })
  }

  @Test fun rejectsAnUnsupportedFormatInsteadOfOpeningAnUnusableCodec() {
    assertNull(AvcEncoderModes.choose(listOf(baseline, fast)) { false })
  }
}
