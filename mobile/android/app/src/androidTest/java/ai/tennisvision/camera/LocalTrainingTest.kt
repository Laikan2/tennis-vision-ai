package ai.tennisvision.camera

import android.Manifest
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Executed on Android, not Robolectric. Fixtures are synthetic and explicitly labelled. */
@RunWith(AndroidJUnit4::class)
class LocalTrainingTest {
  @get:Rule val permission=GrantPermissionRule.grant(Manifest.permission.CAMERA)
  private val instrumentation=InstrumentationRegistry.getInstrumentation()
  private val context get()=instrumentation.targetContext
  private fun waitFor(timeout: Long=8000,check: ()->Boolean) {
    val until=SystemClock.elapsedRealtime()+timeout
    while (SystemClock.elapsedRealtime()<until) { instrumentation.waitForIdleSync(); if (check()) return; Thread.sleep(60) }
    fail("Condition did not become true within $timeout ms")
  }
  private fun ready(scenario: ActivityScenario<LocalTrainingActivity>) {
    var state=""
    try { waitFor { var ok=false; scenario.onActivity { state=it.findViewById<TextView>(R.id.localStatus).text.toString()+" / "+it.findViewById<Button>(R.id.localStart).text; ok=it.findViewById<Button>(R.id.localStart).text.toString()=="Iniciar treino" }; ok } }
    catch (error: AssertionError) { screenshot("failure-ready"); throw AssertionError(state,error) }
  }
  private fun tap(scenario: ActivityScenario<LocalTrainingActivity>,id: Int) {
    var x=0f; var y=0f
    scenario.onActivity { activity -> val view=activity.findViewById<View>(id); view.requestRectangleOnScreen(android.graphics.Rect(0,0,view.width,view.height),true) }
    instrumentation.waitForIdleSync()
    scenario.onActivity { activity -> val v=activity.findViewById<View>(id); val p=IntArray(2); v.getLocationOnScreen(p); x=p[0]+v.width/2f; y=p[1]+v.height/2f }
    touch(x,y,x,y)
  }
  private fun touch(x: Float,y: Float,endX: Float,endY: Float) {
    val time=SystemClock.uptimeMillis()
    fun event(action: Int,px: Float,py: Float,offset: Long) {
      val e=MotionEvent.obtain(time,time+offset,action,px,py,0)
      instrumentation.sendPointerSync(e); e.recycle()
    }
    event(MotionEvent.ACTION_DOWN,x,y,0)
    if (x!=endX || y!=endY) for (i in 1..8) event(MotionEvent.ACTION_MOVE,x+(endX-x)*i/8,y+(endY-y)*i/8,i*10L)
    event(MotionEvent.ACTION_UP,endX,endY,100); instrumentation.waitForIdleSync()
  }
  private fun drag(scenario: ActivityScenario<LocalTrainingActivity>,a: CourtPoint,b: CourtPoint) {
    var x=0f; var y=0f; var ex=0f; var ey=0f
    scenario.onActivity { activity -> val view=activity.findViewById<LocalCourtView>(R.id.localVideo); val p=IntArray(2); view.getLocationOnScreen(p)
      val from=view.pointOnScreen(a); val to=view.pointOnScreen(b); x=p[0]+from.x; y=p[1]+from.y; ex=p[0]+to.x; ey=p[1]+to.y }
    touch(x,y,ex,ey)
  }
  private fun screenshot(name: String) {
    val folder=File(context.getExternalFilesDir(null),"offline-proof").apply { mkdirs() }
    instrumentation.uiAutomation.takeScreenshot()?.let { bitmap -> File(folder,"$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }; bitmap.recycle() }
  }
  private fun area(scenario: ActivityScenario<LocalTrainingActivity>) {
    tap(scenario,R.id.localArea); tap(scenario,R.id.localRedraw)
    drag(scenario,CourtPoint(.2,.35),CourtPoint(.7,.9))
    scenario.onActivity { assertTrue(it.findViewById<Button>(R.id.localSaveArea).isEnabled) }
    tap(scenario,R.id.localSaveArea)
    // The floor wizard advances directly to its boundary step, without saving yet.
    scenario.onActivity { assertEquals(2,it.findViewById<LocalCourtView>(R.id.localVideo).editing) }
    tap(scenario,R.id.localRedraw)
    drag(scenario,CourtPoint(.05,.28),CourtPoint(.05,.28)); drag(scenario,CourtPoint(.95,.28),CourtPoint(.95,.28))
    tap(scenario,R.id.localSaveArea)
  }
  @Test fun visiblePreviewBallDoesNotRequireStartingTrainingOrChangeStatistics() {
    ActivityScenario.launch(LocalTrainingActivity::class.java).use { scenario ->
      ready(scenario);var before=0;var sounds=0;var key="";var config=LocalSettings()
      // Landscape startup may schedule a camera reopen after the first frame.
      // Let that lifecycle transition settle before replacing the emulated camera.
      SystemClock.sleep(400);ready(scenario)
      scenario.onActivity {
        before=it.testSession().events.size;sounds=it.testSoundCount();config=it.testConfig()
        key=LocalTrainingActivity::class.java.getDeclaredField("cameraKey").apply { isAccessible=true }.get(it) as String
        (LocalTrainingActivity::class.java.getDeclaredField("camera").apply { isAccessible=true }.get(it) as LocalCamera).stop()
      }
      val pixels=IntArray(1280*720){0xff315372.toInt()}
      for(y in 336..384) for(x in 616..664) if(kotlin.math.hypot(x-640.0,y-360.0)<=24)pixels[y*1280+x]=0xffc7eb4d.toInt()
      val time=SystemClock.elapsedRealtime()/1e3
      val result=BallPreview().process(pixels,1280,720,time,config)
      assertNotNull(result.observed);assertNull(result.impact);assertNull(result.speedKmh)
      scenario.onActivity { it.receiveFrame(LocalFrame(pixels,1280,720,time,result,0.0,0.0,0,key)) }
      instrumentation.waitForIdleSync()
      val drawn=CountDownLatch(1)
      scenario.onActivity {
        assertEquals("Iniciar treino",it.findViewById<Button>(R.id.localStart).text.toString())
        assertEquals(before,it.testSession().events.size);assertEquals(sounds,it.testSoundCount())
        val view=it.findViewById<LocalCourtView>(R.id.localVideo)
        val shown=LocalCourtView::class.java.getDeclaredField("image").apply { isAccessible=true }.get(view) as Bitmap
        val overlay=LocalCourtView::class.java.getDeclaredField("result").apply { isAccessible=true }.get(view) as LocalResult
        assertEquals(pixels[360*1280+640],shown.getPixel(640,360))
        assertEquals(640.0,overlay.observed!!.x,2.0);assertEquals(360.0,overlay.observed!!.y,2.0)
        assertTrue("Screenshot verification requires hardware rendering",view.isHardwareAccelerated)
        view.viewTreeObserver.registerFrameCommitCallback { drawn.countDown() };view.invalidate()
      }
      // Idle handlers finish before hardware rendering. Wait for its frame commit.
      assertTrue("Known preview must reach the display",drawn.await(2,TimeUnit.SECONDS))
      SystemClock.sleep(100) // The emulator compositor presents after RenderThread's commit.
      screenshot("preview-ring-0.6.9-known-pixels")
    }
  }
  @Test fun decodedVerticalReturnsCreateWallMarksWithoutFalseOutAudio() {
    ActivityScenario.launch(LocalTrainingActivity::class.java).use { scenario ->
      ready(scenario);area(scenario)
      tap(scenario,R.id.localWall);tap(scenario,R.id.localRedraw)
      drag(scenario,CourtPoint(.1,.1),CourtPoint(.9,.95));tap(scenario,R.id.localSaveArea)
      tap(scenario,R.id.localStart)
      var config=LocalSettings();var key="";var before=0;var sounds=0;var id=""
      scenario.onActivity {
        config=it.testConfig();assertEquals(ContactSurface.WALL,config.surface)
        before=it.testSession().events.size;sounds=it.testSoundCount();id=it.testSession().id
        key=LocalTrainingActivity::class.java.getDeclaredField("cameraKey").apply { isAccessible=true }.get(it) as String
        (LocalTrainingActivity::class.java.getDeclaredField("camera").apply { isAccessible=true }.get(it) as LocalCamera).stop()
      }
      val core=LocalAnalysis(config);val events=mutableListOf<LocalImpact>()
      val recent=ArrayDeque<Pair<Double,IntArray>>();val origin=SystemClock.elapsedRealtime()/1e3
      // Encoded synthetic vertical reversals exercise the wall algorithm, not physical contact truth.
      decode("known-bounces.mp4") { pixels,w,h,pts ->
        val time=origin+pts;val result=core.process(pixels,w,h,time)
        recent.addLast(time to pixels);while(recent.size>6)recent.removeFirst()
        val evidence=result.impact?.let { hit -> events.add(hit);recent.minByOrNull { kotlin.math.abs(it.first-hit.time) }?.second }
        scenario.onActivity { it.receiveFrame(LocalFrame(pixels,w,h,time,result,0.0,0.0,0,key,evidence)) }
        instrumentation.waitForIdleSync()
      }
      assertTrue("Two resolved returns must be found; an uncertain third may require more evidence",events.size in 2..3)
      assertTrue(events.all { it.surface==ContactSurface.WALL && it.call==LineCall.REVIEW })
      assertTrue(events.take(2).any { it.speedKmh!=null })
      scenario.onActivity {
        assertEquals(before+events.size,it.testSession().events.size);assertEquals(sounds,it.testSoundCount())
        assertTrue(it.findViewById<TextView>(R.id.localDecision).text.toString().contains("REVISAR"))
      }
      waitFor { LocalSessionStore(context).load(id)?.events?.size==before+events.size }
      screenshot("wall-returns-0.6.9-synthetic")
    }
  }
  @Test fun homeFloorWallSetupAndCancellationPreserveSeparateAreas() {
    ActivityScenario.launch(LocalTrainingActivity::class.java).use { scenario ->
      ready(scenario);area(scenario)
      var floor=LocalSettings()
      scenario.onActivity { floor=it.testConfig();assertEquals(ContactSurface.FLOOR,floor.surface) }
      tap(scenario,R.id.localWall);tap(scenario,R.id.localRedraw)
      drag(scenario,CourtPoint(.25,.1),CourtPoint(.85,.6))
      tap(scenario,R.id.localSaveArea)
      var wall=LocalSettings()
      scenario.onActivity {
        wall=it.testConfig();assertEquals(ContactSurface.WALL,wall.surface)
        assertEquals(floor.polygon,wall.floorPolygon);assertEquals(floor.floorLine,wall.floorLine)
        assertNotEquals(floor.polygon,wall.polygon)
      }
      // Cancelling the second floor step must not save the first step alone.
      tap(scenario,R.id.localArea);tap(scenario,R.id.localRedraw)
      drag(scenario,CourtPoint(.1,.65),CourtPoint(.6,.95));tap(scenario,R.id.localSaveArea)
      scenario.onActivity { assertEquals(2,it.findViewById<LocalCourtView>(R.id.localVideo).editing) }
      tap(scenario,R.id.localCancelArea)
      scenario.onActivity { assertEquals(wall,it.testConfig()) }
      // Switching surfaces in this session uses its own corners and boundary.
      tap(scenario,R.id.localArea)
      scenario.onActivity { assertEquals(floor.polygon,it.findViewById<LocalCourtView>(R.id.localVideo).draft) }
      tap(scenario,R.id.localSaveArea)
      scenario.onActivity { assertEquals(floor.floorLine,it.findViewById<LocalCourtView>(R.id.localVideo).draft) }
      tap(scenario,R.id.localSaveArea)
      scenario.onActivity {
        assertEquals(floor.polygon,it.testConfig().polygon)
        assertEquals(wall.polygon,it.testConfig().wallPolygon)
      }
      screenshot("session-floor-restored")
      scenario.recreate();ready(scenario)
      scenario.onActivity {
        assertTrue("A new Activity/session must not restore old geometry",it.testConfig().polygon.isEmpty())
        assertTrue(it.testConfig().floorLine.isEmpty())
        assertTrue(it.testConfig().floorPolygon.isNullOrEmpty())
        assertTrue(it.testConfig().wallPolygon.isNullOrEmpty())
      }
    }
  }
  @Test fun cameraAreaTouchesCancellationAndLifecycleWorkOffline() {
    ActivityScenario.launch(LocalTrainingActivity::class.java).use { scenario ->
      ready(scenario); area(scenario)
      var saved=LocalSettings()
      scenario.onActivity { saved=it.testConfig(); assertTrue(LocalGeometry.valid(saved.polygon)); assertTrue(LocalGeometry.validFloorLine(saved.floorLine)) }
      tap(scenario,R.id.localArea); tap(scenario,R.id.localRedraw)
      drag(scenario,CourtPoint(.5,.5),CourtPoint(.501,.501))
      scenario.onActivity { assertFalse(it.findViewById<Button>(R.id.localSaveArea).isEnabled) }
      tap(scenario,R.id.localCancelArea)
      scenario.onActivity { assertEquals(saved,it.testConfig()); assertEquals(View.GONE,it.findViewById<View>(R.id.localEditor).visibility) }
      tap(scenario,R.id.localStart); Thread.sleep(1100)
      scenario.moveToState(Lifecycle.State.CREATED); Thread.sleep(200); scenario.moveToState(Lifecycle.State.RESUMED); ready(scenario)
      scenario.onActivity { assertEquals(saved,it.testConfig()); assertEquals("Iniciar treino",it.findViewById<Button>(R.id.localStart).text.toString()) }
      // Rapid real touch events must leave one deterministic start/stop state, not two camera sessions.
      repeat(10) { tap(scenario,R.id.localStart) }
      scenario.onActivity { assertEquals("Iniciar treino",it.findViewById<Button>(R.id.localStart).text.toString()) }
      scenario.recreate(); ready(scenario); Thread.sleep(1500)
      scenario.onActivity {
        assertTrue(it.testConfig().polygon.isEmpty());assertTrue(it.testConfig().floorLine.isEmpty())
        assertEquals(saved.sound,it.testConfig().sound);assertEquals(saved.marks,it.testConfig().marks)
        assertEquals(saved.surface,it.testConfig().surface)
      }
      screenshot("01-local-camera-area-cleared-on-restart")
      scenario.onActivity { activity ->
        val report=JSONObject().put("kind","camera UI exercise; scene is not labelled tennis footage")
          .put("device",android.os.Build.MODEL).put("android",android.os.Build.VERSION.RELEASE)
          .put("metrics",activity.findViewById<TextView>(R.id.localMetrics).text.toString())
        File(context.getExternalFilesDir(null),"offline-proof/camera.json").writeText(report.toString(2))
      }
    }
  }

  @Test fun newSessionDiscardsAreasWithoutDeletingEarlierHistory() {
    ActivityScenario.launch(LocalTrainingActivity::class.java).use { scenario ->
      ready(scenario);area(scenario)
      var oldId="";var oldCount=0
      scenario.onActivity {
        oldId=it.testSession().id;oldCount=it.testSession().events.size
        assertTrue(LocalGeometry.valid(it.testConfig().polygon))
        it.beginNewSession()
        assertNotEquals(oldId,it.testSession().id)
        assertTrue(it.testConfig().polygon.isEmpty());assertTrue(it.testConfig().floorLine.isEmpty())
        assertTrue(it.testConfig().floorPolygon.isNullOrEmpty());assertTrue(it.testConfig().wallPolygon.isNullOrEmpty())
      }
      waitFor { LocalSessionStore(context).load(oldId)?.events?.size==oldCount }
      screenshot("new-session-empty-area")
    }
  }

  private fun shell(command: String) { instrumentation.uiAutomation.executeShellCommand(command).use { descriptor -> java.io.FileInputStream(descriptor.fileDescriptor).use { it.readBytes() } } }

  @Test fun androidAtomicStorageRecoversInterruptedWriteAndPreservesSession() {
    // Atomic rename/overwrite has different filesystem semantics on Windows/JVM.
    val store=LocalSessionStore(context); val original=store.current()
    try {
      val sample=LocalSession(); store.save(sample)
      val updated=sample.copy(events=listOf(LocalImpact(1,1.0,CourtPoint(.5,.5),6.0,LineCall.REVIEW,null,4.0,"storage fixture",ContactSurface.FLOOR)))
      store.save(updated)
      assertEquals(updated,store.load(sample.id))
      val file=File(context.filesDir,"local-sessions/${sample.id}.json")
      file.copyTo(File(file.path+".bak"),true); file.writeText("interrupted{")
      assertEquals(updated,LocalSessionStore(context).load(sample.id))
    } finally { store.save(original) }
  }

  @Test fun lockScreenBatterySaverAndNoNetworkDoNotLoseSettings() {
    val power=context.getSystemService(android.os.PowerManager::class.java)
    val connectivity=context.getSystemService(android.net.ConnectivityManager::class.java)
    assertNull("Run offline test with emulator Wi-Fi and data disabled",connectivity.activeNetwork)
    try {
      shell("dumpsys battery unplug"); shell("cmd power set-mode 1")
      waitFor { power.isPowerSaveMode }
      ActivityScenario.launch(LocalTrainingActivity::class.java).use { scenario ->
        ready(scenario); var config=LocalSettings()
        scenario.onActivity { config=it.testConfig() }
        tap(scenario,R.id.localStart); Thread.sleep(250)
        shell("input keyevent 223"); waitFor { !power.isInteractive }; Thread.sleep(300)
        shell("input keyevent 224"); shell("wm dismiss-keyguard")
        ready(scenario)
        scenario.onActivity {
          assertEquals(config,it.testConfig())
          assertEquals("Iniciar treino",it.findViewById<Button>(R.id.localStart).text.toString())
        }
        screenshot("03-offline-after-lock-and-battery-saver")
      }
    } finally { shell("input keyevent 224"); shell("wm dismiss-keyguard"); shell("cmd power set-mode 0"); shell("dumpsys battery reset") }
  }

  @Test fun encodedKnownBouncesReachUiStatisticsSoundAndPersistence() {
    ActivityScenario.launch(LocalTrainingActivity::class.java).use { scenario ->
      ready(scenario); area(scenario)
      var config=LocalSettings(); var key=""; var before=0; var sounds=0
      tap(scenario,R.id.localStart)
      scenario.onActivity { activity ->
        config=activity.testConfig(); before=activity.testSession().events.size; sounds=activity.testSoundCount()
        key=LocalTrainingActivity::class.java.getDeclaredField("cameraKey").apply { isAccessible=true }.get(activity) as String
        // Stop the unrelated live camera before replaying a controlled encoded source.
        (LocalTrainingActivity::class.java.getDeclaredField("camera").apply { isAccessible=true }.get(activity) as LocalCamera).stop()
      }
      val core=LocalAnalysis(config); val events=mutableListOf<LocalImpact>(); var frames=0
      val timings=mutableListOf<Double>(); val recent=ArrayDeque<Pair<Double,IntArray>>()
      val origin=SystemClock.elapsedRealtime()/1e3
      decode("known-bounces.mp4") { pixels,w,h,pts ->
        val time=origin+pts
        val start=SystemClock.elapsedRealtimeNanos(); val result=core.process(pixels,w,h,time)
        timings.add((SystemClock.elapsedRealtimeNanos()-start)/1e6)
        recent.addLast(time to pixels); while(recent.size>6) recent.removeFirst()
        result.impact?.let(events::add)
        val evidence=result.impact?.let { hit -> recent.minByOrNull { kotlin.math.abs(it.first-hit.time) }?.second }
        val frame=LocalFrame(pixels,w,h,time,result,timings.last(),30.0,30,key,evidence)
        scenario.onActivity { it.receiveFrame(frame) }; instrumentation.waitForIdleSync(); frames++
        if (result.impact!=null) Thread.sleep(320) // Sound cooldown uses real output time, not fixture PTS.
      }
      assertEquals(listOf(LineCall.IN,LineCall.OUT,LineCall.REVIEW),events.map { it.call })
      scenario.onActivity { activity ->
        assertEquals(before+3,activity.testSession().events.size)
        assertEquals(sounds+1,activity.testSoundCount())
        val saved=activity.testSession().events.takeLast(3)
        assertEquals(events.map { it.call },saved.map { it.call })
      }
      screenshot("02-decoded-fixture-in-out-review")
      var id=""
      scenario.onActivity { id=it.testSession().id }
      waitFor { LocalSessionStore(context).load(id)?.events?.size==before+3 }
      val restored=LocalSessionStore(context).load(id)!!
      assertEquals(listOf(LineCall.IN,LineCall.OUT,LineCall.REVIEW),restored.events.takeLast(3).map { it.call })
      assertTrue(File(context.filesDir,"local-evidence/$id/${restored.events.last().id}.jpg").length()>500)
      val sorted=timings.sorted()
      File(context.getExternalFilesDir(null),"offline-proof/pipeline.json").writeText(JSONObject()
        .put("source","synthetic H264 MP4 decoded by Android MediaCodec; no lens ground-truth claim")
        .put("frames",frames).put("calls",events.joinToString { it.call.name }).put("p50AnalysisMs",sorted[sorted.size/2])
        .put("p95AnalysisMs",sorted[(sorted.size*.95).toInt()]).put("outAudioEnqueued",1)
        .put("note","SoundPool accepted playback; human audibility on a physical phone not verified")
        .put("android",android.os.Build.VERSION.RELEASE).toString(2))
    }
  }

  private fun decode(asset: String,frame: (IntArray,Int,Int,Double)->Unit) {
    val extractor=MediaExtractor()
    val source=File(context.cacheDir,asset)
    instrumentation.context.assets.open(asset).use { input -> source.outputStream().use { input.copyTo(it) } }
    extractor.setDataSource(source.path)
    val track=(0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith("video/") }
    extractor.selectTrack(track); val format=extractor.getTrackFormat(track)
    format.setInteger(MediaFormat.KEY_COLOR_FORMAT,android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
    val codec=MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
    try {
      codec.configure(format,null,null,0); codec.start()
      val info=MediaCodec.BufferInfo(); var ended=false; var done=false; val timeout=SystemClock.elapsedRealtime()+60000
      while (!done && SystemClock.elapsedRealtime()<timeout) {
        if (!ended) {
          val index=codec.dequeueInputBuffer(10000)
          if (index>=0) {
            val buffer=codec.getInputBuffer(index)!!; val bytes=extractor.readSampleData(buffer,0)
            if (bytes<0) { codec.queueInputBuffer(index,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM); ended=true }
            else { codec.queueInputBuffer(index,0,bytes,extractor.sampleTime,0); extractor.advance() }
          }
        }
        val index=codec.dequeueOutputBuffer(info,10000)
        if (index>=0) {
          if (info.size>0) codec.getOutputImage(index)?.use { image -> val (pixels,w,h)=LocalCamera.convert(image,0); frame(pixels,w,h,info.presentationTimeUs/1e6) }
            ?: fail("Decoder did not produce a YUV image")
          done=info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM!=0
          codec.releaseOutputBuffer(index,false)
        }
      }
      assertTrue("Video decoder stalled",done)
    } finally { runCatching { codec.stop() }; codec.release(); extractor.release() }
  }
}
