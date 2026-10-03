package ai.tennisvision.camera

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import android.net.Uri
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class UpdateTestActivity : AppCompatActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    setTheme(R.style.Theme_TennisVisionCamera)
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_main)
  }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@LooperMode(LooperMode.Mode.PAUSED)
class CameraUpdatesTest {
  private class FakeSource : AppUpdateSource {
    var available: AppRelease? = AppReleaseTest.release()
    @Volatile var checks = 0
    @Volatile var downloads = 0
    var error: IOException? = null
    var blockFirstDownload = false
    val started = CountDownLatch(1)
    override fun check(installed: StableVersion, cancelled: () -> Boolean): AppRelease? {
      checks++
      error?.let { throw it }
      return available
    }
    override fun download(release: AppRelease, target: File, cancelled: () -> Boolean, progress: (Int) -> Unit) {
      downloads++
      started.countDown()
      if (blockFirstDownload && downloads == 1) {
        while (!cancelled()) Thread.sleep(10)
        throw UpdateCancelled()
      }
      DownloadIntegrity.write(ByteArrayInputStream(AppReleaseTest.content), target, release, cancelled, progress)
    }
  }
  private data class Fixture(val lifecycle: ActivityController<UpdateTestActivity>, val activity: UpdateTestActivity,
    val updates: CameraUpdates, val source: FakeSource) {
    fun close() { updates.close(); lifecycle.pause().stop().destroy() }
  }

  @Before fun clearSavedUpdate() {
    val app = RuntimeEnvironment.getApplication()
    app.getSharedPreferences("app-updates", 0).edit().clear().commit()
    File(app.filesDir, "updates").listFiles()?.forEach { it.delete() }
  }

  private fun fixture(mode: () -> UpdateNetwork = { UpdateNetwork.UNMETERED },
    source: FakeSource = FakeSource(), verifier: (File, AppRelease) -> Unit = { _, _ -> },
    clock: () -> Long = System::currentTimeMillis): Fixture {
    val lifecycle = Robolectric.buildActivity(UpdateTestActivity::class.java).create()
    val activity = lifecycle.get()
    // FileProvider assumes Android "/" paths. The Windows JVM uses "\" paths;
    // URI generation is tested on Android in UpdateFileSharingTest instead.
    val updates = CameraUpdates(activity, source, mode, verifier, now = clock, installUri = { file ->
      Uri.Builder().scheme("content").authority(activity.packageName + ".updates")
        .appendPath("updates").appendPath(file.name).build()
    })
    lifecycle.start().resume().visible()
    return Fixture(lifecycle, activity, updates, source)
  }

  private fun await(condition: () -> Boolean) {
    val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
    do {
      shadowOf(Looper.getMainLooper()).idle()
      if (condition()) return
      Thread.sleep(10)
    } while (System.nanoTime() < until)
    fail("Update did not reach the expected state")
  }

  @Test fun automaticCheckAndDownloadProduceAReadyUpdateWithoutOpeningTheInstaller() {
    val f = fixture()
    try {
      f.updates.resume(true)
      await { f.updates.state.phase == UpdatePhase.READY }
      assertEquals(1, f.source.checks)
      assertEquals(1, f.source.downloads)
      assertTrue(f.activity.findViewById<Button>(R.id.installUpdate).isEnabled)
      assertTrue(f.activity.findViewById<TextView>(R.id.versionText).text.contains("ATUALIZAR"))
      assertNull(shadowOf(f.activity).nextStartedActivityForResult)
      f.updates.pause()
      f.updates.resume(true)
      shadowOf(Looper.getMainLooper()).idle()
      assertEquals(1, f.source.checks)
      assertEquals(1, f.source.downloads)
    } finally { f.close() }
  }

  @Test fun cellularConnectionWaitsForAnExplicitDownload() {
    val f = fixture(mode = { UpdateNetwork.METERED })
    try {
      f.updates.resume(true)
      await { f.updates.state.phase == UpdatePhase.AVAILABLE }
      assertEquals(0, f.source.downloads)
      f.activity.findViewById<Button>(R.id.installUpdate).performClick()
      await { f.updates.state.phase == UpdatePhase.READY }
      assertEquals(1, f.source.downloads)
    } finally { f.close() }
  }

  @Test fun anActiveStreamPreventsCheckingAndCancelsAnInFlightDownload() {
    val source = FakeSource().apply { blockFirstDownload = true }
    val f = fixture(source = source)
    try {
      f.updates.resume(false)
      f.updates.check()
      assertEquals(0, source.checks)
      f.updates.activityState(true)
      await { source.started.count == 0L }
      f.updates.activityState(false)
      await { f.updates.state.phase == UpdatePhase.AVAILABLE }
      assertFalse(f.activity.findViewById<Button>(R.id.installUpdate).isEnabled)
      assertTrue(File(f.activity.filesDir, "updates").listFiles()?.none { it.extension == "part" } ?: true)
      f.updates.activityState(true)
      await { f.updates.state.phase == UpdatePhase.READY }
      assertEquals(2, source.downloads)
    } finally { f.close() }
  }

