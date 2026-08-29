package com.rlks.voicecontroller

data class CaptureDiagnostics(
    val screenshots: Long = 0,
    val screenshotFailures: Long = 0,
    val ocrExecutions: Long = 0,
    val skippedUnchangedFrames: Long = 0,
    val totalOcrMillis: Long = 0,
    val maxOcrMillis: Long = 0,
    val queueDepth: Int = 0
) {
    val averageOcrMillis: Long
        get() = if (ocrExecutions == 0L) 0L else totalOcrMillis / ocrExecutions
}

/** Small state machine used by the Android capture coordinator and unit tests. */
class CaptureGate {
    private var activeReason: String? = null
    private val pending = linkedSetOf<String>()

    @Synchronized
    fun request(reason: String): Boolean {
        if (activeReason == null) {
            activeReason = reason
            return true
        }
        pending += reason
        return false
    }

    @Synchronized
    fun finish(): String? {
        activeReason = null
        val next = pending.firstOrNull() ?: return null
        pending.remove(next)
        return next
    }

    @Synchronized
    fun cancelAll() {
        activeReason = null
        pending.clear()
    }

    @Synchronized fun isBusy(): Boolean = activeReason != null
    @Synchronized fun queueDepth(): Int = pending.size
}

/** Owns screenshot admission and coalesces requests so only one pipeline can run at a time. */
class CaptureCoordinator(private val gate: CaptureGate = CaptureGate()) {
    fun begin(reason: String): Boolean = gate.request(reason)
    fun finish(): String? = gate.finish()
    fun cancelAll() = gate.cancelAll()
    fun isBusy(): Boolean = gate.isBusy()
    fun queueDepth(): Int = gate.queueDepth()
}

object FrameFingerprint {
    /** 64-bit difference hash. It is deliberately cheap and runs on a downscaled frame. */
    fun dHash(argb: IntArray, width: Int, height: Int): Long {
        if (width < 9 || height < 8 || argb.size < width * height) return 0L
        var result = 0L
        for (row in 0 until 8) {
            val y = row * (height - 1) / 7
            for (column in 0 until 8) {
                val x1 = column * (width - 1) / 8
                val x2 = (column + 1) * (width - 1) / 8
                result = result shl 1
                if (luminance(argb[y * width + x1]) > luminance(argb[y * width + x2])) {
                    result = result or 1L
                }
            }
        }
        return result
    }

    fun distance(first: Long, second: Long): Int = java.lang.Long.bitCount(first xor second)

    fun changed(first: Long?, second: Long, threshold: Int = 6): Boolean =
        first == null || distance(first, second) >= threshold

    private fun luminance(color: Int): Int {
        val red = color shr 16 and 0xff
        val green = color shr 8 and 0xff
        val blue = color and 0xff
        return (red * 3 + green * 6 + blue) / 10
    }
}

object AdaptiveCapturePolicy {
    fun nextDelayMillis(
        tftForeground: Boolean,
        selectionOpen: Boolean,
        frameChanged: Boolean,
        consecutiveFailures: Int
    ): Long {
        if (!tftForeground) return Long.MAX_VALUE
        if (consecutiveFailures > 0) return (2_500L * (consecutiveFailures + 1)).coerceAtMost(15_000L)
        if (selectionOpen) return if (frameChanged) 650L else 1_100L
        return if (frameChanged) 2_200L else 4_500L
    }
}
