package ai.tennisvision.camera

import kotlin.math.*

/** Remembers only unresolved/elongated static fragments, never a clean still ball. */
internal class BallClutter {
  private data class Mark(val x:Double,val y:Double,val radius:Double,val since:Double,val last:Double,val count:Int)
  private var marks=emptyList<Mark>()
  fun reset(){marks=emptyList()}
  fun filter(candidates:List<BallObservation>,time:Double):List<BallObservation> {
    val old=marks.filter{time-it.last<=.12};val next=mutableListOf<Mark>()
    val result=candidates.map { b ->
      if(b.nearHand || b.occluded || b.resolved && !b.blurred)return@map b
      val m=old.minByOrNull{hypot(it.x-b.x,it.y-b.y)}?.takeIf{
        hypot(it.x-b.x,it.y-b.y)<=max(3.0,b.radius*.65) && b.radius/it.radius in .60..1.65
      }
      val mark=if(m==null)Mark(b.x,b.y,b.radius,time,time,1) else m.copy(last=time,count=m.count+1)
      if(next.size<96)next.add(mark)
      if(mark.count>=8 && time-mark.since>=.30)b.copy(patterned=true) else b
    }
    marks=next
    return result
  }
}

internal class BallReacquisition {
  private var pending=emptyList<BallObservation>()
  fun reset(){pending=emptyList()}
  fun choose(candidates:List<BallObservation>,still:BallObservation?,backgroundStill:Boolean,width:Int):BallObservation? {
    val current=candidates.filter { b-> b.motionSupport>=.25 && !b.patterned &&
      (still==null || hypot(b.x-still.x,b.y-still.y)>max(12.0,still.radius*3)) }.take(24)
    val supported=if(backgroundStill)current.filter { b->pending.any { p ->
      b.time-p.time in .001.. .10 && b.radius/p.radius in (if(min(b.radius,p.radius)<3).45..2.2 else .60..1.65) &&
      (!b.resolved || !p.resolved || b.luminance/max(1.0,p.luminance) in .65..1.5) &&
      (hypot(b.x-p.x,b.y-p.y) in 2.0..width*.28 || b.nearHand && b.radius>=8)
    }} else emptyList()
    pending=current
    fun score(b:BallObservation)=b.confidence+(if(b.distinctFelt).30 else 0.0)+(if(b.nearHand && b.radius>=8).25 else 0.0)
    val ordered=supported.filter{it.resolved}.ifEmpty{supported}.sortedByDescending(::score)
    return ordered.firstOrNull()?.takeIf{ordered.size==1 || score(it)-score(ordered[1])>=.15}
  }
}
