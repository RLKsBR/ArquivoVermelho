package com.rlks.voicecontroller

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = ProfileStore(this)
        setContentView(buildUi())
        requestMicrophoneIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        refreshAll()
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(28))
            setBackgroundColor(Color.rgb(18, 20, 24))
        }

        root.addView(TextView(this).apply {
            text = "Voice Controller"
            textSize = 28f
            setTextColor(Color.WHITE)
        })

        root.addView(TextView(this).apply {
            text = "TFT-only • voz em português • taps e drags • calibração salva no aparelho"
            textSize = 14f
            setTextColor(Color.LTGRAY)
            setPadding(0, dp(6), 0, dp(14))
        })

        status = bodyBox()
        root.addView(status)

        root.addView(button("1. Permitir microfone") { requestMicrophoneIfNeeded(true) })
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
            text = "Toque numa calibração aqui. Depois abra o TFT e toque UMA vez no botão de acessibilidade do Android (ou no 🎙, se aparecer). Ao terminar, as coordenadas ficam salvas dentro do app e continuam lá quando ele for fechado e aberto novamente."
            textSize = 13f
            setTextColor(Color.LTGRAY)
            setPadding(0, 0, 0, dp(8))
        })

        calibrationStatus = bodyBox()
        root.addView(calibrationStatus)

        root.addView(button("Calibrar tabuleiro A1–G4 (8 toques)") { queue(ProfileStore.PENDING_BOARD) })
        root.addView(button("Calibrar banco 1–9 (2 toques)") { queue(ProfileStore.PENDING_BENCH) })
        root.addView(button("Calibrar loja 1–5 (2 toques)") { queue(ProfileStore.PENDING_SHOP) })
        root.addView(button("Calibrar botão Rolar") { queue(ProfileStore.PENDING_REROLL) })
        root.addView(button("Calibrar botão XP") { queue(ProfileStore.PENDING_XP) })
        root.addView(button("Calibrar botão abrir/fechar loja") { queue(ProfileStore.PENDING_SHOP_TOGGLE) })
        root.addView(button("Calibrar área de venda") { queue(ProfileStore.PENDING_SELL) })
        root.addView(button("Marcar região dos itens (2 cantos)") { queue(ProfileStore.PENDING_ITEMS) })
        root.addView(button("Marcar região das sinergias (2 cantos)") { queue(ProfileStore.PENDING_TRAITS) })
        root.addView(button("Marcar região de escolhas/aprimoramentos (2 cantos)") { queue(ProfileStore.PENDING_CHOICES) })

        root.addView(section("TESTE DE VISÃO"))
        root.addView(button("Testar screenshot do TFT") { queue(ProfileStore.PENDING_SCREENSHOT_TEST) })
        visionStatus = bodyBox()
        root.addView(visionStatus)

        root.addView(section("COMANDOS INICIAIS"))
        root.addView(TextView(this).apply {
            text = "• “rolar”\n• “comprar um”, “comprar dois quatro cinco”\n• “subir nível” / “XP”\n• “banco dois para D4”\n• “D4 para banco três”\n• “C3 para F4”\n• “vender banco dois”\n• “vender D2”\n• “aprimoramento dois”\n• “pausar controle”"
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

        return ScrollView(this).apply { addView(root) }
    }

    private fun queue(type: String) {
        store.pendingCalibration = type
        Toast.makeText(
            this,
            "Pronto. Abra o TFT e toque no botão de acessibilidade uma vez.",
            Toast.LENGTH_LONG
        ).show()
        refreshAll()
    }

    private fun refreshAll() {
        if (!::status.isInitialized) return
        val mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val access = isAccessibilityEnabled()
        status.text = "Microfone: ${if (mic) "OK" else "permissão necessária"}   •   Acessibilidade: ${if (access) "ON" else "OFF"}"
        status.setTextColor(if (mic && access) Color.rgb(130, 230, 150) else Color.rgb(255, 190, 100))
        modeButton.text = "Modo partida contínuo: ${if (store.continuousMode) "LIGADO" else "DESLIGADO"}"
        calibrationStatus.text = store.calibrationSummary() + "\n\nPendente: ${store.pendingCalibration}"
        visionStatus.text = store.visionStatus
        speechLog.text = store.getRecognitionLog()
    }

    private fun isAccessibilityEnabled(): Boolean {
        val expected = ComponentName(this, VoiceAccessibilityService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    private fun requestMicrophoneIfNeeded(force: Boolean = false) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 10)
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
}
