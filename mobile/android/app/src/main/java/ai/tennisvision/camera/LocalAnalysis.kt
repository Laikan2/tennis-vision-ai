package ai.tennisvision.camera

import kotlin.math.*

/** On-device, deterministic green-tennis-ball vision. No socket, model download or PC. */
data class CourtPoint(val x: Double, val y: Double)
enum class ContactSurface { FLOOR, WALL, TRACK }
enum class CourtLayout { CUSTOM, FULL, HALF }
enum class LineCall { IN, OUT, REVIEW, UNMARKED, REJECTED }
data class LocalSettings(
  val polygon: List<CourtPoint> = emptyList(),
  val floorLine: List<CourtPoint> = emptyList(),
  val surface: ContactSurface = ContactSurface.FLOOR,
  val sound: Boolean = true,
  val marks: Boolean = true,
  val court: CourtLayout = CourtLayout.CUSTOM,
  val floorPolygon: List<CourtPoint>? = null,
  val wallPolygon: List<CourtPoint>? = null,
  val floorCourt: CourtLayout? = null,
)
data class BallObservation(val x: Double, val y: Double, val radius: Double,
  val time: Double, val confidence: Double, val nearHand: Boolean = false,
  val blurred: Boolean = false, val luminance: Double=0.0,
  val motionSupport: Double=1.0, val occluded: Boolean=false,
  val colorUncertain: Boolean=false,
  val diameterX: Double=0.0,val diameterY: Double=0.0) {
  val resolved get()=!occluded && !colorUncertain && radius>=2.5 && confidence>=.5
}
data class LocalImpact(val id: Long, val time: Double, val point: CourtPoint,
  val radius: Double, val call: LineCall, val speedKmh: Double?,
  val uncertainty: Double, val reason: String, val surface: ContactSurface,
  val reviewed: LineCall? = null,
  val cameraOffset: CourtPoint=CourtPoint(0.0,0.0),
  val evidenceOffset: CourtPoint=CourtPoint(0.0,0.0)) {
  val decision get() = reviewed ?: call
}
data class LocalResult(val observed: BallObservation?, val predicted: CourtPoint?,
  val trail: List<CourtPoint>, val impact: LocalImpact?, val movingCamera: Boolean,
  val speedKmh: Double?, val message: String,
  val activeArea: LocalSettings?=null, val cameraOffset: CourtPoint=CourtPoint(0.0,0.0),
  val areaNeedsConfirmation:Boolean=false)

object LocalGeometry {
  fun impactPoint(event: LocalImpact,offset: CourtPoint)=CourtPoint(
    event.point.x+offset.x-event.cameraOffset.x,event.point.y+offset.y-event.cameraOffset.y)
  fun valid(points: List<CourtPoint>): Boolean {
    if (points.size != 4 || points.any { !it.x.isFinite() || !it.y.isFinite() || it.x !in 0.0..1.0 || it.y !in 0.0..1.0 }) return false
    val turns = points.indices.map { i ->
      val a = points[i]; val b = points[(i + 1) % 4]; val c = points[(i + 2) % 4]
      (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
    }
    val area = abs(points.indices.sumOf { i -> points[i].x * points[(i + 1) % 4].y - points[(i + 1) % 4].x * points[i].y }) / 2
    return area >= .005 && (turns.all { it > .00001 } || turns.all { it < -.00001 })
  }

  fun validFloorLine(points: List<CourtPoint>) = points.size == 2 && points.all {
    it.x.isFinite() && it.y.isFinite() && it.x in 0.0..1.0 && it.y in 0.0..1.0
  } && abs(points[1].x - points[0].x) > .2

  fun onFloor(point: CourtPoint, line: List<CourtPoint>): Boolean {
    if (!validFloorLine(line)) return false
    val (a, b) = line
    val y = a.y + (point.x - a.x) * (b.y - a.y) / (b.x - a.x)
    return point.y > y + .01
  }

  fun floorDistancePx(point: CourtPoint, line: List<CourtPoint>, width: Int, height: Int): Double? {
    if (!validFloorLine(line)) return null
    val (a,b)=line
    val slope=(b.y-a.y)*height/((b.x-a.x)*width)
    val boundaryY=(a.y+(point.x-a.x)*(b.y-a.y)/(b.x-a.x))*height
    // Positive is below the selected boundary, regardless of click order.
    return (point.y*height-boundaryY)/sqrt(1+slope*slope)
  }

  fun classify(point: CourtPoint, radius: Double, uncertainty: Double,
    polygon: List<CourtPoint>, width: Int, height: Int): LineCall {
    if (!valid(polygon)) return LineCall.UNMARKED
    val p = CourtPoint(point.x * width, point.y * height)
    val vertices = polygon.map { CourtPoint(it.x * width, it.y * height) }
    var inside = false
    var distance = Double.POSITIVE_INFINITY
    vertices.indices.forEach { i ->
      val a = vertices[i]; val b = vertices[(i + 1) % 4]
      if ((a.y > p.y) != (b.y > p.y) && p.x < (b.x-a.x)*(p.y-a.y)/(b.y-a.y)+a.x) inside = !inside
      val dx = b.x-a.x; val dy = b.y-a.y
      val t = (((p.x-a.x)*dx+(p.y-a.y)*dy)/(dx*dx+dy*dy)).coerceIn(0.0, 1.0)
      distance = min(distance, hypot(p.x-a.x-t*dx, p.y-a.y-t*dy))
    }
    // A ball touching the line is IN. An uncertain footprint must be reviewed.
    val signed = if (inside) -distance else distance
    return when {
      signed + uncertainty < radius -> LineCall.IN
      signed - uncertainty > radius -> LineCall.OUT
      else -> LineCall.REVIEW
    }
  }
}

/** Color + connected components + temporal continuity; operates on actual camera pixels. */
class GreenBallDetector {
  private var mask = ByteArray(0)
  private var queue = IntArray(0)
  private var previous = IntArray(0)
  private var previousGreen=ByteArray(0)
  private var rawGreen=ByteArray(0)
  private var strongGreen=ByteArray(0)
  private var contrastCutoffs=IntArray(0)
  private var weakContrastCutoffs=IntArray(0)
  private var lastWidth = 0
  private var lastHeight = 0
  private val contrastReaches=intArrayOf(24,64)
  private fun skin(c: Int): Boolean {
    val r=(c shr 16) and 255;val g=(c shr 8) and 255;val b=c and 255
    return r>=32 && g>=18 && b>=10 && r>g*1.12 && g>b*1.08 && r-g>7
  }
  var alignment=FrameAlignment()
    private set
  fun reset() { previous = IntArray(0) }

