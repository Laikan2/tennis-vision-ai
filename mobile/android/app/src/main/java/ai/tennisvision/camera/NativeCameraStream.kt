package ai.tennisvision.camera

import android.content.Context
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.SurfaceView
import com.pedro.common.ConnectChecker
import com.pedro.common.StreamingStatsReport
import com.pedro.encoder.CodecErrorCallback
import com.pedro.encoder.TimestampMode
import com.pedro.encoder.input.video.CameraCallbacks
import com.pedro.encoder.utils.CodecUtil
import com.pedro.encoder.video.FormatVideoEncoder
import com.pedro.encoder.video.GetVideoData
import com.pedro.encoder.video.VideoEncoder
import com.pedro.library.util.streamclient.SrtStreamClient
import com.pedro.library.util.streamclient.StreamClientListener
import com.pedro.srt.srt.SrtClient
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/** Direct camera -> hardware AVC input surface -> SRT, preserving camera presentation times. */
class NativeCameraStream(context: Context, private val preview: SurfaceView, private val checker: ConnectChecker) {
  private var callbacks: CameraCallbacks? = null
  private val camera = CameraSession(context) { callbacks?.onCameraError(it) }
  private val callbackEpoch = CallbackEpoch()
  private val callbackHandler = Handler(Looper.getMainLooper())
  private var srt = newSrtClient()
  private val encoderMeter = FrameRateMeter()
  private var encodedFpsListener: ((Double) -> Unit)? = null
  private var profile: StreamProfile? = null
  private var opened = false
  @Volatile private var sessionConfigured = false
  @Volatile private var pendingUrl: String? = null
  private val connectionQueued = AtomicBoolean(false)
  @Volatile private var released = false
  private val recovery = KeyframeRecovery()
  val recoveryDiscarded: Long get() = synchronized(recovery) { recovery.discarded }
  var maxBitrate = 0
    private set
  @Volatile var isStreaming = false
    private set
  @Volatile var isOnPreview = false
    private set
  @Volatile var encodingStartedMs = 0L
    private set
  @Volatile var lastEncodedMs = 0L
    private set

  private val encoder = object : VideoEncoder(object : GetVideoData {
    override fun onVideoInfo(sps: ByteBuffer, pps: ByteBuffer?, vps: ByteBuffer?) { srt.setVideoInfo(sps, pps, vps) }
    override fun onVideoFormat(mediaFormat: MediaFormat) = Unit
    override fun getVideoData(videoBuffer: ByteBuffer, info: MediaCodec.BufferInfo) {
      if (isStreaming && info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
        lastEncodedMs = SystemClock.elapsedRealtime()
        connectWhenVideoReady()
        encoderMeter.frame(SystemClock.elapsedRealtimeNanos())?.let { encodedFpsListener?.invoke(it) }
        synchronized(recovery) {
          if (isStreaming && recovery.accepts(info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0))
            srt.sendVideo(videoBuffer, info)
        }
      }
    }
  }) {
    override fun chooseEncoder(mime: String): MediaCodecInfo? = profile?.let(CameraCapabilities::encoderFor)
  }.apply {
    setTimestampMode(TimestampMode.BUFFER)
    setEncoderErrorCallback(object : CodecErrorCallback {
      override fun onCodecError(type: CodecUtil.CodecTypeError, e: MediaCodec.CodecException) {
        if (isStreaming) callbacks?.onCameraError("Codificador H.264: ${e.diagnosticInfo}. Escolha outro perfil.")
      }
      override fun onEncodeError(type: CodecUtil.CodecTypeError, e: IllegalStateException): Boolean {
        if (isStreaming) callbacks?.onCameraError("Codificador interrompido: ${e.message}")
        return false
      }
    })
  }
  private fun newSrtClient(): SrtClient {
    val token = callbackEpoch.advance()
    fun deliver(action: () -> Unit) {
      callbackHandler.post { if (!released && callbackEpoch.isCurrent(token) && isStreaming) action() }
    }
    return SrtClient(object : ConnectChecker {
      override fun onConnectionStarted(url: String) = deliver { checker.onConnectionStarted(url) }
      override fun onConnectionSuccess() = deliver { checker.onConnectionSuccess() }
      override fun onConnectionFailed(reason: String) = deliver { checker.onConnectionFailed(reason) }
      override fun onDisconnect() = deliver { checker.onDisconnect() }
      override fun onAuthError() = deliver { checker.onAuthError() }
      override fun onAuthSuccess() = deliver { checker.onAuthSuccess() }
      override fun onStreamingStats(report: StreamingStatsReport) = deliver { checker.onStreamingStats(report) }
    }).apply { setOnlyVideo(true); setLogs(false); socketTimeout = 3_000 }
  }
  private fun newStreamClient() = SrtStreamClient(srt, object : StreamClientListener {
    override fun onRequestKeyframe() { if (isStreaming) encoder.requestKeyframe() }
  })
  private var client = newStreamClient()

  fun getStreamClient() = client
  fun setCameraCallbacks(value: CameraCallbacks?) { callbacks = value }
  fun setFpsListener(value: ((Double) -> Unit)?) { encodedFpsListener = value }
  fun setCaptureFpsListener(value: ((Double) -> Unit)?) { camera.fpsListener = value }
  fun setCustomOnCaptureCompletedCallback(value: ((TotalCaptureResult) -> Unit)?) { camera.captureListener = value }
  fun getCameraCharacteristics() = profile?.let { camera.characteristics(it.cameraId) }

