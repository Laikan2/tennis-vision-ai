package ai.tennisvision.camera

import org.junit.Assert.*
import org.junit.Test

class CaptureSessionPolicyTest {
  private val fast = StreamProfile("240 fps", 1280, 720, 240, 24_000_000, highSpeed = true)

  @Test fun selecting240DoesNotStartAHighSpeedPreviewWithoutAnEncoder() {
    assertFalse(CaptureSessionPolicy.usesHighSpeed(fast, recording = false))
    assertEquals(60 to 60, CaptureSessionPolicy.range(fast, false, listOf(15 to 30, 30 to 60, 60 to 60)))
  }

  @Test fun previewCanUse30WhenCameraHasNoOrdinary60Mode() {
    assertEquals(30 to 30, CaptureSessionPolicy.range(fast, false, listOf(15 to 30, 30 to 30)))
  }

  @Test fun previewRespectsTheResolutionLimitEvenWhenCameraAdvertises60ForOtherSizes() {
    assertEquals(30 to 30, CaptureSessionPolicy.range(fast, false,
      listOf(30 to 30, 60 to 60), maxPreviewFps = 30))
    assertEquals(240 to 240, CaptureSessionPolicy.range(fast, true,
      listOf(240 to 240), maxPreviewFps = 30))
  }

  @Test fun recordingRequestsExactly240EvenWhenAVariableRangeIsListedFirst() {
    assertTrue(CaptureSessionPolicy.usesHighSpeed(fast, recording = true))
    assertEquals(240 to 240, CaptureSessionPolicy.range(fast, true, listOf(30 to 240, 240 to 240)))
    assertEquals(120 to 120, CaptureSessionPolicy.range(fast.copy(fps = 120), true, listOf(30 to 120, 120 to 120)))
  }

  @Test fun variableRatesCannotMasqueradeAsFixedHighSpeedRecording() {
    assertThrows(IllegalArgumentException::class.java) {
      CaptureSessionPolicy.range(fast, true, listOf(30 to 240))
    }
    assertThrows(IllegalStateException::class.java) {
      CaptureSessionPolicy.range(fast, false, listOf(120 to 120, 240 to 240))
    }
  }

  @Test fun normalRecordingKeepsTheSupportedSourceRange() {
    val normal = fast.copy(fps = 60, fpsLower = 30, highSpeed = false)
    assertFalse(CaptureSessionPolicy.usesHighSpeed(normal, true))
    assertEquals(30 to 60, CaptureSessionPolicy.range(normal, true, emptyList()))
  }
}
