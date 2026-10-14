package ai.tennisvision.camera

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

/** Controlled pixel fixtures, not a claim of accuracy on a live tennis match. */
class CourtDetectionTest {
  private val w=640
  private val h=360
  private val green=0xffc7eb4d.toInt()
  private fun image(x: Int?, y: Int=180, radius: Int=3, floor: Boolean=false): IntArray {
    val pixels=IntArray(w*h) { if(floor) 0xff73943e.toInt() else 0xff202532.toInt() }
    if(x!=null) ball(pixels,x,y,radius)
    return pixels
  }
  private fun ball(pixels: IntArray,x: Int,y: Int,radius: Int) {
    for(py in max(0,y-radius)..min(h-1,y+radius)) for(px in max(0,x-radius)..min(w-1,x+radius))
      if(hypot((px-x).toDouble(),(py-y).toDouble())<=radius) pixels[py*w+px]=green
  }
  @Test fun stationaryBallOnGreenCourtCanBeSeenBeforeTheServe() {
    val core=LocalAnalysis(LocalSettings(surface=ContactSurface.TRACK))
    var seen=0
    repeat(20) { i -> if(core.process(image(350,floor=true),w,h,i/60.0).observed!=null) seen++ }
    println("COURT_FIXTURE stationary_green_court=$seen/20")
    assertTrue("A stationary, locally contrasting ball must not be erased with the court: $seen/20",seen>=18)
  }
  @Test fun greenBallDoesNotNeedToBeBrighterThanAWhiteBackground() {
    val core=LocalAnalysis(LocalSettings(surface=ContactSurface.TRACK))
    repeat(12) { i ->
      val pixels=IntArray(w*h) { 0xffeeeeee.toInt() };ball(pixels,350,180,4)
      assertNotNull("A white wall must not erase a darker green ball",core.process(pixels,w,h,i/60.0).observed)
    }
  }
  @Test fun twoPixelTargetIsExposedAsUncertainRatherThanSilentlyDiscarded() {
    val detector=GreenBallDetector()
    val pixels=image(null)
    for(y in 178..179) for(x in 348..349) pixels[y*w+x]=green
    val found=detector.detect(pixels,w,h,0.0,null).first
    println("COURT_FIXTURE 2px_candidates=${found.size}; confidence=${found.firstOrNull()?.confidence}")
    assertEquals(1,found.size)
    assertTrue(found.single().confidence<.7)
  }
  @Test fun lostBallDoesNotInstantlyJumpToAnotherGreenObject() {
    val core=LocalAnalysis(LocalSettings(surface=ContactSurface.TRACK))
    repeat(5) { i -> assertNotNull(core.process(image(100+i*6),w,h,i/60.0).observed) }
    var wrong=0
    repeat(12) { i ->
      val r=core.process(image(520),w,h,(5+i)/60.0)
      if(r.observed?.x?.let { it>450 }==true) wrong++
      assertNull(r.impact)
    }
    assertEquals("A stationary distractor must not take over the lost track",0,wrong)
    println("COURT_FIXTURE distractor_takeovers=$wrong/12")
  }
  @Test fun sixToOneMovingBlurRemainsVisibleWithoutInventingSpeed() {
    val core=LocalAnalysis(LocalSettings(surface=ContactSurface.TRACK))
    var seen=0
    repeat(12) { i ->
      val x=60+i*35; val pixels=image(null)
      for(y in 177..183) for(px in x-18..x+18)
        if((px-x).toDouble().pow(2)/324+(y-180).toDouble().pow(2)/9<=1) pixels[y*w+px]=green
      val r=core.process(pixels,w,h,i/60.0)
      if(r.observed!=null) seen++
      assertNull(r.speedKmh)
      assertNull(r.impact)
    }
    assertTrue("Six-to-one blur: $seen/12",seen>=10)
    println("COURT_FIXTURE moving_blur_6to1=$seen/12")
  }
  @Test fun equallyPlausibleGreenObjectsDoNotGenerateARandomTrack() {
    val core=LocalAnalysis(LocalSettings(surface=ContactSurface.TRACK))
    repeat(12) { i ->
      val pixels=image(100+i*4);ball(pixels,500-i*4,180,3)
      val result=core.process(pixels,w,h,i/60.0)
      assertNull(result.observed);assertNull(result.predicted);assertTrue(result.trail.isEmpty());assertNull(result.impact)
    }
  }
  @Test fun largerStaticGreenObjectDoesNotWinOverTheMovingBall() {
    val core=LocalAnalysis(LocalSettings(surface=ContactSurface.TRACK))
    var seen=0
    repeat(12) { i ->
      val x=100+i*16;val pixels=image(x);ball(pixels,540,120,10)
      val result=core.process(pixels,w,h,i/60.0)
      result.observed?.let { assertEquals(x.toDouble(),it.x,1.0);seen++ }
    }
    assertTrue("Unique moving ball must be recovered beside a larger stationary green object: $seen/12",seen>=10)
    println("COURT_FIXTURE moving_ball_beside_larger_static_object=$seen/12")
  }
  @Test fun markedTennisCourtDoesNotRequireTheRoomWallFloorLine() {
    val polygon=listOf(CourtPoint(.20,.30),CourtPoint(.70,.30),CourtPoint(.85,.95),CourtPoint(.10,.95))
    for((x,expected) in listOf(280 to LineCall.IN,580 to LineCall.OUT)) {
      val core=LocalAnalysis(LocalSettings(polygon=polygon,court=CourtLayout.FULL))
      val hits=listOf(140,160,185,215,245,280,245,215,185,160).mapIndexedNotNull { i,y->core.process(image(x,y,6),w,h,i/60.0).impact }
      assertEquals(1,hits.size);assertEquals(expected,hits.single().call)
    }
  }
  @Test fun unresolvedDistantContactIsReviewNotACertainOut() {
    val polygon=listOf(CourtPoint(.2,.3),CourtPoint(.7,.3),CourtPoint(.7,.95),CourtPoint(.2,.95))
    val core=LocalAnalysis(LocalSettings(polygon=polygon,court=CourtLayout.FULL))
    val hits=listOf(140,160,185,215,245,280,245,215,185,160).mapIndexedNotNull { i,y->
      val pixels=image(null)
      for(py in y..y+1) for(px in 580..581) pixels[py*w+px]=green
      core.process(pixels,w,h,i/60.0).impact
    }
    assertEquals(1,hits.size);assertEquals(LineCall.REVIEW,hits.single().call);assertNull(hits.single().speedKmh)
  }
}
