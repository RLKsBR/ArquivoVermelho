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

    fun clamp(rect: NormalizedRect): NormalizedRect = NormalizedRect(
        rect.left.coerceIn(0f, 1f),
        rect.top.coerceIn(0f, 1f),
        rect.right.coerceIn(0f, 1f),
        rect.bottom.coerceIn(0f, 1f)
    )
}
