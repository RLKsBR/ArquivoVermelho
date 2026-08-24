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
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var store: ProfileStore
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = ProfileStore(this)
        setContentView(buildUi())
        requestMicrophoneIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        if (::status.isInitialized) refreshStatus()
    }

    private fun buildUi(): View {
        val pad = dp(20)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(Color.rgb(18, 20, 24))
        }

        root.addView(TextView(this).apply {
            text = "Voice Controller"
            textSize = 28f
            setTextColor(Color.WHITE)
        })

        root.addView(TextView(this).apply {
            text = "Voice → tap/drag, while the game stays open. No engine, no screen recording, no strategy automation."
            textSize = 15f
            setTextColor(Color.LTGRAY)
            setPadding(0, dp(8), 0, dp(16))
        })

        status = TextView(this).apply {
            textSize = 16f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, dp(12))
        }
        root.addView(status)

        root.addView(label("Profile"))
        val profiles = listOf(
            ProfileStore.PROFILE_LICHESS,
            ProfileStore.PROFILE_CHESS_COM,
            ProfileStore.PROFILE_TFT,
            ProfileStore.PROFILE_GENERIC
        )
        root.addView(Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, profiles)
            setSelection(profiles.indexOf(store.profile).coerceAtLeast(0))
            onItemSelectedListener = SimpleItemSelectedListener { store.profile = profiles[it] }
        })

        root.addView(label("Speech language"))
        val languages = listOf(ProfileStore.LANG_AUTO, ProfileStore.LANG_EN, ProfileStore.LANG_PT)
        root.addView(Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, languages)
            setSelection(languages.indexOf(store.language).coerceAtLeast(0))
            onItemSelectedListener = SimpleItemSelectedListener { store.language = languages[it] }
        })

        root.addView(button("1. Allow microphone") { requestMicrophoneIfNeeded(true) })
        root.addView(button("2. Enable Voice Controller in Accessibility") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })

        root.addView(TextView(this).apply {
            text = "QUICK CHESS TEST\n1) Enable the accessibility service.\n2) Open Lichess or Chess.com normally.\n3) Tap the floating 🎙 button.\n4) Say “calibrate board”, then tap the outer top-left and bottom-right corners of the board.\n5) Say moves with origin + destination: “E2 E4”, “G1 F3”, etc.\n6) Say “white orientation” or “black orientation” when needed.\n\nTFT / GENERIC\nSay “set reroll”, then tap the reroll button once. Later say “reroll” or “tap reroll”. You can create any named point this way. For drags: set two named points, then say “drag bench one to board back left”.\n\nThe mic is push-to-talk: it only listens after you tap the floating button, which keeps resource use low and plays nicer with Android's native screen recorder."
            textSize = 14f
            setTextColor(Color.LTGRAY)
            setPadding(0, dp(18), 0, 0)
        })

        refreshStatus()
        return root
    }

    private fun refreshStatus() {
        val mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val access = isAccessibilityEnabled()
        status.text = "Microphone: ${if (mic) "OK" else "permission needed"}   •   Accessibility: ${if (access) "ON" else "OFF"}"
        status.setTextColor(if (mic && access) Color.rgb(120, 220, 140) else Color.rgb(255, 190, 100))
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
            refreshStatus()
        }
    }

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        textSize = 14f
        setTextColor(Color.LTGRAY)
        setPadding(0, dp(10), 0, dp(4))
    }

    private fun button(text: String, action: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        setOnClickListener { action() }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}

private class SimpleItemSelectedListener(
    private val onSelected: (Int) -> Unit
) : android.widget.AdapterView.OnItemSelectedListener {
    override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) = onSelected(position)
    override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
}
