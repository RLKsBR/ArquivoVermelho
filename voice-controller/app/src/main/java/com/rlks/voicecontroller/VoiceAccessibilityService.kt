package com.rlks.voicecontroller

import android.Manifest
import android.annotation.SuppressLint
import android.accessibilityservice.AccessibilityButtonController
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class VoiceAccessibilityService : AccessibilityService() {
    private lateinit var store: ProfileStore
    private lateinit var windowManager: WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private val visionExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private var micView: TextView? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var captureOverlay: View? = null
    private var listening = false
    private var visionBusy = false
    private var ttsReady = false
    private var gameMonitorBusy = false
    private var gameMonitorScheduled = false
    private var gameMonitorDueAt = 0L
    private var lastRoundKey: String? = null
    private var activeSelection: SelectionScreen? = null
    private var selectionMissingScans = 0
    private var selectionReading = false
    private var selectionWarningFired = false
    private var textToSpeech: TextToSpeech? = null
    private var warningTone: ToneGenerator? = null
    private lateinit var screenTextReader: ScreenTextReader
    private lateinit var calibrationManager: CalibrationManager
    private val gameStateRepository = GameStateRepository()
    private val captureCoordinator = CaptureCoordinator()
    private var lastGameFrameFingerprint: Long? = null
    private var lastGameFrameChanged = true
    private var lastGameOcrAt = 0L
    private var consecutiveCaptureFailures = 0
    private var pendingSensitiveCommand: VoiceCommand? = null
    private var pendingSensitiveUntil = 0L
    private var gestureBusy = false
    private var accessibilityButtonCallback: AccessibilityButtonController.AccessibilityButtonCallback? = null

    private val resetMic = Runnable {
        if (!listening) micView?.apply {
            text = "🎙"
            textSize = 27f
            contentDescription = "Ativar o microfone do Voice Controller"
        }
    }

    private val restartListening = Runnable {
        if (::store.isInitialized && store.continuousMode && !listening && captureOverlay == null &&
            store.pendingCalibration == ProfileStore.PENDING_NONE && isTftForeground() &&
            (!visionBusy || selectionReading) &&
            (textToSpeech?.isSpeaking != true || selectionReading)) {
            startListening()
        }
    }

    private val gameMonitorRunnable = Runnable {
        gameMonitorScheduled = false
        gameMonitorDueAt = 0L
        scanGameFlow()
    }

    private val selectionWarningRunnable = Runnable {
        if (activeSelection != null && !selectionWarningFired) {
            selectionWarningFired = true
            playSelectionWarning()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        store = ProfileStore(this)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        AppNotifications.createChannels(this)
        screenTextReader = ScreenTextReader()
        calibrationManager = CalibrationManager(store)
        initializeTextToSpeech()
        warningTone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 88)
        createSpeechRecognizer()
        showMicrophoneOverlay()
        registerSystemAccessibilityButton()
        message("Voice Controller TFT pronto")
        scheduleGameMonitor(900)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val current = event?.packageName?.toString() ?: return
        if (current == TFT_PACKAGE || current == TFT_PBE_PACKAGE) {
            store.foregroundPackage = current
            scheduleContextScan()
            scheduleGameMonitor(350)
        } else if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            store.foregroundPackage = current
            captureCoordinator.cancelAll()
            gameStateRepository.clearDynamicState()
            pendingSensitiveCommand = null
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        handler.removeCallbacks(resetMic)
        handler.removeCallbacks(restartListening)
        handler.removeCallbacks(gameMonitorRunnable)
        handler.removeCallbacks(selectionWarningRunnable)
        captureCoordinator.cancelAll()
        gameStateRepository.clearDynamicState()
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
        warningTone?.release()
        warningTone = null
        visionExecutor.shutdownNow()
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
                    micView?.apply {
                        text = "●"
                        textSize = 22f
                        contentDescription = "Voice Controller ouvindo"
                    }
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
                    if (selected != null) {
                        val command = selected.second
                        if (!selectionReading || command == VoiceCommand.StopReading ||
                            command is VoiceCommand.ReadChoice || command is VoiceCommand.Choice
                        ) {
                            processCommand(command)
                        }
                    }
                    scheduleContinuousRestart(420)
                }

                override fun onPartialResults(partialResults: Bundle?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
        }
    }

    private fun startListening() {
        if ((visionBusy || textToSpeech?.isSpeaking == true) && !selectionReading) {
            message("Aguarde a leitura terminar")
            return
        }
        if (!isTftForeground() && !isControllerForeground()) {
            message("Abra o TFT ou o Voice Controller antes de falar um comando")
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

    private fun processCommand(command: VoiceCommand, confirmed: Boolean = false) {
        if (command == VoiceCommand.ConfirmAction) {
            val pending = pendingSensitiveCommand
            if (pending == null || SystemClock.elapsedRealtime() > pendingSensitiveUntil) {
                pendingSensitiveCommand = null
                message("Não há ação aguardando confirmação")
            } else {
                pendingSensitiveCommand = null
                processCommand(pending, confirmed = true)
            }
            return
        }
        if (command == VoiceCommand.CancelAction) {
            pendingSensitiveCommand = null
            pendingSensitiveUntil = 0L
            message("Ação cancelada")
            return
        }
        if (!confirmed && store.sensitiveConfirmationEnabled && GestureController.isSensitive(command)) {
            pendingSensitiveCommand = command
            pendingSensitiveUntil = SystemClock.elapsedRealtime() + 8_000L
            speakFact("Confirma ${GestureController.sensitiveDescription(command)}? Diga confirmar ou cancelar.")
            return
        }
        val safeOutsideGame = command == VoiceCommand.PauseControl ||
            command == VoiceCommand.ResumeControl || command == VoiceCommand.Help ||
            command == VoiceCommand.RepeatLastRead ||
            command == VoiceCommand.StopReading ||
            command is VoiceCommand.ReadChoice ||
            command is VoiceCommand.RecordChampion ||
            command is VoiceCommand.HighestHealth ||
            command == VoiceCommand.HighestValue ||
            command is VoiceCommand.ListRole ||
            command is VoiceCommand.ItemRecipeQuery ||
            command is VoiceCommand.ItemsFromComponent ||
            command == VoiceCommand.ListItemCatalog ||
            command is VoiceCommand.RecordActiveSynergies ||
            command == VoiceCommand.NonSynergyChampions ||
            command == VoiceCommand.ClearRoster ||
            command == VoiceCommand.CalibrationStatus ||
            command == VoiceCommand.MissingCalibration ||
            command == VoiceCommand.StopAutoCalibration ||
            command == VoiceCommand.ResumeAutoCalibration ||
            command == VoiceCommand.ConfirmAction || command == VoiceCommand.CancelAction
        if (!safeOutsideGame && !isTftForeground()) {
            message("Comando bloqueado: o TFT não está em primeiro plano")
            return
        }
        val geometry = currentGeometry()
        val unsafe = GestureController.requiredComponents(command).filterNot {
            store.isComponentCalibrationCurrent(it, geometry)
        }
        if (unsafe.isNotEmpty()) {
            val reason = "Calibração insegura para ${spokenList(unsafe.map { it.spokenName })}. Recalibre antes do gesto."
            store.lastGestureBlockedReason = reason
            message(reason)
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
            is VoiceCommand.BenchToTactical -> {
                val from = TftLayout.benchSlotToPoint(command.bench, store.getBenchLine())
                val to = TftLayout.tacticalPoint(command.position, store.getBoardRows())
                dragOrExplain(from, to, "Calibre banco e tabuleiro")
            }
            is VoiceCommand.BoardToTactical -> {
                val rows = store.getBoardRows()
                val from = TftLayout.boardSquareToPoint(command.square, rows)
                val to = TftLayout.tacticalPoint(command.position, rows)
                dragOrExplain(from, to, "Calibre o tabuleiro")
            }
            is VoiceCommand.ReadChoice -> readSelectionChoice(command.index)
            VoiceCommand.StopReading -> stopCurrentReading()
            VoiceCommand.CollectOrbs -> collectOrbs()
            VoiceCommand.AutoCalibrate -> runAutoCalibration(passiveOnly = false)
            VoiceCommand.CalibrationStatus -> speakCalibrationStatus()
            VoiceCommand.MissingCalibration -> speakMissingCalibration()
            VoiceCommand.TestCalibration -> runAutoCalibration(passiveOnly = true)
            VoiceCommand.RecalibrateCurrentScreen -> {
                calibrationManager.reset()
                runAutoCalibration(passiveOnly = false)
            }
            VoiceCommand.StopAutoCalibration -> {
                store.autoContextCalibration = false
                message("Autocalibração pausada")
            }
            VoiceCommand.ResumeAutoCalibration -> {
                store.autoContextCalibration = true
                message("Autocalibração retomada")
                scheduleContextScan()
            }
            VoiceCommand.ConfirmAction, VoiceCommand.CancelAction -> Unit
            is VoiceCommand.ReadScreen -> readScreen(command.target)
            VoiceCommand.RepeatLastRead -> speakLastRead()
            is VoiceCommand.RecordChampion -> recordChampion(command.observation)
            is VoiceCommand.HighestHealth -> answerHighestHealth(command.filter)
            VoiceCommand.HighestValue -> answerHighestValue()
            is VoiceCommand.ListRole -> answerRole(command.role)
            is VoiceCommand.ItemRecipeQuery -> answerItemRecipe(command.itemName)
            is VoiceCommand.ItemsFromComponent -> answerItemsFromComponent(command.componentName)
            VoiceCommand.ListItemCatalog -> speakFact(
                "Itens combináveis: ${spokenList(ItemRecipeBook.recipes.map { it.name })}."
            )
            is VoiceCommand.RecordActiveSynergies -> {
                store.saveActiveSynergies(command.names)
                speakFact("Sinergias ativas registradas: ${spokenList(command.names.toList())}.")
            }
            VoiceCommand.NonSynergyChampions -> answerNonSynergyChampions()
            VoiceCommand.ClearRoster -> {
                store.clearRosterObservations()
                speakFact("Lista de campeões registrada foi apagada.")
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
        is VoiceCommand.BenchToTactical -> "B${command.bench}>POSIÇÃO TÁTICA"
        is VoiceCommand.BoardToTactical -> "${command.square.uppercase()}>POSIÇÃO TÁTICA"
        is VoiceCommand.SellBench -> "VENDER B${command.bench}"
        is VoiceCommand.SellBoard -> "VENDER ${command.square.uppercase()}"
        is VoiceCommand.Choice -> "ESCOLHA ${command.index}"
        is VoiceCommand.ReadChoice -> "LER OPÇÃO ${command.index}"
        VoiceCommand.StopReading -> "PARAR LEITURA"
        VoiceCommand.CollectOrbs -> "PEGAR ORBES"
        VoiceCommand.AutoCalibrate -> "AUTO CALIBRAR"
        VoiceCommand.CalibrationStatus -> "STATUS DA CALIBRAÇÃO"
        VoiceCommand.MissingCalibration -> "FALTA CALIBRAR"
        VoiceCommand.TestCalibration -> "TESTAR CALIBRAÇÃO"
        VoiceCommand.RecalibrateCurrentScreen -> "RECALIBRAR TELA"
        VoiceCommand.StopAutoCalibration -> "PARAR AUTOCALIBRAÇÃO"
        VoiceCommand.ResumeAutoCalibration -> "RETOMAR AUTOCALIBRAÇÃO"
        VoiceCommand.ConfirmAction -> "CONFIRMAR"
        VoiceCommand.CancelAction -> "CANCELAR"
        is VoiceCommand.ReadScreen -> "LER ${command.target.spokenName.uppercase()}"
        VoiceCommand.RepeatLastRead -> "REPETIR"
        is VoiceCommand.RecordChampion -> "REGISTRAR ${command.observation.name.uppercase()}"
        is VoiceCommand.HighestHealth -> "MAIOR VIDA"
        VoiceCommand.HighestValue -> "MAIOR VALOR"
        is VoiceCommand.ListRole -> "LISTAR ${command.role.name}"
        is VoiceCommand.ItemRecipeQuery -> "RECEITA"
        is VoiceCommand.ItemsFromComponent -> "ITENS DO COMPONENTE"
        VoiceCommand.ListItemCatalog -> "CATÁLOGO DE ITENS"
        is VoiceCommand.RecordActiveSynergies -> "REGISTRAR SINERGIAS"
        VoiceCommand.NonSynergyChampions -> "FORA DAS SINERGIAS"
        VoiceCommand.ClearRoster -> "LIMPAR TIME"
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
            "leia um", "leia dois", "leia três", "parar", "parar leitura",
            "pegar orbes", "coletar orbes",
            "auto calibrar", "calibrar automaticamente",
            "status da calibração", "o que falta calibrar", "testar calibração",
            "recalibrar esta tela", "parar autocalibração", "retomar autocalibração",
            "confirmar", "cancelar ação",
            "vender banco um", "vender banco dois", "vender banco três",
            "ler loja", "ler itens", "ler sinergias", "ler escolhas", "ler aprimoramentos",
            "ler tela", "ler recompensas", "ler orbes", "repetir leitura",
            "ler aviso", "por que não pegou", "maior vida", "maior vida sem item",
            "maior vida com item", "maior valor", "listar frontline",
            "registrar campeão vida valor", "ler tabuleiro", "ler inventário", "ler carrossel",
            "quais componentes fazem", "receita de", "quais itens existem",
            "quem não faz parte das sinergias", "registrar sinergias ativas"
        )
        values += listOf(
            "banco um para linha de frente", "banco dois para retaguarda",
            "banco três para segunda linha de frente", "linha de frente na esquerda",
            "retaguarda no meio", "linha de frente na direita"
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
            ) { first, last ->
                store.saveLineWithMetadata(
                    CalibrationComponent.BENCH, first, last,
                    manualMetadata(CalibrationComponent.BENCH)
                )
            }
            ProfileStore.PENDING_SHOP -> captureLine(
                "Loja", "Toque no CENTRO da carta 1 da loja", "Agora toque no CENTRO da carta 5"
            ) { first, last ->
                store.saveLineWithMetadata(
                    CalibrationComponent.SHOP, first, last,
                    manualMetadata(CalibrationComponent.SHOP)
                )
            }
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
                    val saved = store.saveBoardRows(rows, manualMetadata(CalibrationComponent.BOARD))
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
                val component = componentForPoint(name)
                val saved = component != null && store.savePointWithMetadata(
                    name,
                    fromPixels(event.rawX, event.rawY),
                    manualMetadata(component)
                )
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
                    val component = componentForRegion(name)
                    val saved = component != null && store.saveRegionWithMetadata(
                        name, NormalizedRect(
                            min(firstPoint.x, point.x), min(firstPoint.y, point.y),
                            max(firstPoint.x, point.x), max(firstPoint.y, point.y)
                        ), manualMetadata(component)
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

    private fun recordChampion(observation: ChampionObservation) {
        val saved = store.saveChampionObservation(observation)
        val items = when (observation.itemStatus) {
            ItemStatus.NONE -> "sem item"
            ItemStatus.EQUIPPED -> "com item"
            ItemStatus.UNKNOWN -> "itens não informados"
        }
        val role = when (observation.role) {
            TacticalRole.FRONTLINE -> "frontline"
            TacticalRole.BACKLINE -> "backline"
            TacticalRole.UNKNOWN -> "função não informada"
        }
        val traits = if (observation.traits.isEmpty()) "sinergias não informadas"
        else "sinergias ${spokenList(observation.traits.toList())}"
        speakFact(
            if (saved) {
                "${observation.name} registrado: ${observation.maxHealth} de vida máxima, valor ${observation.value}, $items, $role, $traits."
            } else {
                "Não consegui salvar ${observation.name}."
            }
        )
    }

    private fun answerHighestHealth(filter: HealthFilter) {
        val champions = RosterAnalyzer.highestHealth(store.getRosterObservations(), filter)
        if (champions.isEmpty()) {
            val criterion = when (filter) {
                HealthFilter.ALL -> ""
                HealthFilter.WITHOUT_ITEMS -> " sem item"
                HealthFilter.WITH_ITEMS -> " com item"
            }
            speakFact("Ainda não há campeões$criterion registrados para comparar.")
            return
        }
        val criterion = when (filter) {
            HealthFilter.ALL -> "registrada"
            HealthFilter.WITHOUT_ITEMS -> "registrada entre campeões sem item"
            HealthFilter.WITH_ITEMS -> "registrada entre campeões com item"
        }
        val names = spokenList(champions.map { it.name })
        speakFact("Maior vida máxima $criterion: $names, com ${champions.first().maxHealth}.")
    }

    private fun answerHighestValue() {
        val champions = RosterAnalyzer.highestValue(store.getRosterObservations())
        if (champions.isEmpty()) {
            speakFact("Ainda não há campeões registrados para comparar o valor.")
            return
        }
        speakFact(
            "Maior valor registrado: ${spokenList(champions.map { it.name })}, valor ${champions.first().value}."
        )
    }

    private fun answerRole(role: TacticalRole) {
        val champions = RosterAnalyzer.byRole(store.getRosterObservations(), role)
        val roleName = if (role == TacticalRole.FRONTLINE) "frontline" else "backline"
        if (champions.isEmpty()) {
            speakFact("Nenhum campeão foi registrado como $roleName.")
        } else {
            speakFact("$roleName: ${spokenList(champions.map { it.name })}.")
        }
    }

    private fun answerItemRecipe(itemName: String) {
        val recipe = ItemRecipeBook.find(itemName)
        if (recipe == null) {
            speakFact("Não encontrei a receita de $itemName no catálogo offline.")
        } else {
            speakFact(ItemRecipeBook.describe(recipe))
        }
    }

    private fun answerItemsFromComponent(componentName: String) {
        val component = ItemRecipeBook.findComponent(componentName)
        if (component == null) {
            speakFact("Não reconheci o componente $componentName.")
            return
        }
        val recipes = ItemRecipeBook.recipesUsing(component)
        speakFact("Com $component você pode fazer: ${spokenList(recipes.map { it.name })}.")
    }

    private fun answerNonSynergyChampions() {
        val active = store.getActiveSynergies()
        if (active.isEmpty()) {
            speakFact("Ainda não há sinergias ativas confirmadas. Diga registrar sinergias ativas e os nomes.")
            return
        }
        val roster = store.getRosterObservations()
        val known = roster.filter { it.traits.isNotEmpty() }
        if (known.isEmpty()) {
            speakFact("Os campeões registrados ainda não têm suas sinergias informadas.")
            return
        }
        val outside = RosterAnalyzer.outsideActiveSynergies(known, active)
        val unknownCount = roster.size - known.size
        val main = if (outside.isEmpty()) {
            "Nenhum campeão com sinergias conhecidas está fora das sinergias ativas."
        } else {
            "Fora das sinergias ativas: ${spokenList(outside.map { it.name })}."
        }
        val caveat = if (unknownCount > 0) " $unknownCount campeão ou campeões ainda não puderam ser avaliados." else ""
        speakFact(main + caveat)
    }

    private fun spokenList(values: List<String>): String = when (values.size) {
        0 -> ""
        1 -> values.first()
        2 -> "${values[0]} e ${values[1]}"
        else -> values.dropLast(1).joinToString(", ") + " e " + values.last()
    }

    private fun speakFact(text: String) {
        store.lastReadText = text
        AppNotifications.showStatus(this, text.take(900))
        visionBusy = true
        handler.removeCallbacks(restartListening)
        speakReadout(text)
    }

    @SuppressLint("NewApi")
    private fun readScreen(target: ScreenReadTarget) {
        if (visionBusy) {
            message("Aguarde: uma leitura ainda está em andamento")
            return
        }
        if (target == ScreenReadTarget.CAROUSEL) {
            readCarouselDynamic()
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
                ScreenReadTarget.BOARD -> "Calibre o tabuleiro antes de pedir a leitura"
                ScreenReadTarget.INVENTORY -> "Não foi possível definir o inventário"
                ScreenReadTarget.CAROUSEL -> "Não foi possível definir a tela do carrossel"
                ScreenReadTarget.NOTICE -> "Não foi possível definir a região dos avisos"
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
                    store.recordScreenshot(true)
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

                    val ocrStartedAt = SystemClock.elapsedRealtime()
                    screenTextReader.read(
                        bitmap = regionBitmap,
                        onSuccess = { rawText ->
                            store.recordOcr(SystemClock.elapsedRealtime() - ocrStartedAt)
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
                            val readout = when {
                                target == ScreenReadTarget.NOTICE -> {
                                    GameNoticeDetector.explain(clean)
                                        ?: "Aviso. Não encontrei uma mensagem legível na tela."
                                }
                                clean.isBlank() -> {
                                    val visualOnly = target in setOf(
                                        ScreenReadTarget.BOARD,
                                        ScreenReadTarget.INVENTORY,
                                        ScreenReadTarget.CAROUSEL
                                    )
                                    if (visualOnly) {
                                        "${target.spokenName}. Não encontrei nomes em texto. A identificação por ícones ainda precisa das amostras visuais desta tela."
                                    } else {
                                        "${target.spokenName}. Não encontrei texto legível nessa região."
                                    }
                                }
                                else -> "${target.spokenName}. $clean"
                            }
                            store.lastReadText = readout
                            AppNotifications.showStatus(
                                this@VoiceAccessibilityService,
                                readout.take(900)
                            )
                            speakReadout(readout)
                        },
                        onFailure = { error ->
                            store.recordOcr(SystemClock.elapsedRealtime() - ocrStartedAt)
                            regionBitmap.recycle()
                            failVisionRead(
                                "Falha ao reconhecer o texto: ${error.message ?: error.javaClass.simpleName}"
                            )
                        }
                    )
                }

                override fun onFailure(errorCode: Int) {
                    store.recordScreenshot(false)
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

    private fun readSelectionChoice(index: Int) {
        val selection = activeSelection
        if (selection == null) {
            message("Nenhuma escolha está aberta agora")
            return
        }
        val option = selection.options.firstOrNull { it.index == index }
        if (option == null) {
            message("A opção $index não foi reconhecida nessa tela")
            return
        }
        textToSpeech?.stop()
        selectionReading = true
        visionBusy = true
        handler.removeCallbacks(restartListening)
        val text = "Opção $index. ${option.text}"
        store.lastReadText = text
        speakReadout(text)
        handler.postDelayed({ if (selectionReading) startListening() }, 450)
    }

    private fun stopCurrentReading() {
        textToSpeech?.stop()
        selectionReading = false
        visionBusy = false
        handler.removeCallbacks(restartListening)
        message("Leitura interrompida")
        scheduleContinuousRestart(300)
    }

    private fun collectOrbs() {
        if (visionBusy) {
            message("Aguarde: uma leitura ainda está em andamento")
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            message("A coleta visual de orbes exige Android 11 ou superior")
            return
        }
        visionBusy = true
        listening = false
        speechRecognizer?.cancel()
        handler.removeCallbacks(restartListening)
        AppNotifications.showStatus(this, "Procurando orbes em duas imagens...")
        captureOrbDetectionFrames(mutableListOf(), 0)
    }

    @SuppressLint("NewApi")
    private fun captureOrbDetectionFrames(
        frames: MutableList<List<NormalizedPoint>>,
        index: Int
    ) {
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                    store.recordScreenshot(true)
                    val bitmap = bitmapFromScreenshot(screenshot)
                    if (bitmap == null) {
                        failVisionRead("A imagem para procurar orbes não pôde ser convertida")
                        return
                    }
                    detectOrbPointsAsync(bitmap) { detectedPoints ->
                        frames += detectedPoints
                        if (index == 0) {
                            handler.postDelayed({ captureOrbDetectionFrames(frames, 1) }, 420L)
                        } else {
                            val confirmed = OrbTemporalTracker.confirm(frames)
                                .filter { it.confidence >= 0.9f }
                                .map { it.point }
                            if (confirmed.isEmpty()) {
                                speakFact("Não confirmei uma orbe em duas imagens. Nenhum toque foi executado.")
                            } else {
                                AppNotifications.showStatus(
                                    this@VoiceAccessibilityService,
                                    "${confirmed.size} orbe ou orbes confirmadas; revalidarei antes de cada toque."
                                )
                                collectOrbSequence(confirmed)
                            }
                        }
                    }
                }

                override fun onFailure(errorCode: Int) {
                    store.recordScreenshot(false)
                    failVisionRead("O Android recusou a imagem para procurar orbes. Código $errorCode")
                }
            }
        )
    }

    private fun runAutoCalibration(passiveOnly: Boolean) {
        if (visionBusy) {
            message("Aguarde: uma leitura ainda está em andamento")
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            message("Auto calibrar exige Android 11 ou superior")
            return
        }
        visionBusy = true
        listening = false
        speechRecognizer?.cancel()
        handler.removeCallbacks(restartListening)
        calibrationManager.reset()
        AppNotifications.showStatus(
            this,
            if (passiveOnly) "Teste passivo: analisando as âncoras sem executar gestos."
            else "Auto calibração: analisando três imagens dos controles visíveis."
        )
        captureAutoCalibrationFrames(passiveOnly, 0, mutableListOf())
    }

    @SuppressLint("NewApi")
    private fun captureAutoCalibrationFrames(
        passiveOnly: Boolean,
        index: Int,
        progress: MutableList<CalibrationProgress>
    ) {
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                    store.recordScreenshot(true)
                    val bitmap = bitmapFromScreenshot(screenshot)
                    if (bitmap == null) {
                        failVisionRead("A imagem da auto calibração não pôde ser convertida")
                        return
                    }
                    val scaledWidth = min(360, bitmap.width)
                    val scaledHeight = max(1, bitmap.height * scaledWidth / bitmap.width)
                    val sample = Bitmap.createScaledBitmap(bitmap, scaledWidth, scaledHeight, true)
                    val pixels = IntArray(sample.width * sample.height)
                    sample.getPixels(pixels, 0, sample.width, 0, 0, sample.width, sample.height)
                    if (sample !== bitmap) sample.recycle()
                    val startedAt = SystemClock.elapsedRealtime()
                    screenTextReader.readDetailed(
                        bitmap,
                        onSuccess = { result ->
                            store.recordOcr(SystemClock.elapsedRealtime() - startedAt)
                            val detected = AutoCalibrationDetector.detect(result.lines)
                            val visual = VisualLayoutDetector.detect(
                                pixels, scaledWidth, scaledHeight, detected.shopLine ?: store.getShopLine()
                            )
                            val geometry = currentGeometry()
                            val frameProgress = if (passiveOnly) {
                                CalibrationProgress(
                                    detected = buildSet {
                                        if (detected.shopLine != null) add(CalibrationComponent.SHOP)
                                        if (detected.reroll != null) add(CalibrationComponent.REROLL)
                                        if (detected.xp != null) add(CalibrationComponent.XP)
                                        if (detected.shopToggle != null) add(CalibrationComponent.SHOP_TOGGLE)
                                        if (detected.sell != null) add(CalibrationComponent.SELL)
                                        if (visual.boardRows != null) add(CalibrationComponent.BOARD)
                                        if (visual.benchLine != null) add(CalibrationComponent.BENCH)
                                        detected.contexts.forEach { add(it.kind.component()) }
                                    },
                                    committed = emptySet(),
                                    awaitingConfirmation = emptySet(),
                                    rejected = buildMap {
                                        if (visual.confidence < CalibrationMetadata.MIN_GESTURE_CONFIDENCE) {
                                            put(CalibrationComponent.BOARD, visual.reason)
                                            put(CalibrationComponent.BENCH, visual.reason)
                                        }
                                    }
                                )
                            } else {
                                calibrationManager.observeAutomaticFrame(detected, visual, geometry)
                            }
                            progress += frameProgress
                            if (store.saveReadingScreenshots &&
                                frameProgress.committed.isEmpty() && index == 2
                            ) {
                                runCatching {
                                    ScreenshotStore.save(
                                        this@VoiceAccessibilityService,
                                        bitmap,
                                        category = "AUTO_CALIBRATION_DIAGNOSTIC",
                                        saveAsOcrSample = true
                                    )
                                }
                            }
                            bitmap.recycle()
                            if (index < 2) {
                                handler.postDelayed(
                                    { captureAutoCalibrationFrames(passiveOnly, index + 1, progress) },
                                    480L
                                )
                            } else {
                                finishAutoCalibration(passiveOnly, progress)
                            }
                        },
                        onFailure = { error ->
                            store.recordOcr(SystemClock.elapsedRealtime() - startedAt)
                            bitmap.recycle()
                            failVisionRead("Auto calibração falhou no OCR: ${error.javaClass.simpleName}")
                        }
                    )
                }

                override fun onFailure(errorCode: Int) {
                    store.recordScreenshot(false)
                    failVisionRead("O Android recusou a imagem da auto calibração. Código $errorCode")
                }
            }
        )
    }

    private fun finishAutoCalibration(
        passiveOnly: Boolean,
        progress: List<CalibrationProgress>
    ) {
        val detected = progress.flatMap { it.detected }.toSet()
        val committed = progress.flatMap { it.committed }.toSet()
        val missing = store.missingOrUnsafeComponents(currentGeometry())
        val readout = when {
            passiveOnly -> {
                val seen = if (detected.isEmpty()) "nenhuma âncora confiável"
                else spokenList(detected.map { it.spokenName })
                "Teste passivo concluído. Encontrei $seen. Nenhum gesto foi executado e nenhuma coordenada foi substituída."
            }
            committed.isEmpty() ->
                "Autocalibração ainda não confirmou componentes em imagens consecutivas. A calibração anterior foi preservada. Pendentes: ${spokenList(missing.map { it.spokenName })}."
            missing.isEmpty() ->
                "Autocalibração confirmada para ${spokenList(committed.map { it.spokenName })}. Todas as partes estão válidas para esta tela."
            else ->
                "Autocalibração confirmou ${spokenList(committed.map { it.spokenName })}. Ainda falta calibrar ou revalidar ${spokenList(missing.map { it.spokenName })}."
        }
        store.autoCalibrationStatus = readout
        speakFact(readout)
    }

    private fun collectOrbSequence(points: List<NormalizedPoint>, index: Int = 0) {
        if (index >= points.size) {
            speakFact("Coleta de orbes concluída. Cada posição foi revalidada.")
            return
        }
        if (!isTftForeground()) {
            failVisionRead("A coleta foi interrompida porque o TFT saiu do primeiro plano")
            return
        }
        captureCurrentOrbs { current ->
            val target = OrbTemporalTracker.nearest(points[index], current)
            if (target == null) {
                collectOrbSequence(points, index + 1)
                return@captureCurrentOrbs
            }
            tap(target) {
                handler.postDelayed({ verifyCollectedOrb(points, index, target) }, 1_600L)
            }
        }
    }

    private fun verifyCollectedOrb(
        points: List<NormalizedPoint>,
        index: Int,
        target: NormalizedPoint
    ) {
        if (!isTftForeground()) {
            failVisionRead("A coleta foi interrompida porque a tela mudou")
            return
        }
        captureCurrentOrbs { current ->
            if (OrbTemporalTracker.nearest(target, current) != null) {
                failVisionRead("A orbe não desapareceu após o toque. Interrompi a sequência para evitar toques incorretos.")
            } else {
                collectOrbSequence(points, index + 1)
            }
        }
    }

    @SuppressLint("NewApi")
    private fun captureCurrentOrbs(onResult: (List<NormalizedPoint>) -> Unit) {
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                    store.recordScreenshot(true)
                    val bitmap = bitmapFromScreenshot(screenshot)
                    if (bitmap == null) {
                        failVisionRead("Falha ao revalidar a imagem das orbes")
                        return
                    }
                    detectOrbPointsAsync(bitmap, onResult)
                }

                override fun onFailure(errorCode: Int) {
                    store.recordScreenshot(false)
                    failVisionRead("Falha ao revalidar orbes. Código $errorCode")
                }
            }
        )
    }

    private fun detectOrbPoints(bitmap: Bitmap): List<NormalizedPoint> {
        val scaledWidth = min(360, bitmap.width)
        val scaledHeight = max(1, bitmap.height * scaledWidth / bitmap.width)
        val sample = Bitmap.createScaledBitmap(bitmap, scaledWidth, scaledHeight, true)
        val pixels = IntArray(sample.width * sample.height)
        sample.getPixels(pixels, 0, sample.width, 0, 0, sample.width, sample.height)
        val board = if (store.isComponentCalibrationCurrent(CalibrationComponent.BOARD, currentGeometry())) {
            VisionRegion.boardFromRows(store.getBoardRows())
        } else null
        val search = board?.let {
            NormalizedRect(
                (it.left - 0.08f).coerceAtLeast(0.03f),
                (it.top - 0.16f).coerceAtLeast(0.12f),
                (it.right + 0.08f).coerceAtMost(0.97f),
                (it.bottom + 0.06f).coerceAtMost(0.84f)
            )
        } ?: NormalizedRect(0.06f, 0.16f, 0.94f, 0.80f)
        val points = OrbDetector.detect(pixels, sample.width, sample.height, search)
        if (sample !== bitmap) sample.recycle()
        return points
    }

    private fun detectOrbPointsAsync(bitmap: Bitmap, onResult: (List<NormalizedPoint>) -> Unit) {
        visionExecutor.execute {
            val points = runCatching { detectOrbPoints(bitmap) }.getOrDefault(emptyList())
            bitmap.recycle()
            handler.post { onResult(points) }
        }
    }

    private fun readCarouselDynamic() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            message("Leitura do carrossel exige Android 11 ou superior")
            return
        }
        visionBusy = true
        listening = false
        speechRecognizer?.cancel()
        handler.removeCallbacks(restartListening)
        showMicTemporary("GIRO", 12_000)
        captureCarouselFrames(mutableListOf(), 0)
    }

    @SuppressLint("NewApi")
    private fun captureCarouselFrames(frames: MutableList<ScreenTextResult>, index: Int) {
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                    store.recordScreenshot(true)
                    val bitmap = bitmapFromScreenshot(screenshot)
                    if (bitmap == null) {
                        failVisionRead("Uma imagem do carrossel não pôde ser convertida")
                        return
                    }
                    val ocrStartedAt = SystemClock.elapsedRealtime()
                    screenTextReader.readDetailed(
                        bitmap,
                        onSuccess = { result ->
                            store.recordOcr(SystemClock.elapsedRealtime() - ocrStartedAt)
                            bitmap.recycle()
                            frames += result
                            if (index < 2) {
                                handler.postDelayed({ captureCarouselFrames(frames, index + 1) }, 520)
                            } else {
                                val readout = CarouselReader.describe(frames)
                                store.lastReadText = readout
                                AppNotifications.showStatus(this@VoiceAccessibilityService, readout.take(900))
                                speakReadout(readout)
                            }
                        },
                        onFailure = { error ->
                            store.recordOcr(SystemClock.elapsedRealtime() - ocrStartedAt)
                            bitmap.recycle()
                            failVisionRead("Falha ao acompanhar o carrossel: ${error.message ?: error.javaClass.simpleName}")
                        }
                    )
                }

                override fun onFailure(errorCode: Int) {
                    store.recordScreenshot(false)
                    failVisionRead("O Android recusou uma imagem do carrossel. Código $errorCode")
                }
            }
        )
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
        ScreenReadTarget.SHOP -> store.getShopLine()
            .takeIf { store.isComponentCalibrationCurrent(CalibrationComponent.SHOP, currentGeometry()) }
            ?.let { VisionRegion.shopFromLine(it) }
        ScreenReadTarget.ITEMS -> store.getRegion(ProfileStore.REGION_ITEMS)
            ?.takeIf { store.isComponentCalibrationCurrent(CalibrationComponent.ITEMS, currentGeometry()) }
            ?: NormalizedRect(0f, 0f, 1f, 1f)
        ScreenReadTarget.TRAITS -> store.getRegion(ProfileStore.REGION_TRAITS)
            ?.takeIf { store.isComponentCalibrationCurrent(CalibrationComponent.TRAITS, currentGeometry()) }
            ?: NormalizedRect(0f, 0f, 1f, 1f)
        ScreenReadTarget.CHOICES -> store.getRegion(ProfileStore.REGION_CHOICES)
            ?.takeIf { store.isComponentCalibrationCurrent(CalibrationComponent.CHOICES, currentGeometry()) }
            ?: NormalizedRect(0f, 0f, 1f, 1f)
        ScreenReadTarget.BOARD -> store.getBoardRows()
            .takeIf { store.isComponentCalibrationCurrent(CalibrationComponent.BOARD, currentGeometry()) }
            ?.let { VisionRegion.boardFromRows(it) }
        ScreenReadTarget.INVENTORY -> store.getRegion(ProfileStore.REGION_ITEMS)
            ?.takeIf { store.isComponentCalibrationCurrent(CalibrationComponent.ITEMS, currentGeometry()) }
            ?: NormalizedRect(0f, 0f, 1f, 1f)
        ScreenReadTarget.CAROUSEL -> NormalizedRect(0f, 0f, 1f, 1f)
        ScreenReadTarget.NOTICE -> NormalizedRect(0f, 0f, 1f, 1f)
        ScreenReadTarget.FULL_SCREEN -> NormalizedRect(0f, 0f, 1f, 1f)
    }

    private fun scheduleContextScan() {
        if (!::store.isInitialized || !store.autoContextCalibration) return
        // Context calibration shares the game-monitor frame instead of starting a second OCR pipeline.
        scheduleGameMonitor(350)
    }

    private fun scheduleGameMonitor(delayMs: Long = 1800L) {
        if (!::store.isInitialized) return
        val dueAt = SystemClock.elapsedRealtime() + delayMs
        if (gameMonitorScheduled && gameMonitorDueAt > 0L && gameMonitorDueAt <= dueAt) return
        if (gameMonitorScheduled) handler.removeCallbacks(gameMonitorRunnable)
        gameMonitorScheduled = true
        gameMonitorDueAt = dueAt
        handler.postDelayed(gameMonitorRunnable, delayMs)
    }

    @SuppressLint("NewApi")
    private fun scanGameFlow() {
        if (!::store.isInitialized || !isTftForeground()) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || gameMonitorBusy ||
            visionBusy || captureOverlay != null ||
            store.pendingCalibration != ProfileStore.PENDING_NONE
        ) {
            scheduleGameMonitor(420)
            return
        }
        if (!captureCoordinator.begin("game_monitor")) {
            store.captureQueueDepth = captureCoordinator.queueDepth()
            scheduleGameMonitor(600)
            return
        }
        store.captureQueueDepth = captureCoordinator.queueDepth()
        gameMonitorBusy = true
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                    store.recordScreenshot(true)
                    val bitmap = bitmapFromScreenshot(screenshot)
                    if (bitmap == null) {
                        finishGameMonitor(failed = true)
                        return
                    }
                    if (!isTftForeground()) {
                        bitmap.recycle()
                        finishGameMonitor()
                        return
                    }
                    visionExecutor.execute {
                        val fingerprint = frameFingerprint(bitmap)
                        handler.post { processGameMonitorBitmap(bitmap, fingerprint) }
                    }
                }

                override fun onFailure(errorCode: Int) {
                    store.recordScreenshot(false)
                    finishGameMonitor(failed = true)
                }
            }
        )
    }

    private fun processGameMonitorBitmap(bitmap: Bitmap, fingerprint: Long) {
        if (!isTftForeground() || bitmap.isRecycled) {
            if (!bitmap.isRecycled) bitmap.recycle()
            finishGameMonitor()
            return
        }
        val now = SystemClock.elapsedRealtime()
        lastGameFrameChanged = FrameFingerprint.changed(lastGameFrameFingerprint, fingerprint, 4)
        val forceRefresh = now - lastGameOcrAt >= 10_000L
        lastGameFrameFingerprint = fingerprint
        if (!lastGameFrameChanged && !forceRefresh && activeSelection == null) {
            store.recordSkippedFrame()
            bitmap.recycle()
            finishGameMonitor()
            return
        }
        lastGameOcrAt = now
        val ocrStartedAt = SystemClock.elapsedRealtime()
        screenTextReader.readDetailed(
            bitmap,
            onSuccess = { result ->
                store.recordOcr(SystemClock.elapsedRealtime() - ocrStartedAt)
                if (store.autoContextCalibration) {
                    calibrationManager.observeContextFrame(
                        ContextScreenDetector.detect(result.lines), currentGeometry()
                    )
                }
                bitmap.recycle()
                handleGameFlow(result.lines)
                finishGameMonitor()
            },
            onFailure = {
                store.recordOcr(SystemClock.elapsedRealtime() - ocrStartedAt)
                bitmap.recycle()
                finishGameMonitor(failed = true)
            }
        )
    }

    private fun finishGameMonitor(failed: Boolean = false) {
        gameMonitorBusy = false
        captureCoordinator.finish()
        store.captureQueueDepth = captureCoordinator.queueDepth()
        consecutiveCaptureFailures = if (failed) consecutiveCaptureFailures + 1 else 0
        val delay = AdaptiveCapturePolicy.nextDelayMillis(
            tftForeground = isTftForeground(),
            selectionOpen = activeSelection != null,
            frameChanged = lastGameFrameChanged,
            consecutiveFailures = consecutiveCaptureFailures
        )
        if (delay != Long.MAX_VALUE) scheduleGameMonitor(delay)
    }

    private fun handleGameFlow(lines: List<RecognizedTextLine>) {
        val announcements = mutableListOf<String>()
        val observedRound = GameFlowDetector.stageRound(lines)
        observedRound?.let { round ->
            if (round.key != lastRoundKey) {
                lastRoundKey = round.key
                announcements += round.announcement()
            }
        }

        val selection = GameFlowDetector.selection(lines)
        var startedSelection = false
        if (selection != null) {
            selectionMissingScans = 0
            val previous = activeSelection
            activeSelection = if (selection.options.isNotEmpty() || previous == null) selection else previous
            if (previous == null || previous.kind != selection.kind) {
                startedSelection = true
                selectionWarningFired = false
                handler.removeCallbacks(selectionWarningRunnable)
                scheduleSelectionWarning(selection)
                announcements += selection.announcementText()
            }
        } else if (activeSelection != null) {
            selectionMissingScans++
            if (selectionMissingScans >= 2) {
                activeSelection = null
                selectionMissingScans = 0
                selectionWarningFired = false
                handler.removeCallbacks(selectionWarningRunnable)
            }
        }

        val now = System.currentTimeMillis()
        val screen = when (selection?.kind) {
            SelectionKind.AUGMENT -> GameScreen.AUGMENT
            SelectionKind.ARMORY -> GameScreen.ARMORY
            SelectionKind.COMPONENTS -> GameScreen.COMPONENT_SELECTION
            null -> if (observedRound != null) GameScreen.BOARD else GameScreen.UNKNOWN
        }
        store.lastRecognizedScreen = screen.name.lowercase()
        gameStateRepository.update { previous ->
            previous.copy(
                screen = ObservedValue(screen, if (screen == GameScreen.UNKNOWN) 0.25f else 0.88f, now, ObservationSource.OCR),
                stageRound = observedRound?.let { ObservedValue(it, 0.9f, now, ObservationSource.OCR) }
                    ?: previous.stageRound,
                selection = selection?.let { ObservedValue(it, 0.86f, now, ObservationSource.OCR) },
                calibratedComponents = CalibrationComponent.entries.mapNotNull { component ->
                    store.getCalibrationMetadata(component)?.let { component to it }
                }.toMap(),
                lastFrameAt = now
            )
        }

        if (announcements.isEmpty()) return
        val text = announcements.joinToString(". ")
        store.lastReadText = text
        AppNotifications.showStatus(this, text.take(900))
        visionBusy = true
        listening = false
        speechRecognizer?.cancel()
        handler.removeCallbacks(restartListening)
        selectionReading = startedSelection
        speakReadout(text)
        if (startedSelection) handler.postDelayed({ if (selectionReading) startListening() }, 450)
    }

    private fun SelectionScreen.announcementText(): String {
        if (options.isEmpty()) {
            return "${kind.announcement}. A leitura automática não encontrou texto suficiente. Diga leia um, leia dois ou leia três para tentar novamente."
        }
        val choices = options.joinToString(". ") { "Opção ${it.index}. ${it.text}" }
        return "${kind.announcement}. $choices. Diga parar para interromper, ou leia e o número da opção."
    }

    private fun scheduleSelectionWarning(selection: SelectionScreen) {
        val remaining = selection.remainingSeconds ?: DEFAULT_SELECTION_SECONDS
        handler.postDelayed(selectionWarningRunnable, (remaining - 10).coerceAtLeast(0) * 1000L)
    }

    private fun playSelectionWarning() {
        warningTone?.startTone(ToneGenerator.TONE_PROP_BEEP2, 750)
        AppNotifications.showStatus(this, "Atenção: faltam cerca de 10 segundos para escolher.")
    }

    @SuppressLint("NewApi")
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

    private fun frameFingerprint(bitmap: Bitmap): Long {
        val sample = Bitmap.createScaledBitmap(bitmap, 72, 40, true)
        val pixels = IntArray(sample.width * sample.height)
        sample.getPixels(pixels, 0, sample.width, 0, 0, sample.width, sample.height)
        if (sample !== bitmap) sample.recycle()
        return FrameFingerprint.dHash(pixels, 72, 40)
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
        selectionReading = false
        showMicTemporary("🎙")
        scheduleContinuousRestart(650)
    }

    @SuppressLint("NewApi")
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
                    store.recordScreenshot(true)
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
                    store.recordScreenshot(false)
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
        if (!isTftForeground()) {
            store.lastGestureBlockedReason = "O TFT saiu do primeiro plano antes do toque."
            message(store.lastGestureBlockedReason)
            return
        }
        if (gestureBusy) {
            store.lastGestureBlockedReason = "Outro gesto ainda está em andamento."
            message(store.lastGestureBlockedReason)
            return
        }
        gestureBusy = true
        val (x, y) = toPixels(point)
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 65))
            .build()
        val accepted = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                gestureBusy = false
                after?.invoke()
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                gestureBusy = false
                AppNotifications.showError(this@VoiceAccessibilityService, "O Android cancelou o toque solicitado.")
            }
        }, null)
        if (!accepted) {
            gestureBusy = false
            AppNotifications.showError(this, "O Android recusou o toque solicitado.")
        }
    }

    private fun drag(from: NormalizedPoint, to: NormalizedPoint) {
        if (!isTftForeground()) {
            store.lastGestureBlockedReason = "O TFT saiu do primeiro plano antes do arrasto."
            message(store.lastGestureBlockedReason)
            return
        }
        if (gestureBusy) {
            store.lastGestureBlockedReason = "Outro gesto ainda está em andamento."
            message(store.lastGestureBlockedReason)
            return
        }
        gestureBusy = true
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
            override fun onCompleted(gestureDescription: GestureDescription?) {
                gestureBusy = false
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                gestureBusy = false
                AppNotifications.showError(this@VoiceAccessibilityService, "O Android cancelou o arrasto solicitado.")
            }
        }, null)
        if (!accepted) {
            gestureBusy = false
            AppNotifications.showError(this, "O Android recusou o arrasto solicitado.")
        }
    }

    private fun showMicrophoneOverlay() {
        if (micView != null) return
        val size = dp(56)
        val view = TextView(this).apply {
            text = "🎙"
            contentDescription = "Ativar ou pausar o microfone do Voice Controller"
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
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
            contentDescription = when (value) {
                "🎙" -> "Ativar o microfone do Voice Controller"
                "PAUSA" -> "Voice Controller pausado"
                "…", "●" -> "Voice Controller ouvindo"
                "LER" -> "Voice Controller lendo a tela"
                else -> "Estado do Voice Controller: $value"
            }
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

    private fun isControllerForeground(): Boolean = store.foregroundPackage == packageName

    private fun currentGeometry(): DisplayGeometry {
        val (width, height) = displaySize()
        return DisplayGeometry(width, height, displayRotation())
    }

    private fun manualMetadata(component: CalibrationComponent): CalibrationMetadata {
        val geometry = currentGeometry()
        return CalibrationMetadata(
            component = component,
            geometry = geometry,
            source = CalibrationSource.MANUAL,
            confidence = 0.99f,
            validatedAt = System.currentTimeMillis(),
            interfaceProfileId = "tft_${geometry.profileId()}"
        )
    }

    private fun componentForPoint(name: String): CalibrationComponent? = when (name) {
        ProfileStore.POINT_REROLL -> CalibrationComponent.REROLL
        ProfileStore.POINT_XP -> CalibrationComponent.XP
        ProfileStore.POINT_SHOP_TOGGLE -> CalibrationComponent.SHOP_TOGGLE
        ProfileStore.POINT_SELL -> CalibrationComponent.SELL
        else -> null
    }

    private fun componentForRegion(name: String): CalibrationComponent? = when (name) {
        ProfileStore.REGION_ITEMS -> CalibrationComponent.ITEMS
        ProfileStore.REGION_TRAITS -> CalibrationComponent.TRAITS
        ProfileStore.REGION_CHOICES -> CalibrationComponent.CHOICES
        else -> null
    }

    private fun speakCalibrationStatus() {
        val geometry = currentGeometry()
        val details = CalibrationComponent.entries.joinToString(". ") { component ->
            "${component.spokenName}: ${store.componentStatus(component, geometry)}"
        }
        speakFact("Status da calibração. $details.")
    }

    private fun speakMissingCalibration() {
        val missing = store.missingOrUnsafeComponents(currentGeometry())
        if (missing.isEmpty()) {
            speakFact("Todas as partes da calibração estão válidas para esta tela.")
        } else {
            speakFact("Falta calibrar ou revalidar ${spokenList(missing.map { it.spokenName })}.")
        }
    }

    private fun displaySize(): Pair<Int, Int> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager.currentWindowMetrics.bounds.let { it.width() to it.height() }
        } else {
            resources.displayMetrics.let { it.widthPixels to it.heightPixels }
        }

    private fun displayRotation(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display?.rotation ?: Surface.ROTATION_0
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.rotation
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
        private const val DEFAULT_SELECTION_SECONDS = 30
        private const val TFT_PACKAGE = "com.riotgames.league.teamfighttactics"
        private const val TFT_PBE_PACKAGE = "com.riotgames.league.teamfighttactics.pbe"
    }
}
