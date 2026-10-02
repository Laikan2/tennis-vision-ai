package ai.tennisvision.camera

import org.junit.Assert.*
import org.junit.Test

class FrameRateMeterTest {
  @Test fun measuresActual120And240Fps() {
    for (target in listOf(60, 120, 240)) {
      val meter = FrameRateMeter()
      var value: Double? = null
      for (i in 0..target) meter.frame(i * 1_000_000_000L / target)?.let { value = it }
      assertEquals(target.toDouble(), value!!, 0.01)
    }
  }

  @Test fun missingFramesAndDelayedReportsAreNotReportedAsRequestedRate() {
    val meter = FrameRateMeter()
    meter.frame(0)
    for (i in 1..119) meter.frame(i * 1_000_000_000L / 240)
    assertEquals(96.0, meter.frame(1_250_000_000L)!!, 0.01)
  }

  @Test fun duplicatesAndResetsDoNotInflateFps() {
    val meter = FrameRateMeter()
    meter.frame(0)
    repeat(10) { assertNull(meter.frame(0)) }
    assertNull(meter.frame(-1))
    assertEquals(1.0, meter.frame(1_000_000_000L)!!, 0.01)
    meter.reset()
    assertNull(meter.frame(0))
    assertEquals(1.0, meter.frame(1_000_000_000L)!!, 0.01)
  }
}
