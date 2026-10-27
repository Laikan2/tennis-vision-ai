package ai.tennisvision.camera

import java.io.DataInputStream
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in replay of private frames. Never packages footage or asserts unreviewed ground truth. */
class PrivateSequenceAuditTest {
  @Test fun sequentialReplayRetainsTrackerAndDetectorState() {
    val input=System.getenv("TENNIS_PRIVATE_FRAMES")
    assumeTrue("Private video unavailable", input!=null)
    val dir=File(input!!)
    val settings=LocalSettings(surface=ContactSurface.WALL,
      polygon=listOf(CourtPoint(.01,.01),CourtPoint(.99,.01),CourtPoint(.99,.99),CourtPoint(.01,.99)))
    val core=LocalAnalysis(settings)
    val detector=GreenBallDetector()
    val output=StringBuilder("time,x,y,radius,confidence,nearHand,occluded,uncertain,moving,impact,impactX,impactY,candidates,elapsedMs,message\n")
    var last:BallObservation?=null
    dir.resolve("frames.tsv").readLines().forEach { row ->
      val f=row.split('\t');val time=f[1].toDouble();val w=f[2].toInt();val h=f[3].toInt()
      val pixels=DataInputStream(dir.resolve(f[0]).inputStream().buffered()).use { stream -> IntArray(w*h){stream.readInt()} }
      val start=System.nanoTime();val r=core.process(pixels,w,h,time);val elapsed=(System.nanoTime()-start)/1e6
      val candidates=detector.detect(pixels,w,h,time,last).first
      val b=r.observed;last=b
      output.append(listOf(time,b?.x,b?.y,b?.radius,b?.confidence,b?.nearHand,b?.occluded,b?.colorUncertain,
        r.movingCamera,r.impact?.call,r.impact?.point?.x,r.impact?.point?.y,
        candidates.joinToString(";"){"${it.x}:${it.y}:${it.radius}:${it.confidence}:chroma=${it.feltChroma}:hand=${it.nearHand}:contrast=${it.contrastFelt}:pattern=${it.patterned}"},
        elapsed,r.message.replace(',',';')).joinToString(",")).append('\n')
    }
    File("build/reports/private-sequence.csv").apply { parentFile?.mkdirs();writeText(output.toString()) }
  }
}
