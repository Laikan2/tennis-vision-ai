package ai.tennisvision.camera

import org.junit.Assert.*
import org.junit.Test

class ManualExposureTest {
  @Test fun returningToSlowerShutterReducesIsoInsteadOfBlowingOutTheBall() {
    assertEquals(800, ManualExposure.iso(3200, 250_000, 1_000_000, 50, 6400))
    assertEquals(3200, ManualExposure.iso(800, 1_000_000, 250_000, 50, 6400))
  }

  @Test fun repeatedShutterChangesDoNotKeepRaisingIso() {
    var iso = 200
    repeat(8) {
      iso = ManualExposure.iso(iso, 1_000_000, 250_000, 50, 6400)
      iso = ManualExposure.iso(iso, 250_000, 1_000_000, 50, 6400)
    }
    assertEquals(200, iso)
  }

  @Test fun sensorLimitsAndMissingMetadataStaySafe() {
    assertEquals(6400, ManualExposure.iso(1600, 30_000_000, 250_000, 50, 6400))
    assertEquals(50, ManualExposure.iso(50, 250_000, 4_000_000, 50, 6400))
    assertEquals(100, ManualExposure.iso(0, 0, 250_000, 100, 6400))
  }
}
