package com.rlks.voicecontroller

import kotlin.math.sqrt

data class VisionStats(
    val sampleCount: Int,
    val meanLuminance: Double,
    val standardDeviation: Double,
    val luminanceRange: Double,
    val isUsable: Boolean
)

object VisionAnalyzer {
    fun analyze(argbPixels: IntArray): VisionStats {
        if (argbPixels.isEmpty()) return VisionStats(0, 0.0, 0.0, 0.0, false)
        var sum = 0.0
        var sumSquares = 0.0
        var minimum = 255.0
        var maximum = 0.0
        for (pixel in argbPixels) {
            val red = (pixel shr 16) and 0xff
            val green = (pixel shr 8) and 0xff
            val blue = pixel and 0xff
            val luminance = 0.2126 * red + 0.7152 * green + 0.0722 * blue
            sum += luminance
            sumSquares += luminance * luminance
            if (luminance < minimum) minimum = luminance
            if (luminance > maximum) maximum = luminance
        }
        val mean = sum / argbPixels.size
        val variance = (sumSquares / argbPixels.size - mean * mean).coerceAtLeast(0.0)
        val deviation = sqrt(variance)
        val range = maximum - minimum
        return VisionStats(
            argbPixels.size, mean, deviation, range,
            mean >= 4.0 && deviation >= 5.0 && range >= 18.0
        )
    }
}

