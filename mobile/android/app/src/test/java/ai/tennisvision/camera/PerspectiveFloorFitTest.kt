package ai.tennisvision.camera

import kotlin.math.*
import org.junit.Assert.*
import org.junit.Test

class PerspectiveFloorFitTest {
  private fun observations(transverse:Boolean=true)= (0..20).map { i ->
    val t=(i-10)/60.0
    val radius=26+75*abs(t)
    val y=if(transverse)360-850*abs(t)+120*t*t else 360.0
    BallObservation(320+(20+80*t)*radius/30,240+(y-240)*radius/30,radius,i/60.0,.85,
      luminance=150.0,feltChroma=150.0,greenDominant=true)
  }
  @Test fun perspectiveChangeDoesNotDiscardAnObservedTransverseFloorRebound() {
    val p=observations()
    assertNull("The constant-size fit cannot support this scale change",ObservedContactFit.find(p,ContactSurface.FLOOR))
    val fit=PerspectiveFloorFit.find(p,640,480)
    assertNotNull(fit);fit!!
    assertEquals(10/60.0,fit.time,.015)
    assertEquals(320+20*26/30.0,fit.x,3.0)
    assertEquals(240+120*26/30.0,fit.y,5.0)
    assertTrue(fit.error>1);assertTrue(fit.support.all { it in p })
  }
  @Test fun pureApproachAndRetreatCannotBePromotedToFloorInOut() {
    assertNull(PerspectiveFloorFit.find(observations(false),640,480))
  }
  @Test fun renderedPerspectiveBounceGetsOneLocalizedContactWithSpeed() {
    val w=640;val h=480
    val core=LocalAnalysis(LocalSettings(polygon=listOf(CourtPoint(.05,.05),CourtPoint(.95,.05),CourtPoint(.95,.95),CourtPoint(.05,.95)),floorLine=wholeImageFloorLine()))
    val hits=mutableListOf<LocalImpact>()
    repeat(10){i->core.process(IntArray(w*h){0xff333344.toInt()},w,h,i/60.0)}
    observations().forEach { b->
      val pixels=IntArray(w*h){0xff333344.toInt()}
      for(y in max(0,(b.y-b.radius).toInt())..min(h-1,(b.y+b.radius).toInt()))
        for(x in max(0,(b.x-b.radius).toInt())..min(w-1,(b.x+b.radius).toInt()))
          if(hypot(x-b.x,y-b.y)<=b.radius)pixels[y*w+x]=0xffc7eb4d.toInt()
      core.process(pixels,w,h,b.time+10/60.0).impact?.let{hits.add(it)}
    }
    println("PERSPECTIVE_PIXEL contacts="+hits.joinToString())
    assertEquals(1,hits.size);val hit=hits.single()
    assertEquals(LineCall.IN,hit.call);assertNotNull(hit.speedKmh)
    assertEquals((320+20*26/30.0)/w,hit.point.x,.02)
    assertEquals((240+120*26/30.0+26)/h,hit.point.y,.03)
  }
  @Test fun handGapsAndSizeFlickerBreakSupport() {
    val p=observations()
    assertNull(PerspectiveFloorFit.find(p.mapIndexed{i,b->if(i==10)b.copy(nearHand=true) else b},640,480))
    assertNull(PerspectiveFloorFit.find(p.mapIndexed{i,b->if(i>=10)b.copy(time=b.time+.09) else b},640,480))
    assertNull(PerspectiveFloorFit.find(p.mapIndexed{i,b->b.copy(radius=b.radius*if(i%2==0)1.0 else 1.3)},640,480))
  }
}
