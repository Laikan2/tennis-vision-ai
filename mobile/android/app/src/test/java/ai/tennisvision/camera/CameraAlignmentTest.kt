package ai.tennisvision.camera

import org.junit.Assert.*
import org.junit.Test

class CameraAlignmentTest {
  private val w=320;private val h=240
  private fun texture(): IntArray=IntArray(w*h) { i ->
    val x=i%w;val y=i/w
    val v=40+((x*17+y*31+(x/11)*(y/7)*13)%155)
    0xff000000.toInt() or (v shl 16) or (v shl 8) or v
  }
  private fun move(p: IntArray,dx: Int,dy: Int)=IntArray(p.size) { i ->
    val x=i%w-dx;val y=i/w-dy
    if(x in 0 until w && y in 0 until h) p[y*w+x] else 0xff303030.toInt()
  }
  @Test fun smallJitterIsRegisteredOnDistributedBackground() {
    val original=texture()
    for((dx,dy) in listOf(1 to 2,-3 to 1,4 to -2)) {
      val a=CameraAlignment.estimate(move(original,dx,dy),original,w,h)
      assertTrue("$dx,$dy should be reliable: $a",a.reliable)
      assertEquals(dx,a.dx);assertEquals(dy,a.dy)
    }
  }
  @Test fun flatFloorCannotPretendToProveAlignment() {
    val flat=IntArray(w*h) { 0xff808080.toInt() }
    assertFalse(CameraAlignment.estimate(flat,flat,w,h).reliable)
  }
  @Test fun movingForegroundDoesNotDragTheCourt() {
    val p=texture();val next=p.copyOf()
    for(y in 70..180) for(x in 100..220) next[y*w+x]=0xffbc8062.toInt()
    val a=CameraAlignment.estimate(next,p,w,h)
    assertTrue(a.reliable);assertEquals(0,a.dx);assertEquals(0,a.dy)
  }
  @Test fun largeMoveIsNotSilentlyAppliedAsCalibration() {
    val p=texture();val a=CameraAlignment.estimate(move(p,8,0),p,w,h)
    assertTrue(a.large);assertFalse(a.reliable)
  }
  @Test fun compensatedAreaFollowsJitterWithoutChangingSavedCoordinates() {
    val area=listOf(CourtPoint(.2,.2),CourtPoint(.8,.2),CourtPoint(.8,.9),CourtPoint(.2,.9))
    val settings=LocalSettings(polygon=area)
    val core=LocalAnalysis(settings);val p=texture()
    core.process(p,w,h,0.0)
    val result=core.process(move(p,2,-1),w,h,.02)
    assertEquals(.2+2.0/w,result.activeArea!!.polygon.first().x,1e-9)
    assertEquals(.2-1.0/h,result.activeArea!!.polygon.first().y,1e-9)
    assertEquals(area,core.settings.polygon)
  }
  @Test fun aPlayerCrossingAFlatBackgroundDoesNotPauseTheWholeCamera() {
    val p=IntArray(w*h) { 0xff315372.toInt() };val next=p.copyOf()
    for(y in 40..220) for(x in 90..245) next[y*w+x]=0xffbc8062.toInt()
    val core=LocalAnalysis();core.process(p,w,h,0.0)
    assertFalse(core.process(next,w,h,.02).movingCamera)
  }
}
