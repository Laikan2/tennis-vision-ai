package ai.tennisvision.camera

import kotlin.math.hypot
import org.junit.Assert.*
import org.junit.Test

/** Contacts and saved-court line calls have different validity after a camera move. */
class ContactRecoveryTest {
  private val w=320; private val h=240
  private val area=listOf(CourtPoint(.1,.1),CourtPoint(.9,.1),CourtPoint(.9,.95),CourtPoint(.1,.95))
  private fun room()=IntArray(w*h){i->val v=60+(i%w/40+i/w/30)%3*45;0xff000000.toInt() or(v shl 16)or(v shl 8)or v}
  private fun image(x:Int=160,y:Int=120,ball:Boolean=false)=IntArray(w*h){i->
    if(ball && hypot(i%w-x.toDouble(),i/w-y.toDouble())<=6)0xffc7eb4d.toInt() else 0xff587082.toInt()
  }
  @Test fun stationaryNewViewRetainsContactMarksAndSpeedWithoutUsingTheOldAreaForInOut() {
    for(surface in listOf(ContactSurface.FLOOR,ContactSurface.WALL)) {
      val core=LocalAnalysis(LocalSettings(surface=surface,polygon=area,
        floorLine=if(surface==ContactSurface.FLOOR)listOf(CourtPoint(0.0,.1),CourtPoint(1.0,.1)) else emptyList()))
      core.process(room(),w,h,0.0)
      assertTrue(core.process(image(),w,h,.1).movingCamera)
      val stable=core.process(image(),w,h,1.0)
      assertFalse("Area review must not disable every contact in a now-stable view",stable.movingCamera)
      assertTrue(stable.areaNeedsConfirmation)
      val hits=mutableListOf<LocalImpact>()
      val coordinates=if(surface==ContactSurface.FLOOR)listOf(70,85,105,130,160,195,160,130,105,85) else listOf(80,100,120,140,160,140,120,100,80)
      for((i,c)in coordinates.withIndex()) {
        val result=core.process(image(if(surface==ContactSurface.WALL)c else 160,if(surface==ContactSurface.FLOOR)c else 120,true),w,h,1.1+i/30.0)
        result.impact?.let(hits::add)
      }
      assertEquals("A known $surface rebound in a stable view must produce one candidate",1,hits.size)
      val hit=hits.single();assertEquals(LineCall.REVIEW,hit.call);assertNotNull(hit.speedKmh)
      assertTrue(hit.reason.contains("Área"))
      assertEquals(if(surface==ContactSurface.FLOOR)160.0 else 160.0,hit.point.x*w,3.0)
      assertEquals(if(surface==ContactSurface.FLOOR)201.0 else 120.0,hit.point.y*h,5.0)
      assertFalse(LineVoicePolicy.enabled(hit.call,true,true))
    }
  }
  @Test fun sceneObstructionDoesNotBecomeAnImpactJustBecauseScoringSettles() {
    val core=LocalAnalysis(LocalSettings(polygon=area))
    core.process(room(),w,h,0.0)
    for(i in 1..120)assertNull(core.process(image(),w,h,i/60.0).impact)
  }
}

