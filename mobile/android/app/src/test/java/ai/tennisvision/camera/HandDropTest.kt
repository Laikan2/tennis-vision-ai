package ai.tennisvision.camera

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class HandDropTest {
  @Test fun heldBallIsVisibleAndDoesNotScoreAcrossTestedSkinColors() {
    HandDropEvaluation.cases().filter { it.name.startsWith("held_") }.forEach { c ->
      val r=HandDropEvaluation.evaluate(c)
      assertEquals(c.name,r.visible,r.found);assertEquals(c.name,0,r.falseLocations);assertTrue(c.name,r.calls.isEmpty())
    }
  }
  @Test fun fingersAcrossTheBallDoNotTurnOneBallIntoTwoUnrelatedTargets() {
    HandDropEvaluation.cases().filter { it.name.startsWith("fingers_") }.forEach { c ->
      val r=HandDropEvaluation.evaluate(c)
      assertTrue("${c.name}: ${r.found}/${r.visible}",r.found>=r.visible-1);assertTrue(c.name,r.calls.isEmpty())
    }
  }
  @Test fun carriedBallWithBounceShapedHandMotionCannotMakeAnOut() {
    HandDropEvaluation.cases().filter { it.name.startsWith("carried_") }.forEach { c ->
      val r=HandDropEvaluation.evaluate(c)
      assertTrue("${c.name}: ${r.calls}",r.calls.isEmpty());assertEquals(c.name,r.visible,r.found)
    }
  }
  @Test fun releasedBallIsTrackedFallingAndProducesOnlyTheKnownContact() {
    HandDropEvaluation.cases().filter { it.action==HandDropEvaluation.Action.DROP_BOUNCE }.forEach { c ->
      val r=HandDropEvaluation.evaluate(c)
      assertEquals(c.name,r.visible,r.found);assertEquals(c.name,0,r.falseLocations)
      assertEquals(c.name,listOf(if(c.outside)LineCall.OUT else LineCall.IN),r.calls)
      assertTrue("${c.name}: ${r.error}",r.error!!<=3)
    }
  }
  @Test fun aFallingBallNeedsAnObservedReturnBeforeItCanScore() {
    val c=HandDropEvaluation.cases().single { it.action==HandDropEvaluation.Action.FALL_ONLY }
    val r=HandDropEvaluation.evaluate(c)
    assertEquals(r.visible,r.found);assertTrue(r.calls.isEmpty())
  }
  @Test fun fullyHiddenBallAndHandWithoutBallDoNotCreateObservations() {
    HandDropEvaluation.cases().filter { it.action in listOf(HandDropEvaluation.Action.HIDDEN,HandDropEvaluation.Action.NO_BALL) }.forEach { c ->
      val r=HandDropEvaluation.evaluate(c);assertEquals(c.name,0,r.found);assertEquals(c.name,0,r.falseLocations);assertTrue(c.name,r.calls.isEmpty())
    }
  }
  @Test fun tinyDistractorsDoNotBlockAResolvedStationaryBallAtAcquisition() {
    val core=LocalAnalysis(HandDropEvaluation.settings)
    val c=HandDropEvaluation.cases().first()
    HandDropEvaluation.frames(c).forEach { f ->
      for(y in 240..241)for(x in 565..566)f.pixels[y*HandDropEvaluation.width+x]=0xffc7eb4d.toInt()
      val r=core.process(f.pixels,HandDropEvaluation.width,HandDropEvaluation.height,f.time)
      assertNotNull(r.observed);assertEquals(f.x,r.observed!!.x,1.0);assertNull(r.impact)
    }
  }
  @Test fun saveHandReleaseAndFallMeasurementsWithFailuresVisible() {
    val cases=HandDropEvaluation.cases().map(HandDropEvaluation::evaluate)
    val file=File("build/reports/hand-drop.csv");file.parentFile?.mkdirs()
    file.writeText(HandDropEvaluation.header+"\n"+cases.joinToString("\n"){it.csv()}+"\n")
    cases.forEach { println("HAND_DROP ${it.csv()}") }
    assertEquals(24,cases.size)
  }
  @Test fun partialBallHasNoSpeedOrAutomaticContact() {
    val c=HandDropEvaluation.cases().first { it.name=="fingers_medium" };val core=LocalAnalysis(HandDropEvaluation.settings)
    HandDropEvaluation.frames(c).forEach { f ->
      val r=core.process(f.pixels,HandDropEvaluation.width,HandDropEvaluation.height,f.time)
      assertTrue(r.observed!!.occluded);assertFalse(r.observed!!.resolved);assertNull(r.speedKmh);assertNull(r.impact)
    }
  }
  @Test fun stationaryTinyReflectionDoesNotAcquireARandomTrack() {
    val core=LocalAnalysis(HandDropEvaluation.settings)
    repeat(30) { i ->
      val pixels=IntArray(640*360){0xff315372.toInt()}
      for(y in 240..241)for(x in 565..566)pixels[y*640+x]=0xffc7eb4d.toInt()
      val r=core.process(pixels,640,360,i/60.0)
      assertNull(r.observed);assertNull(r.predicted);assertTrue(r.trail.isEmpty());assertNull(r.impact)
    }
  }
  @Test fun aMovingTinyBallCanBeShownAsUncertainAfterTwoObservedPositions() {
    val core=LocalAnalysis(HandDropEvaluation.settings);var count=0
    repeat(12) { i ->
      val pixels=IntArray(640*360){0xff315372.toInt()}
      for(y in 240..241)for(x in 100+i*12..101+i*12)pixels[y*640+x]=0xffc7eb4d.toInt()
      val r=core.process(pixels,640,360,i/60.0)
      r.observed?.let { assertFalse(it.resolved);count++ };assertNull(r.speedKmh);assertNull(r.impact)
    }
    assertEquals(11,count)
  }
  @Test fun firstQuickBounceAfterASmallVisibleGripIsRetainedForReview() {
    val core=LocalAnalysis(HandDropEvaluation.settings)
    val grip=HandDropEvaluation.Case("small_visible_grip",HandDropEvaluation.Action.CARRY_TURN,radius=2)
    var last:LocalResult?=null
    HandDropEvaluation.frames(grip).take(4).forEach { f ->
      last=core.process(f.pixels,640,360,f.time)
      assertNotNull(last!!.observed);assertNull(last!!.impact)
    }
    assertTrue("The last grip has observed moving-hand evidence",last!!.observed!!.nearHand)
    val events=mutableListOf<LocalImpact>()
    listOf(190,296,190,140).forEachIndexed { i,y ->
      val pixels=IntArray(640*360){0xff315372.toInt()}
      for(py in y-6..y+6)for(px in 314..326)
        if(kotlin.math.hypot((px-320).toDouble(),(py-y).toDouble())<=6)pixels[py*640+px]=0xffc7eb4d.toInt()
      val result=core.process(pixels,640,360,(4+i)/60.0)
      assertNotNull("The released full ball must remain observed",result.observed)
      result.impact?.let(events::add)
    }
    assertEquals("Do not erase the first contact on grip-to-flight size change",1,events.size)
    assertEquals(LineCall.REVIEW,events.single().call)
    assertNull(events.single().speedKmh)
  }
}
