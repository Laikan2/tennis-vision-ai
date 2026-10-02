package ai.tennisvision.camera

import android.content.Context
import android.util.AttributeSet
import android.view.SurfaceView

/** Focus gestures emit a normal accessibility click as well as touch feedback. */
class CameraPreviewView(context: Context, attrs: AttributeSet? = null) : SurfaceView(context, attrs) {
  override fun performClick(): Boolean { super.performClick(); return true }
}
