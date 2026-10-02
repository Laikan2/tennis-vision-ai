package ai.tennisvision.camera

import org.junit.Assert.*
import org.junit.Test

class StreamReliabilityTest {
  @Test fun staleCallbacksCannotChangeANewConnection() {
    val epoch = CallbackEpoch()
    val old = epoch.advance()
    assertTrue(epoch.isCurrent(old))
    epoch.advance()
    assertFalse(epoch.isCurrent(old))
    val current = epoch.advance()
    assertTrue(epoch.isCurrent(current))
    assertFalse(epoch.isCurrent(old))
  }
  @Test fun captureStallsAreReportedOnlyForAnActiveSource() {
    assertFalse(StreamHealth.stalled(0, 5_000, true))
    assertFalse(StreamHealth.stalled(1_000, 4_000, true))
    assertTrue(StreamHealth.stalled(1_000, 4_001, true))
    assertFalse(StreamHealth.stalled(1_000, 4_001, false))
    assertFalse(StreamHealth.stalled(5_000, 4_001, true))
  }
  @Test fun queueEstimateDoesNotOverflowForLargeCounters() {
    assertEquals(100L, StreamHealth.queueMs(150_000, 12_000_000))
    assertEquals(0L, StreamHealth.queueMs(100, 0))
    assertEquals(0L, StreamHealth.queueMs(-1, 100))
    assertEquals(8_000_000_000L, StreamHealth.queueMs(1_000_000_000_000, 1_000_000))
  }
  @Test fun aLiveCameraCannotHideAStalledHardwareEncoder() {
    assertFalse(StreamHealth.encoderStalled(1_000, 0, 9_000, true))
    assertTrue(StreamHealth.encoderStalled(1_000, 0, 9_001, true))
    assertFalse(StreamHealth.encoderStalled(1_000, 8_900, 10_000, true))
    assertTrue(StreamHealth.encoderStalled(1_000, 8_900, 11_901, true))
    assertFalse(StreamHealth.encoderStalled(1_000, 0, 20_000, false))
    assertFalse(StreamHealth.encoderStalled(0, 0, 20_000, true))
    assertFalse(StreamHealth.encoderStalled(20_000, 21_000, 19_000, true))
  }
  @Test fun fastPresetSelectsSupported120Before240AndPrefers720() {
    val modes = listOf(StreamProfile.stableProfiles.first(),
      StreamProfile("720p120", 1280, 720, 120, 16_000_000, highSpeed = true),
      StreamProfile("1080p120", 1920, 1080, 120, 24_000_000, highSpeed = true),
      StreamProfile("720p240", 1280, 720, 240, 24_000_000, highSpeed = true))
    assertEquals(1, ProfileCatalog.recommended(modes, true))
    assertEquals(0, ProfileCatalog.recommended(modes, false))
    assertEquals(0, ProfileCatalog.recommended(listOf(modes.last()), false))
  }
  @Test fun unsupportedQrResolutionKeepsTheAvailableHighRate() {
    val modes = StreamProfile.stableProfiles + StreamProfile("720p120", 1280, 720, 120, 16_000_000, highSpeed = true)
    assertEquals(4, ProfileCatalog.closest(modes, 1920, 1080, 240))
    assertEquals(4, ProfileCatalog.closest(modes, 1920, 1080, 120))
    assertEquals(3, ProfileCatalog.closest(modes, 1280, 720, 30))
  }
  @Test fun profileFallbackNeverPretendsToOfferAnUnsupportedRate() {
    val modes = StreamProfile.stableProfiles
    assertEquals(2, ProfileCatalog.closest(modes, 1920, 1080, 24))
    assertEquals(0, ProfileCatalog.closest(modes, 1920, 1080, 120))
    assertThrows(IllegalArgumentException::class.java) { ProfileCatalog.closest(emptyList(), 1920, 1080, 60) }
  }
}