  fun detect(pixels: IntArray, width: Int, height: Int, time: Double,
    prior: BallObservation?,landmarks: List<CourtPoint> = emptyList()): Pair<List<BallObservation>, Boolean> {
    require(width > 0 && height > 0 && pixels.size == width * height)
    if (mask.size != pixels.size || width != lastWidth || height != lastHeight) {
      mask = ByteArray(pixels.size); queue = IntArray(pixels.size); previous = IntArray(0)
      previousGreen=ByteArray(pixels.size);rawGreen=ByteArray(pixels.size);strongGreen=ByteArray(pixels.size)
      lastWidth = width; lastHeight = height
    }
    fun luminance(c: Int): Int = (((c shr 16) and 255) + 2 * ((c shr 8) and 255) + (c and 255)) / 4
    var moving = false
    var lightShift = 0
    if (previous.size == pixels.size) {
      val samples = ArrayList<Int>()
      for (y in 8 until height step 16) for (x in 8 until width step 16) {
        val i = y * width + x; samples.add(luminance(pixels[i])-luminance(previous[i]))
      }
      samples.sort()
      lightShift = samples.getOrElse(samples.size/2) { 0 }
      var changed = 0
      val counts=IntArray(4);val changes=IntArray(4);var border=0;var borderChanges=0
      for (y in 8 until height step 16) for (x in 8 until width step 16) {
        val i = y * width + x
        val q=(if(x>=width/2) 1 else 0)+(if(y>=height/2) 2 else 0)
        val edge=x<width*.15 || x>width*.85 || y<height*.15 || y>height*.85
        counts[q]++;if(edge) border++
        if (abs(luminance(pixels[i])-luminance(previous[i])-lightShift) > 24) {
          changed++;changes[q]++;if(edge) borderChanges++
        }
      }
      alignment=CameraAlignment.estimate(pixels,previous,width,height,lightShift,landmarks)
      // A moving player is not a moving camera when the background still agrees.
      moving = changed > samples.size * .28 && borderChanges>border*.30 &&
        (0..3).count { changes[it]>counts[it]*.30 }>=3 && !alignment.reliable
    } else alignment=FrameAlignment()
    val columns=(width+7)/8;val rows=(height+7)/8
    if(contrastCutoffs.size!=columns*rows) {
      contrastCutoffs=IntArray(columns*rows);weakContrastCutoffs=IntArray(columns*rows)
    }
    // Estimate the green background once per 8×8 tile. Re-reading eight
    // neighbors for every green pixel made a full green court unnecessarily slow.
    for(gy in 0 until rows) for(gx in 0 until columns) {
      val x=min(width-1,gx*8+4);val y=min(height-1,gy*8+4)
      var lowest=Int.MAX_VALUE;var outerGreen=false
      var weakLowest=Int.MAX_VALUE;var outerWeak=false
      for(reach in contrastReaches) {
        var sum=0;var greens=0
        var weakSum=0;var weakGreens=0
        fun sample(index: Int) {
          val c=pixels[index];val r=(c shr 16) and 255;val g=(c shr 8) and 255;val b=c and 255
          val strong=g>=45 && r>g*.42 && ((g>=r*.86 && g>b*1.32 && g-b>18) || (g>=r*.70 && g<=r*.90 && g>b*2.4 && g-b>=40))
          val light=(r+2*g+b)/4
          if(strong) { greens++;sum+=light }
          if(strong || (g>=45 && r>g*.55 && g>=r*.92 && g>b*1.14 && g-b>16)) {
            weakGreens++;weakSum+=light
          }
        }
        sample(y*width+max(0,x-reach));sample(y*width+min(width-1,x+reach))
        sample(max(0,y-reach)*width+x);sample(min(height-1,y+reach)*width+x)
        if(greens>=2) lowest=min(lowest,sum/greens)
        if(reach==64) outerGreen=greens>=2
        if(weakGreens>=2)weakLowest=min(weakLowest,weakSum/weakGreens)
        if(reach==64)outerWeak=weakGreens>=2
      }
      // A hand or a resolved ball can fill the nearer samples. Only enforce a
      // green-background cutoff when the wider surroundings support it too.
      contrastCutoffs[gy*columns+gx]=if(!outerGreen || lowest==Int.MAX_VALUE) -1 else lowest+12
      // A desaturated green court can match only the expansion colour. Do not
      // connect its entire surface to the strong ball. Keep this cutoff separate
      // so newly recognised backgrounds cannot erase original strong evidence.
      weakContrastCutoffs[gy*columns+gx]=if(!outerWeak || weakLowest==Int.MAX_VALUE) -1 else weakLowest+12
    }
    val searchRadius=prior?.let { max(14.0,it.radius*3) } ?: 0.0
    for (i in pixels.indices) {
      val c = pixels[i]; val r = (c shr 16) and 255; val g = (c shr 8) and 255; val b = c and 255
      // Yellow-green, including dim felt. Neutral glare and pure saturated green are rejected.
      val strong=g>=45 && r>g*.42 && ((g>=r*.86 && g>b*1.32 && g-b>18) || (g>=r*.70 && g<=r*.90 && g>b*2.4 && g-b>=40))
      strongGreen[i]=if(strong) 1 else 0
      // The edge of a distant ball is mixed with the background by area
      // sampling and 4:2:0 chroma. Retain that modest yellow-green signal too,
      // but report a component without enough original colour as uncertain.
      var selected=strong || (g>=45 && r>g*.55 && g>=r*.92 && g>b*1.14 && g-b>16)
      rawGreen[i]=if(selected) 1 else 0
      if (selected) {
        val old=if(previous.size==pixels.size) previous[i] else 0
        val wasGreen=previousGreen[i].toInt()==1
        val y=i/width;val x=i-y*width
        val tracked=prior!=null && abs(x-prior.x)<=searchRadius && abs(y-prior.y)<=searchRadius &&
          luminance(c)-lightShift>=prior.luminance*.82
        // Separate the brighter ball from a stationary green court, instead of
        // merging both into one giant color component. Departing shadows are excluded.
        // Local contrast also exposes a stationary ball before a serve. A temporal
        // subtraction alone erased it together with the green court.
        if (!tracked && (previous.isEmpty() || wasGreen && luminance(c)-luminance(old)-lightShift<=8)) {
          val tile=(y shr 3)*columns+(x shr 3)
          val cutoff=if(strong) contrastCutoffs[tile] else max(contrastCutoffs[tile],weakContrastCutoffs[tile])
          if(luminance(c)<cutoff) selected=false
        }
      }
      mask[i] = if (selected) 1 else 0
    }
    val found = ArrayList<BallObservation>()
    val bounds=ArrayList<IntArray>();val masses=ArrayList<Int>()
    for (seed in mask.indices) {
      // Weak-only regions were always discarded. Avoid flooding a large wall
      // tint when no strong felt pixel can support an accepted component.
      if (mask[seed].toInt() != 1 || strongGreen[seed].toInt()!=1) continue
      var head = 0; var tail = 1; queue[0] = seed; mask[seed] = 0
      var xmin = width; var xmax = 0; var ymin = height; var ymax = 0
      var sumX = 0.0; var sumY = 0.0; var sumLight=0.0; var changed = 0
      var strongCount=0;var weightSum=0.0;var weightedX=0.0;var weightedY=0.0
      var strongXmin=width;var strongXmax=-1;var strongYmin=height;var strongYmax=-1
      while (head < tail) {
        val i = queue[head++]; val x = i % width; val y = i / width
        xmin = min(xmin, x); xmax = max(xmax, x); ymin = min(ymin, y); ymax = max(ymax, y)
        sumX += x; sumY += y; sumLight+=luminance(pixels[i])
        if(strongGreen[i].toInt()==1) {
          strongCount++;strongXmin=min(strongXmin,x);strongXmax=max(strongXmax,x)
          strongYmin=min(strongYmin,y);strongYmax=max(strongYmax,y)
        }
        val weight=(((pixels[i] shr 8)and 255)-(pixels[i]and 255)).coerceAtLeast(1).toDouble()
        weightSum+=weight;weightedX+=x*weight;weightedY+=y*weight
        if (previous.size == pixels.size && abs(luminance(pixels[i])-luminance(previous[i])-lightShift) > 12) changed++
        fun add(n: Int) { if (mask[n].toInt() == 1) { mask[n] = 0; queue[tail++] = n } }
        if (x > 0) add(i-1)
        if (x+1 < width) add(i+1)
        if (y > 0) add(i-width)
        if (y+1 < height) add(i+width)
      }
      val w = xmax-xmin+1; val h = ymax-ymin+1
      val fill=tail.toDouble()/(w*h)
      val aspect=max(w,h).toDouble()/min(w,h)
      // A weak tint alone is common on compression edges and reflections.
      // Expansion needs at least one pixel with the original felt colour.
      val closeRound=aspect<=1.65 && fill in .55.. .90 && strongCount>=tail*.65
      if (strongCount==0 || tail < 3 || tail > pixels.size * (if(closeRound) .12 else .025) || min(w,h) < 2 ||
        max(w,h) > min(width,height)*(if(closeRound) .42 else .23) || aspect > 8 ||
        fill < .35) continue
      // Solid rectangular logos can follow a bounce-shaped path. A resolved
      // ball (including an elongated blur) leaves corners outside its silhouette.
      // Do not apply this at tiny sizes where pixel quantization dominates shape.
      if (min(w,h)>=6 && fill>.94) continue
      // Chroma weights reduce the centre jumping to a faint compression halo.
      // Keep the old centre for resolved silhouettes and partially hidden grips.
      val x = if(min(w,h)<9) weightedX/weightSum else sumX/tail
      val y = if(min(w,h)<9) weightedY/weightSum else sumY/tail
      val radius = min(w,h)/2.0
      val weak=strongCount<tail*.25
      val haloOnly=weak || (min(w,h)>=5 && min(strongXmax-strongXmin+1,strongYmax-strongYmin+1)<5)
      if(weak && (max(w,h)>12 || aspect>4))continue
      if (xmin == 0 || ymin == 0 || xmax == width-1 || ymax == height-1) continue
      val followed = prior != null && time-prior.time < .14 && hypot(x-prior.x,y-prior.y) < max(30.0, prior.radius*6)
      if(aspect>4.5 && changed<tail*.25 && !followed) continue
      var skin = 0; var movingSkin = 0; var ring = 0
      val reach = max(6, (radius*2.4).toInt())
      for (py in max(0,y.toInt()-reach)..min(height-1,y.toInt()+reach) step 2)
        for (px in max(0,x.toInt()-reach)..min(width-1,x.toInt()+reach) step 2) {
          if (hypot(px-x,py-y) < radius*1.2) continue
          val c = pixels[py*width+px]; val r = (c shr 16) and 255; val g = (c shr 8) and 255; val b = c and 255
          ring++
          if (skin(c)) {
            skin++
            // Wooden floors can share skin colors. Only moving surroundings are hand evidence.
            if (previous.size==pixels.size) {
              val old=previous[py*width+px]; val pr=(old shr 16) and 255; val pg=(old shr 8) and 255; val pb=old and 255
              val wasBall=pg>=45 && pr>pg*.42 && ((pg>=pr*.86 && pg>pb*1.32 && pg-pb>18) || (pg>=pr*.70 && pg<=pr*.90 && pg>pb*2.4 && pg-pb>=40))
              if (!wasBall && abs(luminance(c)-luminance(old)-lightShift)>12) movingSkin++
            }
          }
        }
      found.add(BallObservation(x,y,radius,time,if(haloOnly) min(fill,.45) else if(min(w,h)<5) min(fill,.6) else fill.coerceAtMost(.95),
        ring > 0 && skin > ring*.18 && (movingSkin > ring*.12 || prior?.nearHand==true && skin<ring*.85), aspect>1.6,sumLight/tail,
        if(previous.isEmpty()) 0.0 else changed.toDouble()/tail,colorUncertain=haloOnly,diameterX=w.toDouble(),diameterY=h.toDouble()))
      bounds.add(intArrayOf(xmin,ymin,xmax,ymax));masses.add(tail)
    }
    // A finger can split one ball into two green components. Join only nearby
    // fragments with a compact combined outline and skin pixels across the gap.
    // The joined observation is explicitly occluded and cannot score or measure speed.
    if(found.size in 2..48) {
      val consumed=BooleanArray(found.size);val joined=ArrayList<BallObservation>()
      for(i in found.indices) {
        if(consumed[i]) continue
        var match=-1;var bestDistance=Double.POSITIVE_INFINITY
        for(j in i+1 until found.size) {
          if(consumed[j]) continue
          val a=found[i];val b=found[j];val aa=bounds[i];val bb=bounds[j]
          val w=max(aa[2],bb[2])-min(aa[0],bb[0])+1;val h=max(aa[3],bb[3])-min(aa[1],bb[1])+1
          if(min(w,h)<6 || max(w,h)>min(width,height)*.15 || max(w,h)>min(w,h)*1.55 ||
            (masses[i]+masses[j]).toDouble()/(w*h) !in .40.. .90) continue
          val dx=max(0,max(aa[0],bb[0])-min(aa[2],bb[2])-1)
          val dy=max(0,max(aa[1],bb[1])-min(aa[3],bb[3])-1)
          if(max(dx,dy)>max(4.0,min(w,h)*.22)) continue
          var skinBridge=0
          for(k in 0..4) {
            val f=.35+k*.075;val px=(a.x+(b.x-a.x)*f).toInt();val py=(a.y+(b.y-a.y)*f).toInt()
            if(skin(pixels[py*width+px]))skinBridge++
          }
          val distance=hypot(a.x-b.x,a.y-b.y)
          if(skinBridge>=3 && distance<bestDistance) { bestDistance=distance;match=j }
        }
        if(match<0) { joined.add(found[i]);continue }
        consumed[match]=true
        val a=found[i];val b=found[match];val aa=bounds[i];val bb=bounds[match]
        val total=(masses[i]+masses[match]).toDouble();val weight=masses[i]/total
        val radius=min(max(aa[2],bb[2])-min(aa[0],bb[0])+1,max(aa[3],bb[3])-min(aa[1],bb[1])+1)/2.0
        joined.add(BallObservation(a.x*weight+b.x*(1-weight),a.y*weight+b.y*(1-weight),radius,time,
          min(.6,min(a.confidence,b.confidence)),nearHand=true,luminance=a.luminance*weight+b.luminance*(1-weight),
          motionSupport=max(a.motionSupport,b.motionSupport),occluded=true,
          diameterX=(max(aa[2],bb[2])-min(aa[0],bb[0])+1).toDouble(),diameterY=(max(aa[3],bb[3])-min(aa[1],bb[1])+1).toDouble()))
      }
      found.clear();found.addAll(joined)
    }
    if (previous.size != pixels.size) previous = IntArray(pixels.size)
    pixels.copyInto(previous)
    val reusable=previousGreen;previousGreen=rawGreen;rawGreen=reusable
    return found to moving
  }
}

class LocalAnalysis(var settings: LocalSettings = LocalSettings()) {
  private val detector = GreenBallDetector()
  private val scene=SceneReference()
  private val path = ArrayList<BallObservation>()
  private var lastFrame = -1.0
  private var lastImpact = -10.0
  private var unstableUntil = -1.0
  private var serial = 0L
  private var dimensions = 0 to 0
  private var offset=CourtPoint(0.0,0.0)
  val cameraOffset get()=offset
  private var lost: BallObservation?=null
  private var acquisition: BallObservation?=null
  private var tinyAcquisition: BallObservation?=null
  private var motionAcquisition: BallObservation?=null
  val activeSettings get()=settings.translated(offset)
  fun clearTrack() { path.clear(); lost=null; acquisition=null; tinyAcquisition=null; motionAcquisition=null; lastImpact=-10.0 }
  fun reset() { clearTrack(); detector.reset(); scene.reset(); lastFrame = -1.0; unstableUntil = -1.0; offset=CourtPoint(0.0,0.0) }

