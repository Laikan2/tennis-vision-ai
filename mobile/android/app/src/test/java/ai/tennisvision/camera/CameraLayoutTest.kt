package ai.tennisvision.camera

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.Shadows.shadowOf
import android.os.Looper
import android.Manifest
import android.content.Intent
import android.net.Uri
import android.content.pm.PackageManager
import org.robolectric.RuntimeEnvironment
import java.io.File

/** Render the real Android resources; this is not a separate UI mockup. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w960dp-h432dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class CameraLayoutTest {
  private fun draw(activity: MainActivity, name: String, width: Int = 1920, height: Int = 864) {
    val root = activity.findViewById<ConstraintLayout>(R.id.cameraRoot)
    root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
      View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
    root.layout(0, 0, width, height)
    assertTrue(activity.findViewById<View>(R.id.previewContainer).width > 0)
    if (activity.findViewById<View>(R.id.controlPanel).visibility == View.VISIBLE) {
      val panel = activity.findViewById<View>(R.id.controlPanel)
      assertTrue(panel.right <= width)
      assertTrue(activity.findViewById<View>(R.id.connectButton).bottom <= panel.height)
    }
    val image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    root.draw(Canvas(image))
    val folder = File("build/reports/interface-proof").apply { mkdirs() }
    File(folder, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    image.recycle()
  }
  @Test fun layoutsAndManualPairingUseTheRealResources() {
    val controller = Robolectric.buildActivity(MainActivity::class.java).create().start().resume().visible()
    val activity = controller.get()
    shadowOf(Looper.getMainLooper()).idle()
    draw(activity, "android-connection")
    activity.findViewById<Button>(R.id.manualButton).performClick()
    assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.manualFields).visibility)
    activity.findViewById<EditText>(R.id.hostInput).setText("192.168.999.1")
    activity.findViewById<Button>(R.id.captureTab).performClick()
    activity.findViewById<CheckBox>(R.id.guideSwitch).isChecked = true
    draw(activity, "android-capture")
    activity.findViewById<Button>(R.id.networkTab).performClick()
    draw(activity, "android-network")
    assertNotNull(activity.findViewById<View>(R.id.updateSection))
    assertTrue(activity.findViewById<Button>(R.id.checkUpdates).isShown)
    activity.findViewById<Button>(R.id.fullScreenButton).performClick()
    draw(activity, "android-fullscreen")
    activity.onBackPressedDispatcher.onBackPressed()
    assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.controlPanel).visibility)
    controller.pause().stop().destroy()
  }
  @Test @Config(qualifiers = "w800dp-h360dp-land-xhdpi") fun compactPhoneKeepsItsMainActionVisible() {
    val controller = Robolectric.buildActivity(MainActivity::class.java).create().start().resume().visible()
    shadowOf(Looper.getMainLooper()).idle()
    draw(controller.get(), "android-compact", 1600, 720)
    controller.pause().stop().destroy()
  }
  @Test @Config(qualifiers = "w412dp-h892dp-port-xhdpi") fun portraitWindowAdaptsWithoutHorizontalOverflow() {
    val controller = Robolectric.buildActivity(MainActivity::class.java).create().start().resume().visible()
    shadowOf(Looper.getMainLooper()).idle()
    draw(controller.get(), "android-portrait", 824, 1784)
    controller.pause().stop().destroy()
  }
  @Test fun qrProfileAndUserControlsSurviveRecreation() {
    val intent = Intent(RuntimeEnvironment.getApplication(), MainActivity::class.java).setData(Uri.parse(
      "tennisvision://pair?host=192.168.15.2&path=cam_b&fps=30&width=1280&height=720"))
    val controller = Robolectric.buildActivity(MainActivity::class.java, intent).create().start().resume().visible()
    var activity = controller.get()
    shadowOf(Looper.getMainLooper()).idle()
    val saved = activity.getSharedPreferences("pairing", 0)
    assertEquals(30, saved.getInt("fps", 0))
    assertEquals(1280, saved.getInt("width", 0))
    assertEquals("cam_b", saved.getString("path", ""))
    activity.findViewById<Button>(R.id.captureTab).performClick()
    activity.findViewById<CheckBox>(R.id.guideSwitch).isChecked = true
    controller.recreate()
    activity = controller.get()
    shadowOf(Looper.getMainLooper()).idle()
    assertTrue(activity.findViewById<Button>(R.id.captureTab).isSelected)
    assertTrue(activity.findViewById<CheckBox>(R.id.guideSwitch).isChecked)
    controller.pause().stop().destroy()
  }
  @Test fun permissionResultContinuesTheRequestedConnectionAndValidatesHost() {
    val controller = Robolectric.buildActivity(MainActivity::class.java).create().start().resume().visible()
    val activity = controller.get()
    activity.findViewById<Button>(R.id.manualButton).performClick()
    activity.findViewById<EditText>(R.id.hostInput).setText("999.1.1.1")
    activity.findViewById<Button>(R.id.connectButton).performClick()
    val request = shadowOf(activity).lastRequestedPermission
    assertNotNull(request)
    shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.CAMERA)
    assertEquals(PackageManager.PERMISSION_GRANTED, activity.checkSelfPermission(Manifest.permission.CAMERA))
    activity.activityResultRegistry.dispatchResult(request.requestCode, true)
    val status = activity.findViewById<TextView>(R.id.statusText).text.toString()
    assertTrue("Status: $status", status.contains("inválido"))
    assertEquals(activity.getString(R.string.connect), activity.findViewById<Button>(R.id.connectButton).text)
    controller.pause().stop().destroy()
  }
  @Test fun aConnectionErrorDoesNotDisplayAnOldPreviewAsLive() {
    val controller = Robolectric.buildActivity(MainActivity::class.java).create().start().resume().visible()
    val activity = controller.get()
    activity.findViewById<View>(R.id.previewEmpty).visibility = View.GONE
    activity.onAuthError()
    shadowOf(Looper.getMainLooper()).idle()
    assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.previewEmpty).visibility)
    assertTrue(activity.findViewById<TextView>(R.id.statusText).text.contains("recusada"))
    assertEquals(activity.getString(R.string.connect), activity.findViewById<Button>(R.id.connectButton).text)
    controller.pause().stop().destroy()
  }

  @Test fun versionBadgeOpensTheUpdateSectionWithoutChangingPairing() {
    val controller = Robolectric.buildActivity(MainActivity::class.java).create().start().resume().visible()
    val activity = controller.get()
    shadowOf(Looper.getMainLooper()).idle()
    activity.findViewById<TextView>(R.id.versionText).performClick()
    shadowOf(Looper.getMainLooper()).idle()
    assertTrue(activity.findViewById<Button>(R.id.networkTab).isSelected)
    assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.networkSection).visibility)
    draw(activity, "android-updates")
    val visible = android.graphics.Rect()
    assertTrue(activity.findViewById<View>(R.id.checkUpdates).getGlobalVisibleRect(visible))
    assertTrue(visible.height() > 0)
    controller.pause().stop().destroy()
  }
}
