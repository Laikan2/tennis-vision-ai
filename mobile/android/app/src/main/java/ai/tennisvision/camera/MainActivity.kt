package ai.tennisvision.camera

import android.Manifest
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.IntentFilter
import android.graphics.Rect
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Network
import android.net.Uri
import android.provider.Settings
import android.net.wifi.WifiManager
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.os.Build
import android.os.BatteryManager
import android.os.PowerManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.AdapterView
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.SeekBar
import android.widget.ScrollView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.pedro.common.ConnectChecker
import com.pedro.common.StreamingStatsReport
import com.pedro.encoder.input.video.CameraCallbacks
import com.pedro.encoder.input.video.CameraHelper
import com.pedro.library.util.QueueAwareBitrateAdapter
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity(), ConnectChecker, SurfaceHolder.Callback {
  private lateinit var updates: CameraUpdates
  private lateinit var camera: NativeCameraStream
  private lateinit var preview: SurfaceView
  private lateinit var statusText: TextView
  private lateinit var metricsText: TextView
  private lateinit var detailText: TextView
  private lateinit var captureText: TextView
  private lateinit var hostInput: EditText
  private lateinit var portInput: EditText
  private lateinit var pathInput: EditText
  private lateinit var qualitySpinner: Spinner
  private lateinit var connectButton: Button
  private lateinit var scanButton: Button
  private lateinit var fastShutter: CheckBox
  private lateinit var focusLock: CheckBox
  private lateinit var torch: CheckBox
  private lateinit var zoomSlider: SeekBar
  private lateinit var shutterSpinner: Spinner
  private lateinit var transportSpinner: Spinner
  private lateinit var sensorText: TextView
  private lateinit var deviceText: TextView
  private lateinit var networkText: TextView
  private var fullScreen = false
  private var focusTouch = false
  private var selectedTab = 0
  private var startAfterPermission = false
  private var cameraPermissionInFlight = false
  private var networkPermissionInFlight = false
  private var connected = false
  private var streamStartedMs = 0L
  @Volatile private var lastCaptureMs = 0L
  private var warnedStall = false
  private val firstFrameQueued = AtomicBoolean(false)
  private var closingCamera = false
  private var captureRestartUsed = false
  private val closeWorker = Executors.newSingleThreadExecutor()
  private var wifiLock: WifiManager.WifiLock? = null
  private val networkCallback = object : ConnectivityManager.NetworkCallback() {
    override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = onMain {
      if (::updates.isInitialized) updates.networkChanged()
    }
    override fun onLost(network: Network) = onMain {
      if (::updates.isInitialized) updates.networkChanged()
      val manager = getSystemService(ConnectivityManager::class.java)
      if (requestedStream && connected && manager.activeNetwork == null) onConnectionFailed("A rede foi desconectada.")
    }
  }
  private var zoomRatio = 1f
  private var batteryPercent = -1
  private var batteryCelsius = 0f
  private var recoveries = 0
  private var lastDropped = 0L
  private var lastRecoveryAt = 0L
  private val shutters = listOf(1_000_000L, 500_000L, 250_000L)
  private val batteryReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
      val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
      val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
      batteryPercent = if (level >= 0 && scale > 0) level * 100 / scale else -1
      batteryCelsius = (intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f
    }
  }
  private val telemetry = object : Runnable {
    override fun run() {
      if (destroyed) return
      if (resumed) renderDevice()
      mainHandler.postDelayed(this, 1000)
    }
  }
  private var capabilities: CameraCapabilities? = null
  private var profiles = StreamProfile.stableProfiles
  private var configuredProfile: StreamProfile? = null
  private var adapter: QueueAwareBitrateAdapter? = null
  private var pendingConfig: PairingConfig? = null
  private var requestedStream = false
  @Volatile private var cameraReady = false
  @Volatile private var openingCamera = false
  private var profilePrepared = false
  private var resumed = false
  private var scanning = false
  private var scanAfterPermission = false
  private var destroyed = false
  private var surfaceReady = false
  @Volatile private var generation = 0
  private var actualFps = 0.0
  private var captureFps = 0.0
  private var sentFps = 0.0
  private var lastStatsNs = 0L
  private var lastSentFrames = 0L
  private var actualBitrate = 0L
  private var droppedFrames = 0L
  private var queueBytes = 0L
  private val mainHandler = Handler(Looper.getMainLooper())
  @Volatile private var exposureNs = 0L
  @Volatile private var sensorIso = 0
  @Volatile private var focusDistance = 0f

  private val cameraPermission = registerForActivityResult(
    ActivityResultContracts.RequestPermission(),
  ) { granted ->
    cameraPermissionInFlight = false
    if (granted) {
      val continueStream = startAfterPermission
      refreshProfiles()
      if (scanAfterPermission) { scanAfterPermission = false; scanQr() }
      else if (continueStream) requestStart() else ensurePreview()
    } else {
      scanAfterPermission = false
      startAfterPermission = false
      getSharedPreferences("capture", MODE_PRIVATE).edit().putBoolean("cameraDenied", true).apply()
      findViewById<Button>(R.id.permissionButton).setText(R.string.open_settings)
      setStatus("Permita a câmera nas configurações do app para capturar o treino.", true)
    }
    updateControls()
  }
  private val networkPermission = registerForActivityResult(
    ActivityResultContracts.RequestPermission(),
  ) { granted ->
    networkPermissionInFlight = false
    if (granted && startAfterPermission) requestStart() else {
      startAfterPermission = false
      if (!granted) setStatus("Permita acesso à rede local para conectar ao PC.", true)
    }
    updateControls()
  }
  private val qrScanner = registerForActivityResult(ScanContract()) { result ->
    scanning = false
    if (!destroyed) {
      if (result.contents != null) applyPairing(result.contents)
      else setStatus("LEITURA CANCELADA · TOQUE PARA TENTAR NOVAMENTE")
      updateControls()
      ensurePreview()
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_main)
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    WindowCompat.setDecorFitsSystemWindows(window, false)
    WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightNavigationBars = false
    ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.cameraRoot)) { view, insets ->
      val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
      view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
      insets
    }
    bindViews()
    configureInterface()
    selectedTab = savedInstanceState?.getInt("tab", 0) ?: 0
    selectTab(selectedTab)
    fullScreen = savedInstanceState?.getBoolean("fullscreen", false) ?: false
    findViewById<Button>(R.id.fullScreenButton).setText(if (fullScreen) R.string.exit_fullscreen else R.string.fullscreen)
    configureLayout()
    capabilities = runCatching { CameraCapabilities.inspect(this) }.getOrNull()
    profiles = capabilities?.profiles ?: profiles
    qualitySpinner.adapter = ArrayAdapter(this, R.layout.spinner_item, profiles.map { it.label })
      .also { it.setDropDownViewResource(R.layout.spinner_item) }
    findViewById<TextView>(R.id.capabilitiesText).text = capabilities?.describe()
      ?: "Não foi possível consultar os perfis. Teste 1080p30 se o modo 60 fps falhar."
    camera = NativeCameraStream(this, preview, this).also { stream ->
      stream.getStreamClient().apply {
        setReTries(10)
        setOnlyVideo(true)
        setLatency(40)
        setSocketTimeout(3_000)
        setLogs(false)
      }
      stream.setFpsListener { fps ->
        val token = generation
        onMain { if (token == generation && requestedStream) { actualFps = fps; renderMetrics() } }
      }
      stream.setCaptureFpsListener { fps ->
        val token = generation
        onMain { if (token == generation && resumed && camera.isOnPreview) { captureFps = fps; renderMetrics() } }
      }
      stream.setCameraCallbacks(object : CameraCallbacks {
        override fun onCameraChanged(facing: CameraHelper.Facing) = Unit
        override fun onCameraOpened() = Unit
        override fun onCameraDisconnected() = onMain {
          if (resumed && !scanning && camera.isOnPreview) failCamera("A câmera foi desconectada. Abra o app novamente.")
        }
        override fun onCameraError(error: String) {
          val token = generation
          onMain { if (token == generation && resumed && !scanning && !closingCamera) failCamera("Câmera: $error") }
        }
      })
      stream.setCustomOnCaptureCompletedCallback { result ->
        lastCaptureMs = SystemClock.elapsedRealtime()
        exposureNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: exposureNs
        sensorIso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: sensorIso
        focusDistance = result.get(CaptureResult.LENS_FOCUS_DISTANCE) ?: focusDistance
        val token = generation
        if (openingCamera && !cameraReady && firstFrameQueued.compareAndSet(false, true)) onMain {
          if (token == generation && !cameraReady && openingCamera && resumed && surfaceReady && camera.isOnPreview) {
            openingCamera = false
            cameraReady = true
            applyCaptureControls()
            findViewById<View>(R.id.previewEmpty).visibility = View.GONE
            if (requestedStream) transmit() else setStatus("PRÉVIA PRONTA")
          }
        }
      }
    }
    preview.holder.addCallback(this)
    surfaceReady = preview.holder.surface?.isValid == true
    loadPairing()
    loadCapturePreferences()
    handleIntent(intent)
    scanButton.setOnClickListener { scanQr() }
    connectButton.setOnClickListener {
      if (requestedStream) stopTransmission("TRANSMISSÃO ENCERRADA")
      else { startAfterPermission = true; requestStart() }
    }
    findViewById<Button>(R.id.quickConnect).setOnClickListener { connectButton.performClick() }
    qualitySpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
      override fun onNothingSelected(parent: AdapterView<*>?) = Unit
      override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
        saveCapturePreferences()
        updateCaptureControls()
        if (!requestedStream && resumed && configuredProfile != currentProfile()) {
          focusLock.isChecked = false
          zoomSlider.progress = 0
          closePreview(reopen = true)
        }
      }
    }
    updateCaptureControls()
    fastShutter.setOnCheckedChangeListener { _, _ -> saveCapturePreferences(); applyCaptureControls() }
    focusLock.setOnCheckedChangeListener { _, _ -> applyCaptureControls() }
    torch.setOnCheckedChangeListener { _, _ -> applyCaptureControls() }
    shutterSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
      override fun onNothingSelected(parent: AdapterView<*>?) = Unit
      override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { saveCapturePreferences(); applyCaptureControls() }
    }
    preview.setOnTouchListener { view, event ->
      if (event.action == MotionEvent.ACTION_UP && cameraReady) {
        if (configuredProfile?.highSpeed == true) { view.performClick(); return@setOnTouchListener true }
        focusLock.isChecked = false
        runCatching { camera.tapToFocus(preview, event) }.onSuccess {
          if (it) findViewById<FocusIndicatorView>(R.id.focusIndicator).show(event.x, event.y)
        }
        focusTouch = true
        view.performClick()
        focusTouch = false
        true
      } else event.action == MotionEvent.ACTION_DOWN
    }
    preview.setOnClickListener {
      if (!focusTouch && cameraReady && configuredProfile?.highSpeed == false) {
        focusLock.isChecked = false
        runCatching { camera.focusAt(.5f, .5f) }.onSuccess {
          if (it) findViewById<FocusIndicatorView>(R.id.focusIndicator).show(preview.width / 2f, preview.height / 2f)
        }
      }
    }
    updateControls()
    onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
      override fun handleOnBackPressed() {
        when {
          fullScreen -> findViewById<Button>(R.id.fullScreenButton).performClick()
          requestedStream -> androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
            .setTitle("Encerrar a transmissão?").setMessage("O vídeo enviado ao PC será interrompido.")
            .setNegativeButton("Continuar", null)
            .setPositiveButton("Parar e sair") { _, _ -> stopTransmission("TRANSMISSÃO ENCERRADA"); finish() }.show()
          else -> finish()
        }
      }
    })
    ContextCompat.registerReceiver(this, batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
    updates = CameraUpdates(this)
    runCatching { getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(networkCallback) }
    mainHandler.post(telemetry)
    if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
      cameraPermissionInFlight = true
      cameraPermission.launch(Manifest.permission.CAMERA)
    }
  }

  private fun bindViews() {
    preview = findViewById(R.id.cameraPreview)
    statusText = findViewById(R.id.statusText)
    metricsText = findViewById(R.id.metricsText)
    detailText = findViewById(R.id.detailText)
    captureText = findViewById(R.id.captureText)
    hostInput = findViewById(R.id.hostInput)
    portInput = findViewById(R.id.portInput)
    pathInput = findViewById(R.id.pathInput)
    qualitySpinner = findViewById(R.id.qualitySpinner)
    connectButton = findViewById(R.id.connectButton)
    scanButton = findViewById(R.id.scanButton)
    fastShutter = findViewById(R.id.fastShutter)
    focusLock = findViewById(R.id.focusLock)
    torch = findViewById(R.id.torch)
    zoomSlider = findViewById(R.id.zoomSlider)
    shutterSpinner = findViewById(R.id.shutterSpinner)
    transportSpinner = findViewById(R.id.transportSpinner)
    sensorText = findViewById(R.id.sensorText)
    deviceText = findViewById(R.id.deviceText)
    networkText = findViewById(R.id.networkText)
  }

  private fun configureInterface() {
    listOf(R.id.connectionTab, R.id.captureTab, R.id.networkTab).forEachIndexed { i, id ->
      findViewById<View>(id).setOnClickListener { selectTab(i) }
    }
    findViewById<TextView>(R.id.versionText).text = "CAMERA / ${BuildConfig.VERSION_NAME}"
    focusLock.isSaveEnabled = false
    torch.isSaveEnabled = false
    findViewById<Button>(R.id.permissionButton).setOnClickListener {
      if (getSharedPreferences("capture", MODE_PRIVATE).getBoolean("cameraDenied", false) &&
        !shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
      } else if (!cameraPermissionInFlight) {
        cameraPermissionInFlight = true
        cameraPermission.launch(Manifest.permission.CAMERA)
      }
    }
    findViewById<Button>(R.id.manualButton).setOnClickListener {
      val fields = findViewById<View>(R.id.manualFields)
      fields.visibility = if (fields.visibility == View.VISIBLE) View.GONE else View.VISIBLE
      (it as Button).setText(if (fields.visibility == View.VISIBLE) R.string.hide_manual else R.string.manual_connection)
    }
    findViewById<Button>(R.id.fastPreset).setOnClickListener { applyPreset(true) }
    findViewById<Button>(R.id.stablePreset).setOnClickListener { applyPreset(false) }
    findViewById<Button>(R.id.resetCapture).setOnClickListener {
      fastShutter.isChecked = false; focusLock.isChecked = false; torch.isChecked = false
      zoomSlider.progress = 0
      applyCaptureControls()
      saveCapturePreferences()
    }
    shutterSpinner.adapter = ArrayAdapter(this, R.layout.spinner_item, listOf("1/1000 s", "1/2000 s", "1/4000 s"))
      .also { it.setDropDownViewResource(R.layout.spinner_item) }
    transportSpinner.adapter = ArrayAdapter(this, R.layout.spinner_item, TransportProfile.entries.map { it.label })
      .also { it.setDropDownViewResource(R.layout.spinner_item) }
    findViewById<CheckBox>(R.id.guideSwitch).setOnCheckedChangeListener { _, checked ->
      findViewById<View>(R.id.compositionGuide).visibility = if (checked) View.VISIBLE else View.GONE
      saveCapturePreferences()
    }
    findViewById<Button>(R.id.fullScreenButton).setOnClickListener {
      fullScreen = !fullScreen
      (it as Button).setText(if (fullScreen) R.string.exit_fullscreen else R.string.fullscreen)
      configureLayout()
    }
    findViewById<Button>(R.id.copyDiagnostics).setOnClickListener {
      val text = "Tennis Vision Camera ${BuildConfig.VERSION_NAME}\n${currentProfile().label}\n" +
        "${statusText.text}\nCaptura $captureFps · H.264 $actualFps · envio $sentFps fps\n${metricsText.text}\n${sensorText.text}\n${deviceText.text}\n${networkText.text}"
      getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Diagnóstico Tennis Vision", text))
      Toast.makeText(this, R.string.diagnostics_copied, Toast.LENGTH_SHORT).show()
    }
    zoomSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
      override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
      override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
      override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
        val chars = if (::camera.isInitialized) camera.getCameraCharacteristics() else null
        val max = minOf(4f, chars?.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f)
        zoomRatio = 1f + progress / 100f * (max - 1f)
        findViewById<TextView>(R.id.zoomText).text = String.format(Locale.getDefault(), "Enquadramento · %.1f×", zoomRatio)
        if (fromUser) applyCaptureControls()
      }
    })
  }

  private fun selectTab(index: Int) {
    selectedTab = index.coerceIn(0, 2)
    listOf(R.id.connectionTab, R.id.captureTab, R.id.networkTab).forEachIndexed { i, id ->
      findViewById<View>(id).isSelected = i == selectedTab
    }
    listOf(R.id.connectionSection, R.id.captureSection, R.id.networkSection).forEachIndexed { i, id ->
      findViewById<View>(id).visibility = if (i == selectedTab) View.VISIBLE else View.GONE
    }
    findViewById<ScrollView>(R.id.settingsScroll).scrollTo(0, 0)
  }

  private fun refreshProfiles() {
    val previous = currentProfile()
    capabilities = runCatching { CameraCapabilities.inspect(this) }.getOrNull()
    capabilities?.profiles?.let { available ->
      profiles = available
      qualitySpinner.adapter = ArrayAdapter(this, R.layout.spinner_item, profiles.map { it.label })
        .also { it.setDropDownViewResource(R.layout.spinner_item) }
      qualitySpinner.setSelection(profiles.indexOfFirst {
        it.width == previous.width && it.height == previous.height && it.fps == previous.fps
      }.coerceAtLeast(0))
    }
    findViewById<TextView>(R.id.capabilitiesText).text = capabilities?.describe()
      ?: "Não foi possível consultar câmera e H.264. Revise a permissão e feche outros apps de câmera."
  }

  private fun applyPreset(fast: Boolean) {
    if (requestedStream || closingCamera) return
    val index = ProfileCatalog.recommended(profiles, fast)
    qualitySpinner.setSelection(index)
    val profile = profiles[index]
    fastShutter.isChecked = fast && !profile.highSpeed && capabilities?.supportsManualExposure == true
    shutterSpinner.setSelection(0)
    transportSpinner.setSelection(TransportProfile.FAST.ordinal)
    saveCapturePreferences()
    captureText.text = if (fast) "Mais quadros e exposição curta quando disponível. Ilumine bem a bola."
      else "Imagem equilibrada. Confira os FPS reais e a estabilidade do Wi-Fi."
  }

  private fun loadCapturePreferences() {
    val saved = getSharedPreferences("capture", MODE_PRIVATE)
    if (saved.contains("width")) qualitySpinner.setSelection(ProfileCatalog.closest(profiles,
      saved.getInt("width", 1920), saved.getInt("height", 1080), saved.getInt("fps", 60)))
    fastShutter.isChecked = saved.getBoolean("fast", false)
    shutterSpinner.setSelection(saved.getInt("shutter", 0).coerceIn(shutters.indices))
    findViewById<CheckBox>(R.id.guideSwitch).isChecked = saved.getBoolean("guide", false)
  }

  private fun saveCapturePreferences() {
    val profile = currentProfile()
    getSharedPreferences("capture", MODE_PRIVATE).edit()
      .putBoolean("fast", fastShutter.isChecked)
      .putInt("shutter", shutterSpinner.selectedItemPosition.coerceAtLeast(0))
      .putBoolean("guide", findViewById<CheckBox>(R.id.guideSwitch).isChecked)
      .putInt("width", profile.width).putInt("height", profile.height).putInt("fps", profile.fps).apply()
  }

  private fun requestStart() {
    if (destroyed || requestedStream || closingCamera) return
    captureRestartUsed = false
    startAfterPermission = true
    when {
      ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED -> {
        if (!cameraPermissionInFlight) { cameraPermissionInFlight = true; cameraPermission.launch(Manifest.permission.CAMERA) }
      }
      Build.VERSION.SDK_INT >= 37 && ContextCompat.checkSelfPermission(this, "android.permission.ACCESS_LOCAL_NETWORK") != PackageManager.PERMISSION_GRANTED -> {
        if (!networkPermissionInFlight) { networkPermissionInFlight = true; networkPermission.launch("android.permission.ACCESS_LOCAL_NETWORK") }
      }
      else -> { startAfterPermission = false; beginRequestedStream() }
    }
  }

  private fun configureLayout() {
    val root = findViewById<ConstraintLayout>(R.id.cameraRoot)
    val set = ConstraintSet().also { it.clone(root) }
    val margin = (12 * resources.displayMetrics.density).toInt()
    val portrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
    val compact = !portrait && resources.configuration.screenHeightDp < 420
    findViewById<View>(R.id.panelTitle).visibility = if (compact) View.GONE else View.VISIBLE
    findViewById<View>(R.id.panelSummary).visibility = if (compact) View.GONE else View.VISIBLE
    findViewById<TextView>(R.id.previewHint).visibility = if (portrait || resources.configuration.screenHeightDp < 400) View.GONE else View.VISIBLE
    findViewById<View>(R.id.previewLogo).visibility = if (portrait || resources.configuration.screenHeightDp < 400) View.GONE else View.VISIBLE
    findViewById<View>(R.id.controlPanel).visibility = if (fullScreen) View.GONE else View.VISIBLE
    findViewById<View>(R.id.quickConnect).visibility = if (fullScreen) View.VISIBLE else View.GONE
    set.clear(R.id.previewContainer, ConstraintSet.END)
    set.clear(R.id.previewContainer, ConstraintSet.BOTTOM)
    set.clear(R.id.controlPanel, ConstraintSet.START)
    set.clear(R.id.controlPanel, ConstraintSet.TOP)
    if (fullScreen) {
      set.connect(R.id.previewContainer, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END, margin)
      set.connect(R.id.previewContainer, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM, margin)
    } else if (portrait) {
      set.constrainWidth(R.id.controlPanel, ConstraintSet.MATCH_CONSTRAINT)
      set.connect(R.id.controlPanel, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START, margin)
      set.connect(R.id.controlPanel, ConstraintSet.TOP, R.id.previewContainer, ConstraintSet.BOTTOM, margin)
      set.connect(R.id.previewContainer, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END, margin)
    } else {
      val panelWidth = (minOf(350f, resources.configuration.screenWidthDp * .43f) *
        resources.displayMetrics.density).toInt()
      set.constrainWidth(R.id.controlPanel, panelWidth)
      set.connect(R.id.controlPanel, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP, margin)
      set.connect(R.id.previewContainer, ConstraintSet.END, R.id.controlPanel, ConstraintSet.START, margin)
      set.connect(R.id.previewContainer, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM, margin)
    }
    val profile = currentProfile()
    set.setDimensionRatio(R.id.previewContainer, "${profile.width}:${profile.height}")
    set.applyTo(root)
  }

  override fun onConfigurationChanged(newConfig: Configuration) {
    super.onConfigurationChanged(newConfig)
    configureLayout()
  }

  private fun currentProfile() = profiles[qualitySpinner.selectedItemPosition.coerceIn(profiles.indices)]

  private fun ensurePreview() {
    if (destroyed || !resumed || scanning || !surfaceReady || !::camera.isInitialized ||
      closingCamera || camera.isOnPreview || openingCamera ||
      ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
    val profile = currentProfile()
    runCatching {
      check(camera.prepareVideo(profile)) {
        "O codificador não aceitou ${profile.label}. Escolha outro perfil."
      }
      profilePrepared = true
      configuredProfile = profile
      configureLayout()
      openingCamera = true
      firstFrameQueued.set(false)
      lastCaptureMs = 0L
      warnedStall = false
      val openingGeneration = ++generation
      camera.startPreview()
      mainHandler.postDelayed({
        if (!destroyed && generation == openingGeneration && openingCamera && resumed && !scanning) {
          failCamera("A câmera não produziu imagem. Verifique a permissão ou escolha outro perfil.")
        }
      }, 8_000)
    }.onFailure { failCamera(it.message ?: "Não foi possível abrir a câmera.") }
  }

  private fun beginRequestedStream() {
    val profile = currentProfile()
    val config = runCatching {
      PairingConfig(hostInput.text.toString().trim(),
        portInput.text.toString().toIntOrNull() ?: error("Informe a porta SRT."),
        pathInput.text.toString().trim().removePrefix("/"),
        profile.fps, profile.width, profile.height, profile.bitrate, currentTransport().latencyMs)
    }.onFailure { setStatus(it.message ?: "Configuração inválida.", true) }.getOrNull() ?: return
    pendingConfig = config
    requestedStream = true
    savePairing(config)
    updateControls()
    setStatus("PREPARANDO TRANSMISSÃO…")
    if (configuredProfile != profile || !profilePrepared) closePreview(reopen = true)
    if (cameraReady) transmit() else ensurePreview()
  }

  private fun transmit() {
    if (!requestedStream || !cameraReady || !resumed || camera.isStreaming) return
    val config = pendingConfig ?: return
    val profile = currentProfile()
    val maxBitrate = camera.maxBitrate.takeIf { it > 0 } ?: profile.bitrate
    adapter = QueueAwareBitrateAdapter(maxBitrate, maxOf(2_000_000, maxBitrate / 4).coerceAtMost(maxBitrate)) { bitrate ->
      if (requestedStream && camera.isStreaming) camera.setVideoBitrateOnFly(bitrate)
    }
    actualFps = 0.0
    sentFps = 0.0
    lastStatsNs = 0L
    lastSentFrames = 0L
    actualBitrate = 0
    droppedFrames = 0
    queueBytes = 0
    recoveries = 0
    lastDropped = 0
    lastRecoveryAt = 0
    connected = false
    warnedStall = false
    lastCaptureMs = 0L
    captureFps = 0.0
    renderMetrics()
    camera.getStreamClient().setReTries(10)
    detailText.text = "PC: ${config.host}:${config.port} · câmera: ${config.path}"
    runCatching {
      camera.getStreamClient().setLatency(config.latencyMs)
      camera.getStreamClient().resizeCache(currentTransport().queueFrames(profile.fps))
      acquireWifiLock()
      camera.startStream(config.toSrtUrl())
    }
      .onFailure { stopTransmission("Não conectou: ${it.message}", true) }
  }

  private fun stopTransmission(message: String, error: Boolean = false, resumePreview: Boolean = true,
                               afterStopped: (() -> Unit)? = null) {
    requestedStream = false
    startAfterPermission = false
    connected = false
    pendingConfig = null
    adapter = null
    releaseWifiLock()
    profilePrepared = false
    generation++
    openingCamera = false
    cameraReady = false
    findViewById<View>(R.id.previewEmpty).visibility = View.VISIBLE
    actualFps = 0.0
    captureFps = 0.0
    sentFps = 0.0
    actualBitrate = 0
    droppedFrames = 0
    queueBytes = 0
    renderMetrics()
    setStatus(message, error)
    if (!::camera.isInitialized || closingCamera) { updateControls(); return }
    closingCamera = true
    updateControls()
    // Camera/codec shutdown can wait for a device callback. Keep that wait off the UI thread.
    closeWorker.execute {
      val failure = runCatching { camera.stopStream() }.exceptionOrNull()
      onMain {
        closingCamera = false
        closePreview(reopen = resumePreview && failure == null, afterClose = {
          updateControls()
          if (failure == null) afterStopped?.invoke()
        })
        if (failure != null) setStatus(failure.message ?: "Falha ao encerrar a câmera.", true)
        // A network/authentication failure must not leave the local camera
        // frozen. Capture/codec failures explicitly suppress reopening.
      }
    }
  }

  private fun closePreview(reopen: Boolean = false, afterClose: (() -> Unit)? = null) {
    generation++
    openingCamera = false
    cameraReady = false
    exposureNs = 0
    sensorIso = 0
    focusDistance = 0f
    captureFps = 0.0
    renderMetrics()
    profilePrepared = false
    if (!::camera.isInitialized || !camera.isOnPreview) {
      if (!closingCamera) {
        afterClose?.invoke()
        if (reopen) ensurePreview()
      }
      return
    }
    if (closingCamera) return
    closingCamera = true
    updateControls()
    // Switching from 60 to 120/240, and releasing the camera for QR, must not
    // block Android's UI thread. Wait for CameraDevice.onClosed on the worker.
    closeWorker.execute {
      val failure = runCatching { camera.stopPreview() }.exceptionOrNull()
      onMain {
        closingCamera = false
        if (destroyed) return@onMain
        if (failure != null) {
          scanning = false
          failCamera(failure.message ?: "Falha ao encerrar a prévia.")
        } else {
          updateControls()
          afterClose?.invoke()
          if (reopen) ensurePreview()
        }
      }
    }
  }

  private fun failCamera(message: String) {
    val config = pendingConfig
    val profile = configuredProfile ?: currentProfile()
    if (config == null || !requestedStream || captureRestartUsed || closingCamera || !profile.highSpeed) {
      stopTransmission(if (profile.highSpeed) "$message O perfil ${profile.fps} fps foi mantido. Tente 60 fps em Captura se a falha continuar."
        else message, true, resumePreview = false)
      return
    }
    captureRestartUsed = true
    stopTransmission("$message Reiniciando ${profile.height}p / ${profile.fps} fps uma vez.", true, resumePreview = false) {
      if (resumed && !scanning && !destroyed && surfaceReady) {
        requestedStream = true
        pendingConfig = config
        updateControls()
        ensurePreview()
      }
    }
  }

  private fun applyCaptureControls() {
    updateCaptureControls()
    if (!cameraReady) return
    val profile = configuredProfile ?: return
    val chars = camera.getCameraCharacteristics() ?: return
    if (profile.highSpeed) {
      captureText.text = if (requestedStream)
        "${profile.fps} fps solicitados na captura. A prévia da tela roda a 30/60 fps."
      else "Prévia em até 60 fps. Toque em Transmitir para iniciar a captura de ${profile.fps} fps."
      return
    }
    val illuminated = torch.isChecked && chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
    val zoom = zoomRatio
    camera.setCommonRequest { request ->
      if (chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true)
        request.set(CaptureRequest.FLASH_MODE, if (illuminated) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)
      if (Build.VERSION.SDK_INT >= 30 && chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) != null) {
        request.set(CaptureRequest.CONTROL_ZOOM_RATIO, chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)!!.clamp(zoom))
      } else chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)?.let { rect ->
        val halfW = (rect.width() / zoom / 2).toInt()
        val halfH = (rect.height() / zoom / 2).toInt()
        request.set(CaptureRequest.SCALER_CROP_REGION, Rect(rect.centerX()-halfW, rect.centerY()-halfH, rect.centerX()+halfW, rect.centerY()+halfH))
      }
    }
    val manual = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
      ?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) == true
    val fast = fastShutter.isChecked && manual
    val lock = focusLock.isChecked
    val lockedDistance = focusDistance
    val exposureRange = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
    val isoRange = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
    val selectedShutter = shutters[shutterSpinner.selectedItemPosition.coerceIn(shutters.indices)]
    val shutter = exposureRange?.clamp(selectedShutter) ?: selectedShutter
    val iso = ManualExposure.iso(sensorIso, exposureNs, shutter,
      isoRange?.lower ?: 100, isoRange?.upper ?: 800)
    val fpsRange = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
      ?.filter { it.contains(profile.fps) }?.minByOrNull { it.upper - it.lower }
    val autofocus = chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf()
    runCatching {
      check(camera.setCustomRequest { builder ->
        if (fpsRange != null) builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange)
        builder.set(CaptureRequest.CONTROL_AE_MODE, if (fast) CaptureRequest.CONTROL_AE_MODE_OFF else CaptureRequest.CONTROL_AE_MODE_ON)
        if (fast) {
          builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, shutter)
          builder.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
          builder.set(CaptureRequest.SENSOR_FRAME_DURATION, maxOf(1_000_000_000L / profile.fps, shutter))
        }
        val mode = when {
          lock && CaptureRequest.CONTROL_AF_MODE_OFF in autofocus -> CaptureRequest.CONTROL_AF_MODE_OFF
          CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO in autofocus -> CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
          CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE in autofocus -> CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
          else -> autofocus.firstOrNull() ?: CaptureRequest.CONTROL_AF_MODE_OFF
        }
        builder.set(CaptureRequest.CONTROL_AF_MODE, mode)
        if (lock) builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, lockedDistance)
      }) { "A câmera não aceitou o ajuste." }
      captureText.text = if (fast) "Solicitado: 1/${1_000_000_000L / shutter} s · ISO $iso. Use boa iluminação."
        else "Exposição automática. Toque na imagem para focar e depois trave o foco."
    }.onFailure { captureText.text = "Não foi possível aplicar o ajuste: ${it.message}" }
  }

  private fun scanQr() {
    if (requestedStream || scanning || closingCamera) return
    if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
      scanAfterPermission = true
      if (!cameraPermissionInFlight) { cameraPermissionInFlight = true; cameraPermission.launch(Manifest.permission.CAMERA) }
      return
    }
    scanning = true
    updateControls()
    setStatus("ABRINDO LEITOR QR…")
    closePreview(afterClose = {
      if (!destroyed && resumed) runCatching {
        qrScanner.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
          .setCaptureActivity(PairingScannerActivity::class.java)
          .setPrompt(getString(R.string.scanner_prompt)).setBeepEnabled(false)
          .setOrientationLocked(true).setBarcodeImageEnabled(false))
      }.onFailure {
        scanning = false
        updateControls()
        setStatus("Não abriu a câmera do leitor QR: ${it.message}", true)
        ensurePreview()
      } else scanning = false
    })
  }

  private fun applyPairing(value: String) {
    if (destroyed) return
    if (requestedStream) {
      setStatus("Pare a transmissão antes de trocar de computador.", true)
      return
    }
    runCatching { PairingConfig.parse(value) }.onSuccess { config ->
      hostInput.setText(config.host)
      portInput.setText(config.port.toString())
      pathInput.setText(config.path)
      val index = profiles.indexOfFirst { it.width == config.width && it.height == config.height && it.fps == config.fps }
      val selected = ProfileCatalog.closest(profiles, config.width, config.height, config.fps)
      qualitySpinner.setSelection(selected)
      transportSpinner.setSelection(TransportProfile.forLatency(config.latencyMs).ordinal)
      val effectiveProfile = profiles[selected]
      savePairing(config.copy(width = effectiveProfile.width, height = effectiveProfile.height,
        fps = effectiveProfile.fps, bitrate = effectiveProfile.bitrate))
      findViewById<TextView>(R.id.pairingText).text = "${config.host}:${config.port}\nCâmera ${config.path} · pronto para transmitir"
      detailText.text = "Pareado com ${config.host}:${config.port} · ${config.path}"
      setStatus(if (index >= 0) "PAREADO · TOQUE EM TRANSMITIR"
        else "PAREADO · PERFIL ADAPTADO À CÂMERA")
    }.onFailure { setStatus(it.message ?: "QR Code inválido.", true) }
  }

  private fun loadPairing() {
    val saved = getSharedPreferences("pairing", MODE_PRIVATE)
    hostInput.setText(saved.getString("host", ""))
    portInput.setText(saved.getInt("port", 8890).toString())
    pathInput.setText(saved.getString("path", "galaxy"))
    qualitySpinner.setSelection(ProfileCatalog.closest(profiles, saved.getInt("width", 1920),
      saved.getInt("height", 1080), saved.getInt("fps", 60)))
    transportSpinner.setSelection(TransportProfile.forLatency(saved.getInt("latency", 40)).ordinal)
    if (!hostInput.text.isNullOrBlank()) findViewById<TextView>(R.id.pairingText).text =
      "${hostInput.text}:${portInput.text}\nCâmera ${pathInput.text} · configuração salva"
  }

  private fun savePairing(config: PairingConfig) {
    getSharedPreferences("pairing", MODE_PRIVATE).edit()
      .putString("host", config.host).putInt("port", config.port).putString("path", config.path)
      .putInt("width", config.width).putInt("height", config.height).putInt("fps", config.fps)
      .putInt("latency", config.latencyMs).apply()
  }

  private fun handleIntent(intent: Intent?) {
    intent?.dataString?.takeIf { it.startsWith("tennisvision://") }?.let(::applyPairing)
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    handleIntent(intent)
  }

  private fun updateControls() {
    if (::updates.isInitialized) updates.activityState(updatesAllowed())
    connectButton.setText(if (requestedStream) R.string.disconnect else R.string.connect)
    findViewById<Button>(R.id.quickConnect).setText(if (requestedStream) R.string.disconnect else R.string.connect)
    connectButton.isSelected = requestedStream
    findViewById<Button>(R.id.quickConnect).isSelected = requestedStream
    connectButton.isEnabled = !closingCamera && !scanning
    findViewById<Button>(R.id.quickConnect).isEnabled = connectButton.isEnabled
    listOf<View>(hostInput, portInput, pathInput, qualitySpinner, scanButton, transportSpinner, zoomSlider,
      findViewById(R.id.fastPreset), findViewById(R.id.stablePreset), findViewById(R.id.manualButton),
      findViewById(R.id.resetCapture)).forEach { it.isEnabled = !requestedStream && !scanning && !closingCamera }
    updateCaptureControls()
  }

  private fun updateCaptureControls() {
    val high = currentProfile().highSpeed
    val chars = if (::camera.isInitialized) camera.getCameraCharacteristics() else capabilities?.characteristics
    val manual = chars?.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
      ?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) == true
    fastShutter.isEnabled = !high && manual
    val autofocus = chars?.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf()
    focusLock.isEnabled = !high && CaptureRequest.CONTROL_AF_MODE_OFF in autofocus &&
      (chars?.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f) > 0f
    shutterSpinner.isEnabled = !high && fastShutter.isChecked && manual
    torch.isEnabled = !high && chars?.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
    zoomSlider.isEnabled = !high && !requestedStream && (chars?.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f) > 1f
    if (high) captureText.text = if (requestedStream)
      "Captura ${currentProfile().fps} fps · prévia da tela a 30/60 fps. Confira H.264 e ENVIO."
      else "Prévia em até 60 fps. Transmitir inicia ${currentProfile().fps} fps; o Android controla foco e exposição."
    else if (!cameraReady) captureText.setText(R.string.capture_hint)
  }

  private fun currentTransport() = TransportProfile.entries[transportSpinner.selectedItemPosition.coerceIn(TransportProfile.entries.indices)]

  @Suppress("DEPRECATION")
  private fun acquireWifiLock() {
    if (wifiLock?.isHeld == true) return
    val wifi = applicationContext.getSystemService(WifiManager::class.java)
    runCatching {
      wifiLock = wifi.createWifiLock(if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        else WifiManager.WIFI_MODE_FULL_HIGH_PERF, "TennisVision:stream").apply {
        setReferenceCounted(false); acquire()
      }
    }
  }

  private fun releaseWifiLock() { runCatching { wifiLock?.takeIf { it.isHeld }?.release() }; wifiLock = null }

  private fun renderDevice() {
    val network = getSystemService(ConnectivityManager::class.java)
    val caps = network.getNetworkCapabilities(network.activeNetwork)
    val connection = when {
      caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "Wi-Fi"
      caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "Ethernet"
      caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "Rede móvel · confirme acesso ao PC"
      else -> "Sem rede ativa"
    }
    val thermal = if (Build.VERSION.SDK_INT >= 29) getSystemService(PowerManager::class.java).currentThermalStatus else 0
    val stalled = StreamHealth.captureStalled(lastCaptureMs, camera.encodingStartedMs, camera.lastEncodedMs,
      SystemClock.elapsedRealtime(), cameraReady && resumed && !scanning && !closingCamera, camera.isStreaming)
    if (stalled && !warnedStall) {
      warnedStall = true
      failCamera("A captura parou de produzir quadros. Tente transmitir novamente ou escolha menos FPS.")
    }
    if (!warnedStall && StreamHealth.encoderStalled(camera.encodingStartedMs, camera.lastEncodedMs,
        SystemClock.elapsedRealtime(), requestedStream && camera.isStreaming && resumed && !closingCamera)) {
      warnedStall = true
      failCamera("O H.264 parou de produzir vídeo. Tente um perfil de menor FPS ou resolução.")
    }
    deviceText.text = "$connection · ${if (batteryPercent >= 0) "$batteryPercent%" else "bateria —"}" +
      (if (batteryCelsius > 0) String.format(Locale.getDefault(), " · %.1f °C bateria", batteryCelsius) else "") +
      if (thermal >= PowerManager.THERMAL_STATUS_MODERATE) "\nAparelho aquecendo: teste menos FPS ou resolução." else ""
    sensorText.text = if (cameraReady && exposureNs > 0)
      "Exposição real 1/${maxOf(1, 1_000_000_000L / exposureNs)} s · ISO $sensorIso · ${currentProfile().width}×${currentProfile().height}"
      else "Exposição e ISO aparecem quando a câmera estiver pronta."
    val transport = currentTransport()
    networkText.text = "Buffer SRT solicitado: ${transport.latencyMs} ms\nFila: até ${transport.queueFrames(currentProfile().fps)} frames · retomadas: $recoveries\n" +
      "${camera.recoveryDiscarded} frames ignorados na retomada. O atraso total depende também do receptor, Wi-Fi e IA."
    val elapsed = if (connected) ((SystemClock.elapsedRealtime() - streamStartedMs) / 1000).coerceAtLeast(0) else 0
    findViewById<TextView>(R.id.profileBadge).text = "${currentProfile().height}p / ${currentProfile().fps} fps solicitados" +
      (if (connected) String.format(Locale.getDefault(), " · %02d:%02d", elapsed / 60, elapsed % 60) else "") +
      if (captureRestartUsed) "\nCaptura reiniciada · FPS escolhido mantido" else ""
    findViewById<TextView>(R.id.deviceText).setTextColor(ContextCompat.getColor(this,
      if (thermal >= PowerManager.THERMAL_STATUS_MODERATE) R.color.tv_warning else R.color.tv_muted))
    findViewById<View>(R.id.permissionButton).visibility = if (ContextCompat.checkSelfPermission(this,
      Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) View.VISIBLE else View.GONE
  }

  private fun onMain(action: () -> Unit) = runOnUiThread { if (!destroyed) action() }

  private fun setStatus(value: String, error: Boolean = false) {
    statusText.text = value
    statusText.setTextColor(ContextCompat.getColor(this, if (error) R.color.tv_error else R.color.tv_accent))
    if (error) detailText.text = value
  }

  private fun renderMetrics() {
    findViewById<View>(R.id.previewTelemetry).visibility = if (cameraReady) View.VISIBLE else View.GONE
    fun rate(fps: Double) = if (fps > 0) String.format(Locale.getDefault(), "%.0f fps", fps) else getString(R.string.empty_fps)
    findViewById<TextView>(R.id.captureValue).text = rate(captureFps)
    findViewById<TextView>(R.id.encoderValue).text = rate(actualFps)
    findViewById<TextView>(R.id.sendValue).text = rate(sentFps)
    metricsText.text = if (requestedStream) String.format(Locale.getDefault(), "%.1f Mbps · %d descartados · fila ~%d ms",
      actualBitrate / 1_000_000.0, droppedFrames, StreamHealth.queueMs(queueBytes, actualBitrate)) else "Prévia local · vídeo sem áudio"
    if (requestedStream && queueBytes > 0 && actualBitrate > 0) {
      val waitingMs = StreamHealth.queueMs(queueBytes, actualBitrate)
      detailText.text = "Fila de envio: ~$waitingMs ms · qualidade ajustada automaticamente ao Wi-Fi"
    }
  }

  override fun onConnectionStarted(url: String) = onMain { if (requestedStream) setStatus("CONECTANDO AO PC…") }
  override fun onConnectionSuccess() = onMain {
    if (requestedStream) {
      connected = true
      streamStartedMs = SystemClock.elapsedRealtime()
      // A keyframe requested before reconnect may be discarded while SRT is
      // still offline. Request one again now that the receiver is connected.
      camera.recoverFreshVideo()
      lastStatsNs = 0L
      lastSentFrames = 0L
      lastDropped = camera.getStreamClient().getDroppedVideoFrames()
      applyCaptureControls()
      setStatus("AO VIVO · ENVIANDO PARA O PC")
      detailText.text = "PC: ${pendingConfig?.host}:${pendingConfig?.port} · ${pendingConfig?.path}\nNo Windows, abra Conectar celular e toque em Conectar e iniciar vídeo."
    }
  }
  override fun onConnectionFailed(reason: String) = onMain {
    connected = false
    if (requestedStream && camera.getStreamClient().reTry(1_000, reason, null)) {
      setStatus("SINAL INTERROMPIDO · RECONECTANDO…", true)
    } else if (requestedStream) {
      stopTransmission("Não conectou. Abra o Tennis Vision Studio no PC e confira a rede e Preparar rede do PC.", true)
    }
  }
  override fun onDisconnect() = onMain {
    connected = false
    if (requestedStream) setStatus("RECONECTANDO AO PC…", true)
  }
  override fun onAuthError() = onMain { stopTransmission("Conexão recusada pelo PC.", true) }
  override fun onAuthSuccess() = Unit
  override fun onStreamingStats(report: StreamingStatsReport) = onMain {
    if (requestedStream) {
      actualBitrate = report.smoothedBitrate
      val now = SystemClock.elapsedRealtimeNanos()
      val sent = camera.getStreamClient().getSentVideoFrames()
      if (lastStatsNs > 0 && now > lastStatsNs && sent >= lastSentFrames)
        sentFps = (sent - lastSentFrames) * 1_000_000_000.0 / (now - lastStatsNs)
      else sentFps = 0.0
      lastStatsNs = now
      lastSentFrames = sent
      queueBytes = report.queueBytesOut
      droppedFrames = camera.getStreamClient().getDroppedVideoFrames()
      val waitingMs = StreamHealth.queueMs(queueBytes, actualBitrate)
      val nowMs = SystemClock.elapsedRealtime()
      if ((droppedFrames > lastDropped || waitingMs > currentTransport().queueMs * 2) && nowMs - lastRecoveryAt > 1500) {
        camera.recoverFreshVideo()
        recoveries++
        lastRecoveryAt = nowMs
      }
      lastDropped = droppedFrames
      adapter?.onStreamingStats(report)
      renderMetrics()
    }
  }

  override fun surfaceCreated(holder: SurfaceHolder) { surfaceReady = true; ensurePreview() }
  override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
    if (::camera.isInitialized && !closingCamera) camera.surfaceChanged()
  }
  override fun surfaceDestroyed(holder: SurfaceHolder) {
    surfaceReady = false
    if (requestedStream) stopTransmission("PRÉVIA INTERROMPIDA")
    closePreview()
  }
  override fun onResume() {
    super.onResume(); resumed = true
    if (capabilities == null && ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
      refreshProfiles()
    mainHandler.removeCallbacks(telemetry); mainHandler.post(telemetry)
    updateControls()
    ensurePreview()
    if (::updates.isInitialized) updates.resume(updatesAllowed())
  }
  override fun onPause() {
    resumed = false
    if (::updates.isInitialized) updates.pause()
    mainHandler.removeCallbacks(telemetry)
    saveCapturePreferences()
    if (::camera.isInitialized) {
      if (requestedStream) stopTransmission("PAUSADO · VOLTE AO APP PARA TRANSMITIR")
      closePreview()
    }
    super.onPause()
  }
  override fun onDestroy() {
    destroyed = true
    if (::updates.isInitialized) updates.close()
    resumed = false
    if (::camera.isInitialized) {
      stopTransmission("ENCERRADO")
      closePreview()
      camera.setFpsListener(null)
      camera.setCustomOnCaptureCompletedCallback(null)
      closeWorker.execute { runCatching { camera.release() } }
      preview.holder.removeCallback(this)
    }
    mainHandler.removeCallbacksAndMessages(null)
    releaseWifiLock()
    closeWorker.shutdown()
    runCatching { unregisterReceiver(batteryReceiver) }
    runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback) }
    super.onDestroy()
  }

  override fun onSaveInstanceState(outState: Bundle) {
    outState.putInt("tab", selectedTab)
    outState.putBoolean("fullscreen", fullScreen)
    super.onSaveInstanceState(outState)
  }

  private fun updatesAllowed() = resumed && !requestedStream && !scanning && !closingCamera &&
    !cameraPermissionInFlight && !networkPermissionInFlight
}