  fun process(pixels: IntArray, width: Int, height: Int, time: Double): LocalResult {
    if (dimensions != width to height) { reset(); dimensions = width to height }
    if (!time.isFinite() || time <= lastFrame) return LocalResult(null,null,emptyList(),null,false,null,"Timestamp repetido: quadro descartado")
    if (lastFrame >= 0 && time-lastFrame > .25) path.clear()
    lastFrame = time
    val (candidates, cameraMoved) = detector.detect(pixels,width,height,time,path.lastOrNull(),activeSettings.polygon)
    val motion=detector.alignment
    var sceneChanged=cameraMoved || motion.large
    if(motion.reliable && (motion.dx!=0 || motion.dy!=0)) {
      val next=CourtPoint(offset.x+motion.dx.toDouble()/width,offset.y+motion.dy.toDouble()/height)
      if(abs(next.x)<.035 && abs(next.y)<.035) {
        offset=next
        for(i in path.indices) path[i]=path[i].copy(x=path[i].x+motion.dx,y=path[i].y+motion.dy)
        lost=lost?.let { it.copy(x=it.x+motion.dx,y=it.y+motion.dy) }
      } else { unstableUntil=time+.25; clearTrack();sceneChanged=true }
    }
    if (cameraMoved || motion.large) { unstableUntil = time+.25; clearTrack() }
    scene.update(pixels,width,height,time,sceneChanged,motion.reliable && !sceneChanged && (motion.dx!=0 || motion.dy!=0))
    val blocked=time<unstableUntil || scene.blocked
    var previous = path.lastOrNull()
    // An unconfirmed stationary spare ball/logo must not hide a tiny moving
    // ball forever. Require two moving observations of the alternative, plus
    // continued stationary evidence at the old target, before releasing it.
    val still=previous?.takeIf { !it.nearHand && !it.occluded &&
      path.takeLast(4).all { p -> hypot(p.x-it.x,p.y-it.y)<max(2.0,it.radius*.5) } }
    val alternative=candidates.filter { b -> b.motionSupport>=.25 && !b.nearHand &&
      (still==null || hypot(b.x-still.x,b.y-still.y)>max(12.0,still.radius*3)) }.singleOrNull()
    val pendingMotion=motionAcquisition
    motionAcquisition=alternative
    val backgroundStill=still!=null && candidates.any { it.motionSupport<.20 &&
      hypot(it.x-still.x,it.y-still.y)<max(3.0,still.radius) }
    val motionConfirmed=backgroundStill && alternative!=null && pendingMotion!=null &&
      time-pendingMotion.time in .001.. .10 && alternative.radius/pendingMotion.radius in .45..2.2 &&
      hypot(alternative.x-pendingMotion.x,alternative.y-pendingMotion.y) in 2.0..width*.23
    if(motionConfirmed) {
      path.clear();previous=null;lost=null;acquisition=null;tinyAcquisition=pendingMotion
    }
    val prior2 = path.getOrNull(path.size-2)
    val prediction = if (previous != null && prior2 != null && previous.time > prior2.time) {
      val ratio = ((time-previous.time)/(previous.time-prior2.time)).coerceIn(0.0,2.0)
      CourtPoint(previous.x+(previous.x-prior2.x)*ratio,previous.y+(previous.y-prior2.y)*ratio)
    } else previous?.let { CourtPoint(it.x,it.y) }
    val eligible=candidates.filter { b -> previous==null || b.radius/previous.radius in
      (if(previous.nearHand || previous.occluded) .35..3.5 else .48..2.1) }
    // At acquisition, size alone must not make a larger spare ball/logo the
    // target. Wait for unique motion if several plausible objects are visible.
    // Sub-pixel reflections must not veto a sole resolved/partially visible ball.
    // Two plausible balls remain ambiguous; size alone does not pick the largest.
    val movingEligible=if(previous==null) eligible.filter { it.motionSupport>=.25 }.ifEmpty { eligible } else eligible
    val plausible=if(previous==null) movingEligible.filter { it.resolved }.ifEmpty { movingEligible } else eligible
    val acquisitionPool=if(previous==null && plausible.size>1) plausible.filter { it.motionSupport>=.25 }.ifEmpty { plausible } else plausible
    // A resolved close ball must not be vetoed by a few unrelated 2–6 px wall fragments.
    // Unique moving small balls were already preferred above; two substantial balls stay ambiguous.
    val acquiring=if(previous==null && acquisitionPool.any{it.radius>=max(16.0,min(width,height)*.08) && it.confidence>=.60}) acquisitionPool.filter{it.radius>=4} else acquisitionPool
    val gated=acquiring.mapNotNull { b ->
      if(previous==null) b to -b.confidence*b.radius
      else {
        val dt=time-previous.time
        val step=prior2?.let { hypot(previous.x-it.x,previous.y-it.y)*dt/(previous.time-it.time) } ?: 0.0
        val timeGate=width*(.12*max(1.0,dt*60)).coerceAtMost(.23)
        val gate=if(previous.nearHand) width*.36 else min(width*.23,max(timeGate,step*1.8+previous.radius*4))
        if(hypot(b.x-previous.x,b.y-previous.y)>gate) null
        else b to (hypot(b.x-prediction!!.x,b.y-prediction.y)+abs(b.radius-previous.radius)*4)
      }
    }
    // On release, the fully visible ball can grow suddenly from a small grip.
    // Faint nearby glints must not win purely by being nearer the hand.
    val ranked=(if(previous?.nearHand==true || previous?.occluded==true)
      gated.filter { it.first.resolved }.ifEmpty { gated } else gated).sortedBy { it.second }
    var ball=ranked.firstOrNull()?.first
    // Several equally plausible green components are ambiguity, not permission
    // to draw a trail through a different player, logo or spare ball.
    if(ranked.size>1 && (previous==null || abs(ranked[1].second-ranked[0].second)<max(4.0,(ball?.radius ?: 1.0)*1.5))) ball=null
    if(previous==null && ball!=null && (ball.radius<2.5 || ball.colorUncertain) && !ball.occluded && !motionConfirmed) {
      val tiny=tinyAcquisition;tinyAcquisition=ball
      val distance=tiny?.let { hypot(ball!!.x-it.x,ball!!.y-it.y) } ?: 0.0
      // A stationary 2–3 px reflection is not sufficient to acquire a new ball.
      // Two consistent moving observations can expose it as uncertain.
      if(tiny==null || time-tiny.time !in .001.. .10 || ball.motionSupport<.25 ||
        distance<max(2.0,ball.radius) || distance>width*.15 || ball.radius/tiny.radius !in .6..1.65) ball=null
    } else if(ball!=null) tinyAcquisition=null
    val lock=lost
    if(previous==null && ball!=null && lock!=null && time-lock.time<.65) {
      val nearLast=hypot(ball.x-lock.x,ball.y-lock.y)<max(width*.16,lock.radius*10)
      val pending=acquisition
      val consistent=pending!=null && time-pending.time<.075 && ball.radius/pending.radius in .6..1.65 &&
        hypot(ball.x-pending.x,ball.y-pending.y)<width*.12
      if(!nearLast && (!consistent || ball.motionSupport<.20)) { acquisition=ball;ball=null }
    }
    if (ball == null) {
      val age = previous?.let { time-it.time } ?: 1.0
      if (age > .10) { lost=previous ?: lost;path.clear() }
      return LocalResult(null, prediction?.takeIf { age < .06 }, path.takeLast(16).map { CourtPoint(it.x,it.y) },
        null,blocked,null,if(scene.needsConfirmation) "Treino ativo · confira a área; impactos suspensos" else if(blocked) "Treino ativo · aguardando imagem estável" else if(candidates.size>1) "Alvos verdes ambíguos · aguardando continuidade" else "Bola não localizada · sem inventar posição",activeSettings,offset,scene.needsConfirmation)
    }
    if (previous != null && (time-previous.time > .25 || ball.radius/previous.radius !in
      (if(previous.nearHand || previous.occluded) .35..3.5 else .48..2.1))) path.clear()
    path.add(ball)
    lost=null;acquisition=null
    while (path.size > 40 || (path.isNotEmpty() && time-path.first().time > .6)) path.removeAt(0)
    val impact = if (!blocked) contact(width,height) else null
    val speed = speed(path.takeLast(4))
    return LocalResult(ball,null,path.takeLast(16).map { CourtPoint(it.x,it.y) }, impact,blocked,speed,
      when {
        scene.needsConfirmation -> "Treino ativo · confira a área em Marcar área; impactos suspensos"
        blocked -> "Treino ativo · aguardando a imagem estabilizar; impactos suspensos"
        ball.occluded -> "Bola parcialmente oculta · sem velocidade ou impacto automático"
        ball.colorUncertain -> "Sinal de cor fraco · acompanhamento incerto · revise o contato"
        !ball.resolved -> "Bola ≈${(ball.radius*2).toInt()} px · ${if(min(width,height)>=1080) "filme meia quadra ou aproxime a câmera" else "teste 1080p ou filme mais perto"} · revise IN/OUT"
        ball.nearHand -> "Bola próxima à mão: impacto suspenso"
        ball.blurred -> "Bola borrada observada · velocidade indisponível"
        else -> "Bola observada"
      },activeSettings,offset,scene.needsConfirmation)
  }

