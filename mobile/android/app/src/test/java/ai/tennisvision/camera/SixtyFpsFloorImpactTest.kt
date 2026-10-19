package ai.tennisvision.camera

import kotlin.math.abs
import kotlin.math.hypot
import org.junit.Assert.*
import org.junit.Test

/** Physical-shaped projected arcs with known contact time, not phone accuracy. */
class SixtyFpsFloorImpactTest {
  private val w=320;private val h=240
  private val settings=LocalSettings(polygon=listOf(CourtPoint(.1,.1),CourtPoint(.9,.1),CourtPoint(.9,.96),CourtPoint(.1,.96)),
    floorLine=listOf(CourtPoint(0.0,.1),CourtPoint(1.0,.1)))
  private fun frame(y:Double?,radius:Double=24.0,hand:Boolean=false):IntArray {
    val p=IntArray(w*h){0xff8b8884.toInt()};if(y==null)return p
    if(hand)for(py in 0 until h)for(px in 0 until w)
      if(px in (150-radius*1.3).toInt()..(150+radius*1.3).toInt() && py in (y-radius*1.9).toInt()..(y+radius*.4).toInt())p[py*w+px]=0xffb47b65.toInt()
    for(py in 0 until h)for(px in 0 until w)if(hypot(px-150.0,py-y)<=radius)p[py*w+px]=0xffc7df43.toInt()
    return p
  }
  private fun arc(t:Double,rebound:Boolean=true):Double {
    // Free-fall acceleration 800px/s², impact at0.3s, incoming240px/s,
    // rebound168px/s (0.7 restitution). Only image-plane units are known.
    val dt=t-.3
    return if(dt<=0)170+240*dt+400*dt*dt else if(rebound)170-168*dt+400*dt*dt else 170+240*dt+400*dt*dt
  }
  private fun run(fps:Int,s:LocalSettings=settings,hand:Boolean=false,rebound:Boolean=true):Pair<List<LocalResult>,List<LocalImpact>> {
    val core=LocalAnalysis(s);val results=(0..(fps*.52).toInt()).map{i->val t=i.toDouble()/fps
      core.process(frame(arc(t,rebound),hand=hand),w,h,t).also{r->r.impact?.let{hit->
        println("FLOOR_CONTROL fps=$fps hand=$hand return=$rebound emitted=$t contact=${hit.time} yPx=${hit.point.y*h} call=${hit.call} speed=${hit.speedKmh} uncertainty=${hit.uncertainty}")}}
    }
    return results to results.mapNotNull{it.impact}
  }
  @Test fun aVisibleParabolicBounceAt60FPSProducesAMarkAndIncomingSpeed() {
    val (frames,hits)=run(60)
    assertTrue("The positive control must keep observing the ball",frames.count{it.observed!=null}>=frames.size-1)
    assertEquals("Visible descent and slower rebound must produce one mark",1,hits.size)
    assertEquals(LineCall.IN,hits.single().call)
    assertEquals("Known contact time",.3,hits.single().time,.035)
    assertEquals("Mark must stay at the known bottom of the ball",194.5,hits.single().point.y*h,3.0)
    assertNotNull(hits.single().speedKmh);assertTrue(hits.single().speedKmh!! in .9..1.3)
  }
  @Test fun theSameProjectedArcIsNotDependentOnFrameCount() {
    for(fps in listOf(30,120,240)) {
      val (frames,hits)=run(fps)
      assertTrue("$fps FPS keeps the visible ball",frames.count{it.observed!=null}>=frames.size-1)
      assertEquals("$fps FPS physical-time window",1,hits.size);assertEquals(LineCall.IN,hits.single().call)
    }
  }
  @Test fun fallingWithoutObservedReturnDoesNotCreateAContact() {
    assertTrue(run(60,rebound=false).second.isEmpty())
  }
  @Test fun theSameArcCarriedByHandDoesNotScore() {
    val (frames,hits)=run(60,hand=true)
    assertTrue(frames.count{it.observed!=null}>=frames.size-1);assertTrue(hits.isEmpty())
  }
  @Test fun theSameTurnClearlyAboveTheMarkedFloorDoesNotScore() {
    assertTrue(run(60,settings.copy(floorLine=listOf(CourtPoint(0.0,.95),CourtPoint(1.0,.95)))).second.isEmpty())
  }
  @Test fun aLongWindowWithoutAFloorReferenceDoesNotInventAContact() {
    assertTrue(run(60,settings.copy(polygon=emptyList(),floorLine=emptyList())).second.isEmpty())
  }
  @Test fun shortLossNearContactKeepsOnlyAnUncertainCandidate() {
    val core=LocalAnalysis(settings);val hits=mutableListOf<LocalImpact>()
    for(i in 0..24) {
      val t=i/60.0;val y=if(i in 9..15)null else 180-abs(t-.2)*600
      core.process(frame(y,6.0),w,h,t).impact?.let(hits::add)
    }
    assertEquals("Two clean observed legs survive a bounded contact-time loss",1,hits.size)
    assertEquals(LineCall.REVIEW,hits.single().call)
    assertTrue("The unseen interval must increase position uncertainty",hits.single().uncertainty>=30)
  }
  @Test fun aLongLossCannotJoinUnrelatedLegsIntoAContact() {
    val core=LocalAnalysis(settings)
    for(i in 0..28) {
      val t=i/60.0;val y=if(i in 9..19)null else 180-abs(t-.2)*600
      assertNull(core.process(frame(y,6.0),w,h,t).impact)
    }
  }
  @Test fun slowBounceOutsideTheAreaKeepsItsActualMarkAndOUT() {
    val outside=settings.copy(polygon=listOf(CourtPoint(.02,.1),CourtPoint(.2,.1),CourtPoint(.2,.96),CourtPoint(.02,.96)))
    val hits=run(60,outside).second
    assertEquals(1,hits.size);assertEquals(LineCall.OUT,hits.single().call)
    assertEquals(150.0,hits.single().point.x*w,1.0);assertEquals(194.5,hits.single().point.y*h,3.0)
  }
  @Test fun contactBetweenFramesUsesObservedLocalPeakInsteadOfWideCurvedLegs() {
    for(fps in listOf(60,120,240))for(phase in listOf(1.0/3,2.0/3)) {
      val offset=phase/fps;val core=LocalAnalysis(settings);val hits=mutableListOf<LocalImpact>()
      for(i in 0..(fps*.58).toInt()){val t=i.toDouble()/fps;core.process(frame(arc(t-offset),20.0),w,h,t).impact?.let(hits::add)}
      assertEquals("$fps FPS phase $phase",1,hits.size)
      assertEquals(LineCall.IN,hits.single().call);assertEquals(.3+offset,hits.single().time,1.0/fps)
      assertEquals(190.5,hits.single().point.y*h,3.0)
    }
  }
  @Test fun keepingObservedHistoryDoesNotExtendPredictionPast60ms() {
    val core=LocalAnalysis(settings)
    for(i in 0..15){val t=i/60.0;val y=if(i>=9)null else 180-abs(t-.2)*600
      val result=core.process(frame(y,6.0),w,h,t)
      if(i>=12)assertNull("No unobserved ball after 60 ms",result.predicted)
      assertNull(result.impact)
    }
  }
}
