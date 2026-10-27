package ai.tennisvision.camera

import kotlin.math.*
import org.junit.Assert.*
import org.junit.Test

class ObservedContactFitTest {
  private fun samples(fps:Int=60,hand:Boolean=false,occluded:Boolean=false,curve:(Double)->Pair<Double,Double>):List<BallObservation> =
    (0..(fps*.31).toInt()).map{i->val t=i.toDouble()/fps;val p=curve(t)
      BallObservation(p.first,p.second,8.0,t,.9,nearHand=hand,occluded=occluded,greenDominant=true)}
  @Test fun asymmetricNoisyObservedFloorReturnIsLocalizedAtEveryRate() {
    for(fps in listOf(30,60,120,240)) {
      val points=samples(fps){t->val d=t-.17;240+240*t to 300+(if(d<0)700*d else -200*d)+400*d*d+sin(t*fps*2.4)*3}
      val f=ObservedContactFit.find(points,ContactSurface.FLOOR)
      assertNotNull("$fps FPS",f);assertEquals(.17,f!!.time,.015)
      assertEquals(300.0,f.y,4.0);assertEquals(280.8,f.x,4.0)
    }
  }
  @Test fun glancingWallReturnHasAnObservedTwoSidedPosition() {
    val f=ObservedContactFit.find(samples{t->val d=t-.17
      300+(if(d<0)650*d else -300*d)+sin(t*150)*1.5 to 230+100*d+400*d*d},ContactSurface.WALL)
    assertNotNull(f);assertEquals(300.0,f!!.x,4.0);assertEquals(230.0,f.y,4.0)
  }
  @Test fun twentyAnalyzedFramesPerSecondStillSupportAnObservedReturn() {
    val p=(0..8).map { i ->
      val t=i/20.0;val d=t-.17
      BallObservation(240+240*t,300+(if(d<0)700*d else -200*d)+400*d*d+sin(i*2.4)*1.5,
        8.0,t,.9,greenDominant=true)
    }
    val f=ObservedContactFit.find(p,ContactSurface.FLOOR)
    assertNotNull("20 analyzed FPS must not be excluded by the history window",f)
    assertEquals(.17,f!!.time,.02);assertEquals(300.0,f.y,5.0)
  }
  @Test fun smoothFlightAndRoundTurnsCannotBecomeImpacts() {
    val curves=listOf<(Double)->Pair<Double,Double>>(
      {t->200+300*t to 220+700*t*t},
      {t->200+300*t to 300-400*(t-.15)*(t-.15)},
      {t->300+30*cos(t*8) to 230+30*sin(t*8)},
      {t->250+sin(t*150)*3 to 300+sin(t*180)*3},
      {_->250.0 to 300.0})
    for(curve in curves)for(surface in listOf(ContactSurface.FLOOR,ContactSurface.WALL))
      assertNull(ObservedContactFit.find(samples(curve=curve),surface))
  }
  @Test fun handOcclusionAndUnseenReturnDoNotProduceContactFits() {
    val curve:(Double)->Pair<Double,Double> = {t->val d=t-.17;250.0 to 300+(if(d<0)700*d else -400*d)}
    assertNull(ObservedContactFit.find(samples(hand=true,curve=curve),ContactSurface.FLOOR))
    assertNull(ObservedContactFit.find(samples(occluded=true,curve=curve),ContactSurface.FLOOR))
    assertNull(ObservedContactFit.find(samples(curve=curve).filter{it.time<.10 || it.time>.23},ContactSurface.FLOOR))
    assertNull(ObservedContactFit.find(samples(curve=curve).filter{it.time<.17},ContactSurface.FLOOR))
  }
}

