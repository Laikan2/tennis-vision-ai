package ai.tennisvision.camera

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

/** Known pixels inspired by the reported warm-lit room, not a real-match benchmark. */
class IndoorRegressionTest {
  private val w=320; private val h=240
  private fun frame(x: Int=160,y: Int=120,r: Int=12,ball: Boolean=true): IntArray {
    val p=IntArray(w*h){0xff9a9590.toInt()}
    if(ball) for(py in max(0,y-r)..min(h-1,y+r))for(px in max(0,x-r)..min(w-1,x+r)) {
      if(hypot((px-x).toDouble(),(py-y).toDouble())<=r) {
        val shade=((px-x+r).toDouble()/(2*r)).coerceIn(0.0,1.0)
        val red=(134+82*shade).toInt();val green=(98+81*shade).toInt();val blue=(4+25*shade).toInt()
        p[py*w+px]=0xff000000.toInt() or (red shl 16) or (green shl 8) or blue
      }
    }
    return p
  }
  @Test fun warmYellowFeltRemainsCenteredWhileMoving() {
    val core=LocalAnalysis();var located=0
    repeat(20){i->val x=50+i*10;val b=core.process(frame(x),w,h,i/60.0).observed
      if(b!=null && hypot(b.x-x,b.y-120)<3) located++ }
    assertTrue("warm felt located $located/20",located>=19)
  }
  @Test fun closeBallIsVisibleBeforeThrowing() {
    val b=LocalAnalysis().process(frame(r=42),w,h,0.0).observed
    assertNotNull("A round ball near the lens should not exceed the old component limit",b)
    assertEquals(160.0,b!!.x,3.0);assertEquals(120.0,b.y,3.0)
  }
  @Test fun tinyWallFragmentsDoNotVetoAResolvedWarmBall() {
    val p=frame(r=42)
    for(y in 35..39)for(x in 35..39)if(hypot(x-37.0,y-37.0)<=2)p[y*w+x]=0xffc7eb4d.toInt()
    val b=LocalAnalysis().process(p,w,h,0.0).observed
    assertNotNull(b);assertEquals(160.0,b!!.x,3.0)
  }
  @Test fun warmBallStillProducesKnownFloorDecisions() {
    val area=listOf(CourtPoint(.2,.3),CourtPoint(.7,.3),CourtPoint(.7,.95),CourtPoint(.2,.95))
    for((x,call) in listOf(140 to LineCall.IN,285 to LineCall.OUT)) {
      val core=LocalAnalysis(LocalSettings(polygon=area,floorLine=listOf(CourtPoint(0.0,.3),CourtPoint(1.0,.3))))
      val hits=listOf(70,85,105,130,160,195,160,130,105,85).mapIndexedNotNull{i,y->core.process(frame(x,y,6),w,h,i/30.0).impact}
      assertEquals(1,hits.size);assertEquals(call,hits.single().call)
    }
  }
  @Test fun skinWoodAndWarmRectangleDoNotBecomeBall() {
    for(color in listOf(0xffbc8062.toInt(),0xffbc9062.toInt(),0xff3b2921.toInt())) {
      val core=LocalAnalysis()
      repeat(12){i->val p=frame(ball=false);for(y in 75..100)for(x in 30+i*12..55+i*12)p[y*w+x]=color
        assertNull(core.process(p,w,h,i/30.0).observed)}
    }
    val core=LocalAnalysis()
    repeat(12){i->val p=frame(ball=false);for(y in 75..100)for(x in 30+i*12..55+i*12)p[y*w+x]=0xffb8920a.toInt()
      assertNull(core.process(p,w,h,i/30.0).impact)}
  }
  @Test fun disconnectedTinyFragmentsDoNotAccumulateReviewContacts() {
    val core=LocalAnalysis(LocalSettings(court=CourtLayout.FULL,polygon=listOf(CourtPoint(.1,.1),CourtPoint(.9,.1),CourtPoint(.9,.95),CourtPoint(.1,.95))))
    val ys=listOf(70,85,105,130,160,195,160,130,105,85)
    val xs=listOf(95,100,105,110,115,120,180,240,245,250)
    for(i in ys.indices){val p=frame(ball=false)
      for(y in ys[i]..ys[i]+1)for(x in xs[i]..xs[i]+1)p[y*w+x]=0xffc7eb4d.toInt()
      assertNull(core.process(p,w,h,i/60.0).impact)
    }
  }
}
