package com.rlks.voicecontroller

import kotlin.math.max
import kotlin.math.min

object VisionRegion {
    fun shopFromLine(line: TftLine?): NormalizedRect? {
        line ?: return null
        return clamp(
            NormalizedRect(
                left = min(line.first.x, line.last.x) - 0.035f,
                top = min(line.first.y, line.last.y) - 0.16f,
                right = max(line.first.x, line.last.x) + 0.035f,
                bottom = max(line.first.y, line.last.y) + 0.16f
            )
        )
    }

    fun boardFromRows(rows: Map<Int, TftRow>): NormalizedRect? {
        if ((1..4).any { rows[it] == null }) return null
        val points = rows.values.flatMap { listOf(it.left, it.right) }
        return clamp(
            NormalizedRect(
                left = points.minOf { it.x } - 0.04f,
                top = points.minOf { it.y } - 0.1f,
                right = points.maxOf { it.x } + 0.04f,
                bottom = points.maxOf { it.y } + 0.1f
            )
        )
    }

    fun clamp(rect: NormalizedRect): NormalizedRect = NormalizedRect(
        rect.left.coerceIn(0f, 1f),
        rect.top.coerceIn(0f, 1f),
        rect.right.coerceIn(0f, 1f),
        rect.bottom.coerceIn(0f, 1f)
    )
}
