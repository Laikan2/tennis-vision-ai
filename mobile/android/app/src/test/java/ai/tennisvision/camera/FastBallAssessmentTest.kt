package ai.tennisvision.camera

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class FastBallAssessmentTest {
  @Test fun measureAgainstKnownFastPixelTrajectories() {
    val results=FastBallEvaluation.cases().map(FastBallEvaluation::evaluate)
    val output=File("build/reports/fast-ball-jvm.csv");output.parentFile?.mkdirs()
    output.writeText(FastBallEvaluation.header+"\n"+results.joinToString("\n") { it.csv() }+"\n")
    // Controls assert the pipeline ran; stress-case failures stay visible in the measured report.
    assertEquals(listOf(LineCall.IN),results.first { it.case.name=="diam12_step20" }.calls)
    assertEquals(listOf(LineCall.OUT),results.first { it.case.name=="out_fast" }.calls)
    assertTrue(results.first { it.case.name=="air_crossing" }.calls.isEmpty())
  }
  @Test fun fastContactBetweenFramesIsLocatedNearTheKnownBounce() {
    for (phase in listOf(.25,.5,.75)) {
      val result=FastBallEvaluation.evaluate(FastBallEvaluation.Case("phase",6.0,40.0,phase=phase))
      assertEquals(listOf(LineCall.IN),result.calls)
      assertTrue("Position error at phase $phase: ${result.contactErrorPx}",result.contactErrorPx!!<=3.0)
    }
  }
  @Test fun missingContactFrameMustNotCreateCertainOut() {
    val result=FastBallEvaluation.evaluate(FastBallEvaluation.Case("hidden",6.0,30.0,outside=true,occludeContact=true))
    assertEquals(listOf(LineCall.REVIEW),result.calls)
  }
}
