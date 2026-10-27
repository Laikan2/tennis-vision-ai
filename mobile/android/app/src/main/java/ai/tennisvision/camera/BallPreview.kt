package ai.tennisvision.camera

import kotlin.math.*

/** Reduced-cost detection on this frame while scoring is paused. No contacts or speed. */
class BallPreview {
  private val core=LocalAnalysis(LocalSettings(surface=ContactSurface.TRACK))
  private var storage=IntArray(0)
  fun reset() { core.reset() }
  fun process(pixels: IntArray,width: Int,height: Int,time: Double,area: LocalSettings,offset: CourtPoint=CourtPoint(0.0,0.0)): LocalResult {
    if(width<=0 || height<=0 || width>8192 || height>8192 || pixels.size!=width*height)
      return LocalResult(null,null,emptyList(),null,false,null,"Prévia inválida")
    val scale=min(1.0,640.0/max(width,height));val w=max(1,(width*scale).toInt());val h=max(1,(height*scale).toInt())
    if(storage.size!=w*h) storage=IntArray(w*h)
    for(y in 0 until h) {val sy=min(height-1,(2*y+1)*height/(2*h));for(x in 0 until w) {
      val sx=min(width-1,(2*x+1)*width/(2*w));storage[y*w+x]=pixels[sy*width+sx]
    }}
    val detected=core.process(storage,w,h,time);val sx=width.toDouble()/w;val sy=height.toDouble()/h
    fun point(p: CourtPoint)=CourtPoint((p.x+.5)*sx-.5,(p.y+.5)*sy-.5)
    val observed=detected.observed?.let { b -> val p=point(CourtPoint(b.x,b.y))
      b.copy(x=p.x,y=p.y,radius=b.radius*min(sx,sy),diameterX=b.diameterX*sx,diameterY=b.diameterY*sy,colorUncertain=b.colorUncertain || !b.resolved)
    }
    return LocalResult(observed,detected.predicted?.let(::point),detected.trail.map(::point),null,false,null,
      if(observed==null)"Prévia procurando bola · inicie para contar impactos" else "Bola visível · inicie o treino para contar impactos",area,offset,
      diagnostics=detected.diagnostics.copy(samples=detected.diagnostics.samples.map { b ->
        val p=point(CourtPoint(b.x,b.y));b.copy(x=p.x,y=p.y,radius=b.radius*min(sx,sy),diameterX=b.diameterX*sx,diameterY=b.diameterY*sy)
      }))
  }
}