  private fun speed(points: List<BallObservation>): Double? {
    if (points.size < 3 || points.any { it.blurred || it.nearHand || !it.resolved }) return null
    val a=points.first(); val b=points.last(); val dt=b.time-a.time
    if (dt !in .015.. .22) return null
    val radius=points.map { it.radius }.sorted()[points.size/2]
    if (radius < 3 || points.maxOf { it.radius } / points.minOf { it.radius } > 1.35) return null
    // Apparent lateral speed from the 67 mm ball diameter, NOT a calibrated 3-D/radar measurement.
    val kmh = hypot(b.x-a.x,b.y-a.y)/dt * .067/(radius*2)*3.6
    return kmh.takeIf { it.isFinite() && it in .8..250.0 }
  }

  private fun contact(width: Int, height: Int): LocalImpact? {
    if (settings.surface == ContactSurface.TRACK || path.size < 5) return null
    val p = path.takeLast(5)
    val a=p[0]; val b=p[1]; val c=p[2]; val d=p[3]; val e=p[4]
    if (c.time-lastImpact < .18 || e.time-a.time > .65 || c.nearHand || (d.nearHand && e.nearHand) ||
      p.zipWithNext().any { (left,right) -> right.time-left.time > .25 }) return null
    val travel = max(3.0,c.radius*.7)
    val floorTurn = c.y-a.y > travel && c.y-e.y > travel && b.y >= a.y && d.y >= e.y &&
      c.y>=b.y && c.y>=d.y
    val incoming=CourtPoint((b.x-a.x)/(b.time-a.time),(b.y-a.y)/(b.time-a.time))
    val outgoing=CourtPoint((e.x-d.x)/(e.time-d.time),(e.y-d.y)/(e.time-d.time))
    val sa=hypot(incoming.x,incoming.y);val sb=hypot(outgoing.x,outgoing.y)
    val wallTurn=sa>80 && sb>80 && (incoming.x*outgoing.x+incoming.y*outgoing.y)/(sa*sb)<.35
    if (settings.surface == ContactSurface.FLOOR && !floorTurn) return null
    if (settings.surface == ContactSurface.WALL && !wallTurn) return null
    // The observed local maximum need not be the instant of contact. Estimate
    // the crossing of the observed incoming/outgoing legs, bounded by b and d.
    // This is two-sided interpolation, never an extrapolation from a lost ball.
    val fit=if (settings.surface==ContactSurface.FLOOR) floorContact(p) else wallContact(p)
    if(settings.surface==ContactSurface.WALL && fit==null) return null
    // REVIEW must have contact evidence too; never accumulate arbitrary tiny colour fragments.
    if(!c.resolved && (p.maxOf{it.radius}/p.minOf{it.radius}>1.65 ||
      p.maxOf{it.luminance}/max(1.0,p.minOf{it.luminance})>1.8 ||
      settings.surface==ContactSurface.FLOOR && fit==null)) return null
    val contactTime=fit?.time ?: c.time
    val point=CourtPoint((fit?.x ?: c.x)/width,
      ((fit?.y ?: c.y)+if (settings.surface==ContactSurface.FLOOR) c.radius else 0.0)/height)
    val intervals=p.zipWithNext().map { (left,right)->right.time-left.time }.sorted()
    val missingFrame=intervals.last()>intervals[1]*1.55+.001
    val sparse=intervals.last()>.075 || missingFrame
    val uncertainty=max(2.0,c.radius*.3 + (if (c.blurred) c.radius*.6 else 0.0) +
      (fit?.error ?: 0.0) +
      if (sparse) max(hypot(c.x-b.x,c.y-b.y),hypot(d.x-c.x,d.y-c.y))*.5 else 0.0)
    val region=activeSettings
    val floorDistance=if(settings.surface==ContactSurface.FLOOR && settings.court==CourtLayout.CUSTOM)
      LocalGeometry.floorDistancePx(point,region.floorLine,width,height) else null
    // A reversal clearly above a marked floor is not even a floor-contact candidate.
    // Keep near-boundary uncertainty for review and continue detecting outside the IN polygon.
    if (floorDistance!=null && floorDistance + uncertainty < 0.0) return null
    var call=LocalGeometry.classify(point,c.radius,uncertainty,region.polygon,width,height)
    val courtKnown=settings.court!=CourtLayout.CUSTOM && LocalGeometry.valid(region.polygon)
    val floorKnown = settings.surface != ContactSurface.FLOOR || courtKnown || LocalGeometry.onFloor(point,region.floorLine)
    if (!floorKnown && call != LineCall.UNMARKED) call=LineCall.REVIEW
    if (sparse && call != LineCall.UNMARKED) call=LineCall.REVIEW
    if (settings.surface==ContactSurface.FLOOR && fit==null && call!=LineCall.UNMARKED) call=LineCall.REVIEW
    if (p.any { it.nearHand } && call != LineCall.UNMARKED) call=LineCall.REVIEW
    if (p.any { !it.resolved } && call != LineCall.UNMARKED) call=LineCall.REVIEW
    // Monocular wall turns cannot distinguish a racket/hand return from contact with the wall.
    if (settings.surface == ContactSurface.WALL && call != LineCall.UNMARKED) call=LineCall.REVIEW
    val reason=when {
      settings.surface == ContactSurface.WALL -> "Mudança de direção observada; contato com a parede estimado entre quadros. Confirme na revisão."
      p.any { !it.resolved } -> "Bola pequena demais para uma decisão automática. Confira o contato e a linha."
      !floorKnown && floorDistance==null -> "Marque o limite do piso; trajetória sozinha não comprova chão."
      !floorKnown -> "Contato próximo ao limite do piso. Confirme se a bola tocou o chão."
      sparse -> "Quadros espaçados ou ausentes perto do contato. Posição estimada; revise antes de decidir."
      fit==null -> "Não foi possível localizar o instante do contato. Confirme na revisão."
      p.any { it.nearHand } -> "Possível mão perto da trajetória. Confirme o contato antes de decidir."
      call == LineCall.REVIEW -> "A incerteza da marca alcança a linha. Confira o contato."
      call == LineCall.UNMARKED -> "Quicada candidata; selecione a área para classificar IN/OUT."
      courtKnown -> "Mudança de trajetória compatível com quicada na quadra marcada; contato estimado."
      else -> "Descida e subida observadas no piso; contato estimado entre quadros."
    }
    val result=LocalImpact(++serial,contactTime,point,c.radius,call,speed(p.take(3)),uncertainty,reason,settings.surface,
      cameraOffset=offset,evidenceOffset=offset)
    lastImpact=contactTime
    path.removeAll { it.time <= c.time }
    return result
  }

