package ai.tennisvision.camera

import kotlin.math.*
import org.junit.Assert.*
import org.junit.Test

/** Known projected paths, not a claim of measured phone/match accuracy. */
class ContactSurfaceRecoveryTest {
  private val w=320;private val h=240
  private val region=listOf(CourtPoint(.08,.08),CourtPoint(.92,.08),CourtPoint(.92,.95),CourtPoint(.08,.95))
  private fun frame(x:Double,y:Double,r:Double=12.0,hand:Boolean=false):IntArray {
    val p=IntArray(w*h){0xff8b8884.toInt()}
    if(hand) for(py in 0 until h)for(px in 0 until w) if(abs(px-x)<r*1.3 && py>y-r*1.9 && py<y+r*.4)p[py*w+px]=0xffb47b65.toInt()
    for(py in 0 until h)for(px in 0 until w)if(hypot(px-x,py-y)<=r)p[py*w+px]=0xffc7df43.toInt()
    return p
  }
  private fun wall(fps:Int,hand:Boolean=false,crossing:Boolean=false,slow:Boolean=false):List<LocalImpact> {
    val core=LocalAnalysis(LocalSettings(surface=ContactSurface.WALL,polygon=region));val hits=mutableListOf<LocalImpact>()
    for(i in 0..(fps*.4).toInt()) {
      val t=i.toDouble()/fps;val dt=t-.2
      val x=160+if(dt<=0 || crossing)dt*(if(slow)75 else 150) else -dt*(if(slow)65 else 115)
      core.process(frame(x,110.0,if(slow)6.0 else 12.0,hand=hand),w,h,t).impact?.let(hits::add)
    }
    return hits
  }
  @Test fun wallReturnUsesObservedTimeAndDisplacementAtEveryFrameRate() {
    for(fps in listOf(30,60,120,240)) {
      val hits=wall(fps)
      assertEquals("$fps FPS must mark the observed wall return once",1,hits.size)
      assertEquals(LineCall.REVIEW,hits.single().call)
      assertEquals(160.0,hits.single().point.x*w,2.0);assertEquals(110.0,hits.single().point.y*h,1.0)
      assertEquals(.2,hits.single().time,1.0/fps);assertNotNull(hits.single().speedKmh)
    }
  }
  @Test fun handHeldTurnAndStraightPassDoNotCreateWallContacts() {
    for(fps in listOf(60,240)){assertTrue(wall(fps,hand=true).isEmpty());assertTrue(wall(fps,crossing=true).isEmpty())}
  }
  @Test fun slowCleanWallReturnIsNotLostToAnArbitraryPixelSpeedCutoff() {
    for(fps in listOf(30,60,120,240)) {
      val hits=wall(fps,slow=true);assertEquals("$fps FPS slow return",1,hits.size)
      assertEquals(LineCall.REVIEW,hits.single().call);assertNotNull(hits.single().speedKmh)
    }
  }
  @Test fun wholeImageFloorRestoresARealBounceExcludedByAMisplacedDiagonalBoundary() {
    fun run(line:List<CourtPoint>,court:CourtLayout=CourtLayout.CUSTOM):List<LocalImpact> {
      val core=LocalAnalysis(LocalSettings(polygon=region,floorLine=line,court=court));val hits=mutableListOf<LocalImpact>()
      for(i in 0..24){val t=i/60.0;core.process(frame(170.0,110-abs(t-.2)*350,6.0),w,h,t).impact?.let(hits::add)}
      return hits
    }
    assertTrue("Wrong boundary really excludes this point",run(listOf(CourtPoint(.05,.05),CourtPoint(.85,.95))).isEmpty())
    for(layout in CourtLayout.entries) {
      val hits=run(if(layout==CourtLayout.CUSTOM)wholeImageFloorLine() else emptyList(),layout)
      assertEquals("$layout must produce its floor mark",1,hits.size);assertEquals(LineCall.IN,hits.single().call)
      assertEquals(116.5,hits.single().point.y*h,2.0);assertNotNull(hits.single().speedKmh)
    }
  }
  @Test fun floorShadeExactlyMatchesTheExcludedSideAndWholeFloorHasNoShade() {
    assertTrue(LocalGeometry.excludedFloorRegion(wholeImageFloorLine()).isEmpty())
    val line=listOf(CourtPoint(.05,.05),CourtPoint(.85,.95))
    val shade=LocalGeometry.excludedFloorRegion(line)
    assertTrue(shade.size>=3)
    assertTrue(shade.all{it.x in 0.0..1.0 && it.y in 0.0..1.0})
    assertTrue(shade.all{LocalGeometry.floorDistancePx(it,line,w,h)!!<=.00001})
    assertTrue(LocalGeometry.excludedFloorRegion(listOf(CourtPoint(.5,.1),CourtPoint(.5,.9))).isEmpty())
  }
  @Test fun compensatedFloorBoundaryRemainsGeometricallyUsableOutsideTheViewport() {
    val line=listOf(CourtPoint(0.0,0.0),CourtPoint(1.0,0.0))
    val area=LocalSettings(polygon=region,floorLine=line).translated(CourtPoint(.01,-.01))
    assertFalse("Editor still requires points inside the image",LocalGeometry.validFloorLine(area.floorLine))
    assertTrue("A compensated line is still the same infinite floor boundary",LocalGeometry.onFloor(CourtPoint(.5,.6),area.floorLine))
    assertNotNull(LocalGeometry.floorDistancePx(CourtPoint(.5,.6),area.floorLine,w,h))
    val full=LocalSettings(polygon=listOf(CourtPoint(0.0,0.0),CourtPoint(1.0,0.0),CourtPoint(1.0,1.0),CourtPoint(0.0,1.0))).translated(CourtPoint(.01,-.01))
    assertFalse(LocalGeometry.valid(full.polygon));assertTrue(LocalGeometry.projectedPolygon(full.polygon))
    assertEquals(LineCall.IN,LocalGeometry.classify(CourtPoint(.5,.6),6.0,2.0,full.polygon,w,h))
  }
}
