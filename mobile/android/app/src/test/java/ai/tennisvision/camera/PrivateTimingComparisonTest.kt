package ai.tennisvision.camera

import java.io.DataInputStream
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Same input and warm-up for both revisions; no extra detector inside the timed loop. */
class PrivateTimingComparisonTest {
  @Test fun repeatedPrivateFramesMeasureOnlyTheCore() {
    val input=System.getenv("TENNIS_PRIVATE_TIMING")
    assumeTrue("Private timing input unavailable",input!=null)
    val dir=File(input!!)
    val rows=dir.resolve("frames.tsv").readLines()
    val settings=LocalSettings(surface=ContactSurface.WALL,
      polygon=listOf(CourtPoint(.01,.01),CourtPoint(.99,.01),CourtPoint(.99,.99),CourtPoint(.01,.99)))
    val out=StringBuilder("round,time,x,y,impact,elapsedMs\n")
    repeat(3) { round ->
      val core=LocalAnalysis(settings)
      rows.forEach { row ->
        val f=row.split('\t');val time=f[1].toDouble();val w=f[2].toInt();val h=f[3].toInt()
        val pixels=DataInputStream(dir.resolve(f[0]).inputStream().buffered()).use{s->IntArray(w*h){s.readInt()}}
        val start=System.nanoTime();val r=core.process(pixels,w,h,time);val ms=(System.nanoTime()-start)/1e6
        if(round>0)out.append(listOf(round,time,r.observed?.x,r.observed?.y,r.impact?.call,ms).joinToString(",")).append('\n')
      }
    }
    File("build/reports/private-timing.csv").apply{parentFile?.mkdirs();writeText(out.toString())}
  }
}
