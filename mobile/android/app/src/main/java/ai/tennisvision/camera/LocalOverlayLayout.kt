package ai.tennisvision.camera

import android.graphics.RectF
import kotlin.math.ceil
import kotlin.math.min

/** A bounded callout search. Call newest first; markers are obstacles, never moved. */
internal object LocalOverlayLayout {
  fun place(viewport:RectF,width:Float,height:Float,x:Float,y:Float,gap:Float,occupied:List<RectF>):RectF? {
    if(width<=0 || height<=0 || width>viewport.width() || height>viewport.height()) return null
    val rows=min(12,ceil(viewport.height()/(height+gap)).toInt())
    for(row in 0..rows) {
      val shifts=if(row==0) intArrayOf(0) else intArrayOf(-row,row)
      for(shift in shifts) for(left in floatArrayOf(x,x-width-2*gap)) {
        val px=left.coerceIn(viewport.left,viewport.right-width)
        val py=(y+shift*(height+gap)).coerceIn(viewport.top,viewport.bottom-height)
        val box=RectF(px,py,px+width,py+height)
        if(occupied.none { it.left<box.right+gap && it.right>box.left-gap && it.top<box.bottom+gap && it.bottom>box.top-gap }) return box
      }
    }
    return null
  }
}
