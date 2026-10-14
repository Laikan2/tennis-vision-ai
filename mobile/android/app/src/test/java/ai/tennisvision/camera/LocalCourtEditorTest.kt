package ai.tennisvision.camera

import android.content.Context
import android.view.MotionEvent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[33])
class LocalCourtEditorTest {
  private fun view(): LocalCourtView {
    val v=LocalCourtView(RuntimeEnvironment.getApplication() as Context)
    v.layout(0,0,1280,720)
    v.present(LocalFrame(IntArray(1280*720) { 0xff315372.toInt() },1280,720,0.0,
      LocalResult(null,null,emptyList(),null,false,null,"Fixture"),0.0,60.0,60,"fixture"))
    return v
  }
  private fun touch(v: LocalCourtView,p: CourtPoint,action: Int) {
    val screen=v.pointOnScreen(p)
    MotionEvent.obtain(0,20,action,screen.x,screen.y,0).also { v.onTouchEvent(it);it.recycle() }
  }
  private fun tap(v: LocalCourtView,p: CourtPoint) { touch(v,p,MotionEvent.ACTION_DOWN);touch(v,p,MotionEvent.ACTION_UP) }
  @Test fun fourTapsCreateAQuadrilateralNotASingleFloorLine() {
    val v=view();v.editorLayout=CourtLayout.FULL;v.begin(3)
    var valid=false;v.onDraft={valid=it}
    val points=listOf(CourtPoint(.3,.15),CourtPoint(.7,.15),CourtPoint(.94,.92),CourtPoint(.06,.92))
    points.forEachIndexed { i,p -> tap(v,p);assertEquals(i+1,v.draft.size);assertEquals(i==3,valid) }
    v.draft.zip(points).forEach { (a,b)->assertEquals(b.x,a.x,1e-6);assertEquals(b.y,a.y,1e-6) }
    assertEquals(6,CourtProjection.guides(v.draft,CourtLayout.FULL).size)
  }
  @Test fun farCornersCanBePlacedCloseTogetherBeforeHandleEditingStarts() {
    val v=view();v.begin(3)
    tap(v,CourtPoint(.49,.2));tap(v,CourtPoint(.51,.2))
    assertEquals("A nearby second tap is a second corner, not a drag of the first",2,v.draft.size)
  }
  @Test fun draggingACornerUsesReleaseCoordinateAndCancelPreservesSavedArea() {
    val v=view();val original=listOf(CourtPoint(.2,.2),CourtPoint(.8,.2),CourtPoint(.8,.9),CourtPoint(.2,.9))
    v.settings=LocalSettings(polygon=original,court=CourtLayout.FULL);v.begin(3)
    touch(v,original[0],MotionEvent.ACTION_DOWN)
    touch(v,CourtPoint(.23,.24),MotionEvent.ACTION_MOVE)
    touch(v,CourtPoint(.25,.26),MotionEvent.ACTION_UP)
    assertEquals(.25,v.draft[0].x,1e-6);assertEquals(.26,v.draft[0].y,1e-6)
    v.finish();assertEquals(original,v.settings.polygon)
  }
  @Test fun oldRoomDragAndFloorLineStillWork() {
    val v=view();v.begin(1)
    touch(v,CourtPoint(.2,.2),MotionEvent.ACTION_DOWN);touch(v,CourtPoint(.8,.9),MotionEvent.ACTION_UP)
    assertTrue(LocalGeometry.valid(v.draft))
    v.finish();v.begin(2)
    tap(v,CourtPoint(.05,.3));tap(v,CourtPoint(.95,.3))
    assertTrue(LocalGeometry.validFloorLine(v.draft))
    assertEquals(2,v.draft.size)
  }
}
