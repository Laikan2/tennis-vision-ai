package ai.tennisvision.camera

import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[33])
class LocalPersistenceTest {
  @Test fun floorAndWallRemainSeparateAfterReopening() {
    val context=RuntimeEnvironment.getApplication() as Context
    val floor=listOf(CourtPoint(.1,.6),CourtPoint(.9,.6),CourtPoint(.9,.95),CourtPoint(.1,.95))
    val wall=listOf(CourtPoint(.2,.1),CourtPoint(.8,.1),CourtPoint(.8,.5),CourtPoint(.2,.5))
    val line=listOf(CourtPoint(.05,.55),CourtPoint(.95,.55))
    val setup=HomeAreaSetup(LocalSettings(polygon=floor,floorLine=line),ContactSurface.WALL)
    assertTrue(setup.accept(wall))
    val saved=setup.completedSettings!!;LocalSessionStore(context).saveSettings("home-camera",saved)
    val reopened=LocalSessionStore(context).settings("home-camera")
    assertEquals(saved,reopened);assertEquals(floor,reopened.selectingSurface(ContactSurface.FLOOR).polygon)
    assertEquals(wall,reopened.polygon);assertEquals(line,reopened.floorLine)
  }
  @Test fun legacyAreaJsonIsReadWithoutNewSurfaceFields() {
    val context=RuntimeEnvironment.getApplication() as Context
    context.getSharedPreferences("standalone-settings-v1",0).edit().putString("area:legacy",
      """{"polygon":[[0.2,0.1],[0.8,0.1],[0.8,0.5],[0.2,0.5]],"floor":[],"surface":"WALL","court":"CUSTOM"}""").commit()
    val old=LocalSessionStore(context).settings("legacy")
    assertEquals(ContactSurface.WALL,old.surface);assertEquals(4,old.polygon.size);assertNull(old.floorPolygon)
    assertEquals(old.polygon,old.selectingSurface(ContactSurface.FLOOR).selectingSurface(ContactSurface.WALL).polygon)
  }
  private val context get()=RuntimeEnvironment.getApplication() as Context
  @Test fun independentCameraAreasAndPairedPcSettingsSurviveLocalSessions() {
    context.getSharedPreferences("pairing",0).edit().putString("host","192.168.1.55").commit()
    val store=LocalSessionStore(context)
    val area=listOf(CourtPoint(.1,.2),CourtPoint(.8,.2),CourtPoint(.8,.9),CourtPoint(.1,.9))
    val settings=LocalSettings(area,listOf(CourtPoint(.05,.15),CourtPoint(.95,.15)),sound=false)
    store.saveSettings("back:landscape",settings)
    store.saveSettings("front:portrait",LocalSettings(surface=ContactSurface.TRACK,sound=false))
    val event=LocalImpact(1,2.0,CourtPoint(.4,.8),6.0,LineCall.REVIEW,null,3.0,"Test",ContactSurface.FLOOR,LineCall.IN,
      cameraOffset=CourtPoint(.003,-.002),evidenceOffset=CourtPoint(.001,-.001))
    val session=LocalSession(events=listOf(event)); store.save(session)
    val reopened=LocalSessionStore(context)
    assertEquals(settings,reopened.settings("back:landscape"))
    assertEquals(ContactSurface.TRACK,reopened.settings("front:portrait").surface)
    assertEquals(session,reopened.current())
    assertEquals("192.168.1.55",context.getSharedPreferences("pairing",0).getString("host",null))
    assertEquals(LineCall.REVIEW,reopened.current().events.single().call)
    assertEquals(LineCall.IN,reopened.current().events.single().decision)
  }
  @Test fun corruptSessionIsReportedAndOriginalFileIsPreserved() {
    val store=LocalSessionStore(context); val session=LocalSession(); store.save(session)
    val file=File(context.filesDir,"local-sessions/${session.id}.json"); file.writeText("broken{")
    val reopened=LocalSessionStore(context)
    assertNotEquals(session.id,reopened.current().id); assertNotNull(reopened.warning)
    assertEquals("broken{",file.readText())
  }
  @Test fun courtTemplateAndAreasSurviveWithoutOverwritingLegacyOrOtherLenses() {
    val store=LocalSessionStore(context)
    val points=listOf(CourtPoint(.3,.15),CourtPoint(.7,.15),CourtPoint(.94,.92),CourtPoint(.06,.92))
    val old=LocalSettings(points,listOf(CourtPoint(.05,.1),CourtPoint(.95,.1)))
    store.saveSettings("0:1280x720:90",old)
    val court=old.copy(court=CourtLayout.FULL,floorLine=emptyList())
    store.saveSettings("0:1920x1080:90",court)
    store.saveSettings("0:1920x1080:90:zoom0.6",court.copy(court=CourtLayout.HALF))
    val reopened=LocalSessionStore(context)
    assertEquals(old,reopened.settings("0:1280x720:90"))
    assertEquals(court,reopened.settings("0:1920x1080:90"))
    assertEquals(CourtLayout.HALF,reopened.settings("0:1920x1080:90:zoom0.6").court)
  }
}
