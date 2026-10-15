package ai.tennisvision.camera
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.json.JSONObject
import kotlin.math.ceil
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[33])
class LineVoicePolicyTest {
  @Test fun playbackGuardUsesTheDeliveredAudioDurations() {
    val manifest=requireNotNull(javaClass.classLoader!!.getResourceAsStream("line-audio/manifest.json")).bufferedReader().use { JSONObject(it.readText()) }
    val resources=RuntimeEnvironment.getApplication().resources
    for((call,id) in listOf("OUT" to R.integer.line_voice_out_ms,"IN" to R.integer.line_voice_in_ms)) {
      val duration=manifest.getJSONObject("calls").getJSONObject(call).getDouble("durationSeconds")
      assertEquals(ceil(duration*1000).toInt()+30,resources.getInteger(id))
    }
  }
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
