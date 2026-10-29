package ai.tennisvision.camera
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import javax.imageio.ImageIO
import java.io.File
import kotlin.math.*
/** Optional private still background plus a known rendered moving target; NOT live footage accuracy. */
class PrivateBackgroundRecoveryTest {
  @Test fun frozenUserBackgroundAndKnownMovingBall(){
    val input=System.getenv("TENNIS_PRIVATE_BACKGROUND")
    assumeTrue("Private raw background unavailable",input!=null)
    val image=ImageIO.read(File(input!!));val w=image.width;val h=image.height
    val background=image.getRGB(0,0,w,h,null,0,w)
    val core=LocalAnalysis(LocalSettings(surface=ContactSurface.WALL))
    var stationary=0;var wrong=0;var found=0;var contacts=0
    repeat(40){i->
      val result=core.process(background,w,h,i/60.0)
      if(i>=20 && result.observed!=null)stationary++
      if(result.impact!=null)contacts++
    }
    repeat(30){i->
      val x=w*.25+i*w*.012;val y=h*.62+i*1.5;val r=18.0
      val pixels=background.copyOf()
      for(py in (y-r).toInt()..(y+r).toInt())for(px in (x-r).toInt()..(x+r).toInt())
        if(hypot(px-x,py-y)<=r)pixels[py*w+px]=0xffc7eb4d.toInt()
      val result=core.process(pixels,w,h,(40+i)/60.0)
      result.observed?.let { if(hypot(it.x-x,it.y-y)<=10)found++ else wrong++ }
      if(result.impact!=null)contacts++
    }
    println("PRIVATE_BACKGROUND frozen_false_observations=$stationary/20 rendered_ball=$found/30 wrong=$wrong contacts=$contacts")
    assertTrue("Known moving ball: $found/30",found>=25)
    assertEquals("No bounce in either sequence",0,contacts)
    assertEquals(0,wrong)
  }
}
