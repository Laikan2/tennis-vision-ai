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
  private fun fixture(): LocalFrame {
    val image=Bitmap.createBitmap(1280,720,Bitmap.Config.ARGB_8888)
    val canvas=Canvas(image);canvas.drawColor(0xff285c67.toInt())
    val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.WHITE;style=Paint.Style.STROKE;strokeWidth=3f }
    fun line(points: List<CourtPoint>) { val path=Path();points.forEachIndexed { i,p->if(i==0)path.moveTo((p.x*1280).toFloat(),(p.y*720).toFloat())else path.lineTo((p.x*1280).toFloat(),(p.y*720).toFloat()) };canvas.drawPath(path,paint) }
    line(area+area.first());CourtProjection.guides(area,CourtLayout.FULL).forEach(::line)
    val pixels=IntArray(1280*720);image.getPixels(pixels,0,1280,0,0,1280,720);image.recycle()
    return LocalFrame(pixels,1280,720,1.0,LocalResult(null,null,emptyList(),null,false,null,"Imagem sintética · teste de interface"),0.0,0.0,60,"ui-fixture")
  }
  private fun draw(activity: LocalTrainingActivity,name: String,width: Int,height: Int) {
    val root=activity.findViewById<View>(R.id.localRoot)
    root.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));root.layout(0,0,width,height)
    val bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);root.draw(Canvas(bitmap))
    val folder=File("build/reports/court-interface-proof").apply { mkdirs() }
    File(folder,"$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
  }
  private fun check(width: Int,height: Int,name: String) {
    val controller=Robolectric.buildActivity(LocalTrainingActivity::class.java).create().start().resume().visible()
    val activity=controller.get();shadowOf(Looper.getMainLooper()).idle()
    activity.receiveFrame(fixture());shadowOf(Looper.getMainLooper()).idle()
    activity.findViewById<LocalCourtView>(R.id.localVideo).settings=LocalSettings(polygon=area,court=CourtLayout.FULL)
    activity.findViewById<TextView>(R.id.localStatus).text="Imagem sintética · teste de interface"
    draw(activity,"$name-live",width,height)
    activity.findViewById<Button>(R.id.localCourt).performClick()
    shadowOf(Looper.getMainLooper()).idle()
    val dialog=ShadowDialog.getLatestDialog() as? androidx.appcompat.app.AlertDialog
    assertNotNull(dialog)
    dialog!!.listView.performItemClick(null,0,0)
    shadowOf(Looper.getMainLooper()).idle()
    draw(activity,"$name-editor",width,height)
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
}
