package ai.tennisvision.camera

import android.media.MediaCodec
import android.media.MediaFormat
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[33])
class LocalRecordingTest {
  private class Sink(private val file:File,private val blocked:CountDownLatch?=null):RecordingSink {
    val times=mutableListOf<Long>()
    val entered=CountDownLatch(1)
    override fun start(format:MediaFormat) {file.writeBytes(byteArrayOf(1))}
    override fun write(bytes:ByteArray,pts:Long,flags:Int) {entered.countDown();blocked?.await(3,TimeUnit.SECONDS);times.add(pts);file.appendBytes(bytes)}
    override fun stop(hasFrames:Boolean) {}
    override fun release() {}
  }
  private fun packet(recorder:LocalRecording,pts:Long,key:Boolean=false,size:Int=4) {
    val bytes=ByteArray(size+2){it.toByte()}
    recorder.packet(ByteBuffer.wrap(bytes),MediaCodec.BufferInfo().apply {set(2,size,pts,if(key)MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)})
  }
  @Test fun preserves240FpsTimestampsStartsAtKeyframeAndWritesOffTheProducer() {
    val file=File.createTempFile("tennis-recorder",".mp4")
    val sink=Sink(file);var saved:File?=null;var error:String?=null
    val recorder=LocalRecording(file,{f,e->saved=f;error=e},sinkFactory={sink})
    recorder.format(MediaFormat.createVideoFormat("video/avc",1280,720))
    packet(recorder,1000) // ignore undecodable P-frame before first I-frame
    for(i in 0..120)packet(recorder,500_000+i*1_000_000L/240,key=i==0)
    recorder.finish()
    assertEquals(121,recorder.frames.toInt());assertEquals(0L,sink.times.first());assertEquals(500_000L,sink.times.last())
    assertEquals(file,saved);assertNull(error);file.delete()
  }
  @Test fun slowStorageClosesAValidPartialFileWithoutDroppingFramesInsideIt() {
    val file=File.createTempFile("tennis-recorder",".mp4")
    val release=CountDownLatch(1);val sink=Sink(file,release);var error:String?=null
    val recorder=LocalRecording(file,{_,e->error=e},maxBytes=8,sinkFactory={sink})
    recorder.format(MediaFormat.createVideoFormat("video/avc",1280,720));packet(recorder,0,true)
    assertTrue(sink.entered.await(3,TimeUnit.SECONDS))
    packet(recorder,4000);packet(recorder,8000);packet(recorder,12000) // overflow stops before this frame
    release.countDown();recorder.finish()
    assertEquals(listOf(0L,4000L,8000L),sink.times);assertTrue(error!!.contains("Armazenamento lento"));file.delete()
  }
  @Test fun aRegressingClockPreservesTheValidPrefixInsteadOfWritingBadPts() {
    val file=File.createTempFile("tennis-recorder",".mp4");val sink=Sink(file);var error:String?=null
    val recorder=LocalRecording(file,{_,e->error=e},sinkFactory={sink})
    recorder.format(MediaFormat.createVideoFormat("video/avc",1280,720))
    packet(recorder,1000,true);packet(recorder,500);recorder.finish()
    assertEquals(listOf(0L),sink.times);assertTrue(error!!.contains("regrediu"));file.delete()
  }
  @Test fun onlyPublishesAnMp4AfterTheWriterFinalizesIt() {
    val file=File.createTempFile("tennis-recorder",".mp4.pending");val sink=Sink(file);var saved:File?=null
    val recorder=LocalRecording(file,{f,_->saved=f},sinkFactory={sink})
    recorder.format(MediaFormat.createVideoFormat("video/avc",1280,720));packet(recorder,0,true);recorder.finish()
    assertNotNull(saved);assertEquals("mp4",saved!!.extension);assertFalse(file.exists());saved!!.delete()
  }
  @Test fun aFailedRenameDoesNotDeleteAValidRecording() {
    val file=File.createTempFile("tennis-recorder",".mp4.pending");val blockedName=File(file.parentFile,file.name.removeSuffix(".pending"))
    assertTrue(blockedName.mkdir());val sink=Sink(file);var saved:File?=null;var error:String?=null
    val recorder=LocalRecording(file,{f,e->saved=f;error=e},sinkFactory={sink})
    recorder.format(MediaFormat.createVideoFormat("video/avc",1280,720));packet(recorder,0,true);recorder.finish()
    assertEquals(file,saved);assertTrue(file.length()>0);assertTrue(error!!.contains("preservado"))
    file.delete();blockedName.delete()
  }
}
