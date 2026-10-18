package ai.tennisvision.camera

import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class LocalYuvBufferTest {
  private fun plane(bytes: ByteArray,row: Int,pixel: Int=1)=
    LocalYuvBuffer.Plane(ByteBuffer.wrap(bytes),row,pixel)

  @Test fun copiedPlanesSurviveNativeBufferReuseWithoutMovingBufferPosition() {
    val raw=ByteBuffer.allocateDirect(6).apply {
      put(byteArrayOf(9,16,235.toByte(),235.toByte(),16,9));position(1);limit(5)
    }
    val converter=LocalYuvBuffer()
    converter.copy(2,2,listOf(LocalYuvBuffer.Plane(raw,2,1),
      plane(byteArrayOf(128.toByte()),1),plane(byteArrayOf(128.toByte()),1)))
    assertEquals(1,raw.position());assertEquals(5,raw.limit())
    // Camera may overwrite its native storage immediately after Image.close().
    for(i in 1..4) raw.put(i,90)
    val (pixels,w,h)=converter.convert(0)
    assertEquals(2,w);assertEquals(2,h)
    assertArrayEquals(intArrayOf(0xff000000.toInt(),-1,-1,0xff000000.toInt()),pixels)
  }

  @Test fun laterFramesNeverMutateRgbSnapshotsKeptByPreviewOrImpactEvidence() {
    val converter=LocalYuvBuffer()
    val neutral=byteArrayOf(128.toByte())
    converter.copy(2,2,listOf(plane(ByteArray(4){16},2),plane(neutral,1),plane(neutral,1)))
    val first=converter.convert(0).first
    converter.copy(2,2,listOf(plane(ByteArray(4){235.toByte()},2),plane(neutral,1),plane(neutral,1)))
    val next=converter.convert(0).first
    assertNotSame(first,next)
    assertArrayEquals(IntArray(4){0xff000000.toInt()},first)
    assertArrayEquals(IntArray(4){-1},next)
  }

  @Test fun formatChangeRefreshesStrideDimensionsAndRotation() {
    val converter=LocalYuvBuffer()
    converter.copy(2,2,listOf(plane(ByteArray(4){16},2),
      plane(byteArrayOf(128.toByte()),1),plane(byteArrayOf(128.toByte()),1)))
    converter.convert(0)
    val y=byteArrayOf(16,40,80,100,7,7,7,7,120,160.toByte(),200.toByte(),235.toByte())
    val u=byteArrayOf(90,7,160.toByte());val v=byteArrayOf(150.toByte(),7,80)
    converter.copy(4,2,listOf(plane(y,8),plane(u,4,2),plane(v,4,2)))
    val expected=Yuv420.convert(y,u,v,4,2,8,4,4,1,2,2,90)
    val actual=converter.convert(90)
    assertEquals(2,actual.second);assertEquals(4,actual.third)
    assertArrayEquals(expected.first,actual.first)
  }

  @Test fun cameraCloseClearsOldFrameAndCanReopenWithNewPlanes() {
    val converter=LocalYuvBuffer()
    val neutral=byteArrayOf(128.toByte())
    converter.copy(2,2,listOf(plane(ByteArray(4){16},2),plane(neutral,1),plane(neutral,1)))
    converter.clear()
    assertThrows(IllegalStateException::class.java) { converter.convert(0) }
    converter.copy(2,2,listOf(plane(ByteArray(4){235.toByte()},2),plane(neutral,1),plane(neutral,1)))
    assertArrayEquals(IntArray(4){-1},converter.convert(0).first)
  }
}
