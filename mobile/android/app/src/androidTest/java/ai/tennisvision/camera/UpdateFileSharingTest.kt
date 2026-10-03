package ai.tennisvision.camera

import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Android filesystem/URI permissions require a device or emulator, not a Windows JVM. */
@RunWith(AndroidJUnit4::class)
class UpdateFileSharingTest {
  @Test fun updateFileCanBeReadThroughItsUriAndOtherPrivateFilesCannotBeShared() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val folder = File(context.filesDir, "updates").apply { mkdirs() }
    val apk = File(folder, "provider-scope-proof.apk")
    val outside = File(context.filesDir, "outside-provider-proof.apk")
    val bytes = "File sharing test only".toByteArray()
    try {
      apk.writeBytes(bytes)
      outside.writeBytes(bytes)
      val uri = FileProvider.getUriForFile(context, context.packageName + ".updates", apk)
      assertEquals("content", uri.scheme)
      assertArrayEquals(bytes, context.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
      assertThrows(IllegalArgumentException::class.java) {
        FileProvider.getUriForFile(context, context.packageName + ".updates", outside)
      }
    } finally { apk.delete(); outside.delete() }
  }
}
