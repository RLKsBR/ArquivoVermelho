package com.rlks.voicecontroller

import kotlin.math.max
import kotlin.math.min

object OrbDetector {
    fun detect(
        argb: IntArray,
        width: Int,
        height: Int,
        search: NormalizedRect = NormalizedRect(0.04f, 0.16f, 0.96f, 0.84f)
    ): List<NormalizedPoint> {
        if (width <= 0 || height <= 0 || argb.size < width * height) return emptyList()
        val left = (search.left.coerceIn(0f, 1f) * width).toInt()
        val top = (search.top.coerceIn(0f, 1f) * height).toInt()
        val right = (search.right.coerceIn(0f, 1f) * width).toInt().coerceAtMost(width)
        val bottom = (search.bottom.coerceIn(0f, 1f) * height).toInt().coerceAtMost(height)
        val visited = BooleanArray(width * height)
        val results = mutableListOf<Pair<Int, NormalizedPoint>>()
        val queue = IntArray(width * height)

        for (y in top until bottom) for (x in left until right) {
            val start = y * width + x
            if (visited[start] || !isOrbPixel(argb[start])) continue
            var head = 0
            var tail = 0
            queue[tail++] = start
            visited[start] = true
            var count = 0
            var sumX = 0L
            var sumY = 0L
            var minX = x
            var maxX = x
            var minY = y
            var maxY = y
            while (head < tail) {
                val index = queue[head++]
                val px = index % width
                val py = index / width
                count++
                sumX += px
                sumY += py
                minX = min(minX, px); maxX = max(maxX, px)
                minY = min(minY, py); maxY = max(maxY, py)
                for (dy in -1..1) for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val nx = px + dx
                    val ny = py + dy
                    if (nx !in left until right || ny !in top until bottom) continue
                    val ni = ny * width + nx
                    if (!visited[ni] && isOrbPixel(argb[ni])) {
                        visited[ni] = true
                        queue[tail++] = ni
                    }
                }
            }
            val spanX = maxX - minX + 1
            val spanY = maxY - minY + 1
            val minArea = max(10, width * height / 18000)
            val maxArea = max(80, width * height / 180)
            val aspect = spanX.toFloat() / spanY.coerceAtLeast(1)
            val fill = count.toFloat() / (spanX * spanY).coerceAtLeast(1)
            if (count in minArea..maxArea && aspect in 0.45f..2.2f && fill >= 0.18f) {
                results += count to NormalizedPoint(
                    (sumX.toFloat() / count) / width,
                    (sumY.toFloat() / count) / height
                )
            }
        }
        return results.sortedByDescending { it.first }.map { it.second }.take(6)
    }

    private fun isOrbPixel(color: Int): Boolean {
        val red = color shr 16 and 0xff
        val green = color shr 8 and 0xff
        val blue = color and 0xff
        val high = max(red, max(green, blue))
        val low = min(red, min(green, blue))
        val saturation = high - low
        val brightCore = high >= 210 && (red + green + blue) >= 570
        val coloredGlow = high >= 185 && saturation >= 65 &&
            (blue >= 170 || green >= 175 || (red >= 205 && green >= 135))
        return brightCore || coloredGlow
    }
}
