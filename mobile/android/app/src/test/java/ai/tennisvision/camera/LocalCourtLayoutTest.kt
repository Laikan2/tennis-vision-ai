package ai.tennisvision.camera

import android.graphics.*
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowDialog
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[33],qualifiers="w960dp-h432dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class LocalCourtLayoutTest {
  private val area=listOf(CourtPoint(.30,.15),CourtPoint(.70,.15),CourtPoint(.94,.92),CourtPoint(.06,.92))
  private fun fixture(portraitSource:Boolean=false): LocalFrame {
    val w=if(portraitSource)720 else 1280;val h=if(portraitSource)1280 else 720
    val image=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
    val canvas=Canvas(image);canvas.drawColor(0xff285c67.toInt())
    val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.WHITE;style=Paint.Style.STROKE;strokeWidth=3f }
    fun line(points: List<CourtPoint>) { val path=Path();points.forEachIndexed { i,p->if(i==0)path.moveTo((p.x*w).toFloat(),(p.y*h).toFloat())else path.lineTo((p.x*w).toFloat(),(p.y*h).toFloat()) };canvas.drawPath(path,paint) }
    line(area+area.first());CourtProjection.guides(area,CourtLayout.FULL).forEach(::line)
    val pixels=IntArray(w*h);image.getPixels(pixels,0,w,0,0,w,h);image.recycle()
    return LocalFrame(pixels,w,h,1.0,LocalResult(null,null,emptyList(),null,false,null,"Imagem sintética · teste de interface"),0.0,0.0,60,"ui-fixture")
  }
  private fun draw(activity: LocalTrainingActivity,name: String,width: Int,height: Int) {
    val root=activity.findViewById<View>(R.id.localRoot)
    root.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));root.layout(0,0,width,height)
    val bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);root.draw(Canvas(bitmap))
    val folder=File("build/reports/court-interface-proof").apply { mkdirs() }
    File(folder,"$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
  }
  private fun check(width: Int,height: Int,name: String,portraitSource:Boolean=false) {
    val controller=Robolectric.buildActivity(LocalTrainingActivity::class.java).create().start().resume().visible()
    val activity=controller.get();shadowOf(Looper.getMainLooper()).idle()
    activity.receiveFrame(fixture(portraitSource));shadowOf(Looper.getMainLooper()).idle()
    activity.findViewById<LocalCourtView>(R.id.localVideo).settings=LocalSettings(polygon=area,court=CourtLayout.FULL)
    activity.findViewById<TextView>(R.id.localStatus).text="Imagem sintética · teste de interface"
    draw(activity,"$name-live",width,height)
    val panel=activity.findViewById<View>(R.id.localVideoPanel);val video=activity.findViewById<LocalCourtView>(R.id.localVideo)
    assertTrue("No unused dark block below the live image",panel.height-video.bottom<=panel.paddingBottom+2)
    assertTrue("Preview stays inside its panel horizontally: ${video.left}..${video.right} / ${panel.width}",video.left>=0 && video.right<=panel.width)
    assertEquals(if(portraitSource)720.0/1280 else 1280.0/720,video.width.toDouble()/video.height,.01)
    activity.findViewById<Button>(R.id.localCourt).performClick()
    shadowOf(Looper.getMainLooper()).idle()
    val dialog=ShadowDialog.getLatestDialog() as? androidx.appcompat.app.AlertDialog
    assertNotNull(dialog)
    dialog!!.listView.performItemClick(null,0,0)
    shadowOf(Looper.getMainLooper()).idle()
    draw(activity,"$name-editor",width,height)
    val editor=activity.findViewById<View>(R.id.localEditor)
    assertTrue("The panel ends immediately after its editor actions",panel.height-editor.bottom<=panel.paddingBottom+2)
    assertTrue("Editor preview stays inside its panel horizontally: ${video.left}..${video.right} / ${panel.width}",video.left>=0 && video.right<=panel.width)
    assertEquals(View.GONE,activity.findViewById<View>(R.id.settingsScroll).visibility)
    assertEquals(View.VISIBLE,activity.findViewById<View>(R.id.localEditor).visibility)
    for(id in listOf(R.id.localSaveArea,R.id.localRedraw,R.id.localCancelArea)) {
      val bounds=Rect();assertTrue(activity.findViewById<View>(id).getGlobalVisibleRect(bounds));assertTrue(bounds.top>=0 && bounds.bottom<=height)
    }
    assertEquals(3,activity.findViewById<LocalCourtView>(R.id.localVideo).editing)
    activity.findViewById<Button>(R.id.localCancelArea).performClick();shadowOf(Looper.getMainLooper()).idle()
    assertEquals(View.GONE,activity.findViewById<View>(R.id.localEditor).visibility)
    assertEquals(View.VISIBLE,activity.findViewById<View>(R.id.settingsScroll).visibility)
    controller.pause().stop().destroy()
  }
  @Test fun landscapeEditorKeepsAllActionsOnScreen() { check(1920,864,"landscape") }
  @Test @Config(qualifiers="w412dp-h892dp-port-xhdpi") fun portraitEditorKeepsAllActionsOnScreen() { check(824,1784,"portrait") }
  @Test fun portraitCameraInLandscapeKeepsItsFullImageAndEditorActions() { check(1920,864,"landscape-portrait-camera",true) }
  @Test @Config(qualifiers="w412dp-h892dp-port-xhdpi") fun portraitCameraInPortraitKeepsSettingsAndEditorActions() { check(824,1784,"portrait-portrait-camera",true) }
  @Test fun distantBallGuideLetsTheUserSaveDetailWithoutChangingTheArea() {
    val controller=Robolectric.buildActivity(LocalTrainingActivity::class.java).create().start().resume().visible()
    val activity=controller.get();shadowOf(Looper.getMainLooper()).idle()
    activity.receiveFrame(fixture());shadowOf(Looper.getMainLooper()).idle()
    val court=activity.findViewById<LocalCourtView>(R.id.localVideo)
    val saved=LocalSettings(polygon=area,court=CourtLayout.FULL);court.settings=saved
    activity.findViewById<Button>(R.id.localFraming).performClick();shadowOf(Looper.getMainLooper()).idle()
    val framing=ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
    assertEquals("Melhorar bola distante",framing.listView.adapter.getItem(3).toString())
    framing.listView.performItemClick(null,3,3);shadowOf(Looper.getMainLooper()).idle()
    val guide=ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
    assertTrue(guide.findViewById<TextView>(android.R.id.message)!!.text.toString().contains("pixels"))
    guide.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).performClick()
    shadowOf(Looper.getMainLooper()).idle()
    assertEquals("DETAIL",activity.getSharedPreferences("standalone-settings-v1",0).getString("capture-profile",null))
    assertEquals(saved,court.settings)
    controller.pause().stop().destroy()
  }
  @Test fun cancellingTheSecondFloorStepKeepsWallModeAndNamesItClearly() {
    org.robolectric.RuntimeEnvironment.getApplication().getSharedPreferences("standalone-settings-v1",0)
      .edit().putString("surface:ui-fixture",ContactSurface.WALL.name).commit()
    val controller=Robolectric.buildActivity(LocalTrainingActivity::class.java).create().start().resume().visible()
    val activity=controller.get();shadowOf(Looper.getMainLooper()).idle()
    activity.receiveFrame(fixture());shadowOf(Looper.getMainLooper()).idle()
    draw(activity,"cancel-home-before",1920,864)
    activity.findViewById<Button>(R.id.localArea).performClick()
    val court=activity.findViewById<LocalCourtView>(R.id.localVideo)
    fun touch(p:CourtPoint,action:Int){val s=court.pointOnScreen(p);android.view.MotionEvent.obtain(0,20,action,s.x,s.y,0).also{court.onTouchEvent(it);it.recycle()}}
    touch(CourtPoint(.1,.6),android.view.MotionEvent.ACTION_DOWN)
    touch(CourtPoint(.9,.95),android.view.MotionEvent.ACTION_UP)
    activity.findViewById<Button>(R.id.localSaveArea).performClick()
    assertEquals("The floor boundary is a separate unfinished step",2,court.editing)
    activity.findViewById<Button>(R.id.localCancelArea).performClick()
    assertEquals(ContactSurface.WALL,activity.testConfig().surface)
    assertTrue(activity.testConfig().polygon.isEmpty())
    assertTrue(activity.findViewById<TextView>(R.id.localStatus).text.contains("modo parede mantido"))
    assertTrue(activity.findViewById<TextView>(R.id.localSurface).text.startsWith("PAREDE"))
    controller.pause().stop().destroy()
  }
  @Test fun reviewingHistoryAfterReferenceResetDoesNotRestoreOldContactMarks() {
    val controller=Robolectric.buildActivity(LocalTrainingActivity::class.java).create().start().resume().visible()
    val activity=controller.get();shadowOf(Looper.getMainLooper()).idle()
    activity.receiveFrame(fixture());shadowOf(Looper.getMainLooper()).idle()
    val old=LocalImpact(1,1.0,CourtPoint(.2,.8),5.0,LineCall.REVIEW,null,4.0,"Previous camera reference",ContactSurface.FLOOR)
    LocalTrainingActivity::class.java.getDeclaredField("session").apply { isAccessible=true }.set(activity,LocalSession(events=listOf(old)))
    val court=activity.findViewById<LocalCourtView>(R.id.localVideo);court.events=listOf(old);court.resetReference()
    activity.findViewById<Button>(R.id.localReview).apply{isEnabled=true;performClick()}
    val dialog=ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
    dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).performClick()
    shadowOf(Looper.getMainLooper()).idle()
    assertEquals(LineCall.IN,activity.testSession().events.single().decision)
    assertTrue("Review must not resurrect marks in the wrong camera coordinate system",court.events.isEmpty())
    controller.pause().stop().destroy()
  }
  @Test fun lateEmptyPreviewAfterSavingFloorAndWallCannotEmptyTheFloorEditor() {
    val controller=Robolectric.buildActivity(LocalTrainingActivity::class.java).create().start().resume().visible()
    val activity=controller.get();shadowOf(Looper.getMainLooper()).idle()
    activity.receiveFrame(fixture());shadowOf(Looper.getMainLooper()).idle();draw(activity,"late-frame-before",1920,864)
    val court=activity.findViewById<LocalCourtView>(R.id.localVideo)
    fun click(id:Int){activity.findViewById<Button>(id).performClick();shadowOf(Looper.getMainLooper()).idle()}
    fun touch(p:CourtPoint,action:Int){val s=court.pointOnScreen(p);android.view.MotionEvent.obtain(0,20,action,s.x,s.y,0).also{court.onTouchEvent(it);it.recycle()}}
    fun tap(p:CourtPoint){touch(p,android.view.MotionEvent.ACTION_DOWN);touch(p,android.view.MotionEvent.ACTION_UP)}
    click(R.id.localArea);click(R.id.localRedraw)
    touch(CourtPoint(.1,.6),android.view.MotionEvent.ACTION_DOWN);touch(CourtPoint(.9,.95),android.view.MotionEvent.ACTION_UP)
    click(R.id.localSaveArea);assertEquals(2,court.editing)
    tap(CourtPoint(.05,.4));tap(CourtPoint(.95,.4));click(R.id.localSaveArea)
    val floor=activity.testConfig();assertEquals(4,floor.polygon.size)
    click(R.id.localWall);click(R.id.localRedraw)
    touch(CourtPoint(.2,.1),android.view.MotionEvent.ACTION_DOWN);touch(CourtPoint(.8,.5),android.view.MotionEvent.ACTION_UP)
    click(R.id.localSaveArea)
    val wall=activity.testConfig();assertEquals(floor.polygon,wall.floorPolygon)
    // This frame was processed before either Camera.update reached the camera thread.
    activity.receiveFrame(fixture().copy(result=LocalResult(null,null,emptyList(),null,false,null,"Delayed initial frame",activeArea=LocalSettings())))
    shadowOf(Looper.getMainLooper()).idle()
    click(R.id.localArea);assertEquals(1,court.editing);assertEquals(floor.polygon,court.draft)
    click(R.id.localSaveArea);assertEquals(floor.floorLine,court.draft)
    click(R.id.localCancelArea);assertEquals(wall,activity.testConfig())
    assertEquals(wall,court.visibleSettings)
    controller.pause().stop().destroy()
  }
}
