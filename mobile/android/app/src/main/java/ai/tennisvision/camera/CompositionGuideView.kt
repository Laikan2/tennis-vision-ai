package ai.tennisvision.camera

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/** Preview composition aid only; never drawn into the transmitted camera surface. */
class CompositionGuideView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
  private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Color.argb(90, 237, 245, 241)
    strokeWidth = resources.displayMetrics.density
  }
  override fun onDraw(canvas: Canvas) {
    super.onDraw(canvas)
    for (i in 1..2) {
      canvas.drawLine(width*i/3f, 0f, width*i/3f, height.toFloat(), paint)
      canvas.drawLine(0f, height*i/3f, width.toFloat(), height*i/3f, paint)
    }
  }
}
