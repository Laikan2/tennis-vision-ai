package ai.tennisvision.camera

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

enum class UpdateNetwork { OFFLINE, METERED, UNMETERED }
enum class UpdatePhase { IDLE, CHECKING, CURRENT, AVAILABLE, DOWNLOADING, VERIFYING, READY, ERROR }
data class CameraUpdateState(val phase: UpdatePhase = UpdatePhase.IDLE, val release: AppRelease? = null,
  val percent: Int = 0, val message: String = "")

object UpdateTiming {
  const val CHECK_INTERVAL = 6 * 60 * 60 * 1000L
  const val RETRY_INTERVAL = 15 * 60 * 1000L
  fun due(now: Long, last: Long, interval: Long) = last <= 0 || now < last || now - last >= interval
}

/**
 * Activity-owned, single worker. Network work pauses before streaming and never
 * installs in the background. A verified APK stays ready across app restarts.
 */
class CameraUpdates(private val activity: AppCompatActivity,
  private val source: AppUpdateSource = GitHubAppUpdateSource(),
  private val network: () -> UpdateNetwork = { currentNetwork(activity) },
  private val verifyPackage: (File, AppRelease) -> Unit = SignedApkVerifier(activity)::verify,
  private val now: () -> Long = System::currentTimeMillis,
  private val installUri: (File) -> Uri = {
    FileProvider.getUriForFile(activity, activity.packageName + ".updates", it)
  }) {
  private val installed = requireNotNull(StableVersion.parse(BuildConfig.VERSION_NAME))
  private val saved = activity.getSharedPreferences("app-updates", Context.MODE_PRIVATE)
  private val folder = File(activity.filesDir, "updates")
  private val worker = Executors.newSingleThreadExecutor()
  private val handler = Handler(Looper.getMainLooper())
  private val epoch = AtomicInteger()
  private var working = false
  @Volatile private var closed = false
  @Volatile private var foreground = false
  @Volatile private var idle = false
  private var candidate: AppRelease? = runCatching {
    AppRelease.fromAsset(JSONObject(saved.getString("candidate", "") ?: ""))
  }.getOrNull()?.takeIf { it.version > installed }
  private var restored = false
  private var installAfterPermission = false
  private var announced: StableVersion? = null
  private val periodicCheck = object : Runnable {
    override fun run() {
      if (closed || !foreground) return
      maybeAutomatic()
      handler.postDelayed(this, 60_000)
    }
  }
  var state = CameraUpdateState(release = candidate)
    private set
  private val status = activity.findViewById<TextView>(R.id.updateStatus)
  private val progress = activity.findViewById<ProgressBar>(R.id.updateProgress)
  private val checkButton = activity.findViewById<Button>(R.id.checkUpdates)
  private val actionButton = activity.findViewById<Button>(R.id.installUpdate)
  private val badge = activity.findViewById<TextView>(R.id.versionText)

  private val installPermission = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
    if (closed) return@registerForActivityResult
    if (!activity.packageManager.canRequestPackageInstalls()) {
      installAfterPermission = false
      publish(state.copy(message = activity.getString(R.string.update_permission_denied)))
    } else {
      installAfterPermission = true
      maybeInstall()
    }
  }
  private val packageInstaller = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
    if (!closed) publish(state.copy(message = activity.getString(
      if (it.resultCode == Activity.RESULT_OK) R.string.update_install_sent else R.string.update_install_cancelled)))
  }

  init {
    if (candidate == null) saved.edit().remove("candidate").apply()
    checkButton.setOnClickListener { check() }
    actionButton.setOnClickListener {
      when (state.phase) {
        UpdatePhase.READY -> install()
        else -> if (candidate != null) download(manual = true) else check()
      }
    }
    badge.setOnClickListener {
      activity.findViewById<View>(R.id.networkTab).performClick()
      handler.post {
        if (!closed) activity.findViewById<android.widget.ScrollView>(R.id.settingsScroll).smoothScrollTo(0,
          activity.findViewById<View>(R.id.updateSection).top)
      }
    }
    render()
  }

  fun resume(canUpdate: Boolean) {
    foreground = true
    handler.removeCallbacks(periodicCheck)
    handler.postDelayed(periodicCheck, 60_000)
    activityState(canUpdate)
    maybeInstall()
    maybeAutomatic()
  }
  fun activityState(canUpdate: Boolean) {
    val allowed = foreground && canUpdate
    if (idle && !allowed) cancelWork()
    idle = allowed
    render()
    if (idle) { maybeInstall(); maybeAutomatic() }
  }
  fun networkChanged() { if (!closed) { render(); maybeAutomatic() } }
  fun pause() {
    foreground = false
    idle = false
    handler.removeCallbacks(periodicCheck)
    cancelWork()
    render()
  }
  fun close() {
    closed = true
    foreground = false
    idle = false
    cancelWork()
    handler.removeCallbacksAndMessages(null)
    worker.shutdown()
  }

  private fun canWork() = !closed && foreground && idle
  private fun cancelWork() { epoch.incrementAndGet(); source.cancel() }
  private fun apk(release: AppRelease) = File(folder, "camera-v" + release.version + ".apk")
  private fun message(resource: Int) = activity.getString(resource)
  private fun publish(next: CameraUpdateState) { if (!closed) { state = next; render() } }

  private fun task(phase: UpdatePhase, operation: (cancelled: () -> Boolean, token: Int) -> (() -> Unit)) {
    if (!canWork() || working) return
    working = true
    val token = epoch.get()
    publish(state.copy(phase = phase, message = "", percent = 0, release = candidate))
    worker.execute {
      val cancelled = { closed || !foreground || !idle || token != epoch.get() }
      val result = runCatching { operation(cancelled, token) }
      source.cancel()
      handler.post {
        working = false
        if (closed) return@post
        if (cancelled() || result.exceptionOrNull() is UpdateCancelled) {
          // A live stream wins. Retry cancelled work when the app becomes idle.
          saved.edit().putLong("lastAttempt", saved.getLong("lastCheck", 0)).putLong("downloadAttempt", 0).apply()
          publish(CameraUpdateState(if (candidate == null) UpdatePhase.IDLE else UpdatePhase.AVAILABLE, candidate))
        } else result.fold({ it() }, {
          val error = if (it is IOException || it is IllegalArgumentException)
            it.message?.take(180) ?: message(R.string.update_failed) else message(R.string.update_failed)
          publish(CameraUpdateState(UpdatePhase.ERROR, candidate, message = error))
        })
        maybeInstall()
        maybeAutomatic()
      }
    }
  }

  private fun maybeAutomatic() {
    if (!canWork() || working || installAfterPermission) return
    val release = candidate
    if (release != null && !restored && apk(release).isFile) {
      download(manual = false, restoreOnly = true)
      return
    }
    val connection = network()
    val lastCheck = saved.getLong("lastCheck", 0)
    val lastAttempt = saved.getLong("lastAttempt", 0)
    val interval = if (lastAttempt > lastCheck) UpdateTiming.RETRY_INTERVAL else UpdateTiming.CHECK_INTERVAL
    if (connection != UpdateNetwork.OFFLINE && UpdateTiming.due(now(), lastAttempt, interval)) {
      check()
    } else if (release != null && state.phase != UpdatePhase.READY && connection == UpdateNetwork.UNMETERED &&
      UpdateTiming.due(now(), saved.getLong("downloadAttempt", 0), UpdateTiming.RETRY_INTERVAL)) {
      download(manual = false)
    }
  }

  fun check() {
    if (!canWork() || working) return
    if (network() == UpdateNetwork.OFFLINE) {
      publish(state.copy(message = message(R.string.update_offline)))
      return
    }
    saved.edit().putLong("lastAttempt", now()).apply()
    task(UpdatePhase.CHECKING) { cancelled, _ ->
      val release = source.check(installed, cancelled)
      if (cancelled()) throw UpdateCancelled()
      ({
        val old = candidate
        candidate = release
        restored = false
        saved.edit().putLong("lastCheck", now()).putLong("lastAttempt", now())
          .apply { if (release == null) remove("candidate") else putString("candidate", release.toJson().toString()) }.apply()
        if (old != release) saved.edit().putLong("downloadAttempt", 0).apply()
        if (old != null && old != release) apk(old).delete()
        publish(CameraUpdateState(if (release == null) UpdatePhase.CURRENT else UpdatePhase.AVAILABLE, release))
      })
    }
  }

  private fun download(manual: Boolean, restoreOnly: Boolean = false) {
    val release = candidate ?: return
    if (!canWork() || working) return
    val target = apk(release)
    if (!target.isFile && (network() == UpdateNetwork.OFFLINE || (!manual && network() != UpdateNetwork.UNMETERED))) {
      render()
      return
    }
    saved.edit().putLong("downloadAttempt", now()).apply()
    task(if (target.isFile) UpdatePhase.VERIFYING else UpdatePhase.DOWNLOADING) { cancelled, token ->
      folder.mkdirs()
      val partial = File(folder, target.name + ".part")
      try {
        if (!DownloadIntegrity.matches(target, release)) {
          target.delete()
          if (restoreOnly) throw IOException(message(R.string.update_invalid_cache))
          source.download(release, partial, cancelled) { percent ->
            handler.post {
              if (canWork() && token == epoch.get()) publish(state.copy(percent = percent))
            }
          }
          if (cancelled()) throw UpdateCancelled()
          verifyPackage(partial, release)
          if (!partial.renameTo(target)) throw IOException(message(R.string.update_save_failed))
        } else verifyPackage(target, release)
        if (cancelled()) throw UpdateCancelled()
        ({
          restored = true
          publish(CameraUpdateState(UpdatePhase.READY, release, 100))
          if (announced != release.version) {
            announced = release.version
            Toast.makeText(activity, activity.getString(R.string.update_ready_notice, release.version.toString()),
              Toast.LENGTH_LONG).show()
          }
        })
      } catch (error: Exception) {
        if (error !is UpdateCancelled) { target.delete(); restored = true }
        throw error
      } finally { partial.delete() }
    }
  }

  fun install() {
    if (!canWork() || working || state.phase != UpdatePhase.READY) return
    val release = candidate ?: return
    task(UpdatePhase.VERIFYING) { cancelled, _ ->
      val target = apk(release)
      if (!DownloadIntegrity.matches(target, release)) throw IOException(message(R.string.update_invalid_cache))
      verifyPackage(target, release)
      if (cancelled()) throw UpdateCancelled()
      ({
        publish(CameraUpdateState(UpdatePhase.READY, release, 100))
        if (!activity.packageManager.canRequestPackageInstalls()) {
          installAfterPermission = true
          runCatching { installPermission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:" + activity.packageName))) }
            .onFailure {
              android.util.Log.w("CameraUpdates", "Permission activity failed: " + it.javaClass.simpleName)
              installAfterPermission = false
              publish(state.copy(message = message(R.string.update_install_unavailable)))
            }
        } else launchInstaller(target)
      })
    }
  }

  private fun maybeInstall() {
    if (installAfterPermission && canWork() && !working && activity.packageManager.canRequestPackageInstalls()) {
      installAfterPermission = false
      install()
    }
  }

  @Suppress("DEPRECATION")
  private fun launchInstaller(target: File) {
    if (!canWork()) return
    runCatching {
      val uri = installUri(target)
      packageInstaller.launch(Intent(Intent.ACTION_INSTALL_PACKAGE).setDataAndType(uri, "application/vnd.android.package-archive")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        .apply { clipData = ClipData.newRawUri("Tennis Vision Camera", uri) }
        .putExtra(Intent.EXTRA_RETURN_RESULT, true))
    }.onFailure {
      android.util.Log.w("CameraUpdates", "Installer activity failed: " + it.javaClass.simpleName, it)
      publish(state.copy(message = message(R.string.update_install_unavailable)))
    }
  }

  private fun render() {
    if (closed) return
    val release = candidate
    val busy = state.phase in listOf(UpdatePhase.CHECKING, UpdatePhase.DOWNLOADING, UpdatePhase.VERIFYING) && working
    status.text = state.message.ifEmpty {
      when {
        !idle && release != null -> message(R.string.update_wait_idle)
        state.phase == UpdatePhase.CHECKING -> message(R.string.update_checking)
        state.phase == UpdatePhase.DOWNLOADING -> activity.getString(R.string.update_downloading, state.percent)
        state.phase == UpdatePhase.VERIFYING -> message(R.string.update_verifying)
        state.phase == UpdatePhase.READY -> activity.getString(R.string.update_ready, release?.version.toString())
        state.phase == UpdatePhase.CURRENT -> activity.getString(R.string.update_current, installed.toString())
        release != null -> activity.getString(if (network() == UpdateNetwork.UNMETERED)
          R.string.update_available else R.string.update_wait_wifi, release.version.toString())
        else -> message(R.string.update_automatic_help)
      }
    }
    status.setTextColor(ContextCompat.getColor(activity,
      if (state.phase == UpdatePhase.ERROR) R.color.tv_error else if (release != null) R.color.tv_accent else R.color.tv_muted))
    progress.visibility = if (busy) View.VISIBLE else View.GONE
    progress.isIndeterminate = state.phase != UpdatePhase.DOWNLOADING
    progress.progress = state.percent
    checkButton.isEnabled = canWork() && !working
    actionButton.visibility = if (release != null) View.VISIBLE else View.GONE
    actionButton.setText(if (state.phase == UpdatePhase.READY) R.string.update_install else R.string.update_download)
    actionButton.isEnabled = canWork() && !working
    badge.text = activity.getString(if (release != null) R.string.update_badge else R.string.update_version,
      installed.toString())
    badge.contentDescription = activity.getString(R.string.update_badge_description, installed.toString())
  }

  companion object {
    fun currentNetwork(context: Context): UpdateNetwork {
      val manager = context.getSystemService(ConnectivityManager::class.java) ?: return UpdateNetwork.OFFLINE
      val connection = manager.activeNetwork ?: return UpdateNetwork.OFFLINE
      val capabilities = manager.getNetworkCapabilities(connection) ?: return UpdateNetwork.OFFLINE
      if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return UpdateNetwork.OFFLINE
      return if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
        UpdateNetwork.UNMETERED else UpdateNetwork.METERED
    }
  }
}
