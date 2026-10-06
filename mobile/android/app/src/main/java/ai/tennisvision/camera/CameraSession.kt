package ai.tennisvision.camera

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Range
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.SurfaceHolder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/** Owns Camera2 sessions. Camera surfaces feed MediaCodec directly, without a GL copy. */
class CameraSession(context: Context, private val onError: (String) -> Unit) {
  private val manager = context.getSystemService(CameraManager::class.java)
  private val thread = HandlerThread("TennisCamera").also { it.start() }
  private val handler = Handler(thread.looper)
  private var device: CameraDevice? = null
  private var session: CameraCaptureSession? = null
  private var builder: CaptureRequest.Builder? = null
  @Volatile private var profile: StreamProfile? = null
  private var outputs = emptyList<Surface>()
  @Volatile private var generation = 0
  private var sessionGeneration = 0
  private var pendingDevices = 0
  private val closeWaiters = mutableListOf<CountDownLatch>()
  private var releasing = false
  private var customRequest: ((CaptureRequest.Builder) -> Unit)? = null
  private var commonRequest: ((CaptureRequest.Builder) -> Unit)? = null
  private val meter = FrameRateMeter()
  @Volatile var captureListener: ((TotalCaptureResult) -> Unit)? = null
  @Volatile var fpsListener: ((Double) -> Unit)? = null
  @Volatile private var active = false

  fun characteristics(cameraId: String): CameraCharacteristics = manager.getCameraCharacteristics(cameraId)

