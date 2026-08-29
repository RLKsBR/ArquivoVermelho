package com.rlks.voicecontroller

import kotlin.math.sqrt

data class ConfirmedOrb(
    val point: NormalizedPoint,
    val confidence: Float,
    val observations: Int
)

object OrbTemporalTracker {
    fun confirm(
        frames: List<List<NormalizedPoint>>,
        tolerance: Float = 0.055f,
        minimumFrames: Int = 2
    ): List<ConfirmedOrb> {
        if (frames.size < minimumFrames) return emptyList()
        val seeds = frames.firstOrNull().orEmpty()
        return seeds.mapNotNull { seed ->
            val matches = frames.mapNotNull { frame -> frame.minByOrNull { distance(seed, it) }
                ?.takeIf { distance(seed, it) <= tolerance } }
            if (matches.size < minimumFrames) return@mapNotNull null
            val point = NormalizedPoint(
                matches.map { it.x }.average().toFloat(),
                matches.map { it.y }.average().toFloat()
            )
            ConfirmedOrb(
                point,
                confidence = (0.72f + matches.size * 0.1f).coerceAtMost(0.96f),
                observations = matches.size
            )
        }.distinctBy { orb ->
            "${(orb.point.x * 20).toInt()}_${(orb.point.y * 20).toInt()}"
        }.sortedByDescending { it.confidence }.take(4)
    }

    fun nearest(
        target: NormalizedPoint,
        candidates: List<NormalizedPoint>,
        tolerance: Float = 0.06f
    ): NormalizedPoint? = candidates.minByOrNull { distance(target, it) }
        ?.takeIf { distance(target, it) <= tolerance }

    private fun distance(first: NormalizedPoint, second: NormalizedPoint): Float {
        val dx = first.x - second.x
        val dy = first.y - second.y
        return sqrt(dx * dx + dy * dy)
    }
}