  private data class ContactFit(val x: Double,val y: Double,val time: Double,val error: Double)
  private fun wallContact(p: List<BallObservation>): ContactFit? {
    val (a,b,c,d,e)=p
    val vx=(b.x-a.x)/(b.time-a.time);val vy=(b.y-a.y)/(b.time-a.time)
    val ux=(e.x-d.x)/(e.time-d.time);val uy=(e.y-d.y)/(e.time-d.time)
    val bx=a.x+vx*(c.time-a.time);val by=a.y+vy*(c.time-a.time)
    val ax=d.x+ux*(c.time-d.time);val ay=d.y+uy*(c.time-d.time)
    val dx=vx-ux;val dy=vy-uy;val den=dx*dx+dy*dy
    if(den<=.000001) return null
    val time=c.time+((ax-bx)*dx+(ay-by)*dy)/den
    if(!time.isFinite() || time<b.time || time>d.time) return null
    val x1=a.x+vx*(time-a.time);val y1=a.y+vy*(time-a.time)
    val x2=d.x+ux*(time-d.time);val y2=d.y+uy*(time-d.time)
    val mx=if(c.time<=time)bx else ax;val my=if(c.time<=time)by else ay
    val error=hypot(mx-c.x,my-c.y)+hypot(x1-x2,y1-y2)*.5
    if(!error.isFinite() || error>max(4.0,c.radius*2)) return null
    return ContactFit((x1+x2)/2,(y1+y2)/2,time,error)
  }
  private fun floorContact(p: List<BallObservation>): ContactFit? {
    val (a,b,c,d,e)=p
    val up=(b.y-a.y)/(b.time-a.time);val down=(e.y-d.y)/(e.time-d.time)
    if (up<=0 || down>=0) return null
    val beforeAtC=a.y+up*(c.time-a.time);val afterAtC=d.y+down*(c.time-d.time)
    val time=c.time+(afterAtC-beforeAtC)/(up-down)
    if (!time.isFinite() || time<b.time || time>d.time) return null
    val vx=(b.x-a.x)/(b.time-a.time);val ux=(e.x-d.x)/(e.time-d.time)
    val x1=a.x+vx*(time-a.time);val x2=d.x+ux*(time-d.time)
    val y=a.y+up*(time-a.time)
    val onIncoming=c.time<=time
    val modelX=if (onIncoming) a.x+vx*(c.time-a.time) else d.x+ux*(c.time-d.time)
    val modelY=if (onIncoming) beforeAtC else afterAtC
    val error=hypot(modelX-c.x,modelY-c.y)+abs(x1-x2)*.5
    if (!error.isFinite() || error>max(c.radius*4,8.0)) return null
    // Curved/decelerating legs can make a straight-line fit overshoot even an
    // observed contact. Keep the observed maximum rather than moving its mark
    // beyond the ball; retain the model residual as uncertainty.
    if(error>max(2.0,c.radius*.35)) return ContactFit(c.x,c.y,c.time,error)
    return ContactFit((x1+x2)*.5,max(y,c.y),time,error)
  }
}
