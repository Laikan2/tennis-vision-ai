package ai.tennisvision.camera

import android.Manifest
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Default Android entry point. The complete training loop runs in this process, offline. */
class LocalTrainingActivity: AppCompatActivity() {
  private lateinit var camera: LocalCamera
  private lateinit var store: LocalSessionStore
  private lateinit var updates: CameraUpdates
  private lateinit var court: LocalCourtView
  private lateinit var status: TextView
  private lateinit var metrics: TextView
  private lateinit var start: Button
  private var config=LocalSettings()
  private var session=LocalSession()
  private var cameraKey=""
  private var frameReady=false
  private var areaConfirmed=false
  private var mustMarkArea=false
  private var cameraRotation=-1
  private var analyzing=false
  private var permissionPending=false
  @Volatile private var foreground=false
  @Volatile private var closed=false
  private var sessionLoaded=false
  private var autoSaveError=false
  private var editorResume=false
  private var editorConfig=LocalSettings()
  private var homeSetup: HomeAreaSetup?=null
  private var uiEpoch=0
  private var lastInfo=0L
  private val main=Handler(Looper.getMainLooper())
  private val pending=AtomicReference<LocalFrame?>()
  private val scheduled=AtomicBoolean(false)
  private val disk=Executors.newSingleThreadExecutor()
  private lateinit var sound: LineVoicePlayer
  private var inVoice=false
  private var audioStatus="Voz Brian · ElevenLabs · chamadas claras · disponível sem internet"
  private val refreshOverlay=object : Runnable {
    override fun run() { if (!closed && foreground) { court.invalidate(); main.postDelayed(this,500) } }
  }
  private val screenReceiver=object : BroadcastReceiver() {
    override fun onReceive(context: Context?,intent: Intent?) {
      if (closed) return
      if (intent?.action==Intent.ACTION_SCREEN_OFF) {
        sound.stop()
        editorResume=false; if (court.editing!=0) finishEdit(false)
        pauseAnalysis(); camera.stop(); frameReady=false; uiEpoch++; pending.set(null)
        start.text="Reabrir câmera"; status.text="Tela bloqueada · treino pausado"
      } else main.postDelayed({ if (!closed && foreground && getSystemService(PowerManager::class.java).isInteractive) openCamera() },300)
    }
  }
  private val cameraPermission=registerForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
    permissionPending=false
    if (allowed) openCamera() else {
      status.text="Câmera não autorizada. Você pode permitir o acesso em Ajustes."
      start.text="Permitir câmera"; start.isEnabled=true
    }
  }
  private val exportFile=registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
    if (uri!=null) {
      val snapshot=session
      disk.execute {
        runCatching { contentResolver.openOutputStream(uri)?.use { it.write(store.json(snapshot).toByteArray()) } ?: error("Destino indisponível") }
          .onSuccess { main.post { if (!closed) Toast.makeText(this,"Sessão exportada",Toast.LENGTH_SHORT).show() } }
          .onFailure { main.post { if (!closed) showError("Não exportou: ${it.message}") } }
      }
    }
  }
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState); setContentView(R.layout.activity_local)
    ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.localRoot)) { view,insets ->
      val bars=insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
      val padding=(12*resources.displayMetrics.density).toInt()
      view.setPadding(bars.left+padding,bars.top+padding,bars.right+padding,bars.bottom+padding); insets
    }
    store=LocalSessionStore(this)
    // Session files are bounded; loading is delegated to disk, not camera startup.
    disk.execute { val saved=store.current(); main.post { if (!closed) { session=saved; sessionLoaded=true; renderStats(); store.warning?.let(::showError) } } }
    court=findViewById(R.id.localVideo); status=findViewById(R.id.localStatus); metrics=findViewById(R.id.localMetrics); start=findViewById(R.id.localStart)
    val diagnosticsPrefs=getSharedPreferences("local-diagnostics",MODE_PRIVATE)
    court.debugOverlay=diagnosticsPrefs.getBoolean("visible",false)
    court.markerDurationSeconds=diagnosticsPrefs.getInt("marker-seconds",0)
    camera=LocalCamera(this,{ key -> store.settings(key) },::receiveFrame) { message -> main.post { if (!closed && foreground) { pauseAnalysis(); frameReady=false; showError(message); start.text="Reabrir câmera"; start.isEnabled=true } } }
    updates=CameraUpdates(this)
    ContextCompat.registerReceiver(this,screenReceiver,IntentFilter().apply {
      addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT)
    },ContextCompat.RECEIVER_NOT_EXPORTED)
    volumeControlStream=AudioManager.STREAM_MUSIC
    inVoice=getSharedPreferences("standalone-settings-v1",0).getBoolean("sound-in",false)
    sound=LineVoicePlayer(this).also { it.onStatus={ message -> audioStatus=message } }
    start.setOnClickListener { toggleAnalysis() }
    findViewById<Button>(R.id.localArea).setOnClickListener { edit(1,surface=ContactSurface.FLOOR,home=true) }
    findViewById<Button>(R.id.localWall).setOnClickListener { edit(1,surface=ContactSurface.WALL,home=true) }
    findViewById<Button>(R.id.localFloor).setOnClickListener { edit(2) }
    findViewById<Button>(R.id.localWholeFloor).setOnClickListener { if(court.editing==2){court.wholeImageFloor();finishEdit(true)} }
    findViewById<Button>(R.id.localConfirmArea).setOnClickListener { confirmCurrentArea() }
    findViewById<Button>(R.id.localCourt).setOnClickListener {
      if(!frameReady) { showError("Aguarde a imagem antes de marcar a quadra."); return@setOnClickListener }
      AlertDialog.Builder(this).setTitle("Que parte da quadra aparece?")
        .setItems(arrayOf("Quadra inteira · os dois lados da rede","Meia quadra · rede até um fundo")) { _,i -> edit(3,if(i==0) CourtLayout.FULL else CourtLayout.HALF) }
        .setNegativeButton("Cancelar",null).show()
    }
    findViewById<Button>(R.id.localFraming).setOnClickListener { showFraming() }
    findViewById<Button>(R.id.localCancelArea).setOnClickListener { finishEdit(false) }
    findViewById<Button>(R.id.localRedraw).setOnClickListener { court.redraw() }
    findViewById<Button>(R.id.localSaveArea).setOnClickListener { finishEdit(true) }
    court.onDraft={ valid -> findViewById<Button>(R.id.localSaveArea).isEnabled=valid; updateEditorHint(valid) }
    court.onImpact={ id -> session.events.firstOrNull { it.id==id }?.let(::review) }
    findViewById<Button>(R.id.networkTab).setOnClickListener { showSettings() }
    findViewById<Button>(R.id.localHistory).setOnClickListener { showHistory() }
    findViewById<Button>(R.id.localReview).setOnClickListener { session.events.lastOrNull()?.let(::review) }
    onBackPressedDispatcher.addCallback(this,object : OnBackPressedCallback(true) {
      override fun handleOnBackPressed() { if (court.editing!=0) finishEdit(false) else { pauseAnalysis(); finish() } }
    })
    configureLayout(); renderStats()
  }
  private fun configureLayout() {
    val compact=resources.configuration.screenWidthDp<640
    val body=findViewById<LinearLayout>(R.id.localBody)
    body.orientation=if (compact) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
    val editing=::court.isInitialized && court.editing!=0
    findViewById<LocalVideoPanel>(R.id.localVideoPanel).apply {
      heightFraction=if(compact && !editing) .58 else 1.0
      layoutParams=LinearLayout.LayoutParams(if(compact) -1 else 0,-2,if(compact) 0f else 1f)
    }
    findViewById<View>(R.id.settingsScroll).layoutParams=LinearLayout.LayoutParams(if (compact) -1 else (286*resources.displayMetrics.density).toInt(),if (compact) 0 else -1,if (compact) 1f else 0f).apply {
      leftMargin=if (compact) 0 else (12*resources.displayMetrics.density).toInt()
    }
    findViewById<View>(R.id.settingsScroll).visibility=if(editing) View.GONE else View.VISIBLE
    findViewById<View>(R.id.localHeader).visibility=if(editing) View.GONE else View.VISIBLE
    findViewById<View>(R.id.localMetrics).visibility=if(editing) View.GONE else View.VISIBLE
  }
  @Suppress("DEPRECATION") override fun onConfigurationChanged(newConfig: Configuration) {
    super.onConfigurationChanged(newConfig); configureLayout()
    if (cameraRotation>=0 && cameraRotation!=windowManager.defaultDisplay.rotation) {
      editorResume=false; if (court.editing!=0) finishEdit(false)
      pauseAnalysis(); camera.stop(); frameReady=false
      main.postDelayed({ if (!closed && foreground) openCamera() },250)
    }
  }
  private fun allowed()=ContextCompat.checkSelfPermission(this,Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED
  private fun requestPermission() {
    if (permissionPending) return
    val prefs=getSharedPreferences("standalone-settings-v1",MODE_PRIVATE)
    if (prefs.getBoolean("asked-camera",false) && !shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
      AlertDialog.Builder(this).setTitle("Permitir câmera").setMessage("Em Permissões, permita a câmera. O treino e os dados ficam neste celular.")
        .setPositiveButton("Abrir Ajustes") { _,_ -> startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:$packageName"))) }
        .setNegativeButton("Agora não",null).show()
    } else { permissionPending=true; prefs.edit().putBoolean("asked-camera",true).apply(); cameraPermission.launch(Manifest.permission.CAMERA) }
  }
  @Suppress("DEPRECATION") private fun openCamera() {
    if (!foreground || !allowed() || camera.running) return
    frameReady=false; status.text="Abrindo câmera local…"; start.text="Aguardando imagem"; start.isEnabled=false
    areaConfirmed=false
    val rotation=when(windowManager.defaultDisplay.rotation) { Surface.ROTATION_90->90; Surface.ROTATION_180->180; Surface.ROTATION_270->270; else->0 }
    cameraRotation=windowManager.defaultDisplay.rotation
    val power=getSystemService(PowerManager::class.java).isPowerSaveMode
    val prefs=getSharedPreferences("standalone-settings-v1",0)
    val profile=if(power) LocalCaptureProfile.ECONOMY else LocalCaptureProfile.saved(prefs.getString("capture-profile",null),prefs.getBoolean("economy",false))
    camera.start(rotation,profile=profile,lensToken=prefs.getString("local-lens",null))
  }
  private fun toggleAnalysis() {
    if (!allowed()) { requestPermission(); return }
    if (!camera.running) { openCamera(); return }
    if (!frameReady || court.editing!=0 || !sessionLoaded) return
    if (analyzing) { pauseAnalysis(); return }
    if(config.surface!=ContactSurface.TRACK && LocalGeometry.projectedPolygon(config.polygon) && !areaConfirmed) {
      val mode=if(config.court==CourtLayout.CUSTOM) 1 else 3
      if(mustMarkArea) {
        AlertDialog.Builder(this).setTitle("Câmera reposicionada")
          .setMessage("A área anterior ficou sem referência. Ajuste os quatro cantos sobre as linhas antes de voltar a contar IN/OUT.")
          .setPositiveButton("Ajustar cantos") { _,_->edit(mode,config.court) }.setNegativeButton("Cancelar",null).show()
      } else AlertDialog.Builder(this).setTitle("Confira a área salva na imagem")
        .setMessage("Os quatro cantos ainda coincidem com o chão? A mesma lente não garante a mesma posição do celular.")
        .setPositiveButton("Coincidem · iniciar") { _,_->areaConfirmed=true;toggleAnalysis() }
        .setNeutralButton("Ajustar cantos") { _,_->edit(mode,config.court) }.setNegativeButton("Cancelar",null).show()
      return
    }
    if (autoSaveError) { showError("Não é seguro continuar sem salvar. Libere espaço e reabra o app."); return }
    if (session.events.size>=2000) { showError("Esta sessão chegou a 2.000 contatos. Comece uma nova em Ajustes."); return }
    analyzing=true; camera.reset(); camera.analyze=true; start.text="Pausar treino"
    status.setTextColor(ContextCompat.getColor(this,R.color.tv_text))
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    updates.activityState(false)
    status.text=if (config.surface==ContactSurface.TRACK) "Rastreamento ativo · sem contar impactos" else "Treino ativo · aguardando bola"
  }
  private fun pauseAnalysis() {
    analyzing=false; camera.analyze=false; camera.reset(); court.clearTracking()
    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    if (frameReady) { start.text="Iniciar treino"; status.text="Treino pausado · dados salvos no celular" }
    updates.activityState(court.editing==0)
  }

  /** Shared presentation path: live camera and decoded local video fixtures use the same result. */
  internal fun receiveFrame(frame: LocalFrame) {
    if (!foreground || closed) return
    val token=uiEpoch
    // Events must never be dropped along with superseded preview frames.
    if (frame.result.impact!=null) main.post { if (!closed && token==uiEpoch && analyzing && court.editing==0) acceptImpact(frame) }
    pending.set(frame)
    if (scheduled.compareAndSet(false,true)) main.post {
      scheduled.set(false)
      val latest=pending.getAndSet(null)
      if (!closed && foreground && token==uiEpoch && latest!=null) present(latest)
    }
  }
  private fun present(frame: LocalFrame) {
    if (cameraKey!=frame.key) {
      cameraKey=frame.key; config=store.settings(cameraKey); court.settings=config
      areaConfirmed=false;mustMarkArea=false
      // Areas belong to the camera format/orientation, never stretched onto a different view.
      court.resetReference()
    }
    val first=!frameReady
    frameReady=true
    if (first) { start.isEnabled=true; start.text="Iniciar treino"; status.text="Câmera pronta · confira a área antes de iniciar"; status.setTextColor(ContextCompat.getColor(this,R.color.tv_text)); renderAreaSummary() }
    val now=SystemClock.elapsedRealtime()
    court.present(frame)
    val currentResult=frame.result.referencedTo(config)
    findViewById<View>(R.id.localConfirmArea).visibility=if(currentResult.areaNeedsConfirmation && court.editing==0) View.VISIBLE else View.GONE
    if (now-lastInfo>600) {
      lastInfo=now
      metrics.text=String.format(Locale.getDefault(),"%d × %d · %.0f FPS recebidos · até %d solicitados\nConversão %.1f ms · detecção %.1f ms\n%s · sem computador",frame.width,frame.height,frame.captureFps,frame.requestedFps,frame.conversionMs,frame.detectorMs,frame.lensLabel)
      val ageMs=if(frame.capturedAtRealtime) (SystemClock.elapsedRealtimeNanos()/1e9-frame.timestamp)*1000 else null
      val diagnostics=mutableListOf<String>()
      frame.exposureMs?.let { diagnostics.add(String.format(Locale.getDefault(),"Exposição recente %.1f ms",it)) }
      ageMs?.takeIf { it in 0.0..10000.0 }?.let { diagnostics.add("captura → UI ≈${it.toInt()} ms") }
      if(diagnostics.isNotEmpty()) metrics.append("\n"+diagnostics.joinToString(" · "))
      if(analyzing)metrics.append("\n"+String.format(Locale.getDefault(),"%.0f FPS analisados",frame.processedFps))
      if(court.debugOverlay)metrics.append("\n"+currentResult.diagnostics.summary+"\nEscore visual = suporte da silhueta; não é probabilidade calibrada")
      frame.result.observed?.let { ball ->
        metrics.append("\nBola ≈${(ball.radius*2).toInt()} px · "+if(ball.resolved) "observada" else "detalhe insuficiente para decisão automática")
      }
      if (analyzing && court.editing==0) {
        status.text=if(currentResult.movingCamera && !currentResult.areaNeedsConfirmation) "IMPACTOS SUSPENSOS · estabilize o celular" else currentResult.message
        status.setTextColor(ContextCompat.getColor(this,if(currentResult.movingCamera || currentResult.areaNeedsConfirmation) R.color.tv_warning else R.color.tv_text))
      }
    }
    if (currentResult.areaNeedsConfirmation && analyzing) {
      areaConfirmed=false;mustMarkArea=true
      status.text="IMPACTOS SUSPENSOS · fixe o celular e confira os cantos em Marcar área"
      status.setTextColor(ContextCompat.getColor(this,R.color.tv_warning))
    }
  }
  private fun acceptImpact(frame: LocalFrame) {
    if(frame.key!=cameraKey) return
    val detected=frame.result.referencedTo(config).impact ?: return
    if (session.events.any { kotlin.math.abs(it.time-detected.time)<.01 }) return
    val event=detected.copy(id=(session.events.maxOfOrNull { it.id } ?: 0L)+1,evidenceOffset=frame.contactOffset ?: detected.evidenceOffset)
    session=session.copy(events=session.events+event)
    court.events=(court.events+event).takeLast(24); renderStats(); saveSession(frame,event)
    findViewById<TextView>(R.id.localDecision).apply {
      val speed=event.speedKmh?.let { "≈"+String.format(Locale.getDefault(),if(it<10) "%.1f" else "%.0f",it)+" km/h lateral" } ?: "Velocidade indisponível"
      text="${if(event.surface==ContactSurface.WALL) "Parede" else "Chão"} · marca #${event.id} · ${court.callLabel(event.decision)}\n$speed"
      setTextColor(court.callColor(event.decision))
    }
    if (LineVoicePolicy.enabled(event.decision,config.sound,inVoice)) sound.play(event.decision)
    if (session.events.size>=2000) pauseAnalysis()
  }
  private fun saveSession(frame: LocalFrame?=null,event: LocalImpact?=null) {
    val snapshot=session
    disk.execute {
      runCatching {
        if (frame?.contactPixels!=null && event!=null) {
          val folder=File(filesDir,"local-evidence/${snapshot.id}").apply { mkdirs() }
          val bitmap=Bitmap.createBitmap(frame.contactPixels,frame.width,frame.height,Bitmap.Config.ARGB_8888)
          try { File(folder,"${event.id}.jpg").outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG,85,it) } } finally { bitmap.recycle() }
        }
        store.save(snapshot)
      }.onFailure { error -> main.post { if (!closed) { autoSaveError=true; pauseAnalysis(); showError("Não salvou o treino: ${error.message}. Os dados atuais continuam na tela.") } } }
    }
  }
  private fun renderStats() {
    val valid=session.events.filter { it.decision!=LineCall.REJECTED }
    val inside=valid.count { it.decision==LineCall.IN }; val outside=valid.count { it.decision==LineCall.OUT }
    val review=valid.size-inside-outside
    val percent=if (inside+outside>0) "${inside*100/(inside+outside)}% IN" else "Acerto —"
    findViewById<TextView>(R.id.localStats).text="$inside IN · $outside OUT · $review para revisar\n$percent · ${valid.size} contatos estimados"
    findViewById<Button>(R.id.localReview).isEnabled=session.events.isNotEmpty()
  }
  private fun edit(mode: Int,layout: CourtLayout=CourtLayout.CUSTOM,surface: ContactSurface?=null,home: Boolean=false) {
    if (!frameReady) { showError("Abra a câmera e aguarde uma imagem antes de marcar a área."); return }
    if (court.editing!=0) return
    val target=surface ?: if(mode==2 || layout!=CourtLayout.CUSTOM) ContactSurface.FLOOR else config.surface
    val reference=court.visibleSettings.selectingSurface(target).copy(court=layout)
    homeSetup=if(home) HomeAreaSetup(reference,target) else null
    editorConfig=homeSetup?.settings ?: reference
    editorResume=analyzing; pauseAnalysis(); uiEpoch++; pending.set(null)
    court.settings=editorConfig;court.editorLayout=layout;court.begin(mode,editorConfig); start.isEnabled=false
    findViewById<View>(R.id.localEditor).visibility=View.VISIBLE
    updateEditorHint(if(mode==2) LocalGeometry.validFloorLine(court.draft) else LocalGeometry.valid(court.draft))
    configureLayout()
    status.text=if(mode==3) "Quadra · ${court.draft.size}/4 cantos · referência pausada" else "Editando · imagem de referência pausada"
    updates.activityState(false)
  }
  private fun updateEditorHint(valid: Boolean) {
    if(!::court.isInitialized || court.editing==0) return
    findViewById<View>(R.id.localConfirmArea).visibility=View.GONE
    findViewById<View>(R.id.localWholeFloor).visibility=if(court.editing==2) View.VISIBLE else View.GONE
    findViewById<TextView>(R.id.localSurface).text="EDITANDO ${if(editorConfig.surface==ContactSurface.WALL) "PAREDE" else "CHÃO"} · APLICAR AO CONCLUIR"
    findViewById<Button>(R.id.localSaveArea).text=if(homeSetup?.surface==ContactSurface.FLOOR && homeSetup?.stage==HomeAreaSetup.Stage.AREA) "Próximo: limite do piso" else "Salvar área"
    val hint=when {
      homeSetup?.surface==ContactSurface.FLOOR && court.editing==1 -> "Passo 1/2 · arraste sobre a área do chão e ajuste os quatro cantos. Depois indique onde o piso começa."
      court.editing==2 -> "Passo 2/2 · "+floorBoundaryHint(court.draft)
      homeSetup?.surface==ContactSurface.WALL -> "Parede · arraste sobre a área de contato e ajuste os quatro cantos. A marcação do chão será mantida."
      else -> when(court.editing) {
      3 -> if(court.draft.size<4) {
        val names=if(court.editorLayout==CourtLayout.HALF) arrayOf("rede, esquerda","rede, direita","fundo, direita","fundo, esquerda")
          else arrayOf("um fundo, esquerda","o mesmo fundo, direita","outro fundo, direita","outro fundo, esquerda")
        "Toque no canto ${court.draft.size+1}/4: ${names[court.draft.size]}. Os quatro cantos devem aparecer."
      } else if(valid) "Arraste cada canto sobre a linha. A lupa ajuda no ajuste. Salve para voltar ao vídeo ao vivo."
        else "Os cantos se cruzam ou ficaram alinhados. Ajuste os quatro pontos ao redor da quadra."
      1 -> "Arraste para abrir um retângulo, depois ajuste os quatro cantos. A área válida não limita a busca da bola."
      else -> "Somente para quarto: toque em dois pontos entre parede e piso. O chão fica abaixo da linha. Para tênis, use Marcar quadra."
      }
    }
    findViewById<TextView>(R.id.localEditorHint).text=hint
    status.text=if(court.editing==3) "Quadra · ${court.draft.size}/4 cantos · referência pausada" else "Editando · imagem de referência pausada"
  }
  private fun finishEdit(save: Boolean) {
    if (court.editing==0) return
    if (save) {
      if (court.editing!=2 && !LocalGeometry.valid(court.draft) || court.editing==2 && !LocalGeometry.validFloorLine(court.draft)) { showError("A marcação precisa de pontos válidos. Ajuste antes de salvar."); return }
      val home=homeSetup
      if(home!=null) {
        if(!home.accept(court.draft)) return
        if(home.stage==HomeAreaSetup.Stage.FLOOR_BOUNDARY) {
          editorConfig=home.settings;court.settings=editorConfig;court.begin(2,editorConfig)
          updateEditorHint(LocalGeometry.validFloorLine(court.draft));return
        }
        config=home.completedSettings ?: return
      }else {
        config=(if(court.editing!=2) editorConfig.copy(polygon=court.draft.toList()) else editorConfig.copy(floorLine=court.draft.toList())).rememberingArea()
      }
      if(court.editing!=2 || home!=null) { areaConfirmed=true;mustMarkArea=false }
      store.saveSettings(cameraKey,config); court.settings=config; camera.update(config)
      court.resetReference();uiEpoch++;pending.set(null)
    }
    homeSetup=null;court.finish();court.settings=config; findViewById<View>(R.id.localEditor).visibility=View.GONE; start.isEnabled=true
    configureLayout();renderAreaSummary()
    status.text=if (save) "${surfaceName()} marcado para esta sessão" else "Edição cancelada · modo ${surfaceName().lowercase()} mantido"
    updates.activityState(true)
    val resume=editorResume; editorResume=false
    if (resume && foreground) toggleAnalysis()
  }
  private fun renderAreaSummary() {
    findViewById<TextView>(R.id.localSurface).text=when(config.surface) {
      ContactSurface.FLOOR->"CHÃO · QUICADAS";ContactSurface.WALL->"PAREDE · CONTATOS PARA REVISÃO";ContactSurface.TRACK->"RASTREAMENTO · SEM IMPACTOS"
    }
    findViewById<TextView>(R.id.localAreaSummary).text=if(!LocalGeometry.projectedPolygon(config.polygon)) "Sem área nesta lente e orientação · marque os quatro cantos."
      else if(config.surface==ContactSurface.WALL) "Parede desta sessão · a área do chão permanece separada."
      else when(config.court) { CourtLayout.FULL->"Quadra inteira · dois lados. IN/OUT usa o contorno marcado; saque não é classificado por caixa de serviço.";CourtLayout.HALF->"Meia quadra · rede até o fundo. Confira os cantos se reposicionar o celular.";else->"Chão desta sessão · a área da parede permanece separada." }
    val hint=when {
      config.surface==ContactSurface.WALL -> "Parede: retorno gera candidato para revisão; uma câmera não comprova o contato em profundidade."
      config.surface==ContactSurface.TRACK -> "Somente rastrear: impactos e IN/OUT desligados."
      config.court==CourtLayout.CUSTOM && !LocalGeometry.validFloorLine(config.floorLine) -> "Chão: toque em Marcar chão e conclua os dois passos, área e limite do piso. Sem a referência, o contato exige revisão."
      else -> "Chão: aguarda descida e retorno no piso; perto da linha pode exigir revisão."
    }
    findViewById<TextView>(R.id.localAreaSummary).append("\n"+hint+"\nÁreas temporárias · limpas ao fechar o app ou iniciar nova sessão.")
  }
  private fun confirmCurrentArea() {
    if(!frameReady || court.editing!=0 || !mustMarkArea)return
    val resume=analyzing;val reference=court.visibleSettings
    pauseAnalysis();config=reference;areaConfirmed=true;mustMarkArea=false
    store.saveSettings(cameraKey,config);court.settings=config;court.resetReference();camera.update(config)
    uiEpoch++;pending.set(null);findViewById<View>(R.id.localConfirmArea).visibility=View.GONE
    renderAreaSummary();status.text="Área conferida · impactos liberados nesta posição"
    if(resume && foreground)toggleAnalysis()
  }
  private fun surfaceName()=when(config.surface) { ContactSurface.FLOOR->"Chão";ContactSurface.WALL->"Parede";ContactSurface.TRACK->"Somente rastrear" }
  internal fun beginNewSession() {
    pauseAnalysis();saveSession();store.clearAreas();config=store.settings(cameraKey)
    areaConfirmed=false;mustMarkArea=false;uiEpoch++;pending.set(null)
    court.settings=config;court.resetReference();camera.update(config)
    session=LocalSession();renderStats();renderAreaSummary();saveSession()
    status.text="Nova sessão · marque o chão ou a parede; áreas anteriores descartadas"
  }
  private fun showSettings() {
    if (court.editing!=0) { showError("Salve ou cancele a área antes de abrir Ajustes."); return }
    pauseAnalysis()
    val choices=arrayOf("Superfície: ${when(config.surface) { ContactSurface.FLOOR->"chão"; ContactSurface.WALL->"parede (revisão)"; else->"somente rastrear" }}",
      "Vozes IN / OUT · ElevenLabs","Marcas na imagem: ${if (config.marks) "visíveis" else "ocultas"}",
      "Perfil de captura / bateria","Começar nova sessão","Exportar sessão (JSON)","Transmitir para PC (opcional)","Como usar sem computador",
      "Diagnóstico ao vivo: ${if(court.debugOverlay) "ligado" else "desligado"}","Duração das marcas: ${if(court.markerDurationSeconds==0) "sessão" else "${court.markerDurationSeconds} s"}")
    AlertDialog.Builder(this).setTitle("Seu treino local").setItems(choices) { _,which -> when(which) {
      0 -> AlertDialog.Builder(this).setTitle("Superfície observada").setItems(arrayOf("Chão · quicadas","Parede · candidatos para revisão","Somente rastrear · sem impactos")) { _,i ->
        config=court.visibleSettings.selectingSurface(ContactSurface.entries[i]);areaConfirmed=false;court.resetReference();persistConfig()
      }.show()
      1 -> showVoiceSettings()
      2 -> { config=config.copy(marks=!config.marks); persistConfig() }
      3 -> showCaptureProfiles()
      4 -> AlertDialog.Builder(this).setTitle("Nova sessão?").setMessage("A sessão atual continuará no histórico. As áreas de todas as lentes serão limpas.")
        .setPositiveButton("Começar") { _,_ -> beginNewSession() }.setNegativeButton("Cancelar",null).show()
      5 -> exportFile.launch("Tennis-Vision-${session.id}.json")
      6 -> { camera.stop(); startActivity(Intent(this,MainActivity::class.java)) }
      8 -> {court.debugOverlay=!court.debugOverlay;getSharedPreferences("local-diagnostics",MODE_PRIVATE).edit().putBoolean("visible",court.debugOverlay).apply();court.invalidate()}
      9 -> AlertDialog.Builder(this).setTitle("Duração das marcas na imagem").setItems(arrayOf("5 segundos","15 segundos","30 segundos","Toda a sessão")) {_,i->
        court.markerDurationSeconds=listOf(5,15,30,0)[i]
        getSharedPreferences("local-diagnostics",MODE_PRIVATE).edit().putInt("marker-seconds",court.markerDurationSeconds).apply();court.invalidate()
      }.setMessage(null).show()
      7 -> AlertDialog.Builder(this).setTitle("Treino sem PC").setMessage("QUADRA\n1. Celular fixo e na horizontal, com os quatro cantos e margem para bolas fora.\n2. Ajuste lente e detalhe em Enquadramento.\n3. Toque em Marcar quadra: inteira ou metade. Toque nos quatro cantos ao redor do piso; ajuste com a lupa e salve.\n4. Confira a área e inicie.\n\nQUARTO\nUse Marcar chão: arraste a área, avance e indique o limite parede/piso. Use Marcar parede para a área de contato na parede. As duas marcações ficam separadas somente nesta sessão; nova sessão ou fechar o app limpa as áreas. Troque a superfície em Ajustes. Candidatos de parede exigem revisão.\n\nA busca cobre a imagem inteira. Bola minúscula, borrão, mão, perda de quadros e contato incerto exigem revisão. O contorno da área não prova sozinho que uma bola tocou o chão. IN/OUT refere-se à área marcada, sem regras automáticas de saque.\n\nPequenas translações com fundo texturizado são compensadas durante o treino. Reposicionar ou girar o celular exige conferir e remarcar. Uma lente ampla mostra mais, mas deixa a bola menor. Velocidade lateral estimada pela bola de 67 mm; não mede um saque em 3D.\n\nTela bloqueada ou outro app pausa e salva. Internet só é usada nas atualizações opcionais.")
        .setPositiveButton("Entendi",null).show()
    } }.setNegativeButton("Fechar",null).show()
  }
  private fun persistConfig() { store.saveSettings(cameraKey,config); court.settings=config; court.invalidate(); camera.update(config); renderAreaSummary();status.text="Ajuste salvo" }
  private fun showVoiceSettings() {
    AlertDialog.Builder(this).setTitle("Vozes IN / OUT · ElevenLabs")
      .setMultiChoiceItems(arrayOf("Som OUT","Som IN · opcional"),booleanArrayOf(config.sound,inVoice)) { _,which,checked ->
        if(which==0) { config=config.copy(sound=checked);persistConfig() }
        else { inVoice=checked;getSharedPreferences("standalone-settings-v1",0).edit().putBoolean("sound-in",checked).apply() }
      }
      .setPositiveButton("Concluir",null).setNeutralButton("Testar vozes") { _,_ -> showVoiceTests() }.show()
  }
  private fun showVoiceTests() {
    AlertDialog.Builder(this).setTitle("Testar vozes")
      .setMessage("$audioStatus\n${sound.volumeDescription()}\n\nVoz Brian, chamadas claras, salva no app. Confira também a saída Bluetooth. Os testes não contam impactos.")
      .setPositiveButton("Testar OUT") { _,_ -> sound.play(LineCall.OUT,true);Toast.makeText(this,audioStatus,Toast.LENGTH_LONG).show() }
      .setNeutralButton("Testar IN") { _,_ -> sound.play(LineCall.IN,true);Toast.makeText(this,audioStatus,Toast.LENGTH_LONG).show() }
      .setNegativeButton("Fechar",null).show()
  }
  private fun showCaptureProfiles() {
    AlertDialog.Builder(this).setTitle("Detalhe ou fluidez").setItems(arrayOf("Fluidez · até 720p\nPrioriza quadros recebidos", "Bola distante · até 1080p\nMais pixels; pode reduzir os FPS", "Economia · até 720p / 30 FPS\nMenor consumo de bateria")) { _,i ->
      getSharedPreferences("standalone-settings-v1",0).edit().putString("capture-profile",LocalCaptureProfile.entries[i].name).apply();reopenProfile()
    }.setNeutralButton("Sobre FPS") { _,_ -> AlertDialog.Builder(this).setTitle("Taxa realmente recebida").setMessage("O treino mostra FPS recebidos, resolução e tempos de conversão e detecção. Modos de gravação a 120/240 FPS não garantem análise local nessa taxa. Cada lente, resolução e orientação guarda sua própria área; marque de novo ao trocar o perfil.").setPositiveButton("Entendi",null).show() }
      .setNegativeButton("Cancelar",null).show()
  }
  private fun showFraming() {
    if(court.editing!=0) return
    pauseAnalysis()
    AlertDialog.Builder(this).setTitle("Enquadramento da quadra").setItems(arrayOf("Detalhe e FPS","Escolher lente traseira","Como pegar os dois lados","Melhorar bola distante")) { _,i -> when(i) {
      0 -> showCaptureProfiles()
      1 -> {
        val lenses=runCatching { camera.lenses() }.getOrDefault(emptyList())
        if(lenses.isEmpty()) showError("As lentes não estão disponíveis. Permita a câmera e tente novamente.")
        else AlertDialog.Builder(this).setTitle("Lente · escolha antes de marcar").setItems(lenses.map { it.label }.toTypedArray()) { _,n ->
          getSharedPreferences("standalone-settings-v1",0).edit().putString("local-lens",lenses[n].token).apply();reopenProfile()
        }.setNegativeButton("Cancelar",null).show()
      }
      2 -> AlertDialog.Builder(this).setTitle("Os quatro cantos na imagem").setMessage("Use a horizontal e um suporte fixo, recuado e elevado, de fora da área de jogo. Confira os dois fundos, a rede e margem além das linhas.\n\nSe não couber, mude a posição ou experimente a lente Ampla, quando disponível. A lente ampla diminui a bola na imagem; para leitura melhor, prefira filmar meia quadra com mais detalhe. Um celular não garante a leitura de toda a quadra nem de saques a 200 km/h.\n\nTeste 1080p em boa luz, olhando os FPS recebidos. Se a imagem engasgar, use Fluidez. Depois de trocar a lente ou mover o suporte, marque e confira os cantos novamente.").setPositiveButton("Entendi",null).show()
      3 -> AlertDialog.Builder(this).setTitle("Mais detalhe na bola")
        .setMessage("Use 1080p e confira o tamanho da bola em pixels e os FPS recebidos. Em Escolher lente, uma lente de Detalhe mostra um campo menor e pode deixar a bola maior; mantenha o chão e margem para OUT na imagem.\n\nPrefira meia quadra, boa luz e suporte fixo. O contorno ampliado só ajuda a enxergar a marca; não acrescenta detalhe à câmera. Um sinal pequeno ou fraco aparece em amarelo e exige revisão do contato.\n\nAo trocar lente ou resolução, confira a área correspondente antes de iniciar.")
        .setPositiveButton("Usar 1080p") { _,_->
          getSharedPreferences("standalone-settings-v1",0).edit().putString("capture-profile",LocalCaptureProfile.DETAIL.name).apply();reopenProfile()
        }.setNegativeButton("Fechar",null).show()
    } }.setNegativeButton("Fechar",null).show()
  }
  private fun reopenProfile() { pauseAnalysis();uiEpoch++;pending.set(null);camera.stop(); frameReady=false; main.postDelayed({ if (foreground) openCamera() },250) }
  private fun showHistory() {
    pauseAnalysis()
    disk.execute { val sessions=store.history(); main.post {
      if (!closed && foreground) AlertDialog.Builder(this).setTitle("Sessões neste celular")
        .setItems(sessions.map { java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT,java.text.DateFormat.SHORT).format(java.util.Date(it.started))+" · ${it.events.size} registros" }.toTypedArray()) { _,index ->
          val old=sessions[index]
          AlertDialog.Builder(this).setTitle("Sessão salva").setMessage("${old.events.count { it.decision==LineCall.IN }} IN · ${old.events.count { it.decision==LineCall.OUT }} OUT\n${old.events.count { it.decision in listOf(LineCall.REVIEW,LineCall.UNMARKED) }} para revisar\nIdentificador: ${old.id}")
            .setPositiveButton("Fechar",null).show()
        }.setNegativeButton("Fechar",null).show()
    } }
  }
  private fun review(event: LocalImpact) {
    pauseAnalysis()
    val column=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(20,8,20,8) }
    val text=TextView(this).apply { text="${court.callLabel(event.decision)} · ${event.reason}\n${event.speedKmh?.let { "≈${it.toInt()} km/h lateral" } ?: "Velocidade indisponível"}"; setTextColor(ContextCompat.getColor(context,R.color.tv_text)) }
    column.addView(text)
    val image=ImageView(this).apply { adjustViewBounds=true; maxHeight=(190*resources.displayMetrics.density).toInt(); contentDescription="Quadro observado mais próximo do contato estimado" }
    column.addView(image)
    val evidence=File(filesDir,"local-evidence/${session.id}/${event.id}.jpg")
    val dialog=AlertDialog.Builder(this).setTitle("Revisar contato #${event.id}").setView(column)
      .setPositiveButton("Confirmar IN") { _,_ -> revise(event.id,LineCall.IN) }
      .setNegativeButton("Confirmar OUT") { _,_ -> revise(event.id,LineCall.OUT) }
      .setNeutralButton("Não foi impacto") { _,_ -> revise(event.id,LineCall.REJECTED) }.create()
    var thumbnail: Bitmap?=null
    disk.execute {
      val bitmap=BitmapFactory.decodeFile(evidence.path,BitmapFactory.Options().apply { inSampleSize=2; inMutable=true })
      bitmap?.let { image ->
        val paint=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color=court.callColor(event.decision); style=android.graphics.Paint.Style.STROKE; strokeWidth=3f }
        val point=LocalGeometry.impactPoint(event,event.evidenceOffset)
        android.graphics.Canvas(image).drawCircle((point.x*image.width).toFloat(),(point.y*image.height).toFloat(),12f,paint)
      }
      main.post {
      if (dialog.isShowing) { thumbnail=bitmap; image.setImageBitmap(bitmap); if (bitmap==null) text.append("\nQuadro de evidência indisponível.") } else bitmap?.recycle()
      }
    }
    dialog.setOnDismissListener { image.setImageDrawable(null); thumbnail?.recycle() }; dialog.show()
  }
  private fun revise(id: Long,call: LineCall) {
    session=session.copy(events=session.events.map { if (it.id==id) it.copy(reviewed=call) else it })
    // Editing history must not restore marks from a previous lens/area reference.
    court.events=court.events.mapNotNull { live -> session.events.firstOrNull { it.id==live.id } }
    saveSession();renderStats();court.invalidate()
  }
  private fun showError(message: String) { status.text=message; status.setTextColor(ContextCompat.getColor(this,R.color.tv_error)) }
  internal fun testSession()=session
  internal fun testSoundCount()=sound.startedCount
  internal fun testConfig()=config
  override fun onResume() { super.onResume(); foreground=true; main.removeCallbacks(refreshOverlay); main.post(refreshOverlay); if (::updates.isInitialized) updates.resume(true); if (allowed()) openCamera() }
  override fun onPause() {
    foreground=false; uiEpoch++; pending.set(null); main.removeCallbacks(refreshOverlay)
    if (::camera.isInitialized) { editorResume=false; if (court.editing!=0) finishEdit(false); pauseAnalysis(); camera.stop(); frameReady=false; start.isEnabled=true; start.text="Reabrir câmera" }
    if (::updates.isInitialized) updates.pause()
    sound.stop(); super.onPause()
  }
  override fun onDestroy() { closed=true; unregisterReceiver(screenReceiver); camera.close(); updates.close(); sound.close(); main.removeCallbacksAndMessages(null); disk.shutdown(); super.onDestroy() }
}
