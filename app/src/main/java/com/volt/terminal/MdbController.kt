package com.volt.terminal

import android.content.Context
import android.hardware.cashless.CashlessManager
import android.hardware.cashless.ICashlessEventMonitor
import android.os.Handler
import android.os.Looper
import android.util.Log

class MdbController(private val context: Context) {

    companion object {
        private const val TAG = "VOLT_MDB"
        private const val CURRENCY_CHF = 0x02F4
        private const val VEND_REQUEST_TIMEOUT_MS  = 60_000L  // 60s pour sélectionner
        private const val READER_ENABLE_WAIT_MS    = 60_000L
        // Si SESSION_COMPLETE arrive moins de 3s après BEGIN_SESSION → rejet de format → downgrade
        private const val QUICK_REJECT_MS          = 3_000L
    }

    private val cashless: CashlessManager = CashlessManager.getInstance()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var onVendOutcome: ((Boolean) -> Unit)? = null
    @Volatile private var pendingRetryOutcome: ((Boolean) -> Unit)? = null
    @Volatile private var sessionActive = false

    // Format BEGIN_SESSION : Level 2 (6 octets, comme Nayax) ou Level 1 (2 octets fallback)
    // La machine auto-downgrade vers Level 1 si elle rejette Level 2 rapidement (SESSION_COMPLETE < 3s)
    @Volatile private var useLevelTwoFormat = true
    @Volatile private var lastBeginSessionMs = 0L

    var onStatusChange:        ((String) -> Unit)? = null
    var onMdbReady:            (() -> Unit)? = null
    var onReaderEnabled:       (() -> Unit)? = null
    var onReaderDisabled:      (() -> Unit)? = null
    /**
     * Appelé quand la machine envoie VEND_REQUEST.
     * MainActivity décide d'appeler approveVend() ou denyVend() selon la validation QR.
     * Si null → comportement auto (approuve directement).
     */
    var onVendRequestReceived: ((price: Int, data: ByteArray) -> Unit)? = null

    private fun postStatus(msg: String) {
        Log.i(TAG, msg)
        onStatusChange?.invoke(msg)
    }

    private fun hex(data: ByteArray) = data.joinToString(" ") { "%02X".format(it) }

    // Timeout : pas de VEND_REQUEST après beginSession
    private val vendRequestTimeoutRunnable = Runnable {
        if (sessionActive) {
            postStatus("TIMEOUT: aucun VEND_REQUEST apres ${VEND_REQUEST_TIMEOUT_MS/1000}s")
            sessionActive = false
            val cb = onVendOutcome.also { onVendOutcome = null }
            try { cashless.sendSessionCancelRequest() } catch (_: Throwable) {}
            cb?.invoke(false)
        }
    }

    // Timeout : READER_ENABLE jamais reçu après ret<0
    private val readerEnableWaitTimeout = Runnable {
        val cb = pendingRetryOutcome.also { pendingRetryOutcome = null }
        if (cb != null) {
            postStatus("TIMEOUT: READER_ENABLE jamais recu apres ret<0")
            cb(false)
        }
    }

