package com.rlks.voicecontroller

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
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
    private var diagnosticView: TextView? = null
    private var listening = false

    private val hideDiagnostic = Runnable {
        diagnosticView?.visibility = View.GONE
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        store = ProfileStore(this)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createSpeechRecognizer()
        showMicrophoneOverlay()
        showDiagnosticOverlay()
        message("Voice Controller ready")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    override fun onDestroy() {
        handler.removeCallbacks(hideDiagnostic)
        removeCaptureOverlay()
        diagnosticView?.let { runCatching { windowManager.removeView(it) } }
        diagnosticView = null
        micView?.let { runCatching { windowManager.removeView(it) } }
        micView = null
        speechRecognizer?.destroy()
        speechRecognizer = null
        super.onDestroy()
    }

    private fun createSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            message("No Android speech recognizer is available")
            return
        }
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).also { recognizer ->
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    listening = true
                    micView?.text = "●"
                }

                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit

                override fun onError(error: Int) {
                    listening = false
                    micView?.text = "🎙"
                    if (error != SpeechRecognizer.ERROR_NO_MATCH && error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                        showDiagnosticText("Speech error: $error")
                    } else {
                        showDiagnosticText("No speech match")
                    }
                }

                override fun onResults(results: Bundle?) {
                    listening = false
                    micView?.text = "🎙"
                    val phrases = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                    val selected = VoiceCommandParser.parseAlternatives(phrases)
                    showRecognitionDiagnostic(phrases, selected)
                    if (selected != null) {
                        processCommand(selected.first, selected.second)
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
        }
    }

    private fun startListening() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            message("Open Voice Controller and allow microphone first")
            return
        }
        val recognizer = speechRecognizer ?: run {
            createSpeechRecognizer()
            speechRecognizer
        } ?: return

        if (listening) {
            recognizer.cancel()
            listening = false
            micView?.text = "🎙"
            return
        }

        handler.removeCallbacks(hideDiagnostic)
        diagnosticView?.visibility = View.GONE

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 10)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, chessBiasingStrings())
            when (store.language) {
                ProfileStore.LANG_EN -> putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
                ProfileStore.LANG_PT -> putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
            }
        }
        micView?.text = "…"
        recognizer.startListening(intent)
    }

    private fun chessBiasingStrings(): ArrayList<String> {
        val values = ArrayList<String>(260)
        val englishFiles = mapOf(
            'A' to "A", 'B' to "B", 'C' to "C", 'D' to "D",
            'E' to "E", 'F' to "F", 'G' to "G", 'H' to "H"
        )
        val natoFiles = mapOf(
            'A' to "Alpha", 'B' to "Bravo", 'C' to "Charlie", 'D' to "Delta",
            'E' to "Echo", 'F' to "Foxtrot", 'G' to "Golf", 'H' to "Hotel"
        )
        val ranks = mapOf(
            1 to "one", 2 to "two", 3 to "three", 4 to "four",
            5 to "five", 6 to "six", 7 to "seven", 8 to "eight"
        )

        for (file in 'A'..'H') {
            values += file.toString()
            values += natoFiles.getValue(file)
            for (rank in 1..8) {
                values += "$file$rank"
                values += "${englishFiles.getValue(file)} ${ranks.getValue(rank)}"
                values += "${natoFiles.getValue(file)} ${ranks.getValue(rank)}"
            }
        }

        // Frequent opening coordinates get extra bias because short alphanumeric speech
        // is where Android recognition tends to struggle the most.
        values += listOf(
            "E two E four", "D two D four", "A two A four", "H two H four",
            "G one F three", "B one C three", "E seven E five", "D seven D five",
            "A seven A five", "H seven H five", "G eight F six", "B eight C six",
            "Echo two Echo four", "Delta two Delta four", "Alpha two Alpha four",
            "Hotel two Hotel four", "Golf one Foxtrot three", "Bravo one Charlie three"
        )
        values += listOf("castle kingside", "castle queenside", "roque pequeno", "roque grande")
        return values
    }

    private fun showMicrophoneOverlay() {
        if (micView != null) return
        val size = dp(58)
        val view = TextView(this).apply {
            text = "🎙"
            textSize = 27f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(215, 35, 38, 46))
            elevation = dp(8).toFloat()
        }

        val params = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = resources.displayMetrics.widthPixels - size - dp(12)
            y = resources.displayMetrics.heightPixels / 3
        }

        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var moved = false

        view.setOnTouchListener { _: View, event: MotionEvent ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = params.x
                    startY = params.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (abs(dx) > dp(7) || abs(dy) > dp(7)) moved = true
                    params.x = (startX + dx).toInt().coerceIn(0, max(0, resources.displayMetrics.widthPixels - size))
                    params.y = (startY + dy).toInt().coerceIn(0, max(0, resources.displayMetrics.heightPixels - size))
                    windowManager.updateViewLayout(view, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) startListening()
                    true
                }
                else -> false
            }
        }

        windowManager.addView(view, params)
        micView = view
    }

    private fun showDiagnosticOverlay() {
        if (diagnosticView != null) return
        val view = TextView(this).apply {
            visibility = View.GONE
            textSize = 13f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(235, 20, 22, 28))
            setPadding(dp(12), dp(9), dp(12), dp(9))
            maxLines = 7
        }
        val params = WindowManager.LayoutParams(
            resources.displayMetrics.widthPixels - dp(24),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(12)
            y = dp(56)
        }
        windowManager.addView(view, params)
        diagnosticView = view
    }

    private fun showRecognitionDiagnostic(
        phrases: List<String>,
        selected: Pair<String, VoiceCommand>?
    ) {
        val lines = mutableListOf<String>()
        val summary = selected?.second?.let { commandSummary(it) }
        lines += if (summary != null) "PARSED: $summary" else "PARSED: UNKNOWN"
        if (phrases.isEmpty()) {
            lines += "HEARD: <nothing>"
        } else {
            phrases.take(5).forEachIndexed { index, phrase ->
                lines += "${index + 1}. $phrase"
            }
        }
        showDiagnosticText(lines.joinToString("\n"))
    }

    private fun showDiagnosticText(text: String) {
        val view = diagnosticView ?: return
        handler.removeCallbacks(hideDiagnostic)
        view.text = text
        view.visibility = View.VISIBLE
        handler.postDelayed(hideDiagnostic, 20_000)
    }

    private fun processCommand(raw: String, command: VoiceCommand) {
        when (command) {
            is VoiceCommand.ChessMove -> executeChessMove(command.from, command.to)
            is VoiceCommand.Castle -> {
                val move = ChessMapper.castleMove(command.kingside, store.isWhiteBottom())
                executeChessMove(move.first, move.second)
            }
            VoiceCommand.CalibrateBoard -> captureBoard()
            is VoiceCommand.SetOrientation -> {
                store.setWhiteBottom(command.whiteBottom)
                message(if (command.whiteBottom) "White at bottom" else "Black at bottom")
            }
            is VoiceCommand.SetPoint -> captureNamedPoint(command.name)
            is VoiceCommand.TapPoint -> tapNamedPoint(command.name)
            is VoiceCommand.TapPercent -> tap(NormalizedPoint(command.x, command.y))
            is VoiceCommand.DragPoint -> dragNamed(command.from, command.to)
            is VoiceCommand.DragPercent -> drag(
                NormalizedPoint(command.x1, command.y1),
                NormalizedPoint(command.x2, command.y2)
            )
            VoiceCommand.Help -> message("Try: E2 E4 • calibrate board • set reroll • tap reroll")
            is VoiceCommand.Unknown -> {
                val name = VoiceCommandParser.canonicalName(command.raw)
                if (store.hasPoint(name)) tapNamedPoint(name)
            }
        }
    }

    private fun commandSummary(command: VoiceCommand): String? = when (command) {
        is VoiceCommand.ChessMove -> "${command.from.uppercase()} ${command.to.uppercase()}"
        is VoiceCommand.Castle -> if (command.kingside) "CASTLE KINGSIDE" else "CASTLE QUEENSIDE"
        VoiceCommand.CalibrateBoard -> "CALIBRATE BOARD"
        is VoiceCommand.SetOrientation -> if (command.whiteBottom) "WHITE ORIENTATION" else "BLACK ORIENTATION"
        is VoiceCommand.SetPoint -> "SET ${command.name.uppercase()}"
        is VoiceCommand.TapPoint -> "TAP ${command.name.uppercase()}"
        is VoiceCommand.DragPoint -> "DRAG ${command.from.uppercase()} → ${command.to.uppercase()}"
        is VoiceCommand.TapPercent -> "TAP"
        is VoiceCommand.DragPercent -> "DRAG"
        VoiceCommand.Help -> "HELP"
        is VoiceCommand.Unknown -> null
    }

    private fun executeChessMove(from: String, to: String) {
        val rect = store.getBoardRect()
        if (rect == null) {
            message("Say ‘calibrate board’ first")
            return
        }
        val whiteBottom = store.isWhiteBottom()
        val a = ChessMapper.squareToPoint(from, rect, whiteBottom)
        val b = ChessMapper.squareToPoint(to, rect, whiteBottom)
        if (a == null || b == null) {
            message("Invalid chess squares")
            return
        }
        tap(a) {
            handler.postDelayed({ tap(b) }, 120)
        }
    }

    private fun tapNamedPoint(name: String) {
        val clean = VoiceCommandParser.canonicalName(name)
        val point = store.getPoint(clean)
        if (point == null) {
            message("Point ‘$clean’ is not set. Say ‘set $clean’ first.")
            return
        }
        tap(point)
    }

    private fun dragNamed(from: String, to: String) {
        val a = store.getPoint(from)
        val b = store.getPoint(to)
        if (a == null || b == null) {
            message("Set both points first")
            return
        }
        drag(a, b)
    }

    private fun tap(point: NormalizedPoint, after: (() -> Unit)? = null) {
        val (x, y) = toPixels(point)
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 55))
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
            .addStroke(GestureDescription.StrokeDescription(path, 0, 420))
            .build()
        dispatchGesture(gesture, null, null)
    }

    private fun captureBoard() {
        removeCaptureOverlay()
        val overlay = FrameLayout(this)
        overlay.setBackgroundColor(Color.argb(35, 60, 180, 100))
        val hint = hintView("Tap the OUTER top-left corner of the chessboard")
        overlay.addView(hint, hintLayoutParams())

        val points = mutableListOf<NormalizedPoint>()
        overlay.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                points += fromPixels(event.rawX, event.rawY)
                if (points.size == 1) {
                    hint.text = "Now tap the OUTER bottom-right corner"
                } else {
                    val first = points[0]
                    val second = points[1]
                    val rect = BoardRect(
                        min(first.x, second.x),
                        min(first.y, second.y),
                        max(first.x, second.x),
                        max(first.y, second.y)
                    )
                    store.saveBoardRect(rect)
                    handler.post {
                        removeCaptureOverlay()
                        message("Chessboard calibrated")
                    }
                }
                true
            } else true
        }
        showCaptureOverlay(overlay)
    }

    private fun captureNamedPoint(name: String) {
        removeCaptureOverlay()
        val clean = VoiceCommandParser.canonicalName(name)
        val overlay = FrameLayout(this)
        overlay.setBackgroundColor(Color.argb(28, 90, 140, 230))
        val hint = hintView("Tap the location for: $clean")
        overlay.addView(hint, hintLayoutParams())
        overlay.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                val point = fromPixels(event.rawX, event.rawY)
                store.savePoint(clean, point.x, point.y)
                handler.post {
                    removeCaptureOverlay()
                    message("Saved: $clean")
                }
                true
            } else true
        }
        showCaptureOverlay(overlay)
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

    private fun hintView(textValue: String) = TextView(this).apply {
        text = textValue
        textSize = 17f
        setTextColor(Color.WHITE)
        setBackgroundColor(Color.argb(230, 20, 22, 28))
        setPadding(dp(16), dp(12), dp(16), dp(12))
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
