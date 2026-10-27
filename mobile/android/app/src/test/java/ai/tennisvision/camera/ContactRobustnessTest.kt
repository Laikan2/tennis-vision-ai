package ai.tennisvision.camera

import kotlin.math.*
import org.junit.Assert.*
import org.junit.Test

/** Projected synthetic flight, with independently known time/location. Not camera accuracy. */
class ContactRobustnessTest {
  private val w=640;private val h=480
  private val area=listOf(CourtPoint(.1,.1),CourtPoint(.9,.1),CourtPoint(.9,.95),CourtPoint(.1,.95))
  private fun pixels(x:Double,y:Double,radius:Double=8.0):IntArray {
    val p=IntArray(w*h){0xff587082.toInt()}
    for(py in max(0,(y-radius).toInt())..min(h-1,(y+radius).toInt()))
      for(px in max(0,(x-radius).toInt())..min(w-1,(x+radius).toInt()))
        if(hypot(px-x,py-y)<=radius)p[py*w+px]=0xffc7eb4d.toInt()
    return p
  }
  @Test fun observedReboundsWithLocalizationNoiseStillProduceOneCorrectMark() {
    val failures=mutableListOf<String>()
    for(fps in listOf(30,60,120))for(noise in listOf(0.0,1.5,3.0))for(vout in listOf(200.0,500.0)) {
      val core=LocalAnalysis(LocalSettings(polygon=area,floorLine=listOf(CourtPoint(0.0,.12),CourtPoint(1.0,.12))))
      val hits=mutableListOf<LocalImpact>();var observed=0;val contact=.203
      for(i in 0..(fps*.38).toInt()) {
        val t=i.toDouble()/fps;val dt=t-contact;val j=sin(i*2.4)*noise
        val y=340+(if(dt<=0)700*dt else -vout*dt)+400*dt*dt+j
        val r=core.process(pixels(240+240*t,y),w,h,t)
        if(r.observed!=null)observed++
        r.impact?.let(hits::add)
      }
      val label="fps=$fps noise=$noise rebound=$vout observed=$observed hits=${hits.size} calls=${hits.map{it.call}} point=${hits.map{it.point}}"
      println("ROBUST_FLOOR $label")
      if(hits.size!=1 || hits.any{hypot(it.point.x*w-(240+240*contact),it.point.y*h-348)>10})failures.add(label)
    }
    assertTrue(failures.joinToString("\n"),failures.isEmpty())
  }
}

