package ai.tennisvision.camera

import kotlin.math.abs
import kotlin.math.hypot
import org.junit.Assert.*
import org.junit.Test

/** Known observed pixel motion and capture timestamps, not match accuracy. */
class AdaptiveMotionTest {
  private val w=320;private val h=240
  private val settings=LocalSettings(
    polygon=listOf(CourtPoint(.1,.2),CourtPoint(.9,.2),CourtPoint(.9,.96),CourtPoint(.1,.96)),
    floorLine=listOf(CourtPoint(0.0,.1),CourtPoint(1.0,.1)))
  private fun frame(x:Double=150.0,y:Double):IntArray {
    val p=IntArray(w*h){0xff8b8884.toInt()}
    for(py in 0 until h)for(px in 0 until w)if(hypot(px-x,py-y)<=6)p[py*w+px]=0xffc7df43.toInt()
    return p
  }
  @Test fun projectedSpeedUsesEnoughObservedTimeAtEveryCaptureRate() {
    for(fps in listOf(30,60,120,240)) {
      val core=LocalAnalysis(settings);var last:LocalResult?=null
      for(i in 0..(fps*.25).toInt()) {
        val t=i.toDouble()/fps;last=core.process(frame(x=60+600*t,y=120.0),w,h,t)
      }
      assertNotNull("$fps FPS must still observe the ball",last!!.observed)
      assertNotNull("$fps FPS needs a time-supported projected speed",last!!.speedKmh)
      assertTrue("Known 600px/s, ~13px diameter => ~11.1 km/h projected",last!!.speedKmh!! in 9.8..12.5)
    }
  }
  @Test fun fastObservedBounceKeepsIncomingSpeedAt240FPS() {
    val core=LocalAnalysis(settings);val hits=mutableListOf<LocalImpact>()
    for(i in 0..72) {
      val t=i/240.0;core.process(frame(y=200-abs(t-.15)*600),w,h,t).impact?.let(hits::add)
    }
    assertEquals(1,hits.size);assertEquals(LineCall.IN,hits.single().call)
    assertNotNull("An observed incoming leg exists before the short contact window",hits.single().speedKmh)
    assertTrue(hits.single().speedKmh!! in 9.8..12.5)
  }
  @Test fun aSlowBounceWithSmallLocalizationNoiseIsNotLostAt240FPS() {
    for(fps in listOf(30,60,120,240))for(turn in listOf(.2,.208333333)) {
      val core=LocalAnalysis(settings);val hits=mutableListOf<LocalImpact>()
      for(i in 0..(fps*.4).toInt()) {
        val t=i.toDouble()/fps;val noise=if(i%2==0)1.2 else -1.2
        core.process(frame(y=180-abs(t-turn)*120+noise),w,h,t).impact?.let(hits::add)
      }
      assertEquals("$fps FPS, turn=$turn: preserve both observed legs",1,hits.size)
      assertEquals(LineCall.IN,hits.single().call)
    }
  }
  @Test fun speedCannotBridgeAMissingObservedInterval() {
    val core=LocalAnalysis(settings)
    for(t in listOf(0.0,1.0/60,2.0/60))core.process(frame(x=60+600*t,y=120.0),w,h,t)
    for(t in listOf(.125,.129,.133)) {
      val r=core.process(frame(x=60+600*t,y=120.0),w,h,t)
      assertNotNull(r.observed);assertNull("Do not bridge the missing interval to display speed",r.speedKmh)
    }
    val resumed=core.process(frame(x=150.0,y=120.0),w,h,.15)
    assertNotNull("Fresh time-supported observations recover the estimate",resumed.speedKmh)
  }
  @Test fun jitterAndJitteringMonotonicFallStillCannotCreateABounce() {
    for(mode in 0..1) {
      val core=LocalAnalysis(settings)
      repeat(100){i->val noise=if(i%2==0)1.8 else -1.8
        val y=if(mode==0)170+noise else 120+i*.5+noise
        assertNull(core.process(frame(y=y),w,h,i/240.0).impact)
      }
    }
  }
}
