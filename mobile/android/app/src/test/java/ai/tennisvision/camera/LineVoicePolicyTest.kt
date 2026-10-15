package ai.tennisvision.camera
import org.junit.Assert.*
import org.junit.Test
class LineVoicePolicyTest {
  @Test fun uncertainContactsStaySilent() {
    for(call in listOf(LineCall.REVIEW,LineCall.UNMARKED,LineCall.REJECTED)) assertFalse(LineVoicePolicy.enabled(call,true,true))
  }
  @Test fun preferencesAreIndependent() {
    assertTrue(LineVoicePolicy.enabled(LineCall.OUT,true,false));assertFalse(LineVoicePolicy.enabled(LineCall.IN,true,false))
    assertTrue(LineVoicePolicy.enabled(LineCall.IN,false,true));assertFalse(LineVoicePolicy.enabled(LineCall.OUT,false,true))
  }
  @Test fun outsidePreemptsInsideWithoutQueueing() {
    assertTrue(LineVoicePolicy.canStart(LineCall.OUT,LineCall.IN));assertFalse(LineVoicePolicy.canStart(LineCall.IN,LineCall.OUT))
    assertFalse(LineVoicePolicy.canStart(LineCall.OUT,LineCall.OUT));assertTrue(LineVoicePolicy.canStart(LineCall.IN,null))
  }
}
