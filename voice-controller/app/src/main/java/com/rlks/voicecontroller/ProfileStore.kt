package com.rlks.voicecontroller

import android.content.Context

class ProfileStore(context: Context) {
    private val prefs = context.getSharedPreferences("voice_controller", Context.MODE_PRIVATE)

    var profile: String
        get() = prefs.getString("profile", PROFILE_LICHESS) ?: PROFILE_LICHESS
        set(value) = prefs.edit().putString("profile", value).apply()

    var language: String
        get() = prefs.getString("language", LANG_AUTO) ?: LANG_AUTO
        set(value) = prefs.edit().putString("language", value).apply()

    fun isWhiteBottom(profile: String = this.profile): Boolean =
        prefs.getBoolean("orientation_${key(profile)}", true)

    fun setWhiteBottom(value: Boolean, profile: String = this.profile) {
        prefs.edit().putBoolean("orientation_${key(profile)}", value).apply()
    }

    fun saveBoardRect(rect: BoardRect, profile: String = this.profile) {
        prefs.edit()
            .putFloat("board_l_${key(profile)}", rect.left)
            .putFloat("board_t_${key(profile)}", rect.top)
            .putFloat("board_r_${key(profile)}", rect.right)
            .putFloat("board_b_${key(profile)}", rect.bottom)
            .apply()
    }

    fun getBoardRect(profile: String = this.profile): BoardRect? {
        val suffix = key(profile)
        if (!prefs.contains("board_l_$suffix")) return null
        return BoardRect(
            prefs.getFloat("board_l_$suffix", 0f),
            prefs.getFloat("board_t_$suffix", 0f),
            prefs.getFloat("board_r_$suffix", 1f),
            prefs.getFloat("board_b_$suffix", 1f)
        )
    }

    fun savePoint(name: String, x: Float, y: Float, profile: String = this.profile) {
        val pointKey = pointKey(profile, name)
        prefs.edit().putString(pointKey, "$x,$y").apply()
    }

    fun getPoint(name: String, profile: String = this.profile): NormalizedPoint? {
        val raw = prefs.getString(pointKey(profile, name), null) ?: return null
        val pieces = raw.split(',')
        if (pieces.size != 2) return null
        val x = pieces[0].toFloatOrNull() ?: return null
        val y = pieces[1].toFloatOrNull() ?: return null
        return NormalizedPoint(x, y)
    }

    fun hasPoint(name: String, profile: String = this.profile): Boolean =
        prefs.contains(pointKey(profile, name))

    fun addRecognitionLog(phrases: List<String>, parsed: String?) {
        val compactPhrases = if (phrases.isEmpty()) {
            "<nothing>"
        } else {
            phrases.take(6).joinToString(" | ")
        }
        val entry = "PARSED: ${parsed ?: "UNKNOWN"}\nRAW: $compactPhrases"
        val existing = prefs.getString(KEY_RECOGNITION_LOG, "").orEmpty()
            .split(LOG_SEPARATOR)
            .filter { it.isNotBlank() }
        val updated = (listOf(entry) + existing).take(12).joinToString(LOG_SEPARATOR)
        prefs.edit().putString(KEY_RECOGNITION_LOG, updated).apply()
    }

    fun addRecognitionError(text: String) {
        val existing = prefs.getString(KEY_RECOGNITION_LOG, "").orEmpty()
            .split(LOG_SEPARATOR)
            .filter { it.isNotBlank() }
        val entry = "ERROR: $text"
        val updated = (listOf(entry) + existing).take(12).joinToString(LOG_SEPARATOR)
        prefs.edit().putString(KEY_RECOGNITION_LOG, updated).apply()
    }

    fun getRecognitionLog(): String = prefs.getString(KEY_RECOGNITION_LOG, "").orEmpty()
        .replace(LOG_SEPARATOR, "\n\n")
        .ifBlank { "No speech attempts recorded yet." }

    fun clearRecognitionLog() {
        prefs.edit().remove(KEY_RECOGNITION_LOG).apply()
    }

    private fun pointKey(profile: String, name: String): String =
        "point_${key(profile)}_${VoiceCommandParser.canonicalName(name)}"

    private fun key(value: String): String = value.lowercase().replace(Regex("[^a-z0-9]+"), "_")

    companion object {
        const val PROFILE_LICHESS = "Lichess"
        const val PROFILE_CHESS_COM = "Chess.com"
        const val PROFILE_TFT = "TFT"
        const val PROFILE_GENERIC = "Generic"

        const val LANG_AUTO = "Auto"
        const val LANG_EN = "English"
        const val LANG_PT = "Português"

        private const val KEY_RECOGNITION_LOG = "recognition_log"
        private const val LOG_SEPARATOR = "\n---VC-ENTRY---\n"
    }
}

data class NormalizedPoint(val x: Float, val y: Float)
data class BoardRect(val left: Float, val top: Float, val right: Float, val bottom: Float)
