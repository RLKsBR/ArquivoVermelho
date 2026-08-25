package com.rlks.voicecontroller

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

class ProfileStore(context: Context) {
    private val prefs = context.getSharedPreferences("voice_controller", Context.MODE_PRIVATE)

    var continuousMode: Boolean
        get() = prefs.getBoolean(KEY_CONTINUOUS_MODE, false)
        set(value) = prefs.edit().putBoolean(KEY_CONTINUOUS_MODE, value).apply()

    var pendingCalibration: String
        get() = prefs.getString(KEY_PENDING_CALIBRATION, PENDING_NONE) ?: PENDING_NONE
        set(value) = prefs.edit().putString(KEY_PENDING_CALIBRATION, value).apply()

    var calibrationWizardActive: Boolean
        get() = prefs.getBoolean(KEY_CALIBRATION_WIZARD, false)
        set(value) = prefs.edit().putBoolean(KEY_CALIBRATION_WIZARD, value).apply()

    var foregroundPackage: String
        get() = prefs.getString(KEY_FOREGROUND_PACKAGE, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_FOREGROUND_PACKAGE, value).apply()

    var lastScreenshotUri: String
        get() = prefs.getString(KEY_LAST_SCREENSHOT_URI, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_LAST_SCREENSHOT_URI, value).apply()

    var lastReadText: String
        get() = prefs.getString(KEY_LAST_READ_TEXT, "Nenhuma leitura feita ainda.").orEmpty()
        set(value) = prefs.edit().putString(KEY_LAST_READ_TEXT, value).apply()

    var saveReadingScreenshots: Boolean
        get() = prefs.getBoolean(KEY_SAVE_READING_SCREENSHOTS, false)
        set(value) = prefs.edit().putBoolean(KEY_SAVE_READING_SCREENSHOTS, value).apply()

    fun saveChampionObservation(observation: ChampionObservation): Boolean {
        val updated = getRosterObservations()
            .filterNot { it.name.equals(observation.name, ignoreCase = true) }
            .plus(observation)
        return prefs.edit().putString(KEY_ROSTER_OBSERVATIONS, encodeRoster(updated)).commit()
    }

