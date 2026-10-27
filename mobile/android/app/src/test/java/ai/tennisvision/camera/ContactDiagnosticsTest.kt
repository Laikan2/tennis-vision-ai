package ai.tennisvision.camera

import kotlin.math.hypot
import org.junit.Assert.*
import org.junit.Test

class ContactDiagnosticsTest {
  private val w=640;private val h=360
  private val region=listOf(CourtPoint(.1,.1),CourtPoint(.9,.1),CourtPoint(.9,.95),CourtPoint(.1,.95))
  private fun frame(x:Double,y:Double,r:Double=6.0)=IntArray(w*h){i->
    if(hypot(i%w-x,i/w-y)<=r)0xffc7eb4d.toInt() else 0xff315372.toInt() }
  @Test fun knownFloorBounceCannotBeReportedAsAWallContact() {
    for(surface in listOf(ContactSurface.FLOOR,ContactSurface.WALL)) {
      val core=LocalAnalysis(LocalSettings(surface=surface,polygon=region,floorLine=listOf(CourtPoint(0.0,.4),CourtPoint(1.0,.4))))
      val hits=mutableListOf<LocalImpact>();var rejected=false
      listOf(100,125,150,175,200,175,150,125,100).forEachIndexed{i,y->
        val r=core.process(frame(300.0,y.toDouble()),w,h,i/60.0)
        r.impact?.let(hits::add);if(r.diagnostics.contactReason.contains("modo parede"))rejected=true
      }
      if(surface==ContactSurface.FLOOR){assertEquals(1,hits.size);assertEquals(ContactEvidence.CONFIRMED_FLOOR,hits.single().evidence)}
      else{assertTrue(hits.isEmpty());assertTrue(rejected)}
    }
  }
  @Test fun fastObservedBallKeepsIdentityAndMakesOnlyOneWallCandidate() {
    // A prior whole-image floor preset must not suppress later wall returns.
    val core=LocalAnalysis(LocalSettings(surface=ContactSurface.WALL,polygon=region,floorLine=wholeImageFloorLine()))
    val ids=mutableSetOf<Long?>();val hits=mutableListOf<LocalImpact>();var observed=0
    listOf(100,220,340,460,580,460,340,220,100).forEachIndexed{i,x->
      val r=core.process(frame(x.toDouble(),180.0,4.0),w,h,i/60.0)
      if(r.observed!=null){observed++;ids.add(r.diagnostics.trackId)}
      r.impact?.let(hits::add)
    }
    assertEquals(9,observed);assertEquals(1,ids.size);assertEquals(1,hits.size)
    assertEquals(ContactEvidence.CANDIDATE,hits.single().evidence)
    assertEquals(580.0,hits.single().point.x*w,2.0)
    assertTrue(hits.single().speedKmh!! in 180.0..220.0)
  }
  @Test fun predictionExpiresAndReacquisitionUsesANewId() {
    val core=LocalAnalysis(LocalSettings(surface=ContactSurface.TRACK))
    val first=core.process(frame(100.0,150.0),w,h,0.0).diagnostics.trackId
    core.process(frame(108.0,150.0),w,h,.016)
    val empty=IntArray(w*h){0xff315372.toInt()}
    assertNotNull(core.process(empty,w,h,.032).predicted)
    val gone=core.process(empty,w,h,.20);assertNull(gone.predicted);assertNull(gone.impact)
    val next=core.process(frame(108.0,150.0),w,h,.23)
    assertNotNull(next.observed);assertNotEquals(first,next.diagnostics.trackId)
  }
  @Test fun malformedFramesAreRejectedWithoutBreakingNextGoodFrame() {
    val core=LocalAnalysis()
    assertNull(core.process(IntArray(0),w,h,0.0).observed)
    assertNull(core.process(IntArray(0),-1,Int.MAX_VALUE,0.0).observed)
    assertNotNull(core.process(frame(100.0,150.0),w,h,1.0).observed)
  }
}
