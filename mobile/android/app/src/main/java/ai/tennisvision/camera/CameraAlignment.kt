package ai.tennisvision.camera

import kotlin.math.*

data class FrameAlignment(val dx: Int=0,val dy: Int=0,val reliable: Boolean=false,val large: Boolean=false)

/** Small translation only. No extrapolation of a court from a player's motion.
 * Requires textured background agreement distributed over at least three quadrants.
 * Rotation, perspective changes and large moves require marking again. */
object CameraAlignment {
  private fun light(c: Int)=(((c shr 16) and 255)+2*((c shr 8) and 255)+(c and 255))/4
  private data class Feature(val x: Int,val y: Int,val quadrant: Int)
  private data class Score(val error: Double,val support: Double,val quadrants: Int)
  fun estimate(now: IntArray,before: IntArray,width: Int,height: Int,lightShift: Int=0,
    landmarks: List<CourtPoint> = emptyList()): FrameAlignment {
    if(before.size!=width*height || now.size!=before.size || width<80 || height<80) return FrameAlignment()
    val features=ArrayList<Feature>(240)
    val margin=14
    for(y in margin until height-margin step max(8,height/12)) for(x in margin until width-margin step max(8,width/20)) {
      val i=y*width+x
      val gx=abs(light(before[i-2])-light(before[i+2]))
      val gy=abs(light(before[i-2*width])-light(before[i+2*width]))
      if(max(gx,gy)>=20) features.add(Feature(x,y,(if(x>=width/2) 1 else 0)+(if(y>=height/2) 2 else 0)))
    }
    // Thin court lines can fall between a sparse image grid. Sample around the
    // marked perimeter too, but still require independent, distributed agreement.
    if(LocalGeometry.valid(landmarks)) for(edge in 0..3) {
      val a=landmarks[edge];val b=landmarks[(edge+1)%4]
      for(step in 0..8) {
        val fraction=step/8.0
        val px=((a.x+(b.x-a.x)*fraction)*width).toInt().coerceIn(margin,width-margin-1)
        val py=((a.y+(b.y-a.y)*fraction)*height).toInt().coerceIn(margin,height-margin-1)
        var best=0;var bx=px;var by=py
        for(y in py-4..py+4 step 2) for(x in px-4..px+4 step 2) {
          val i=y*width+x
          val gradient=max(abs(light(before[i-2])-light(before[i+2])),abs(light(before[i-2*width])-light(before[i+2*width])))
          if(gradient>best) { best=gradient;bx=x;by=y }
        }
        if(best>=20 && features.none { abs(it.x-bx)<4 && abs(it.y-by)<4 })
          features.add(Feature(bx,by,(if(bx>=width/2) 1 else 0)+(if(by>=height/2) 2 else 0)))
      }
    }
    if(features.size<16 || features.map { it.quadrant }.distinct().size<3) return FrameAlignment()
    fun score(dx: Int,dy: Int): Score {
      var total=0.0;var support=0
      val supported=IntArray(4);val counts=IntArray(4)
      for(f in features) {
        val old=f.y*width+f.x;val current=(f.y+dy)*width+f.x+dx
        var difference=abs(light(now[current])-light(before[old])-lightShift)
        difference+=abs(light(now[current-1])-light(before[old-1])-lightShift)
        difference+=abs(light(now[current+width])-light(before[old+width])-lightShift)
        val error=difference/3.0
        total+=min(error,30.0) // limit the influence of a moving player
        counts[f.quadrant]++
        if(error<=8) { support++;supported[f.quadrant]++ }
      }
      val quadrants=(0..3).count { counts[it]>=3 && supported[it]>=counts[it]*.55 }
      return Score(total/features.size,support.toDouble()/features.size,quadrants)
    }
    val zero=score(0,0)
    if(zero.error<3 && zero.support>=.70 && zero.quadrants>=3) return FrameAlignment(reliable=true)
    var best=zero;var dx=0;var dy=0
    for(y in -8..8) for(x in -8..8) {
      val s=score(x,y)
      if(s.error<best.error) { best=s;dx=x;dy=y }
    }
    val coarseX=dx;val coarseY=dy
    for(y in coarseY-1..coarseY+1) for(x in coarseX-1..coarseX+1) {
      val s=score(x,y)
      if(s.error<best.error) { best=s;dx=x;dy=y }
    }
    if(dx==0 && dy==0 || best.error>=zero.error*.65) {
      // Enough static background remains: do not pause for a person in the foreground.
      return FrameAlignment(reliable=zero.support>=.55 && zero.quadrants>=3)
    }
    val accepted=best.support>=.72 && best.quadrants>=3 && best.error<10
    if(!accepted) return FrameAlignment(large=features.size>=32 && zero.support<.30 && best.support<.55)
    if(abs(dx)>=8 || abs(dy)>=8) return FrameAlignment(large=true)
    return FrameAlignment(dx,dy,reliable=true)
  }
}
