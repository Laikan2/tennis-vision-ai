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
  @Test fun twoCloseFloorPointsCanBeCorrectedWithAThirdTapWithoutRestarting() {
    val v=view();v.begin(2)
    tap(v,CourtPoint(.48,.5));tap(v,CourtPoint(.52,.5))
    assertEquals(2,v.draft.size);assertFalse(LocalGeometry.validFloorLine(v.draft))
    assertTrue(floorBoundaryHint(v.draft).contains("horizontal"))
    tap(v,CourtPoint(.95,.5))
    assertEquals(2,v.draft.size);assertTrue(LocalGeometry.validFloorLine(v.draft))
    assertEquals(.48,v.draft[0].x,1e-6);assertTrue(floorBoundaryHint(v.draft).contains("Salvar área"))
  }
  @Test fun openingWallEditorUsesItsOwnAreaRatherThanTheLastFloorFrame() {
    val v=view()
    val floor=listOf(CourtPoint(.1,.6),CourtPoint(.9,.6),CourtPoint(.9,.95),CourtPoint(.1,.95))
    val wall=listOf(CourtPoint(.2,.1),CourtPoint(.8,.1),CourtPoint(.8,.5),CourtPoint(.2,.5))
    v.present(LocalFrame(IntArray(1280*720),1280,720,0.0,
      LocalResult(null,null,emptyList(),null,false,null,"Fixture",activeArea=LocalSettings(polygon=floor)),0.0,60.0,60,"fixture"))
    val selected=LocalSettings(polygon=wall,surface=ContactSurface.WALL)
    v.settings=selected;v.begin(1,selected)
    assertEquals(wall,v.draft)
    v.finish();assertEquals(wall,v.settings.polygon)
  }
  @Test fun delayedFrameCannotRestorePriorSurfaceOrRemoveAnotherSavedArea() {
    val floor=listOf(CourtPoint(.1,.6),CourtPoint(.9,.6),CourtPoint(.9,.95),CourtPoint(.1,.95))
    val wall=listOf(CourtPoint(.2,.1),CourtPoint(.8,.1),CourtPoint(.8,.5),CourtPoint(.2,.5))
    val current=LocalSettings(polygon=wall,surface=ContactSurface.WALL,floorPolygon=floor,wallPolygon=wall,floorCourt=CourtLayout.CUSTOM)
    val v=view();v.settings=current
    for(stale in listOf(LocalSettings(),current.copy(floorPolygon=null),current.selectingSurface(ContactSurface.FLOOR))) {
      v.present(LocalFrame(IntArray(1280*720),1280,720,0.0,
        LocalResult(null,null,emptyList(),null,false,null,"Old frame",activeArea=stale),0.0,60.0,60,"fixture"))
      assertEquals(current,v.visibleSettings)
      v.begin(1,v.visibleSettings.selectingSurface(ContactSurface.FLOOR))
      assertEquals(floor,v.draft);v.finish()
    }
  }
  @Test fun currentCameraTranslationIsRetainedWithoutRevertingSoundOrMarks() {
    val current=LocalSettings(polygon=listOf(CourtPoint(.1,.5),CourtPoint(.9,.5),CourtPoint(.9,.9),CourtPoint(.1,.9)),sound=false,marks=false).rememberingArea()
    val offset=CourtPoint(.03,-.02)
    val result=LocalResult(null,null,emptyList(),null,false,null,"Current frame",activeArea=current.translated(offset).copy(sound=true,marks=true),cameraOffset=offset)
    val v=view();v.settings=current;v.present(LocalFrame(IntArray(1280*720),1280,720,0.0,result,0.0,60.0,60,"fixture"))
    assertEquals(current.translated(offset),v.visibleSettings)
    assertEquals(offset,result.referencedTo(current).cameraOffset)
    assertFalse(current.acceptsFrameArea(result.activeArea,CourtPoint(Double.NaN,0.0)))
  }
  @Test fun staleAreaImpactAndConfirmationCannotAffectTheCurrentSession() {
    val current=LocalSettings(surface=ContactSurface.WALL)
    val hit=LocalImpact(1,1.0,CourtPoint(.2,.8),5.0,LineCall.OUT,null,1.0,"old floor",ContactSurface.FLOOR)
    val result=LocalResult(null,null,emptyList(),hit,false,null,"Old floor event",activeArea=LocalSettings(),cameraOffset=CourtPoint(.03,.02),areaNeedsConfirmation=true)
    val safe=result.referencedTo(current)
    assertNull(safe.impact);assertFalse(safe.areaNeedsConfirmation)
    assertEquals(current,safe.activeArea);assertEquals(CourtPoint(0.0,0.0),safe.cameraOffset)
  }
  @Test @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
  fun previewContourIsDrawnAroundTheObservedBallWithoutCoveringItsCentre() {
    val p=IntArray(640*360){0xff315372.toInt()}
    for(y in 174..186) for(x in 94..106) if(kotlin.math.hypot(x-100.0,y-180.0)<=6)p[y*640+x]=0xffc7eb4d.toInt()
    val result=BallPreview().process(p,640,360,0.0,LocalSettings())
    assertNotNull(result.observed);assertNull(result.impact)
    val v=LocalCourtView(RuntimeEnvironment.getApplication() as Context);v.layout(0,0,640,360)
    v.present(LocalFrame(p,640,360,0.0,result,0.0,60.0,60,"preview-fixture"))
    val image=android.graphics.Bitmap.createBitmap(640,360,android.graphics.Bitmap.Config.ARGB_8888)
    v.draw(android.graphics.Canvas(image))
    assertEquals("The outline must leave the actual ball centre visible",p[180*640+100],image.getPixel(100,180))
    assertNotEquals("The contour needs contrast outside the ball",p[180*640+108],image.getPixel(108,180))
    image.recycle()
  }
  @Test fun portraitAndLandscapeFramesUseTheirOwnRatioAndMapEveryCorner() {
    for((iw,ih) in listOf(720 to 1280,1280 to 720,1920 to 1080)) {
      val v=LocalCourtView(RuntimeEnvironment.getApplication() as Context)
      v.present(LocalFrame(IntArray(iw*ih){0xff315372.toInt()},iw,ih,0.0,
        LocalResult(null,null,emptyList(),null,false,null,"Fixture"),0.0,60.0,60,"ratio-$iw"))
      v.measure(android.view.View.MeasureSpec.makeMeasureSpec(500,android.view.View.MeasureSpec.AT_MOST),android.view.View.MeasureSpec.makeMeasureSpec(400,android.view.View.MeasureSpec.AT_MOST))
      v.layout(0,0,v.measuredWidth,v.measuredHeight)
      assertTrue(v.width<=500 && v.height<=400)
      assertEquals(iw.toDouble()/ih,v.width.toDouble()/v.height,.004)
      val origin=v.pointOnScreen(CourtPoint(0.0,0.0));val end=v.pointOnScreen(CourtPoint(1.0,1.0))
      assertEquals(0f,origin.x,.6f);assertEquals(0f,origin.y,.6f)
      assertEquals(v.width.toFloat(),end.x,.6f);assertEquals(v.height.toFloat(),end.y,.6f)
      v.begin(3);val p=CourtPoint(.8,.7);tap(v,p)
      assertEquals(p.x,v.draft.single().x,1e-6);assertEquals(p.y,v.draft.single().y,1e-6)
    }
  }
  @Test @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
  fun contactPulseAppearsAtMappedContactThenExpiresWithoutRemovingTheMark() {
    val v=LocalCourtView(RuntimeEnvironment.getApplication() as Context);v.layout(0,0,640,360)
    val background=0xff315372.toInt()
    v.present(LocalFrame(IntArray(640*360){background},640,360,0.0,
      LocalResult(null,null,emptyList(),null,false,null,"Fixture",activeArea=LocalSettings(),cameraOffset=CourtPoint(.04,.03)),0.0,60.0,60,"impact"))
    val hit=LocalImpact(1,1.0,CourtPoint(.25,.75),5.0,LineCall.OUT,30.0,1.0,"Known test contact",ContactSurface.FLOOR,cameraOffset=CourtPoint(.01,.02))
    v.events=listOf(hit)
    org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(240))
    val image=android.graphics.Bitmap.createBitmap(640,360,android.graphics.Bitmap.Config.ARGB_8888)
    v.draw(android.graphics.Canvas(image))
    val centre=v.pointOnScreen(LocalGeometry.impactPoint(hit,CourtPoint(.04,.03)))
    val x=(centre.x+20).toInt();val y=centre.y.toInt()
    assertNotEquals("An arrival pulse must be visible at the same camera-offset contact coordinate",background,image.getPixel(x,y))
    org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(400))
    v.draw(android.graphics.Canvas(image))
    assertEquals("The pulse is transient",background,image.getPixel(x,y))
    assertNotEquals("The contact ring remains after the pulse",background,image.getPixel((centre.x+7).toInt(),y))
    image.recycle()
  }
}
