package ai.tennisvision.camera
import org.junit.Assert.*
import org.junit.Test
class BallClutterTest {
  private fun ball(t:Double,x:Double=100.0)=BallObservation(x,120.0,12.0,t,.85,luminance=150.0,distinctFelt=true,greenDominant=true)
  @Test fun onlyStationaryUnresolvedFragmentsAreLearned(){
    val cache=BallClutter()
    repeat(35){i->
      val result=cache.filter(listOf(ball(i/60.0).copy(radius=1.5),ball(i/60.0,220.0),ball(i/60.0,330.0).copy(nearHand=true)),i/60.0)
      if(i>=20)assertTrue(result[0].patterned)
      assertFalse(result[1].patterned);assertFalse(result[2].patterned)
    }
    assertFalse(cache.filter(listOf(ball(.6,120.0).copy(radius=1.5)),.6).single().patterned)
    cache.reset();assertFalse(cache.filter(listOf(ball(.7).copy(radius=1.5)),.7).single().patterned)
  }
  @Test fun recoveredBallDoesNotRequireEveryOtherCandidateToDisappear(){
    val recovery=BallReacquisition();val still=ball(0.0,20.0).copy(radius=1.5,motionSupport=0.0)
    assertNull(recovery.choose(listOf(ball(0.0),ball(0.0,400.0).copy(radius=1.5)),still,true,640))
    val chosen=recovery.choose(listOf(ball(1/60.0,110.0),ball(1/60.0,402.0).copy(radius=1.5)),still,true,640)
    assertEquals(110.0,chosen!!.x,0.0)
  }
  @Test fun twoEqualMovingBallsAndAStaleSampleRemainAmbiguous(){
    val recovery=BallReacquisition()
    recovery.choose(listOf(ball(0.0),ball(0.0,400.0)),null,true,640)
    assertNull(recovery.choose(listOf(ball(1/60.0,110.0),ball(1/60.0,410.0)),null,true,640))
    assertNull(recovery.choose(listOf(ball(.3,120.0)),null,true,640))
    recovery.reset();assertNull(recovery.choose(listOf(ball(.32,130.0)),null,true,640))
  }
  @Test fun largeHeldBallIsVisibleWithoutClaimingAnImpact(){
    val recovery=BallReacquisition()
    val held=ball(0.0).copy(nearHand=true)
    assertNull(recovery.choose(listOf(held),null,true,640))
    assertTrue(recovery.choose(listOf(held.copy(time=1/60.0)),null,true,640)!!.nearHand)
  }
}
