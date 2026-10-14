package ai.tennisvision.camera

import kotlin.math.abs

enum class LocalCaptureProfile(val title: String,val maxWidth: Int,val maxHeight: Int,val fpsLimit: Int) {
  FAST("Fluidez · até 720p",1280,720,240),
  DETAIL("Bola distante · até 1080p",1920,1080,240),
  ECONOMY("Economia · até 720p / 30 FPS",1280,720,30);
  companion object {
    fun saved(value: String?,legacyEconomy: Boolean=false)=entries.firstOrNull { it.name==value }
      ?: if(legacyEconomy) ECONOMY else FAST
  }
}
data class LocalStreamSize(val width: Int,val height: Int,val maxFps: Int)
object LocalLensLabel {
  fun describe(id: String,angle: Int?): String = if(angle==null) "Câmera $id" else
    "${when { angle<50->"Detalhe";angle>85->"Ampla";else->"Equilibrada" }} · campo ${angle}° · câmera $id"
}
object LocalCaptureChoice {
  fun size(available: List<LocalStreamSize>,profile: LocalCaptureProfile): LocalStreamSize? {
    val supported=available.filter { it.width in 320..profile.maxWidth && it.height in 240..profile.maxHeight && it.maxFps>=15 }
    val wide=supported.filter { abs(it.width.toDouble()/it.height-16.0/9)<.1 }
    return (wide.ifEmpty { supported }).maxByOrNull { it.width*it.height }
  }
  fun fps(ranges: List<Pair<Int,Int>>,size: LocalStreamSize,profile: LocalCaptureProfile): Pair<Int,Int>? {
    val limit=minOf(size.maxFps,profile.fpsLimit)
    // Use an advertised range, not an invented 15-60 range on a device that only
    // lists 15-120. High-speed recording support is not YUV analysis support.
    return ranges.filter { (low,high) -> low>0 && high>=low && high<=limit }
      .maxWithOrNull(compareBy<Pair<Int,Int>> { it.second }.thenBy { it.first })
  }
}
