package ai.tennisvision.camera

import kotlin.math.hypot

internal object ObservedBallSpeed {
  fun current(points: List<BallObservation>): Double? {
    if(points.size<3)return null
    val b=points.last();if(b.blurred || b.nearHand || !b.resolved)return null
    var start=points.lastIndex
    // Four samples span only 12.5 ms at 240 FPS. Select a short observed
    // time interval instead of a fixed count, without crossing a tracking gap.
    while(start>0) {
      val prior=points[start-1];val gap=points[start].time-prior.time
      if(prior.blurred || prior.nearHand || !prior.resolved || gap !in .000001.. .075 || b.time-prior.time>.22)break
      start--
      if(points.size-start>=3 && b.time-points[start].time>=.02)break
    }
    val supported=points.subList(start,points.size)
    if(supported.size<3)return null
    val a=supported.first();val dt=b.time-a.time
    if (dt !in .015.. .22) return null
    val radius=supported.map { it.radius }.sorted()[supported.size/2]
    if (radius < 3 || supported.maxOf { it.radius } / supported.minOf { it.radius } > 1.35) return null
    // Apparent lateral speed from the 67 mm ball diameter, NOT a calibrated 3-D/radar measurement.
    val kmh = hypot(b.x-a.x,b.y-a.y)/dt * .067/(radius*2)*3.6
    return kmh.takeIf { it.isFinite() && it in .8..250.0 }
  }


  /** Only the incoming leg, at most 50 ms before contact. A visible short blur
   * may use clean preceding observations; never bridge hand/hidden evidence. */
  fun incoming(history:List<BallObservation>,contactTime:Double):Double? {
    var p=history.filter{it.time<=contactTime+1e-9}
    if(p.isEmpty() || contactTime-p.last().time>.050001)return null
    current(p)?.let{return it}
    var removed=0
    while(p.isNotEmpty() && p.last().blurred && p.last().resolved && !p.last().nearHand && removed<2) {
      p=p.dropLast(1);removed++
      if(p.isEmpty() || contactTime-p.last().time>.050001)return null
      current(p)?.let{return it}
    }
    return null
  }
}
