package ai.tennisvision.camera

import kotlin.math.*

/** Known pixel ground truth, not a claim about lens quality or real match accuracy. */
object FastBallEvaluation {
  const val width=1280
  const val height=720
  data class Case(val name: String,val diameter: Double,val dx: Double,val blur: Double=1.0,
    val phase: Double=0.0,val outside: Boolean=false,val occludeContact: Boolean=false,
    val bounce: Boolean=true,val fps: Int=60)
  data class Result(val case: Case,val visible: Int,val correctlyLocated: Int,val falseLocations: Int,
    val calls: List<LineCall>,val contactErrorPx: Double?,val responseDelayMs: Double?,
    val analysisP50Ms: Double,val analysisP95Ms: Double) {
    fun csv()=listOf(case.name,case.diameter,case.dx,case.blur,case.phase,case.fps,visible,
      correctlyLocated,falseLocations,calls.joinToString("|"),contactErrorPx ?: "",responseDelayMs ?: "",
      analysisP50Ms,analysisP95Ms).joinToString(",")
  }
  val header="case,diameter_px,dx_px_per_frame,blur_ratio,contact_phase,fps_input,visible_frames,located_frames,false_locations,calls,contact_error_px,response_delay_ms,analysis_p50_ms,analysis_p95_ms"
  fun cases()=buildList {
    for (diameter in listOf(3.0,6.0,12.0)) for (dx in listOf(20.0,40.0,80.0)) {
      add(Case("diam${diameter.toInt()}_step${dx.toInt()}",diameter,dx))
    }
    for (blur in listOf(2.0,4.0,6.0)) add(Case("blur${blur.toInt()}",6.0,40.0,blur))
    for (phase in listOf(.25,.5,.75)) add(Case("between_frames_$phase",6.0,40.0,phase=phase))
    add(Case("out_fast",6.0,30.0,outside=true))
    add(Case("out_contact_hidden",6.0,30.0,outside=true,occludeContact=true))
    add(Case("air_crossing",6.0,60.0,bounce=false))
    add(Case("30fps_fast",6.0,80.0,fps=30))
  }
  fun evaluate(case: Case): Result = evaluateWithFloor(case,.3)
  fun evaluateWithFloor(case: Case,floorLineY: Double): Result {
    val settings=LocalSettings(listOf(CourtPoint(.15,.4),CourtPoint(.75,.4),CourtPoint(.75,.95),CourtPoint(.15,.95)),
      listOf(CourtPoint(0.0,floorLineY),CourtPoint(1.0,floorLineY)))
    val core=LocalAnalysis(settings)
    val contactX=if (case.outside) 1080.0 else 640.0
    val contactY=540.0
    val contactFrame=5.0+case.phase
    val groundTime=contactFrame/case.fps
    var visible=0;var located=0;var falseLocations=0
    val contacts=mutableListOf<Pair<LocalImpact,Double>>();val costs=mutableListOf<Double>()
    for (i in 0..11) {
      val t=i-contactFrame;val x=contactX+case.dx*t
      val y=if (case.bounce) contactY-32*abs(t) else 370.0
      val rx=case.diameter/2*case.blur;val ry=case.diameter/2
      val shown=!(case.occludeContact && i==5) && x-rx>=1 && x+rx<width-1 && y-ry>=1 && y+ry<height-1
      val pixels=IntArray(width*height) { 0xff315372.toInt() }
      if (shown) {
        visible++
        for (py in floor(y-ry).toInt()..ceil(y+ry).toInt())
          for (px in floor(x-rx).toInt()..ceil(x+rx).toInt())
            if (((px-x)/rx).pow(2)+((py-y)/ry).pow(2)<=1.0) pixels[py*width+px]=0xffc7eb4d.toInt()
      }
      val started=System.nanoTime();val result=core.process(pixels,width,height,i.toDouble()/case.fps)
      costs.add((System.nanoTime()-started)/1e6)
      result.observed?.let {
        if (shown && hypot(it.x-x,it.y-y)<=max(2.0,case.diameter/2))located++ else falseLocations++
      }
      result.impact?.let { contacts.add(it to i.toDouble()/case.fps) }
    }
    costs.sort()
    val closest=contacts.minByOrNull { abs(it.first.time-groundTime) }
    return Result(case,visible,located,falseLocations,contacts.map { it.first.call },
      closest?.let { hypot(it.first.point.x*width-contactX,it.first.point.y*height-(contactY+case.diameter/2)) },
      closest?.let { (it.second-groundTime)*1000 },costs[costs.size/2],costs[(costs.size*.95).toInt()])
  }
}