  @Test fun offlineModeDoesNotStartNetworkWorkOrBlockTheCameraAction() {
    val f = fixture(mode = { UpdateNetwork.OFFLINE })
    try {
      f.updates.resume(true)
      f.updates.check()
      assertEquals(0, f.source.checks)
      assertEquals(0, f.source.downloads)
      assertTrue(f.activity.findViewById<TextView>(R.id.updateStatus).text.contains("Sem internet"))
      assertTrue(f.activity.findViewById<Button>(R.id.connectButton).isEnabled)
    } finally { f.close() }
  }

  @Test fun failedRequestsHaveACooldownAndRemainManuallyRetryable() {
    val source = FakeSource().apply { error = IOException("Sem conexão.") }
    val f = fixture(source = source)
    try {
      f.updates.resume(true)
      await { f.updates.state.phase == UpdatePhase.ERROR }
      repeat(5) { f.updates.networkChanged() }
      shadowOf(Looper.getMainLooper()).idle()
      assertEquals(1, source.checks)
      source.error = null
      f.activity.findViewById<Button>(R.id.checkUpdates).performClick()
      await { f.updates.state.phase == UpdatePhase.READY }
      assertEquals(2, source.checks)
    } finally { f.close() }
  }

  @Test fun incompatiblePackagesAreNotExposedToTheInstallerAndDoNotLoopDownloads() {
    val f = fixture(verifier = { _, _ -> throw IllegalArgumentException("Assinatura incompatível.") })
    try {
      f.updates.resume(true)
      await { f.updates.state.phase == UpdatePhase.ERROR }
      repeat(5) { f.updates.networkChanged() }
      shadowOf(Looper.getMainLooper()).idle()
      assertEquals(1, f.source.downloads)
      assertTrue(File(f.activity.filesDir, "updates").listFiles()?.none { it.extension in listOf("apk", "part") } ?: true)
      assertNull(shadowOf(f.activity).nextStartedActivityForResult)
    } finally { f.close() }
  }

  @Test fun verifiedUpdatesSurviveRecreationAndRestoreOffline() {
    val first = fixture()
    first.updates.resume(true)
    await { first.updates.state.phase == UpdatePhase.READY }
    first.close()
    val next = fixture(mode = { UpdateNetwork.OFFLINE })
    try {
      next.updates.resume(true)
      await { next.updates.state.phase == UpdatePhase.READY }
      assertEquals(0, next.source.checks)
      assertEquals(0, next.source.downloads)
    } finally { next.close() }
  }

  @Test @Suppress("DEPRECATION") fun aTrustedSourceUsesAContentUriAndRechecksIntegrityBeforeInstalling() {
    var checks = 0
    val f = fixture(verifier = { _, _ -> checks++ })
    try {
      shadowOf(f.activity.packageManager).setCanRequestPackageInstalls(true)
      f.updates.resume(true)
      await { f.updates.state.phase == UpdatePhase.READY }
      f.activity.findViewById<Button>(R.id.installUpdate).performClick()
      await { checks == 2 && f.updates.state.phase == UpdatePhase.READY }
      val request = shadowOf(f.activity).nextStartedActivityForResult
      assertNotNull("Phase " + f.updates.state.phase + "; message: " + f.updates.state.message +
        "; log: " + org.robolectric.shadows.ShadowLog.getLogsForTag("CameraUpdates").map { it.msg }, request)
      assertEquals(Intent.ACTION_INSTALL_PACKAGE, request.intent.action)
      assertEquals("application/vnd.android.package-archive", request.intent.type)
      assertEquals("content", request.intent.data?.scheme)
      assertEquals(f.activity.packageName + ".updates", request.intent.data?.authority)
      assertTrue(request.intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
      assertTrue(request.intent.data!!.path!!.endsWith("camera-v0.5.4.apk"))
      val provider = requireNotNull(f.activity.packageManager.resolveContentProvider(request.intent.data!!.authority!!, 0))
      assertFalse(provider.exported)
      assertTrue(provider.grantUriPermissions)
    } finally { f.close() }
  }

  @Test fun installationPermissionCanBeDeclinedWithoutLosingTheVerifiedFile() {
    val f = fixture()
    try {
      shadowOf(f.activity.packageManager).setCanRequestPackageInstalls(false)
      f.updates.resume(true)
      await { f.updates.state.phase == UpdatePhase.READY }
      f.updates.install()
      await { f.updates.state.phase == UpdatePhase.READY }
      val request = shadowOf(f.activity).nextStartedActivityForResult
      assertNotNull("Phase " + f.updates.state.phase + "; message: " + f.updates.state.message +
        "; log: " + org.robolectric.shadows.ShadowLog.getLogsForTag("CameraUpdates").map { it.msg }, request)
      assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, request.intent.action)
      f.activity.activityResultRegistry.dispatchResult(request.requestCode, Activity.RESULT_CANCELED, Intent())
      assertEquals(UpdatePhase.READY, f.updates.state.phase)
      assertTrue(f.updates.state.message.contains("Permita"))
      assertTrue(f.activity.findViewById<Button>(R.id.installUpdate).isEnabled)
      assertNull(shadowOf(f.activity).nextStartedActivityForResult)
    } finally { f.close() }
  }

