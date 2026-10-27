package ai.tennisvision.camera
import kotlin.math.*
import org.junit.Assert.*
import org.junit.Test
class DepthContactFitTest {
  private val w=640;private val h=480
  private fun observation(t:Double,held:Boolean=false,rebound:Boolean=true):BallObservation {
    val d=t-.17;val z=2+(if(d<=0 || !rebound)4*d else -4*d)+2*d*d
    return BallObservation(320+50/z,240+20/z,40/z,t,.9,nearHand=held,luminance=160.0,greenDominant=true)
  }
  private fun pixels(b:BallObservation):IntArray {
    val p=IntArray(w*h){0xff587082.toInt()}
    for(y in max(0,(b.y-b.radius).toInt())..min(h-1,(b.y+b.radius).toInt()))for(x in max(0,(b.x-b.radius).toInt())..min(w-1,(b.x+b.radius).toInt()))
      if(hypot(x-b.x,y-b.y)<=b.radius)p[y*w+x]=0xffc7eb4d.toInt()
    return p
  }
  @Test fun radiusReturnWithoutVerticalImageBounceCreatesOnlyACandidate() {
    for(surface in listOf(ContactSurface.FLOOR,ContactSurface.WALL)) {
      val s=LocalSettings(surface=surface,polygon=listOf(CourtPoint(.1,.1),CourtPoint(.9,.1),CourtPoint(.9,.9),CourtPoint(.1,.9)),
        floorLine=if(surface==ContactSurface.FLOOR)listOf(CourtPoint(0.0,.1),CourtPoint(1.0,.1)) else emptyList())
      val core=LocalAnalysis(s);val hits=mutableListOf<LocalImpact>()
      for(i in 0..21)core.process(pixels(observation(i/60.0)),w,h,i/60.0).impact?.let(hits::add)
      assertEquals("$surface depth return",1,hits.size)
      val hit=hits.single();assertEquals(LineCall.REVIEW,hit.call);assertTrue(hit.reason.contains("profundidade"))
      assertEquals(.17,hit.time,.03);assertEquals(345.0,hit.point.x*w,4.0);assertEquals(250.0,hit.point.y*h,4.0)
      assertTrue(hit.uncertainty>=20)
    }
  }
  @Test fun depthFitRequiresBothObservedLegsAndRejectsHeldAndAbruptSizeChanges() {
    val p=(0..20).map{observation(it/60.0)}
    assertNotNull(DepthContactFit.find(p,w,h))
    assertNull(DepthContactFit.find(p.map{it.copy(nearHand=true)},w,h))
    assertNull(DepthContactFit.find(p.map{it.copy(occluded=true)},w,h))
    assertNull(DepthContactFit.find((0..20).map{observation(it/60.0,rebound=false)},w,h))
    assertNull(DepthContactFit.find(p.mapIndexed{i,b->b.copy(radius=if(i==10)9.0 else 24.0)},w,h))
    assertNull(DepthContactFit.find(p.map{it.copy(radius=24.0)},w,h))
  }
}

