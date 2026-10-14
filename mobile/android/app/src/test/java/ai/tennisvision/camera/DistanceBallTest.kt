package ai.tennisvision.camera

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class DistanceBallTest {
  @Test fun desaturatedGreenCourtCannotConnectTheWholeBackgroundToTheBall() {
    for(diameter in listOf(4.0,6.0)) {
      val c=DistanceBallEvaluation.Case("muted_green_${diameter.toInt()}px",diameter,0xff849c87.toInt(),true)
      val r=DistanceBallEvaluation.evaluate(c)
      println("DISTANCE_MUTED ${r.csv()}")
      assertTrue("A muted green court must not absorb the ball: ${r.csv()}",r.found>=14)
      assertEquals(0,r.wrong);assertTrue(r.impacts.isEmpty())
    }
  }
  @Test fun smallMovingBallSurvivesAreaSamplingChromaLossAndSubpixelPositions() {
    DistanceBallEvaluation.cases().filter { !it.clutter && !it.bounce && it.diameter>=3 }.forEach { c ->
      val r=DistanceBallEvaluation.evaluate(c)
      assertTrue("${c.name}: ${r.csv()}",r.found>=14)
      assertEquals(c.name,0,r.wrong);assertTrue(c.name,r.impacts.isEmpty())
    }
  }
  @Test fun smallMovingTargetCanAcquireBesideALargerStationaryObject() {
    DistanceBallEvaluation.cases().filter { it.clutter }.forEach { c ->
      val r=DistanceBallEvaluation.evaluate(c)
      assertTrue("${c.name}: ${r.csv()}",r.found>=13)
      assertTrue("Release the static object after confirming two moving positions: ${r.csv()}",r.wrong<=2)
      assertTrue(r.impacts.isEmpty())
    }
  }
  @Test fun tinyBounceRemainsReviewInsteadOfInventingConfidentInOutOrSpeed() {
    val c=DistanceBallEvaluation.cases().single{it.bounce};val r=DistanceBallEvaluation.evaluate(c)
    assertEquals(r.csv(),1,r.impacts.size)
    assertEquals(LineCall.REVIEW,r.impacts.single().call);assertNull(r.impacts.single().speedKmh)
  }
  @Test fun greyNoiseAndMovingWhiteGlareCannotBecomeGreenBallContacts() {
    val w=DistanceBallEvaluation.width;val h=DistanceBallEvaluation.height
    val core=LocalAnalysis(LocalSettings(court=CourtLayout.FULL))
    repeat(24) { i ->
      val p=IntArray(w*h){ k-> val c=120+(k*37+i*11)%9;0xff000000.toInt()or(c shl 16)or((c+3)shl 8)or c }
      DistanceBallEvaluation.spot(p,75.0+i*17,260.0-kotlin.math.abs(i-12)*10,4.0,color=0xffffffff.toInt())
      DistanceBallEvaluation.chroma420(p)
      val r=core.process(p,w,h,i/60.0)
      assertNull(r.observed);assertNull(r.impact);assertNull(r.speedKmh)
    }
  }
  @Test fun saveDistanceMeasurementsIncludingBelowDetailFailures() {
    val results=DistanceBallEvaluation.cases().map(DistanceBallEvaluation::evaluate)
    val f=File("build/reports/distance-ball.csv");f.parentFile?.mkdirs()
    f.writeText(DistanceBallEvaluation.header+"\n"+results.joinToString("\n"){it.csv()}+"\n")
    results.forEach { println("DISTANCE_BALL ${it.csv()}") }
    assertEquals(16,results.size)
  }
  @Test fun stationaryWeakColourReflectionDoesNotAcquireATrack() {
    val w=DistanceBallEvaluation.width;val h=DistanceBallEvaluation.height
    val core=LocalAnalysis(LocalSettings(surface=ContactSurface.TRACK))
    repeat(24) { i ->
      val p=IntArray(w*h){0xffe8e8e8.toInt()}
      DistanceBallEvaluation.spot(p,320.2,180.7,2.0)
      DistanceBallEvaluation.chroma420(p)
      val r=core.process(p,w,h,i/60.0)
      assertNull(r.observed);assertNull(r.predicted);assertNull(r.impact)
    }
  }
  @Test fun twoSmallMovingTargetsStayAmbiguous() {
    val w=DistanceBallEvaluation.width;val h=DistanceBallEvaluation.height
    val core=LocalAnalysis(LocalSettings(surface=ContactSurface.TRACK))
    repeat(16) { i ->
      val p=IntArray(w*h){0xff394b59.toInt()}
      DistanceBallEvaluation.spot(p,75.2+i*18,145.3,3.0)
      DistanceBallEvaluation.spot(p,550.2-i*18,210.3,3.0)
      DistanceBallEvaluation.chroma420(p)
      val r=core.process(p,w,h,i/60.0)
      assertNull(r.observed);assertNull(r.predicted);assertNull(r.impact)
    }
  }
  @Test fun compressionHaloDoesNotCertifyDetailOrVelocityOfAnUnresolvedBall() {
    val c=DistanceBallEvaluation.cases().single{it.name=="1.5px_below_detail"}
    val core=LocalAnalysis(LocalSettings(surface=ContactSurface.TRACK))
    DistanceBallEvaluation.frames(c).forEach { f ->
      val r=core.process(f.pixels,DistanceBallEvaluation.width,DistanceBallEvaluation.height,f.time)
      r.observed?.let { assertFalse(it.resolved) }
      assertNull(r.speedKmh);assertNull(r.impact)
    }
  }
}
