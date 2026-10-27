package ai.tennisvision.camera

import kotlin.math.*

/** Apparent-depth reversal is a candidate, never an automatic IN/OUT decision. */
internal object DepthContactFit {
  fun find(history:List<BallObservation>,width:Int,height:Int):ObservedContactFit.Fit? {
    val end=history.lastOrNull()?.time ?: return null
    var p=history.takeLastWhile{end-it.time<=.32 && it.resolved && it.supportsBallColor &&
      !it.nearHand && !it.blurred && it.radius>=8}
    if(p.size<9)return null
    val peak=p.indices.minByOrNull{p[it].radius}!!
    if(peak<3 || peak>p.lastIndex-3)return null
    val minimum=p[peak].radius
    var start=peak;var stop=peak
    while(start>0 && p[start-1].radius<=minimum*1.8)start--
    while(stop<p.lastIndex && p[stop+1].radius<=minimum*1.8)stop++
    p=p.subList(start,stop+1)
    if(p.size<9 || p.last().time!=end || p.zipWithNext().any{(a,b)->
      b.time-a.time !in .000001.. .050001 || b.radius/a.radius !in .78..1.28})return null
    if(p.first().radius<minimum*1.16 || p.last().radius<minimum*1.16)return null
    if(p.maxOf{it.luminance}/max(1.0,p.minOf{it.luminance})>1.4)return null
    val reference=p.map{it.radius}.sorted()[p.size/2]
    // Radius supplies optical depth; x/r removes perspective scaling.
    // These are fit features, not synthetic observations drawn on the video.
    val projected=p.map{it.copy(x=(it.x-width*.5)*reference/it.radius,
      y=reference*reference/it.radius,radius=6.0)}
    val fit=ObservedContactFit.find(projected,ContactSurface.FLOOR) ?: return null
    val before=p.lastOrNull{it.time<=fit.time} ?: return null
    val after=p.firstOrNull{it.time>=fit.time} ?: return null
    val fraction=if(after.time>before.time)(fit.time-before.time)/(after.time-before.time) else 0.0
    val x=before.x+(after.x-before.x)*fraction;val y=before.y+(after.y-before.y)*fraction
    if(x !in 0.0..width.toDouble() || y !in 0.0..height.toDouble())return null
    val support=fit.support.map{sample->p.minByOrNull{abs(it.time-sample.time)}!!}
    return ObservedContactFit.Fit(x,y,fit.time,max(minimum,fit.error),support)
  }
}

