package ai.tennisvision.camera

import org.junit.Assert.*
import org.junit.Test

class HomeAreaTest {
  private val floor=listOf(CourtPoint(.1,.6),CourtPoint(.9,.6),CourtPoint(.9,.95),CourtPoint(.1,.95))
  private val wall=listOf(CourtPoint(.2,.1),CourtPoint(.8,.1),CourtPoint(.8,.5),CourtPoint(.2,.5))
  private val boundary=listOf(CourtPoint(.05,.55),CourtPoint(.95,.55))
  @Test fun floorRequiresBothValidSteps() {
    val setup=HomeAreaSetup(LocalSettings(),ContactSurface.FLOOR)
    assertFalse(setup.accept(floor.take(2)));assertNull(setup.completedSettings)
    assertTrue(setup.accept(floor));assertEquals(HomeAreaSetup.Stage.FLOOR_BOUNDARY,setup.stage);assertNull(setup.completedSettings)
    assertFalse(setup.accept(listOf(boundary[0],boundary[0])));assertNull(setup.completedSettings)
    assertTrue(setup.accept(boundary));assertEquals(floor,setup.completedSettings!!.polygon)
    assertEquals(boundary,setup.completedSettings!!.floorLine);assertFalse(setup.accept(boundary))
  }
  @Test fun wallPreservesFloorAndBoundary() {
    val original=LocalSettings(polygon=floor,floorLine=boundary)
    val setup=HomeAreaSetup(original,ContactSurface.WALL);assertTrue(setup.accept(wall))
    val saved=setup.completedSettings!!
    assertEquals(wall,saved.polygon);assertEquals(boundary,saved.floorLine)
    assertEquals(floor,saved.selectingSurface(ContactSurface.FLOOR).polygon)
    assertEquals(wall,saved.selectingSurface(ContactSurface.FLOOR).selectingSurface(ContactSurface.WALL).polygon)
    assertNull(original.wallPolygon)
  }
  @Test fun cancelledFloorDraftCannotReplaceWall() {
    val original=LocalSettings(polygon=wall,surface=ContactSurface.WALL)
    val setup=HomeAreaSetup(original,ContactSurface.FLOOR);assertTrue(setup.accept(floor))
    assertNull(setup.completedSettings);assertEquals(wall,original.polygon);assertNull(original.floorPolygon)
  }
  @Test fun courtAndTrackingRoundTripKeepBothProfiles() {
    val original=LocalSettings(polygon=floor,floorLine=boundary,court=CourtLayout.HALF)
    val setup=HomeAreaSetup(original,ContactSurface.WALL);assertTrue(setup.accept(wall))
    val track=setup.completedSettings!!.selectingSurface(ContactSurface.TRACK)
    assertEquals(CourtLayout.HALF,track.selectingSurface(ContactSurface.FLOOR).court)
    assertEquals(floor,track.selectingSurface(ContactSurface.FLOOR).polygon)
    assertEquals(wall,track.selectingSurface(ContactSurface.WALL).polygon)
  }
  @Test fun legacyWallKeepsItsAreaAcrossSwitching() {
    val original=LocalSettings(polygon=wall,surface=ContactSurface.WALL)
    assertEquals(wall,original.selectingSurface(ContactSurface.FLOOR).selectingSurface(ContactSurface.WALL).polygon)
  }
  @Test fun translationsMoveBothAreasAndBoundary() {
    val setup=HomeAreaSetup(LocalSettings(polygon=floor,floorLine=boundary),ContactSurface.WALL);assertTrue(setup.accept(wall))
    val shifted=setup.completedSettings!!.translated(CourtPoint(.01,-.02))
    assertEquals(.21,shifted.polygon[0].x,1e-8)
    assertEquals(.58,shifted.selectingSurface(ContactSurface.FLOOR).polygon[0].y,1e-8)
    assertEquals(.53,shifted.floorLine[0].y,1e-8)
  }
}