  fun prepareVideo(config: StreamProfile): Boolean {
    check(!isStreaming && !isOnPreview) { "Pare a prévia e a transmissão antes de trocar o perfil." }
    profile = config
    val codecInfo = CameraCapabilities.encoderFor(config) ?: return false
    val caps = codecInfo.getCapabilitiesForType("video/avc")
    val bitrate = (caps.videoCapabilities ?: return false).bitrateRange.clamp(config.bitrate)
    maxBitrate = bitrate
    preview.holder.setFixedSize(config.width, config.height)
    // Preview uses only the camera. Allocate a codec when transmission starts,
    // so changing profiles or scanning a QR does not keep unused codecs open.
    return true
  }

  fun startPreview() {
    check(!released) { "A câmera já foi encerrada." }
    isOnPreview = true
    surfaceChanged()
  }

  fun surfaceChanged() {
    val config = profile ?: return
    if (!isOnPreview || opened || !preview.holder.surface.isValid) return
    val frame = preview.holder.surfaceFrame
    if (frame.width() != config.width || frame.height() != config.height) return
    opened = true
    camera.open(config, preview.holder.surface)
  }

  fun startStream(url: String) {
    check(isOnPreview && opened && !isStreaming) { "Aguarde a câmera ficar pronta." }
    val config = checkNotNull(profile)
    val codecInfo = checkNotNull(CameraCapabilities.encoderFor(config)) { "O H.264 não oferece este perfil." }
    val caps = codecInfo.getCapabilitiesForType("video/avc")
    val avc = checkNotNull(AvcEncoderModes.forCapabilities(caps, config, maxBitrate)) {
      "O codificador não confirmou H.264 para ${config.label}."
    }
    // No forced frame limiter: the fixed Camera2 range controls the source rate.
    encoder.setForceFps(0)
    check(encoder.prepareVideoEncoder(config.width, config.height, config.fps, maxBitrate, 0, 1,
      FormatVideoEncoder.SURFACE, avc.profile, avc.level)) { "O codificador não aceitou ${config.label}. Escolha outro perfil." }
    isStreaming = true
    sessionConfigured = false
    pendingUrl = url
    connectionQueued.set(false)
    encodingStartedMs = SystemClock.elapsedRealtime()
    lastEncodedMs = 0L
    synchronized(recovery) { recovery.reset() }
    encoderMeter.reset()
    encoder.start()
    val token = callbackEpoch.current()
    camera.record(preview.holder.surface, encoder.inputSurface) {
      callbackHandler.post {
        if (!released && isStreaming && callbackEpoch.isCurrent(token)) {
          sessionConfigured = true
          connectWhenVideoReady()
        }
      }
    }
  }

  private fun connectWhenVideoReady() {
    if (!isStreaming || !sessionConfigured || lastEncodedMs <= 0 ||
      !connectionQueued.compareAndSet(false, true)) return
    val token = callbackEpoch.current()
    val url = pendingUrl ?: return
    // A configured sensor without encoder output is not a working stream.
    callbackHandler.post {
      if (!released && isStreaming && callbackEpoch.isCurrent(token)) srt.connect(url)
    }
  }

  fun stopStream() {
    isStreaming = false
    sessionConfigured = false
    pendingUrl = null
    encodingStartedMs = 0L
    lastEncodedMs = 0L
    callbackEpoch.advance()
    encoderMeter.reset()
    val stoppedClient = srt
    try { closeResources({ stopPreview() }, { stoppedClient.disconnect() }, { encoder.stop() }) }
    finally {
      if (!released) { srt = newSrtClient(); client = newStreamClient() }
    }
  }

  fun stopPreview() {
    if (!isOnPreview && !opened) return
    opened = false
    isOnPreview = false
    camera.stop()
  }

  fun setVideoBitrateOnFly(value: Int) { if (isStreaming) encoder.setVideoBitrateOnFly(value) }
  fun setCustomRequest(update: (CaptureRequest.Builder) -> Unit) = camera.setCustomRequest(update)
  fun setCommonRequest(update: (CaptureRequest.Builder) -> Unit) = camera.setCommonRequest(update)
  fun recoverFreshVideo() {
    if (!isStreaming) return
    // A missing inter-frame packet otherwise freezes decoding until the next
    // scheduled I-frame. Resume from a fresh keyframe after dropping stale data.
    synchronized(recovery) {
      recovery.request()
      client.clearCache()
      encoder.requestKeyframe()
    }
  }
  fun tapToFocus(view: SurfaceView, event: MotionEvent) = camera.tapToFocus(event.x / view.width, event.y / view.height)
  fun focusAt(x: Float, y: Float) = camera.tapToFocus(x, y)
  fun release() {
    if (released) return
    released = true
    try { closeResources({ stopStream() }, { camera.release() }) }
    finally {
      encodedFpsListener = null
      camera.captureListener = null
      camera.fpsListener = null
      callbacks = null
      callbackHandler.removeCallbacksAndMessages(null)
    }
  }
}
