package ai.tennisvision.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.util.Size
import android.view.SurfaceHolder

data class CameraCapabilities(
  val cameraId: String,
  val characteristics: CameraCharacteristics,
  val profiles: List<StreamProfile>,
) {
  val supportsManualExposure: Boolean get() = characteristics
    .get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
    ?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) == true

  fun describe(): String {
    val rates = profiles.filter { it.highSpeed }.map { it.fps }.distinct().sorted().joinToString("/")
    return if (rates.isEmpty()) "Até ${profiles.maxOf { it.fps }} fps. A câmera/encoder não anunciou combinação compatível de 120/240 fps."
    else "Alta velocidade disponível: $rates fps. Selecione um perfil acima; o FPS real aparece na imagem."
  }

  companion object {
    fun hardwareEncoders(): List<MediaCodecInfo> = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
      .filter { info ->
        info.isEncoder && info.supportedTypes.any { it.equals("video/avc", true) } &&
          if (Build.VERSION.SDK_INT >= 29) info.isHardwareAccelerated && !info.isSoftwareOnly
          else !info.name.startsWith("OMX.google.", true) && !info.name.startsWith("c2.android.", true)
      }

    fun encoderFor(profile: StreamProfile): MediaCodecInfo? = hardwareEncoders().firstOrNull { info ->
      (profile.encoderName == null || info.name == profile.encoderName) && runCatching {
        val caps = info.getCapabilitiesForType("video/avc")
        MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface in caps.colorFormats &&
          caps.videoCapabilities?.areSizeAndRateSupported(profile.width, profile.height, profile.fps.toDouble()) == true &&
          AvcEncoderModes.forCapabilities(caps, profile, caps.videoCapabilities!!.bitrateRange.clamp(profile.bitrate)) != null
      }.getOrDefault(false)
    }

    fun inspect(context: Context): CameraCapabilities {
      val manager = context.getSystemService(CameraManager::class.java)
      val rear = manager.cameraIdList.filter { id ->
        manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
      }
      val id = rear.firstOrNull() ?: error("Nenhuma câmera traseira encontrada.")
      val chars = manager.getCameraCharacteristics(id)
      val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        ?: error("A câmera não informou os modos de vídeo.")
      val sizes = map.getOutputSizes(SurfaceHolder::class.java)?.toSet().orEmpty()
      val ranges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES).orEmpty()
      val stable = StreamProfile.stableProfiles.mapNotNull { profile ->
        val size = Size(profile.width, profile.height)
        val range = ranges.filter { it.contains(profile.fps) && it.upper == profile.fps }
          .minByOrNull { it.upper - it.lower } ?: return@mapNotNull null
        if (size !in sizes || map.getOutputMinFrameDuration(SurfaceHolder::class.java, size).let {
            it > 0 && it > 1_000_000_000.0 / profile.fps * 1.02
          }) return@mapNotNull null
        val withCamera = profile.copy(cameraId = id, fpsLower = range.lower)
        val encoder = encoderFor(withCamera) ?: return@mapNotNull null
        withCamera.copy(encoderName = encoder.name)
      }
      val highSpeed = rear.flatMap { rearId ->
        runCatching {
          val cameraChars = manager.getCameraCharacteristics(rearId)
          if (CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO !in
            (cameraChars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf())) return@runCatching emptyList()
          val config = cameraChars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
          val modes = config.highSpeedVideoSizes.flatMap { size ->
            config.getHighSpeedVideoFpsRangesFor(size).map { HighSpeedMode(size.width, size.height, it.lower, it.upper) }
          }.filter { Size(it.width, it.height) in config.getOutputSizes(SurfaceHolder::class.java).orEmpty() }
          ProfileCatalog.highSpeedProfiles(modes, rearId) { width, height, fps ->
            encoderFor(StreamProfile("", width, height, fps, 24_000_000))?.name
          }
        }.getOrDefault(emptyList())
      }.distinctBy { Triple(it.width, it.height, it.fps) }
        .sortedWith(compareByDescending<StreamProfile> { it.fps }.thenByDescending { it.width * it.height })
      val profiles = stable + highSpeed
      require(profiles.isNotEmpty()) { "Nenhuma combinação de câmera e H.264 por hardware disponível." }
      return CameraCapabilities(id, chars, profiles)
    }
  }
}

