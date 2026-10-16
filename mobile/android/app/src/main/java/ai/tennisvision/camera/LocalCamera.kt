package ai.tennisvision.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Build
import android.os.SystemClock
import android.util.Range
import android.util.Size
import kotlin.math.*

data class LocalFrame(val pixels: IntArray, val width: Int, val height: Int,
  val timestamp: Double, val result: LocalResult, val analysisMs: Double,
  val captureFps: Double, val requestedFps: Int, val key: String, val contactPixels: IntArray?=null,
  val conversionMs: Double=0.0, val detectorMs: Double=0.0,
  val lensLabel: String="",val profileLabel: String="",val contactOffset: CourtPoint?=null,
  val exposureMs: Double?=null,val capturedAtRealtime: Boolean=false)

data class LocalLens(val id: String,val zoom: Float,val label: String) {
  val token get()="$id@$zoom"
}

/** A single YUV stream: no H.264 encoding, relay, WebView, Python or transport queue. */
class LocalCamera(context: Context, private val settings: (String) -> LocalSettings,
  private val frame: (LocalFrame) -> Unit, private val error: (String) -> Unit) {
  private val manager=context.getSystemService(CameraManager::class.java)
  private val thread=HandlerThread("TennisLocalVision").also { it.start() }
  private val handler=Handler(thread.looper)
  private var device: CameraDevice?=null
  private var session: CameraCaptureSession?=null
  private var reader: ImageReader?=null
  @Volatile private var epoch=0
  @Volatile private var disposed=false
  @Volatile var running=false
    private set
  @Volatile var analyze=false
  private var core=LocalAnalysis()
  private val preview=BallPreview()
  private var rotation=0
  private val rateMeter=FrameRateMeter()
  private var rate=0.0
  private var lastFrameMs=0L
  private var openingMs=0L
  private data class Evidence(val time: Double,val pixels: IntArray,val offset: CourtPoint)
  private val recent=ArrayDeque<Evidence>()
  private var exposureMs: Double?=null
  private val watchdog=object : Runnable {
    override fun run() {
      if (!running || disposed) return
      if (SystemClock.elapsedRealtime()-max(openingMs,lastFrameMs)>5000) {
        error("A câmera parou de enviar imagens. Toque em Reabrir câmera.")
        running=false; closeHardware(); return
      }
      handler.postDelayed(this,1000)
    }
  }
  fun update(value: LocalSettings) { handler.post { core.settings=value; core.reset();preview.reset() } }
  fun reset() { handler.post { core.clearTrack();preview.reset() } }

  fun lenses(): List<LocalLens> = manager.cameraIdList.flatMap { id ->
    runCatching {
      val c=manager.getCameraCharacteristics(id)
      if(c.get(CameraCharacteristics.LENS_FACING)!=CameraCharacteristics.LENS_FACING_BACK ||
        c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.getOutputSizes(ImageFormat.YUV_420_888).isNullOrEmpty()) emptyList()
      else {
        val focal=c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
        val sensor=c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val angle=if(focal!=null && sensor!=null) (2*atan(sensor.width/(2*focal).toDouble())*180/PI).toInt() else null
        val standard=LocalLens(id,1f,LocalLensLabel.describe(id,angle))
        val minimum=if(Build.VERSION.SDK_INT>=30) c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.lower else null
        if(minimum!=null && minimum<.99f) listOf(standard,LocalLens(id,minimum,"Ampla · ${String.format(java.util.Locale.ROOT,"%.1f",minimum)}× · câmera $id"))
        else listOf(standard)
      }
    }.getOrDefault(emptyList())
  }

  @SuppressLint("MissingPermission") fun start(displayDegrees: Int, prefer60: Boolean=true,
    profile: LocalCaptureProfile=if(prefer60) LocalCaptureProfile.FAST else LocalCaptureProfile.ECONOMY,
    lensToken: String?=null) {
    if (disposed || running) return
    running=true
    val token=++epoch
    handler.post {
      if (disposed || token!=epoch) return@post
      closeHardware()
      runCatching {
        val choices=lenses()
        val lens=choices.firstOrNull { it.token==lensToken } ?: choices.firstOrNull()
          ?: throw IllegalStateException("Nenhuma câmera traseira disponível.")
        val id=lens.id
        val chars=manager.getCameraCharacteristics(id)
        val realtime=chars.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE)==CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME
        val map=chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
        val sizes=map.getOutputSizes(ImageFormat.YUV_420_888).map { s ->
          val duration=map.getOutputMinFrameDuration(ImageFormat.YUV_420_888,s)
          LocalStreamSize(s.width,s.height,if(duration>0) floor(1e9/duration+.01).toInt() else 30)
        }
        val chosen=LocalCaptureChoice.size(sizes,profile)
          ?: throw IllegalStateException("A câmera não oferece imagens compatíveis com análise local.")
        val size=Size(chosen.width,chosen.height)
        // High-speed constrained sessions cannot target an ImageReader. Offer only real YUV rates.
        val available=chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES).orEmpty()
        val selected=LocalCaptureChoice.fps(available.map { it.lower to it.upper },chosen,profile)
          ?: throw IllegalStateException("Esta lente não oferece FPS regulares compatíveis com esse perfil. Escolha outra lente ou perfil em Enquadramento.")
        val fps=Range(selected.first,selected.second)
        rotation=((chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90)-displayDegrees+360)%360
        // Keep the legacy 1× key so existing areas stay available. Zoom/lens changes
        // get independent calibration, never a stretched copy of another area.
        val key="$id:${size.width}x${size.height}:$rotation"+if(lens.zoom==1f) "" else ":zoom${lens.zoom}"
        core=LocalAnalysis(settings(key));preview.reset(); recent.clear(); rateMeter.reset(); rate=0.0;exposureMs=null
        val output=ImageReader.newInstance(size.width,size.height,ImageFormat.YUV_420_888,3)
        reader=output
        output.setOnImageAvailableListener({ source ->
          if (token!=epoch || disposed || !running) { runCatching { source.acquireLatestImage()?.close() }; return@setOnImageAvailableListener }
          try {
            source.acquireLatestImage()?.use { image ->
              val started=SystemClock.elapsedRealtimeNanos()
              lastFrameMs=SystemClock.elapsedRealtime()
              val time=image.timestamp/1e9
              rateMeter.frame(image.timestamp)?.let { rate=it }
              val (pixels,w,h)=convert(image,rotation)
              val converted=SystemClock.elapsedRealtimeNanos()
              val result=if (analyze) core.process(pixels,w,h,time) else
                preview.process(pixels,w,h,time,core.activeSettings,core.cameraOffset)
              val analyzed=SystemClock.elapsedRealtimeNanos()
              if (analyze) recent.addLast(Evidence(time,pixels,result.cameraOffset)) else recent.clear()
              while (recent.size>6) recent.removeFirst()
              val evidence=result.impact?.let { event -> recent.minByOrNull { abs(it.time-event.time) } }
              if (token==epoch && running) frame(LocalFrame(pixels,w,h,time,result,
                (analyzed-started)/1e6,rate,fps.upper,key,evidence?.pixels,(converted-started)/1e6,(analyzed-converted)/1e6,lens.label,profile.title,evidence?.offset,exposureMs,realtime))
            }
          } catch (failure: Exception) {
            if (token==epoch && running) { running=false; error("Falha na captura local: ${failure.message}"); closeHardware() }
          }
        },handler)
        openingMs=SystemClock.elapsedRealtime(); lastFrameMs=0
        handler.postDelayed(watchdog,1000)
        manager.openCamera(id,object : CameraDevice.StateCallback() {
          override fun onOpened(camera: CameraDevice) {
            if (token!=epoch || !running) { camera.close(); return }
            device=camera
            @Suppress("DEPRECATION")
            camera.createCaptureSession(listOf(output.surface),object : CameraCaptureSession.StateCallback() {
              override fun onConfigured(active: CameraCaptureSession) {
                if (token!=epoch || !running) { active.close(); return }
                session=active
                runCatching {
                  val request=camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(output.surface)
                    set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,fps)
                    if(Build.VERSION.SDK_INT>=30 && chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.contains(lens.zoom)==true)
                      set(CaptureRequest.CONTROL_ZOOM_RATIO,lens.zoom)
                    // Dynamic stabilization changes framing underneath saved corners.
                    if(chars.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)?.contains(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)==true)
                      set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
                    if(chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)?.contains(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF)==true)
                      set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF)
                    if (chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)?.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)==true)
                      set(CaptureRequest.CONTROL_AF_MODE,CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                  }
                  active.setRepeatingRequest(request.build(),object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(session: CameraCaptureSession,request: CaptureRequest,result: TotalCaptureResult) {
                      if(token==epoch) exposureMs=result.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.div(1e6)
                    }
                  },handler)
                }.onFailure { fail(token,"Não iniciou a câmera: ${it.message}") }
              }
              override fun onConfigureFailed(active: CameraCaptureSession) { active.close(); fail(token,"Perfil de captura recusado. Tente o modo econômico de 30 FPS.") }
            },handler)
          }
          override fun onDisconnected(camera: CameraDevice) { camera.close(); fail(token,"Câmera interrompida por outro aplicativo. Reabra a câmera.") }
          override fun onError(camera: CameraDevice, code: Int) { camera.close(); fail(token,"Câmera indisponível ($code). Feche outros apps que usam a câmera e tente novamente.") }
        },handler)
      }.onFailure { fail(token,it.message ?: "Não abriu a câmera.") }
    }
  }
  private fun fail(token: Int, message: String) { if (token==epoch && !disposed) { running=false; closeHardware(); error(message) } }
  fun stop() { running=false; epoch++; handler.post { closeHardware(); core.reset() } }
  fun close() { disposed=true; running=false; epoch++; handler.post { closeHardware(); thread.quitSafely() } }
  private fun closeHardware() {
    handler.removeCallbacks(watchdog)
    runCatching { session?.stopRepeating() }; runCatching { session?.close() }; session=null
    runCatching { device?.close() }; device=null
    reader?.setOnImageAvailableListener(null,null); runCatching { reader?.close() }; reader=null
    recent.clear()
  }

  companion object {
    /** Stride-aware YUV conversion. Output rotation is shared by video, area and detector. */
    fun convert(image: Image, rotation: Int): Triple<IntArray,Int,Int> {
      val width=image.width; val height=image.height
      val yPlane=image.planes[0]; val u=image.planes[1]; val v=image.planes[2]
      // Bulk reads avoid millions of direct ByteBuffer.get JNI/check calls per second.
      val yb=ByteArray(yPlane.buffer.remaining()).also { yPlane.buffer.duplicate().get(it) }
      val ub=ByteArray(u.buffer.remaining()).also { u.buffer.duplicate().get(it) }
      val vb=ByteArray(v.buffer.remaining()).also { v.buffer.duplicate().get(it) }
      // Image.Plane accessors validate the native Image on every access. Cache
      // strides once: querying them per pixel caused millions of calls per frame.
      return Yuv420.convert(yb,ub,vb,width,height,yPlane.rowStride,u.rowStride,v.rowStride,
        yPlane.pixelStride,u.pixelStride,v.pixelStride,rotation)
    }
  }
}
