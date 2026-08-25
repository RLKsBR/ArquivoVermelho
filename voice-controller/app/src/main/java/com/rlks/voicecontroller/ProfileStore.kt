package com.rlks.voicecontroller

import android.content.Context

class ProfileStore(context: Context) {
    private val prefs = context.getSharedPreferences("voice_controller", Context.MODE_PRIVATE)

    var continuousMode: Boolean
        get() = prefs.getBoolean(KEY_CONTINUOUS_MODE, false)
        set(value) = prefs.edit().putBoolean(KEY_CONTINUOUS_MODE, value).apply()

    var pendingCalibration: String
        get() = prefs.getString(KEY_PENDING_CALIBRATION, PENDING_NONE) ?: PENDING_NONE
        set(value) = prefs.edit().putString(KEY_PENDING_CALIBRATION, value).apply()

    fun saveBoardRows(rows: Map<Int, TftRow>): Boolean {
        if ((1..4).any { rows[it] == null }) return false
        val editor = prefs.edit()
        for (rank in 1..4) {
            val row = rows.getValue(rank)
            editor.putString("tft_board_${rank}_left", encodePoint(row.left))
            editor.putString("tft_board_${rank}_right", encodePoint(row.right))
        }
        return editor.commit()
    }

    fun getBoardRows(): Map<Int, TftRow> {
        val rows = mutableMapOf<Int, TftRow>()
        for (rank in 1..4) {
            val left = decodePoint(prefs.getString("tft_board_${rank}_left", null))
            val right = decodePoint(prefs.getString("tft_board_${rank}_right", null))
            if (left != null && right != null) rows[rank] = TftRow(left, right)
        }
        return rows
    }

    fun saveBenchLine(first: NormalizedPoint, last: NormalizedPoint): Boolean =
        saveLine(KEY_BENCH_LINE, first, last)

    fun getBenchLine(): TftLine? = getLine(KEY_BENCH_LINE)

    fun saveShopLine(first: NormalizedPoint, last: NormalizedPoint): Boolean =
        saveLine(KEY_SHOP_LINE, first, last)

    fun getShopLine(): TftLine? = getLine(KEY_SHOP_LINE)

    fun savePoint(name: String, point: NormalizedPoint): Boolean =
        prefs.edit().putString("tft_point_${key(name)}", encodePoint(point)).commit()

    fun getPoint(name: String): NormalizedPoint? =
        decodePoint(prefs.getString("tft_point_${key(name)}", null))

    fun hasPoint(name: String): Boolean = getPoint(name) != null

    fun saveRegion(name: String, rect: NormalizedRect): Boolean =
        prefs.edit().putString(
            "tft_region_${key(name)}",
            "${rect.left},${rect.top},${rect.right},${rect.bottom}"
        ).commit()

    fun getRegion(name: String): NormalizedRect? {
        val raw = prefs.getString("tft_region_${key(name)}", null) ?: return null
        val p = raw.split(',').mapNotNull { it.toFloatOrNull() }
        if (p.size != 4) return null
        return NormalizedRect(p[0], p[1], p[2], p[3])
    }

    fun calibrationSummary(): String {
        fun mark(ok: Boolean) = if (ok) "✓" else "—"
        return buildString {
            append("${mark(getBoardRows().size == 4)} Tabuleiro A1–G4\n")
            append("${mark(getBenchLine() != null)} Banco 1–9\n")
            append("${mark(getShopLine() != null)} Loja 1–5\n")
            append("${mark(hasPoint(POINT_REROLL))} Rolar   ${mark(hasPoint(POINT_XP))} XP   ${mark(hasPoint(POINT_SHOP_TOGGLE))} Botão loja\n")
            append("${mark(hasPoint(POINT_SELL))} Venda   ${mark(getRegion(REGION_ITEMS) != null)} Itens   ${mark(getRegion(REGION_TRAITS) != null)} Sinergias\n")
            append("${mark(getRegion(REGION_CHOICES) != null)} Escolhas/aprimoramentos")
        }
    }

