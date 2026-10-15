package ai.tennisvision.camera

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.*

/** One fitted coordinate system for pixels, touches, trajectories and saved areas. */
class LocalCourtView(context: Context, attrs: AttributeSet?=null): View(context,attrs) {
  var settings=LocalSettings()
  val visibleSettings get()=result?.activeArea ?: settings
  var events: List<LocalImpact> = emptyList()
  var editing=0 // 1: dragged area; 2: room floor boundary; 3: four court corners
    private set
  var draft: List<CourtPoint> = emptyList()
    private set
  var onDraft: ((Boolean)->Unit)?=null
  var onImpact: ((Long)->Unit)?=null
  private var image: Bitmap?=null
  private var result: LocalResult?=null
  private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
  private val drawingPath=Path()
  private val rect=RectF()
  private var selected=-1
  private var anchor: CourtPoint?=null
  private var age=0L
  private var loupePoint: CourtPoint?=null
  private var cameraOffset=CourtPoint(0.0,0.0)
  var editorLayout=CourtLayout.CUSTOM
  private val lime=Color.rgb(197,250,100)
  private val cyan=Color.rgb(97,211,238)
  private val amber=Color.rgb(255,212,121)
  private val red=Color.rgb(255,115,107)
  init { contentDescription="Imagem da câmera, trajetória e área de treino"; isFocusable=true }
  fun present(frame: LocalFrame) {
    if (editing!=0) return // explicit frozen reference for precise editing; camera still drains frames
    if (image?.width!=frame.width || image?.height!=frame.height) {
      image?.recycle(); image=Bitmap.createBitmap(frame.width,frame.height,Bitmap.Config.ARGB_8888)
    }
    image!!.setPixels(frame.pixels,0,frame.width,0,0,frame.width,frame.height)
    result=frame.result;cameraOffset=frame.result.cameraOffset; age=android.os.SystemClock.elapsedRealtime(); invalidate()
  }
  fun clearTracking() { result=null; invalidate() }
  fun resetReference() { cameraOffset=CourtPoint(0.0,0.0);result=null;events=emptyList();invalidate() }
  fun begin(mode: Int) { editing=mode; draft=if (mode==2) visibleSettings.floorLine.toList() else visibleSettings.polygon.toList(); selected=-1; anchor=null; loupePoint=null; changed() }
  fun redraw() { draft=emptyList(); selected=-1; anchor=null; loupePoint=null; changed() }
  fun finish() { editing=0; draft=emptyList(); result=null; loupePoint=null; invalidate() }
  private fun changed() { onDraft?.invoke(if (editing==2) LocalGeometry.validFloorLine(draft) else LocalGeometry.valid(draft)); invalidate() }
  override fun performClick(): Boolean { super.performClick(); return true }
  private fun fitted() {
    val iw=(image?.width ?: 16).toFloat(); val ih=(image?.height ?: 9).toFloat()
    val scale=min(width/iw,height/ih)
    rect.set((width-iw*scale)/2,(height-ih*scale)/2,(width+iw*scale)/2,(height+ih*scale)/2)
  }
  fun pointOnScreen(p: CourtPoint): PointF { fitted(); return PointF(rect.left+(p.x*rect.width()).toFloat(),rect.top+(p.y*rect.height()).toFloat()) }
  private fun screen(p: CourtPoint)=pointOnScreen(p)
  private fun point(x: Float,y: Float): CourtPoint? {
    fitted(); if (!rect.contains(x,y)) return null
    return CourtPoint(((x-rect.left)/rect.width()).toDouble().coerceIn(0.0,1.0),((y-rect.top)/rect.height()).toDouble().coerceIn(0.0,1.0))
  }
  private fun updatePoint(p: CourtPoint?) {
    if (p==null) return
    loupePoint=p
    if (selected>=0) draft=draft.mapIndexed { i,v -> if (i==selected) p else v }
    else anchor?.let { a ->
      val l=min(a.x,p.x); val r=max(a.x,p.x); val t=min(a.y,p.y); val b=max(a.y,p.y)
      draft=listOf(CourtPoint(l,t),CourtPoint(r,t),CourtPoint(r,b),CourtPoint(l,b))
    }
    changed()
  }
  override fun onTouchEvent(event: MotionEvent): Boolean {
    if (image==null) return super.onTouchEvent(event)
    if (editing==0) {
      if (event.actionMasked==MotionEvent.ACTION_UP && settings.marks) {
        events.filter { it.decision!=LineCall.REJECTED }.minByOrNull { e -> val s=screen(LocalGeometry.impactPoint(e,cameraOffset)); hypot(s.x-event.x,s.y-event.y) }
          ?.let { e -> val s=screen(LocalGeometry.impactPoint(e,cameraOffset)); if (hypot(s.x-event.x,s.y-event.y)<28*resources.displayMetrics.density) onImpact?.invoke(e.id) }
        performClick()
      }
      return true
    }
    val p=point(event.x,event.y)
    when(event.actionMasked) {
      MotionEvent.ACTION_DOWN -> {
        if (p==null) return false
        parent?.requestDisallowInterceptTouchEvent(true)
        selected=if(editing==3 && draft.size<4) -1 else draft.indices.minByOrNull { i -> val s=screen(draft[i]); hypot(s.x-event.x,s.y-event.y) } ?: -1
        if (selected>=0) { val s=screen(draft[selected]); if (hypot(s.x-event.x,s.y-event.y)>36*resources.displayMetrics.density) selected=-1 }
        if (selected<0) {
          if (editing==2) { draft=if (draft.size>=2) listOf(p) else draft+p; selected=draft.lastIndex }
          else if(editing==3 && draft.size<4) { draft=draft+p;selected=draft.lastIndex }
          else if (draft.isEmpty()) anchor=p
        }
        loupePoint=p
        changed(); return true
      }
      MotionEvent.ACTION_MOVE -> {
        updatePoint(p)
        return true
      }
      MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL -> {
        // Android can resample MOVE beyond the finger. The release coordinate is authoritative.
        if (event.actionMasked==MotionEvent.ACTION_UP) updatePoint(p)
        parent?.requestDisallowInterceptTouchEvent(false); selected=-1; anchor=null;loupePoint=null;invalidate()
        if (event.actionMasked==MotionEvent.ACTION_UP) performClick()
        return true
      }
    }
    return true
  }

