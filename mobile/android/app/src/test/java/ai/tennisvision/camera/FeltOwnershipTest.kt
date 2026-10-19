package ai.tennisvision.camera

import kotlin.math.hypot
import org.junit.Assert.*
import org.junit.Test

/** Known pixels reproducing fragments selected inside a clipped ball or a grip. */
class FeltOwnershipTest {
  private val w=320;private val h=240
  private fun disk(p:IntArray,x:Double,y:Double,r:Double,color:Int) {
    for(py in 0 until h)for(px in 0 until w)if(hypot(px-x,py-y)<=r)p[py*w+px]=color
  }
  private fun image(clipped:Boolean=false,smallOutside:Boolean=false):IntArray {
    val p=IntArray(w*h){0xff8b8884.toInt()}
    if(clipped) {
      disk(p,320.0,110.0,55.0,0xffc7df43.toInt())
      disk(p,290.0,110.0,8.0,0xff303030.toInt())
      disk(p,290.0,110.0,3.0,0xffc7df43.toInt())
      // A disconnected compression fringe can lie just beyond the parent's box.
      disk(p,290.0,169.0,3.0,0xffc7df43.toInt())
    }
    if(smallOutside)disk(p,90.0,150.0,3.0,0xffc7df43.toInt())
    return p
  }
  @Test fun aClippedLargeBallCannotBecomeAnInnerTinyBall() {
    val core=LocalAnalysis()
    repeat(10){i->val r=core.process(image(clipped=true),w,h,i/60.0)
      assertNull("A felt island inside an incomplete silhouette is not a separate ball",r.observed)
      assertNull(r.impact)
    }
  }
  @Test fun aSeparateSmallBallOutsideTheClippedSilhouetteIsStillVisible() {
    val r=LocalAnalysis().process(image(clipped=true,smallOutside=true),w,h,0.0)
    assertNotNull(r.observed);assertEquals(90.0,r.observed!!.x,1.0);assertEquals(150.0,r.observed!!.y,1.0)
  }
  @Test fun aSmallFeltPatchSurroundedByCurrentGripCannotScore() {
    val core=LocalAnalysis();var seen=0
    listOf(90.0,105.0,120.0,140.0,160.0,140.0,120.0,105.0,90.0).forEachIndexed { i,y->
      val p=image();for(py in 65..185)for(px in 120..180)p[py*w+px]=0xffbc8b7b.toInt()
      disk(p,150.0,y,3.0,0xffc7df43.toInt())
      val r=core.process(p,w,h,i/60.0)
      r.observed?.let { seen++;assertTrue("Use the present grip even for a tiny patch",it.nearHand) }
      assertNull("A carried patch is not a floor bounce",r.impact)
    };assertTrue("Do not pass by losing the visible patch",seen>=8)
  }
  @Test fun aSmallFreeBallOverUniformPinkWoodDoesNotInheritAHand() {
    val core=LocalAnalysis();var seen=0
    listOf(90.0,100.0,112.0,126.0,142.0,160.0,142.0,126.0,112.0).forEachIndexed { i,y->
      val p=IntArray(w*h){0xff987e73.toInt()};disk(p,150.0,y,3.0,0xffc7df43.toInt())
      val r=core.process(p,w,h,i/60.0)
      r.observed?.let{seen++;assertFalse("Uniform warm background alone is not a bounded hand",it.nearHand)}
    };assertTrue("Do not pass by hiding the small ball",seen>=8)
  }
  @Test fun theWholeVisibleBallImmediatelyReplacesItsOldTinyFragment() {
    val core=LocalAnalysis()
    repeat(4){i->val p=image();disk(p,150.0,130.0,3.0,0xffc7df43.toInt());core.process(p,w,h,i/60.0)}
    val p=image();for(py in 65..115)for(px in 105..195)p[py*w+px]=0xffbc8b7b.toInt()
    disk(p,150.0,130.0,36.0,0xffc7df43.toInt())
    val r=core.process(p,w,h,4/60.0)
    assertNotNull("A tiny prior cannot lock out its resolved owner",r.observed)
    assertTrue(r.observed!!.radius>=30);assertTrue(r.observed!!.nearHand);assertNull(r.impact)
  }
}
