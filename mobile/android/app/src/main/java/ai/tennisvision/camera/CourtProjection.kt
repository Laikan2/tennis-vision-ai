package ai.tennisvision.camera

import kotlin.math.abs

/** Projective guide, not a 3-D ball position or a speed calibration. */
object CourtProjection {
  fun point(corners: List<CourtPoint>,u: Double,v: Double): CourtPoint? {
    if(!LocalGeometry.projectedPolygon(corners)) return null
    val (p0,p1,p2,p3)=corners
    val dx1=p1.x-p2.x;val dx2=p3.x-p2.x
    val dy1=p1.y-p2.y;val dy2=p3.y-p2.y
    val dx3=p0.x-p1.x+p2.x-p3.x;val dy3=p0.y-p1.y+p2.y-p3.y
    val det=dx1*dy2-dx2*dy1
    if(abs(det)<1e-10) return null
    val g=(dx3*dy2-dx2*dy3)/det;val h=(dx1*dy3-dx3*dy1)/det
    val divisor=g*u+h*v+1
    if(abs(divisor)<1e-10) return null
    val x=((p1.x-p0.x+g*p1.x)*u+(p3.x-p0.x+h*p3.x)*v+p0.x)/divisor
    val y=((p1.y-p0.y+g*p1.y)*u+(p3.y-p0.y+h*p3.y)*v+p0.y)/divisor
    return CourtPoint(x,y).takeIf { it.x.isFinite() && it.y.isFinite() }
  }
  fun guides(corners: List<CourtPoint>,layout: CourtLayout): List<List<CourtPoint>> {
    if(layout==CourtLayout.CUSTOM) return emptyList()
    val singles=(10.97-8.23)/(2*10.97)
    val service=if(layout==CourtLayout.FULL) (11.885-6.4)/23.77 else 6.4/11.885
    val normalized=mutableListOf(
      listOf(CourtPoint(singles,0.0),CourtPoint(singles,1.0)),
      listOf(CourtPoint(1-singles,0.0),CourtPoint(1-singles,1.0)))
    if(layout==CourtLayout.FULL) {
      normalized+=listOf(CourtPoint(0.0,.5),CourtPoint(1.0,.5))
      normalized+=listOf(CourtPoint(singles,service),CourtPoint(1-singles,service))
      normalized+=listOf(CourtPoint(singles,1-service),CourtPoint(1-singles,1-service))
      normalized+=listOf(CourtPoint(.5,service),CourtPoint(.5,1-service))
    } else {
      // First edge is the net; last edge is the baseline.
      normalized+=listOf(CourtPoint(singles,service),CourtPoint(1-singles,service))
      normalized+=listOf(CourtPoint(.5,0.0),CourtPoint(.5,service))
    }
    return normalized.mapNotNull { line -> line.mapNotNull { point(corners,it.x,it.y) }.takeIf { it.size==2 } }
  }
}
