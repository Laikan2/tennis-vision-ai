package ai.tennisvision.camera

import org.junit.Assert.*
import org.junit.Test

class HighSpeedProfilesTest {
  @Test fun cameraAndEncoderMustBothSupportAFixedRate() {
    val modes = listOf(
      HighSpeedMode(1920, 1080, 120, 120),
      HighSpeedMode(1920, 1080, 240, 240),
      HighSpeedMode(1280, 720, 240, 240),
      HighSpeedMode(1920, 1080, 30, 240),
      HighSpeedMode(3840, 2160, 240, 240),
    )
    val profiles = ProfileCatalog.highSpeedProfiles(modes, "2") { width, _, fps ->
      if (width == 1920 && fps == 240) null else "hardware.avc"
    }
    assertEquals(listOf(240, 120), profiles.map { it.fps })
    assertEquals(1280, profiles.first().width)
    assertTrue(profiles.all { it.highSpeed && it.fpsLower == it.fps && it.cameraId == "2" })
    assertEquals("hardware.avc", profiles.first().encoderName)
  }

  @Test fun unsupportedAndVariableModesDoNotCreateArtificialOptions() {
    val modes = listOf(HighSpeedMode(1280, 720, 30, 240), HighSpeedMode(1280, 720, 480, 480))
    assertTrue(ProfileCatalog.highSpeedProfiles(modes, "0") { _, _, _ -> "hardware" }.isEmpty())
    assertTrue(ProfileCatalog.highSpeedProfiles(listOf(HighSpeedMode(1280, 720, 120, 120)), "0") { _, _, _ -> null }.isEmpty())
  }

  @Test fun duplicateRangesDoNotDuplicateProfilesAndBitratesScaleWithRate() {
    val mode = HighSpeedMode(1920, 1080, 240, 240)
    val result = ProfileCatalog.highSpeedProfiles(listOf(mode, mode, mode.copy(lower = 120, upper = 120)), "0") { _, _, _ -> "avc" }
    assertEquals(2, result.size)
    assertEquals(40_000_000, result.first().bitrate)
    assertEquals(24_000_000, result.last().bitrate)
  }

  @Test fun pairingKeeps240FpsWithoutTurningItIntoSlowMotion() {
    val config = PairingConfig.parse("tennisvision://pair?host=192.168.15.2&path=galaxy&fps=240&width=1280&height=720&bitrate=24000000")
    assertEquals(240, config.fps)
    assertEquals(24_000_000, config.bitrate)
    assertThrows(IllegalArgumentException::class.java) { config.copy(fps = 480) }
  }
}
