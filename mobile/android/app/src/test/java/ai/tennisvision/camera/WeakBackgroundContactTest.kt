package ai.tennisvision.camera

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

/** Known negative pixels, motivated by false marks on a warm wooden floor. */
class WeakBackgroundContactTest {
  private val w=320;private val h=240
  private val settings=LocalSettings(polygon=listOf(CourtPoint(.2,.3),CourtPoint(.7,.3),CourtPoint(.7,.95),CourtPoint(.2,.95)),
    floorLine=listOf(CourtPoint(0.0,.25),CourtPoint(1.0,.25)))
  private fun frame(x:Double,y:Double,r:Double=3.0,color:Int=0xffac9866.toInt()):IntArray {
    val p=IntArray(w*h){0xff394b59.toInt()}
    for(py in max(0,(y-r).toInt())..min(h-1,ceil(y+r).toInt()))for(px in max(0,(x-r).toInt())..min(w-1,ceil(x+r).toInt()))
      if(hypot(px-x,py-y)<=r)p[py*w+px]=color
    return p
  }
  @Test fun movingWarmFloorFragmentsCannotAcquireOrCreateReviewContacts() {
    for(r in listOf(2.0,3.0,4.0)) {
      val core=LocalAnalysis(settings)
      listOf(70,85,105,130,160,195,160,130,105,85).forEachIndexed { i,y ->
        val result=core.process(frame(150.0,y.toDouble(),r),w,h,i/60.0)
        assertNull("Warm fragment radius=$r frame=$i must not be a ball",result.observed)
        assertNull("REVIEW is still a contact claim",result.impact)
      }
    }
  }
  @Test fun warmFragmentsCannotMakeWallContactCandidatesEither() {
    val core=LocalAnalysis(settings.copy(surface=ContactSurface.WALL))
    listOf(70,85,105,130,160,195,160,130,105,85).forEachIndexed { i,x ->
      val result=core.process(frame(x.toDouble(),150.0),w,h,i/60.0)
      assertNull(result.observed);assertNull(result.impact)
    }
  }
  @Test fun warmYellowFeltStillProducesKnownInAndOut() {
    for((x,call) in listOf(140.0 to LineCall.IN,285.0 to LineCall.OUT)) {
      val core=LocalAnalysis(settings);val hits=mutableListOf<LocalImpact>()
      listOf(70,85,105,130,160,195,160,130,105,85).forEachIndexed { i,y ->
        core.process(frame(x,y.toDouble(),6.0,0xffbd930e.toInt()),w,h,i/60.0).impact?.let(hits::add)
      }
      assertEquals(1,hits.size);assertEquals(call,hits.single().call)
    }
  }
  @Test fun briefWeakColourCanContinueOnlyAnAlreadySupportedTrajectory() {
    val core=LocalAnalysis(settings.copy(surface=ContactSurface.TRACK))
    repeat(18){i->
      val color=if(i in 6..7)0xffac9866.toInt() else 0xffc7eb4d.toInt()
      val result=core.process(frame(70.0+i*3,120.0,6.0,color),w,h,i/60.0)
      assertNotNull("Supported trajectory frame=$i",result.observed);assertNull(result.impact)
    }
  }
  @Test fun weakColourCannotKeepAnUnconfirmedTrackAliveIndefinitely() {
    val core=LocalAnalysis(settings.copy(surface=ContactSurface.TRACK))
    repeat(30){i->
      val color=if(i<5)0xffc7eb4d.toInt() else 0xffac9866.toInt()
      val result=core.process(frame(70.0+i*3,120.0,6.0,color),w,h,i/60.0)
      if(i>=12)assertNull("Weak continuation must expire at frame=$i",result.observed)
      if(i>=5)assertNull("Uncertain colour must not produce speed",result.speedKmh)
      assertNull(result.impact)
    }
  }
  @Test fun colourLostOnTheOutgoingLegCannotCertifyAContact() {
    val core=LocalAnalysis(settings)
    repeat(21){i->
      val y=180.0-abs(i-8)*2
      val color=if(i<=8)0xffc7eb4d.toInt() else 0xffac9866.toInt()
      assertNull(core.process(frame(150.0,y,3.0,color),w,h,i/60.0).impact)
    }
  }
}
