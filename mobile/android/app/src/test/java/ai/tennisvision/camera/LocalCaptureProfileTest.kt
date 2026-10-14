package ai.tennisvision.camera

import org.junit.Assert.*
import org.junit.Test

class LocalCaptureProfileTest {
  private val sizes=listOf(LocalStreamSize(1920,1080,30),LocalStreamSize(1280,720,60),LocalStreamSize(640,480,120),LocalStreamSize(3840,2160,30))
  @Test fun detailPreservesFullHdAndFastKeeps720p() {
    assertEquals(LocalStreamSize(1920,1080,30),LocalCaptureChoice.size(sizes,LocalCaptureProfile.DETAIL))
    assertEquals(LocalStreamSize(1280,720,60),LocalCaptureChoice.size(sizes,LocalCaptureProfile.FAST))
  }
  @Test fun actualYuvRateWinsOverAHighSpeedRecordingClaim() {
    val ranges=listOf(15 to 30,30 to 30,30 to 60,120 to 120,240 to 240)
    assertEquals(30 to 30,LocalCaptureChoice.fps(ranges,sizes[0],LocalCaptureProfile.DETAIL))
    assertEquals(30 to 60,LocalCaptureChoice.fps(ranges,sizes[1],LocalCaptureProfile.FAST))
    assertEquals(30 to 30,LocalCaptureChoice.fps(ranges,sizes[1],LocalCaptureProfile.ECONOMY))
  }
  @Test fun unsupportedFpsRangeIsNotInvented() {
    assertNull(LocalCaptureChoice.fps(listOf(120 to 120),sizes[1],LocalCaptureProfile.FAST))
    assertNull(LocalCaptureChoice.size(listOf(LocalStreamSize(1920,1080,10)),LocalCaptureProfile.DETAIL))
  }
  @Test fun oldEconomyPreferenceIsKept() {
    assertEquals(LocalCaptureProfile.ECONOMY,LocalCaptureProfile.saved(null,true))
    assertEquals(LocalCaptureProfile.DETAIL,LocalCaptureProfile.saved("DETAIL",true))
  }
  @Test fun lensLabelsDescribeTheActualFieldWithoutInventingMagnification() {
    assertEquals("Detalhe · campo 32° · câmera 3",LocalLensLabel.describe("3",32))
    assertEquals("Ampla · campo 108° · câmera 2",LocalLensLabel.describe("2",108))
    assertEquals("Equilibrada · campo 68° · câmera 0",LocalLensLabel.describe("0",68))
    assertEquals("Câmera 0",LocalLensLabel.describe("0",null))
  }
}
