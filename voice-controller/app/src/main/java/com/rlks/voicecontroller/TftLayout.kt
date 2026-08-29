package com.rlks.voicecontroller

data class NormalizedPoint(val x: Float, val y: Float)

data class NormalizedRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    fun center(): NormalizedPoint = NormalizedPoint((left + right) / 2f, (top + bottom) / 2f)

    fun horizontalChoice(index: Int, count: Int): NormalizedPoint? {
        if (count <= 0 || index !in 1..count) return null
        val width = right - left
        val x = left + width * ((index - 0.5f) / count.toFloat())
        return NormalizedPoint(x, (top + bottom) / 2f)
    }
}

data class TftRow(val left: NormalizedPoint, val right: NormalizedPoint)
data class TftLine(val first: NormalizedPoint, val last: NormalizedPoint)

enum class HorizontalZone { LEFT, CENTER, RIGHT }

data class TacticalPosition(
    val rowFromFront: Int,
    val horizontal: HorizontalZone = HorizontalZone.CENTER
)

data class TftCoreCalibration(
    val boardRows: Map<Int, TftRow>,
    val benchLine: TftLine,
    val shopLine: TftLine,
    val reroll: NormalizedPoint,
    val xp: NormalizedPoint,
    val shopToggle: NormalizedPoint,
    val sell: NormalizedPoint
)

data class TftCalibration(
    val boardRows: Map<Int, TftRow>,
    val benchLine: TftLine,
    val shopLine: TftLine,
    val reroll: NormalizedPoint,
    val xp: NormalizedPoint,
    val shopToggle: NormalizedPoint,
    val sell: NormalizedPoint,
    val items: NormalizedRect,
    val traits: NormalizedRect,
    val choices: NormalizedRect
)

object TftLayout {
    fun boardSquareToPoint(square: String, rows: Map<Int, TftRow>): NormalizedPoint? {
        val clean = square.lowercase().replace(" ", "")
        val match = Regex("^([a-g])([1-4])$").matchEntire(clean) ?: return null
        val fileIndex = match.groupValues[1][0] - 'a'
        val rank = match.groupValues[2].toInt()
        val row = rows[rank] ?: return null
        return interpolate(row.left, row.right, fileIndex, 7)
    }

    fun benchSlotToPoint(slot: Int, line: TftLine?): NormalizedPoint? {
        if (line == null || slot !in 1..9) return null
        return interpolate(line.first, line.last, slot - 1, 9)
    }

    fun shopSlotToPoint(slot: Int, line: TftLine?): NormalizedPoint? {
        if (line == null || slot !in 1..5) return null
        return interpolate(line.first, line.last, slot - 1, 5)
    }

    fun tacticalPoint(position: TacticalPosition, rows: Map<Int, TftRow>): NormalizedPoint? {
        if (position.rowFromFront !in 1..4 || rows.size < 4) return null
        val orderedFrontToBack = rows.values.sortedBy { (it.left.y + it.right.y) / 2f }
        val row = orderedFrontToBack.getOrNull(position.rowFromFront - 1) ?: return null
        val columnIndex = when (position.horizontal) {
            HorizontalZone.LEFT -> 0
            HorizontalZone.CENTER -> 3
            HorizontalZone.RIGHT -> 6
        }
        return interpolate(row.left, row.right, columnIndex, 7)
    }

    private fun interpolate(first: NormalizedPoint, last: NormalizedPoint, index: Int, count: Int): NormalizedPoint {
        if (count <= 1) return first
        val t = index.toFloat() / (count - 1).toFloat()
        return NormalizedPoint(first.x + (last.x - first.x) * t, first.y + (last.y - first.y) * t)
    }
}

