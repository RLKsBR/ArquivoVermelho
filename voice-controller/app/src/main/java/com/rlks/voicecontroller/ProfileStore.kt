package com.rlks.voicecontroller

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

class ProfileStore(context: Context) {
    private val prefs = context.getSharedPreferences("voice_controller", Context.MODE_PRIVATE)

    init {
        migrateLegacyCalibration()
    }

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

    var autoContextCalibration: Boolean
        get() = prefs.getBoolean(KEY_AUTO_CONTEXT_CALIBRATION, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_CONTEXT_CALIBRATION, value).apply()

    var autoCalibrationStatus: String
        get() = prefs.getString(
            KEY_AUTO_CALIBRATION_STATUS,
            "Autocalibração aguardando telas contextuais."
        ).orEmpty()
        set(value) = prefs.edit().putString(KEY_AUTO_CALIBRATION_STATUS, value).apply()

    var sensitiveConfirmationEnabled: Boolean
        get() = prefs.getBoolean(KEY_SENSITIVE_CONFIRMATION, true)
        set(value) = prefs.edit().putBoolean(KEY_SENSITIVE_CONFIRMATION, value).apply()

    var lastGestureBlockedReason: String
        get() = prefs.getString(KEY_LAST_GESTURE_BLOCKED, "Nenhum gesto bloqueado.").orEmpty()
        set(value) = prefs.edit().putString(KEY_LAST_GESTURE_BLOCKED, value).apply()

    var lastRecognizedScreen: String
        get() = prefs.getString(KEY_LAST_RECOGNIZED_SCREEN, "Desconhecida").orEmpty()
        set(value) = prefs.edit().putString(KEY_LAST_RECOGNIZED_SCREEN, value).apply()

    fun recordScreenshot(success: Boolean) {
        val key = if (success) KEY_SCREENSHOT_COUNT else KEY_SCREENSHOT_FAILURES
        prefs.edit().putLong(key, prefs.getLong(key, 0L) + 1L).apply()
    }

    fun recordOcr(durationMillis: Long) {
        prefs.edit()
            .putLong(KEY_OCR_COUNT, prefs.getLong(KEY_OCR_COUNT, 0L) + 1L)
            .putLong(KEY_OCR_TOTAL_MS, prefs.getLong(KEY_OCR_TOTAL_MS, 0L) + durationMillis.coerceAtLeast(0L))
            .putLong(KEY_OCR_MAX_MS, maxOf(prefs.getLong(KEY_OCR_MAX_MS, 0L), durationMillis))
            .apply()
    }

    fun recordSkippedFrame() {
        prefs.edit().putLong(KEY_SKIPPED_FRAMES, prefs.getLong(KEY_SKIPPED_FRAMES, 0L) + 1L).apply()
    }

    var captureQueueDepth: Int
        get() = prefs.getInt(KEY_CAPTURE_QUEUE_DEPTH, 0)
        set(value) = prefs.edit().putInt(KEY_CAPTURE_QUEUE_DEPTH, value.coerceAtLeast(0)).apply()

    fun diagnosticsSummary(): String {
        val ocrCount = prefs.getLong(KEY_OCR_COUNT, 0L)
        val total = prefs.getLong(KEY_OCR_TOTAL_MS, 0L)
        val average = if (ocrCount == 0L) 0L else total / ocrCount
        return buildString {
            append("Screenshots: ${prefs.getLong(KEY_SCREENSHOT_COUNT, 0L)}\n")
            append("Falhas de screenshot: ${prefs.getLong(KEY_SCREENSHOT_FAILURES, 0L)}\n")
            append("OCRs executados: $ocrCount\n")
            append("Frames sem mudança ignorados: ${prefs.getLong(KEY_SKIPPED_FRAMES, 0L)}\n")
            append("OCR médio: ${average} ms; máximo: ${prefs.getLong(KEY_OCR_MAX_MS, 0L)} ms\n")
            append("Fila de captura: $captureQueueDepth\n")
            append("Tela reconhecida: $lastRecognizedScreen\n")
            val metadata = CalibrationComponent.entries.mapNotNull(::getCalibrationMetadata)
            val confidence = metadata.minOfOrNull { it.confidence }
            append("Menor confiança calibrada: ${confidence?.let(::percent) ?: "indisponível"}\n")
            append("Último gesto bloqueado: $lastGestureBlockedReason")
        }
    }

