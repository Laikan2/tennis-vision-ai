package ai.tennisvision.camera

import android.Manifest
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.CheckBox
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class CameraInterfaceTest {
  @get:Rule val permission = GrantPermissionRule.grant(Manifest.permission.CAMERA)
  private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
  private fun waitIdle() = InstrumentationRegistry.getInstrumentation().waitForIdleSync()
  private fun screenshot(activity: MainActivity, name: String) {
    val root = activity.window.decorView
    if (root.width == 0 || root.height == 0) return
    val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
    root.draw(Canvas(bitmap))
    val directory = File(context.getExternalFilesDir(null), "interface-proof").apply { mkdirs() }
    File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    bitmap.recycle()
  }

  @Test fun manualConnectionAndValidationRemainAccessible() {
    context.getSharedPreferences("pairing", 0).edit().clear().commit()
    ActivityScenario.launch(MainActivity::class.java).use { scenario ->
      waitIdle()
      scenario.onActivity { activity ->
        assertEquals(View.GONE, activity.findViewById<View>(R.id.manualFields).visibility)
        activity.findViewById<Button>(R.id.manualButton).performClick()
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.manualFields).visibility)
        activity.findViewById<EditText>(R.id.hostInput).setText("999.999.1.1")
        activity.findViewById<Button>(R.id.connectButton).performClick()
        assertTrue(activity.findViewById<TextView>(R.id.statusText).text.contains("inválido"))
        assertEquals(context.getString(R.string.connect), activity.findViewById<Button>(R.id.connectButton).text)
      }
      waitIdle()
      scenario.onActivity { screenshot(it, "connection") }
    }
  }

  @Test fun pairingSavesTheProfileFromTheNewQrAndTabsDoNotReset() {
    val intent = Intent(context, MainActivity::class.java).setData(Uri.parse(
      "tennisvision://pair?host=10.0.2.2&path=test_camera&width=1280&height=720&fps=30"))
    ActivityScenario.launch<MainActivity>(intent).use { scenario ->
      waitIdle()
      scenario.onActivity { activity ->
        assertEquals("10.0.2.2", activity.findViewById<EditText>(R.id.hostInput).text.toString())
        assertEquals(30, context.getSharedPreferences("pairing", 0).getInt("fps", 0))
        activity.findViewById<Button>(R.id.captureTab).performClick()
        activity.findViewById<CheckBox>(R.id.guideSwitch).isChecked = true
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.compositionGuide).visibility)
        screenshot(activity, "capture")
      }
      scenario.recreate()
      waitIdle()
      scenario.onActivity { activity ->
        assertTrue(activity.findViewById<Button>(R.id.captureTab).isSelected)
        assertTrue(activity.findViewById<CheckBox>(R.id.guideSwitch).isChecked)
        activity.findViewById<Button>(R.id.networkTab).performClick()
        screenshot(activity, "network")
      }
    }
  }

  @Test fun fullscreenCanBeExitedWithBackAndKeepsStreamControl() {
    ActivityScenario.launch(MainActivity::class.java).use { scenario ->
      waitIdle()
      scenario.onActivity { activity ->
        activity.findViewById<Button>(R.id.fullScreenButton).performClick()
        assertEquals(View.GONE, activity.findViewById<View>(R.id.controlPanel).visibility)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.quickConnect).visibility)
      }
      waitIdle()
      scenario.onActivity { activity ->
        screenshot(activity, "fullscreen")
        activity.onBackPressedDispatcher.onBackPressed()
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.controlPanel).visibility)
      }
    }
  }
}
