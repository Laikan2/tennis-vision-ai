package ai.tennisvision.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PairingConfigTest {
  @Test fun qrPreservesPortPathAndProfile() {
    val config = PairingConfig.parse("tennisvision://pair?host=192.168.15.2&port=9000&path=cam_2&transport=srt&fps=30&width=1280&height=720&bitrate=8000000")
    assertEquals(9000, config.port)
    assertEquals(30, config.fps)
    assertEquals(720, config.height)
    assertEquals("srt://192.168.15.2:9000?streamid=publish:cam_2&latency=40&pkt_size=1316", config.toSrtUrl())
  }

  @Test fun manualConnectionDoesNotInterpretQueryCharacters() {
    assertThrows(IllegalArgumentException::class.java) { PairingConfig("pc&port=1", path = "galaxy") }
    assertThrows(IllegalArgumentException::class.java) { PairingConfig("192.168.999.1") }
    assertThrows(IllegalArgumentException::class.java) { PairingConfig("pc.local", port = 0) }
    assertThrows(IllegalArgumentException::class.java) { PairingConfig("pc.local", path = "galaxy&x=1") }
  }

  @Test fun malformedPairingCannotSilentlySelectAnotherEndpoint() {
    for (query in listOf("port=invalid", "port=99999", "host=another.local", "transport=http", "path=%2Fother", "latency=0", "latency=oops", "latency=2000")) {
      assertThrows(IllegalArgumentException::class.java) {
        PairingConfig.parse("tennisvision://pair?host=192.168.15.2&path=galaxy&$query")
      }
    }
    assertThrows(IllegalArgumentException::class.java) {
      PairingConfig.parse("https://pair?host=192.168.15.2&path=galaxy")
    }
  }

  @Test fun hostnameAndDefaultPortAreAccepted() {
    val config = PairingConfig.parse("tennisvision://pair?host=tennis-pc.local&path=galaxy")
    assertEquals("tennis-pc.local", config.host)
    assertEquals(8890, config.port)
  }

  @Test fun explicitLatencyAndHighFrameRateSurvivePairing() {
    val config = PairingConfig.parse("tennisvision://pair?host=pc.local&path=galaxy&fps=240&latency=160")
    assertEquals(240, config.fps)
    assertEquals(160, config.latencyMs)
    assertEquals("srt://pc.local:8890?streamid=publish:galaxy&latency=160&pkt_size=1316", config.toSrtUrl())
  }
}
