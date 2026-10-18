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
  @Test fun floorAndWallRemainSeparateOnlyWithinTheCurrentSession() {
    val context=RuntimeEnvironment.getApplication() as Context
    val floor=listOf(CourtPoint(.1,.6),CourtPoint(.9,.6),CourtPoint(.9,.95),CourtPoint(.1,.95))
    val wall=listOf(CourtPoint(.2,.1),CourtPoint(.8,.1),CourtPoint(.8,.5),CourtPoint(.2,.5))
    val line=listOf(CourtPoint(.05,.55),CourtPoint(.95,.55))
    val setup=HomeAreaSetup(LocalSettings(polygon=floor,floorLine=line),ContactSurface.WALL)
    assertTrue(setup.accept(wall))
    val saved=setup.completedSettings!!;val store=LocalSessionStore(context);store.saveSettings("home-camera",saved)
    val current=store.settings("home-camera")
    assertEquals(saved,current);assertEquals(floor,current.selectingSurface(ContactSurface.FLOOR).polygon)
    assertEquals(wall,current.polygon);assertEquals(line,current.floorLine)
    val reopened=LocalSessionStore(context).settings("home-camera")
    assertTrue(reopened.polygon.isEmpty());assertTrue(reopened.floorLine.isEmpty())
    assertTrue(reopened.selectingSurface(ContactSurface.FLOOR).polygon.isEmpty())
    assertTrue(reopened.selectingSurface(ContactSurface.WALL).polygon.isEmpty())
  }
  @Test fun legacyGeometryIsRemovedButItsSurfaceAndUnrelatedPreferencesSurvive() {
    val context=RuntimeEnvironment.getApplication() as Context
    context.getSharedPreferences("standalone-settings-v1",0).edit().putString("area:legacy",
      """{"polygon":[[0.2,0.1],[0.8,0.1],[0.8,0.5],[0.2,0.5]],"floor":[],"surface":"WALL","court":"CUSTOM"}""").commit()
    val old=LocalSessionStore(context).settings("legacy")
    assertEquals(ContactSurface.WALL,old.surface);assertTrue(old.polygon.isEmpty());assertNull(old.floorPolygon)
    assertFalse(context.getSharedPreferences("standalone-settings-v1",0).contains("area:legacy"))
  }
  private val context get()=RuntimeEnvironment.getApplication() as Context
  @Test fun processRestartClearsCameraAreasButPreservesHistoryPreferencesAndPairing() {
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
    assertEquals(settings,store.settings("back:landscape"))
    assertTrue(reopened.settings("back:landscape").polygon.isEmpty())
    assertTrue(reopened.settings("back:landscape").floorLine.isEmpty())
    assertFalse(reopened.settings("back:landscape").sound)
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
  @Test fun cameraFormatsRemainSeparateUntilAnExplicitNewSessionClearsAllOfThem() {
    val store=LocalSessionStore(context)
    val points=listOf(CourtPoint(.3,.15),CourtPoint(.7,.15),CourtPoint(.94,.92),CourtPoint(.06,.92))
    val old=LocalSettings(points,listOf(CourtPoint(.05,.1),CourtPoint(.95,.1)))
    store.saveSettings("0:1280x720:90",old)
    val court=old.copy(court=CourtLayout.FULL,floorLine=emptyList())
    store.saveSettings("0:1920x1080:90",court)
    store.saveSettings("0:1920x1080:90:zoom0.6",court.copy(court=CourtLayout.HALF))
    assertEquals(old,store.settings("0:1280x720:90"))
    assertEquals(court,store.settings("0:1920x1080:90"))
    assertEquals(CourtLayout.HALF,store.settings("0:1920x1080:90:zoom0.6").court)
    store.clearAreas()
    for(key in listOf("0:1280x720:90","0:1920x1080:90","0:1920x1080:90:zoom0.6")) {
      val clean=store.settings(key)
      assertTrue(clean.polygon.isEmpty());assertTrue(clean.floorLine.isEmpty())
      assertNull(clean.floorPolygon);assertNull(clean.wallPolygon);assertNull(clean.floorCourt)
      assertEquals(CourtLayout.CUSTOM,clean.court)
    }
  }
  @Test fun noGeometryIsEverWrittenToPreferencesAndResetKeepsAudioMarksAndCapture() {
    val prefs=context.getSharedPreferences("standalone-settings-v1",0)
    prefs.edit().putString("capture-profile","DETAIL").putString("local-lens","camera2").putBoolean("sound-in",true).commit()
    val store=LocalSessionStore(context)
    val polygon=listOf(CourtPoint(.1,.1),CourtPoint(.9,.1),CourtPoint(.9,.9),CourtPoint(.1,.9))
    store.saveSettings("camera",LocalSettings(polygon=polygon,sound=false,marks=false,surface=ContactSurface.WALL,wallPolygon=polygon))
    assertTrue(prefs.all.keys.none{it.startsWith("area:")})
    store.clearAreas()
    val clean=store.settings("camera")
    assertFalse(clean.sound);assertFalse(clean.marks);assertEquals(ContactSurface.WALL,clean.surface)
    assertEquals("DETAIL",prefs.getString("capture-profile",null));assertEquals("camera2",prefs.getString("local-lens",null));assertTrue(prefs.getBoolean("sound-in",false))
  }
}
