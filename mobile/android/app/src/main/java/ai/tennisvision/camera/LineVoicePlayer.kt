package ai.tennisvision.camera

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.os.SystemClock

internal object LineVoicePolicy {
  fun enabled(call: LineCall,out: Boolean,inside: Boolean)=when(call) {
    LineCall.OUT->out;LineCall.IN->inside;else->false
  }
  fun canStart(next: LineCall,current: LineCall?)=current==null || next==LineCall.OUT && current==LineCall.IN
}

/** Two offline voice samples; no network, synthesis, or delayed announcement queue. */
internal class LineVoicePlayer(private val context: Context) {
  private val pool=SoundPool.Builder().setMaxStreams(1).setAudioAttributes(AudioAttributes.Builder()
    .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()).build()
  private val samples=mutableMapOf<LineCall,Int>()
  private val loaded=mutableSetOf<Int>()
  private var stream=0
  private var current: LineCall?=null
  private var endsAt=0L
  private var closed=false
  var startedCount=0;private set
  var onStatus: ((String)->Unit)?=null
  init {
    pool.setOnLoadCompleteListener { _,id,code -> if(!closed && code==0) loaded.add(id) }
    samples[LineCall.OUT]=pool.load(context,R.raw.out_natural,1)
    samples[LineCall.IN]=pool.load(context,R.raw.in_natural,1)
  }
  fun volumeDescription(): String {
    val audio=context.getSystemService(AudioManager::class.java)
    val value=audio.getStreamVolume(AudioManager.STREAM_MUSIC)
    val max=audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
    return "Volume de mídia ${value*100/max}% · use os botões laterais para ajustar"
  }
  fun play(call: LineCall,test: Boolean=false): Boolean {
    if(closed || call !in samples) return false
    if(SystemClock.elapsedRealtime()>=endsAt) current=null
    if(!test && !LineVoicePolicy.canStart(call,current)) return false
    val id=samples.getValue(call)
    if(id !in loaded) { onStatus?.invoke("Som ${call.name} ainda carregando · teste novamente.");return false }
    stop()
    stream=pool.play(id,1f,1f,if(call==LineCall.OUT) 2 else 1,0,1f)
    if(stream==0) { onStatus?.invoke("Não iniciou o áudio ${call.name} · tente novamente.");return false }
    val duration=context.resources.getInteger(if(call==LineCall.OUT) R.integer.line_voice_out_ms else R.integer.line_voice_in_ms)
    current=call;endsAt=SystemClock.elapsedRealtime()+duration
    startedCount++
    onStatus?.invoke("Reprodução ${call.name} iniciada · ${volumeDescription()}")
    return true
  }
  fun stop(){if(stream!=0) pool.stop(stream);stream=0;current=null;endsAt=0}
  fun close(){stop();closed=true;pool.release()}
}