  @SuppressLint("MissingPermission")
  fun open(config: StreamProfile, preview: Surface, encoder: Surface? = null) {
    handler.post {
      if (releasing) return@post
      closeOnCameraThread()
      profile = config
      outputs = listOfNotNull(preview, encoder)
      active = true
      val token = generation
      fun openDevice(attempt: Int) {
        if (!active || token != generation) return
        pendingDevices++
        runCatching {
          manager.openCamera(config.cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
              if (!active || token != generation) { camera.close(); return }
              device = camera
              configureSession()
            }
            override fun onDisconnected(camera: CameraDevice) {
              camera.close()
              if (token == generation && active) reportError("A câmera foi desconectada.")
            }
            override fun onError(camera: CameraDevice, error: Int) {
              camera.close()
              if (token != generation || !active) return
              if (error == ERROR_CAMERA_IN_USE && attempt < 2) handler.postDelayed({ openDevice(attempt + 1) }, 300)
              else reportError("Não abriu a câmera (código $error). Feche outros apps de câmera ou escolha outro perfil.")
            }
            override fun onClosed(camera: CameraDevice) {
              pendingDevices--
              finishRelease()
            }
          }, handler)
        }.onFailure { pendingDevices--; finishRelease(); reportError(it.message ?: "Não abriu a câmera.") }
      }
      openDevice(0)
    }
  }

  fun record(preview: Surface, encoder: Surface, onReady: () -> Unit) {
    handler.post {
      if (!active || device == null) { reportError("A câmera não está pronta para transmitir."); return@post }
      outputs = listOf(preview, encoder)
      configureSession(onReady)
    }
  }

  @Suppress("DEPRECATION")
  private fun configureSession(onReady: () -> Unit = {}) {
    val camera = device ?: return
    val config = profile ?: return
    val token = generation
    val sessionToken = ++sessionGeneration
    runCatching { session?.stopRepeating() }
    runCatching { session?.abortCaptures() }
    runCatching { session?.close() }
    session = null
    builder = null
    meter.reset()
    val targets = outputs.toList()
    val recording = targets.size == 2
    val highSpeed = CaptureSessionPolicy.usesHighSpeed(config, recording)
    // Set the FPS range before configuring the sensor. Setting a session key
    // only after onConfigured can trigger a second expensive HAL transition.
    val request = runCatching {
      val request = camera.createCaptureRequest(if (recording) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW)
      targets.forEach(request::addTarget)
      request.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
      request.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
      request.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
      val chars = characteristics(config.cameraId)
      val focus = chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf()
      if (CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO in focus)
        request.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
      val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
      val available = if (highSpeed) map.getHighSpeedVideoFpsRangesFor(Size(config.width, config.height)).toList()
        else chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.toList().orEmpty()
      val duration = if (config.highSpeed && !recording)
        map.getOutputMinFrameDuration(SurfaceHolder::class.java, Size(config.width, config.height)) else 0L
      val limit = if (duration > 0) minOf(60, (1_000_000_000.0 / duration * 1.02).toInt()) else 60
      val (lower, upper) = CaptureSessionPolicy.range(config, recording, available.map { it.lower to it.upper }, limit)
      request.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(lower, upper))
      if (!config.highSpeed) customRequest?.invoke(request)
      if (!highSpeed) commonRequest?.invoke(request)
      Log.i("TennisCamera", "Configuring camera ${config.cameraId}: ${config.width}x${config.height}, $lower-$upper fps, highSpeed=$highSpeed, outputs=${targets.size}")
      request
    }.getOrElse { reportError("Não preparou ${config.label}: ${it.message}"); return }
    val callbacks = object : CameraCaptureSession.StateCallback() {
      override fun onConfigured(newSession: CameraCaptureSession) {
        if (!active || token != generation || sessionToken != sessionGeneration) { newSession.close(); return }
        session = newSession
        runCatching {
          builder = request
          repeatRequest()
          onReady()
        }.onFailure { reportError("A câmera não aceitou ${config.label}: ${it.message}") }
      }
      override fun onConfigureFailed(failedSession: CameraCaptureSession) {
        failedSession.close()
        if (active && token == generation && sessionToken == sessionGeneration)
          reportError("Não iniciou ${config.label}. Tente um perfil de menor resolução ou FPS.")
      }
    }
    runCatching {
      if (Build.VERSION.SDK_INT >= 28) {
        val settings = SessionConfiguration(
          if (highSpeed) SessionConfiguration.SESSION_HIGH_SPEED else SessionConfiguration.SESSION_REGULAR,
          targets.map(::OutputConfiguration), Executor { handler.post(it) }, callbacks,
        )
        settings.setSessionParameters(request.build())
        camera.createCaptureSession(settings)
      } else if (highSpeed) camera.createConstrainedHighSpeedCaptureSession(targets, callbacks, handler)
      else camera.createCaptureSession(targets, callbacks, handler)
    }.onFailure { reportError("Não configurou ${config.label}: ${it.message}") }
  }

  private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
    override fun onCaptureCompleted(captureSession: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
      if (!active || captureSession !== session) return
      result.get(CaptureResult.SENSOR_TIMESTAMP)?.let { timestamp -> meter.frame(timestamp)?.let { fpsListener?.invoke(it) } }
      captureListener?.invoke(result)
    }
  }

  private fun repeatRequest() {
    val activeSession = session ?: return
    val request = builder?.build() ?: return
    if (activeSession is CameraConstrainedHighSpeedCaptureSession) {
      activeSession.setRepeatingBurst(activeSession.createHighSpeedRequestList(request), captureCallback, handler)
    } else activeSession.setRepeatingRequest(request, captureCallback, handler)
  }

  fun setCustomRequest(update: (CaptureRequest.Builder) -> Unit): Boolean {
    if (!active || profile?.highSpeed == true) return false
    val token = generation
    handler.post {
      if (!active || token != generation) return@post
      customRequest = update
      runCatching { builder?.let(update); repeatRequest() }
        .onFailure { reportError("Ajuste de captura: ${it.message}") }
    }
    return true
  }

  fun setCommonRequest(update: (CaptureRequest.Builder) -> Unit) {
    if (profile?.highSpeed == true) return
    val token = generation
    handler.post {
      if (!active || token != generation) return@post
      commonRequest = update
      runCatching { builder?.let(update); repeatRequest() }
        .onFailure { reportError("Ajuste de imagem: ${it.message}") }
    }
  }

  fun tapToFocus(x: Float, y: Float): Boolean {
    if (!active || profile?.highSpeed == true) return false
    val focusToken = generation
    handler.post {
      if (!active || generation != focusToken) return@post
      val chars = profile?.let { characteristics(it.cameraId) } ?: return@post
      val sensor = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return@post
      // zoomRatio uses post-zoom coordinates; older crop-based zoom exposes
      // only a portion of the active array. Also account for the stream's 16:9 crop.
      val crop = builder?.get(CaptureRequest.SCALER_CROP_REGION) ?: sensor
      val config = profile ?: return@post
      val ratio = config.width.toFloat() / config.height
      val width = minOf(crop.width(), (crop.height() * ratio).toInt())
      val height = minOf(crop.height(), (crop.width() / ratio).toInt())
      val rect = android.graphics.Rect(crop.centerX()-width/2, crop.centerY()-height/2,
        crop.centerX()+width/2, crop.centerY()+height/2)
      if ((chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0) == 0) return@post
      val area = minOf(rect.width(), rect.height()) / 12
      val left = (rect.left + x.coerceIn(0f, 1f) * rect.width() - area / 2).toInt().coerceIn(rect.left, rect.right - area)
      val top = (rect.top + y.coerceIn(0f, 1f) * rect.height() - area / 2).toInt().coerceIn(rect.top, rect.bottom - area)
      val token = generation
      runCatching {
        builder?.apply {
          set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
          set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(MeteringRectangle(left, top, area, area, MeteringRectangle.METERING_WEIGHT_MAX)))
          set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
          session?.capture(build(), captureCallback, handler)
          set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
        }
        repeatRequest()
        handler.postDelayed({
          if (active && generation == token) runCatching {
            builder?.let { customRequest?.invoke(it) }; repeatRequest()
          }
        }, 800)
      }.onFailure { reportError("Foco: ${it.message}") }
    }
    return true
  }

  fun stop() {
    if (Looper.myLooper() == handler.looper) { closeOnCameraThread(); return }
    val done = CountDownLatch(1)
    check(handler.post {
      closeWaiters.add(done)
      try { closeOnCameraThread() } finally { finishRelease() }
    }) { "A câmera já encerrou seu processamento." }
    check(done.await(3, TimeUnit.SECONDS)) { "A câmera demorou para encerrar." }
  }

  private fun closeOnCameraThread() {
    active = false
    generation++
    sessionGeneration++
    runCatching { session?.stopRepeating() }
    runCatching { session?.abortCaptures() }
    runCatching { session?.close() }
    runCatching { device?.close() }
    session = null
    device = null
    builder = null
    outputs = emptyList()
    customRequest = null
    commonRequest = null
    meter.reset()
  }

  private fun reportError(message: String) {
    if (active) { Log.e("TennisCamera", message); onError(message) }
  }
  private fun finishRelease() {
    if (pendingDevices == 0) {
      closeWaiters.forEach { it.countDown() }
      closeWaiters.clear()
      if (releasing) thread.quitSafely()
    }
  }
  fun release() {
    try { stop() }
    finally {
      // Late open/close callbacks must still release their camera devices.
      handler.post { releasing = true; captureListener = null; fpsListener = null; finishRelease() }
    }
  }
}