  override fun onDraw(canvas: Canvas) {
    super.onDraw(canvas); fitted(); canvas.drawColor(Color.rgb(6,13,16))
    paint.reset(); paint.isAntiAlias=true; paint.isFilterBitmap=true
    image?.let { canvas.drawBitmap(it,null,rect,paint) }
    val density=resources.displayMetrics.density
    fun line(points: List<CourtPoint>, closed: Boolean, color: Int, fill: Boolean=false) {
      if (points.isEmpty()) return
      val path=drawingPath; path.reset(); points.forEachIndexed { i,p -> val s=screen(p); if (i==0) path.moveTo(s.x,s.y) else path.lineTo(s.x,s.y) }
      if (closed) path.close()
      if (fill) { paint.color=(color and 0xffffff) or (30 shl 24); paint.style=Paint.Style.FILL; canvas.drawPath(path,paint) }
      paint.color=color; paint.style=Paint.Style.STROKE; paint.strokeWidth=2*density; canvas.drawPath(path,paint); paint.style=Paint.Style.FILL
    }
    val displayed=visibleSettings
    val area=if (editing==1 || editing==3) draft else displayed.polygon
    line(area,area.size==4,if (editing==1 || editing==3) cyan else lime,area.size==4)
    val layout=if(editing!=0) editorLayout else displayed.court
    CourtProjection.guides(area,layout).forEach { line(it,false,0x88ffffff.toInt()) }
    if(layout==CourtLayout.CUSTOM) line(if (editing==2) draft else displayed.floorLine,false,cyan)
    if (editing!=0) {
      draft.forEachIndexed { i,p ->
        val s=screen(p); paint.color=cyan; canvas.drawCircle(s.x,s.y,9*density,paint)
        paint.color=Color.BLACK; paint.textSize=11*density; paint.textAlign=Paint.Align.CENTER; canvas.drawText("${i+1}",s.x,s.y+4*density,paint)
      }
      paint.textAlign=Paint.Align.LEFT
      // Instructions live above/below the image so they cannot cover a distant corner.
      loupePoint?.let { p ->
        val bitmap=image ?: return@let
        val s=screen(p);val radius=min(52*density,min(rect.width(),rect.height())*.23f)
        val cx=if(s.x>rect.centerX()) rect.left+radius+10*density else rect.right-radius-10*density
        val cy=rect.top+radius+36*density
        val save=canvas.save()
        val clip=Path().apply { addCircle(cx,cy,radius,Path.Direction.CW) }
        canvas.clipPath(clip)
        canvas.translate(cx,cy);canvas.scale(3f,3f);canvas.translate(-s.x,-s.y)
        canvas.drawBitmap(bitmap,null,rect,paint);canvas.restoreToCount(save)
        paint.color=cyan;paint.style=Paint.Style.STROKE;paint.strokeWidth=2*density
        canvas.drawCircle(cx,cy,radius,paint)
        canvas.drawLine(cx-7*density,cy,cx+7*density,cy,paint);canvas.drawLine(cx,cy-7*density,cx,cy+7*density,paint)
        paint.style=Paint.Style.FILL
      }
      return
    }
    if (settings.marks && result?.movingCamera!=true) events.filter { it.decision!=LineCall.REJECTED }.takeLast(24).forEach { e ->
      val s=screen(LocalGeometry.impactPoint(e,cameraOffset)); val color=callColor(e.decision)
      paint.color=color; paint.style=Paint.Style.STROKE; paint.strokeWidth=2*density; canvas.drawCircle(s.x,s.y,7*density,paint); paint.style=Paint.Style.FILL
      if(events.takeLast(3).any{it.id==e.id}) label(canvas,callLabel(e.decision)+(e.speedKmh?.let { " · ≈${it.toInt()} km/h" } ?: ""),s.x+11*density,s.y-8*density,color)
    }
    // Never leave a tracking box on a frozen image after capture interruption.
    if (android.os.SystemClock.elapsedRealtime()-age<350) result?.let { r ->
      val iw=image?.width ?: return@let; val ih=image?.height ?: return@let
      line(r.trail.map { CourtPoint(it.x/iw,it.y/ih) },false,lime)
      r.observed?.let { b ->
        val s=screen(CourtPoint(b.x/iw,b.y/ih)); val radius=(b.radius/iw*rect.width()).toFloat().coerceAtLeast(7*density)
        val color=if(b.resolved) lime else amber
        paint.color=color; paint.style=Paint.Style.STROKE; paint.strokeWidth=2*density
        canvas.drawRoundRect(s.x-radius,s.y-radius,s.x+radius,s.y+radius,4*density,4*density,paint); paint.style=Paint.Style.FILL
        label(canvas,if(b.occluded) "BOLA PARCIAL · INCERTA" else if(b.colorUncertain) "SINAL INCERTO" else if(!b.resolved) "BOLA PEQUENA · INCERTO" else r.speedKmh?.let { "≈${it.toInt()} km/h lateral" } ?: "BOLA",s.x+radius+5*density,s.y,color)
      }
      r.predicted?.let { p -> val s=screen(CourtPoint(p.x/iw,p.y/ih)); label(canvas,"PREVISÃO",s.x,s.y,amber) }
    }
  }
  private fun label(canvas: Canvas,text: String,x: Float,y: Float,color: Int) {
    val d=resources.displayMetrics.density; paint.textSize=11*d; paint.typeface=Typeface.DEFAULT_BOLD; paint.textAlign=Paint.Align.LEFT
    val width=paint.measureText(text); val px=x.coerceIn(rect.left+4*d,max(rect.left+4*d,rect.right-width-6*d)); val py=y.coerceIn(rect.top+18*d,rect.bottom-5*d)
    paint.color=0xdd0b1115.toInt(); canvas.drawRoundRect(px-4*d,py-14*d,px+width+4*d,py+4*d,4*d,4*d,paint)
    paint.color=color; canvas.drawText(text,px,py,paint)
  }
  fun callColor(call: LineCall)=when(call) { LineCall.IN->lime; LineCall.OUT->red; else->amber }
  fun callLabel(call: LineCall)=when(call) { LineCall.REVIEW->"REVISAR"; LineCall.UNMARKED->"SEM ÁREA"; LineCall.REJECTED->"DESCARTADO"; else->call.name }
  override fun onDetachedFromWindow() { super.onDetachedFromWindow(); image?.recycle(); image=null }
}
