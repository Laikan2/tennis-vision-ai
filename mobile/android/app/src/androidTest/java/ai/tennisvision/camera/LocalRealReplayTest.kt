package ai.tennisvision.camera

import android.graphics.BitmapFactory
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Optional private regression input. User recordings are never bundled in the APK/repository. */
@RunWith(AndroidJUnit4::class)
class LocalRealReplayTest {
  @Test fun evaluatePrivateRecordedFramesOnAndroid() {
    val context=InstrumentationRegistry.getInstrumentation().targetContext
    val folder=File(context.getExternalFilesDir(null),"offline-fixtures")
    assumeTrue("Private replay is not installed on this test device",File(folder,"frames.json").exists())
    val input=JSONObject(File(folder,"frames.json").readText())
    val polygon=input.getJSONArray("polygon").let { list -> (0 until list.length()).map { i -> list.getJSONArray(i).let { CourtPoint(it.getDouble(0),it.getDouble(1)) } } }
    val core=LocalAnalysis(LocalSettings(polygon,listOf(CourtPoint(0.0,.53),CourtPoint(1.0,.53))))
    val frames=input.getJSONArray("frames"); val rows=JSONArray(); val costs=mutableListOf<Double>(); var observations=0; var contacts=0
    for (i in 0 until frames.length()) {
      val f=frames.getJSONObject(i)
      val bitmap=BitmapFactory.decodeFile(File(folder,f.getString("file")).path,BitmapFactory.Options().apply { inSampleSize=2 })!!
      val pixels=IntArray(bitmap.width*bitmap.height); bitmap.getPixels(pixels,0,bitmap.width,0,0,bitmap.width,bitmap.height)
      val started=SystemClock.elapsedRealtimeNanos()
      val result=core.process(pixels,bitmap.width,bitmap.height,f.getDouble("time"))
      costs.add((SystemClock.elapsedRealtimeNanos()-started)/1e6)
      if (result.observed!=null) observations++
      if (result.impact!=null) contacts++
      val b=result.observed
      rows.put(JSONObject().put("file",f.getString("file")).put("time",f.getDouble("time")).put("x",b?.x ?: JSONObject.NULL)
        .put("y",b?.y ?: JSONObject.NULL).put("nearHand",b?.nearHand ?: false).put("radius",b?.radius ?: JSONObject.NULL)
        .put("call",result.impact?.call?.name ?: JSONObject.NULL).put("contactY",result.impact?.point?.y ?: JSONObject.NULL))
      bitmap.recycle()
    }
    costs.sort()
    File(context.getExternalFilesDir(null),"offline-proof/real-replay.json").writeText(JSONObject()
      .put("frames",frames.length()).put("observed",observations).put("contacts",contacts)
      .put("p50Ms",costs[costs.size/2]).put("p95Ms",costs[(costs.size*.95).toInt()]).put("rows",rows)
      .put("caution","Recorded user replay; not an independently labelled precision/recall benchmark").toString(2))
    assertTrue(observations>0)
  }
}
