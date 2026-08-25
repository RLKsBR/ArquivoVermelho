package com.rlks.voicecontroller

import android.Manifest
import android.accessibilityservice.AccessibilityButtonController
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
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
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class VoiceAccessibilityService : AccessibilityService() {
    private lateinit var store: ProfileStore
    private lateinit var windowManager: WindowManager
    private val handler = Handler(Looper.getMainLooper())

    private var micView: TextView? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var captureOverlay: View? = null
    private var listening = false
    private var visionBusy = false
    private var ttsReady = false
    private var textToSpeech: TextToSpeech? = null
    private lateinit var screenTextReader: ScreenTextReader
    private var accessibilityButtonCallback: AccessibilityButtonController.AccessibilityButtonCallback? = null

    private val resetMic = Runnable {
        if (!listening) micView?.apply { text = "🎙"; textSize = 27f }
    }

    private val restartListening = Runnable {
        if (::store.isInitialized && store.continuousMode && !listening && captureOverlay == null &&
            store.pendingCalibration == ProfileStore.PENDING_NONE && isTftForeground() &&
            !visionBusy && textToSpeech?.isSpeaking != true) {
            startListening()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        store = ProfileStore(this)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        AppNotifications.createChannels(this)
        screenTextReader = ScreenTextReader()
        initializeTextToSpeech()
        createSpeechRecognizer()
        showMicrophoneOverlay()
        registerSystemAccessibilityButton()
        message("Voice Controller TFT pronto")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event?.packageName?.toString()?.let { store.foregroundPackage = it }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        handler.removeCallbacks(resetMic)
        handler.removeCallbacks(restartListening)
        accessibilityButtonCallback?.let { callback ->
            runCatching { accessibilityButtonController.unregisterAccessibilityButtonCallback(callback) }
        }
        accessibilityButtonCallback = null
        removeCaptureOverlay()
        AppNotifications.clearCalibration(this)
        micView?.let { runCatching { windowManager.removeView(it) } }
        micView = null
        speechRecognizer?.destroy()
        speechRecognizer = null
        if (::screenTextReader.isInitialized) screenTextReader.close()
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        textToSpeech = null
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
            if (!isTftForeground()) {
                message("Abra o TFT antes de iniciar esta etapa")
                return
            }
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
            AppNotifications.showError(this, "Reconhecimento de voz indisponível neste aparelho.")
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
                    if (error != SpeechRecognizer.ERROR_NO_MATCH && error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                        AppNotifications.showError(this@VoiceAccessibilityService, "Reconhecimento de voz: $description")
                    }
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
        if (visionBusy || textToSpeech?.isSpeaking == true) {
            message("Aguarde a leitura terminar")
            return
        }
        if (!isTftForeground()) {
            message("Comandos bloqueados: o TFT não está em primeiro plano")
            return
        }
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
        val safeOutsideGame = command == VoiceCommand.PauseControl ||
            command == VoiceCommand.ResumeControl || command == VoiceCommand.Help ||
            command == VoiceCommand.RepeatLastRead
        if (!safeOutsideGame && !isTftForeground()) {
            message("Comando bloqueado: o TFT não está em primeiro plano")
            return
        }
        val (displayWidth, displayHeight) = displaySize()
        val needsGeometry = !safeOutsideGame &&
            !(command is VoiceCommand.ReadScreen && command.target == ScreenReadTarget.FULL_SCREEN)
        if (needsGeometry &&
            !store.isCalibrationGeometryCurrent(displayWidth, displayHeight, displayRotation())
        ) {
            message("A tela mudou de tamanho ou orientação. Recalibre antes de executar comandos")
            return
        }

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
            is VoiceCommand.ReadScreen -> readScreen(command.target)
            VoiceCommand.RepeatLastRead -> speakLastRead()
            VoiceCommand.PauseControl -> {
                store.continuousMode = false
                handler.removeCallbacks(restartListening)
                message("Controle contínuo pausado")
            }
            VoiceCommand.ResumeControl -> {
                store.continuousMode = true
                message("Controle contínuo ativado")
            }
            VoiceCommand.Help -> message("Ex.: ler loja • ler itens • rolar • comprar três • banco dois para D4")
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
        is VoiceCommand.ReadScreen -> "LER ${command.target.spokenName.uppercase()}"
        VoiceCommand.RepeatLastRead -> "REPETIR"
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
            "vender banco um", "vender banco dois", "vender banco três",
            "ler loja", "ler itens", "ler sinergias", "ler escolhas", "ler aprimoramentos",
            "ler tela", "ler recompensas", "ler orbes", "repetir leitura"
        )
        val ranks = listOf("um", "dois", "três", "quatro")
        for (file in 'A'..'G') {
            for (rank in ranks) values += "$file $rank"
        }
        return values
    }

    private fun performPendingCalibration(type: String) {
        when (type) {
            ProfileStore.PENDING_ALL, ProfileStore.PENDING_CORE -> captureCore()
            ProfileStore.PENDING_BOARD -> captureBoard()
            ProfileStore.PENDING_BENCH -> captureLine(
                "Banco", "Toque no CENTRO do banco 1", "Agora toque no CENTRO do banco 9"
            ) { first, last -> store.saveBenchLine(first, last) }
            ProfileStore.PENDING_SHOP -> captureLine(
                "Loja", "Toque no CENTRO da carta 1 da loja", "Agora toque no CENTRO da carta 5"
            ) { first, last -> store.saveShopLine(first, last) }
            ProfileStore.PENDING_REROLL -> capturePoint(ProfileStore.POINT_REROLL, "Toque no botão ROLAR")
            ProfileStore.PENDING_XP -> capturePoint(ProfileStore.POINT_XP, "Toque no botão de XP")
            ProfileStore.PENDING_SHOP_TOGGLE -> capturePoint(ProfileStore.POINT_SHOP_TOGGLE, "Toque no botão de abrir/fechar a loja")
            ProfileStore.PENDING_SELL -> capturePoint(ProfileStore.POINT_SELL, "Toque no CENTRO da área onde o campeão é solto para vender")
            ProfileStore.PENDING_ITEMS -> captureRegion(ProfileStore.REGION_ITEMS, "itens", guidedNext(type))
            ProfileStore.PENDING_TRAITS -> captureRegion(ProfileStore.REGION_TRAITS, "sinergias", guidedNext(type))
            ProfileStore.PENDING_CHOICES -> captureRegion(ProfileStore.REGION_CHOICES, "escolhas/aprimoramentos", guidedNext(type))
            ProfileStore.PENDING_SCREENSHOT_TEST -> testScreenshot()
            else -> store.pendingCalibration = ProfileStore.PENDING_NONE
        }
    }

    private fun captureCore() {
        removeCaptureOverlay()
        val steps = listOf(
            "Toque no CENTRO de A1", "Toque no CENTRO de G1",
            "Toque no CENTRO de A2", "Toque no CENTRO de G2",
            "Toque no CENTRO de A3", "Toque no CENTRO de G3",
            "Toque no CENTRO de A4", "Toque no CENTRO de G4",
            "Toque no CENTRO do banco 1", "Toque no CENTRO do banco 9",
            "Toque no CENTRO da carta 1 da loja", "Toque no CENTRO da carta 5 da loja",
            "Toque no botão ROLAR", "Toque no botão de XP",
            "Toque no botão de abrir/fechar a loja", "Toque no CENTRO da área de venda"
        )
        fun instruction(index: Int) = "${index + 1}/${steps.size} — ${steps[index]}"

        val overlay = FrameLayout(this).apply { setBackgroundColor(Color.argb(28, 60, 180, 100)) }
        val hint = hintView(instruction(0))
        overlay.addView(hint, hintLayoutParams())
        AppNotifications.showCalibration(this, hint.text.toString())
        val points = mutableListOf<NormalizedPoint>()

        addCalibrationControls(
            overlay,
            onBack = {
                if (points.isNotEmpty()) {
                    points.removeAt(points.lastIndex)
                    hint.text = instruction(points.size)
                    AppNotifications.showCalibration(this, hint.text.toString())
                } else {
                    Toast.makeText(this, "Você já está no primeiro passo", Toast.LENGTH_SHORT).show()
                }
            },
            onCancel = { cancelCalibration("Calibração cancelada; as coordenadas anteriores foram preservadas") }
        )

        overlay.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                points += fromPixels(event.rawX, event.rawY)
                if (points.size < steps.size) {
                    hint.text = instruction(points.size)
                    AppNotifications.showCalibration(this, hint.text.toString())
                } else {
                    val boardRows = (1..4).associateWith { rank ->
                        val index = (rank - 1) * 2
                        TftRow(points[index], points[index + 1])
                    }
                    val core = TftCoreCalibration(
                        boardRows, TftLine(points[8], points[9]), TftLine(points[10], points[11]),
                        points[12], points[13], points[14], points[15]
                    )
                    val (width, height) = displaySize()
                    val saved = store.saveCoreCalibration(core, width, height, displayRotation())
                    val next = when {
                        !saved -> ProfileStore.PENDING_CORE
                        store.calibrationWizardActive -> CalibrationFlow.next(ProfileStore.PENDING_CORE)
                        else -> ProfileStore.PENDING_NONE
                    }
                    finishCalibration(
                        if (saved) "Calibração principal salva no app" else "Falha ao salvar a calibração principal",
                        next
                    )
                }
                true
            } else true
        }
        showCaptureOverlay(overlay)
    }

    private fun captureBoard() {
        removeCaptureOverlay()
        val labels = listOf("A1", "G1", "A2", "G2", "A3", "G3", "A4", "G4")
        val overlay = FrameLayout(this).apply { setBackgroundColor(Color.argb(28, 60, 180, 100)) }
        val hint = hintView("Toque no CENTRO de ${labels.first()}")
        overlay.addView(hint, hintLayoutParams())
        AppNotifications.showCalibration(this, hint.text.toString())
        val points = mutableListOf<NormalizedPoint>()

        overlay.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                points += fromPixels(event.rawX, event.rawY)
                if (points.size < labels.size) {
                    hint.text = "Agora toque no CENTRO de ${labels[points.size]}"
                    AppNotifications.showCalibration(this, hint.text.toString())
                } else {
                    val rows = (1..4).associateWith { rank ->
                        val index = (rank - 1) * 2
                        TftRow(points[index], points[index + 1])
                    }
                    val saved = store.saveBoardRows(rows)
                    finishCalibration(
                        if (saved) "Tabuleiro A1–G4 calibrado e salvo no app"
                        else "Falha ao salvar o tabuleiro"
                    )
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
        save: (NormalizedPoint, NormalizedPoint) -> Boolean
    ) {
        removeCaptureOverlay()
        val overlay = FrameLayout(this).apply { setBackgroundColor(Color.argb(24, 70, 120, 210)) }
        val hint = hintView(firstHint)
        overlay.addView(hint, hintLayoutParams())
        AppNotifications.showCalibration(this, hint.text.toString())
        var first: NormalizedPoint? = null
        overlay.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                val point = fromPixels(event.rawX, event.rawY)
                if (first == null) {
                    first = point
                    hint.text = secondHint
                    AppNotifications.showCalibration(this, hint.text.toString())
                } else {
                    val saved = save(first!!, point)
                    finishCalibration(
                        if (saved) "$title calibrado e salvo no app"
                        else "Falha ao salvar $title"
                    )
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
        AppNotifications.showCalibration(this, hint.text.toString())
        overlay.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                val saved = store.savePoint(name, fromPixels(event.rawX, event.rawY))
                finishCalibration(if (saved) "Posição salva no app" else "Falha ao salvar a posição")
                true
            } else true
        }
        showCaptureOverlay(overlay)
    }

    private fun captureRegion(name: String, label: String, nextPending: String = ProfileStore.PENDING_NONE) {
        removeCaptureOverlay()
        val overlay = FrameLayout(this).apply { setBackgroundColor(Color.argb(22, 120, 80, 200)) }
        val firstText = "Região de $label: toque no canto SUPERIOR ESQUERDO"
        val hint = hintView(firstText)
        overlay.addView(hint, hintLayoutParams())
        AppNotifications.showCalibration(this, hint.text.toString())
        var first: NormalizedPoint? = null

        addCalibrationControls(
            overlay,
            onBack = {
                first = null
                hint.text = firstText
                AppNotifications.showCalibration(this, hint.text.toString())
            },
            onCancel = { cancelCalibration("Calibração cancelada; as coordenadas anteriores foram preservadas") }
        )

        overlay.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                val point = fromPixels(event.rawX, event.rawY)
                if (first == null) {
                    first = point
                    hint.text = "Agora toque no canto INFERIOR DIREITO"
                    AppNotifications.showCalibration(this, hint.text.toString())
                } else {
                    val firstPoint = first!!
                    val saved = store.saveRegion(
                        name,
                        NormalizedRect(
                            min(firstPoint.x, point.x), min(firstPoint.y, point.y),
                            max(firstPoint.x, point.x), max(firstPoint.y, point.y)
                        )
                    )
                    finishCalibration(
                        if (saved) "Região de $label salva no app" else "Falha ao salvar a região de $label",
                        if (saved) nextPending else store.pendingCalibration
                    )
                }
                true
            } else true
        }
        showCaptureOverlay(overlay)
    }

    private fun guidedNext(current: String): String =
        if (store.calibrationWizardActive) CalibrationFlow.next(current) else ProfileStore.PENDING_NONE

    private fun finishCalibration(text: String, nextPending: String = ProfileStore.PENDING_NONE) {
        handler.post {
            removeCaptureOverlay()
            store.pendingCalibration = nextPending
            if (nextPending == ProfileStore.PENDING_NONE) {
                val completedWizard = store.calibrationWizardActive
                store.calibrationWizardActive = false
                AppNotifications.clearCalibration(this)
                message(if (completedWizard) "$text. Calibração guiada concluída" else text)
            } else {
                message(text)
                AppNotifications.showCalibration(this, CalibrationFlow.prompt(nextPending))
            }
            scheduleContinuousRestart(500)
        }
    }

    private fun cancelCalibration(text: String) {
        store.pendingCalibration = ProfileStore.PENDING_NONE
        store.calibrationWizardActive = false
        removeCaptureOverlay()
        AppNotifications.clearCalibration(this)
        message(text)
    }

    private fun initializeTextToSpeech() {
        textToSpeech = TextToSpeech(this) { status ->
            if (status != TextToSpeech.SUCCESS) {
                ttsReady = false
                AppNotifications.showError(this, "A voz do Android não pôde ser iniciada.")
            } else {
                val engine = textToSpeech
                if (engine == null) {
                    ttsReady = false
                    AppNotifications.showError(this, "A voz do Android não ficou disponível.")
                } else {
                    val languageResult = engine.setLanguage(Locale("pt", "BR"))
                    ttsReady = languageResult != TextToSpeech.LANG_MISSING_DATA &&
                        languageResult != TextToSpeech.LANG_NOT_SUPPORTED
                    engine.setSpeechRate(0.92f)
                    engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) = Unit

                        override fun onDone(utteranceId: String?) {
                            handler.post { releaseVisionAndResume() }
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {
                            handler.post {
                                AppNotifications.showError(
                                    this@VoiceAccessibilityService,
                                    "A leitura foi encontrada, mas a voz do Android falhou."
                                )
                                releaseVisionAndResume()
                            }
                        }
                    })
                    if (!ttsReady) {
                        AppNotifications.showError(
                            this,
                            "A voz em português não está instalada no Android."
                        )
                    }
                }
            }
        }
    }

    private fun readScreen(target: ScreenReadTarget) {
        if (visionBusy) {
            message("Aguarde: uma leitura ainda está em andamento")
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            message("Leitura da tela exige Android 11 ou superior")
            return
        }
        val region = regionFor(target) ?: run {
            val missing = when (target) {
                ScreenReadTarget.SHOP -> "Calibre a loja antes de pedir a leitura"
                ScreenReadTarget.ITEMS -> "Marque a região dos itens antes de pedir a leitura"
                ScreenReadTarget.TRAITS -> "Marque a região das sinergias antes de pedir a leitura"
                ScreenReadTarget.CHOICES -> "Marque a região das escolhas antes de pedir a leitura"
                ScreenReadTarget.FULL_SCREEN -> "Não foi possível definir a tela"
            }
            message(missing)
            return
        }

        visionBusy = true
        listening = false
        handler.removeCallbacks(restartListening)
        showMicTemporary("LENDO", 12_000)
        AppNotifications.showStatus(this, "Lendo ${target.spokenName.lowercase()} do TFT...")

        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                    val fullBitmap = bitmapFromScreenshot(screenshot)
                    if (fullBitmap == null) {
                        failVisionRead("A captura chegou, mas não pôde ser convertida em imagem")
                        return
                    }
                    val regionBitmap = cropBitmap(fullBitmap, region)
                    if (regionBitmap !== fullBitmap) fullBitmap.recycle()
                    if (regionBitmap == null) {
                        failVisionRead("A região calibrada ficou fora da imagem")
                        return
                    }

                    screenTextReader.read(
                        bitmap = regionBitmap,
                        onSuccess = { rawText ->
                            if (store.saveReadingScreenshots) {
                                runCatching {
                                    ScreenshotStore.save(
                                        this@VoiceAccessibilityService,
                                        regionBitmap,
                                        category = target.name,
                                        saveAsOcrSample = true
                                    )
                                }.onSuccess { uri ->
                                    store.lastScreenshotUri = uri.toString()
                                }.onFailure {
                                    AppNotifications.showError(
                                        this@VoiceAccessibilityService,
                                        "A leitura funcionou, mas a amostra não foi salva."
                                    )
                                }
                            }
                            regionBitmap.recycle()
                            val clean = rawText.trim()
                            val readout = if (clean.isBlank()) {
                                "${target.spokenName}. Não encontrei texto legível nessa região."
                            } else {
                                "${target.spokenName}. $clean"
                            }
                            store.lastReadText = readout
                            AppNotifications.showStatus(
                                this@VoiceAccessibilityService,
                                readout.take(900)
                            )
                            speakReadout(readout)
                        },
                        onFailure = { error ->
                            regionBitmap.recycle()
                            failVisionRead(
                                "Falha ao reconhecer o texto: ${error.message ?: error.javaClass.simpleName}"
                            )
                        }
                    )
                }

                override fun onFailure(errorCode: Int) {
                    failVisionRead("O Android recusou a screenshot. Código $errorCode")
                }
            }
        )
    }

    private fun speakLastRead() {
        if (visionBusy) {
            message("Aguarde: uma leitura ainda está em andamento")
            return
        }
        val text = store.lastReadText
        if (text.isBlank() || text == "Nenhuma leitura feita ainda.") {
            message("Nenhuma leitura anterior para repetir")
            return
        }
        visionBusy = true
        handler.removeCallbacks(restartListening)
        speakReadout(text)
    }

    private fun speakReadout(text: String) {
        if (!ttsReady) {
            message("Leitura concluída, mas a voz em português não está disponível")
            releaseVisionAndResume()
            return
        }
        val engine = textToSpeech
        if (engine == null) {
            message("A voz do Android não está disponível")
            releaseVisionAndResume()
            return
        }
        val result = engine.speak(
            text.take(TextToSpeech.getMaxSpeechInputLength()),
            TextToSpeech.QUEUE_FLUSH,
            null,
            "tft_read_${System.currentTimeMillis()}"
        )
        if (result == TextToSpeech.ERROR) {
            message("A voz do Android não conseguiu falar a leitura")
            releaseVisionAndResume()
        }
    }

    private fun regionFor(target: ScreenReadTarget): NormalizedRect? = when (target) {
        ScreenReadTarget.SHOP -> VisionRegion.shopFromLine(store.getShopLine())
        ScreenReadTarget.ITEMS -> store.getRegion(ProfileStore.REGION_ITEMS)
        ScreenReadTarget.TRAITS -> store.getRegion(ProfileStore.REGION_TRAITS)
        ScreenReadTarget.CHOICES -> store.getRegion(ProfileStore.REGION_CHOICES)
        ScreenReadTarget.FULL_SCREEN -> NormalizedRect(0f, 0f, 1f, 1f)
    }

    private fun bitmapFromScreenshot(
        screenshot: AccessibilityService.ScreenshotResult
    ): Bitmap? {
        val buffer = screenshot.hardwareBuffer
        return try {
            val hardwareBitmap = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
            val softwareBitmap = hardwareBitmap?.copy(Bitmap.Config.ARGB_8888, false)
            hardwareBitmap?.recycle()
            softwareBitmap
        } finally {
            buffer.close()
        }
    }

    private fun cropBitmap(source: Bitmap, normalized: NormalizedRect): Bitmap? {
        val rect = VisionRegion.clamp(normalized)
        val left = (rect.left * source.width).roundToInt().coerceIn(0, source.width - 1)
        val top = (rect.top * source.height).roundToInt().coerceIn(0, source.height - 1)
        val right = (rect.right * source.width).roundToInt().coerceIn(left + 1, source.width)
        val bottom = (rect.bottom * source.height).roundToInt().coerceIn(top + 1, source.height)
        if (right <= left || bottom <= top) return null
        return Bitmap.createBitmap(source, left, top, right - left, bottom - top)
    }

    private fun failVisionRead(text: String) {
        store.lastReadText = text
        message(text)
        releaseVisionAndResume()
    }

    private fun releaseVisionAndResume() {
        visionBusy = false
        showMicTemporary("🎙")
        scheduleContinuousRestart(650)
    }

    private fun testScreenshot() {
        AppNotifications.clearCalibration(this)
        AppNotifications.showStatus(this, "Testando a imagem capturada do TFT...")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            store.pendingCalibration = ProfileStore.PENDING_NONE
            store.visionStatus = "Screenshot via acessibilidade exige Android 11+."
            message(store.visionStatus)
            return
        }

        store.visionStatus = "Testando captura e conteúdo da imagem..."
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                    val buffer = screenshot.hardwareBuffer
                    val hardwareBitmap = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                    val bitmap = hardwareBitmap?.copy(Bitmap.Config.ARGB_8888, false)
                    buffer.close()
                    hardwareBitmap?.recycle()
                    store.pendingCalibration = ProfileStore.PENDING_NONE

                    if (bitmap == null) {
                        store.visionStatus = "A captura chegou, mas não pôde ser convertida em imagem."
                        message("Visão: falha ao converter a captura")
                        return
                    }

                    val sample = Bitmap.createScaledBitmap(bitmap, 160, 90, true)
                    val pixels = IntArray(sample.width * sample.height)
                    sample.getPixels(pixels, 0, sample.width, 0, 0, sample.width, sample.height)
                    val stats = VisionAnalyzer.analyze(pixels)
                    if (sample !== bitmap) sample.recycle()

                    val savedUri = runCatching {
                        ScreenshotStore.save(this@VoiceAccessibilityService, bitmap)
                    }.getOrNull()
                    bitmap.recycle()
                    if (savedUri != null) store.lastScreenshotUri = savedUri.toString()

                    val metrics = "brilho %.1f, variação %.1f".format(
                        stats.meanLuminance, stats.standardDeviation
                    )
                    store.visionStatus = when {
                        savedUri == null -> "Imagem recebida (${stats.sampleCount} amostras; $metrics), mas falhou ao salvar em Fotos."
                        stats.isUsable -> "OK: o TFT gerou uma imagem visível ($metrics). Salva em Pictures/Voice Controller/TFT Captures."
                        else -> "Captura salva, mas a imagem parece preta ou sem detalhes ($metrics)."
                    }
                    message(
                        if (stats.isUsable && savedUri != null) "Visão: imagem do TFT capturada e salva"
                        else store.visionStatus
                    )
                }

                override fun onFailure(errorCode: Int) {
                    store.pendingCalibration = ProfileStore.PENDING_NONE
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
        val accepted = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                after?.invoke()
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                AppNotifications.showError(this@VoiceAccessibilityService, "O Android cancelou o toque solicitado.")
            }
        }, null)
        if (!accepted) AppNotifications.showError(this, "O Android recusou o toque solicitado.")
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
        val accepted = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCancelled(gestureDescription: GestureDescription?) {
                AppNotifications.showError(this@VoiceAccessibilityService, "O Android cancelou o arrasto solicitado.")
            }
        }, null)
        if (!accepted) AppNotifications.showError(this, "O Android recusou o arrasto solicitado.")
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

    private fun addCalibrationControls(
        overlay: FrameLayout,
        onBack: () -> Unit,
        onCancel: () -> Unit
    ) {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setBackgroundColor(Color.argb(225, 20, 22, 28))
        }
        val child = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        bar.addView(Button(this).apply {
            text = "Voltar um passo"
            isAllCaps = false
            setOnClickListener { onBack() }
        }, child)
        bar.addView(Button(this).apply {
            text = "Cancelar"
            isAllCaps = false
            setOnClickListener { onCancel() }
        }, child)
        overlay.addView(
            bar,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.BOTTOM }
        )
    }

    private fun isTftForeground(): Boolean =
        store.foregroundPackage == TFT_PACKAGE || store.foregroundPackage == TFT_PBE_PACKAGE

    private fun displaySize(): Pair<Int, Int> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager.currentWindowMetrics.bounds.let { it.width() to it.height() }
        } else {
            resources.displayMetrics.let { it.widthPixels to it.heightPixels }
        }

    private fun displayRotation(): Int = display?.rotation ?: Surface.ROTATION_0

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
        val (width, height) = displaySize()
        return point.x * width to point.y * height
    }

    private fun fromPixels(x: Float, y: Float): NormalizedPoint {
        val (width, height) = displaySize()
        return NormalizedPoint(
            (x / width).coerceIn(0f, 1f),
            (y / height).coerceIn(0f, 1f)
        )
    }

    private fun message(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
        val normalized = text.lowercase()
        val isProblem = listOf("falha", "falhou", "erro", "indisponível", "calibre", "recalibre", "marque", "permita", "exige", "preta", "bloqueado")
            .any { it in normalized }
        if (isProblem) AppNotifications.showError(this, text)
        else AppNotifications.showStatus(this, text)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val TFT_PACKAGE = "com.riotgames.league.teamfighttactics"
        private const val TFT_PBE_PACKAGE = "com.riotgames.league.teamfighttactics.pbe"
    }
}