    fun saveActiveSynergies(names: Set<String>) {
        prefs.edit().putStringSet(KEY_ACTIVE_SYNERGIES, names).apply()
    }

    fun getActiveSynergies(): Set<String> =
        prefs.getStringSet(KEY_ACTIVE_SYNERGIES, emptySet()).orEmpty().toSet()

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
            val traits = if (champion.traits.isEmpty()) "sinergias não informadas"
            else "sinergias ${champion.traits.joinToString(", ")}"
            "${champion.name}: ${champion.maxHealth} de vida, valor ${champion.value}, $items, $role, $traits"
        }
    }

    fun saveBoardRows(rows: Map<Int, TftRow>): Boolean {
        if ((1..4).any { rows[it] == null }) return false
        val editor = prefs.edit()
        putBoardRows(editor, rows)
        return editor.commit()
    }

    fun saveBoardRows(
        rows: Map<Int, TftRow>,
        metadata: CalibrationMetadata
    ): Boolean {
        if ((1..4).any { rows[it] == null } || metadata.component != CalibrationComponent.BOARD) return false
        val editor = prefs.edit()
        putBoardRows(editor, rows)
        putCalibrationMetadata(editor, metadata)
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
        val geometry = DisplayGeometry(displayWidth, displayHeight, rotation)
        val now = System.currentTimeMillis()
        listOf(
            CalibrationComponent.BOARD,
            CalibrationComponent.BENCH,
            CalibrationComponent.SHOP,
            CalibrationComponent.REROLL,
            CalibrationComponent.XP,
            CalibrationComponent.SHOP_TOGGLE,
            CalibrationComponent.SELL
        ).forEach { component ->
            putCalibrationMetadata(
                editor,
                CalibrationMetadata(
                    component = component,
                    geometry = geometry,
                    source = CalibrationSource.MANUAL,
                    confidence = 0.99f,
                    validatedAt = now,
                    interfaceProfileId = geometry.profileId()
                )
            )
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

    fun saveBenchLine(first: NormalizedPoint, last: NormalizedPoint): Boolean = saveLine(KEY_BENCH_LINE, first, last)
    fun getBenchLine(): TftLine? = getLine(KEY_BENCH_LINE)
    fun saveShopLine(first: NormalizedPoint, last: NormalizedPoint): Boolean = saveLine(KEY_SHOP_LINE, first, last)
    fun getShopLine(): TftLine? = getLine(KEY_SHOP_LINE)

    fun saveLineWithMetadata(
        component: CalibrationComponent,
        first: NormalizedPoint,
        last: NormalizedPoint,
        metadata: CalibrationMetadata
    ): Boolean {
        val lineKey = when (component) {
            CalibrationComponent.BENCH -> KEY_BENCH_LINE
            CalibrationComponent.SHOP -> KEY_SHOP_LINE
            else -> return false
        }
        if (metadata.component != component) return false
        val editor = prefs.edit()
            .putString("${lineKey}_first", encodePoint(first))
            .putString("${lineKey}_last", encodePoint(last))
        putCalibrationMetadata(editor, metadata)
        return editor.commit()
    }

    fun savePoint(name: String, point: NormalizedPoint): Boolean =
        prefs.edit().putString("tft_point_${key(name)}", encodePoint(point)).commit()

    fun savePointWithMetadata(
        name: String,
        point: NormalizedPoint,
        metadata: CalibrationMetadata
    ): Boolean {
        if (componentForPoint(name) != metadata.component) return false
        val editor = prefs.edit().putString("tft_point_${key(name)}", encodePoint(point))
        putCalibrationMetadata(editor, metadata)
        return editor.commit()
    }

    fun getPoint(name: String): NormalizedPoint? =
        decodePoint(prefs.getString("tft_point_${key(name)}", null))

    fun hasPoint(name: String): Boolean = getPoint(name) != null

    fun saveRegion(name: String, rect: NormalizedRect): Boolean =
        prefs.edit().putString("tft_region_${key(name)}", encodeRect(rect)).commit()

    fun saveRegionWithMetadata(
        name: String,
        rect: NormalizedRect,
        metadata: CalibrationMetadata
    ): Boolean {
        if (componentForRegion(name) != metadata.component) return false
        val editor = prefs.edit().putString("tft_region_${key(name)}", encodeRect(rect))
        putCalibrationMetadata(editor, metadata)
        return editor.commit()
    }

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

    fun getCalibrationMetadata(component: CalibrationComponent): CalibrationMetadata? {
        val raw = prefs.getString(metadataKey(component), null) ?: return null
        return runCatching {
            val json = JSONObject(raw)
            CalibrationMetadata(
                component = component,
                geometry = DisplayGeometry(
                    json.getInt("width"),
                    json.getInt("height"),
                    json.getInt("rotation")
                ),
                source = CalibrationSource.valueOf(json.getString("source")),
                confidence = json.getDouble("confidence").toFloat(),
                validatedAt = json.getLong("validatedAt"),
                interfaceProfileId = json.getString("profileId"),
                formatVersion = json.getInt("formatVersion")
            )
        }.getOrNull()
    }

    fun isComponentCalibrationCurrent(
        component: CalibrationComponent,
        geometry: DisplayGeometry,
        minimumConfidence: Float = CalibrationMetadata.MIN_GESTURE_CONFIDENCE
    ): Boolean = componentHasCoordinates(component) &&
        getCalibrationMetadata(component)?.isCurrent(geometry, minimumConfidence) == true

    fun missingOrUnsafeComponents(geometry: DisplayGeometry): List<CalibrationComponent> =
        CalibrationComponent.entries.filterNot { isComponentCalibrationCurrent(it, geometry) }

    fun componentStatus(component: CalibrationComponent, geometry: DisplayGeometry? = null): String {
        if (!componentHasCoordinates(component)) return "pendente"
        val metadata = getCalibrationMetadata(component) ?: return "legada; revalidação necessária"
        if (metadata.source == CalibrationSource.LEGACY) return "legada; revalidação necessária"
        if (geometry != null && !metadata.geometry.matches(geometry)) return "de outra tela; revalidação necessária"
        if (metadata.confidence < CalibrationMetadata.MIN_GESTURE_CONFIDENCE) {
            return "confiança ${percent(metadata.confidence)}; bloqueada"
        }
        return "${metadata.source.name.lowercase()}, confiança ${percent(metadata.confidence)}"
    }

    fun saveCalibrationGeometry(width: Int, height: Int, rotation: Int): Boolean =
        prefs.edit()
            .putInt(KEY_CALIBRATION_WIDTH, width)
            .putInt(KEY_CALIBRATION_HEIGHT, height)
            .putInt(KEY_CALIBRATION_ROTATION, rotation)
            .commit()

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
            append("\nAutocalibração contextual: ${if (autoContextCalibration) "ligada" else "desligada"}")
            append("\nPerfis v${CalibrationMetadata.CURRENT_FORMAT_VERSION}: ")
            append(CalibrationComponent.entries.count { getCalibrationMetadata(it)?.source != CalibrationSource.LEGACY })
            append("/${CalibrationComponent.entries.size} revalidados")
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

    private fun putCalibrationMetadata(
        editor: SharedPreferences.Editor,
        metadata: CalibrationMetadata
    ) {
        val json = JSONObject()
            .put("width", metadata.geometry.width)
            .put("height", metadata.geometry.height)
            .put("rotation", metadata.geometry.rotation)
            .put("source", metadata.source.name)
            .put("confidence", metadata.confidence.coerceIn(0f, 1f).toDouble())
            .put("validatedAt", metadata.validatedAt)
            .put("profileId", metadata.interfaceProfileId)
            .put("formatVersion", metadata.formatVersion)
        editor.putString(metadataKey(metadata.component), json.toString())
    }

    private fun migrateLegacyCalibration() {
        val geometry = DisplayGeometry(
            prefs.getInt(KEY_CALIBRATION_WIDTH, 0),
            prefs.getInt(KEY_CALIBRATION_HEIGHT, 0),
            prefs.getInt(KEY_CALIBRATION_ROTATION, -1)
        )
        if (!geometry.isUsable()) return
        val editor = prefs.edit()
        var changed = false
        val withCoordinates = CalibrationComponent.entries.filter { componentHasCoordinates(it) }.toSet()
        val withMetadata = CalibrationComponent.entries.filter {
            prefs.getString(metadataKey(it), null) != null
        }.toSet()
        LegacyCalibrationMigration.componentsToMarkLegacy(
            geometry.isUsable(), withCoordinates, withMetadata
        ).forEach { component ->
                putCalibrationMetadata(
                    editor,
                    CalibrationMetadata(
                        component = component,
                        geometry = geometry,
                        source = CalibrationSource.LEGACY,
                        confidence = 0.35f,
                        validatedAt = 0L,
                        interfaceProfileId = "legacy_${geometry.profileId()}"
                    )
                )
                changed = true
        }
        if (changed) editor.commit()
    }

    private fun componentHasCoordinates(component: CalibrationComponent): Boolean = when (component) {
        CalibrationComponent.BOARD -> getBoardRows().size == 4
        CalibrationComponent.BENCH -> getBenchLine() != null
        CalibrationComponent.SHOP -> getShopLine() != null
        CalibrationComponent.REROLL -> hasPoint(POINT_REROLL)
        CalibrationComponent.XP -> hasPoint(POINT_XP)
        CalibrationComponent.SHOP_TOGGLE -> hasPoint(POINT_SHOP_TOGGLE)
        CalibrationComponent.SELL -> hasPoint(POINT_SELL)
        CalibrationComponent.ITEMS -> getRegion(REGION_ITEMS) != null
        CalibrationComponent.TRAITS -> getRegion(REGION_TRAITS) != null
        CalibrationComponent.CHOICES -> getRegion(REGION_CHOICES) != null
    }

    private fun componentForPoint(name: String): CalibrationComponent? = when (name) {
        POINT_REROLL -> CalibrationComponent.REROLL
        POINT_XP -> CalibrationComponent.XP
        POINT_SHOP_TOGGLE -> CalibrationComponent.SHOP_TOGGLE
        POINT_SELL -> CalibrationComponent.SELL
        else -> null
    }

    private fun componentForRegion(name: String): CalibrationComponent? = when (name) {
        REGION_ITEMS -> CalibrationComponent.ITEMS
        REGION_TRAITS -> CalibrationComponent.TRAITS
        REGION_CHOICES -> CalibrationComponent.CHOICES
        else -> null
    }

    private fun metadataKey(component: CalibrationComponent): String =
        "${KEY_COMPONENT_METADATA}_${component.name.lowercase()}"

    private fun percent(value: Float): String = "%.0f%%".format(value.coerceIn(0f, 1f) * 100f)

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
                    .put("traits", JSONArray(champion.traits.toList()))
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
        val traitsArray = json.optJSONArray("traits") ?: JSONArray()
        val traits = (0 until traitsArray.length())
            .mapNotNull { traitsArray.optString(it).trim().takeIf { value -> value.isNotBlank() } }
            .toSet()
        return ChampionObservation(name, health, value, items, role, traits)
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
        private const val KEY_AUTO_CONTEXT_CALIBRATION = "tft_auto_context_calibration"
        private const val KEY_AUTO_CALIBRATION_STATUS = "tft_auto_calibration_status"
        private const val KEY_SENSITIVE_CONFIRMATION = "tft_sensitive_confirmation"
        private const val KEY_LAST_GESTURE_BLOCKED = "tft_last_gesture_blocked"
        private const val KEY_LAST_RECOGNIZED_SCREEN = "tft_last_recognized_screen"
        private const val KEY_SCREENSHOT_COUNT = "tft_diagnostic_screenshots"
        private const val KEY_SCREENSHOT_FAILURES = "tft_diagnostic_screenshot_failures"
        private const val KEY_OCR_COUNT = "tft_diagnostic_ocr_count"
        private const val KEY_OCR_TOTAL_MS = "tft_diagnostic_ocr_total_ms"
        private const val KEY_OCR_MAX_MS = "tft_diagnostic_ocr_max_ms"
        private const val KEY_SKIPPED_FRAMES = "tft_diagnostic_skipped_frames"
        private const val KEY_CAPTURE_QUEUE_DEPTH = "tft_diagnostic_capture_queue_depth"
        private const val KEY_ACTIVE_SYNERGIES = "tft_active_synergies"
        private const val KEY_ROSTER_OBSERVATIONS = "tft_roster_observations"
        private const val KEY_BENCH_LINE = "tft_bench_line"
        private const val KEY_SHOP_LINE = "tft_shop_line"
        private const val KEY_RECOGNITION_LOG = "recognition_log"
        private const val KEY_VISION_STATUS = "tft_vision_status"
        private const val KEY_COMPONENT_METADATA = "tft_component_metadata"
        private const val LOG_SEPARATOR = "\n---VC-ENTRY---\n"
    }
}
