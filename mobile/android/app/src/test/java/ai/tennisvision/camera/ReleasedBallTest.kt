package ai.tennisvision.camera

import kotlin.math.hypot
import org.junit.Assert.*
import org.junit.Test

/** Release must not inherit a hand forever over a two-tone warm floor. */
class ReleasedBallTest {
  private val w=720;private val h=960
  private fun frame(y:Double,hand:Boolean):IntArray {
    val x=360.0;val r=24.0
    val p=IntArray(w*h){i->if(i%w<360)0xff88705a.toInt() else 0xff84909f.toInt()}
    if(hand)for(py in (y-r*1.9).toInt()..(y+r*.4).toInt())
      for(px in (x-r*1.3).toInt()..(x+r*1.3).toInt())p[py*w+px]=0xffb47b65.toInt()
    for(py in (y-r).toInt()..(y+r).toInt())for(px in (x-r).toInt()..(x+r).toInt())
      if(hypot(px-x,py-y)<=r)p[py*w+px]=0xffc7df43.toInt()
    return p
  }
  private fun run(heldFirst:Boolean):List<LocalResult> {
    val core=LocalAnalysis(LocalSettings(polygon=listOf(CourtPoint(.1,.1),CourtPoint(.9,.1),CourtPoint(.9,.95),CourtPoint(.1,.95)),floorLine=listOf(CourtPoint(0.0,.05),CourtPoint(1.0,.05))))
    var i=0
    if(heldFirst)repeat(5){core.process(frame(250.0,true),w,h,i++/60.0)}
    return listOf(300.0,350.0,400.0,450.0,500.0,550.0,500.0,450.0,400.0,350.0,300.0).map{y->core.process(frame(y,false),w,h,i++/60.0)}
  }
  @Test fun aFreeBallStillHasABounceOverTheSameFloor() {
    val free=run(false)
    assertTrue("Positive control must see the ball",free.count{it.observed!=null}>=9)
    assertEquals("Positive control must contain one contact",1,free.count{it.impact!=null})
  }
  @Test fun aReleasedBallClearsHandStateAndCanBounce() {
    val released=run(true)
    assertTrue("Release must not pass by losing the ball",released.count{it.observed!=null}>=9)
    assertTrue("The removed hand must not stay attached to a free ball",released.drop(3).none{it.observed?.nearHand==true})
    assertEquals("A real fixture bounce must remain after the hand is removed",1,released.count{it.impact!=null})
  }
}
