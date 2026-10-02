package ai.tennisvision.camera

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat

/** One brief visual response to a focus tap; no render loop during transmission. */
class FocusIndicatorView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
  private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = ContextCompat.getColor(context, R.color.tv_accent)
    style = Paint.Style.STROKE
    strokeWidth = resources.displayMetrics.density * 1.5f
  }
  private var point: Pair<Float, Float>? = null
  private val clear = Runnable { point = null; invalidate() }
  fun show(x: Float, y: Float) {
    point = x to y
    removeCallbacks(clear)
    invalidate()
    postDelayed(clear, 1_000)
  }
  override fun onDraw(canvas: Canvas) {
    point?.let { (x, y) ->
      val radius = 22 * resources.displayMetrics.density
      canvas.drawRoundRect(x-radius, y-radius, x+radius, y+radius, radius/4, radius/4, paint)
      canvas.drawCircle(x, y, 2 * resources.displayMetrics.density, paint)
    }
  }
  override fun onDetachedFromWindow() { removeCallbacks(clear); super.onDetachedFromWindow() }
}
