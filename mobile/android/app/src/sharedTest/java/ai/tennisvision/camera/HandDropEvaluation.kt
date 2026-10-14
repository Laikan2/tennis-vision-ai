package ai.tennisvision.camera

import kotlin.math.*

/** Known synthetic pixels and contact times. This is not a recorded person or a camera benchmark. */
object HandDropEvaluation {
  const val width=640
  const val height=360
  val area=listOf(CourtPoint(.20,.3),CourtPoint(.72,.3),CourtPoint(.72,.94),CourtPoint(.20,.94))
  val settings=LocalSettings(area,listOf(CourtPoint(0.0,.26),CourtPoint(1.0,.26)))
  enum class Action { HOLD, CARRY_TURN, DROP_BOUNCE, FALL_ONLY, HIDDEN, NO_BALL }
  data class Case(val name:String,val action:Action,val skin:Int=0xffbc8062.toInt(),val radius:Int=6,
    val fingers:Boolean=false,val greenFloor:Boolean=false,val outside:Boolean=false,val fps:Int=60,val woodFloor:Boolean=false)
  data class Frame(val pixels:IntArray,val time:Double,val x:Double,val y:Double,val visible:Boolean,val held:Boolean,val falling:Boolean)
  data class Result(val case:Case,val visible:Int,val found:Int,val falseLocations:Int,val heldFound:Int,
    val heldFrames:Int,val fallingFound:Int,val fallingFrames:Int,val calls:List<LineCall>,val error:Double?) {
    fun csv()=listOf(case.name,case.fps,case.radius*2,visible,found,falseLocations,heldFrames,heldFound,fallingFrames,fallingFound,calls.joinToString("|"),error ?: "").joinToString(",")
  }
  val header="case,fps_input,diameter_px,visible,located,false_locations,held_frames,held_located,falling_frames,falling_located,calls,contact_error_px"
  private val turn=listOf(90,102,118,138,162,190,224,264,296,264,224,190,162,138)
  fun cases()=buildList {
    for((name,skin) in listOf("light" to 0xffe2b49a.toInt(),"medium" to 0xffbc8062.toInt(),"dim" to 0xff3b2921.toInt())) {
      add(Case("held_$name",Action.HOLD,skin=skin))
      add(Case("carried_turn_$name",Action.CARRY_TURN,skin=skin,outside=true))
      add(Case("fingers_$name",Action.HOLD,skin=skin,radius=9,fingers=true))
    }
    add(Case("held_green_floor",Action.HOLD,greenFloor=true))
    for(fps in listOf(30,60,120))for(outside in listOf(false,true))
      add(Case("release_${if(outside) "out" else "in"}_$fps",Action.DROP_BOUNCE,outside=outside,fps=fps))
    add(Case("release_green_floor",Action.DROP_BOUNCE,greenFloor=true))
    add(Case("release_wood_floor",Action.DROP_BOUNCE,woodFloor=true))
    add(Case("release_after_fingers",Action.DROP_BOUNCE,radius=9,fingers=true))
    add(Case("release_after_fingers_green_floor",Action.DROP_BOUNCE,radius=9,fingers=true,greenFloor=true))
    add(Case("fall_without_observed_return",Action.FALL_ONLY,outside=true))
    add(Case("fully_hidden",Action.HIDDEN))
    add(Case("hand_without_ball",Action.NO_BALL))
    add(Case("held_tiny",Action.HOLD,radius=2))
  }
  fun frames(case:Case):List<Frame> {
    val x=if(case.outside) 560 else 320
    val ys=when(case.action) {
      Action.CARRY_TURN -> turn
      Action.DROP_BOUNCE -> List(10){90}+turn
      Action.FALL_ONLY -> List(10){90}+turn.take(9)+List(8){-100}
      else -> List(20){90}
    }
    return ys.mapIndexed { i,y ->
      val held=case.action in listOf(Action.HOLD,Action.CARRY_TURN,Action.HIDDEN,Action.NO_BALL) || i<10
      val show=y>0 && case.action !in listOf(Action.HIDDEN,Action.NO_BALL)
      val pixels=IntArray(width*height){if(case.greenFloor)0xff73943e.toInt() else if(case.woodFloor)0xffbc9062.toInt() else 0xff315372.toInt()}
      val hy=if(held)y else 90
      val reach=max(12,case.radius*3)
      for(py in max(0,hy-reach)..min(height-1,hy+reach))for(px in max(0,x-reach)..min(width-1,x+reach))pixels[py*width+px]=case.skin
      if(show)for(py in y-case.radius..y+case.radius)for(px in x-case.radius..x+case.radius)
        if(hypot((px-x).toDouble(),(py-y).toDouble())<=case.radius)pixels[py*width+px]=0xffc7eb4d.toInt()
      if(show && held && case.fingers)for(py in y-case.radius..y+case.radius)for(px in x-1..x+1)pixels[py*width+px]=case.skin
      Frame(pixels,i.toDouble()/case.fps,x.toDouble(),y.toDouble(),show,held,!held && i in 10..18)
    }
  }
  fun evaluate(case:Case):Result {
    val core=LocalAnalysis(settings);var visible=0;var found=0;var wrong=0;var held=0;var heldFound=0;var falling=0;var fallingFound=0
    val events=mutableListOf<LocalImpact>()
    frames(case).forEach { f ->
      if(f.visible) { visible++;if(f.held)held++ else if(f.falling)falling++ }
      val result=core.process(f.pixels,width,height,f.time)
      result.observed?.let { b ->
        if(f.visible && hypot(b.x-f.x,b.y-f.y)<=max(3.0,case.radius*.6)) {
          found++;if(f.held)heldFound++ else if(f.falling)fallingFound++
        } else wrong++
      }
      result.impact?.let(events::add)
    }
    return Result(case,visible,found,wrong,heldFound,held,fallingFound,falling,events.map { it.call },
      events.firstOrNull()?.let { hypot(it.point.x*width-(if(case.outside)560 else 320),it.point.y*height-(296+case.radius+.5)) })
  }
}
