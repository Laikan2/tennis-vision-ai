package ai.tennisvision.camera

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AppReleaseTest {
  companion object {
    val content = "Tennis Vision APK fixture".toByteArray()
    fun asset(version: String = "0.5.4"): JSONObject {
      val name = "Tennis-Vision-Camera-Android-v" + version + ".apk"
      val hash = MessageDigest.getInstance("SHA-256").digest(content)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
      return JSONObject().put("name", name).put("state", "uploaded").put("size", content.size)
        .put("digest", "sha256:" + hash)
        .put("browser_download_url", "https://github.com/Laikan2/tennis-vision-releases/releases/download/v0.6.2/" + name)
    }
    fun release(version: String = "0.5.4") = requireNotNull(AppRelease.fromAsset(asset(version)))
    fun feed(vararg assets: JSONObject, draft: Boolean = false, prerelease: Boolean = false) =
      JSONObject().put("draft", draft).put("prerelease", prerelease).put("assets", JSONArray(assets.toList()))
  }

  @Test fun choosesTheHighestNumericAndroidVersionAcrossWindowsAndAndroidReleases() {
    val releases = JSONArray().put(feed(asset("0.5.9"))).put(feed(asset("0.5.10")))
      .put(feed(asset("0.5.11"), prerelease = true)).put(feed(asset("0.5.12"), draft = true))
    assertEquals("0.5.10", AppRelease.newest(releases.toString(), StableVersion(0, 5, 2))?.version.toString())
    assertNull(AppRelease.newest(releases.toString(), StableVersion(0, 5, 10)))
  }

  @Test fun ignoresOtherPlatformsAndUnsignedMetadata() {
    val invalid = asset().put("name", "Tennis-Vision-Studio-0.6.2-Windows-x64.exe")
    val noDigest = asset().apply { remove("digest") }
    val unpublished = asset().put("state", "new")
    val missingFlags = JSONObject().put("assets", JSONArray().put(asset("0.5.99")))
    assertEquals("0.5.4", AppRelease.newest(JSONArray().put(feed(invalid, noDigest, unpublished, asset()))
      .put(missingFlags).toString(), StableVersion(0, 5, 3))?.version.toString())
  }

  @Test fun rejectsUntrustedOrMalformedDownloadAddresses() {
    val valid = asset().getString("browser_download_url")
    val urls = listOf(valid.replace("https:", "http:"), valid.replace("Laikan2/", "someone/"),
      valid.replace("github.com", "github.com.evil.example"), valid + "?token=unexpected",
      valid + "#fragment", valid.replace("github.com/", "user:password@github.com/"),
      valid.replace("/v0.6.2/", "/v0.6.2/extra/"), valid.replace(".apk", ".zip"))
    for (url in urls) assertNull(url, AppRelease.fromAsset(asset().put("browser_download_url", url)))
  }

  @Test fun rejectsInvalidVersionsOversizedPackagesAndMissingIntegrity() {
    for (version in listOf("0.05.4", "0.5.4-beta", "0.5", "9999999.1.0", "-1.0.0"))
      assertNull(version, StableVersion.parse(version))
    assertNull(AppRelease.fromAsset(asset().put("size", AppRelease.MAX_BYTES + 1)))
    assertNull(AppRelease.fromAsset(asset().put("size", 0)))
    assertNull(AppRelease.fromAsset(asset().put("digest", "sha256:123")))
  }

  @Test fun verifiedBytesAreSavedAndProgressReachesCompletion() {
    val target = File.createTempFile("camera-update", ".part")
    val values = mutableListOf<Int>()
    try {
      DownloadIntegrity.write(ByteArrayInputStream(content), target, release(), { false }, values::add)
      assertTrue(DownloadIntegrity.matches(target, release()))
      assertEquals(100, values.last())
      target.appendBytes(byteArrayOf(0))
      assertFalse(DownloadIntegrity.matches(target, release()))
    } finally { target.delete() }
  }

  @Test fun truncatedExtraOrCorruptedBytesNeverRemainInstallable() {
    for (bytes in listOf(content.copyOf(content.size - 1), content + byteArrayOf(0),
      content.copyOf().apply { this[0] = 0 })) {
      val target = File.createTempFile("camera-update", ".part")
      assertThrows(IOException::class.java) {
        DownloadIntegrity.write(ByteArrayInputStream(bytes), target, release(), { false }, {})
      }
      assertFalse(target.exists())
    }
  }

  @Test fun cancellationRemovesPartialDownload() {
    val target = File.createTempFile("camera-update", ".part")
    assertThrows(UpdateCancelled::class.java) {
      DownloadIntegrity.write(ByteArrayInputStream(content), target, release(), { true }, {})
    }
    assertFalse(target.exists())
  }

  private val installed = ApkIdentity("ai.tennisvision.camera", "0.5.3", 9, 26, setOf("trusted-certificate"))
  private val incoming = installed.copy(versionName = "0.5.4", versionCode = 10)

  @Test fun acceptsOnlyTheSamePackageAndCertificateWithAnIncreasingBuildNumber() {
    incoming.validate(installed, release(), 33)
  }

  @Test fun rejectsWrongSignerUnsignedApkAndAdditionalSigners() {
    for (signers in listOf(emptySet(), setOf("another-certificate"),
      setOf("trusted-certificate", "another-certificate"))) {
      assertThrows(IllegalArgumentException::class.java) {
        incoming.copy(signers = signers).validate(installed, release(), 33)
      }
    }
  }

  @Test fun rejectsOtherPackagesDowngradesMetadataMismatchAndUnsupportedAndroid() {
    for (invalid in listOf(incoming.copy(packageName = "another.application"),
      incoming.copy(versionCode = 9), incoming.copy(versionName = "0.5.5"), incoming.copy(minSdk = 34))) {
      assertThrows(IllegalArgumentException::class.java) { invalid.validate(installed, release(), 33) }
    }
  }

  @Test fun checkTimingAllowsFirstOpenAndClockChangesWithoutHammeringTheFeed() {
    assertTrue(UpdateTiming.due(100, 0, UpdateTiming.CHECK_INTERVAL))
    assertTrue(UpdateTiming.due(100, 200, UpdateTiming.CHECK_INTERVAL))
    assertFalse(UpdateTiming.due(100 + UpdateTiming.CHECK_INTERVAL - 1, 100, UpdateTiming.CHECK_INTERVAL))
    assertTrue(UpdateTiming.due(100 + UpdateTiming.CHECK_INTERVAL, 100, UpdateTiming.CHECK_INTERVAL))
  }
}