  @Test @Suppress("DEPRECATION") fun returningFromPermissionSettingsContinuesInstallationWhileIdle() {
    val f = fixture()
    try {
      shadowOf(f.activity.packageManager).setCanRequestPackageInstalls(false)
      f.updates.resume(true)
      await { f.updates.state.phase == UpdatePhase.READY }
      f.updates.install()
      await { f.updates.state.phase == UpdatePhase.READY }
      val permission = shadowOf(f.activity).nextStartedActivityForResult
      assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, permission.intent.action)
      f.updates.pause()
      shadowOf(f.activity.packageManager).setCanRequestPackageInstalls(true)
      f.activity.activityResultRegistry.dispatchResult(permission.requestCode, Activity.RESULT_OK, Intent())
      assertNull(shadowOf(f.activity).nextStartedActivityForResult)
      f.updates.resume(true)
      await { f.updates.state.phase == UpdatePhase.READY }
      val installer = shadowOf(f.activity).nextStartedActivityForResult
      assertNotNull("Phase " + f.updates.state.phase + "; message: " + f.updates.state.message +
        "; permission: " + f.activity.packageManager.canRequestPackageInstalls() +
        "; log: " + org.robolectric.shadows.ShadowLog.getLogsForTag("CameraUpdates").map { it.msg }, installer)
      assertEquals(Intent.ACTION_INSTALL_PACKAGE, installer.intent.action)
      f.activity.activityResultRegistry.dispatchResult(installer.requestCode, Activity.RESULT_CANCELED, Intent())
      assertTrue("Installer result message: " + f.updates.state.message +
        "; log: " + org.robolectric.shadows.ShadowLog.getLogsForTag("CameraUpdates").map { it.throwable?.message },
        f.updates.state.message.contains("não concluída"))
      assertEquals(UpdatePhase.READY, f.updates.state.phase)
    } finally { f.close() }
  }

  @Test fun noNewVersionLeavesTheAppCurrent() {
    val f = fixture(source = FakeSource().apply { available = null })
    try {
      f.updates.resume(true)
      await { f.updates.state.phase == UpdatePhase.CURRENT }
      assertEquals(0, f.source.downloads)
      assertEquals(android.view.View.GONE, f.activity.findViewById<Button>(R.id.installUpdate).visibility)
    } finally { f.close() }
  }

  @Test fun aFileChangedAfterDownloadIsRejectedBeforeOpeningTheInstaller() {
    val f = fixture()
    try {
      shadowOf(f.activity.packageManager).setCanRequestPackageInstalls(true)
      f.updates.resume(true)
      await { f.updates.state.phase == UpdatePhase.READY }
      File(f.activity.filesDir, "updates/camera-v0.5.4.apk").appendBytes(byteArrayOf(0))
      f.updates.install()
      await { f.updates.state.phase == UpdatePhase.ERROR }
      assertNull(shadowOf(f.activity).nextStartedActivityForResult)
    } finally { f.close() }
  }

  @Test fun repeatedChecksReuseTheVerifiedFileAndCanDiscoverANewerRelease() {
    val f = fixture()
    try {
      f.updates.resume(true)
      await { f.updates.state.phase == UpdatePhase.READY }
      f.updates.check()
      await { f.updates.state.phase == UpdatePhase.READY }
      assertEquals(1, f.source.downloads)
      f.source.available = AppReleaseTest.release("0.5.5")
      f.updates.check()
      await { f.updates.state.phase == UpdatePhase.READY && f.updates.state.release?.version == StableVersion(0, 5, 5) }
      assertEquals(2, f.source.downloads)
      assertFalse(File(f.activity.filesDir, "updates/camera-v0.5.4.apk").exists())
    } finally { f.close() }
  }

  @Test fun anOpenIdleAppChecksAgainAfterSixHoursWithoutRequiringARestart() {
    var time = 10_000L
    val f = fixture(source = FakeSource().apply { available = null }, clock = { time })
    try {
      f.updates.resume(true)
      await { f.updates.state.phase == UpdatePhase.CURRENT }
      time += UpdateTiming.CHECK_INTERVAL - 1
      shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.MINUTES)
      assertEquals(1, f.source.checks)
      time++
      shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.MINUTES)
      await { f.source.checks == 2 && f.updates.state.phase == UpdatePhase.CURRENT }
    } finally { f.close() }
  }
}
