package ai.tennisvision.camera

import kotlin.math.*

/** Remove apparent-size scaling before fitting a visible vertical rebound.
 * A pure approach/retreat has no transverse rebound and cannot pass this fit.
 * This is not surface calibration and never overrides the area's line-call guards.
 */
internal object PerspectiveFloorFit {
  fun find(history:List<BallObservation>,width:Int,height:Int):ObservedContactFit.Fit? {
    val end=history.lastOrNull()?.time ?: return null
    val p=history.takeLastWhile{end-it.time<=.42 && it.resolved && it.supportsBallColor &&
      !it.nearHand && !it.blurred && it.radius>=8}
    if(p.size<9 || p.maxOf{it.radius}/p.minOf{it.radius} !in 1.12..1.8)return null
    if(p.zipWithNext().any{(a,b)->b.time-a.time !in .000001.. .050001 || b.radius/a.radius !in .85..1.18})return null
    if(p.maxOf{it.luminance}/max(1.0,p.minOf{it.luminance})>1.4)return null
    val reference=p.map{it.radius}.sorted()[p.size/2]
    val cx=width*.5;val cy=height*.5
    val normalized=p.map{it.copy(x=cx+(it.x-cx)*reference/it.radius,
      y=cy+(it.y-cy)*reference/it.radius,radius=reference)}
    val fit=ObservedContactFit.find(normalized,ContactSurface.FLOOR) ?: return null
    val before=p.lastOrNull{it.time<=fit.time} ?: return null
    val after=p.firstOrNull{it.time>=fit.time} ?: return null
    val t=if(after.time>before.time)(fit.time-before.time)/(after.time-before.time) else 0.0
    val radius=before.radius+(after.radius-before.radius)*t
    val x=cx+(fit.x-cx)*radius/reference;val y=cy+(fit.y-cy)*radius/reference
    if(x !in 0.0..width.toDouble() || y !in 0.0..height.toDouble())return null
    // One-pixel radius uncertainty propagates with distance from the optical centre.
    val error=fit.error*radius/reference+hypot(x-cx,y-cy)/radius+1
    if(error>radius*.75)return null
    return ObservedContactFit.Fit(x,y,fit.time,error,fit.support.map{sample->p.minByOrNull{abs(it.time-sample.time)}!!})
  }
}
