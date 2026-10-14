package ai.tennisvision.camera

/** Pixel-only conversion: no Image/Plane method calls in the pixel loop. */
internal object Yuv420 {
  fun convert(yBytes: ByteArray,uBytes: ByteArray,vBytes: ByteArray,width: Int,height: Int,
    yStride: Int,uStride: Int,vStride: Int,yPixelStride: Int,uPixelStride: Int,vPixelStride: Int,
    rotation: Int): Triple<IntArray,Int,Int> {
    require(rotation in listOf(0,90,180,270))
    val w=if(rotation%180==0) width else height
    val h=if(rotation%180==0) height else width
    val pixels=IntArray(w*h)
    val step=when(rotation) { 90->w;180->-1;270->-w;else->1 }
    val nextRow=when(rotation) { 90->-1;180->-w;270->1;else->w }
    var y=0
    while(y<height) {
      var yi=y*yStride
      var ui=(y/2)*uStride;var vi=(y/2)*vStride
      var dest=when(rotation) { 90->height-1-y;180->(height-1-y)*w+width-1;270->(width-1)*w+y;else->y*w }
      val bottom=y+1<height
      var x=0
      while(x<width) {
        // Each chroma sample belongs to a 2x2 luma block. Read and multiply
        // it once, preserving the exact colors without reducing resolution.
        val uu=(uBytes[ui].toInt() and 255)-128
        val vv=(vBytes[vi].toInt() and 255)-128
        val red=409*vv;val green=-100*uu-208*vv;val blue=516*uu
        pixels[dest]=argb(yBytes[yi],red,green,blue)
        if(bottom) pixels[dest+nextRow]=argb(yBytes[yi+yStride],red,green,blue)
        if(x+1<width) {
          pixels[dest+step]=argb(yBytes[yi+yPixelStride],red,green,blue)
          if(bottom) pixels[dest+nextRow+step]=argb(yBytes[yi+yStride+yPixelStride],red,green,blue)
        }
        yi+=2*yPixelStride;dest+=2*step;ui+=uPixelStride;vi+=vPixelStride;x+=2
      }
      y+=2
    }
    return Triple(pixels,w,h)
  }

  private fun argb(y: Byte,red: Int,green: Int,blue: Int): Int {
    val luma=298*((y.toInt() and 255)-16)+128
    val r=((luma+red) shr 8).coerceIn(0,255)
    val g=((luma+green) shr 8).coerceIn(0,255)
    val b=((luma+blue) shr 8).coerceIn(0,255)
    return (255 shl 24) or (r shl 16) or (g shl 8) or b
  }
}
