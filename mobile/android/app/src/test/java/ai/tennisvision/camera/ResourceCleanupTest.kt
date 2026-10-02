package ai.tennisvision.camera

import org.junit.Assert.*
import org.junit.Test

class ResourceCleanupTest {
  @Test fun cameraFailureStillClosesNetworkAndEncoder() {
    val closed = mutableListOf<String>()
    val first = IllegalStateException("camera timeout")
    val failure = assertThrows(IllegalStateException::class.java) {
      closeResources({ closed.add("camera"); throw first },
        { closed.add("network") }, { closed.add("encoder") })
    }
    assertSame(first, failure)
    assertEquals(listOf("camera", "network", "encoder"), closed)
  }

  @Test fun multipleFailuresRetainTheOriginalCauseAndFinishCleanup() {
    val last = mutableListOf<String>()
    val failure = assertThrows(IllegalArgumentException::class.java) {
      closeResources({ throw IllegalArgumentException("first") },
        { throw IllegalStateException("second") }, { last.add("released") })
    }
    assertEquals("first", failure.message)
    assertEquals("second", failure.suppressed.single().message)
    assertEquals(listOf("released"), last)
  }
}
