package ai.tennisvision.camera

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.SystemClock
import android.view.Surface
import android.widget.Button
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Collections

/** Measures the real camera and detector without giving this audit app network access. */
@RunWith(AndroidJUnit4::class)
class CameraStabilityDeviceTest {
  @get:Rule val permission=GrantPermissionRule.grant(Manifest.permission.CAMERA)

  @Test fun measureCameraFramesAndAnalysisWithoutNetworkPermission() {
    val instrumentation=InstrumentationRegistry.getInstrumentation()
    val context=instrumentation.targetContext
    check(context.packageName.endsWith(".audit")) { "Use the isolated audit app" }
    assertEquals("Audit must be unable to call a PC or cloud API",PackageManager.PERMISSION_DENIED,
      context.checkSelfPermission(Manifest.permission.INTERNET))
    val folder=File(context.getExternalFilesDir(null),"device-proof").apply { mkdirs() }
    val profiles=JSONArray()
    val failures=Collections.synchronizedList(mutableListOf<String>())
    val capabilities=JSONArray()
    val manager=context.getSystemService(CameraManager::class.java)
    for(id in manager.cameraIdList) {
      val chars=manager.getCameraCharacteristics(id)
      val map=chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: continue
      val sizes=JSONArray()
      map.getOutputSizes(ImageFormat.YUV_420_888).filter { it.width<=1280 && it.height<=720 }.forEach {
        sizes.put(JSONObject().put("width",it.width).put("height",it.height)
          .put("minimumFrameDurationNs",map.getOutputMinFrameDuration(ImageFormat.YUV_420_888,it)))
      }
      val highSpeed=JSONArray()
      runCatching { map.highSpeedVideoSizes.forEach { size -> highSpeed.put(JSONObject()
        .put("size",size.toString()).put("ranges",map.getHighSpeedVideoFpsRangesFor(size).joinToString())) } }
      capabilities.put(JSONObject().put("cameraId",id).put("facing",chars.get(CameraCharacteristics.LENS_FACING))
        .put("timestampSource",chars.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE))
        .put("normalAeRanges",chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.joinToString())
        .put("yuvSizes",sizes).put("advertisedHighSpeedVideoOnly",highSpeed))
    }
    ActivityScenario.launch(LocalTrainingActivity::class.java).use { scenario ->
      var ready=false
      val deadline=SystemClock.elapsedRealtime()+10000
      while (!ready && SystemClock.elapsedRealtime()<deadline) {
        scenario.onActivity { ready=it.findViewById<Button>(R.id.localStart).text.toString()=="Iniciar treino" }
        if(!ready) Thread.sleep(100)
      }
      assertTrue("Real camera never delivered the first frame",ready)
      var displayDegrees=0
      var activity: LocalTrainingActivity?=null
      scenario.onActivity {
        activity=it
        @Suppress("DEPRECATION")
        val rotation=it.windowManager.defaultDisplay.rotation
        displayDegrees=when(rotation) { Surface.ROTATION_90->90;Surface.ROTATION_180->180;Surface.ROTATION_270->270;else->0 }
        (LocalTrainingActivity::class.java.getDeclaredField("camera").apply { isAccessible=true }.get(it) as LocalCamera).stop()
      }
      Thread.sleep(350)
      for(preferFast in listOf(true,false)) {
        data class Sample(val timestamp: Double,val analysis: Double,val fps: Double,val requested: Int,
          val key: String,val observed: Boolean,val impact: Boolean,val conversionMs: Double,val detectorMs: Double)
        val samples=Collections.synchronizedList(mutableListOf<Sample>())
        val warmUntil=SystemClock.elapsedRealtime()+4000
        val camera=LocalCamera(context,{ LocalSettings() },{ frame ->
          if(SystemClock.elapsedRealtime()>=warmUntil) samples.add(Sample(frame.timestamp,frame.analysisMs,
            frame.captureFps,frame.requestedFps,frame.key,frame.result.observed!=null,frame.result.impact!=null,frame.conversionMs,frame.detectorMs))
          activity?.receiveFrame(frame) // Exercise the actual bounded UI queue and bitmap renderer too.
        },{ failures.add(it) })
        try {
          camera.analyze=true
          camera.start(displayDegrees,preferFast)
          val until=SystemClock.elapsedRealtime()+16000
          while(SystemClock.elapsedRealtime()<until && failures.isEmpty()) Thread.sleep(100)
        } finally { camera.close() }
        val copy=synchronized(samples) { samples.toList() }
        val costs=copy.map { it.analysis }.sorted()
        val gaps=copy.zipWithNext().map { (a,b)->(b.timestamp-a.timestamp)*1000 }.sorted()
        fun percentile(values: List<Double>,fraction: Double): Double? = if(values.isEmpty()) null else values[((values.size-1)*fraction).toInt()]
        profiles.put(JSONObject().put("preferFast",preferFast).put("frames",copy.size)
          .put("key",copy.firstOrNull()?.key).put("requestedFps",copy.firstOrNull()?.requested)
          .put("receivedFps",if(copy.size>1) (copy.size-1)/(copy.last().timestamp-copy.first().timestamp) else 0.0)
          .put("conversionAndAnalysisP50Ms",percentile(costs,.5)).put("conversionAndAnalysisP95Ms",percentile(costs,.95))
          .put("conversionP50Ms",percentile(copy.map { it.conversionMs }.sorted(),.5))
          .put("conversionP95Ms",percentile(copy.map { it.conversionMs }.sorted(),.95))
          .put("detectorP50Ms",percentile(copy.map { it.detectorMs }.sorted(),.5))
          .put("detectorP95Ms",percentile(copy.map { it.detectorMs }.sorted(),.95))
          .put("frameGapP50Ms",percentile(gaps,.5)).put("frameGapP95Ms",percentile(gaps,.95))
          .put("maxFrameGapMs",gaps.lastOrNull()).put("gapsAbove100Ms",gaps.count { it>100 })
          .put("observations",copy.count { it.observed }).put("candidateContacts",copy.count { it.impact }))
        Thread.sleep(350)
      }
    }
    File(folder,"camera-stability.json").writeText(JSONObject().put("model",android.os.Build.MODEL)
      .put("android",android.os.Build.VERSION.RELEASE).put("networkPermission",false).put("capabilities",capabilities)
      .put("profiles",profiles).put("errors",JSONArray(failures.toList()))
      .put("limits","Camera/CPU/UI exercise with unlabelled scene. No claim of match accuracy or optical end-to-end latency. High-speed video modes are advertised capabilities, not tested local-analysis modes.").toString(2))
    assertTrue("Camera errors: $failures",failures.isEmpty())
    assertEquals(2,profiles.length())
    for(i in 0 until profiles.length()) assertTrue("Too few real camera frames",profiles.getJSONObject(i).getInt("frames")>120)
  }
}
