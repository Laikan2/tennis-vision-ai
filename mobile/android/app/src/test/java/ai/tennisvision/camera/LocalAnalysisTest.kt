package ai.tennisvision.camera

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class LocalAnalysisTest {
  private val polygon=listOf(CourtPoint(.2,.35),CourtPoint(.7,.35),CourtPoint(.7,.9),CourtPoint(.2,.9))
  private val settings=LocalSettings(polygon,listOf(CourtPoint(.05,.28),CourtPoint(.95,.28)))
  private fun image(x: Int,y: Int, radius: Int=6, hand: Boolean=false, light: Int=0): IntArray {
    val pixels=IntArray(320*240) { 0xff202532.toInt() }
    if (hand) for (py in max(0,y-18)..min(239,y+18)) for (px in max(0,x-18)..min(319,x+18)) pixels[py*320+px]=0xffbc8062.toInt()
    for (py in max(0,y-radius)..min(239,y+radius)) for (px in max(0,x-radius)..min(319,x+radius))
      if (hypot((px-x).toDouble(),(py-y).toDouble())<=radius) pixels[py*320+px]=0xffc7eb4d.toInt()
    if (light!=0) for (i in pixels.indices) { val c=pixels[i]; pixels[i]=0xff000000.toInt() or (((((c shr 16) and 255)+light).coerceIn(0,255)) shl 16) or (((((c shr 8) and 255)+light).coerceIn(0,255)) shl 8) or (((c and 255)+light).coerceIn(0,255)) }
    return pixels
  }
  private fun bounce(x: Int=140, fps: Int=30, cfg: LocalSettings=settings, hand: Boolean=false, lighting: Boolean=false): List<LocalImpact> {
    val core=LocalAnalysis(cfg)
    val ys=listOf(60,72,87,105,125,145,166,144,124,105,90,80,76,80,90,105,124,145,166,145,124,105)
    return ys.mapIndexedNotNull { index,y -> core.process(image(x,y,hand=hand,light=if (lighting && index%2==0) 35 else 0),320,240,10+index.toDouble()/fps).impact }
  }
  @Test fun observedFloorBounceProducesCorrectInAndOutFromPixels() {
    for (fps in listOf(30,60,120,240)) {
      val inside=bounce(fps=fps); val outside=bounce(x=275,fps=fps)
      assertTrue("No IN at $fps",inside.isNotEmpty()); assertTrue("No OUT at $fps",outside.isNotEmpty())
      assertTrue(inside.all { it.call==LineCall.IN }); assertTrue(outside.all { it.call==LineCall.OUT })
      assertEquals(140.0/320,inside.first().point.x,.001)
      assertEquals((166.0+6.5)/240,inside.first().point.y,.005)
    }
  }
  @Test fun twoBouncesAt30FpsCountTwiceWithoutDuplicates() { assertEquals(2,bounce().size) }
  @Test fun changingBrightnessDoesNotSuppressRealBall() { assertEquals(2,bounce(lighting=true).size) }
  @Test fun woodenFloorIsNotMisclassifiedAsAHand() {
    val core=LocalAnalysis(settings)
    val hits=listOf(60,72,87,105,125,145,166,144,124,105).mapIndexedNotNull { i,y ->
      val pixels=image(140,y)
      for (p in pixels.indices) if (pixels[p]==0xff202532.toInt()) pixels[p]=0xffbc9062.toInt()
      core.process(pixels,320,240,i/30.0).impact
    }
    assertEquals(1,hits.size); assertEquals(LineCall.IN,hits.first().call)
  }
  @Test fun ballSeparatesFromStaticGreenCourtInsteadOfMergingWithTheWholeFloor() {
    val core=LocalAnalysis(settings)
    var seen=0
    for (i in 0..15) {
      val pixels=image(40+i*15,120)
      for (p in pixels.indices) if (pixels[p]==0xff202532.toInt()) pixels[p]=0xff73943e.toInt()
      if (core.process(pixels,320,240,i/30.0).observed!=null) seen++
    }
    assertTrue("Ball should be acquired after the first background frame",seen>=14)
  }
  @Test fun sparseFramesRequireReviewEvenWhenTheFootprintIsOutside() {
    assertEquals(LineCall.REVIEW,bounce(x=275,fps=12).first().call)
  }
  @Test fun smallBallRemainsObservable() {
    val core=LocalAnalysis(settings)
    for (i in 0..15) assertNotNull(core.process(image(90+i*8,100,3),320,240,i/60.0).observed)
  }
  @Test fun heldBallCannotMakeAnOut() { assertTrue(bounce(x=275,hand=true).isEmpty()) }
  @Test fun trackingModeNeverScores() { assertTrue(bounce(cfg=settings.copy(surface=ContactSurface.TRACK)).isEmpty()) }
  @Test fun noFloorBoundaryRequiresReviewNotOut() { assertEquals(LineCall.REVIEW,bounce(x=275,cfg=settings.copy(floorLine=emptyList())).first().call) }
  @Test fun turnClearlyAboveMarkedFloorDoesNotPolluteContactCount() {
    assertTrue(bounce(x=275,cfg=settings.copy(floorLine=listOf(CourtPoint(0.0,.8),CourtPoint(1.0,.8)))).isEmpty())
  }
  @Test fun uncertainContactAtFloorBoundaryRemainsReviewable() {
    val contacts=bounce(x=275,cfg=settings.copy(floorLine=listOf(CourtPoint(0.0,.72),CourtPoint(1.0,.72))))
    assertEquals(2,contacts.size); assertTrue(contacts.all { it.call==LineCall.REVIEW })
  }
  @Test fun floorExclusionUsesSlopedBoundaryRegardlessOfClickOrder() {
    val line=listOf(CourtPoint(0.0,.6),CourtPoint(1.0,.9))
    for(points in listOf(line,line.reversed())) {
      assertTrue(bounce(x=275,cfg=settings.copy(floorLine=points)).isEmpty())
      // This is a different question from OUT: far beyond the IN polygon,
      // but below a known floor boundary, the same bounce is still counted OUT.
      assertTrue(bounce(x=275,cfg=settings.copy(floorLine=settings.floorLine)).all { it.call==LineCall.OUT })
    }
  }
  @Test fun noAreaMeansUnclassifiedContactNotOut() { assertEquals(LineCall.UNMARKED,bounce(cfg=settings.copy(polygon=emptyList())).first().call) }
  @Test fun ballInAirCrossingAreaIsNeverAnImpact() {
    val core=LocalAnalysis(settings)
    for (i in 0..25) assertNull(core.process(image(30+i*10,100+i*3),320,240,i/30.0).impact)
  }
  @Test fun flightApexIsNotAFloorBounce() {
    val core=LocalAnalysis(settings)
    listOf(180,160,140,120,110,105,110,120,140,160,180).forEachIndexed { i,y -> assertNull(core.process(image(275,y),320,240,i/30.0).impact) }
  }
  @Test fun stationaryGreenObjectNeverMakesAnImpact() {
    val core=LocalAnalysis(settings)
    repeat(60) { i -> assertNull(core.process(image(275,160),320,240,i/30.0).impact) }
  }
  @Test fun movingSquareLogoWithBounceShapedMotionIsNotABallImpact() {
    val core=LocalAnalysis(settings)
    listOf(60,72,87,105,125,145,166,144,124,105).forEachIndexed { i,y ->
      val pixels=IntArray(320*240) { 0xff202532.toInt() }
      for (py in y-6..y+6) for (px in 269..281) pixels[py*320+px]=0xffc7eb4d.toInt()
      assertNull("Square logo must not call OUT",core.process(pixels,320,240,i/30.0).impact)
    }
  }
  @Test fun elongatedBlurStillRemainsObservable() {
    val core=LocalAnalysis(settings)
    repeat(16) { i ->
      val x=35+i*16; val pixels=IntArray(320*240) { 0xff202532.toInt() }
      for (py in 94..106) for (px in x-14..x+14)
        if ((px-x).toDouble().pow(2)/196+(py-100).toDouble().pow(2)/36<=1) pixels[py*320+px]=0xffc7eb4d.toInt()
      assertNotNull(core.process(pixels,320,240,i/60.0).observed)
    }
  }
  @Test fun longOcclusionAndRepeatedTimestampsCannotCreateTurn() {
    val core=LocalAnalysis(settings)
    for (i in 0..3) core.process(image(275,100+15*i),320,240,i/30.0)
    assertNull(core.process(image(275,125),320,240,1.0).impact)
    assertNull(core.process(image(275,145),320,240,1.0).impact)
    assertNull(core.process(image(275,105),320,240,1.03).impact)
  }
  @Test fun predictedBallHasShortLifetimeAndNeverScores() {
    val core=LocalAnalysis(settings)
    for (i in 0..3) core.process(image(130+i*3,100+i*10),320,240,i/60.0)
    val result=core.process(IntArray(320*240) { 0xff202532.toInt() },320,240,4/60.0)
    assertNull(result.observed); assertNotNull(result.predicted); assertNull(result.impact)
    assertNull(core.process(IntArray(320*240) { 0xff202532.toInt() },320,240,.3).predicted)
  }
  @Test fun geometryRejectsCrossedDegenerateAndNanAreas() {
    assertTrue(LocalGeometry.valid(polygon))
    assertFalse(LocalGeometry.valid(listOf(polygon[0],polygon[2],polygon[1],polygon[3])))
    assertFalse(LocalGeometry.valid(List(4) { CourtPoint(.3,.3) }))
    assertFalse(LocalGeometry.valid(polygon.toMutableList().apply { set(0,CourtPoint(Double.NaN,.3)) }))
  }
  @Test fun nearLineIsReviewAndTouchingLineIsIn() {
    assertEquals(LineCall.REVIEW,LocalGeometry.classify(CourtPoint(.722,.6),6.0,2.0,polygon,320,240))
    assertEquals(LineCall.IN,LocalGeometry.classify(CourtPoint(.705,.6),6.0,2.0,polygon,320,240))
    assertEquals(LineCall.OUT,LocalGeometry.classify(CourtPoint(.8,.6),6.0,2.0,polygon,320,240))
  }
  @Test fun estimatedSpeedUsesSecondsNotArrivalInterval() {
    val slow=bounce(fps=30).first().speedKmh!!
    val fast=bounce(fps=60).first().speedKmh!!
    assertEquals(slow*2,fast,.01)
  }
}
