package ai.tennisvision.camera

import org.junit.Assert.*
import org.junit.Test

class Yuv420Test {
  @Test fun paddedAndInterleavedPlanesPreserveColorsAndAllRotations() {
    // Real ImageReader planes can omit trailing row padding and interleave U/V.
    val width=4;val height=2
    val y=byteArrayOf(16,40,80,100,7,7,7,7,120,160.toByte(),200.toByte(),235.toByte())
    val u=byteArrayOf(90,7,160.toByte());val v=byteArrayOf(150.toByte(),7,80)
    fun rgb(yy: Int,uu: Int,vv: Int): Int {
      val l=298*(yy-16)+128;val a=uu-128;val b=vv-128
      return 0xff000000.toInt() or (((l+409*b) shr 8).coerceIn(0,255) shl 16) or
        (((l-100*a-208*b) shr 8).coerceIn(0,255) shl 8) or ((l+516*a) shr 8).coerceIn(0,255)
    }
    val source=IntArray(8) { i->rgb(y[(i/4)*8+i%4].toInt() and 255,u[(i%4)/2*2].toInt() and 255,v[(i%4)/2*2].toInt() and 255) }
    val permutations=mapOf(0 to listOf(0,1,2,3,4,5,6,7),90 to listOf(4,0,5,1,6,2,7,3),
      180 to listOf(7,6,5,4,3,2,1,0),270 to listOf(3,7,2,6,1,5,0,4))
    for((rotation,order) in permutations) {
      val (pixels,w,h)=Yuv420.convert(y,u,v,width,height,8,4,4,1,2,2,rotation)
      assertEquals(if(rotation%180==0) 4 else 2,w);assertEquals(if(rotation%180==0) 2 else 4,h)
      assertArrayEquals(order.map { source[it] }.toIntArray(),pixels)
    }
  }
  @Test fun neutralChromaGivesBlackAndWhiteWithSparseLuma() {
    val y=byteArrayOf(16,9,235.toByte(),9,16,9,235.toByte())
    val (pixels,_,_)=Yuv420.convert(y,byteArrayOf(128.toByte()),byteArrayOf(128.toByte()),2,2,4,1,1,2,1,1,0)
    assertArrayEquals(intArrayOf(0xff000000.toInt(),0xffffffff.toInt(),0xff000000.toInt(),0xffffffff.toInt()),pixels)
  }
  @Test fun oddEdgesAndPaddedPlanesMatchScalarReferenceAcrossRotations() {
    val random=java.util.Random(42)
    for(width in listOf(1,3,16)) for(height in listOf(1,3,10)) {
      val yStride=width*2+3;val uvStride=((width+1)/2)*2+4
      val y=ByteArray((height-1)*yStride+(width-1)*2+1).also { random.nextBytes(it) }
      val u=ByteArray(((height-1)/2)*uvStride+((width-1)/2)*2+1).also { random.nextBytes(it) }
      val v=ByteArray(u.size).also { random.nextBytes(it) }
      for(rotation in listOf(0,90,180,270)) {
        val outW=if(rotation%180==0) width else height
        val expected=IntArray(width*height)
        for(py in 0 until height) for(px in 0 until width) {
          val yy=(y[py*yStride+px*2].toInt() and 255)-16
          val uu=(u[(py/2)*uvStride+(px/2)*2].toInt() and 255)-128
          val vv=(v[(py/2)*uvStride+(px/2)*2].toInt() and 255)-128
          val r=((298*yy+409*vv+128) shr 8).coerceIn(0,255)
          val g=((298*yy-100*uu-208*vv+128) shr 8).coerceIn(0,255)
          val b=((298*yy+516*uu+128) shr 8).coerceIn(0,255)
          val (rx,ry)=when(rotation) { 90->height-1-py to px;180->width-1-px to height-1-py;
            270->py to width-1-px;else->px to py }
          expected[ry*outW+rx]=0xff000000.toInt() or (r shl 16) or (g shl 8) or b
        }
        val (actual,_,_)=Yuv420.convert(y,u,v,width,height,yStride,uvStride,uvStride,2,2,2,rotation)
        assertArrayEquals("${width}x$height rotation $rotation",expected,actual)
      }
    }
  }
}
