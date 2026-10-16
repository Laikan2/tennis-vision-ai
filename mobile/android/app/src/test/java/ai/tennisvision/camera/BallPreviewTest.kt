package ai.tennisvision.camera

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class BallPreviewTest {
  private fun pixels(x: Double?,y: Double=180.0,r: Double=12.0,w: Int=1280,h: Int=720,rx: Double=r):IntArray {
    val p=IntArray(w*h){0xff315372.toInt()}
    if(x!=null) for(py in max(0,(y-r).toInt())..min(h-1,(y+r).toInt()))
      for(px in max(0,(x-rx).toInt())..min(w-1,(x+rx).toInt()))
        if(((px-x)/rx).pow(2)+((py-y)/r).pow(2)<=1) p[py*w+px]=0xffc7eb4d.toInt()
    return p
  }
  @Test fun previewFindsVisibleBallWithoutStartingScoringAndUsesFullImageCoordinates() {
    val r=BallPreview().process(pixels(500.0,300.0),1280,720,0.0,LocalSettings())
    assertNotNull(r.observed);assertEquals(500.0,r.observed!!.x,2.0);assertEquals(300.0,r.observed.y,2.0)
    assertEquals(25.0,r.observed.diameterX,3.0);assertNull(r.impact);assertNull(r.speedKmh)
  }
  @Test fun previewNeverCountsEvenACompleteKnownBounce() {
    val preview=BallPreview()
    for((i,y) in listOf(90,115,150,190,240,290,240,190,150,115).withIndex()) {
      val result=preview.process(pixels(500.0,y.toDouble()),1280,720,i/60.0,LocalSettings())
      assertNotNull(result.observed);assertNull(result.impact);assertNull(result.speedKmh)
    }
  }
  @Test fun rescalingDoesNotTurnAPoorlyResolvedSignalIntoAResolvedBall() {
    val preview=BallPreview()
    preview.process(pixels(100.0,r=4.0),1280,720,0.0,LocalSettings())
    val b=preview.process(pixels(112.0,r=4.0),1280,720,1.0/60,LocalSettings()).observed
    assertNotNull(b);assertFalse(b!!.resolved);assertTrue(b.colorUncertain)
  }
  @Test fun missingBallDoesNotKeepAnObservedOutlineAtTheOldPosition() {
    val preview=BallPreview()
    assertNotNull(preview.process(pixels(500.0),1280,720,0.0,LocalSettings()).observed)
    val result=preview.process(pixels(null),1280,720,1.0/60,LocalSettings())
    assertNull(result.observed);assertNull(result.impact)
  }
  @Test fun elongatedObservedBlurCarriesBothContourDimensions() {
    val core=LocalAnalysis(LocalSettings(surface=ContactSurface.TRACK))
    val b=core.process(pixels(300.0,180.0,6.0,640,360,14.0),640,360,0.0).observed
    assertNotNull(b);assertTrue(b!!.blurred);assertTrue(b.diameterX>b.diameterY*1.8)
  }
}
