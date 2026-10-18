package ai.tennisvision.camera

import android.graphics.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[33])
class LocalImpactLabelTest {
  @Test fun adjacentInOutAndReviewLabelsFitWithoutCoveringContactCircles() {
    val viewport=RectF(2f,2f,318f,238f)
    val anchors=listOf(PointF(230f,172f),PointF(276f,172f),PointF(140f,172f))
    val markers=anchors.map { RectF(it.x-9,it.y-9,it.x+9,it.y+9) }
    val occupied=markers.toMutableList();val boxes=mutableListOf<RectF>()
    for((i,p) in anchors.withIndex()) {
      val box=LocalOverlayLayout.place(viewport,if(i==0)145f else 115f,18f,p.x+7,p.y-22,3f,occupied)
      assertNotNull("Every known test contact should retain a readable label",box)
      assertTrue(viewport.contains(box!!))
      assertTrue(occupied.none { RectF.intersects(it,box) })
      occupied.add(box);boxes.add(box)
    }
    assertEquals(3,boxes.size)
  }
  @Test fun fullViewportRetainsNewestLabelAndOmitsOlderText() {
    val viewport=RectF(0f,0f,120f,20f)
    val newest=LocalOverlayLayout.place(viewport,116f,18f,118f,0f,3f,emptyList())
    assertNotNull(newest);assertTrue(viewport.contains(newest!!))
    assertNull(LocalOverlayLayout.place(viewport,116f,18f,0f,0f,3f,listOf(newest)))
    assertNull(LocalOverlayLayout.place(viewport,121f,18f,0f,0f,3f,emptyList()))
  }
  @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
  fun clusteredImpactCalloutsRenderAndLeaveEveryContactTouchable() {
    val view=LocalCourtView(RuntimeEnvironment.getApplication());view.layout(0,0,320,240)
    view.present(LocalFrame(IntArray(320*240){0xff1e2833.toInt()},320,240,0.0,
      LocalResult(null,null,emptyList(),null,false,null,"Fixture",activeArea=LocalSettings()),0.0,30.0,30,"labels"))
    val events=listOf(
      LocalImpact(1,1.0,CourtPoint(.44,.71),4.0,LineCall.IN,20.0,1.0,"Known inside",ContactSurface.FLOOR),
      LocalImpact(2,2.0,CourtPoint(.86,.71),4.0,LineCall.OUT,28.0,1.0,"Known outside",ContactSurface.FLOOR),
      LocalImpact(3,3.0,CourtPoint(.72,.71),4.0,LineCall.REVIEW,12.0,3.0,"Known uncertainty",ContactSurface.FLOOR))
    view.events=events
    val bitmap=Bitmap.createBitmap(320,240,Bitmap.Config.ARGB_8888);view.draw(Canvas(bitmap))
    val folder=java.io.File("build/reports/court-interface-proof").apply{mkdirs()}
    java.io.File(folder,"clustered-impact-labels.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
    var touched:Long?=null;view.onImpact={touched=it}
    for(event in events) {
      val p=view.pointOnScreen(event.point)
      val touch=android.view.MotionEvent.obtain(0,20,android.view.MotionEvent.ACTION_UP,p.x,p.y,0)
      view.onTouchEvent(touch);touch.recycle()
      assertEquals(event.id,touched)
      assertNotEquals("Contact ring remains on its exact point",0xff1e2833.toInt(),bitmap.getPixel((p.x+7).toInt(),p.y.toInt()))
    }
    bitmap.recycle()
  }
}
