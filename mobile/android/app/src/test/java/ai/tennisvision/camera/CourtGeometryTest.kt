package ai.tennisvision.camera

import org.junit.Assert.*
import org.junit.Test

class CourtGeometryTest {
  private val trapezoid=listOf(CourtPoint(.30,.15),CourtPoint(.70,.15),CourtPoint(.94,.92),CourtPoint(.06,.92))
  @Test fun projectiveGuidePassesThroughAllFourCorners() {
    val unit=listOf(CourtPoint(0.0,0.0),CourtPoint(1.0,0.0),CourtPoint(1.0,1.0),CourtPoint(0.0,1.0))
    unit.zip(trapezoid).forEach { (a,b) -> val p=CourtProjection.point(trapezoid,a.x,a.y)!!;assertEquals(b.x,p.x,1e-9);assertEquals(b.y,p.y,1e-9) }
  }
  @Test fun netAndServiceGuidesUsePerspectiveAndBothCourtHalves() {
    val lines=CourtProjection.guides(trapezoid,CourtLayout.FULL)
    assertEquals(6,lines.size)
    val net=lines[2]
    assertEquals(net[0].y,net[1].y,1e-9)
    assertTrue("Perspective net is not the image midpoint",net[0].y<(.15+.92)/2)
    assertTrue(lines[3][0].y<net[0].y)
    assertTrue(lines[4][0].y>net[0].y)
  }
  @Test fun invalidAreaCannotProduceGuide() {
    assertTrue(CourtProjection.guides(listOf(trapezoid[0],trapezoid[2],trapezoid[1],trapezoid[3]),CourtLayout.FULL).isEmpty())
    assertNull(CourtProjection.point(emptyList(),.5,.5))
  }
  @Test fun liveAndEvidenceMarksUseTheirOwnRegisteredFrame() {
    val event=LocalImpact(1,1.0,CourtPoint(.4,.8),5.0,LineCall.IN,null,2.0,"Test",ContactSurface.FLOOR,
      cameraOffset=CourtPoint(.01,.02),evidenceOffset=CourtPoint(.005,.01))
    val live=LocalGeometry.impactPoint(event,CourtPoint(.02,.03))
    assertEquals(.41,live.x,1e-9);assertEquals(.81,live.y,1e-9)
    val evidence=LocalGeometry.impactPoint(event,event.evidenceOffset)
    assertEquals(.395,evidence.x,1e-9);assertEquals(.79,evidence.y,1e-9)
  }
}
