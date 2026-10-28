package ai.tennisvision.camera

import kotlin.math.hypot
import org.junit.Assert.*
import org.junit.Test

/** Known projected descent/rebound, including variable delivery intervals. */
class ContactCadenceTest {
  private fun bounce(outside:Boolean,nearLine:Boolean=false):List<LocalImpact> {
    val w=320;val h=240
    val right=if(nearLine) .39 else if(outside) .2 else .9
    val settings=LocalSettings(polygon=listOf(CourtPoint(.02,.1),CourtPoint(right,.1),CourtPoint(right,.96),CourtPoint(.02,.96)),
      floorLine=listOf(CourtPoint(0.0,.1),CourtPoint(1.0,.1)))
    val core=LocalAnalysis(settings);val hits=mutableListOf<LocalImpact>()
    // Alternating 16.7/33.3 ms: all positions known, no invented frames.
    var t=0.0
    for(i in 0..24){
      val dt=t-.3;val y=if(dt<=0)170+240*dt+400*dt*dt else 170-168*dt+400*dt*dt
      val pixels=IntArray(w*h){0xff8b8884.toInt()}
      for(py in 0 until h)for(px in 0 until w)if(hypot(px-150.0,py-y)<=24)pixels[py*w+px]=0xffc7df43.toInt()
      core.process(pixels,w,h,t).impact?.let(hits::add)
      t+=if(i%2==0)1.0/60 else 1.0/30
    };return hits
  }
  @Test fun boundedCadenceVariationDoesNotForceEveryClearFloorContactToReview(){
    for(outside in listOf(false,true)){
      val hits=bounce(outside);assertEquals(1,hits.size)
      assertEquals(if(outside)LineCall.OUT else LineCall.IN,hits.single().call)
      assertEquals(.3,hits.single().time,.035)
      assertEquals(194.5,hits.single().point.y*240,4.0)
      assertNotNull(hits.single().speedKmh)
    }
  }
  @Test fun cadenceToleranceDoesNotHideLineUncertainty(){
    val hits=bounce(false,true);assertEquals(1,hits.size);assertEquals(LineCall.REVIEW,hits.single().call)
  }
}
