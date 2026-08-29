package com.rlks.voicecontroller

import kotlin.math.abs

enum class CalibrationComponent(val spokenName: String) {
    BOARD("tabuleiro"),
    BENCH("banco"),
    SHOP("loja"),
    REROLL("rolar"),
    XP("XP"),
    SHOP_TOGGLE("botão da loja"),
    SELL("área de venda"),
    ITEMS("inventário de itens"),
    TRAITS("painel de sinergias"),
    CHOICES("tela de escolhas")
}

enum class CalibrationSource {
    AUTOMATIC,
    MANUAL,
    LEGACY
}

data class DisplayGeometry(
    val width: Int,
    val height: Int,
    val rotation: Int
) {
    fun isUsable(): Boolean = width > 0 && height > 0 && rotation >= 0

    fun matches(other: DisplayGeometry): Boolean {
        if (!isUsable() || !other.isUsable() || rotation != other.rotation) return false
        return abs(width - other.width) <= maxOf(8, width / 100) &&
            abs(height - other.height) <= maxOf(8, height / 100)
    }

    fun profileId(): String = "${width}x${height}_r$rotation"
}

data class CalibrationMetadata(
    val component: CalibrationComponent,
    val geometry: DisplayGeometry,
    val source: CalibrationSource,
    val confidence: Float,
    val validatedAt: Long,
    val interfaceProfileId: String,
    val formatVersion: Int = CURRENT_FORMAT_VERSION
) {
    fun isCurrent(
        current: DisplayGeometry,
        minimumConfidence: Float = MIN_GESTURE_CONFIDENCE
    ): Boolean = formatVersion == CURRENT_FORMAT_VERSION &&
        geometry.matches(current) &&
        source != CalibrationSource.LEGACY &&
        confidence >= minimumConfidence

    companion object {
        const val CURRENT_FORMAT_VERSION = 2
        const val MIN_GESTURE_CONFIDENCE = 0.82f
    }
}

data class CalibrationCandidate<T>(
    val component: CalibrationComponent,
    val value: T,
    val confidence: Float,
    val geometry: DisplayGeometry,
    val source: CalibrationSource = CalibrationSource.AUTOMATIC
)

/** Requires compatible candidates in consecutive frames before calibration is committed. */
class CalibrationStabilityTracker(
    private val requiredFrames: Int = 2,
    private val pointTolerance: Float = 0.025f
) {
    private data class Pending(val signature: List<Float>, val count: Int)
    private val pending = mutableMapOf<CalibrationComponent, Pending>()

    fun observe(component: CalibrationComponent, signature: List<Float>): Int {
        val previous = pending[component]
        val stable = previous != null && compatible(previous.signature, signature)
        val count = if (stable) previous!!.count + 1 else 1
        pending[component] = Pending(signature, count)
        return count
    }

    fun isConfirmed(component: CalibrationComponent): Boolean =
        (pending[component]?.count ?: 0) >= requiredFrames

    fun reset(component: CalibrationComponent? = null) {
        if (component == null) pending.clear() else pending.remove(component)
    }

    private fun compatible(first: List<Float>, second: List<Float>): Boolean =
        first.size == second.size && first.indices.all { abs(first[it] - second[it]) <= pointTolerance }
}

fun NormalizedPoint.signature(): List<Float> = listOf(x, y)
fun TftLine.signature(): List<Float> = listOf(first.x, first.y, last.x, last.y)
fun TftRow.signature(): List<Float> = listOf(left.x, left.y, right.x, right.y)
fun NormalizedRect.signature(): List<Float> = listOf(left, top, right, bottom)

object LegacyCalibrationMigration {
    fun componentsToMarkLegacy(
        geometryUsable: Boolean,
        componentsWithCoordinates: Set<CalibrationComponent>,
        componentsWithMetadata: Set<CalibrationComponent>
    ): Set<CalibrationComponent> = if (!geometryUsable) emptySet() else
        componentsWithCoordinates - componentsWithMetadata
}
