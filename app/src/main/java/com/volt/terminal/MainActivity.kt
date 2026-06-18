package com.volt.terminal

import android.animation.*
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.Paint
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.*
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    enum class State { SCANNING, PROCESSING, WAITING_SELECTION, SUCCESS, COOLDOWN, ERROR }

    // ── Views ─────────────────────────────────────────────────────────────────
    private lateinit var previewView: PreviewView
    private lateinit var layoutOverlay: FrameLayout
    private lateinit var layoutScanning: FrameLayout
    private lateinit var layoutResult: LinearLayout
    private lateinit var tvResultIcon: TextView
    private lateinit var tvResultTitle: TextView
    private lateinit var tvResultSub: TextView
    private lateinit var tvVoltLogo: TextView
    private lateinit var tvVoltLogoScan: TextView
    private lateinit var scannerView: ScannerView
    private lateinit var bubbleFruitView: BubbleFruitView
    private lateinit var waveView: WaveView
    private lateinit var sparkleView: SparkleView
    private lateinit var dropRippleView: DropRippleView
    private lateinit var tvScanHint: TextView
    private var logoAnimator: Animator? = null
    private lateinit var tvDiagTime: TextView
    private lateinit var tvDiagMdb: TextView
    private lateinit var tvDiagClear: TextView
    private lateinit var scrollDiag: ScrollView
    private lateinit var layoutDiag: LinearLayout
    private lateinit var scanOverlayView: ScanOverlayView
    private lateinit var layoutPause: FrameLayout
    private lateinit var pauseAnimView: PauseAnimView
    private lateinit var tvVoltLogoPause: TextView
    private lateinit var fruitRainView: FruitRainView

    private val clockHandler      = Handler(Looper.getMainLooper())
    private val inactivityHandler = Handler(Looper.getMainLooper())
    private val timeFmt    = SimpleDateFormat("HH:mm:ss  dd/MM/yyyy", Locale.getDefault())
    private val logTimeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val logLines   = ArrayDeque<String>(50)

    private val clockRunnable = object : Runnable {
        override fun run() {
            tvDiagTime.text = timeFmt.format(Date())
            clockHandler.postDelayed(this, 1000)
        }
    }

    private fun appendLog(msg: String) {
        val line = "${logTimeFmt.format(Date())} $msg"
        if (logLines.size >= 50) logLines.removeFirst()
        logLines.addLast(line)
        // Mise à jour UI seulement si les logs sont visibles (mode admin)
        if (layoutDiag.visibility == View.VISIBLE) {
            runOnUiThread {
                tvDiagMdb.text = logLines.joinToString("\n")
                scrollDiag.post { scrollDiag.fullScroll(ScrollView.FOCUS_DOWN) }
            }
        }
    }

    // ── Logic ─────────────────────────────────────────────────────────────────
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var apiClient: VoltApiClient
    private lateinit var mdbController: MdbController

    private var state = State.SCANNING
    private var isProcessing = false
    private var mdbReady = false

    // true uniquement entre onReaderEnable et le premier beginSession qui le consomme
    private var readerEnabled = false

    @Volatile private var pendingVendOutcome:    ((Boolean) -> Unit)? = null

    // ── Nayax-like flow : BEGIN_SESSION immédiat, QR validé pendant VEND_REQUEST ──
    // pendingVendRequestData : données VEND_REQUEST reçues avant que le QR soit validé
    @Volatile private var pendingVendRequestData: ByteArray? = null
    // qrApproved : null=pas encore, true=APPROVED, false=REJECTED
    @Volatile private var qrApproved: Boolean? = null
    // nom de l'utilisateur pour l'affichage SUCCESS
    private var currentUserName: String? = null

    // ── Kiosk ─────────────────────────────────────────────────────────────────
    private val ADMIN_PIN = "2025"
    private var logoLongPressStart = 0L

    companion object {
        private const val TAG                  = "VOLT_Main"
        private const val RESULT_DISPLAY_MS    = 4_000L
        private const val FRUIT_RAIN_MS        = 5_000L
        private const val INACTIVITY_TIMEOUT_MS = 30_000L
        private const val REQ_CAMERA           = 101
    }

    // ── Timer d'inactivité ────────────────────────────────────────────────────
    private val inactivityRunnable = Runnable {
        if (state == State.SCANNING && layoutPause.visibility != View.VISIBLE) {
            appendLog("Inactivite 30s -> ecran pause")
            onMachinePaused()
        }
    }

    private fun resetInactivityTimer() {
        inactivityHandler.removeCallbacks(inactivityRunnable)
        if (state == State.SCANNING && layoutPause.visibility != View.VISIBLE) {
            inactivityHandler.postDelayed(inactivityRunnable, INACTIVITY_TIMEOUT_MS)
        }
    }

    private fun stopInactivityTimer() {
        inactivityHandler.removeCallbacks(inactivityRunnable)
    }

    // Tout touch sur l'écran remet le timer à zéro
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (state == State.SCANNING && layoutPause.visibility != View.VISIBLE) {
            resetInactivityTimer()
        }
        return super.dispatchTouchEvent(ev)
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  LIFECYCLE
    // ══════════════════════════════════════════════════════════════════════════

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupWindow()
        setContentView(R.layout.activity_main)
        bindViews()
        setupKiosk()
        applyBoldStroke(tvVoltLogo,      strokeDp = 2.5f)
        applyBoldStroke(tvVoltLogoPause, strokeDp = 4f)
        applyBoldStroke(tvVoltLogoScan,  strokeDp = 4f)

        apiClient      = VoltApiClient()
        mdbController  = MdbController(this)
        cameraExecutor = Executors.newSingleThreadExecutor()

        mdbController.onStatusChange = { msg -> appendLog(msg) }

        mdbController.onMdbReady = {
            mdbReady = true
            runOnUiThread { tvScanHint.text = "Scanne ton QR code" }
        }

        mdbController.onReaderEnabled = {
            readerEnabled = false

            // ════════════════════════════════════════════════════════════════════
            //  APPROCHE NAYAX : BEGIN_SESSION immédiat sur READER_ENABLE
            //  Nayax valide la carte offline (<1ms) → BEGIN_SESSION < 100ms
            //  La machine a une fenêtre courte après READER_ENABLE : si on attend
            //  le scan QR + API (2-5s), la machine a déjà quitté son état interne
            //  "attente paiement cashless" → plus jamais de VEND_REQUEST.
            //
            //  Solution : BEGIN_SESSION immédiat → machine envoie VEND_REQUEST
            //  (boisson déjà sélectionnée) → on valide le QR pendant ce temps →
            //  VEND_APPROVED dès que les deux sont prêts.
            // ════════════════════════════════════════════════════════════════════

            val preValidated = pendingVendOutcome  // QR déjà validé ?
            pendingVendOutcome   = null
            pendingVendRequestData = null
            qrApproved           = if (preValidated != null) true else null

            appendLog("READER_ENABLE → BEGIN SESSION immediat (comme Nayax)")

            val sessionOutcome: (Boolean) -> Unit = preValidated ?: { ok ->
                pendingVendRequestData = null
                qrApproved             = null
                val name = currentUserName
                if (ok) setState(State.SUCCESS, StateData(userName = name))
                else    setState(State.ERROR, StateData(
                    errorMessage = getString(R.string.vend_failed_message),
                    errorSub     = getString(R.string.vend_failed_sub)
                ))
            }
            mdbController.beginSession(sessionOutcome)

            if (preValidated == null) {
                // QR pas encore scanné → afficher l'écran de scan
                runOnUiThread {
                    isProcessing = false
                    if (state != State.SCANNING) onMachineResumed()
                    else setState(State.SCANNING)
                }
            }
            // Si preValidated != null : UI est déjà en WAITING_SELECTION → ne rien changer
        }

        // VEND_REQUEST reçu de la machine → décider APPROVED ou DENIED selon état QR
        mdbController.onVendRequestReceived = { price, vendData ->
            when (val approved = qrApproved) {
                true -> {
                    // QR déjà validé (pré-scan ou pendingVendOutcome) → VEND_APPROVED immédiat
                    pendingVendRequestData = null
                    qrApproved = null
                    appendLog("VEND_REQUEST ${price}c + QR pre-valide → VEND_APPROVED")
                    mdbController.approveVend(vendData)
                }
                false -> {
                    // QR rejeté → VEND_DENIED
                    pendingVendRequestData = null
                    qrApproved = null
                    appendLog("VEND_REQUEST ${price}c + QR invalide → VEND_DENIED")
                    mdbController.denyVend()
                }
                null -> {
                    // QR pas encore scanné → stocker, attendre le scan utilisateur
                    pendingVendRequestData = vendData
                    appendLog("VEND_REQUEST ${price}c recu - attente scan QR utilisateur")
                    // L'écran SCANNING est déjà affiché → l'utilisateur va scanner son QR
                }
            }
        }

        mdbController.onReaderDisabled = {
            readerEnabled = false
            runOnUiThread { onMachinePaused() }
        }

        mdbController.start()
        clockHandler.post(clockRunnable)

        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this,
                arrayOf(android.Manifest.permission.CAMERA), REQ_CAMERA)
        }

        setState(State.SCANNING)
    }

    override fun onResume() {
        super.onResume()
        hideSystemUI()
    }

    override fun onDestroy() {
        super.onDestroy()
        clockHandler.removeCallbacks(clockRunnable)
        stopInactivityTimer()
        cameraExecutor.shutdown()
        mdbController.stop()
        pauseAnimView.stopAnimation()
        fruitRainView.stopRain()
        scannerView.stopScan()
        bubbleFruitView.stop()
        waveView.stop()
        sparkleView.stop()
        dropRippleView.stop()
        logoAnimator?.cancel()
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  PAUSE / REPRISE CM30
    // ══════════════════════════════════════════════════════════════════════════

    private fun onMachinePaused() {
        isProcessing = false
        state = State.SCANNING
        stopInactivityTimer()
        scannerView.stopScan()
        bubbleFruitView.stop()
        waveView.stop()
        sparkleView.stop()
        dropRippleView.stop()
        logoAnimator?.cancel(); logoAnimator = null
        tvVoltLogoScan.scaleX = 1f; tvVoltLogoScan.scaleY = 1f
        fruitRainView.stopRain()
        fruitRainView.visibility   = View.GONE
        layoutScanning.visibility  = View.GONE
        layoutOverlay.visibility   = View.GONE
        scanOverlayView.visibility = View.GONE
        layoutPause.visibility = View.VISIBLE
        layoutPause.alpha = 0f
        layoutPause.animate().alpha(1f).setDuration(500).withStartAction {
            pauseAnimView.startAnimation()
        }.start()
    }

    private fun onMachineResumed() {
        layoutPause.animate().alpha(0f).setDuration(600).withEndAction {
            layoutPause.visibility = View.GONE
            pauseAnimView.stopAnimation()
        }.start()
        fruitRainView.visibility = View.VISIBLE
        fruitRainView.startRain()
        fruitRainView.postDelayed({
            fruitRainView.stopRain()
            fruitRainView.visibility = View.GONE
        }, FRUIT_RAIN_MS)
        setState(State.SCANNING)
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  LOGO — gras x3 via stroke sur TextPaint
    // ══════════════════════════════════════════════════════════════════════════

    private fun applyBoldStroke(tv: TextView, strokeDp: Float) {
        val px = resources.displayMetrics.density * strokeDp
        tv.paint.apply {
            style       = Paint.Style.FILL_AND_STROKE
            strokeWidth = px
            strokeJoin  = Paint.Join.ROUND
            strokeCap   = Paint.Cap.ROUND
        }
        tv.invalidate()
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  WINDOW / KIOSK
    // ══════════════════════════════════════════════════════════════════════════

    private fun setupWindow() {
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
        }
    }

    private fun hideSystemUI() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        )
    }

    private fun setupKiosk() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try { startLockTask() } catch (e: Exception) { Log.w(TAG, "startLockTask: ${e.message}") }
        }

        // Long press 3s sur le logo bas → dialog admin
        tvVoltLogo.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> logoLongPressStart = System.currentTimeMillis()
                MotionEvent.ACTION_UP   -> {
                    if (System.currentTimeMillis() - logoLongPressStart >= 3000) showAdminPinDialog()
                }
            }
            true
        }

        // Un seul tap sur l'écran pause → écran actif
        layoutPause.setOnClickListener { onMachineResumed() }
    }

    private fun showAdminPinDialog() {
        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                        android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "Code admin"
            textSize = 22f
            gravity = Gravity.CENTER
            setPadding(40, 40, 40, 40)
        }
        AlertDialog.Builder(this)
            .setTitle("Mode administration")
            .setView(input)
            .setPositiveButton("Confirmer") { _, _ ->
                if (input.text.toString() == ADMIN_PIN) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) stopLockTask()
                    // Affiche les logs et les met à jour avec l'historique en mémoire
                    layoutDiag.visibility = View.VISIBLE
                    tvDiagMdb.text = logLines.joinToString("\n").ifEmpty { "--- Journal MDB ---" }
                    scrollDiag.post { scrollDiag.fullScroll(ScrollView.FOCUS_DOWN) }
                    Toast.makeText(this, "Mode admin activé", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Code incorrect", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CAMERA && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        }
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() { /* kiosque */ }

    // ══════════════════════════════════════════════════════════════════════════
    //  STATES
    // ══════════════════════════════════════════════════════════════════════════

    private fun setState(newState: State, data: StateData = StateData()) {
        state = newState
        layoutOverlay.removeCallbacks(null)
        runOnUiThread {
            hideSystemUI()
            // Arrête tous les effets visuels dès qu'on quitte l'écran de scan
            if (newState != State.SCANNING) {
                scannerView.stopScan()
                bubbleFruitView.stop()
                waveView.stop()
                sparkleView.stop()
                dropRippleView.stop()
                logoAnimator?.cancel(); logoAnimator = null
                tvVoltLogoScan.scaleX = 1f; tvVoltLogoScan.scaleY = 1f
            }
            when (newState) {
                State.SCANNING           -> showScanning()
                State.PROCESSING         -> showProcessing()
                State.WAITING_SELECTION  -> showWaitingSelection(data)
                State.SUCCESS            -> showSuccess(data)
                State.COOLDOWN           -> showCooldown(data)
                State.ERROR              -> showError(data)
            }
        }
    }

    private fun showScanning() {
        isProcessing = false
        layoutOverlay.visibility   = View.GONE
        layoutScanning.visibility  = View.VISIBLE
        scanOverlayView.visibility = View.VISIBLE

        // ── Effets visuels écran actif ────────────────────────────────────────
        scannerView.startScan()
        if (!bubbleFruitView.isRunning()) bubbleFruitView.start()
        if (!waveView.isRunning())        waveView.start()
        if (!sparkleView.isRunning())     sparkleView.start()
        if (!dropRippleView.isRunning())  dropRippleView.start()

        // ── Pulse doux du logo VOLT. (1.0 → 1.025 → 1.0, 3.5s) ─────────────
        logoAnimator?.cancel()
        logoAnimator = ObjectAnimator.ofPropertyValuesHolder(
            tvVoltLogoScan,
            PropertyValuesHolder.ofFloat("scaleX", 1f, 1.025f, 1f),
            PropertyValuesHolder.ofFloat("scaleY", 1f, 1.025f, 1f)
        ).apply {
            duration       = 3500L
            repeatCount    = ObjectAnimator.INFINITE
            interpolator   = AccelerateDecelerateInterpolator()
            startDelay     = 600L
            start()
        }

        resetInactivityTimer()
    }

    private fun showProcessing() {
        stopInactivityTimer()
        layoutScanning.visibility  = View.GONE
        scanOverlayView.visibility = View.GONE
        layoutOverlay.visibility   = View.VISIBLE
        layoutOverlay.setBackgroundResource(R.drawable.bg_processing)
        tvResultIcon.text  = "⚡"
        tvResultTitle.text = getString(R.string.state_processing)
        tvResultSub.text   = getString(R.string.state_processing_sub)
        animatePulse(tvResultIcon)
    }

    private var waitingCountdown: Runnable? = null

    private fun showWaitingSelection(data: StateData) {
        layoutScanning.visibility  = View.GONE
        scanOverlayView.visibility = View.GONE
        layoutOverlay.visibility   = View.VISIBLE
        layoutOverlay.setBackgroundResource(R.drawable.bg_success)

        tvResultTitle.text = data.userName?.let { getString(R.string.success_greeting, it) }
            ?: getString(R.string.waiting_message)
        tvResultSub.text   = getString(R.string.waiting_sub)
        animateSlideIn(layoutResult)

        // Compte à rebours dans l'icône (flèche vers le bas + secondes restantes)
        var secondsLeft = 55
        val tick = object : Runnable {
            override fun run() {
                if (state != State.WAITING_SELECTION) return
                tvResultIcon.text = "👇\n${secondsLeft}s"
                if (secondsLeft-- > 0) layoutOverlay.postDelayed(this, 1000)
            }
        }
        waitingCountdown = tick
        tvResultIcon.text = "👇\n${secondsLeft}s"
        layoutOverlay.post(tick)
        animatePulse(tvResultIcon)
    }

    private fun showSuccess(data: StateData) {
        layoutScanning.visibility = View.GONE
        layoutOverlay.visibility  = View.VISIBLE
        layoutOverlay.setBackgroundResource(R.drawable.bg_success)
        tvResultIcon.text  = "✓"
        tvResultTitle.text = data.userName?.let { getString(R.string.success_greeting, it) }
            ?: getString(R.string.success_message)
        tvResultSub.text   = getString(R.string.success_sub)
        animateSlideIn(layoutResult)
        layoutOverlay.postDelayed({ setState(State.SCANNING) }, RESULT_DISPLAY_MS)
    }

    private fun showCooldown(data: StateData) {
        layoutScanning.visibility = View.GONE
        layoutOverlay.visibility  = View.VISIBLE
        layoutOverlay.setBackgroundResource(R.drawable.bg_cooldown)
        tvResultIcon.text  = "⏱"
        tvResultTitle.text = getString(R.string.cooldown_message)
        tvResultSub.text   = getString(R.string.cooldown_sub)
        animateSlideIn(layoutResult)
        layoutOverlay.postDelayed({ setState(State.SCANNING) }, RESULT_DISPLAY_MS)
    }

    private fun showError(data: StateData) {
        layoutScanning.visibility = View.GONE
        layoutOverlay.visibility  = View.VISIBLE
        layoutOverlay.setBackgroundResource(R.drawable.bg_error)
        tvResultIcon.text  = "✗"
        tvResultTitle.text = data.errorMessage ?: getString(R.string.error_message)
        tvResultSub.text   = data.errorSub ?: getString(R.string.error_sub)
        animateShake(tvResultIcon)
        layoutOverlay.postDelayed({ setState(State.SCANNING) }, RESULT_DISPLAY_MS)
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  QR DETECTION
    // ══════════════════════════════════════════════════════════════════════════

    private fun onQRDetected(raw: String) {
        if (isProcessing || state != State.SCANNING) return
        if (!raw.startsWith("volt://")) return
        val token = raw.removePrefix("volt://")
        if (token.isBlank()) return
        if (!mdbReady) {
            appendLog("Scan ignore - MDB pas encore pret")
            return
        }

        isProcessing = true
        setState(State.PROCESSING)

        apiClient.validateToken(token) { result ->
            when (result.status) {
                VoltApiClient.ValidationStatus.APPROVED -> {
                    val userName = result.userName
                    currentUserName = userName

                    val vr = pendingVendRequestData
                    when {
                        vr != null -> {
                            // ── CAS NORMAL (Nayax-like) ──────────────────────────────────
                            // VEND_REQUEST déjà reçu pendant la validation API → VEND_APPROVED
                            pendingVendRequestData = null
                            qrApproved = null
                            appendLog("QR APPROUVE + VEND_REQUEST en attente → VEND_APPROVED")
                            setState(State.WAITING_SELECTION, StateData(userName = userName))
                            mdbController.approveVend(vr)
                        }
                        mdbController.isSessionActive() -> {
                            // ── Session active, VEND_REQUEST pas encore reçu ─────────────
                            // (QR validé rapidement avant que la machine réponde)
                            qrApproved = true
                            appendLog("QR APPROUVE - VEND_REQUEST de la machine attendu")
                            setState(State.WAITING_SELECTION, StateData(userName = userName))
                        }
                        else -> {
                            // ── Pas de session active (QR scanné avant READER_ENABLE) ────
                            // Stocker : quand READER_ENABLE arrivera, BEGIN_SESSION immédiat
                            // + qrApproved=true → VEND_REQUEST → VEND_APPROVED auto
                            appendLog("QR APPROUVE sans session active - attente READER_ENABLE")
                            val outcome: (Boolean) -> Unit = { ok ->
                                pendingVendOutcome     = null
                                pendingVendRequestData = null
                                qrApproved             = null
                                if (ok) setState(State.SUCCESS, StateData(userName = userName))
                                else    setState(State.ERROR, StateData(
                                    errorMessage = getString(R.string.vend_failed_message),
                                    errorSub     = getString(R.string.vend_failed_sub)
                                ))
                            }
                            pendingVendOutcome = outcome
                            setState(State.WAITING_SELECTION, StateData(userName = userName))
                            clockHandler.postDelayed({
                                if (pendingVendOutcome != null) {
                                    pendingVendOutcome = null
                                    appendLog("Timeout 90s - machine jamais appelee")
                                    setState(State.ERROR, StateData(
                                        errorMessage = "Session expirée",
                                        errorSub     = "Sélectionnez votre boisson sur la machine."
                                    ))
                                }
                            }, 90_000L)
                        }
                    }
                }
                VoltApiClient.ValidationStatus.COOLDOWN -> {
                    abortActiveSession()
                    setState(State.COOLDOWN, StateData())
                }
                VoltApiClient.ValidationStatus.BLOCKED -> {
                    abortActiveSession()
                    setState(State.ERROR, StateData(
                        errorMessage = getString(R.string.blocked_message),
                        errorSub     = getString(R.string.blocked_sub)
                    ))
                }
                else -> {
                    abortActiveSession()
                    setState(State.ERROR, StateData(
                        errorMessage = getString(R.string.error_message),
                        errorSub     = when (result.reason) {
                            "NOT_SUBSCRIBED"        -> getString(R.string.error_not_subscribed)
                            "QR_EXPIRED_OR_INVALID" -> getString(R.string.error_expired)
                            else                    -> getString(R.string.error_sub)
                        }
                    ))
                }
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  CAMERA
    // ══════════════════════════════════════════════════════════════════════════

    private fun startCamera() {
        ProcessCameraProvider.getInstance(this).also { future ->
            future.addListener({
                try {
                    val provider = future.get()
                    val preview  = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .also { it.setAnalyzer(cameraExecutor, ::analyzeImage) }
                    provider.unbindAll()
                    provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis)
                } catch (e: Exception) { Log.e(TAG, "Camera: ${e.message}") }
            }, ContextCompat.getMainExecutor(this))
        }
    }

    @androidx.camera.core.ExperimentalGetImage
    private fun analyzeImage(proxy: ImageProxy) {
        val img = proxy.image ?: run { proxy.close(); return }
        val input = InputImage.fromMediaImage(img, proxy.imageInfo.rotationDegrees)
        BarcodeScanning.getClient().process(input)
            .addOnSuccessListener { codes ->
                codes.firstOrNull { it.format == Barcode.FORMAT_QR_CODE }
                    ?.rawValue?.let { onQRDetected(it) }
            }
            .addOnCompleteListener { proxy.close() }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  ANIMATIONS
    // ══════════════════════════════════════════════════════════════════════════

    private fun animatePulse(v: View) {
        ObjectAnimator.ofPropertyValuesHolder(v,
            PropertyValuesHolder.ofFloat("scaleX", 1f, 1.2f, 1f),
            PropertyValuesHolder.ofFloat("scaleY", 1f, 1.2f, 1f)
        ).apply {
            duration = 800
            repeatCount = ObjectAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
        }.start()
    }

    private fun animateSlideIn(v: View) {
        v.translationY = 80f
        v.alpha = 0f
        v.animate().translationY(0f).alpha(1f).setDuration(350)
            .setInterpolator(AccelerateDecelerateInterpolator()).start()
    }

    private fun animateShake(v: View) {
        ObjectAnimator.ofFloat(v, "translationX", 0f, -24f, 24f, -16f, 16f, -8f, 8f, 0f).apply {
            duration = 500
        }.start()
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  HELPERS
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Annule proprement une session MDB active (si en attente de VEND_REQUEST → denyVend,
     * sinon cancelSession). Réinitialise les flags QR.
     */
    private fun abortActiveSession() {
        val vr = pendingVendRequestData
        pendingVendRequestData = null
        qrApproved = null
        if (mdbController.isSessionActive()) {
            if (vr != null) mdbController.denyVend()
            else             mdbController.cancelSession()
        }
    }

    private fun bindViews() {
        previewView      = findViewById(R.id.previewView)
        layoutOverlay    = findViewById(R.id.layoutOverlay)
        layoutScanning   = findViewById(R.id.layoutScanning)
        layoutResult     = findViewById(R.id.layoutResult)
        tvResultIcon     = findViewById(R.id.tvResultIcon)
        tvResultTitle    = findViewById(R.id.tvResultTitle)
        tvResultSub      = findViewById(R.id.tvResultSub)
        tvVoltLogo       = findViewById(R.id.tvVoltLogo)
        tvVoltLogoScan   = findViewById(R.id.tvVoltLogoScan)
        scannerView      = findViewById(R.id.scannerView)
        bubbleFruitView  = findViewById(R.id.bubbleFruitView)
        waveView         = findViewById(R.id.waveView)
        sparkleView      = findViewById(R.id.sparkleView)
        dropRippleView   = findViewById(R.id.dropRippleView)
        tvScanHint       = findViewById(R.id.tvScanHint)
        tvDiagTime       = findViewById(R.id.tvDiagTime)
        tvDiagMdb        = findViewById(R.id.tvDiagMdb)
        tvDiagClear      = findViewById(R.id.tvDiagClear)
        scrollDiag       = findViewById(R.id.scrollDiag)
        layoutDiag       = findViewById(R.id.layoutDiag)
        scanOverlayView  = findViewById(R.id.scanOverlayView)
        layoutPause      = findViewById(R.id.layoutPause)

        // Positionne le trou transparent exactement sur le cadre QR après le layout
        scannerView.viewTreeObserver.addOnGlobalLayoutListener(object :
            android.view.ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                scannerView.viewTreeObserver.removeOnGlobalLayoutListener(this)
                val loc = IntArray(2)
                scannerView.getLocationInWindow(loc)
                scanOverlayView.setHole(
                    loc[0].toFloat(),
                    loc[1].toFloat(),
                    (loc[0] + scannerView.width).toFloat(),
                    (loc[1] + scannerView.height).toFloat()
                )
            }
        })
        pauseAnimView    = findViewById(R.id.pauseAnimView)
        tvVoltLogoPause  = findViewById(R.id.tvVoltLogoPause)
        fruitRainView    = findViewById(R.id.fruitRainView)
        tvDiagClear.setOnClickListener { logLines.clear(); tvDiagMdb.text = "--- Journal effacé ---" }
    }

    data class StateData(
        val userName: String?     = null,
        val errorMessage: String? = null,
        val errorSub: String?     = null
    )
}
