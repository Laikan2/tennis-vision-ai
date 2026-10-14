package ai.tennisvision.camera

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.json.JSONObject
import kotlin.math.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[33])
class MobileParityTest {
  @Test fun sharedIosAndAndroidFixturesHaveTheSameKnownDecisions() {
    val text=requireNotNull(javaClass.classLoader!!.getResourceAsStream("vision-cases.json")).bufferedReader().use { it.readText() }
    val cases=JSONObject(text).getJSONArray("cases")
    assertEquals(41,cases.length())
    for(n in 0 until cases.length()) {
      val c=cases.getJSONObject(n);val fast=c.getString("family")=="fast";val name=c.getString("name")
      val w=if(fast)1280 else 640;val h=if(fast)720 else 360
      val settings=LocalSettings(if(fast)listOf(CourtPoint(.15,.4),CourtPoint(.75,.4),CourtPoint(.75,.95),CourtPoint(.15,.95)) else HandDropEvaluation.area,
        listOf(CourtPoint(0.0,if(fast).3 else .26),CourtPoint(1.0,if(fast).3 else .26)))
      val engine=LocalAnalysis(settings);val fps=c.optInt("fps",60);val action=c.optString("action")
      val turn=listOf(90,102,118,138,162,190,224,264,296,264,224,190,162,138)
      val ys=when(action){"release"->List(10){90}+turn;"carry"->turn;"fall"->List(10){90}+turn.take(9)+List(8){-100};else->List(if(fast)12 else 20){90}}
      var found=0;var wrong=0;val hits=mutableListOf<LocalImpact>()
      for(i in ys.indices) {
        val pixels=IntArray(w*h){c.optString("background","ff315372").toLong(16).toInt()}
        val x:Double;val y:Double;val rx:Double;val ry:Double;val visible:Boolean
        if(fast) {
          val t=i-5-c.optDouble("phase",0.0)
          x=(if(c.optBoolean("outside"))1080.0 else 640.0)+c.optDouble("dx",40.0)*t
          y=if(c.optBoolean("bounce",true))540-32*abs(t) else 370.0
          ry=c.optDouble("diameter",6.0)/2;rx=ry*c.optDouble("blur",1.0)
          visible=!(c.optBoolean("hideContact") && i==5) && x-rx>=1 && x+rx<w-1
        }else {
          x=if(c.optBoolean("outside"))560.0 else 320.0;y=ys[i].toDouble();rx=c.optDouble("radius",6.0);ry=rx
          visible=y>0 && action !in listOf("hidden","no_ball")
          val held=action in listOf("hold","carry","fingers","hidden","no_ball") || i<10
          val hy=if(held)y.toInt() else 90;val reach=max(12,rx.toInt()*3);val skin=c.optString("skin","ffbc8062").toLong(16).toInt()
          for(py in max(0,hy-reach)..min(h-1,hy+reach))for(px in x.toInt()-reach..x.toInt()+reach)pixels[py*w+px]=skin
        }
        if(visible) {
          for(py in max(0,floor(y-ry).toInt())..min(h-1,ceil(y+ry).toInt()))for(px in max(0,floor(x-rx).toInt())..min(w-1,ceil(x+rx).toInt()))
            if(((px-x)/rx).pow(2)+((py-y)/ry).pow(2)<=1)pixels[py*w+px]=0xffc7eb4d.toInt()
          if(action=="fingers" || c.optBoolean("fingers") && i<10)
            for(py in (y-ry).toInt()..(y+ry).toInt())for(px in x.toInt()-1..x.toInt()+1)pixels[py*w+px]=c.optString("skin","ffbc8062").toLong(16).toInt()
        }
        val result=engine.process(pixels,w,h,i.toDouble()/fps)
        result.observed?.let { if(visible && hypot(it.x-x,it.y-y)<=max(3.0,ry*.6))found++ else wrong++ }
        result.impact?.let(hits::add)
      }
      val expected=c.getJSONArray("calls").let { a->(0 until a.length()).map { when(a.getString(it)){"inside"->LineCall.IN;"outside"->LineCall.OUT;else->LineCall.REVIEW} } }
      assertTrue("$name: $found",found>=c.getInt("minLocated"));assertEquals(name,0,wrong);assertEquals(name,expected,hits.map { it.call })
      hits.firstOrNull()?.let { hit->
        val knownX=if(c.optBoolean("outside")){if(fast)1080.0 else 560.0}else{if(fast)640.0 else 320.0}
        val knownY=if(fast)540+c.optDouble("diameter",6.0)/2 else 296+c.optDouble("radius",6.0)+.5
        assertTrue("$name: wrong impact point",hypot(hit.point.x*w-knownX,hit.point.y*h-knownY)<=3)
      }
      println("MOBILE_PARITY $name located=$found wrong=$wrong calls=${hits.map { it.call }}")
    }
  }
}
