package ai.tennisvision.camera

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class WallContactTest {
  private val w=640;private val h=360
  private fun frame(x: Double?,y: Double=180.0): IntArray {
    val p=IntArray(w*h){0xff315372.toInt()}
    if(x!=null) for(py in 0 until h) for(px in 0 until w)
      if(hypot(px-x,py-y)<=6) p[py*w+px]=0xffc7eb4d.toInt()
    return p
  }
  private fun hits(vx: Double,vy: Double,ux: Double,uy: Double,cx: Double=350.0,cy: Double=220.0,surface: ContactSurface=ContactSurface.WALL): List<LocalImpact> {
    val s=LocalSettings(surface=surface,polygon=listOf(CourtPoint(.1,.1),CourtPoint(.9,.1),CourtPoint(.9,.95),CourtPoint(.1,.95)))
    val core=LocalAnalysis(s);val found=mutableListOf<LocalImpact>()
    for(i in 0..8) {
      val dt=i-5.4;val x=cx+dt*(if(dt<0)vx else ux);val y=cy+dt*(if(dt<0)vy else uy)
      core.process(frame(x,y),w,h,i/60.0).impact?.let(found::add)
    }
    return found
  }
  @Test fun verticalWallReturnIsLocatedAndRetainsReview() {
    val events=hits(0.0,24.0,0.0,-24.0)
    assertEquals(1,events.size);assertEquals(LineCall.REVIEW,events.single().call)
    assertEquals(220.0,events.single().point.y*h,2.0);assertNotNull(events.single().speedKmh)
  }
  @Test fun diagonalGlancingReturnIsLocatedBetweenObservedFrames() {
    val events=hits(30.0,18.0,-18.0,30.0,cy=220.0)
    assertEquals(1,events.size)
    assertEquals(350.0,events.single().point.x*w,2.0);assertEquals(220.0,events.single().point.y*h,2.0)
  }
  @Test fun horizontalReturnIsInterpolatedRatherThanMarkedBeforeTheWall() {
    val events=hits(35.0,0.0,-35.0,0.0)
    assertEquals(1,events.size);assertEquals(350.0,events.single().point.x*w,2.0)
  }
  @Test fun crossingTheWallAreaWithoutATurnCannotScore() {
    assertTrue(hits(30.0,8.0,30.0,8.0).isEmpty())
  }
  @Test fun anObliqueWallTurnCannotBeCountedAsAFloorBounce() {
    assertTrue(hits(30.0,18.0,-18.0,30.0,surface=ContactSurface.FLOOR).isEmpty())
  }
  @Test fun disappearanceAtTheWallWithoutObservedReturnCannotScore() {
    val core=LocalAnalysis(LocalSettings(surface=ContactSurface.WALL))
    for(i in 0..9) assertNull(core.process(frame(if(i<6)100.0+i*35 else null),w,h,i/60.0).impact)
  }
  @Test fun carryingBallInHandCannotCreateAWallMark() {
    val core=LocalAnalysis(LocalSettings(surface=ContactSurface.WALL))
    for((i,y) in listOf(70,95,120,145,170,195,170,145,120,95).withIndex()) {
      val p=IntArray(w*h){0xff315372.toInt()}
      for(py in y-16..y+16) for(px in 304..336) p[py*w+px]=0xffbd8466.toInt()
      for(py in y-6..y+6) for(px in 314..326) if(hypot(px-320.0,py-y.toDouble())<=6)p[py*w+px]=0xffc7eb4d.toInt()
      assertNull(core.process(p,w,h,i/60.0).impact)
    }
  }
  @Test fun incoherentTracksDoNotCreateAnInterpolatedWallPosition() {
    val core=LocalAnalysis(LocalSettings(surface=ContactSurface.WALL))
    val positions=listOf(100.0 to 100.0,120.0 to 100.0,180.0 to 180.0,120.0 to 140.0,100.0 to 140.0)
    for((i,p) in positions.withIndex()) assertNull(core.process(frame(p.first,p.second),w,h,i/60.0).impact)
  }
}
