package ai.tennisvision.camera

import org.junit.Assert.*
import org.junit.Test

class TransportRecoveryTest {
  @Test fun queueDurationStaysBoundedAtHighFrameRates() {
    for (fps in listOf(30, 60, 120, 240)) {
      for (profile in TransportProfile.entries) {
        val frames = profile.queueFrames(fps)
        assertTrue(frames >= 3)
        assertTrue(frames * 1000.0 / fps <= maxOf(profile.queueMs.toDouble(), 3000.0 / fps) + 1000.0 / fps)
      }
    }
    assertEquals(20, TransportProfile.FAST.queueFrames(240))
    assertEquals(TransportProfile.BALANCED, TransportProfile.forLatency(80))
  }

  @Test fun recoveryNeverSendsDependentFramesBeforeFreshKeyframe() {
    val recovery = KeyframeRecovery()
    assertTrue(recovery.accepts(false))
    recovery.request()
    repeat(10) { assertFalse(recovery.accepts(false)) }
    assertTrue(recovery.accepts(true))
    assertTrue(recovery.accepts(false))
    assertEquals(10L, recovery.discarded)
    recovery.request()
    assertFalse(recovery.accepts(false))
    recovery.reset()
    assertTrue(recovery.accepts(false))
    assertEquals(0L, recovery.discarded)
  }
}
