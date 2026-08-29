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
    private var contextScanBusy = false
    private var contextScanScheduled = false
    private var gameMonitorBusy = false
    private var gameMonitorScheduled = false
    private var lastContextScanAt = 0L
    private var lastRoundKey: String? = null
    private var activeSelection: SelectionScreen? = null
    private var selectionMissingScans = 0
    private var selectionReading = false
    private var selectionWarningFired = false
    private var textToSpeech: TextToSpeech? = null
    private var warningTone: ToneGenerator? = null
    private lateinit var screenTextReader: ScreenTextReader
    private var accessibilityButtonCallback: AccessibilityButtonController.AccessibilityButtonCallback? = null

    private val resetMic = Runnable {
        if (!listening) micView?.apply { text = "🎙"; textSize = 27f }
    }

    private val restartListening = Runnable {
        if (::store.isInitialized && store.continuousMode && !listening && captureOverlay == null &&
            store.pendingCalibration == ProfileStore.PENDING_NONE && isTftForeground() &&
            (!visionBusy || selectionReading) &&
            (textToSpeech?.isSpeaking != true || selectionReading)) {
            startListening()
        }
    }

    private val contextScanRunnable = Runnable {
        contextScanScheduled = false
        scanForContextCalibration()
    }

    private val gameMonitorRunnable = Runnable {
        gameMonitorScheduled = false
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
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        handler.removeCallbacks(resetMic)
        handler.removeCallbacks(restartListening)
        handler.removeCallbacks(contextScanRunnable)
        handler.removeCallbacks(gameMonitorRunnable)
        handler.removeCallbacks(selectionWarningRunnable)
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

    private fun processCommand(command: VoiceCommand) {
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
            command == VoiceCommand.ClearRoster
        if (!safeOutsideGame && !isTftForeground()) {
            message("Comando bloqueado: o TFT não está em primeiro plano")
            return
        }
        val (displayWidth, displayHeight) = displaySize()
        val geometryFreeReads = setOf(
            ScreenReadTarget.FULL_SCREEN, ScreenReadTarget.NOTICE,
            ScreenReadTarget.ITEMS, ScreenReadTarget.TRAITS, ScreenReadTarget.CHOICES,
            ScreenReadTarget.INVENTORY, ScreenReadTarget.CAROUSEL
        )
        val needsGeometry = !safeOutsideGame &&
            command != VoiceCommand.CollectOrbs &&
            command != VoiceCommand.AutoCalibrate &&
            !(command is VoiceCommand.ReadScreen && command.target in geometryFreeReads)
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
            VoiceCommand.AutoCalibrate -> runAutoCalibration()
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
        AppNotifications.showStatus(this, "Procurando orbes visíveis...")
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                    val bitmap = bitmapFromScreenshot(screenshot)
                    if (bitmap == null) {
                        failVisionRead("A imagem para procurar orbes não pôde ser convertida")
                        return
                    }
                    val scaledWidth = min(360, bitmap.width)
                    val scaledHeight = max(1, bitmap.height * scaledWidth / bitmap.width)
                    val sample = Bitmap.createScaledBitmap(bitmap, scaledWidth, scaledHeight, true)
                    if (sample !== bitmap) bitmap.recycle()
                    val pixels = IntArray(sample.width * sample.height)
                    sample.getPixels(pixels, 0, sample.width, 0, 0, sample.width, sample.height)
                    val search = VisionRegion.boardFromRows(store.getBoardRows())
                        ?.let { NormalizedRect(
                            (it.left - 0.08f).coerceAtLeast(0.02f),
                            (it.top - 0.18f).coerceAtLeast(0.10f),
                            (it.right + 0.08f).coerceAtMost(0.98f),
                            (it.bottom + 0.08f).coerceAtMost(0.90f)
                        ) } ?: NormalizedRect(0.04f, 0.14f, 0.96f, 0.86f)
                    val points = OrbDetector.detect(pixels, sample.width, sample.height, search)
                    sample.recycle()
                    if (points.isEmpty()) {
                        speakFact("Não encontrei uma orbe com confiança suficiente. Mova a câmera ou tente novamente.")
                    } else {
                        AppNotifications.showStatus(
                            this@VoiceAccessibilityService,
                            "${points.size} orbe ou orbes detectadas; iniciando coleta."
                        )
                        collectOrbSequence(points)
                    }
                }

                override fun onFailure(errorCode: Int) {
                    failVisionRead("O Android recusou a imagem para procurar orbes. Código $errorCode")
                }
            }
        )
    }

    private fun runAutoCalibration() {
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
        AppNotifications.showStatus(this, "Auto calibração: analisando os controles visíveis.")
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                    val bitmap = bitmapFromScreenshot(screenshot)
                    if (bitmap == null) {
                        failVisionRead("A imagem da auto calibração não pôde ser convertida")
                        return
                    }
                    screenTextReader.readDetailed(
                        bitmap,
                        onSuccess = { result ->
                            bitmap.recycle()
                            val detected = AutoCalibrationDetector.detect(result.lines)
                            val saved = mutableListOf<String>()
                            detected.shopLine?.takeIf { store.saveShopLine(it.first, it.last) }
                                ?.let { saved += "loja" }
                            detected.reroll?.takeIf { store.savePoint(ProfileStore.POINT_REROLL, it) }
                                ?.let { saved += "rolar" }
                            detected.xp?.takeIf { store.savePoint(ProfileStore.POINT_XP, it) }
                                ?.let { saved += "XP" }
                            detected.contexts.filter { it.confidence >= 0.85f }.forEach { context ->
                                if (store.saveRegion(context.kind.regionName, context.region)) {
                                    saved += context.kind.spokenName
                                }
                            }
                            if (saved.isNotEmpty()) {
                                val (width, height) = displaySize()
                                store.saveCalibrationGeometry(width, height, displayRotation())
                            }
                            val readout = if (saved.isEmpty()) {
                                "Auto calibração não encontrou controles com confiança suficiente. Abra a loja ou uma tela de escolha e tente novamente. A calibração anterior foi preservada."
                            } else {
                                "Auto calibração atualizada para ${spokenList(saved.distinct())}. Tabuleiro e banco continuam usando a calibração guiada para evitar movimentos errados."
                            }
                            store.autoCalibrationStatus = readout
                            speakFact(readout)
                        },
                        onFailure = { error ->
                            bitmap.recycle()
                            failVisionRead("Auto calibração falhou no OCR: ${error.javaClass.simpleName}")
                        }
                    )
                }

                override fun onFailure(errorCode: Int) {
                    failVisionRead("O Android recusou a imagem da auto calibração. Código $errorCode")
                }
            }
        )
    }

    private fun collectOrbSequence(points: List<NormalizedPoint>, index: Int = 0) {
        if (index >= points.size) {
            speakFact("Coleta de orbes concluída. Verifiquei ${points.size} posição ou posições.")
            return
        }
        tap(points[index]) {
            handler.postDelayed({ collectOrbSequence(points, index + 1) }, 1050)
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

    private fun captureCarouselFrames(frames: MutableList<ScreenTextResult>, index: Int) {
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                    val bitmap = bitmapFromScreenshot(screenshot)
                    if (bitmap == null) {
                        failVisionRead("Uma imagem do carrossel não pôde ser convertida")
                        return
                    }
                    screenTextReader.readDetailed(
                        bitmap,
                        onSuccess = { result ->
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
                            bitmap.recycle()
                            failVisionRead("Falha ao acompanhar o carrossel: ${error.message ?: error.javaClass.simpleName}")
                        }
                    )
                }

                override fun onFailure(errorCode: Int) {
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
        ScreenReadTarget.SHOP -> VisionRegion.shopFromLine(store.getShopLine())
        ScreenReadTarget.ITEMS -> store.getRegion(ProfileStore.REGION_ITEMS)
            ?: NormalizedRect(0f, 0f, 1f, 1f)
        ScreenReadTarget.TRAITS -> store.getRegion(ProfileStore.REGION_TRAITS)
            ?: NormalizedRect(0f, 0f, 1f, 1f)
        ScreenReadTarget.CHOICES -> store.getRegion(ProfileStore.REGION_CHOICES)
            ?: NormalizedRect(0f, 0f, 1f, 1f)
        ScreenReadTarget.BOARD -> VisionRegion.boardFromRows(store.getBoardRows())
        ScreenReadTarget.INVENTORY -> store.getRegion(ProfileStore.REGION_ITEMS)
            ?: NormalizedRect(0f, 0f, 1f, 1f)
        ScreenReadTarget.CAROUSEL -> NormalizedRect(0f, 0f, 1f, 1f)
        ScreenReadTarget.NOTICE -> NormalizedRect(0f, 0f, 1f, 1f)
        ScreenReadTarget.FULL_SCREEN -> NormalizedRect(0f, 0f, 1f, 1f)
    }

    private fun scheduleContextScan() {
        if (!::store.isInitialized || !store.autoContextCalibration || contextScanScheduled) return
        if (!hasMissingContextRegions()) return
        contextScanScheduled = true
        handler.postDelayed(contextScanRunnable, 900)
    }

    private fun scheduleGameMonitor(delayMs: Long = 1800L) {
        if (!::store.isInitialized || gameMonitorScheduled) return
        gameMonitorScheduled = true
        handler.postDelayed(gameMonitorRunnable, delayMs)
    }

    private fun scanGameFlow() {
        if (!::store.isInitialized || !isTftForeground()) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || gameMonitorBusy || contextScanBusy ||
            visionBusy || captureOverlay != null ||
            store.pendingCalibration != ProfileStore.PENDING_NONE
        ) {
            scheduleGameMonitor(420)
            return
        }
        gameMonitorBusy = true
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                    val bitmap = bitmapFromScreenshot(screenshot)
                    if (bitmap == null) {
                        finishGameMonitor()
                        return
                    }
                    screenTextReader.readDetailed(
                        bitmap,
                        onSuccess = { result ->
                            if (store.autoContextCalibration) {
                                ContextScreenDetector.detect(result.lines)
                                    .filter { it.confidence >= 0.85f }
                                    .filter { store.getRegion(it.kind.regionName) == null }
                                    .forEach { store.saveRegion(it.kind.regionName, it.region) }
                            }
                            bitmap.recycle()
                            handleGameFlow(result.lines)
                            finishGameMonitor()
                        },
                        onFailure = {
                            bitmap.recycle()
                            finishGameMonitor()
                        }
                    )
                }

                override fun onFailure(errorCode: Int) = finishGameMonitor()
            }
        )
    }

    private fun finishGameMonitor() {
        gameMonitorBusy = false
        scheduleGameMonitor(1750)
    }

    private fun handleGameFlow(lines: List<RecognizedTextLine>) {
        val announcements = mutableListOf<String>()
        GameFlowDetector.stageRound(lines)?.let { round ->
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

    private fun hasMissingContextRegions(): Boolean =
        store.getRegion(ProfileStore.REGION_ITEMS) == null ||
            store.getRegion(ProfileStore.REGION_TRAITS) == null ||
            store.getRegion(ProfileStore.REGION_CHOICES) == null

    private fun scanForContextCalibration() {
        if (!store.autoContextCalibration || !isTftForeground() || !hasMissingContextRegions()) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || contextScanBusy || gameMonitorBusy || visionBusy ||
            listening || captureOverlay != null || store.pendingCalibration != ProfileStore.PENDING_NONE
        ) {
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastContextScanAt < 3500L) return
        lastContextScanAt = now
        contextScanBusy = true
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                    val bitmap = bitmapFromScreenshot(screenshot)
                    if (bitmap == null) {
                        contextScanBusy = false
                        store.autoCalibrationStatus = "Autocalibração: a screenshot não pôde ser convertida."
                        return
                    }
                    screenTextReader.readDetailed(
                        bitmap,
                        onSuccess = { result ->
                            val saved = ContextScreenDetector.detect(result.lines)
                                .filter { detection ->
                                    detection.confidence >= 0.85f &&
                                        store.getRegion(detection.kind.regionName) == null
                                }
                                .filter { detection ->
                                    store.saveRegion(detection.kind.regionName, detection.region)
                                }
                            if (store.saveReadingScreenshots && saved.isNotEmpty()) {
                                runCatching {
                                    ScreenshotStore.save(
                                        this@VoiceAccessibilityService,
                                        bitmap,
                                        category = "AUTO_CONTEXT",
                                        saveAsOcrSample = true
                                    )
                                }
                            }
                            bitmap.recycle()
                            contextScanBusy = false
                            if (saved.isNotEmpty()) {
                                val names = spokenList(saved.map { it.kind.spokenName })
                                val announcement = "Autocalibração concluída para $names. Região salva no aparelho."
                                store.autoCalibrationStatus = announcement
                                visionBusy = true
                                handler.removeCallbacks(restartListening)
                                AppNotifications.showStatus(this@VoiceAccessibilityService, announcement)
                                speakReadout(announcement)
                            }
                        },
                        onFailure = { error ->
                            bitmap.recycle()
                            contextScanBusy = false
                            store.autoCalibrationStatus =
                                "Autocalibração: OCR falhou (${error.javaClass.simpleName})."
                        }
                    )
                }

                override fun onFailure(errorCode: Int) {
                    contextScanBusy = false
                    store.autoCalibrationStatus = "Autocalibração: screenshot recusada, código $errorCode."
                }
            }
        )
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
        selectionReading = false
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

    private fun isControllerForeground(): Boolean = store.foregroundPackage == packageName

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
        private const val DEFAULT_SELECTION_SECONDS = 30
        private const val TFT_PACKAGE = "com.riotgames.league.teamfighttactics"
        private const val TFT_PBE_PACKAGE = "com.riotgames.league.teamfighttactics.pbe"
    }
}
