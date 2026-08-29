package com.rlks.voicecontroller

import kotlin.math.abs

data class AutoCalibrationResult(
    val shopLine: TftLine?,
    val reroll: NormalizedPoint?,
    val xp: NormalizedPoint?,
    val shopToggle: NormalizedPoint?,
    val sell: NormalizedPoint?,
    val contexts: List<ContextDetection>
)

object AutoCalibrationDetector {
    fun detect(lines: List<RecognizedTextLine>): AutoCalibrationResult {
        val normalized = lines.map { it to ItemRecipeBook.normalize(it.text) }
        val reroll = normalized.firstOrNull { (line, text) ->
            line.region.top > 0.55f && listOf("rolar", "atualizar", "refresh").any { it in text }
        }?.first?.region?.center()
        val xp = normalized.firstOrNull { (line, text) ->
            line.region.top > 0.55f && (text == "xp" || "comprar xp" in text || "nivel" in text)
        }?.first?.region?.center()
        val shopToggle = normalized.firstOrNull { (line, text) ->
            line.region.top > 0.5f &&
                (text == "loja" || "abrir loja" in text || "fechar loja" in text)
        }?.first?.region?.center()
        val sell = normalized.firstOrNull { (line, text) ->
            line.region.top > 0.58f &&
                (text == "vender" || "venda" in text || "arraste para vender" in text)
        }?.first?.region?.center()

        val lowerCenters = lines.filter { line ->
            val center = line.region.center()
            center.y in 0.66f..0.96f && center.x in 0.08f..0.92f && line.text.length >= 2
        }.map { it.region.center() }.sortedBy { it.x }
        val clusters = mutableListOf<MutableList<NormalizedPoint>>()
        lowerCenters.forEach { point ->
            val existing = clusters.lastOrNull()
            if (existing == null || abs(existing.map { it.x }.average().toFloat() - point.x) > 0.075f) {
                clusters += mutableListOf(point)
            } else {
                existing += point
            }
        }
        val likelyCards = clusters.filter { cluster -> cluster.size >= 1 }
            .sortedByDescending { it.size }.take(5).sortedBy { it.map { p -> p.x }.average() }
        val shopLine = if (likelyCards.size == 5) {
            val points = likelyCards.map { cluster ->
                NormalizedPoint(
                    cluster.map { it.x }.average().toFloat(),
                    cluster.map { it.y }.average().toFloat()
                )
            }
            val span = points.last().x - points.first().x
            if (span >= 0.42f) TftLine(points.first(), points.last()) else null
        } else null

        return AutoCalibrationResult(
            shopLine, reroll, xp, shopToggle, sell, ContextScreenDetector.detect(lines)
        )
    }
}
