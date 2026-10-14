package ai.tennisvision.camera

import kotlin.math.*

/** Known pixel locations with area sampling and 4:2:0 chroma loss. These are
 * controlled images, not measurements of real metres, km/h or match accuracy. */
object DistanceBallEvaluation {
  const val width=640
  const val height=360
  private const val felt=0xffc7eb4d.toInt()
  data class Case(val name: String,val diameter: Double,val background: Int,
    val chroma420: Boolean=false,val blur: Double=1.0,val clutter: Boolean=false,
    val bounce: Boolean=false,val fps: Int=60)
  data class Frame(val pixels: IntArray,val x: Double,val y: Double,val time: Double)
  data class Measurement(val name: String,val visible: Int,val found: Int,val wrong: Int,
    val maxError: Double,val impacts: List<LocalImpact>) {
    fun csv()=listOf(name,visible,found,wrong,"%.3f".format(java.util.Locale.ROOT,maxError),
      impacts.size,impacts.joinToString("|"){it.call.name}).joinToString(",")
  }
  const val header="case,visible_frames,observed_at_ball,wrong_locations,max_center_error_px,contacts,calls"
  fun cases(): List<Case> = buildList {
    for(d in listOf(3.0,4.0,6.0))for((label,bg) in listOf("white" to 0xffe8e8e8.toInt(),
      "dark" to 0xff394b59.toInt(),"green" to 0xff73943e.toInt())) {
      add(Case("${d.toInt()}px_${label}_420",d,bg,true))
    }
    add(Case("3px_white_fast",3.0,0xffe8e8e8.toInt(),true,fps=120))
    add(Case("4px_dark_blur3",4.0,0xff394b59.toInt(),true,3.0))
    add(Case("4px_green_blur3",4.0,0xff73943e.toInt(),true,3.0))
    add(Case("3px_beside_static_object",3.0,0xff394b59.toInt(),true,clutter=true))
    add(Case("4px_beside_static_object",4.0,0xff394b59.toInt(),true,clutter=true))
    add(Case("4px_known_bounce",4.0,0xff394b59.toInt(),true,bounce=true))
    // Deliberately keep a below-detail case in the report, even if it is missed.
    add(Case("1.5px_below_detail",1.5,0xffe8e8e8.toInt(),true))
  }
  fun spot(p: IntArray,x: Double,y: Double,diameter: Double,blur: Double=1.0,color: Int=felt) {
    val rx=diameter*.5*blur;val ry=diameter*.5
    for(py in max(0,floor(y-ry-1).toInt())..min(height-1,ceil(y+ry+1).toInt()))
      for(px in max(0,floor(x-rx-1).toInt())..min(width-1,ceil(x+rx+1).toInt())) {
        var inside=0
        for(sy in 0..7)for(sx in 0..7) {
          val dx=(px+(sx+.5)/8-x)/rx;val dy=(py+(sy+.5)/8-y)/ry
          if(dx*dx+dy*dy<=1)inside++
        }
        if(inside==0)continue
        val old=p[py*width+px];val a=inside/64.0
        fun channel(shift: Int)=((((old ushr shift)and 255)*(1-a)+((color ushr shift)and 255)*a).roundToInt()).coerceIn(0,255)
        p[py*width+px]=(255 shl 24)or(channel(16)shl 16)or(channel(8)shl 8)or channel(0)
      }
  }
  fun chroma420(p: IntArray) {
    // Average colour differences in each 2×2 block while preserving per-pixel
    // luma. This makes a tiny ball harder than a perfect opaque RGB disk.
    for(y in 0 until height step 2)for(x in 0 until width step 2) {
      val ids=intArrayOf(y*width+x,y*width+x+1,(y+1)*width+x,(y+1)*width+x+1)
      val ys=DoubleArray(4);var u=0.0;var v=0.0
      for(k in 0..3) {
        val c=p[ids[k]];val r=(c ushr 16)and 255;val g=(c ushr 8)and 255;val b=c and 255
        val l=.299*r+.587*g+.114*b;ys[k]=l;u+=(b-l)/4;v+=(r-l)/4
      }
      for(k in 0..3) {
        val noise=((ids[k]*37+17)%7)-3
        val r=(ys[k]+v+noise).roundToInt().coerceIn(0,255)
        val b=(ys[k]+u+noise).roundToInt().coerceIn(0,255)
        val g=(ys[k]-(.299*v+.114*u)/.587+noise).roundToInt().coerceIn(0,255)
        p[ids[k]]=(255 shl 24)or(r shl 16)or(g shl 8)or b
      }
    }
  }
  fun frames(c: Case): List<Frame> = (0 until 16).map { i ->
    val x=75.2+i*28.65
    val y=if(c.bounce) 270.35-abs(i-8)*16 else 112.3+i*3.35
    val p=IntArray(width*height){c.background}
    spot(p,x,y,c.diameter,c.blur)
    if(c.clutter)spot(p,550.0,85.0,18.0)
    if(c.chroma420)chroma420(p)
    // Detector coordinates name pixel centres by integer index; the area
    // sampler above uses pixel boundaries. Match those conventions explicitly.
    Frame(p,x-.5,y-.5,i.toDouble()/c.fps)
  }
  fun evaluate(c: Case): Measurement {
    val polygon=listOf(CourtPoint(.15,.2),CourtPoint(.75,.2),CourtPoint(.75,.95),CourtPoint(.15,.95))
    val core=LocalAnalysis(LocalSettings(polygon=polygon,court=CourtLayout.FULL,
      surface=if(c.bounce)ContactSurface.FLOOR else ContactSurface.TRACK))
    var found=0;var wrong=0;var maxError=0.0;val impacts=mutableListOf<LocalImpact>()
    for(f in frames(c)) {
      val r=core.process(f.pixels,width,height,f.time)
      r.observed?.let { b ->
        val error=hypot(b.x-f.x,b.y-f.y)
        if(error<=max(3.0,c.diameter*.6)) { found++;maxError=max(maxError,error) } else wrong++
      }
      r.impact?.let(impacts::add)
    }
    return Measurement(c.name,16,found,wrong,maxError,impacts)
  }
  @JvmStatic fun main(args: Array<String>) {
    println(header);cases().map(::evaluate).forEach { println(it.csv()) }
  }
}
