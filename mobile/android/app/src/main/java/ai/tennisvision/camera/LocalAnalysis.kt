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
  val diameterX: Double=0.0,val diameterY: Double=0.0,val distinctFelt: Boolean=false,val feltChroma: Double=0.0,
  val greenDominant: Boolean=false, val contrastFelt: Boolean=false,
  val patterned: Boolean=false) {
  val resolved get()=!occluded && !colorUncertain && !patterned && radius>=2.5 && confidence>=.5
  val supportsBallColor get()=greenDominant || feltChroma>=107 || contrastFelt
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
  val areaNeedsConfirmation:Boolean=false, val diagnostics:TrackingDiagnostics=TrackingDiagnostics())

object LocalGeometry {
  fun impactPoint(event: LocalImpact,offset: CourtPoint)=CourtPoint(
    event.point.x+offset.x-event.cameraOffset.x,event.point.y+offset.y-event.cameraOffset.y)
  fun valid(points: List<CourtPoint>)=validShape(points,0.0)
  fun projectedPolygon(points:List<CourtPoint>)=validShape(points,.035)
  private fun validShape(points: List<CourtPoint>,margin:Double): Boolean {
    if (points.size != 4 || points.any { !it.x.isFinite() || !it.y.isFinite() || it.x < -margin || it.x > 1+margin || it.y < -margin || it.y > 1+margin }) return false
    val turns = points.indices.map { i ->
      val a = points[i]; val b = points[(i + 1) % 4]; val c = points[(i + 2) % 4]
      (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
    }
    val area = abs(points.indices.sumOf { i -> points[i].x * points[(i + 1) % 4].y - points[(i + 1) % 4].x * points[i].y }) / 2
    return area >= .005 && (turns.all { it > .00001 } || turns.all { it < -.00001 })
  }

  fun projectedFloorLine(points: List<CourtPoint>) = points.size == 2 && points.all {
    it.x.isFinite() && it.y.isFinite()
  } && abs(points[1].x-points[0].x)>.2

  fun validFloorLine(points: List<CourtPoint>) = projectedFloorLine(points) && points.all {
    it.x.isFinite() && it.y.isFinite() && it.x in 0.0..1.0 && it.y in 0.0..1.0
  } && abs(points[1].x - points[0].x) > .2

  fun onFloor(point: CourtPoint, line: List<CourtPoint>): Boolean {
    if (!projectedFloorLine(line)) return false
    val (a, b) = line
    val y = a.y + (point.x - a.x) * (b.y - a.y) / (b.x - a.x)
    return point.y > y + .01
  }

  fun floorDistancePx(point: CourtPoint, line: List<CourtPoint>, width: Int, height: Int): Double? {
    if (!projectedFloorLine(line)) return null
    val (a,b)=line
    val slope=(b.y-a.y)*height/((b.x-a.x)*width)
    val boundaryY=(a.y+(point.x-a.x)*(b.y-a.y)/(b.x-a.x))*height
    // Positive is below the selected boundary, regardless of click order.
    return (point.y*height-boundaryY)/sqrt(1+slope*slope)
  }

  /** Clip only the non-floor half-plane to the image for honest editor feedback. */
  fun excludedFloorRegion(line:List<CourtPoint>):List<CourtPoint> {
    if(!projectedFloorLine(line))return emptyList()
    val (a,b)=line
    fun signed(p:CourtPoint)=p.y-a.y-(p.x-a.x)*(b.y-a.y)/(b.x-a.x)
    val box=listOf(CourtPoint(0.0,0.0),CourtPoint(1.0,0.0),CourtPoint(1.0,1.0),CourtPoint(0.0,1.0))
    val clipped=mutableListOf<CourtPoint>()
    for(i in box.indices) {
      val p=box[i];val q=box[(i+1)%box.size];val d=signed(p);val e=signed(q)
      if(d<0)clipped.add(p)
      if((d<0)!=(e<0)){val t=d/(d-e);clipped.add(CourtPoint(p.x+(q.x-p.x)*t,p.y+(q.y-p.y)*t))}
    }
    return clipped.takeIf{it.size>=3} ?: emptyList()
  }

  fun classify(point: CourtPoint, radius: Double, uncertainty: Double,
    polygon: List<CourtPoint>, width: Int, height: Int): LineCall {
    if (!projectedPolygon(polygon)) return LineCall.UNMARKED
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
  private var chroma=ByteArray(0)
  private var contrastCutoffs=IntArray(0)
  private var weakContrastCutoffs=IntArray(0)
  private var lastWidth = 0
  private var lastHeight = 0
  private var contrastReaches=intArrayOf(24,64)
  private fun skin(c: Int): Boolean {
    val r=(c shr 16) and 255;val g=(c shr 8) and 255;val b=c and 255
    return r>=32 && g>=18 && b>=10 && r>g*1.12 && g>b*1.08 && r-g>7
  }
  private fun gripSkin(c: Int): Boolean {
    val r=(c shr 16)and 255;val g=(c shr 8)and 255;val b=c and 255
    // A bounded pink/red grip is different from a uniformly warm wood surround.
    return r>=32 && g>=18 && b>=10 && r*100>g*110 && r*100>b*120 && r-g>10 && g-b<=max(10,(r-g)*4/5)
  }
  var alignment=FrameAlignment()
    private set
  fun reset() { previous = IntArray(0) }

  private fun markPatterned(found:MutableList<BallObservation>) {
    val texture=found.filter { it.radius<=12 && it.supportsBallColor }.take(192)
    for(i in found.indices) {
      val b=found[i];if(b.radius>12 || b.occluded)continue
      val reach=max(24.0,b.radius*6);var neighbours=0;var mass=0.0
      for(q in texture) {
        if(q===b || q.radius>b.radius*2.5 || hypot(q.x-b.x,q.y-b.y)>reach)continue
        neighbours++;mass+=q.radius*q.radius
        if(neighbours>=3 && mass>=b.radius*b.radius*.35)break
      }
      if(neighbours>=3 && mass>=b.radius*b.radius*.35)found[i]=b.copy(patterned=true)
    }
  }
  private fun contextualGrip(ball:BallObservation,pixels:IntArray,width:Int,height:Int):BallObservation {
    if(ball.supportsBallColor || ball.feltChroma<75)return ball
    val reach=max(8,ceil(ball.radius*3).toInt());var sum=0L;var n=0
    for(py in max(0,ball.y.toInt()-reach)..min(height-1,ball.y.toInt()+reach) step 2)
      for(px in max(0,ball.x.toInt()-reach)..min(width-1,ball.x.toInt()+reach) step 2) {
        if(hypot(px-ball.x,py-ball.y)<ball.radius*1.8)continue
        val color=pixels[py*width+px];val r=(color shr 16)and 255;val g=(color shr 8)and 255;val blue=color and 255
        sum+=max(0,g-blue)*255/max(1,max(r,g));n++
      }
    return if(n>0 && ball.feltChroma-sum.toDouble()/n>=32)ball.copy(contrastFelt=true) else ball
  }

  fun detect(pixels: IntArray, width: Int, height: Int, time: Double,
    prior: BallObservation?,landmarks: List<CourtPoint> = emptyList()): Pair<List<BallObservation>, Boolean> {
    require(width > 0 && height > 0 && pixels.size == width * height)
    if (mask.size != pixels.size || width != lastWidth || height != lastHeight) {
      mask = ByteArray(pixels.size); queue = IntArray(pixels.size); previous = IntArray(0)
      previousGreen=ByteArray(pixels.size);rawGreen=ByteArray(pixels.size);strongGreen=ByteArray(pixels.size);chroma=ByteArray(pixels.size)
      lastWidth = width; lastHeight = height
      val outerReach=max(64,min(width,height)/3)
      contrastReaches=if(outerReach==64)intArrayOf(24,64)else intArrayOf(24,64,outerReach)
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
    contrastCutoffs.fill(-2)
    fun ensureContrast(tile:Int) {
      if(contrastCutoffs[tile]!=-2)return
      val gx=tile%columns;val gy=tile/columns
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
        if(reach==contrastReaches.last()) outerGreen=greens>=2
        if(weakGreens>=2)weakLowest=min(weakLowest,weakSum/weakGreens)
        if(reach==contrastReaches.last())outerWeak=weakGreens>=2
      }
      // A hand or a resolved ball can fill the nearer samples. Only enforce a
      // green-background cutoff when the wider surroundings support it too.
      // The outer ring scales with resolution: a close ball wider than 128px
      // must not be erased by samples that are still inside its own silhouette.
      contrastCutoffs[gy*columns+gx]=if(!outerGreen || lowest==Int.MAX_VALUE) -1 else lowest+12
      // A desaturated green court can match only the expansion colour. Do not
      // connect its entire surface to the strong ball. Keep this cutoff separate
      // so newly recognised backgrounds cannot erase original strong evidence.
      weakContrastCutoffs[gy*columns+gx]=if(!outerWeak || weakLowest==Int.MAX_VALUE) -1 else weakLowest+12
    }
    // Dense colour backgrounds keep one eager tile pass. Sparse frames defer
    // each tile until needed; both paths preserve all original pixel evidence.
    var greenSamples=0;var samples=0
    for(y in 4 until height step 32)for(x in 4 until width step 32) {
      val c=pixels[y*width+x];val r=(c shr 16)and 255;val g=(c shr 8)and 255;val b=c and 255;samples++
      val strong=g>=45 && r>g*.42 && ((g>=r*.86 && g>b*1.32 && g-b>18) || (g>=r*.70 && g<=r*.90 && g>b*2.4 && g-b>=40))
      if(strong || (g>=45 && r>g*.55 && g>=r*.92 && g>b*1.14 && g-b>16))greenSamples++
    }
    val lazy=greenSamples*4<max(1,samples)
    if(!lazy)for(tile in contrastCutoffs.indices)ensureContrast(tile)
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
        chroma[i]=((g-b).coerceAtLeast(0)*255/max(1,max(r,g))).toByte()
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
          if(lazy)ensureContrast(tile)
          val cutoff=if(strong) contrastCutoffs[tile] else max(contrastCutoffs[tile],weakContrastCutoffs[tile])
          if(luminance(c)<cutoff) selected=false
        }
      }
      mask[i] = if (selected) 1 else 0
    }
    val found = ArrayList<BallObservation>()
    val bounds=ArrayList<IntArray>();val masses=ArrayList<Int>()
    val clippedFelt=ArrayList<IntArray>()
    // Extract saturated felt before the weak expansion pixels. A warm wooden
    // floor can satisfy the old colour mask and connect directly to a held
    // ball, turning it into an enormous irregular component. Pixels are still
    // consumed at most once; the second scan preserves faint/distant evidence.
    for (pass in 0..1) for (seed in mask.indices) {
      // Weak-only regions were always discarded. Avoid flooding a large wall
      // tint when no strong felt pixel can support an accepted component.
      if (mask[seed].toInt() != 1 || strongGreen[seed].toInt()!=1 || pass==0 && (chroma[seed].toInt() and 255)<82) continue
      var head = 0; var tail = 1; queue[0] = seed; mask[seed] = 0
      var xmin = width; var xmax = 0; var ymin = height; var ymax = 0
      var sumX = 0.0; var sumY = 0.0; var sumLight=0.0; var sumChroma=0L;var sumRed=0L;var sumGreen=0L;var sumBlue=0L; var changed = 0
      var strongCount=0;var weightSum=0.0;var weightedX=0.0;var weightedY=0.0
      var strongXmin=width;var strongXmax=-1;var strongYmin=height;var strongYmax=-1
      while (head < tail) {
        val i = queue[head++]; val x = i % width; val y = i / width
        xmin = min(xmin, x); xmax = max(xmax, x); ymin = min(ymin, y); ymax = max(ymax, y)
        sumX += x; sumY += y; sumLight+=luminance(pixels[i])
        sumChroma+=(chroma[i].toInt() and 255)
        sumRed+=((pixels[i] shr 16)and 255);sumGreen+=((pixels[i] shr 8)and 255);sumBlue+=(pixels[i]and 255)
        if(strongGreen[i].toInt()==1) {
          strongCount++;strongXmin=min(strongXmin,x);strongXmax=max(strongXmax,x)
          strongYmin=min(strongYmin,y);strongYmax=max(strongYmax,y)
        }
        val weight=(((pixels[i] shr 8)and 255)-(pixels[i]and 255)).coerceAtLeast(1).toDouble()
        weightSum+=weight;weightedX+=x*weight;weightedY+=y*weight
        if (previous.size == pixels.size && abs(luminance(pixels[i])-luminance(previous[i])-lightShift) > 12) changed++
        // Preserve the few low-chroma edge pixels of a distant 4:2:0 ball,
        // but never let this local expansion flood an entire warm floor.
        fun add(n: Int) { if (mask[n].toInt() == 1 && (pass==1 || (chroma[n].toInt() and 255)>=71 ||
            abs(n%width-seed%width)<=4 && abs(n/width-seed/width)<=4)) { mask[n] = 0; queue[tail++] = n } }
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
      val saturated=sumChroma.toDouble()/tail>=107 && strongCount>=tail*.65 && sumRed>=sumGreen*.78
      // A printed mark can isolate a tiny felt island inside a ball that is
      // cut off by the image border. Keep its enclosing silhouette even when
      // the incomplete ball itself is discarded; its islands are not new balls.
      if(saturated && min(w,h)>=12 && max(w,h)<=min(width,height)*.8 &&
        aspect<=4 && fill>=.35 && (xmin==0 || ymin==0 || xmax==width-1 || ymax==height-1))
        clippedFelt.add(intArrayOf(xmin,ymin,xmax,ymax))
      val closePartial=aspect<=1.65 && fill in .40.. .55 && min(w,h)>=16 && saturated
      val seamFragment=aspect in 1.65..2.35 && fill in .65.. .85 && min(w,h)>=12 && saturated
      val close=closeRound || closePartial || seamFragment
      if (strongCount==0 || tail < 3 || tail > pixels.size * (if(close) .12 else .025) || min(w,h) < 2 ||
        max(w,h) > min(width,height)*(if(close) .60 else .23) || aspect > 8 ||
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
      if(radius<=12) {
        // Tiny bright felt inside a much larger dark silhouette is a highlight,
        // not a separate resolved ball. Keep far balls without that surround.
        val reach=max(16,ceil(radius*4).toInt());var samples=0;var dimFelt=0;var warmFelt=0
        for(py in max(0,y.toInt()-reach)..min(height-1,y.toInt()+reach) step 2)
          for(px in max(0,x.toInt()-reach)..min(width-1,x.toInt()+reach) step 2) {
            if(hypot(px-x,py-y)<radius*1.5)continue
            samples++
            val c=pixels[py*width+px];val r=(c shr 16)and 255;val g=(c shr 8)and 255;val b=c and 255
            if(g>=18 && g*100>=r*70 && g*100<=r*90 && g*10>b*24 && g-b>=16){warmFelt++;if(g<45)dimFelt++}
          }
        if(samples>0 && warmFelt>samples*.40 && dimFelt>samples*.25)continue
      }
      val followed = prior != null && time-prior.time < .14 && hypot(x-prior.x,y-prior.y) < max(30.0, prior.radius*6)
      if(aspect>4.5 && changed<tail*.25 && !followed) continue
      var skin = 0; var movingSkin = 0; var ring = 0;var closeRing=0;var closeGrip=0
      var surroundChroma=0L;var surroundCount=0
      val needsContrast=radius>=2.5 && aspect<=1.9 && fill in .50.. .90 &&
        sumChroma.toDouble()/tail<107 && sumGreen<sumRed*1.05 &&
        strongCount>=tail*.55 && sumGreen>=sumRed*.75 && sumGreen>=sumBlue*1.8
      val reach = max(6, (radius*2.4).toInt())
      for (py in max(0,y.toInt()-reach)..min(height-1,y.toInt()+reach) step 2)
        for (px in max(0,x.toInt()-reach)..min(width-1,x.toInt()+reach) step 2) {
          val distance=hypot(px-x,py-y)
          if (distance < radius*1.2) continue
          val c = pixels[py*width+px]; val r = (c shr 16) and 255; val g = (c shr 8) and 255; val b = c and 255
          ring++
          if(needsContrast && distance>=radius*1.8) { surroundChroma+=max(0,g-b)*255/max(1,max(r,g));surroundCount++ }
          if(distance<=radius*1.8) {
            closeRing++
            if(gripSkin(c) && strongGreen[py*width+px].toInt()==0)closeGrip++
          }
          if (skin(c)) {
            skin++
            // Wooden floors can share skin colors. A uniformly skin-colored
            // surround is not a hand; motion or a bounded grip supplies evidence.
            if (previous.size==pixels.size) {
              val old=previous[py*width+px]; val pr=(old shr 16) and 255; val pg=(old shr 8) and 255; val pb=old and 255
              val wasBall=pg>=45 && pr>pg*.42 && ((pg>=pr*.86 && pg>pb*1.32 && pg-pb>18) || (pg>=pr*.70 && pg<=pr*.90 && pg>pb*2.4 && pg-pb>=40))
              if (!wasBall && abs(luminance(c)-luminance(old)-lightShift)>12) movingSkin++
            }
          }
        }
      if(closePartial && (ring==0 || skin<ring*.12 || skin>ring*.85))continue
      // A horizontal wood-floor/neutral-wall boundary also gives a partial
      // skin-colored ring. A static grip must end on both lateral sides.
      var skinSides=0
      for(side in -1..1 step 2) for(vertical in listOf(-.5,.5,2.0)) {
        val px=(x+side*reach).roundToInt().coerceIn(0,width-1)
        val py=(y+vertical*radius).roundToInt().coerceIn(0,height-1)
        if(skin(pixels[py*width+px]))skinSides++
      }
      val boundedGrip=skin>ring*.30 && skin<ring*.80 && skinSides<=1
      // A tiny ball on pink-toned wood can have skin-coloured pixels on every
      // nearby side. A small grip must end in a wider lateral context.
      var smallGripBounded=false
      if(radius<6 && closeRing>=4 && closeGrip>=closeRing*.08) {
        val wider=max(24.0,radius*12);var gripSides=0
        for(side in -1..1 step 2)for(vertical in listOf(-.5,.5,2.0)) {
          val px=(x+side*wider).roundToInt().coerceIn(0,width-1)
          val py=(y+vertical*radius).roundToInt().coerceIn(0,height-1)
          if(gripSkin(pixels[py*width+px]))gripSides++
        }
        smallGripBounded=gripSides<=1
      }
      val closeGripPresent=closeRing>=4 && closeGrip>=closeRing*.08 &&
        (if(radius<6)smallGripBounded else closeGrip<=closeRing*.65)
      // Exposure changes yellow felt to olive. Absolute chroma alone rejects
      // that ball while preferring brighter printed clothing. Require a compact
      // silhouette AND local chroma separation; warm wood without a boundary
      // cannot acquire a track by satisfying the broad colour mask alone.
      val contrastFelt=needsContrast && surroundCount>0 &&
        sumChroma.toDouble()/tail-surroundChroma.toDouble()/surroundCount>=32
      // Warm floor colours cannot keep a previous hand attached after release.
      // Each observation needs present grip, partial silhouette or moving skin.
      found.add(BallObservation(x,y,radius,time,if(haloOnly) min(fill,.45) else if(min(w,h)<5) min(fill,.6) else fill.coerceAtMost(.95),
        closePartial || closeGripPresent || ring > 0 && skin > ring*.18 && (movingSkin > ring*.12 || boundedGrip), aspect>1.6,sumLight/tail,
        if(previous.isEmpty()) 0.0 else changed.toDouble()/tail,colorUncertain=haloOnly,diameterX=w.toDouble(),diameterY=h.toDouble(),
        distinctFelt=closePartial || radius>=2.5 && aspect<=1.6 && fill>=.55 && saturated,occluded=closePartial || seamFragment,
        feltChroma=sumChroma.toDouble()/tail,greenDominant=sumGreen>=sumRed*1.05,contrastFelt=contrastFelt))
      bounds.add(intArrayOf(xmin,ymin,xmax,ymax));masses.add(tail)
    }
    // Fingers or a dark printed seam can split one ball into two components.
    // Skin bridges remain explicitly occluded. An ink bridge must be narrow,
    // dark and neutral, joining saturated fragments into one compact round outline.
    if(found.size in 2..48) {
      val consumed=BooleanArray(found.size);val joined=ArrayList<BallObservation>()
      for(i in found.indices) {
        if(consumed[i]) continue
        var match=-1;var bestDistance=Double.POSITIVE_INFINITY;var matchSeam=false
        for(j in i+1 until found.size) {
          if(consumed[j]) continue
          val a=found[i];val b=found[j];val aa=bounds[i];val bb=bounds[j]
          val w=max(aa[2],bb[2])-min(aa[0],bb[0])+1;val h=max(aa[3],bb[3])-min(aa[1],bb[1])+1
          if(min(w,h)<6 || max(w,h)>min(width,height)*.60 || max(w,h)>min(w,h)*1.55 ||
            (masses[i]+masses[j]).toDouble()/(w*h) !in .40.. .90) continue
          val dx=max(0,max(aa[0],bb[0])-min(aa[2],bb[2])-1)
          val dy=max(0,max(aa[1],bb[1])-min(aa[3],bb[3])-1)
          if(max(dx,dy)>max(4.0,min(w,h)*.22)) continue
          var skinBridge=0
          for(k in 0..4) {
            val f=.35+k*.075;val px=(a.x+(b.x-a.x)*f).toInt();val py=(a.y+(b.y-a.y)*f).toInt()
            if(skin(pixels[py*width+px]))skinBridge++
          }
          var inkBridge=0
          val centerDistance=max(1.0,hypot(a.x-b.x,a.y-b.y))
          for(k in 0..4) {
            val across=(k-2)*min(w,h)*.04
            val px=((a.x+b.x)*.5-(b.y-a.y)/centerDistance*across).roundToInt()
            val py=((a.y+b.y)*.5+(b.x-a.x)/centerDistance*across).roundToInt()
            val color=pixels[py*width+px];val rr=(color shr 16)and 255;val gg=(color shr 8)and 255;val bbColor=color and 255
            if(luminance(color)<min(a.luminance,b.luminance)*.65 && max(rr,max(gg,bbColor))-min(rr,min(gg,bbColor))<=max(12.0,max(rr,max(gg,bbColor))*.25))inkBridge++
          }
          val seam=inkBridge>=3 && a.feltChroma>=107 && b.feltChroma>=107 &&
            max(w,h)<=min(w,h)*1.30 && max(dx,dy)<=max(2.0,min(w,h)*.14) &&
            (masses[i]+masses[j]).toDouble()/(w*h)>=.55
          val distance=hypot(a.x-b.x,a.y-b.y)
          if((seam || skinBridge>=3 && max(w,h)<=min(width,height)*.15) && distance<bestDistance) { bestDistance=distance;match=j;matchSeam=seam }
        }
        if(match<0) { joined.add(found[i]);continue }
        consumed[match]=true
        val a=found[i];val b=found[match];val aa=bounds[i];val bb=bounds[match]
        val total=(masses[i]+masses[match]).toDouble();val weight=masses[i]/total
        val radius=min(max(aa[2],bb[2])-min(aa[0],bb[0])+1,max(aa[3],bb[3])-min(aa[1],bb[1])+1)/2.0
        var combined=BallObservation(if(matchSeam)(min(aa[0],bb[0])+max(aa[2],bb[2]))*.5 else a.x*weight+b.x*(1-weight),
          if(matchSeam)(min(aa[1],bb[1])+max(aa[3],bb[3]))*.5 else a.y*weight+b.y*(1-weight),radius,time,
          min(if(matchSeam).8 else .6,min(a.confidence,b.confidence)),nearHand=!matchSeam || a.nearHand || b.nearHand,luminance=a.luminance*weight+b.luminance*(1-weight),
          motionSupport=max(a.motionSupport,b.motionSupport),occluded=!matchSeam,
          diameterX=(max(aa[2],bb[2])-min(aa[0],bb[0])+1).toDouble(),diameterY=(max(aa[3],bb[3])-min(aa[1],bb[1])+1).toDouble(),
          distinctFelt=matchSeam,feltChroma=a.feltChroma*weight+b.feltChroma*(1-weight),greenDominant=a.greenDominant && b.greenDominant,
          contrastFelt=a.contrastFelt && b.contrastFelt)
        // A finger may split muted felt into fragments too small to establish
        // contrast separately. Test the reconstructed outline against its ring.
        // This remains an occluded observation: never speed/contact evidence.
        if(!matchSeam)combined=contextualGrip(combined,pixels,width,height)
        joined.add(combined)
      }
      found.clear();found.addAll(joined)
    }
    found.removeAll { b -> b.radius<=12 && clippedFelt.any { box ->
      // Adaptive contrast can trim the parent's cap before the border. That
      // incomplete edge region is ambiguous too, not an isolated small target.
      val margin=max(max(6.0,b.radius*3),min(box[2]-box[0]+1,box[3]-box[1]+1)*.35)
      b.x>=box[0]-margin && b.x<=box[2]+margin && b.y>=box[1]-margin && b.y<=box[3]+margin } }
    // A bright fleck embedded in a cluster of similar fragments is ambiguous
    // visual texture, not a new ball. Bound neighbour work in very busy frames;
    // large resolved balls are retained and two separate balls stay ambiguous.
    markPatterned(found)
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
  private var floorBoundaryNoticeUntil = -1.0
  private var framingNoticeUntil = -1.0
  private var dimensions = 0 to 0
  private var offset=CourtPoint(0.0,0.0)
  val cameraOffset get()=offset
  private var lost: BallObservation?=null
  private var acquisition: BallObservation?=null
  private var tinyAcquisition: BallObservation?=null
  private var motionAcquisition: BallObservation?=null
  private var trackSerial=0L
  private var contactReason="Aguardando trajetória"
  private var debugCandidates=emptyList<BallObservation>()
  private var usableCandidates=0
  private var rejectedTurn=false
  private fun diagnosed(r:LocalResult):LocalResult {
    val state=when {
      r.movingCamera -> "CÂMERA INSTÁVEL"
      r.impact!=null -> r.impact.evidence.name
      rejectedTurn -> "DIREÇÃO ALTERADA · SEM CONTATO"
      r.observed?.nearHand==true -> "NA MÃO · SEM IMPACTO"
      r.observed!=null -> "OBSERVADO"
      r.predicted!=null -> "PREVISTO · SEM MEDIÇÃO"
      lost!=null -> "RASTREAMENTO PERDIDO"
      else -> "BUSCANDO"
    }
    return r.copy(diagnostics=TrackingDiagnostics(trackSerial.takeIf{path.isNotEmpty()},state,
      debugCandidates.size,usableCandidates,debugCandidates.count{it.patterned},contactReason,debugCandidates.take(12)))
  }
  private fun rejectContact(reason:String):LocalImpact? {contactReason=reason;rejectedTurn=true;return null}
  val activeSettings get()=settings.translated(offset)
  fun clearTrack() { path.clear(); lost=null; acquisition=null; tinyAcquisition=null; motionAcquisition=null; lastImpact=-10.0 }
  fun reset() { clearTrack(); detector.reset(); scene.reset(); lastFrame = -1.0; unstableUntil = -1.0; floorBoundaryNoticeUntil = -1.0; framingNoticeUntil = -1.0; offset=CourtPoint(0.0,0.0) }

  fun process(pixels: IntArray, width: Int, height: Int, time: Double): LocalResult {
    if(width !in 1..8192 || height !in 1..8192 || pixels.size.toLong()!=width.toLong()*height || !time.isFinite())
      return LocalResult(null,null,emptyList(),null,false,null,"Quadro ou timestamp inválido · descartado")
    if (dimensions != width to height) { reset(); dimensions = width to height }
    if (!time.isFinite() || time <= lastFrame) return LocalResult(null,null,emptyList(),null,false,null,"Timestamp repetido: quadro descartado")
    if (lastFrame >= 0 && time-lastFrame > .25) path.clear()
    lastFrame = time
    contactReason=if(settings.surface==ContactSurface.TRACK) "Contagem desligada · somente rastrear" else "Aguardando descida/aproximação e retorno observados"
    rejectedTurn=false
    val (components, cameraMoved) = detector.detect(pixels,width,height,time,path.lastOrNull(),activeSettings.polygon)
    debugCandidates=components
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
    // Warm wood passes the broad yellow mask too. A moving weak component
    // cannot acquire a ball; allow only a brief, spatially gated continuation
    // of a trajectory that already has independent colour support.
    val anchor=path.lastOrNull{it.supportsBallColor};val last=path.lastOrNull()
    val weakReference=if(anchor!=null && last!=null && time-anchor.time<=.10 && time-last.time<=.10) {
      val before=path.getOrNull(path.size-2)
      if(before!=null && last.time>before.time) {
        val f=((time-last.time)/(last.time-before.time)).coerceIn(0.0,2.0)
        CourtPoint(last.x+(last.x-before.x)*f,last.y+(last.y-before.y)*f)
      } else CourtPoint(last.x,last.y)
    } else null
    val candidates=components.filter { b -> !b.patterned && (b.supportsBallColor || weakReference!=null &&
      hypot(b.x-weakReference.x,b.y-weakReference.y)<=max(12.0,(last?.radius ?: 0.0)*3)) }
    usableCandidates=candidates.size
    var previous = path.lastOrNull()
    // A tiny printed/highlight island must not lock out the now resolved
    // silhouette containing it. This is an ownership change, never a bounce.
    val fragment=previous?.takeIf{it.radius<=6}
    if(fragment!=null && candidates.count{b->b.resolved && b.distinctFelt &&
        b.radius>=max(16.0,fragment.radius*4) && hypot(b.x-fragment.x,b.y-fragment.y)<=b.radius*1.15}==1) {
      path.clear();previous=null;lost=null;acquisition=null;tinyAcquisition=null;motionAcquisition=null
    }
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
    val felt=if(previous==null)movingEligible.filter{it.distinctFelt} else emptyList()
    // Colour separation, not size, distinguishes vivid felt from a dull green
    // leaf/wood fragment. Equally plausible felt balls remain ambiguous.
    val vivid=felt.filter{it.feltChroma>=140}
    val plausible=if(previous==null) (if(vivid.isNotEmpty())vivid else if(felt.isNotEmpty())felt else movingEligible.filter { it.resolved }.ifEmpty { movingEligible }) else eligible
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
        val uniqueFast=prior2==null && acquiring.size==1 && previous.resolved && b.resolved &&
          b.motionSupport>=.25 && b.radius/previous.radius in .75..1.33 &&
          abs(b.feltChroma-previous.feltChroma)<35 && b.luminance/max(1.0,previous.luminance) in .65..1.5
        val predictedFast=prior2!=null && b.resolved && b.motionSupport>=.25 && dt<=.075 &&
          hypot(b.x-prediction!!.x,b.y-prediction.y)<max(12.0,step*.35+previous.radius*2)
        // Fast balls must not be rejected merely for moving >12% of the image
        // in one observed frame. Extra reach needs a unique matching silhouette
        // or a supported velocity prediction, never a fabricated position.
        val gate=if(previous.nearHand) width*.36 else if(uniqueFast)width*.28 else if(predictedFast)
          min(width*.5,max(timeGate,step*1.5+previous.radius*4)) else min(width*.23,max(timeGate,step*1.8+previous.radius*4))
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
    if(previous==null && ball!=null && (ball.radius<2.5 || ball.colorUncertain || ball.feltChroma<82 && !ball.greenDominant) && !ball.occluded && !motionConfirmed) {
      val tiny=tinyAcquisition;tinyAcquisition=ball
      val distance=tiny?.let { hypot(ball!!.x-it.x,ball!!.y-it.y) } ?: 0.0
      // A stationary tiny reflection or low-chroma warm fragment is not
      // sufficient to acquire a new ball from a single frame.
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
      // Retain only an already observed, clean descending floor leg across a
      // brief loss near contact. This does not extend the visible prediction.
      val hold=if(canBridgeFloorLoss()) .16 else .10
      if (age > hold) { lost=previous ?: lost;path.clear() }
      return diagnosed(LocalResult(null, prediction?.takeIf { age < .06 }, path.takeLast(16).map { CourtPoint(it.x,it.y) },
        null,blocked,null,if(scene.needsConfirmation) "Treino ativo · confira a área; impactos suspensos" else if(blocked) "Treino ativo · aguardando imagem estável" else if(time<framingNoticeUntil) "Possível quicada fora da imagem · recue a câmera; contato não contado" else if(candidates.size>1) "Alvos verdes ambíguos · aguardando continuidade" else "Bola não localizada · sem inventar posição",activeSettings,offset,scene.needsConfirmation))
    }
    if (previous != null && (time-previous.time > .25 || ball.radius/previous.radius !in
      (if(previous.nearHand || previous.occluded) .35..3.5 else .48..2.1))) path.clear()
    if(!ball.supportsBallColor)ball=ball.copy(colorUncertain=true)
    if(path.isEmpty())trackSerial++
    path.add(ball)
    lost=null;acquisition=null
    while (path.size > 160 || (path.isNotEmpty() && time-path.first().time > .6)) path.removeAt(0)
    val impact = if (!blocked) contact(width,height) else null
    val speed = speed(path)
    return diagnosed(LocalResult(ball,null,path.takeLast(16).map { CourtPoint(it.x,it.y) }, impact,blocked,speed,
      when {
        scene.needsConfirmation -> "Treino ativo · confira a área em Marcar área; impactos suspensos"
        blocked -> "Treino ativo · aguardando a imagem estabilizar; impactos suspensos"
        time<framingNoticeUntil -> "Possível quicada fora da imagem · recue a câmera; contato não contado"
        time<floorBoundaryNoticeUntil -> "Retorno acima do piso marcado · ajuste o limite ou use Toda a imagem é piso"
        ball.occluded -> "Bola parcialmente oculta · sem velocidade ou impacto automático"
        ball.colorUncertain -> "Sinal de cor fraco · acompanhamento incerto · revise o contato"
        !ball.resolved -> "Bola ≈${(ball.radius*2).toInt()} px · ${if(min(width,height)>=1080) "filme meia quadra ou aproxime a câmera" else "teste 1080p ou filme mais perto"} · revise IN/OUT"
        ball.nearHand -> "Bola próxima à mão: impacto suspenso"
        ball.blurred -> "Bola borrada observada · velocidade indisponível"
        else -> "Bola observada"
      },activeSettings,offset,scene.needsConfirmation))
  }

  private fun speed(points:List<BallObservation>)=ObservedBallSpeed.current(points)

  private fun contact(width: Int, height: Int): LocalImpact? {
    if (settings.surface == ContactSurface.TRACK || path.size < 5) return null
    val simple = if(settings.surface==ContactSurface.FLOOR) floorEvidence() else wallEvidence()
    val robust = if(simple==null && (settings.surface==ContactSurface.WALL || floorReferenceKnown()))
      ObservedContactFit.find(path,settings.surface) else null
    val depth = if(simple==null && robust==null && (settings.surface==ContactSurface.WALL || floorReferenceKnown()))
      DepthContactFit.find(path,width,height) else null
    val p = (simple ?: robust?.support ?: depth?.support) ?: run {
      contactReason=if(path.any{it.nearHand || it.occluded}) "Mão/oclusão na trajetória · aguardando voo livre" else "Sem retorno coerente observado dos dois lados do contato"
      return null
    }
    val evidence=path.filter{it.time>=p.first().time && it.time<=p.last().time}
    val a=p[0]; val b=p[1]; val c=p[2]; val d=p[3]; val e=p[4]
    // REVIEW is a contact claim too: require colour-supported observations
    // before and after the turn, not just one coloured sample at its centre.
    if(evidence.none{it.time<c.time && it.supportsBallColor} ||
      evidence.none{it.time>c.time && it.supportsBallColor}) return rejectContact("Cor/forma sem suporte nos dois lados do retorno")
    if (c.time-lastImpact < .18 || e.time-a.time > .65 || c.nearHand || (d.nearHand && e.nearHand) ||
      evidence.zipWithNext().any { (left,right) -> right.time-left.time > .25 }) return rejectContact("Retorno duplicado, ocluso ou sem continuidade temporal")
    val travel = max(3.0,c.radius*.7)
    val floorTurn = c.y-a.y > travel && c.y-e.y > travel && b.y >= a.y && d.y >= e.y &&
      c.y>=b.y && c.y>=d.y
    val incoming=CourtPoint((b.x-a.x)/(b.time-a.time),(b.y-a.y)/(b.time-a.time))
    val outgoing=CourtPoint((e.x-d.x)/(e.time-d.time),(e.y-d.y)/(e.time-d.time))
    val sa=hypot(incoming.x,incoming.y);val sb=hypot(outgoing.x,outgoing.y)
    val wallTurn=sa>20 && sb>20 && (incoming.x*outgoing.x+incoming.y*outgoing.y)/(sa*sb)<.35
    if (settings.surface == ContactSurface.FLOOR && !floorTurn && robust==null && depth==null) return null
    if (settings.surface == ContactSurface.WALL && !wallTurn && robust==null && depth==null) return null
    // The observed local maximum need not be the instant of contact. Estimate
    // the crossing of the observed incoming/outgoing legs, bounded by b and d.
    // This is two-sided interpolation, never an extrapolation from a lost ball.
    val fit=(robust ?: depth)?.let{ContactFit(it.x,it.y,it.time,it.error)} ?: if (settings.surface==ContactSurface.FLOOR) {
      // A wide window establishes the rebound, but its curved outer legs must
      // not move the mark beyond the observed ball. Localise with the actual
      // samples around the observed maximum when they are continuous.
      val local=if(e.time-a.time>.200001) localFloorPeak(evidence) else null
      (local?.let(::floorContact)) ?: floorContact(p)
    } else wallContact(p)
    if(settings.surface==ContactSurface.WALL && fit==null) return null
    // REVIEW must have contact evidence too; never accumulate arbitrary tiny colour fragments.
    if(!c.resolved && (p.maxOf{it.radius}/p.minOf{it.radius}>1.65 ||
      p.maxOf{it.luminance}/max(1.0,p.minOf{it.luminance})>1.8 ||
      settings.surface==ContactSurface.FLOOR && fit==null)) return null
    val contactTime=fit?.time ?: c.time
    val point=CourtPoint((fit?.x ?: c.x)/width,
      ((fit?.y ?: c.y)+if (settings.surface==ContactSurface.FLOOR && depth==null) c.radius else 0.0)/height)
    if(!point.x.isFinite() || !point.y.isFinite() || point.x !in 0.0..1.0 || point.y !in 0.0..1.0) {
      framingNoticeUntil=lastFrame+1.5
      return rejectContact("Contato calculado fora da imagem · recue a câmera para mostrar o ponto da quicada")
    }
    val intervals=evidence.zipWithNext().map { (left,right)->right.time-left.time }.sorted()
    val irregular=intervals.last()>intervals[1]*1.55+.001
    // A clean fitted floor return with less than 0.65 radii of motion during
    // the longest interval is spatially bounded, even with delivery jitter.
    // Keep the added uncertainty and never bypass long gaps or uncertain balls.
    val boundedTiming=settings.surface==ContactSurface.FLOOR && depth==null && fit!=null &&
      fit.error<=max(2.0,c.radius*.35) && max(sa,sb)*intervals.last()<=c.radius*.65 &&
      evidence.all{it.resolved && !it.nearHand && !it.blurred}
    val sparse=intervals.last()>.075 || irregular && !boundedTiming
    val localization=if(settings.surface==ContactSurface.FLOOR)floorLocalizationNoise(evidence,c.time) else 0.0
    val missing=if(irregular || sparse) max(max(hypot(c.x-b.x,c.y-b.y),hypot(d.x-c.x,d.y-c.y))*.5,
      max(sa,sb)*intervals.last()*.5) else 0.0
    val uncertainty=max(2.0,c.radius*.3 + (if (c.blurred) c.radius*.6 else 0.0) +
      (fit?.error ?: 0.0) + localization + missing)
    val region=activeSettings
    // The floor boundary separates physical planes; the IN target polygon
    // must not suppress real contacts outside the target.
    // "Whole image is floor" is a convenience setting, not a measured wall
    // boundary. It must not disable every later wall contact after switching.
    if(settings.surface==ContactSurface.WALL && settings.floorLine!=wholeImageFloorLine()) {
      val distance=LocalGeometry.floorDistancePx(point,region.floorLine,width,height)
      if(distance!=null && distance>uncertainty+c.radius)
        return rejectContact("Retorno sobre o piso no modo parede · selecione Chão")
    }
    val floorDistance=if(settings.surface==ContactSurface.FLOOR && settings.court==CourtLayout.CUSTOM)
      LocalGeometry.floorDistancePx(point,region.floorLine,width,height) else null
    // A reversal clearly above a marked floor is not even a floor-contact candidate.
    // Keep near-boundary uncertainty for review and continue detecting outside the IN polygon.
    if (floorDistance!=null && floorDistance + uncertainty < 0.0) {
      floorBoundaryNoticeUntil=lastFrame+.8;return rejectContact("Retorno acima do limite do piso · sem evidência de contato com o chão")
    }
    var call=LocalGeometry.classify(point,c.radius,uncertainty,region.polygon,width,height)
    val courtKnown=settings.court!=CourtLayout.CUSTOM && LocalGeometry.projectedPolygon(region.polygon)
    val floorKnown = settings.surface != ContactSurface.FLOOR || courtKnown || LocalGeometry.onFloor(point,region.floorLine)
    if (!floorKnown && call != LineCall.UNMARKED) call=LineCall.REVIEW
    if (sparse && call != LineCall.UNMARKED) call=LineCall.REVIEW
    if (settings.surface==ContactSurface.FLOOR && fit==null && call!=LineCall.UNMARKED) call=LineCall.REVIEW
    if (evidence.any { it.nearHand } && call != LineCall.UNMARKED) call=LineCall.REVIEW
    if (evidence.any { !it.resolved } && call != LineCall.UNMARKED) call=LineCall.REVIEW
    // Monocular wall turns cannot distinguish a racket/hand return from contact with the wall.
    if (settings.surface == ContactSurface.WALL && call != LineCall.UNMARKED) call=LineCall.REVIEW
    if (depth!=null && call!=LineCall.UNMARKED) call=LineCall.REVIEW
    val reason=when {
      depth!=null -> "Retorno em profundidade observado pelo tamanho da bola. Centro e contato são estimados; confirme a superfície na revisão."
      settings.surface == ContactSurface.WALL -> "Mudança de direção observada; contato com a parede estimado entre quadros. Confirme na revisão."
      evidence.any { !it.resolved } -> "Bola pequena demais para uma decisão automática. Confira o contato e a linha."
      !floorKnown && floorDistance==null -> "Marque o limite do piso; trajetória sozinha não comprova chão."
      !floorKnown -> "Contato próximo ao limite do piso. Confirme se a bola tocou o chão."
      sparse -> "Quadros espaçados ou ausentes perto do contato. Posição estimada; revise antes de decidir."
      fit==null -> "Não foi possível localizar o instante do contato. Confirme na revisão."
      evidence.any { it.nearHand } -> "Possível mão perto da trajetória. Confirme o contato antes de decidir."
      call == LineCall.REVIEW -> "A incerteza da marca alcança a linha. Confira o contato."
      call == LineCall.UNMARKED -> "Quicada candidata; selecione a área para classificar IN/OUT."
      courtKnown -> "Mudança de trajetória compatível com quicada na quadra marcada; contato estimado."
      else -> "Descida e subida observadas no piso; contato estimado entre quadros."
    }
    val result=LocalImpact(++serial,contactTime,point,c.radius,call,ObservedBallSpeed.incoming(path,contactTime),uncertainty,reason,settings.surface,
      cameraOffset=offset,evidenceOffset=offset)
    lastImpact=contactTime
    contactReason=reason
    floorBoundaryNoticeUntil = -1.0
    framingNoticeUntil = -1.0
    path.removeAll { it.time <= c.time }
    return result
  }

  private data class ContactFit(val x: Double,val y: Double,val time: Double,val error: Double)
  private fun floorReferenceKnown():Boolean {
    val area=activeSettings
    return settings.surface==ContactSurface.FLOOR && (LocalGeometry.projectedFloorLine(area.floorLine) ||
      settings.court!=CourtLayout.CUSTOM && LocalGeometry.projectedPolygon(area.polygon))
  }
  private fun cleanFloorSample(p:BallObservation)=p.resolved && p.supportsBallColor && !p.nearHand && !p.blurred
  private fun canBridgeFloorLoss():Boolean {
    if(!floorReferenceKnown() || path.size<3)return false
    val p=path.takeLast(3);val a=p.first();val b=p.last()
    return p.all(::cleanFloorSample) && p.maxOf{it.radius}/p.minOf{it.radius}<=1.35 &&
      p.zipWithNext().all{(left,right)->right.time-left.time in .000001.. .075 && right.y>=left.y-.75} &&
      b.y-a.y>max(2.0,b.radius*.3)
  }
  private fun localFloorPeak(points:List<BallObservation>):List<BallObservation>? {
    val peak=points.indices.maxByOrNull{points[it].y} ?: return null
    if(peak<2 || peak+2>=points.size)return null
    val p=points.subList(peak-2,peak+3)
    if(p.zipWithNext().any{(a,b)->b.time-a.time>.075} || p[2].y<=p.first().y || p[2].y<=p.last().y)return null
    return p
  }
  private fun wallEvidence():List<BallObservation>? {
    // A five-frame turn has sub-pixel legs at high capture rates. Require
    // observed displacement over bounded time, and reject hand/jitter paths.
    for(stride in 1..((path.size-1)/4)) {
      val start=path.size-1-stride*4
      val p=(0..4).map{path[start+it*stride]};val (a,b,c,d,e)=p
      if(e.time-a.time>.240001)break
      val raw=path.subList(start,path.size)
      if(raw.any{it.nearHand || it.occluded} || raw.maxOf{it.radius}/raw.minOf{it.radius}>1.65)continue
      val travel=max(3.0,c.radius*.5)
      if(hypot(c.x-a.x,c.y-a.y)<travel || hypot(c.x-e.x,c.y-e.y)<travel)continue
      val vx=(b.x-a.x)/(b.time-a.time);val vy=(b.y-a.y)/(b.time-a.time)
      val ux=(e.x-d.x)/(e.time-d.time);val uy=(e.y-d.y)/(e.time-d.time)
      val sa=hypot(vx,vy);val sb=hypot(ux,uy)
      if(sa<=20 || sb<=20 || (vx*ux+vy*uy)/(sa*sb)>=.35)continue
      val fit=wallContact(p) ?: continue
      val error=raw.maxOf { q ->
        val before=q.time<=fit.time;val anchor=if(before)a else e
        hypot(q.x-anchor.x-(if(before)vx else ux)*(q.time-anchor.time),
          q.y-anchor.y-(if(before)vy else uy)*(q.time-anchor.time))
      }
      if(error>max(2.0,c.radius*.35))continue
      return p
    }
    return null
  }
  private fun floorEvidence(): List<BallObservation>? {
    // Search bounded physical time rather than eight fixed frame strides.
    // A slower rebound can need more than 200 ms even at 60 FPS. Longer
    // evidence requires a marked floor and clean, coherent observed legs.
    for(stride in 1..((path.size-1)/4)) {
      val start=path.size-1-stride*4
      val a=path[start];val b=path[start+stride];val c=path[start+stride*2]
      val d=path[start+stride*3];val e=path.last()
      val span=e.time-a.time;if(span>.420001)break
      val travel=max(3.0,c.radius*.7)
      if(c.y-a.y<=travel || c.y-e.y<=travel || b.y<a.y || d.y<e.y || c.y<b.y || c.y<d.y)continue
      if(stride>1) {
        val raw=path.subList(start,path.size);val extended=span>.200001
        if(c.nearHand || raw.any{it.nearHand || it.occluded})continue
        if(extended && (!floorReferenceKnown() || raw.any{!cleanFloorSample(it)} ||
          raw.maxOf{it.radius}/raw.minOf{it.radius}>1.35))continue
        // A longer window must show coherent descent and ascent throughout,
        // not just five convenient samples of jitter or an unrelated turn.
        val middle=start+stride*2
        val noisy=(start until middle).any{path[it+1].y<path[it].y-.75} ||
          (middle until path.lastIndex).any{path[it+1].y>path[it].y+.75}
        val factor=if(extended) .7 else .9
        if((noisy || extended) && (!coherentFloorLeg(path.subList(start,middle+1),c.radius,true,factor) ||
          !coherentFloorLeg(path.subList(middle,path.size),c.radius,false,factor)))continue
      }
      return listOf(a,b,c,d,e)
    }
    return null
  }
  private data class FloorLegSupport(val displacement:Double,val error:Double,val rms:Double)
  private fun floorLegSupport(points:List<BallObservation>):FloorLegSupport? {
    if(points.size<3)return null
    val first=points.first().time;val span=points.last().time-first
    if(span<=0)return null
    val mt=points.sumOf{it.time-first}/points.size;val my=points.sumOf{it.y}/points.size
    val den=points.sumOf{val t=it.time-first-mt;t*t};if(den<=1e-12)return null
    val slope=points.sumOf{(it.time-first-mt)*(it.y-my)}/den
    val errors=points.map{abs(it.y-my-slope*(it.time-first-mt))}
    return FloorLegSupport(slope*span,errors.max(),sqrt(errors.sumOf{it*it}/errors.size))
  }
  private fun coherentFloorLeg(points:List<BallObservation>,radius:Double,descending:Boolean,factor:Double=.9):Boolean {
    val support=floorLegSupport(points) ?: return false
    val progress=support.displacement*(if(descending)1 else -1)
    return progress>=max(5.0,radius*factor) && support.error<=max(2.0,radius*.35) &&
      support.rms<=max(.75,progress*.18)
  }
  private fun floorLocalizationNoise(points:List<BallObservation>,turn:Double):Double {
    val before=points.filter{it.time<=turn};val after=points.filter{it.time>=turn}
    val noisy=before.zipWithNext().any{(a,b)->b.y<a.y-.75} || after.zipWithNext().any{(a,b)->b.y>a.y+.75}
    if(!noisy)return 0.0
    return max(floorLegSupport(before)?.error ?: 0.0,floorLegSupport(after)?.error ?: 0.0)
  }
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