    fun addRecognitionLog(phrases: List<String>, parsed: String?) {
        val raw = if (phrases.isEmpty()) "<nada>" else phrases.take(6).joinToString(" | ")
        addLogEntry("PARSED: ${parsed ?: "UNKNOWN"}\nRAW: $raw")
    }

    fun addRecognitionError(text: String) = addLogEntry("ERROR: $text")

    fun getRecognitionLog(): String = prefs.getString(KEY_RECOGNITION_LOG, "").orEmpty()
        .replace(LOG_SEPARATOR, "\n\n")
        .ifBlank { "Nenhuma tentativa registrada ainda." }

    fun clearRecognitionLog() {
        prefs.edit().remove(KEY_RECOGNITION_LOG).apply()
    }

    var visionStatus: String
        get() = prefs.getString(KEY_VISION_STATUS, "Visão ainda não testada.") ?: "Visão ainda não testada."
        set(value) = prefs.edit().putString(KEY_VISION_STATUS, value).apply()

    private fun saveLine(key: String, first: NormalizedPoint, last: NormalizedPoint): Boolean =
        prefs.edit()
            .putString("${key}_first", encodePoint(first))
            .putString("${key}_last", encodePoint(last))
            .commit()

    private fun getLine(key: String): TftLine? {
        val first = decodePoint(prefs.getString("${key}_first", null)) ?: return null
        val last = decodePoint(prefs.getString("${key}_last", null)) ?: return null
        return TftLine(first, last)
    }

    private fun addLogEntry(entry: String) {
        val existing = prefs.getString(KEY_RECOGNITION_LOG, "").orEmpty()
            .split(LOG_SEPARATOR)
            .filter { it.isNotBlank() }
        prefs.edit().putString(KEY_RECOGNITION_LOG, (listOf(entry) + existing).take(12).joinToString(LOG_SEPARATOR)).apply()
    }

    private fun encodePoint(point: NormalizedPoint) = "${point.x},${point.y}"

    private fun decodePoint(raw: String?): NormalizedPoint? {
        val pieces = raw?.split(',') ?: return null
        if (pieces.size != 2) return null
        return NormalizedPoint(pieces[0].toFloatOrNull() ?: return null, pieces[1].toFloatOrNull() ?: return null)
    }

    private fun key(value: String) = value.lowercase().replace(Regex("[^a-z0-9]+"), "_")

    companion object {
        const val PENDING_NONE = "none"
        const val PENDING_BOARD = "board"
        const val PENDING_BENCH = "bench"
        const val PENDING_SHOP = "shop"
        const val PENDING_REROLL = "reroll"
        const val PENDING_XP = "xp"
        const val PENDING_SHOP_TOGGLE = "shop_toggle"
        const val PENDING_SELL = "sell"
        const val PENDING_ITEMS = "items"
        const val PENDING_TRAITS = "traits"
        const val PENDING_CHOICES = "choices"
        const val PENDING_SCREENSHOT_TEST = "screenshot_test"

        const val POINT_REROLL = "reroll"
        const val POINT_XP = "xp"
        const val POINT_SHOP_TOGGLE = "shop_toggle"
        const val POINT_SELL = "sell"

        const val REGION_ITEMS = "items"
        const val REGION_TRAITS = "traits"
        const val REGION_CHOICES = "choices"

        private const val KEY_CONTINUOUS_MODE = "tft_continuous_mode"
        private const val KEY_PENDING_CALIBRATION = "tft_pending_calibration"
        private const val KEY_BENCH_LINE = "tft_bench_line"
        private const val KEY_SHOP_LINE = "tft_shop_line"
        private const val KEY_RECOGNITION_LOG = "recognition_log"
        private const val KEY_VISION_STATUS = "tft_vision_status"
        private const val LOG_SEPARATOR = "\n---VC-ENTRY---\n"
    }
}