    fun getRosterObservations(): List<ChampionObservation> {
        val raw = prefs.getString(KEY_ROSTER_OBSERVATIONS, "[]").orEmpty()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                decodeChampion(array.optJSONObject(index))
            }
        }.getOrDefault(emptyList())
    }

    fun clearRosterObservations() {
        prefs.edit().remove(KEY_ROSTER_OBSERVATIONS).apply()
    }

    fun rosterSummary(): String {
        val champions = getRosterObservations()
        if (champions.isEmpty()) {
            return "Nenhum campeão registrado ainda."
        }
        return champions.joinToString("\n") { champion ->
            val items = when (champion.itemStatus) {
                ItemStatus.NONE -> "sem item"
                ItemStatus.EQUIPPED -> "com item"
                ItemStatus.UNKNOWN -> "itens desconhecidos"
            }
            val role = when (champion.role) {
                TacticalRole.FRONTLINE -> "frontline"
                TacticalRole.BACKLINE -> "backline"
                TacticalRole.UNKNOWN -> "função não marcada"
            }
            "${champion.name}: ${champion.maxHealth} de vida, valor ${champion.value}, $items, $role"
        }
    }

    fun saveBoardRows(rows: Map<Int, TftRow>): Boolean {
        if ((1..4).any { rows[it] == null }) return false
        val editor = prefs.edit()
        putBoardRows(editor, rows)
        return editor.commit()
    }

    fun saveCoreCalibration(
        calibration: TftCoreCalibration,
        displayWidth: Int,
        displayHeight: Int,
        rotation: Int
    ): Boolean {
        if ((1..4).any { calibration.boardRows[it] == null }) return false
        val editor = prefs.edit()
        putBoardRows(editor, calibration.boardRows)
        editor.putString("${KEY_BENCH_LINE}_first", encodePoint(calibration.benchLine.first))
        editor.putString("${KEY_BENCH_LINE}_last", encodePoint(calibration.benchLine.last))
        editor.putString("${KEY_SHOP_LINE}_first", encodePoint(calibration.shopLine.first))
        editor.putString("${KEY_SHOP_LINE}_last", encodePoint(calibration.shopLine.last))
        editor.putString("tft_point_${key(POINT_REROLL)}", encodePoint(calibration.reroll))
        editor.putString("tft_point_${key(POINT_XP)}", encodePoint(calibration.xp))
        editor.putString("tft_point_${key(POINT_SHOP_TOGGLE)}", encodePoint(calibration.shopToggle))
        editor.putString("tft_point_${key(POINT_SELL)}", encodePoint(calibration.sell))
        editor.putInt(KEY_CALIBRATION_WIDTH, displayWidth)
        editor.putInt(KEY_CALIBRATION_HEIGHT, displayHeight)
        editor.putInt(KEY_CALIBRATION_ROTATION, rotation)
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

    fun saveBenchLine(first: NormalizedPoint, last: NormalizedPoint): Boolean = saveLine(KEY_BENCH_LINE, first, last)
    fun getBenchLine(): TftLine? = getLine(KEY_BENCH_LINE)
    fun saveShopLine(first: NormalizedPoint, last: NormalizedPoint): Boolean = saveLine(KEY_SHOP_LINE, first, last)
    fun getShopLine(): TftLine? = getLine(KEY_SHOP_LINE)

    fun savePoint(name: String, point: NormalizedPoint): Boolean =
        prefs.edit().putString("tft_point_${key(name)}", encodePoint(point)).commit()

    fun getPoint(name: String): NormalizedPoint? =
        decodePoint(prefs.getString("tft_point_${key(name)}", null))

    fun hasPoint(name: String): Boolean = getPoint(name) != null

    fun saveRegion(name: String, rect: NormalizedRect): Boolean =
        prefs.edit().putString("tft_region_${key(name)}", encodeRect(rect)).commit()

    fun getRegion(name: String): NormalizedRect? {
        val raw = prefs.getString("tft_region_${key(name)}", null) ?: return null
        val pieces = raw.split(',').mapNotNull { it.toFloatOrNull() }
        if (pieces.size != 4) return null
        return NormalizedRect(pieces[0], pieces[1], pieces[2], pieces[3])
    }

    fun isCalibrationGeometryCurrent(width: Int, height: Int, rotation: Int): Boolean {
        val storedWidth = prefs.getInt(KEY_CALIBRATION_WIDTH, 0)
        val storedHeight = prefs.getInt(KEY_CALIBRATION_HEIGHT, 0)
        val storedRotation = prefs.getInt(KEY_CALIBRATION_ROTATION, -1)
        if (storedWidth <= 0 || storedHeight <= 0 || storedRotation < 0) return false
        return abs(width - storedWidth) <= maxOf(8, storedWidth / 100) &&
            abs(height - storedHeight) <= maxOf(8, storedHeight / 100) &&
            rotation == storedRotation
    }

    fun calibrationSummary(): String {
        fun mark(ok: Boolean) = if (ok) "✓" else "—"
        val width = prefs.getInt(KEY_CALIBRATION_WIDTH, 0)
        val height = prefs.getInt(KEY_CALIBRATION_HEIGHT, 0)
        return buildString {
            append("${mark(getBoardRows().size == 4)} Tabuleiro A1–G4\n")
            append("${mark(getBenchLine() != null)} Banco 1–9\n")
            append("${mark(getShopLine() != null)} Loja 1–5\n")
            append("${mark(hasPoint(POINT_REROLL))} Rolar   ${mark(hasPoint(POINT_XP))} XP   ${mark(hasPoint(POINT_SHOP_TOGGLE))} Botão loja\n")
            append("${mark(hasPoint(POINT_SELL))} Venda   ${mark(getRegion(REGION_ITEMS) != null)} Itens   ${mark(getRegion(REGION_TRAITS) != null)} Sinergias\n")
            append("${mark(getRegion(REGION_CHOICES) != null)} Escolhas/aprimoramentos\n")
            append(if (width > 0 && height > 0) "✓ Tela salva: ${width}×${height}" else "— Tela/orientação ainda não registradas")
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

    private fun putBoardRows(editor: SharedPreferences.Editor, rows: Map<Int, TftRow>) {
        for (rank in 1..4) {
            val row = rows.getValue(rank)
            editor.putString("tft_board_${rank}_left", encodePoint(row.left))
            editor.putString("tft_board_${rank}_right", encodePoint(row.right))
        }
    }

    private fun saveLine(key: String, first: NormalizedPoint, last: NormalizedPoint): Boolean =
        prefs.edit().putString("${key}_first", encodePoint(first))
            .putString("${key}_last", encodePoint(last)).commit()

    private fun getLine(key: String): TftLine? {
        val first = decodePoint(prefs.getString("${key}_first", null)) ?: return null
        val last = decodePoint(prefs.getString("${key}_last", null)) ?: return null
        return TftLine(first, last)
    }

    private fun addLogEntry(entry: String) {
        val existing = prefs.getString(KEY_RECOGNITION_LOG, "").orEmpty()
            .split(LOG_SEPARATOR).filter { it.isNotBlank() }
        prefs.edit().putString(
            KEY_RECOGNITION_LOG,
            (listOf(entry) + existing).take(12).joinToString(LOG_SEPARATOR)
        ).apply()
    }

    private fun encodePoint(point: NormalizedPoint) = "${point.x},${point.y}"
    private fun encodeRect(rect: NormalizedRect) = "${rect.left},${rect.top},${rect.right},${rect.bottom}"

    private fun decodePoint(raw: String?): NormalizedPoint? {
        val pieces = raw?.split(',') ?: return null
        if (pieces.size != 2) return null
        return NormalizedPoint(
            pieces[0].toFloatOrNull() ?: return null,
            pieces[1].toFloatOrNull() ?: return null
        )
    }

    private fun encodeRoster(champions: List<ChampionObservation>): String {
        val array = JSONArray()
        champions.forEach { champion ->
            array.put(
                JSONObject()
                    .put("name", champion.name)
                    .put("maxHealth", champion.maxHealth)
                    .put("value", champion.value)
                    .put("itemStatus", champion.itemStatus.name)
                    .put("role", champion.role.name)
            )
        }
        return array.toString()
    }

    private fun decodeChampion(json: JSONObject?): ChampionObservation? {
        json ?: return null
        val name = json.optString("name").trim()
        val health = json.optInt("maxHealth", 0)
        val value = json.optInt("value", 0)
        if (name.isBlank() || health <= 0 || value <= 0) return null
        val items = runCatching {
            ItemStatus.valueOf(json.optString("itemStatus", ItemStatus.UNKNOWN.name))
        }.getOrDefault(ItemStatus.UNKNOWN)
        val role = runCatching {
            TacticalRole.valueOf(json.optString("role", TacticalRole.UNKNOWN.name))
        }.getOrDefault(TacticalRole.UNKNOWN)
        return ChampionObservation(name, health, value, items, role)
    }

    private fun key(value: String) = value.lowercase().replace(Regex("[^a-z0-9]+"), "_")

    companion object {
        const val PENDING_NONE = "none"
        const val PENDING_ALL = "all"
        const val PENDING_CORE = "core"
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
        private const val KEY_CALIBRATION_WIZARD = "tft_calibration_wizard"
        private const val KEY_CALIBRATION_WIDTH = "tft_calibration_width"
        private const val KEY_CALIBRATION_HEIGHT = "tft_calibration_height"
        private const val KEY_CALIBRATION_ROTATION = "tft_calibration_rotation"
        private const val KEY_FOREGROUND_PACKAGE = "tft_foreground_package"
        private const val KEY_LAST_SCREENSHOT_URI = "tft_last_screenshot_uri"
        private const val KEY_LAST_READ_TEXT = "tft_last_read_text"
        private const val KEY_SAVE_READING_SCREENSHOTS = "tft_save_reading_screenshots"
        private const val KEY_ROSTER_OBSERVATIONS = "tft_roster_observations"
        private const val KEY_BENCH_LINE = "tft_bench_line"
        private const val KEY_SHOP_LINE = "tft_shop_line"
        private const val KEY_RECOGNITION_LOG = "recognition_log"
        private const val KEY_VISION_STATUS = "tft_vision_status"
        private const val LOG_SEPARATOR = "\n---VC-ENTRY---\n"
    }
}
