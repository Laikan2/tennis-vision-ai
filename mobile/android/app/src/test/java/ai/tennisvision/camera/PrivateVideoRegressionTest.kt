package ai.tennisvision.camera

import java.io.File
import java.io.DataInputStream
import org.junit.Test
import org.junit.Assume.assumeTrue

/** Optional private user-video replay: inputs remain outside the source tree and APK. */
class PrivateVideoRegressionTest {
  @Test fun exportActualDetectorResultsForManuallyReviewedFrames() {
    val path=System.getenv("TENNIS_PRIVATE_FRAMES")
    assumeTrue("Private cropped recording is not available",path!=null)
    val dir=File(path!!)
    val result=StringBuilder("file,time,x,y,radius,confidence,nearHand,occluded,candidates,elapsedMs,message\n")
    dir.resolve("frames.tsv").readLines().forEach { row ->
      val f=row.split('\t');val time=f[1].toDouble();val w=f[2].toInt();val h=f[3].toInt()
      val pixels=DataInputStream(dir.resolve(f[0]).inputStream().buffered()).use { input->IntArray(w*h){input.readInt()} }
      val core=LocalAnalysis(LocalSettings(surface=ContactSurface.TRACK))
      val start=System.nanoTime();val r=core.process(pixels,w,h,time);val ms=(System.nanoTime()-start)/1e6
      val candidates=GreenBallDetector().detect(pixels,w,h,time,null).first
      val b=r.observed
      result.append(listOf(f[0],time,b?.x,b?.y,b?.radius,b?.confidence,b?.nearHand,b?.occluded,
        candidates.joinToString(";"){"${it.x}:${it.y}:${it.radius}:${it.confidence}"},ms,r.message.replace(',',';')).joinToString(",")).append('\n')
    }
    File("build/reports/private-video.csv").apply { parentFile?.mkdirs();writeText(result.toString()) }
  }
}
