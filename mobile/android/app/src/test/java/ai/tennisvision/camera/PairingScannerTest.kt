package ai.tennisvision.camera

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.os.Looper
import android.widget.Button
import android.widget.EditText
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.MultiFormatWriter
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.client.android.Intents
import com.google.zxing.common.HybridBinarizer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@LooperMode(LooperMode.Mode.PAUSED)
class PairingScannerTest {
  private val payload = "tennisvision://pair?host=192.168.15.2&port=8890&path=galaxy_b&width=1280&height=720&fps=120&bitrate=16000000"

  @Test fun includedDecoderReadsARealPairingQrWithoutPlayServices() {
    val matrix = MultiFormatWriter().encode(payload, BarcodeFormat.QR_CODE, 600, 600)
    val pixels = IntArray(600 * 600) { index -> if (matrix[index % 600, index / 600]) 0xff000000.toInt() else -1 }
    val decoded = MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(600, 600, pixels))))
    assertEquals(payload, decoded.text)
    assertEquals("galaxy_b", PairingConfig.parse(decoded.text).path)
    assertEquals(120, PairingConfig.parse(decoded.text).fps)
  }

  @Test fun scanButtonOpensTheIncludedActivityAndAppliesItsResult() {
    shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.CAMERA)
    val controller = Robolectric.buildActivity(MainActivity::class.java).create().start().resume().visible()
    val activity = controller.get()
    shadowOf(Looper.getMainLooper()).idle()
    activity.findViewById<Button>(R.id.scanButton).performClick()
    val launched = shadowOf(activity).nextStartedActivityForResult
    assertNotNull(launched)
    assertEquals(PairingScannerActivity::class.java.name, launched.intent.component?.className)
    assertFalse(activity.findViewById<Button>(R.id.connectButton).isEnabled)
    activity.activityResultRegistry.dispatchResult(launched.requestCode, Activity.RESULT_OK,
      Intent().putExtra(Intents.Scan.RESULT, payload).putExtra(Intents.Scan.RESULT_FORMAT, "QR_CODE"))
    assertEquals("192.168.15.2", activity.findViewById<EditText>(R.id.hostInput).text.toString())
    assertEquals("galaxy_b", activity.getSharedPreferences("pairing", 0).getString("path", ""))
    assertTrue(activity.findViewById<Button>(R.id.connectButton).isEnabled)
    controller.pause().stop().destroy()
  }

  @Test fun cancelScanRestoresControlsAndDoesNotEraseTheComputer() {
    shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.CAMERA)
    val intent = Intent(RuntimeEnvironment.getApplication(), MainActivity::class.java)
      .setData(android.net.Uri.parse(payload))
    val controller = Robolectric.buildActivity(MainActivity::class.java, intent).create().start().resume().visible()
    val activity = controller.get()
    shadowOf(Looper.getMainLooper()).idle()
    activity.findViewById<Button>(R.id.scanButton).performClick()
    val launched = shadowOf(activity).nextStartedActivityForResult
    activity.activityResultRegistry.dispatchResult(launched.requestCode, Activity.RESULT_CANCELED, Intent())
    assertEquals("192.168.15.2", activity.findViewById<EditText>(R.id.hostInput).text.toString())
    assertTrue(activity.findViewById<Button>(R.id.scanButton).isEnabled)
    assertTrue(activity.findViewById<Button>(R.id.connectButton).isEnabled)
    controller.pause().stop().destroy()
  }

  @Test fun cameraPermissionThenScanDoesNotStartTransmission() {
    val controller = Robolectric.buildActivity(MainActivity::class.java).create().start().resume().visible()
    val activity = controller.get()
    activity.findViewById<Button>(R.id.scanButton).performClick()
    val permission = shadowOf(activity).lastRequestedPermission
    shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.CAMERA)
    activity.activityResultRegistry.dispatchResult(permission.requestCode, true)
    val launched = shadowOf(activity).nextStartedActivityForResult
    assertEquals(PairingScannerActivity::class.java.name, launched.intent.component?.className)
    activity.activityResultRegistry.dispatchResult(launched.requestCode, Activity.RESULT_CANCELED, Intent())
    assertEquals(activity.getString(R.string.connect), activity.findViewById<Button>(R.id.connectButton).text)
    controller.pause().stop().destroy()
  }
}
