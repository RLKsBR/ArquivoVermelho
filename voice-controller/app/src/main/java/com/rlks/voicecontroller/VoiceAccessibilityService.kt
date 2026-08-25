package com.rlks.voicecontroller

import android.Manifest
import android.accessibilityservice.AccessibilityButtonController
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class VoiceAccessibilityService : AccessibilityService() {
    private lateinit var store: ProfileStore
    private lateinit var windowManager: WindowManager
    private val handler = Handler(Looper.getMainLooper())

    private var micView: TextView? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var captureOverlay: View? = null
    private var listening = false
    private var accessibilityButtonCallback: AccessibilityButtonController.AccessibilityButtonCallback? = null

    private val resetMic = Runnable {
        if (!listening) micView?.apply { text = "🎙"; textSize = 27f }
    }

    private val restartListening = Runnable {
        if (::store.isInitialized && store.continuousMode && !listening && captureOverlay == null &&
            store.pendingCalibration == ProfileStore.PENDING_NONE) {
            startListening()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        store = ProfileStore(this)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createSpeechRecognizer()
        showMicrophoneOverlay()
        registerSystemAccessibilityButton()
        message("Voice Controller TFT pronto")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    override fun onDestroy() {
        handler.removeCallbacks(resetMic)
        handler.removeCallbacks(restartListening)
        accessibilityButtonCallback?.let { callback ->
            runCatching { accessibilityButtonController.unregisterAccessibilityButtonCallback(callback) }
        }
        accessibilityButtonCallback = null
        removeCaptureOverlay()
        micView?.let { runCatching { windowManager.removeView(it) } }
        micView = null
        speechRecognizer?.destroy()
        speechRecognizer = null
        super.onDestroy()
    }

    private fun registerSystemAccessibilityButton() {
        val callback = object : AccessibilityButtonController.AccessibilityButtonCallback() {
            override fun onClicked(controller: AccessibilityButtonController) {
                activateController()
            }
        }
        accessibilityButtonCallback = callback
        accessibilityButtonController.registerAccessibilityButtonCallback(callback)
    }

    private fun activateController() {
        val pending = store.pendingCalibration
        if (pending != ProfileStore.PENDING_NONE) {
            store.pendingCalibration = ProfileStore.PENDING_NONE
            performPendingCalibration(pending)
            return
        }

        if (listening) {
            store.continuousMode = false
            speechRecognizer?.cancel()
            listening = false
            showMicTemporary("PAUSA")
            return
        }
        startListening()
    }

    private fun createSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            store.addRecognitionError("Reconhecimento de voz indisponível")
            return
        }
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).also { recognizer ->
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    listening = true
                    micView?.apply { text = "●"; textSize = 22f }
                }

                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit

                override fun onError(error: Int) {
                    listening = false
                    val description = when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH -> "Sem correspondência"
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Tempo de fala esgotado"
                        else -> "Erro de voz $error"
                    }
                    store.addRecognitionError(description)
                    showMicTemporary("?")
                    scheduleContinuousRestart(650)
                }

                override fun onResults(results: Bundle?) {
                    listening = false
                    val phrases = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                    val selected = VoiceCommandParser.parseAlternatives(phrases)
                    val summary = selected?.second?.let { commandSummary(it) }
                    store.addRecognitionLog(phrases, summary)
                    showMicTemporary(summary?.take(8) ?: "?")
                    if (selected != null) processCommand(selected.second)
                    scheduleContinuousRestart(420)
                }

                override fun onPartialResults(partialResults: Bundle?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
        }
    }

    private fun startListening() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            message("Abra o Voice Controller e permita o microfone")
            return
        }
        if (captureOverlay != null) return

        val recognizer = speechRecognizer ?: run {
            createSpeechRecognizer()
            speechRecognizer
        } ?: return

        if (listening) return
        handler.removeCallbacks(resetMic)
        handler.removeCallbacks(restartListening)

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 8)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, tftBiasingStrings())
        }
        micView?.apply { text = "…"; textSize = 22f }
        recognizer.startListening(intent)
    }

    private fun scheduleContinuousRestart(delayMs: Long) {
        handler.removeCallbacks(restartListening)
        if (store.continuousMode) handler.postDelayed(restartListening, delayMs)
    }

    private fun processCommand(command: VoiceCommand) {
        when (command) {
            is VoiceCommand.BuySlots -> {
                val line = store.getShopLine()
                val points = command.slots.mapNotNull { TftLayout.shopSlotToPoint(it, line) }
                if (points.size != command.slots.size) message("Calibre a loja primeiro") else tapSequence(points)
            }
            VoiceCommand.Reroll -> tapStoredPoint(ProfileStore.POINT_REROLL, "Rolar")
            VoiceCommand.BuyXp -> tapStoredPoint(ProfileStore.POINT_XP, "XP")
            VoiceCommand.ToggleShop -> tapStoredPoint(ProfileStore.POINT_SHOP_TOGGLE, "botão da loja")
            is VoiceCommand.BenchToBoard -> {
                val from = TftLayout.benchSlotToPoint(command.bench, store.getBenchLine())
                val to = TftLayout.boardSquareToPoint(command.square, store.getBoardRows())
                dragOrExplain(from, to, "Calibre banco e tabuleiro")
            }
            is VoiceCommand.BoardToBench -> {
                val from = TftLayout.boardSquareToPoint(command.square, store.getBoardRows())
                val to = TftLayout.benchSlotToPoint(command.bench, store.getBenchLine())
                dragOrExplain(from, to, "Calibre tabuleiro e banco")
            }
            is VoiceCommand.BoardToBoard -> {
                val rows = store.getBoardRows()
                dragOrExplain(
                    TftLayout.boardSquareToPoint(command.from, rows),
                    TftLayout.boardSquareToPoint(command.to, rows),
                    "Calibre o tabuleiro"
                )
            }
            is VoiceCommand.SellBench -> {
                val from = TftLayout.benchSlotToPoint(command.bench, store.getBenchLine())
                dragOrExplain(from, store.getPoint(ProfileStore.POINT_SELL), "Calibre banco e área de venda")
            }
            is VoiceCommand.SellBoard -> {
                val from = TftLayout.boardSquareToPoint(command.square, store.getBoardRows())
                dragOrExplain(from, store.getPoint(ProfileStore.POINT_SELL), "Calibre tabuleiro e área de venda")
            }
            is VoiceCommand.Choice -> {
                val point = store.getRegion(ProfileStore.REGION_CHOICES)?.horizontalChoice(command.index, 3)
                if (point == null) message("Marque a região de escolhas primeiro") else tap(point)
            }
            VoiceCommand.PauseControl -> {
                store.continuousMode = false
                handler.removeCallbacks(restartListening)
                message("Controle contínuo pausado")
            }
            VoiceCommand.ResumeControl -> {
                store.continuousMode = true
                message("Controle contínuo ativado")
            }
            VoiceCommand.Help -> message("Ex.: rolar • comprar três • banco dois para D4")
            is VoiceCommand.Unknown -> Unit
        }
    }

    private fun commandSummary(command: VoiceCommand): String? = when (command) {
        is VoiceCommand.BuySlots -> "COMPRAR ${command.slots.joinToString(",")}"
        VoiceCommand.Reroll -> "ROLAR"
        VoiceCommand.BuyXp -> "XP"
        VoiceCommand.ToggleShop -> "LOJA"
        is VoiceCommand.BenchToBoard -> "B${command.bench}>${command.square.uppercase()}"
        is VoiceCommand.BoardToBench -> "${command.square.uppercase()}>B${command.bench}"
        is VoiceCommand.BoardToBoard -> "${command.from.uppercase()}>${command.to.uppercase()}"
        is VoiceCommand.SellBench -> "VENDER B${command.bench}"
        is VoiceCommand.SellBoard -> "VENDER ${command.square.uppercase()}"
        is VoiceCommand.Choice -> "ESCOLHA ${command.index}"
        VoiceCommand.PauseControl -> "PAUSAR"
        VoiceCommand.ResumeControl -> "RETOMAR"
        VoiceCommand.Help -> "AJUDA"
        is VoiceCommand.Unknown -> null
    }

    private fun tftBiasingStrings(): ArrayList<String> {
        val values = arrayListOf(
            "rolar", "rerrolar", "comprar um", "comprar dois", "comprar três", "comprar quatro", "comprar cinco",
            "subir nível", "comprar xp", "abrir loja", "fechar loja", "pausar controle", "retomar controle",
            "aprimoramento um", "aprimoramento dois", "aprimoramento três",
            "vender banco um", "vender banco dois", "vender banco três"
        )
        val ranks = listOf("um", "dois", "três", "quatro")
        for (file in 'A'..'G') {
            for (rank in ranks) values += "$file $rank"
        }
        return values
    }

    private fun performPendingCalibration(type: String) {
        when (type) {
            ProfileStore.PENDING_BOARD -> captureBoard()
            ProfileStore.PENDING_BENCH -> captureLine(
                "Banco",
                "Toque no CENTRO do banco 1",
                "Agora toque no CENTRO do banco 9"
            ) { first, last -> store.saveBenchLine(first, last) }
            ProfileStore.PENDING_SHOP -> captureLine(
                "Loja",
                "Toque no CENTRO da carta 1 da loja",
                "Agora toque no CENTRO da carta 5"
            ) { first, last -> store.saveShopLine(first, last) }
            ProfileStore.PENDING_REROLL -> capturePoint(ProfileStore.POINT_REROLL, "Toque no botão ROLAR")
            ProfileStore.PENDING_XP -> capturePoint(ProfileStore.POINT_XP, "Toque no botão de XP")
            ProfileStore.PENDING_SHOP_TOGGLE -> capturePoint(ProfileStore.POINT_SHOP_TOGGLE, "Toque no botão de abrir/fechar a loja")
            ProfileStore.PENDING_SELL -> capturePoint(ProfileStore.POINT_SELL, "Toque no CENTRO da área onde o campeão é solto para vender")
            ProfileStore.PENDING_ITEMS -> captureRegion(ProfileStore.REGION_ITEMS, "itens")
            ProfileStore.PENDING_TRAITS -> captureRegion(ProfileStore.REGION_TRAITS, "sinergias")
            ProfileStore.PENDING_CHOICES -> captureRegion(ProfileStore.REGION_CHOICES, "escolhas/aprimoramentos")
            ProfileStore.PENDING_SCREENSHOT_TEST -> testScreenshot()
        }
    }

    private fun captureBoard() {
        removeCaptureOverlay()
        val labels = listOf("A1", "G1", "A2", "G2", "A3", "G3", "A4", "G4")
        val overlay = FrameLayout(this).apply { setBackgroundColor(Color.argb(28, 60, 180, 100)) }
        val hint = hintView("Toque no CENTRO de ${labels.first()}")
        overlay.addView(hint, hintLayoutParams())
        val points = mutableListOf<NormalizedPoint>()

        overlay.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                points += fromPixels(event.rawX, event.rawY)
                if (points.size < labels.size) {
                    hint.text = "Agora toque no CENTRO de ${labels[points.size]}"
                } else {
                    for (rank in 1..4) {
                        val index = (rank - 1) * 2
                        store.saveBoardRow(rank, points[index], points[index + 1])
                    }
                    finishCalibration("Tabuleiro A1–G4 calibrado")
                }
                true
            } else true
        }
        showCaptureOverlay(overlay)
    }

    private fun captureLine(
        title: String,
        firstHint: String,
        secondHint: String,
        save: (NormalizedPoint, NormalizedPoint) -> Unit
    ) {
        removeCaptureOverlay()
        val overlay = FrameLayout(this).apply { setBackgroundColor(Color.argb(24, 70, 120, 210)) }
        val hint = hintView(firstHint)
        overlay.addView(hint, hintLayoutParams())
        var first: NormalizedPoint? = null
        overlay.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                val point = fromPixels(event.rawX, event.rawY)
                if (first == null) {
                    first = point
                    hint.text = secondHint
                } else {
                    save(first!!, point)
                    finishCalibration("$title calibrado")
                }
                true
            } else true
        }
        showCaptureOverlay(overlay)
    }

    private fun capturePoint(name: String, hintText: String) {
        removeCaptureOverlay()
        val overlay = FrameLayout(this).apply { setBackgroundColor(Color.argb(20, 180, 120, 40)) }
        val hint = hintView(hintText)
        overlay.addView(hint, hintLayoutParams())
        overlay.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                store.savePoint(name, fromPixels(event.rawX, event.rawY))
                finishCalibration("Posição salva")
                true
            } else true
        }
        showCaptureOverlay(overlay)
    }

    private fun captureRegion(name: String, label: String) {
        removeCaptureOverlay()
        val overlay = FrameLayout(this).apply { setBackgroundColor(Color.argb(22, 120, 80, 200)) }
        val hint = hintView("Região de $label: toque no canto SUPERIOR ESQUERDO")
        overlay.addView(hint, hintLayoutParams())
        var first: NormalizedPoint? = null
        overlay.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                val point = fromPixels(event.rawX, event.rawY)
                if (first == null) {
                    first = point
                    hint.text = "Agora toque no canto INFERIOR DIREITO"
                } else {
                    val a = first!!
                    store.saveRegion(name, NormalizedRect(min(a.x, point.x), min(a.y, point.y), max(a.x, point.x), max(a.y, point.y)))
                    finishCalibration("Região de $label salva")
                }
                true
            } else true
        }
        showCaptureOverlay(overlay)
    }

    private fun finishCalibration(text: String) {
        handler.post {
            removeCaptureOverlay()
            message(text)
            scheduleContinuousRestart(500)
        }
    }

    private fun testScreenshot() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            store.visionStatus = "Screenshot via acessibilidade exige Android 11+."
            message(store.visionStatus)
            return
        }

        store.visionStatus = "Testando captura..."
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                    val buffer = screenshot.hardwareBuffer
                    store.visionStatus = "OK: TFT capturável pela acessibilidade (${buffer.width}×${buffer.height})."
                    buffer.close()
                    message("Visão: captura OK")
                }

                override fun onFailure(errorCode: Int) {
                    store.visionStatus = "Falhou a captura do TFT. Código Android: $errorCode"
                    message("Visão: captura falhou ($errorCode)")
                }
            }
        )
    }

    private fun tapStoredPoint(name: String, label: String) {
        val point = store.getPoint(name)
        if (point == null) message("Calibre $label primeiro") else tap(point)
    }

    private fun dragOrExplain(from: NormalizedPoint?, to: NormalizedPoint?, text: String) {
        if (from == null || to == null) message(text) else drag(from, to)
    }

    private fun tapSequence(points: List<NormalizedPoint>, index: Int = 0) {
        if (index >= points.size) return
        tap(points[index]) {
            handler.postDelayed({ tapSequence(points, index + 1) }, 110)
        }
    }

    private fun tap(point: NormalizedPoint, after: (() -> Unit)? = null) {
        val (x, y) = toPixels(point)
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 65))
            .build()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                after?.invoke()
            }
        }, null)
    }

    private fun drag(from: NormalizedPoint, to: NormalizedPoint) {
        val (x1, y1) = toPixels(from)
        val (x2, y2) = toPixels(to)
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 480))
            .build()
        dispatchGesture(gesture, null, null)
    }

    private fun showMicrophoneOverlay() {
        if (micView != null) return
        val size = dp(56)
        val view = TextView(this).apply {
            text = "🎙"
            textSize = 27f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(205, 35, 38, 46))
            elevation = dp(8).toFloat()
        }

        val params = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = resources.displayMetrics.widthPixels - size - dp(10)
            y = resources.displayMetrics.heightPixels / 3
        }

        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY; startX = params.x; startY = params.y; moved = false; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (abs(dx) > dp(7) || abs(dy) > dp(7)) moved = true
                    params.x = (startX + dx).toInt().coerceIn(0, max(0, resources.displayMetrics.widthPixels - size))
                    params.y = (startY + dy).toInt().coerceIn(0, max(0, resources.displayMetrics.heightPixels - size))
                    windowManager.updateViewLayout(view, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) activateController()
                    true
                }
                else -> false
            }
        }
        windowManager.addView(view, params)
        micView = view
    }

    private fun showMicTemporary(value: String, durationMs: Long = 2200L) {
        handler.removeCallbacks(resetMic)
        micView?.apply {
            text = value
            textSize = if (value.length <= 2) 22f else 9f
        }
        handler.postDelayed(resetMic, durationMs)
    }

    private fun showCaptureOverlay(view: View) {
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        windowManager.addView(view, params)
        captureOverlay = view
    }

    private fun removeCaptureOverlay() {
        captureOverlay?.let { runCatching { windowManager.removeView(it) } }
        captureOverlay = null
    }

    private fun hintView(value: String) = TextView(this).apply {
        text = value
        textSize = 16f
        setTextColor(Color.WHITE)
        setBackgroundColor(Color.argb(235, 20, 22, 28))
        setPadding(dp(14), dp(10), dp(14), dp(10))
    }

    private fun hintLayoutParams() = FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.WRAP_CONTENT,
        FrameLayout.LayoutParams.WRAP_CONTENT
    ).apply {
        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        topMargin = dp(28)
    }

    private fun toPixels(point: NormalizedPoint): Pair<Float, Float> {
        val metrics = resources.displayMetrics
        return point.x * metrics.widthPixels to point.y * metrics.heightPixels
    }

    private fun fromPixels(x: Float, y: Float): NormalizedPoint {
        val metrics = resources.displayMetrics
        return NormalizedPoint(
            (x / metrics.widthPixels).coerceIn(0f, 1f),
            (y / metrics.heightPixels).coerceIn(0f, 1f)
        )
    }

    private fun message(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
