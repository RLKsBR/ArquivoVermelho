package com.rlks.voicecontroller

import kotlin.math.abs

data class VisualLayoutCandidate(
    val boardRows: Map<Int, TftRow>?,
    val benchLine: TftLine?,
    val confidence: Float,
    val reason: String
)

/**
 * Conservative edge-based candidate detector. It never commits coordinates itself.
 * Real-device validation is still required before this can be considered production-ready.
 */
object VisualLayoutDetector {
    fun detect(
        argb: IntArray,
        width: Int,
        height: Int,
        shopLine: TftLine?
    ): VisualLayoutCandidate {
        if (width < 120 || height < 80 || width <= height || argb.size < width * height) {
            return VisualLayoutCandidate(null, null, 0f, "imagem incompatível")
        }
        val gray = IntArray(width * height) { index -> luminance(argb[index]) }
        val horizontal = FloatArray(height)
        for (y in 1 until height) {
            var sum = 0L
            for (x in width / 12 until width * 11 / 12) {
                sum += abs(gray[y * width + x] - gray[(y - 1) * width + x])
            }
            horizontal[y] = sum.toFloat() / (width * 5f / 6f)
        }
        val shopY = shopLine?.let { (it.first.y + it.last.y) / 2f } ?: 0.86f
        val benchMin = (height * (shopY - 0.31f).coerceAtLeast(0.48f)).toInt()
        val benchMax = (height * (shopY - 0.07f).coerceAtMost(0.80f)).toInt()
        val benchY = strongest(horizontal, benchMin, benchMax) ?: return VisualLayoutCandidate(
            null, null, 0.2f, "linha do banco não encontrada"
        )
        val boardMax = (benchY - height * 0.055f).toInt()
        val boardPeaks = separatedPeaks(
            horizontal,
            (height * 0.18f).toInt(),
            boardMax,
            required = 4,
            separation = (height * 0.055f).toInt().coerceAtLeast(3)
        ).sorted()
        if (boardPeaks.size != 4) {
            return VisualLayoutCandidate(null, null, 0.35f, "quatro fileiras não ficaram estáveis")
        }
        val vertical = FloatArray(width)
        val yStart = boardPeaks.first().coerceAtLeast(1)
        val yEnd = benchY.coerceAtMost(height - 1)
        for (x in 1 until width) {
            var sum = 0L
            for (y in yStart..yEnd) {
                sum += abs(gray[y * width + x] - gray[y * width + x - 1])
            }
            vertical[x] = sum.toFloat() / (yEnd - yStart + 1).coerceAtLeast(1)
        }
        val left = strongest(vertical, width / 18, width * 5 / 12) ?: return VisualLayoutCandidate(
            null, null, 0.4f, "limite esquerdo não encontrado"
        )
        val right = strongest(vertical, width * 7 / 12, width * 17 / 18) ?: return VisualLayoutCandidate(
            null, null, 0.4f, "limite direito não encontrado"
        )
        if (right - left < width * 0.42f) {
            return VisualLayoutCandidate(null, null, 0.45f, "largura visual insuficiente")
        }
        val averageEnergy = horizontal.average().toFloat().coerceAtLeast(1f)
        val rowStrength = boardPeaks.map { horizontal[it] / averageEnergy }.average().toFloat()
        val benchStrength = horizontal[benchY] / averageEnergy
        val regularity = rowRegularity(boardPeaks, height)
        val confidence = (
            0.48f +
                (rowStrength / 12f).coerceIn(0f, 0.18f) +
                (benchStrength / 12f).coerceIn(0f, 0.14f) +
                regularity * 0.14f
            ).coerceIn(0f, 0.94f)
        val leftX = left.toFloat() / width
        val rightX = right.toFloat() / width
        val rows = boardPeaks.mapIndexed { index, y ->
            index + 1 to TftRow(
                NormalizedPoint(leftX, y.toFloat() / height),
                NormalizedPoint(rightX, y.toFloat() / height)
            )
        }.toMap()
        val bench = TftLine(
            NormalizedPoint(leftX, benchY.toFloat() / height),
            NormalizedPoint(rightX, benchY.toFloat() / height)
        )
        return VisualLayoutCandidate(rows, bench, confidence, "candidato visual experimental")
    }

    private fun strongest(values: FloatArray, start: Int, end: Int): Int? {
        val from = start.coerceIn(0, values.lastIndex)
        val to = end.coerceIn(from, values.lastIndex)
        return (from..to).maxByOrNull { values[it] }?.takeIf { values[it] > 3f }
    }

    private fun separatedPeaks(
        values: FloatArray,
        start: Int,
        end: Int,
        required: Int,
        separation: Int
    ): List<Int> {
        val candidates = (start.coerceAtLeast(1)..end.coerceAtMost(values.lastIndex - 1))
            .filter { values[it] >= values[it - 1] && values[it] >= values[it + 1] }
            .sortedByDescending { values[it] }
        val selected = mutableListOf<Int>()
        for (candidate in candidates) {
            if (selected.none { abs(it - candidate) < separation }) selected += candidate
            if (selected.size == required) break
        }
        return selected
    }

    private fun rowRegularity(rows: List<Int>, height: Int): Float {
        val gaps = rows.zipWithNext { first, second -> second - first }
        if (gaps.size != 3) return 0f
        val average = gaps.average().toFloat()
        if (average <= 0f) return 0f
        val deviation = gaps.maxOf { abs(it - average) } / height.toFloat()
        return (1f - deviation / 0.08f).coerceIn(0f, 1f)
    }

    private fun luminance(color: Int): Int {
        val red = color shr 16 and 0xff
        val green = color shr 8 and 0xff
        val blue = color and 0xff
        return (red * 3 + green * 6 + blue) / 10
    }
}
