package ai.tennisvision.camera

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer
import java.util.ArrayDeque

/** A bounded encoder tap. Disk work never runs on Camera2 or the codec callback. */
internal interface RecordingSink {
  fun start(format: MediaFormat)
  fun write(bytes: ByteArray, pts: Long, flags: Int)
  fun stop(hasFrames: Boolean)
  fun release()
}
private class AndroidRecordingSink(file: File): RecordingSink {
  private val muxer=MediaMuxer(file.absolutePath,MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
  private var track=-1
  override fun start(format: MediaFormat) {track=muxer.addTrack(format);muxer.start()}
  override fun write(bytes: ByteArray,pts: Long,flags: Int) {
    muxer.writeSampleData(track,ByteBuffer.wrap(bytes),MediaCodec.BufferInfo().apply {set(0,bytes.size,pts,flags)})
  }
  override fun stop(hasFrames: Boolean) {if(hasFrames)muxer.stop()}
  override fun release() {muxer.release()}
}
internal class LocalRecording(private val file: File, private val completed: (File?, String?) -> Unit,
                     private val maxBytes: Int = 8 * 1024 * 1024,
                     private val sinkFactory: (File) -> RecordingSink = ::AndroidRecordingSink) {
  private data class Packet(val bytes: ByteArray, val pts: Long, val flags: Int)
  private val lock = Object()
  private val packets = ArrayDeque<Packet>()
  private var queuedBytes = 0
  private var format: MediaFormat? = null
  private var closing = false
  private var failure: String? = null
  private var waitingKeyframe = true
  @Volatile var frames = 0L
    private set
  @Volatile var bytes = 0L
    private set
  private val worker = Thread({ write() }, "original-video-writer").apply { start() }

  fun format(value: MediaFormat) = synchronized(lock) {
    if (!closing) { format = value; lock.notifyAll() }
  }
  fun packet(buffer: ByteBuffer, info: MediaCodec.BufferInfo) = synchronized(lock) {
    if (closing || info.size <= 0 || info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) return@synchronized
    if (waitingKeyframe) {
      if (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME == 0) return@synchronized
      waitingKeyframe = false
    }
    if (queuedBytes + info.size > maxBytes) {
      // Continuing after dropping P-frames would create a corrupt GOP. Close
      // a valid partial recording; network transmission remains independent.
      failure = "Armazenamento lento. A gravação foi encerrada para preservar o trecho válido."
      closing = true; lock.notifyAll(); return@synchronized
    }
    val source = buffer.duplicate()
    source.position(info.offset); source.limit(info.offset + info.size)
    val copy = ByteArray(info.size); source.get(copy)
    packets.add(Packet(copy, info.presentationTimeUs, info.flags)); queuedBytes += copy.size
    lock.notifyAll()
  }
  fun finish() { synchronized(lock) { closing = true; lock.notifyAll() }; worker.join(5_000) }

  private fun write() {
    var muxer: RecordingSink? = null
    var firstPts: Long? = null
    var lastPts = -1L
    try {
      while (true) {
        val packet = synchronized(lock) {
          while (!closing && (packets.isEmpty() || format == null)) lock.wait()
          if (packets.isEmpty() || format == null) null
          else packets.removeFirst().also { queuedBytes -= it.bytes.size }
        } ?: break
        if (muxer == null) {
          file.parentFile?.mkdirs()
          muxer = sinkFactory(file)
          muxer.start(checkNotNull(format))
        }
        val origin = firstPts ?: packet.pts.also { firstPts = it }
        val pts = packet.pts - origin
        check(pts > lastPts) { "O relógio do codificador regrediu. O trecho anterior foi preservado." }
        muxer.write(packet.bytes,pts,packet.flags)
        lastPts = pts; frames++; bytes += packet.bytes.size
      }
    } catch (e: Exception) {
      failure = e.message ?: "Não foi possível gravar o vídeo local."
    } finally {
      synchronized(lock) { closing = true; packets.clear(); queuedBytes = 0 }
      try { muxer?.stop(frames>0) } catch (_: Exception) { failure = "O arquivo não foi finalizado corretamente."; frames = 0 }
      try { muxer?.release() } catch (_: Exception) { }
      var result = file.takeIf { frames > 0 && it.isFile && it.length() > 0 }
      if(result!=null && file.name.endsWith(".mp4.pending")) {
        val finalized=File(file.parentFile,file.name.removeSuffix(".pending"))
        if(file.renameTo(finalized))result=finalized else {failure="O vídeo foi preservado, mas não foi possível finalizar seu nome. Compartilhe o arquivo para recuperá-lo."}
      }
      if (result == null) file.delete()
      completed(result, failure ?: if (result == null) "Nenhum quadro foi gravado." else null)
    }
  }
}
