package ai.tennisvision.camera

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

/** Pixel/trajectory regression fixtures, not a real match accuracy benchmark. */
class TargetedDetectionTest {
  private val w=320;private val h=240
  private val settings=LocalSettings(polygon=listOf(CourtPoint(.2,.35),CourtPoint(.7,.35),CourtPoint(.7,.95),CourtPoint(.2,.95)),
    floorLine=listOf(CourtPoint(0.0,.3),CourtPoint(1.0,.3)))
  private fun frame(x:Double=150.0,y:Double=130.0,r:Double=12.0,seam:Int=0,hand:Boolean=false):IntArray {
    val p=IntArray(w*h){0xff8b8884.toInt()}
    if(hand)for(py in max(0,(y-r*.4).toInt())..min(h-1,(y+r*2.5).toInt()))
      for(px in max(0,(x-r*1.9).toInt())..min(w-1,(x+r*1.9).toInt()))p[py*w+px]=0xffb78063.toInt()
    for(py in max(0,(y-r).toInt())..min(h-1,(y+r).toInt()))for(px in max(0,(x-r).toInt())..min(w-1,(x+r).toInt()))
      if(hypot(px-x,py-y)<=r)p[py*w+px]=if(seam>0 && abs(px-x)<seam*.5)0xff373737.toInt() else 0xffc7df43.toInt()
    return p
  }
  @Test fun narrowDarkPrintedSeamDoesNotSplitOneHeldBallIntoAmbiguousTargets() {
    for(r in listOf(12.0,25.0,38.0))for(seam in listOf(1,3)) {
      val core=LocalAnalysis(settings);var seen=0
      repeat(12){i->val result=core.process(frame(r=r,seam=seam,hand=true),w,h,i/60.0)
        result.observed?.let{assertEquals(150.0,it.x,2.0);assertEquals(130.0,it.y,2.0);seen++};assertNull(result.impact)}
      assertEquals("radius=$r seam=$seam",12,seen)
    }
  }
  @Test fun twoSeparateRoundBallsRemainAmbiguous() {
    val core=LocalAnalysis(settings)
    repeat(8){i->val p=frame(x=130.0,r=12.0);val q=frame(x=159.0,r=12.0)
      for(k in p.indices)if(q[k]!=0xff8b8884.toInt())p[k]=q[k]
      val result=core.process(p,w,h,i/60.0);assertNull(result.observed);assertNull(result.impact)}
  }
  @Test fun resolvedCloseBallLargerThanOldShortDimensionGateIsVisible() {
    val b=LocalAnalysis(settings).process(frame(r=53.0),w,h,0.0).observed
    assertNotNull(b);assertEquals(150.0,b!!.x,2.0);assertEquals(130.0,b.y,2.0)
  }
  @Test fun vividFeltIsNotVetoedByASeparateDullGreenLeaf() {
    val p=frame(r=12.0)
    for(y in 54..66)for(x in 44..56)if(hypot(x-50.0,y-60.0)<=6)p[y*w+x]=0xff3f4928.toInt()
    val b=LocalAnalysis(settings).process(p,w,h,0.0).observed
    assertNotNull(b);assertEquals(150.0,b!!.x,2.0);assertEquals(130.0,b.y,2.0)
  }
  @Test fun isolatedStationaryLowChromaWarmFragmentDoesNotAcquire() {
    val p=IntArray(w*h){0xff918c84.toInt()}
    for(y in 94..106)for(x in 144..156)if(hypot(x-150.0,y-100.0)<=6)p[y*w+x]=0xffac9866.toInt()
    val core=LocalAnalysis(settings)
    repeat(12){i->val r=core.process(p,w,h,i/60.0);assertNull(r.observed);assertNull(r.impact)}
  }
  @Test fun resolvedDesaturatedGreenBallRemainsVisibleStationaryAndMovingSlowly() {
    for(step in listOf(0.0,1.0)) {
      val core=LocalAnalysis(settings)
      repeat(20){i->val x=100.0+i*step;val p=frame(x=x)
        for(k in p.indices)if(p[k]==0xffc7df43.toInt())p[k]=0xff78966e.toInt()
        val r=core.process(p,w,h,i/60.0)
        assertNotNull("Desaturated green at step=$step frame=$i",r.observed)
        assertEquals(x,r.observed!!.x,1.0);assertEquals(130.0,r.observed.y,1.0);assertNull(r.impact)
      }
    }
  }
  @Test fun realLowDisplacementBounceCountsOnceAtEachCaptureRate() {
    for(fps in listOf(30,60,120,240)) {
      val core=LocalAnalysis(settings);val hits=mutableListOf<LocalImpact>()
      // 120px/s on each observed leg: at240FPS, a five-frame window moves
      // only one pixel from its middle sample and cannot meet a3px threshold.
      for(i in 0..(fps*.4).toInt()) {
        val t=i.toDouble()/fps;val y=180-abs(t-.2)*120
        core.process(frame(y=y,r=6.0),w,h,t).impact?.let(hits::add)
      }
      assertEquals("$fps FPS observed bounce",1,hits.size)
      assertEquals(LineCall.IN,hits.single().call);assertEquals(186.5,hits.single().point.y*h,2.0)
    }
  }
  @Test fun extendedHistoryCannotCreateContactsFromStillJitterOrMonotonicFall() {
    for(mode in 0..2) {
      val core=LocalAnalysis(settings)
      repeat(100){i->val y=when(mode){0->180.0;1->180.0+sin(i*.6)*1.2;else->130.0+i*.5}
        assertNull(core.process(frame(y=y,r=6.0),w,h,i/240.0).impact)}
    }
  }
  @Test fun lowDisplacementTurnCarriedByHandCannotScore() {
    val core=LocalAnalysis(settings)
    repeat(97){i->val t=i/240.0;val y=180-abs(t-.2)*120
      assertNull(core.process(frame(y=y,r=6.0,hand=true),w,h,t).impact)}
  }
  @Test fun lowDisplacementTurnAboveFloorCannotScore() {
    val core=LocalAnalysis(settings.copy(floorLine=listOf(CourtPoint(0.0,.85),CourtPoint(1.0,.85))))
    repeat(97){i->val t=i/240.0;val y=180-abs(t-.2)*120
      assertNull(core.process(frame(y=y,r=6.0),w,h,t).impact)}
  }
  @Test fun mixedWoodFloorAndNeutralWallDoNotLookLikeAHandAtContact() {
    val core=LocalAnalysis(settings);val hits=mutableListOf<LocalImpact>()
    listOf(145,154,165,174,180,174,165,154,145).forEachIndexed { i,y ->
      val p=frame(y=y.toDouble(),r=6.0)
      for(py in 172 until h)for(px in 0 until w)if(p[py*w+px]==0xff8b8884.toInt())p[py*w+px]=0xffbc9062.toInt()
      core.process(p,w,h,i/60.0).impact?.let(hits::add)
    }
    assertEquals(1,hits.size);assertEquals(LineCall.IN,hits.single().call)
  }
}
