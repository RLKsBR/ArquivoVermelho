package com.rlks.voicecontroller

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var store: ProfileStore
    private lateinit var status: TextView
    private lateinit var calibrationStatus: TextView
    private lateinit var visionStatus: TextView
    private lateinit var speechLog: TextView
    private lateinit var modeButton: Button
    private lateinit var skipButton: Button
    private lateinit var cancelButton: Button
    private lateinit var visionPreview: ImageView
    private lateinit var openScreenshotButton: Button
    private lateinit var lastReadStatus: TextView
    private lateinit var saveSamplesButton: Button
    private lateinit var rosterStatus: TextView
    private lateinit var autoCalibrationButton: Button
    private lateinit var confirmationButton: Button
    private lateinit var diagnosticsStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = ProfileStore(this)
        AppNotifications.createChannels(this)
        window.statusBarColor = Color.rgb(18, 20, 24)
        window.navigationBarColor = Color.rgb(18, 20, 24)
        setContentView(buildUi())
        requestRequiredPermissions()
    }

    override fun onResume() {
        super.onResume()
        refreshAll()
    }

    private fun buildUi(): View {
        val basePadding = dp(18)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(basePadding, basePadding, basePadding, dp(28))
            setBackgroundColor(Color.rgb(18, 20, 24))
            setOnApplyWindowInsetsListener { view, insets ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val bars = insets.getInsets(WindowInsets.Type.systemBars())
                    view.setPadding(
                        basePadding + bars.left, basePadding + bars.top,
                        basePadding + bars.right, dp(28) + bars.bottom
                    )
                } else {
                    @Suppress("DEPRECATION")
                    view.setPadding(
                        basePadding + insets.systemWindowInsetLeft,
                        basePadding + insets.systemWindowInsetTop,
                        basePadding + insets.systemWindowInsetRight,
                        dp(28) + insets.systemWindowInsetBottom
                    )
                }
                insets
            }
        }

        root.addView(TextView(this).apply {
            text = "Voice Controller"
            textSize = 28f
            setTextColor(Color.WHITE)
        })
        root.addView(TextView(this).apply {
            text = "TFT-only • voz em português • taps e drags • coordenadas salvas no aparelho"
            textSize = 14f
            setTextColor(Color.LTGRAY)
            setPadding(0, dp(6), 0, dp(14))
        })

        status = bodyBox().apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        root.addView(status)
        root.addView(button("1. Permitir microfone e notificações") { requestRequiredPermissions(true) })
        root.addView(button("2. Ativar Voice Controller na Acessibilidade") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })

        modeButton = button("") {
            store.continuousMode = !store.continuousMode
            refreshAll()
        }
        root.addView(modeButton)

        root.addView(section("CALIBRAÇÃO TFT"))
        root.addView(TextView(this).apply {
            text = "Método principal: abra o TFT, ative o microfone e diga “auto calibrar”. O app observa três imagens e só confirma partes estáveis. A marcação de pontos continua disponível como fallback com ajuda visual."
            textSize = 13f
            setTextColor(Color.LTGRAY)
            setPadding(0, 0, 0, dp(8))
        })
        calibrationStatus = bodyBox().apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        root.addView(calibrationStatus)
        root.addView(button("Preparar autocalibração sem toques") {
            store.autoContextCalibration = true
            Toast.makeText(
                this,
                "Abra o TFT, toque no controle de acessibilidade e diga auto calibrar.",
                Toast.LENGTH_LONG
            ).show()
            refreshAll()
        })
        root.addView(button("Iniciar calibração manual (fallback)") { startCalibrationWizard() })
        autoCalibrationButton = button("") {
            store.autoContextCalibration = !store.autoContextCalibration
            refreshAll()
        }
        root.addView(autoCalibrationButton)
        skipButton = button("Pular etapa contextual atual") { skipCurrentStage() }
        root.addView(skipButton)
        cancelButton = button("Cancelar calibração guiada") { cancelCalibrationWizard() }
        root.addView(cancelButton)
        confirmationButton = button("") {
            store.sensitiveConfirmationEnabled = !store.sensitiveConfirmationEnabled
            refreshAll()
        }
        root.addView(confirmationButton)

        root.addView(section("TESTE DE VISÃO"))
        root.addView(TextView(this).apply {
            text = "A captura é convertida em imagem, verificada contra tela preta/sem detalhes e salva em Fotos: Pictures/Voice Controller/TFT Captures."
            textSize = 13f
            setTextColor(Color.LTGRAY)
            setPadding(0, 0, 0, dp(8))
        })
        root.addView(button("Testar e salvar screenshot do TFT") { queueScreenshotTest() })
        visionStatus = bodyBox()
        root.addView(visionStatus)
        visionPreview = ImageView(this).apply {
            adjustViewBounds = true
            maxHeight = dp(230)
            scaleType = ImageView.ScaleType.CENTER_CROP
            contentDescription = "Miniatura da última screenshot do TFT"
            visibility = View.GONE
        }
        root.addView(visionPreview)
        openScreenshotButton = button("Abrir última screenshot") { openLastScreenshot() }.apply {
            isEnabled = false
        }
        root.addView(openScreenshotButton)

        root.addView(section("LEITURA POR VOZ"))
        root.addView(TextView(this).apply {
            text = "Dentro do TFT, diga “ler loja”, “ler itens”, “ler sinergias”, “ler escolhas” ou “ler tela”. O reconhecimento roda no aparelho e o Android fala o texto encontrado."
            textSize = 13f
            setTextColor(Color.LTGRAY)
            setPadding(0, 0, 0, dp(8))
        })
        lastReadStatus = bodyBox()
        root.addView(lastReadStatus)
        saveSamplesButton = button("") {
            store.saveReadingScreenshots = !store.saveReadingScreenshots
            refreshAll()
        }
        root.addView(saveSamplesButton)

        root.addView(section("COMPARAÇÕES DO TIME"))
        root.addView(TextView(this).apply {
            text = "Cadastro provisório por voz: “registrar indígena vida 1800 valor quatro com item frontline”. Depois pergunte “maior vida”, “maior vida sem item”, “maior valor” ou “listar frontline”. O valor é o número que você informou; o app não cria uma nota própria."
            textSize = 13f
            setTextColor(Color.LTGRAY)
            setPadding(0, 0, 0, dp(8))
        })
        rosterStatus = bodyBox()
        root.addView(rosterStatus)

        root.addView(section("GUIA OFFLINE DE ITENS"))
        root.addView(TextView(this).apply {
            text = "Disponível antes da partida. Pergunte “quais componentes fazem o Gume do Infinito?”, “o que faz com arco?” ou “quais itens existem?”."
            textSize = 13f
            setTextColor(Color.LTGRAY)
            setPadding(0, 0, 0, dp(8))
        })
        root.addView(bodyBox().apply {
            text = ItemRecipeBook.catalogSummary()
            contentDescription = "Catálogo de receitas dos itens combináveis do TFT. $text"
        })

        root.addView(section("COMANDOS INICIAIS"))
        root.addView(TextView(this).apply {
            text = "• “auto calibrar”, “testar calibração”\n• “status da calibração”, “o que falta calibrar?”\n• “recalibrar esta tela”, “parar autocalibração”\n• “ler loja”, “ler itens”, “ler sinergias”\n• “ler tabuleiro”, “ler inventário”, “ler carrossel” (experimental)\n• “ler aviso”, “por que não pegou”\n• “ler escolhas”, “ler tela”, “repetir leitura”\n• “quais componentes fazem o Gume do Infinito?”\n• “o que faz com arco?”, “quais itens existem?”\n• “quem não faz parte das sinergias?”\n• “maior vida”, “maior vida sem item”\n• “maior valor”, “listar frontline”\n• “rolar”\n• “comprar um”, “comprar dois quatro cinco”\n• “subir nível” / “XP”\n• “banco dois para D4”\n• “D4 para banco três”\n• “C3 para F4”\n• “vender banco dois”; depois “confirmar”\n• “aprimoramento dois”; depois “confirmar”\n• “pausar controle”"
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding(0, dp(2), 0, dp(10))
        })

        root.addView(section("ÚLTIMAS FALAS"))
        speechLog = bodyBox().apply { maxLines = 10 }
        root.addView(speechLog)
        root.addView(button("Limpar log de voz") {
            store.clearRecognitionLog()
            refreshAll()
        })

        root.addView(section("DIAGNÓSTICO"))
        diagnosticsStatus = bodyBox().apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        root.addView(diagnosticsStatus)
        root.addView(button("Copiar relatório de diagnóstico") { copyDiagnostics() })
        root.addView(button("Compartilhar relatório de diagnóstico") { shareDiagnostics() })

        root.addView(TextView(this).apply {
            text = "Voice Controller não é endossado pela Riot Games e não reflete as opiniões da Riot Games ou de qualquer pessoa oficialmente envolvida na produção ou administração de suas propriedades. Riot Games e todas as propriedades associadas são marcas comerciais ou registradas da Riot Games, Inc."
            textSize = 11f
            setTextColor(Color.GRAY)
            setPadding(0, dp(18), 0, 0)
        })

        return ScrollView(this).apply {
            clipToPadding = false
            addView(root)
        }
    }

    private fun startCalibrationWizard() {
        store.calibrationWizardActive = true
        store.pendingCalibration = ProfileStore.PENDING_CORE
        AppNotifications.showCalibration(this, CalibrationFlow.prompt(ProfileStore.PENDING_CORE))
        Toast.makeText(
            this,
            "Abra o TFT e toque no 🎙. As coordenadas serão salvas ao fim de cada etapa.",
            Toast.LENGTH_LONG
        ).show()
        refreshAll()
    }

    private fun skipCurrentStage() {
        if (!store.calibrationWizardActive) return
        val next = CalibrationFlow.next(store.pendingCalibration)
        store.pendingCalibration = next
        if (next == ProfileStore.PENDING_NONE) {
            store.calibrationWizardActive = false
            AppNotifications.clearCalibration(this)
            Toast.makeText(
                this,
                "Calibração guiada encerrada. Você pode marcar essa região depois.",
                Toast.LENGTH_LONG
            ).show()
        } else {
            AppNotifications.showCalibration(this, CalibrationFlow.prompt(next))
            Toast.makeText(
                this,
                "Etapa pulada. Próxima: ${CalibrationFlow.label(next)}.",
                Toast.LENGTH_LONG
            ).show()
        }
        refreshAll()
    }

    private fun cancelCalibrationWizard() {
        store.pendingCalibration = ProfileStore.PENDING_NONE
        store.calibrationWizardActive = false
        AppNotifications.clearCalibration(this)
        Toast.makeText(
            this,
            "Calibração cancelada; coordenadas já salvas foram preservadas.",
            Toast.LENGTH_LONG
        ).show()
        refreshAll()
    }

    private fun queueScreenshotTest() {
        if (store.calibrationWizardActive) {
            Toast.makeText(
                this,
                "Conclua ou cancele a calibração guiada antes do teste de visão.",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        store.pendingCalibration = ProfileStore.PENDING_SCREENSHOT_TEST
        AppNotifications.showCalibration(
            this,
            "Abra o TFT e toque uma vez no 🎙 para testar e salvar a imagem."
        )
        Toast.makeText(this, "Abra o TFT e toque no 🎙 para capturar.", Toast.LENGTH_LONG).show()
        refreshAll()
    }

    private fun openLastScreenshot() {
        val raw = store.lastScreenshotUri
        if (raw.isBlank()) return
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.parse(raw), "image/jpeg")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        }.onFailure {
            Toast.makeText(
                this,
                "Não encontrei um app capaz de abrir a screenshot.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun refreshAll() {
        if (!::status.isInitialized) return
        val mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val notifications = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val access = isAccessibilityEnabled()
        val tftDetected = store.foregroundPackage in TFT_PACKAGES
        val foreground = when {
            tftDetected -> "TFT detectado ✓"
            store.foregroundPackage.isBlank() -> "TFT ainda não detectado"
            else -> "TFT fora de foco"
        }
        status.text = "Microfone: ${if (mic) "OK" else "permissão necessária"}   •   Notificações: ${if (notifications) "OK" else "permissão necessária"}   •   Acessibilidade: ${if (access) "ON" else "OFF"}\n$foreground"
        status.setTextColor(
            if (mic && notifications && access) Color.rgb(130, 230, 150)
            else Color.rgb(255, 190, 100)
        )
        modeButton.text = "Modo partida contínuo: ${if (store.continuousMode) "LIGADO" else "DESLIGADO"}"

        val pending = store.pendingCalibration
        calibrationStatus.text = store.calibrationSummary() +
            "\n\nEtapa pendente: ${CalibrationFlow.label(pending)}" +
            "\n${store.autoCalibrationStatus}"
        autoCalibrationButton.text =
            "Autocalibração contextual: ${if (store.autoContextCalibration) "LIGADA" else "DESLIGADA"}"
        confirmationButton.text =
            "Confirmação para venda e escolha: ${if (store.sensitiveConfirmationEnabled) "LIGADA" else "DESLIGADA"}"
        skipButton.isEnabled = store.calibrationWizardActive && pending in CONTEXT_STAGES
        cancelButton.isEnabled = store.calibrationWizardActive

        visionStatus.text = store.visionStatus
        val screenshot = store.lastScreenshotUri
        openScreenshotButton.isEnabled = screenshot.isNotBlank()
        if (screenshot.isBlank()) {
            visionPreview.visibility = View.GONE
            visionPreview.setImageDrawable(null)
        } else {
            visionPreview.visibility = View.VISIBLE
            runCatching { visionPreview.setImageURI(Uri.parse(screenshot)) }
                .onFailure { visionPreview.setImageDrawable(null) }
        }
        lastReadStatus.text = store.lastReadText
        saveSamplesButton.text = "Salvar amostras de OCR: ${if (store.saveReadingScreenshots) "LIGADO" else "DESLIGADO"}"
        rosterStatus.text = store.rosterSummary()
        speechLog.text = store.getRecognitionLog()
        diagnosticsStatus.text = store.diagnosticsSummary()
    }

    private fun copyDiagnostics() {
        val report = store.diagnosticsSummary()
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Diagnóstico Voice Controller", report))
        Toast.makeText(this, "Relatório copiado sem screenshots", Toast.LENGTH_SHORT).show()
    }

    private fun shareDiagnostics() {
        val report = store.diagnosticsSummary()
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "Diagnóstico Voice Controller TFT")
                    putExtra(Intent.EXTRA_TEXT, report)
                },
                "Compartilhar diagnóstico"
            )
        )
    }

    private fun isAccessibilityEnabled(): Boolean {
        val expected = ComponentName(this, VoiceAccessibilityService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    private fun requestRequiredPermissions(force: Boolean = false) {
        val missing = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            missing += Manifest.permission.RECORD_AUDIO
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            missing += Manifest.permission.POST_NOTIFICATIONS
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            missing += Manifest.permission.WRITE_EXTERNAL_STORAGE
        }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), 10)
        } else if (force) {
            refreshAll()
        }
    }

    private fun section(text: String) = TextView(this).apply {
        this.text = text
        textSize = 15f
        setTextColor(Color.rgb(180, 200, 255))
        setPadding(0, dp(18), 0, dp(7))
    }

    private fun bodyBox() = TextView(this).apply {
        textSize = 13f
        setTextColor(Color.WHITE)
        setBackgroundColor(Color.rgb(30, 33, 39))
        setPadding(dp(10), dp(9), dp(10), dp(9))
    }

    private fun button(text: String, action: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        setOnClickListener { action() }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private val TFT_PACKAGES = setOf(
            "com.riotgames.league.teamfighttactics",
            "com.riotgames.league.teamfighttactics.pbe"
        )
        private val CONTEXT_STAGES = setOf(
            ProfileStore.PENDING_ITEMS,
            ProfileStore.PENDING_TRAITS,
            ProfileStore.PENDING_CHOICES
        )
    }
}
