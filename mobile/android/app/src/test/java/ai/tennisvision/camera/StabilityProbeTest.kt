package ai.tennisvision.camera

import org.junit.Assert.*
import org.junit.Test

class StabilityProbeTest {
  private fun run(fps:Int,capture:Double=fps.toDouble(),encoded:Double=capture,sent:Double=encoded,thermal:Int=0,network:Boolean=true):StabilityResult {
    val probe=StabilityProbe(fps,network,0)
    for(i in 0..25)probe.add(StabilitySample(i*1000L,capture,encoded,sent,0,20,35f,thermal))
    return probe.result()
  }
  @Test fun actual60IsNotAcceptedAs120Or240() {
    assertFalse(run(120,60.0).stable);assertFalse(run(240,60.0).stable)
    assertTrue(run(60).stable);assertTrue(run(120).stable);assertTrue(run(240).stable)
  }
  @Test fun identifiesCaptureEncoderNetworkAndThermalProblemsSeparately() {
    assertTrue(run(120,60.0).reason.contains("captura"))
    assertTrue(run(120,120.0,60.0).reason.contains("H.264"))
    assertTrue(run(120,120.0,120.0,30.0).reason.contains("envio"))
    assertFalse(run(120,thermal=2).stable)
    assertTrue(run(120,sent=0.0,network=false).stable)
  }
  @Test fun excludesWarmupButRejectsIncompleteTests() {
    val probe=StabilityProbe(240,false,0)
    for(i in 0..4)probe.add(StabilitySample(i*1000L,0.0,0.0,0.0,0,0,30f,0))
    assertFalse(probe.result().stable)
    for(i in 5..25)probe.add(StabilitySample(i*1000L,240.0,240.0,0.0,0,0,30f,0))
    assertTrue(probe.result().stable);assertEquals(0L,probe.remaining(25_000))
  }
  @Test fun imageAdviceUsesRealExposureRatherThanRequestedFps() {
    assertTrue(ImageAdvice.describe(10_000_000,2000,240,false).contains("podem borrar"))
    assertTrue(ImageAdvice.describe(10_000_000,2000,240,false).contains("ruído"))
    assertTrue(ImageAdvice.describe(1_000_000,200,120,true).contains("Exposição curta"))
    assertTrue(ImageAdvice.describe(0,0,240,false).contains("Aguarde"))
  }
}
