package ai.tennisvision.camera

import kotlin.math.*
import org.junit.Assert.*
import org.junit.Test

/** A large close ball is not its own green background; a carried turn is not a bounce. */
class CloseRangeBallTest {
  private val w=720;private val h=960
  private fun frame(y:Double,r:Double,hand:Boolean=false,color:Int=0xff755d18.toInt()):IntArray {
    val x=360.0;val p=IntArray(w*h){0xff88705a.toInt()}
    if(hand)for(py in max(0,(y-r*1.9).toInt())..min(h-1,(y+r*.4).toInt()))
      for(px in (x-r*1.3).toInt()..(x+r*1.3).toInt())p[py*w+px]=0xffb47b65.toInt()
    for(py in max(0,(y-r).toInt())..min(h-1,(y+r).toInt()))for(px in (x-r).toInt()..(x+r).toInt())
      if(hypot(px-x,py-y)<=r)p[py*w+px]=color
    return p
  }
  @Test fun largeWarmAndGreenBallKeepTheirWholeSilhouette() {
    for(color in listOf(0xff755d18.toInt(),0xffc7df43.toInt())) {
      val b=LocalAnalysis().process(frame(400.0,140.0,color=color),w,h,0.0).observed
      assertNotNull("A 280px ball must not become small felt fragments",b)
      assertEquals(360.0,b!!.x,6.0);assertEquals(400.0,b.y,6.0);assertEquals(140.0,b.radius,8.0)
    }
  }
  @Test fun movingCloseGripOverWarmFloorCannotScoreABounce() {
    val core=LocalAnalysis(LocalSettings(polygon=listOf(CourtPoint(.1,.1),CourtPoint(.9,.1),CourtPoint(.9,.95),CourtPoint(.1,.95)),floorLine=listOf(CourtPoint(0.0,.05),CourtPoint(1.0,.05))))
    var seen=0;var held=0
    val heights=listOf(380.0,430.0,480.0,530.0,580.0,630.0,580.0,530.0,480.0,430.0,380.0)
    heights.forEachIndexed{i,y->
      val result=core.process(frame(y,90.0,hand=true),w,h,i/60.0)
      result.observed?.let{seen++;if(it.nearHand)held++;assertEquals(360.0,it.x,8.0);assertEquals(y,it.y,8.0)}
      assertNull("Carrying a ball through a downward/upward turn is not floor contact",result.impact)
    }
    assertTrue("Do not pass by losing the ball",seen>=8)
    assertTrue("Visible moving grip must be represented",held>=3)
  }
  @Test fun highlightInsideLargeUnderexposedFeltIsNotATinyResolvedBall() {
    val p=frame(400.0,70.0,color=0xff352a0a.toInt())
    for(y in 396..404)for(x in 356..364)if(hypot(x-360.0,y-400.0)<=4)p[y*w+x]=0xff75601a.toInt()
    val b=LocalAnalysis().process(p,w,h,0.0).observed
    assertTrue("A 140px underexposed silhouette cannot be represented by its 8px highlight",b==null || b.radius>=50 && b.occluded)
  }
  @Test fun isolatedTinyWarmBallIsNotRejectedByTheHighlightGuard() {
    val p=frame(400.0,4.0,color=0xff75601a.toInt())
    val b=LocalAnalysis().process(p,w,h,0.0).observed
    assertNotNull(b);assertEquals(360.0,b!!.x,1.0);assertEquals(400.0,b.y,1.0)
  }
}