    private val monitor = object : ICashlessEventMonitor.Stub() {

        override fun onInitialComplete(cashlessInfo: ByteArray, vmcInfo: ByteArray) {
            val level = if (cashlessInfo.isNotEmpty()) cashlessInfo[0].toInt() and 0xFF else 0
            // Réinitialise le format au reconnect : on retente Level 2 à chaque reconnexion MDB
            useLevelTwoFormat = true
            postStatus("MDB connecte niveau L$level - pret (format=${if (useLevelTwoFormat) "L2" else "L1"})")
            onMdbReady?.invoke()
        }

        override fun onReset() {
            postStatus("MDB RESET recu (session=$sessionActive)")
            sessionActive = false
            mainHandler.removeCallbacks(vendRequestTimeoutRunnable)
            mainHandler.removeCallbacks(readerEnableWaitTimeout)
            // Toujours répondre au RESET, sinon la machine reste bloquée
            try { cashless.sendJustReset() } catch (e: Throwable) {
                Log.e(TAG, "sendJustReset: ${e.message}")
            }
            // Si une session ou outcome en attente → signale l'échec
            val cb = onVendOutcome.also { onVendOutcome = null }
            val retry = pendingRetryOutcome.also { pendingRetryOutcome = null }
            if (cb != null)    mainHandler.post { cb(false) }
            else if (retry != null) mainHandler.post { retry(false) }
        }

        override fun onReaderEnable() {
            if (sessionActive) {
                // CM30 envoie parfois READER_ENABLE pendant une session active → on l'ignore
                // pour ne pas interrompre le flux VEND_REQUEST / VEND_APPROVED
                postStatus("READER_ENABLE pendant session active -> ignore")
                return
            }

            mainHandler.removeCallbacks(vendRequestTimeoutRunnable)

            val retry = pendingRetryOutcome
            if (retry != null) {
                pendingRetryOutcome = null
                mainHandler.removeCallbacks(readerEnableWaitTimeout)
                postStatus("READER_ENABLE -> retry BEGIN SESSION")
                mainHandler.postDelayed({ beginSession(retry) }, 200L)
            } else {
                postStatus("READER_ENABLE -> attente scan QR")
                onReaderEnabled?.invoke()
            }
        }

        override fun onReaderDisable() {
            postStatus("READER_DISABLE")
            if (!sessionActive) {
                mainHandler.removeCallbacks(vendRequestTimeoutRunnable)
                mainHandler.removeCallbacks(readerEnableWaitTimeout)
                pendingRetryOutcome = null
                onReaderDisabled?.invoke()
            } else {
                postStatus("READER_DISABLE pendant session -> ignore")
            }
        }

        override fun onSetupMaxMinPrices(data: ByteArray) {
            postStatus("SETUP PRICES: ${hex(data)}")
        }

        override fun onVendRequest(data: ByteArray) {
            mainHandler.removeCallbacks(vendRequestTimeoutRunnable)

            val priceH = if (data.size > 0) data[0].toInt() and 0xFF else 0
            val priceL = if (data.size > 1) data[1].toInt() and 0xFF else 0
            val price  = (priceH shl 8) or priceL
            postStatus("VEND_REQUEST prix=${price}c data=[${hex(data)}]")

            val handler = onVendRequestReceived
            if (handler != null) {
                // Délègue la décision à MainActivity (validation QR en cours / déjà faite)
                mainHandler.post { handler(price, data) }
            } else {
                // Pas de handler → auto-approuve (compatibilité)
                doSendVendApproved(data)
            }
        }

        override fun onVendSuccess(data: ByteArray) {
            sessionActive = false
            mainHandler.removeCallbacks(vendRequestTimeoutRunnable)
            postStatus("VEND_SUCCESS - boisson distribuee! data=[${hex(data)}]")
            val cb = onVendOutcome.also { onVendOutcome = null }
            cb?.invoke(true)
        }

        override fun onVendFailure(data: ByteArray) {
            sessionActive = false
            mainHandler.removeCallbacks(vendRequestTimeoutRunnable)
            postStatus("VEND_FAILURE data=[${hex(data)}]")
            try { cashless.sendACK() } catch (_: Throwable) {}
            val cb = onVendOutcome.also { onVendOutcome = null }
            cb?.invoke(false)
        }

        override fun onVendCancel() {
            sessionActive = false
            mainHandler.removeCallbacks(vendRequestTimeoutRunnable)
            postStatus("VEND_CANCEL")
            val cb = onVendOutcome.also { onVendOutcome = null }
            cb?.invoke(false)
        }

        override fun onSessionComplete() {
            sessionActive = false
            mainHandler.removeCallbacks(vendRequestTimeoutRunnable)
            val elapsed = System.currentTimeMillis() - lastBeginSessionMs

            if (useLevelTwoFormat && lastBeginSessionMs > 0 && elapsed < QUICK_REJECT_MS) {
                // Rejet rapide du format Level 2 (6 octets) → la machine veut Level 1 (2 octets)
                // Downgrade automatique + retry au prochain READER_ENABLE
                useLevelTwoFormat = false
                postStatus("SESSION_COMPLETE ${elapsed}ms apres BEGIN_SESSION -> downgrade Level1 + retry")
                val cb = onVendOutcome.also { onVendOutcome = null }
                if (cb != null) {
                    pendingRetryOutcome = cb
                    mainHandler.postDelayed(readerEnableWaitTimeout, READER_ENABLE_WAIT_MS)
                }
            } else {
                postStatus("SESSION_COMPLETE apres ${elapsed}ms - machine a ferme la session")
                val cb = onVendOutcome.also { onVendOutcome = null }
                if (cb != null) mainHandler.post { cb(false) }
            }
        }

        override fun onReaderCancel() {
            postStatus("READER_CANCEL")
        }

        override fun onCashSale(data: ByteArray) {
            postStatus("CASH_SALE: ${hex(data)}")
        }

        override fun onNegativeVendRequest(data: ByteArray) {
            postStatus("NEGATIVE_VEND_REQUEST: ${hex(data)}")
            try { cashless.sendVendDenied() } catch (_: Throwable) {}
        }

        override fun onSelectionDenied(data: ByteArray) {
            postStatus("SELECTION_DENIED: ${hex(data)}")
        }

        override fun onCouponReply(data: ByteArray) { }
        override fun onReaderDataEntryResponse(data: ByteArray) { }

        override fun onRevalueRequest(data: ByteArray) {
            postStatus("REVALUE_REQUEST: ${hex(data)} -> denied")
            try { cashless.sendRevalueDenied() } catch (_: Throwable) {}
        }

        override fun onRevalueLimitRequest() {
            try { cashless.sendRevalueDenied() } catch (_: Throwable) {}
        }

        override fun onSyncTimeDate(timeDate: ByteArray) { }

        override fun onDiagnostics(data: ByteArray) {
            postStatus("DIAGNOSTICS: ${hex(data)}")
            try { cashless.sendDiagnosticsResponse(byteArrayOf(0x00)) } catch (_: Throwable) {}
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    fun start() {
        Thread {
            try {
                Thread.sleep(3000)
                tryInit()
            } catch (_: Throwable) {}
        }.start()
    }

    private fun tryInit() {
        try {
            if (cashless.isActive()) cashless.destroy()
            Thread.sleep(500)
            cashless.setLogLevel(1)
            // Level 2 = même niveau que Nayax (carte bancaire).
            // La machine CM30 est configurée pour Level 2 → BEGIN_SESSION doit être 6 octets.
            cashless.setConfiguration(2, CURRENCY_CHF, 0x01, 0x02, 0x20, 0x0E)
            cashless.registerMonitor(monitor)
            cashless.start()
            postStatus("MDB init OK - attente machine...")
        } catch (e: Throwable) {
            postStatus("MDB ERREUR init: ${e.message?.take(80)}")
        }
    }

    fun stop() {
        mainHandler.removeCallbacks(vendRequestTimeoutRunnable)
        mainHandler.removeCallbacks(readerEnableWaitTimeout)
        try { cashless.destroy() } catch (_: Throwable) {}
    }

    /**
     * Lance une session MDB.
     * - ret >= 0 : session ouverte, on attend VEND_REQUEST.
     * - ret < 0  : MDB pas prêt, on attend le prochain READER_ENABLE pour réessayer.
     */
    fun beginSession(outcome: (Boolean) -> Unit) {
        if (sessionActive) {
            postStatus("beginSession ignore : session deja active")
            return
        }

        sessionActive = true
        onVendOutcome = outcome

        try {
            val data = if (useLevelTwoFormat) {
                // ── Level 2 (comme Nayax / carte bancaire) ──────────────────────────────
                // [FundsH][FundsL] [PaymentMediaID 4 octets]
                // FundsH/L = 0xFFFF → crédit max (65535 unités)
                // PaymentMediaID = 0x00000001 (ID valide non-nul ≠ 0xFFFFFFFF rejeté)
                // Ce format déclenche VEND_REQUEST automatique si une boisson est déjà sélectionnée.
                byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x01.toByte())
            } else {
                // ── Level 1 fallback ─────────────────────────────────────────────────────
                // [FundsH][FundsL] uniquement — utilisé si Level 2 a été rejeté (SESSION_COMPLETE < 3s)
                byteArrayOf(0xFF.toByte(), 0xFF.toByte())
            }
            lastBeginSessionMs = System.currentTimeMillis()
            val ret = cashless.sendBeginSession(data)
            postStatus("BEGIN_SESSION L${if (useLevelTwoFormat) 2 else 1} ${data.size}o ret=$ret [${hex(data)}]")

            if (ret >= 0) {
                mainHandler.postDelayed(vendRequestTimeoutRunnable, VEND_REQUEST_TIMEOUT_MS)
            } else {
                sessionActive = false
                onVendOutcome = null
                postStatus("MDB pas pret (ret=$ret) -> attente READER_ENABLE pour retry")
                pendingRetryOutcome = outcome
                mainHandler.postDelayed(readerEnableWaitTimeout, READER_ENABLE_WAIT_MS)
            }
        } catch (e: Throwable) {
            sessionActive = false
            onVendOutcome = null
            postStatus("BEGIN_SESSION ERREUR: ${e.message?.take(60)}")
            outcome(false)
        }
    }

    /** Approuve la distribution après validation QR réussie. */
    fun approveVend(vendData: ByteArray) {
        doSendVendApproved(vendData)
    }

    /** Refuse la distribution (QR invalide, expiré, etc.). */
    fun denyVend() {
        postStatus("VEND_DENIED - QR invalide ou refuse")
        try { cashless.sendVendDenied() } catch (_: Throwable) {}
        sessionActive = false
        val cb = onVendOutcome.also { onVendOutcome = null }
        mainHandler.post { cb?.invoke(false) }
    }

    private fun doSendVendApproved(data: ByteArray) {
        val priceH = if (data.size > 0) data[0].toInt() and 0xFF else 0
        val priceL = if (data.size > 1) data[1].toInt() and 0xFF else 0
        try {
            // Nayax (Level 2) : [prixH][prixL][itemH][itemL]
            val fullData  = if (data.size >= 4) data else
                            byteArrayOf(priceH.toByte(), priceL.toByte(), 0x00, 0x00)
            val priceOnly = byteArrayOf(priceH.toByte(), priceL.toByte())

            val ret1 = cashless.sendVendApproved(fullData)
            postStatus("VEND_APPROVED ${fullData.size}o ret=$ret1 [${hex(fullData)}]")

            if (ret1 < 0) {
                postStatus("retry 2 octets...")
                val ret2 = cashless.sendVendApproved(priceOnly)
                postStatus("VEND_APPROVED 2o ret=$ret2 [${hex(priceOnly)}]")
                if (ret2 < 0) {
                    postStatus("VEND_APPROVED echec total -> DENY")
                    try { cashless.sendVendDenied() } catch (_: Throwable) {}
                    sessionActive = false
                    val cb = onVendOutcome.also { onVendOutcome = null }
                    mainHandler.post { cb?.invoke(false) }
                }
            }
        } catch (e: Throwable) {
            postStatus("VEND_APPROVED ERREUR: ${e.message?.take(60)}")
            try { cashless.sendVendDenied() } catch (_: Throwable) {}
            sessionActive = false
            val cb = onVendOutcome.also { onVendOutcome = null }
            mainHandler.post { cb?.invoke(false) }
        }
    }

    fun cancelSession() {
        sessionActive = false
        mainHandler.removeCallbacks(vendRequestTimeoutRunnable)
        mainHandler.removeCallbacks(readerEnableWaitTimeout)
        onVendOutcome = null
        pendingRetryOutcome = null
        try { cashless.sendSessionCancelRequest() } catch (_: Throwable) {}
    }

    fun isSessionActive(): Boolean = sessionActive
}
