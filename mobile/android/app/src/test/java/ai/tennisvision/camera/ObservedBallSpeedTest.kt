package ai.tennisvision.camera

import org.junit.Assert.*
import org.junit.Test

class ObservedBallSpeedTest {
  private fun path()=(0..6).map{BallObservation(it*10.0,100.0,6.0,it/60.0,.9)}
  @Test fun briefBlurAtContactUsesRecentCleanIncomingSpeed(){
    val p=path().toMutableList();p[6]=p[6].copy(blurred=true)
    assertNull(ObservedBallSpeed.current(p))
    assertEquals(12.06,ObservedBallSpeed.incoming(p,.1)!!,.001)
  }
  @Test fun outgoingLegNeverCancelsIncomingSpeed(){
    val p=path().toMutableList();p[6]=p[6].copy(x=40.0)
    assertEquals(12.06,ObservedBallSpeed.incoming(p,.085)!!,.001)
  }
  @Test fun neverRecoversAcrossHandOcclusionUnresolvedOrLongBlur(){
    for(kind in 0..4){val p=path().toMutableList();p[6]=p[6].copy(blurred=true)
      when(kind){0->p[6]=p[6].copy(nearHand=true);1->p[6]=p[6].copy(occluded=true)
        2->p[6]=p[6].copy(colorUncertain=true);3->p[5]=p[5].copy(nearHand=true)
        4->{p[4]=p[4].copy(blurred=true);p[5]=p[5].copy(blurred=true)}}
      assertNull(ObservedBallSpeed.incoming(p,.1))
    }
    assertNull(ObservedBallSpeed.incoming(path(),.16))
  }
  @Test fun changingScaleAndTooSmallBallCannotProduceSpeed(){
    val p=path().toMutableList();p[6]=p[6].copy(radius=12.0)
    assertNull(ObservedBallSpeed.incoming(p,.1))
    assertNull(ObservedBallSpeed.incoming(path().map{it.copy(radius=2.0)},.1))
  }
}
