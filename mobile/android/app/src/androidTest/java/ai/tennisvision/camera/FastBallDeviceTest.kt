package ai.tennisvision.camera

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import java.io.File

@RunWith(AndroidJUnit4::class)
class FastBallDeviceTest {
  @Test fun knownFloorExcludesUpperTurnsAndStillCountsOutOnTheFloor() {
    val context=InstrumentationRegistry.getInstrumentation().targetContext
    check(context.packageName.endsWith(".audit"))
    val above=FastBallEvaluation.evaluateWithFloor(FastBallEvaluation.Case("above_floor",3.0,40.0,outside=true),.9)
    val outside=FastBallEvaluation.evaluate(FastBallEvaluation.Case("outside_on_floor",3.0,40.0,outside=true))
    assertEquals(above.visible,above.correctlyLocated)
    assertTrue(above.calls.isEmpty())
    assertEquals(listOf(LineCall.OUT),outside.calls)
    File(context.getExternalFilesDir(null),"device-proof").apply { mkdirs() }.let { folder ->
      File(folder,"floor-boundary.json").writeText(JSONObject().put("input","known synthetic trajectories processed on physical device")
        .put("aboveFloorObservedFrames",above.correctlyLocated).put("aboveFloorContacts",above.calls.size)
        .put("outsideOnFloorCalls",outside.calls.joinToString()).put("success",true).toString(2))
    }
  }
  @Test fun evaluateKnownFastTrajectoriesOnDeviceCpu() {
    val context=InstrumentationRegistry.getInstrumentation().targetContext
    check(context.packageName.endsWith(".audit")) { "Use the isolated audit app; never modify personal training data" }
    val folder=File(context.getExternalFilesDir(null),"device-proof").apply { mkdirs() }
    // Warm the same code before recording per-scenario CPU times.
    FastBallEvaluation.evaluate(FastBallEvaluation.Case("warmup",12.0,20.0))
    val results=FastBallEvaluation.cases().map(FastBallEvaluation::evaluate)
    File(folder,"fast-ball.csv").writeText(FastBallEvaluation.header+"\n"+results.joinToString("\n") { it.csv() }+"\n")
    File(folder,"device.json").writeText(JSONObject().put("manufacturer",android.os.Build.MANUFACTURER)
      .put("model",android.os.Build.MODEL).put("android",android.os.Build.VERSION.RELEASE)
      .put("sdk",android.os.Build.VERSION.SDK_INT).put("hardware",android.os.Build.HARDWARE)
      .put("package",context.packageName).put("input","Synthetic pixels with independent known positions; not physical ball footage")
      .put("build","audit; production detector with test instrumentation").toString(2))
    assertEquals(listOf(LineCall.IN),results.first { it.case.name=="diam12_step20" }.calls)
    assertEquals(listOf(LineCall.OUT),results.first { it.case.name=="out_fast" }.calls)
    assertTrue(results.first { it.case.name=="air_crossing" }.calls.isEmpty())
  }
}
