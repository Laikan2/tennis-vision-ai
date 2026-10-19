package ai.tennisvision.camera

import android.Manifest
import android.content.pm.PackageManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Run separately after revoking CAMERA on the isolated emulator. Never clear user sessions. */
@RunWith(AndroidJUnit4::class)
class LocalPermissionTest {
  @Test fun allowingCameraActuallyProducesFrames() {
    val instrumentation=InstrumentationRegistry.getInstrumentation(); val context=instrumentation.targetContext
    assertEquals(PackageManager.PERMISSION_DENIED,context.checkSelfPermission(Manifest.permission.CAMERA))
    context.getSharedPreferences("standalone-settings-v1",0).edit().putBoolean("asked-camera",false).commit()
    instrumentation.uiAutomation.serviceInfo=instrumentation.uiAutomation.serviceInfo.apply {
      flags=flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
    }
    ActivityScenario.launch(LocalTrainingActivity::class.java).use { scenario ->
      scenario.onActivity { it.findViewById<Button>(R.id.localStart).performClick() }
      var allow: AccessibilityNodeInfo?=null
      repeat(40) { if (allow==null) { allow=instrumentation.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByViewId("com.android.permissioncontroller:id/permission_allow_foreground_only_button")?.firstOrNull(); if (allow==null) Thread.sleep(100) } }
      assertNotNull(allow); assertTrue(allow!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
      var ready=false
      repeat(80) { if (!ready) { Thread.sleep(100); scenario.onActivity { ready=it.findViewById<Button>(R.id.localStart).text.toString()=="Iniciar treino" } } }
      assertTrue("Camera must actually deliver a frame after grant",ready)
      assertEquals(PackageManager.PERMISSION_GRANTED,context.checkSelfPermission(Manifest.permission.CAMERA))
    }
  }

  @Test fun deniedCameraNeverPretendsReadyAndCanOpenSettings() {
    val instrumentation=InstrumentationRegistry.getInstrumentation(); val context=instrumentation.targetContext
    instrumentation.uiAutomation.serviceInfo=instrumentation.uiAutomation.serviceInfo.apply {
      flags=flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
    }
    assertEquals("Run this test with camera permission revoked",PackageManager.PERMISSION_DENIED,context.checkSelfPermission(Manifest.permission.CAMERA))
    context.getSharedPreferences("standalone-settings-v1",0).edit().putBoolean("asked-camera",false).commit()
    fun clickSystem(id: String) {
      var found: AccessibilityNodeInfo?=null
      repeat(50) {
        if (found==null) {
          val root=instrumentation.uiAutomation.rootInActiveWindow
          found=root?.findAccessibilityNodeInfosByViewId(id)?.firstOrNull()
          if (found==null && id.endsWith("permission_deny_button")) found=root?.findAccessibilityNodeInfosByViewId("com.android.permissioncontroller:id/permission_deny_and_dont_ask_again_button")?.firstOrNull()
          if (found==null) Thread.sleep(100)
        }
      }
      if (found==null) {
        fun tree(node: AccessibilityNodeInfo?,depth: Int=0): String = if (node==null || depth>12) "" else
          "${node.packageName} ${node.viewIdResourceName} ${node.text}\n"+(0 until node.childCount).joinToString("") { tree(node.getChild(it),depth+1) }
        fail("Missing system action $id\n"+tree(instrumentation.uiAutomation.rootInActiveWindow))
      }
      assertTrue(found!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
      instrumentation.waitForIdleSync(); Thread.sleep(250)
    }
    ActivityScenario.launch(LocalTrainingActivity::class.java).use { scenario ->
      repeat(2) {
        var before=0
        scenario.onActivity { before=it.testSession().events.size }
        scenario.onActivity { it.findViewById<Button>(R.id.localStart).performClick() }
        clickSystem("com.android.permissioncontroller:id/permission_deny_button")
        // The system dialog can disappear before ActivityResult is delivered.
        // Await the actual denied state rather than assuming a 250 ms delay.
        var denied=false;var actual=""
        repeat(40) { if(!denied) {
          Thread.sleep(100)
          scenario.onActivity {
            actual=it.findViewById<TextView>(R.id.localStatus).text.toString()
            denied=actual.contains("não autorizada") && it.findViewById<Button>(R.id.localStart).text.toString()=="Permitir câmera"
          }
        } }
        assertTrue("Permission denial must reach the app; actual status: $actual",denied)
        scenario.onActivity {
          assertEquals("Denied camera cannot add events",before,it.testSession().events.size)
        }
        assertEquals(PackageManager.PERMISSION_DENIED,context.checkSelfPermission(Manifest.permission.CAMERA))
      }
      scenario.onActivity { it.findViewById<Button>(R.id.localStart).performClick() }
      clickSystem("android:id/button1")
      var settingsOpened=false
      repeat(30) { if (instrumentation.uiAutomation.rootInActiveWindow?.packageName=="com.android.settings") settingsOpened=true; if (!settingsOpened) Thread.sleep(100) }
      assertTrue("Application settings should be opened",settingsOpened)
    }
  }
}
