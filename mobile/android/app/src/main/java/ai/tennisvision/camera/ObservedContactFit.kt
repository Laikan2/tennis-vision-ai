package ai.tennisvision.camera

import kotlin.math.*

/** A bounded change-point fit to actual observations; never fills missing ball positions. */
internal object ObservedContactFit {
  data class Fit(val x:Double,val y:Double,val time:Double,val error:Double,val support:List<BallObservation>)

  fun find(history:List<BallObservation>,surface:ContactSurface):Fit? {
    val end=history.lastOrNull()?.time ?: return null
    // A hand, uncertain silhouette or long gap breaks free-flight support.
    val clean=history.takeLastWhile { end-it.time<=.32 && it.resolved && it.supportsBallColor && !it.nearHand && !it.blurred }
    if(clean.size<9 || clean.last().time-clean.first().time<.07)return null
    if(clean.zipWithNext().any{(a,b)->b.time-a.time<=0 || b.time-a.time>.075})return null
    val radius=clean.map{it.radius}.sorted()[clean.size/2]
    if(clean.maxOf{it.radius}/clean.minOf{it.radius}>1.35)return null
    // Keep work bounded at high capture rates, preserving real timestamps and endpoints.
    val points=if(clean.size<=40)clean else (0..39).map{clean[it*(clean.size-1)/39]}
    val peak=points.indices.maxByOrNull{points[it].y}!!
    val travel=max(5.0,radius*.9)
    if(surface==ContactSurface.FLOOR && (peak<3 || peak>points.lastIndex-3 ||
      points[peak].y-points.first().y<travel || points[peak].y-points.last().y<travel))return null
    val origin=points.first().time;val span=points.last().time-origin
    val smooth=fit(points,origin,span,null) ?: return null
    var best:Fit?=null;var bestCost=Double.POSITIVE_INFINITY
    // At most 25 hypotheses over the observed interval. A smooth arc must fit
    // materially worse than the velocity change, ruling out ordinary turns/jitter.
    for(i in 0..24) {
      val time=points[3].time+(points[points.lastIndex-3].time-points[3].time)*i/24.0
      if(surface==ContactSurface.FLOOR && abs(time-points[peak].time)>.045)continue
      if(points.count{it.time<time}<3 || points.count{it.time>time}<3)continue
      val model=fit(points,time,span,time) ?: continue
      if(model.rms>max(1.2,radius*.30) || model.error>max(3.5,radius*.55) ||
        model.rms*model.rms>=smooth.rms*smooth.rms*.40)continue
      val vx=model.x[1]/span;val vy=model.y[1]/span
      val ux=(model.x[1]+model.x[3])/span;val uy=(model.y[1]+model.y[3])/span
      val incoming=hypot(vx,vy);val outgoing=hypot(ux,uy)
      if(incoming<20 || outgoing<20)continue
      if(surface==ContactSurface.FLOOR) {
        if(vy<20 || uy> -20 || model.y[2]/(span*span)<-max(50.0,abs(vy-uy)/span*.1))continue
      } else if((vx*ux+vy*uy)/(incoming*outgoing)>=.35)continue
      val before=points.filter{it.time<time};val after=points.filter{it.time>time}
      if(hypot(model.x[0]-before.first().x,model.y[0]-before.first().y)<travel ||
        hypot(model.x[0]-after.last().x,model.y[0]-after.last().y)<travel)continue
      if(model.rms<bestCost) {
        bestCost=model.rms
        val c=points.minByOrNull{abs(it.time-time)}!!
        best=Fit(model.x[0],model.y[0],time,model.error,
          listOf(before.first(),before[before.size/2],c,after[after.size/2],after.last()))
      }
    }
    return best
  }

  private data class Model(val x:DoubleArray,val y:DoubleArray,val rms:Double,val error:Double)
  private fun fit(points:List<BallObservation>,origin:Double,scale:Double,turn:Double?):Model? {
    val count=if(turn==null)3 else 4
    val rows=points.map { val t=(it.time-origin)/scale
      if(turn==null)doubleArrayOf(1.0,t,t*t) else doubleArrayOf(1.0,t,t*t,max(0.0,t)) }
    fun solve(value:(BallObservation)->Double):DoubleArray? {
      val matrix=Array(count){DoubleArray(count+1)}
      for(i in points.indices)for(j in 0 until count) {
        for(k in 0 until count)matrix[j][k]+=rows[i][j]*rows[i][k]
        matrix[j][count]+=rows[i][j]*value(points[i])
      }
      for(j in 0 until count) {
        val pivot=(j until count).maxByOrNull{abs(matrix[it][j])}!!
        if(abs(matrix[pivot][j])<1e-10)return null
        val swap=matrix[j];matrix[j]=matrix[pivot];matrix[pivot]=swap
        val divisor=matrix[j][j];for(k in j..count)matrix[j][k]/=divisor
        for(i in 0 until count)if(i!=j) {val multiplier=matrix[i][j];for(k in j..count)matrix[i][k]-=multiplier*matrix[j][k]}
      }
      return DoubleArray(count){matrix[it][count]}.takeIf{it.all(Double::isFinite)}
    }
    val x=solve{it.x} ?: return null;val y=solve{it.y} ?: return null
    val errors=points.indices.map { i->hypot(rows[i].indices.sumOf{rows[i][it]*x[it]}-points[i].x,
      rows[i].indices.sumOf{rows[i][it]*y[it]}-points[i].y) }
    return Model(x,y,sqrt(errors.sumOf{it*it}/errors.size),errors.max())
  }
}

