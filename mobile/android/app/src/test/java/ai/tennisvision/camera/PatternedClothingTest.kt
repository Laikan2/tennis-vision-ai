package ai.tennisvision.camera

import kotlin.math.hypot
import org.junit.Assert.*
import org.junit.Test

/** Controlled pixel regressions; these are not a claim of real-court accuracy. */
class PatternedClothingTest {
  private val w=320;private val h=240
  private fun disk(p:IntArray,x:Double,y:Double,r:Double,color:Int) {
    for(py in 0 until h)for(px in 0 until w)if(hypot(px-x,py-y)<=r)p[py*w+px]=color
  }
  private fun scene(y:Double,ball:Boolean=false):IntArray {
    val p=IntArray(w*h){0xffa89988.toInt()}
    for(py in 50..205)for(px in 20..90)p[py*w+px]=0xff242631.toInt()
    for((dx,dy) in listOf(0 to 0,-13 to -9,14 to -7,-11 to 13,13 to 13))
      disk(p,55.0+dx,y+dy,if(dx==0)4.0 else 1.5,0xffb5cf39.toInt())
    if(ball)disk(p,200.0,100.0,5.0,0xff847c28.toInt())
    return p
  }
  @Test fun dimOliveBallIsFoundWithoutSelectingBrighterClothing() {
    val r=LocalAnalysis().process(scene(130.0,true),w,h,0.0)
    assertNotNull("Local chroma contrast must retain the real dim ball",r.observed)
    assertEquals(200.0,r.observed!!.x,2.0)
  }
  @Test fun movingGarmentFragmentsNeverBecomeImpactEvidence() {
    for(surface in listOf(ContactSurface.FLOOR,ContactSurface.WALL)) {
      val core=LocalAnalysis(LocalSettings(surface=surface))
      for((i,y) in listOf(100,108,118,130,144,130,118,108,100).withIndex()) {
        val r=core.process(scene(y.toDouble()),w,h,i/60.0)
        assertNull("Patterned clothing is not a ball, even when it reverses direction",r.observed)
        assertNull(r.impact)
      }
    }
  }
  @Test fun mutedOliveFeltRequiresContrastAgainstItsSurroundings() {
    for(color in listOf(0xff897a4d.toInt(),0xff726840.toInt(),0xff9b8c59.toInt())) {
      val neutral=IntArray(w*h){0xff9a9691.toInt()};disk(neutral,160.0,100.0,6.0,color)
      val b=LocalAnalysis().process(neutral,w,h,0.0).observed
      assertNotNull("Muted felt must not require saturated colour when its boundary is resolved",b)
      assertEquals(160.0,b!!.x,2.0)
      val uniform=IntArray(w*h){color}
      assertNull("A similarly coloured background is not a ball",LocalAnalysis().process(uniform,w,h,0.0).observed)
    }
  }
  @Test fun realBallReacquiresAfterSeparatingFromClutter() {
    val core=LocalAnalysis();var seen=0
    for(i in 0..10) {
      val p=scene(130.0);val x=95.0+i*10;disk(p,x,100.0,5.0,0xff847c28.toInt())
      val r=core.process(p,w,h,i/60.0)
      if(r.observed!=null){assertEquals(x,r.observed!!.x,2.0);seen++}
    }
    assertTrue("Do not pass the negative test by discarding every ball",seen>=8)
  }
  @Test fun fingerSplitMutedBallIsVisibleButCannotScore() {
    val core=LocalAnalysis();var seen=0
    for((i,y) in listOf(90,100,112,126,142,160,142,126,112).withIndex()) {
      val p=IntArray(w*h){0xff9a9691.toInt()};disk(p,160.0,y.toDouble(),10.0,0xff897a4d.toInt())
      for(py in y-12..y+12)for(px in 159..161)p[py*w+px]=0xffbc8b7b.toInt()
      val r=core.process(p,w,h,i/60.0)
      r.observed?.let { seen++;assertTrue(it.occluded);assertTrue(it.nearHand) }
      assertNull(r.impact);assertNull(r.speedKmh)
    }
    assertTrue("A carried muted ball must remain visible without a false contact",seen>=8)
  }
}
