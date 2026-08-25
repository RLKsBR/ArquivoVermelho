package com.rlks.voicecontroller

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

data class RecognizedTextLine(
    val text: String,
    val region: NormalizedRect
)

data class ScreenTextResult(
    val text: String,
    val lines: List<RecognizedTextLine>
)

class ScreenTextReader {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    fun read(
        bitmap: Bitmap,
        onSuccess: (String) -> Unit,
        onFailure: (Throwable) -> Unit
    ) {
        readDetailed(bitmap, { onSuccess(it.text) }, onFailure)
    }

    fun readDetailed(
        bitmap: Bitmap,
        onSuccess: (ScreenTextResult) -> Unit,
        onFailure: (Throwable) -> Unit
    ) {
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { result ->
                val lines = result.textBlocks
                    .flatMap { it.lines }
                    .sortedWith(
                        compareBy(
                            { it.boundingBox?.top ?: Int.MAX_VALUE },
                            { it.boundingBox?.left ?: Int.MAX_VALUE }
                        )
                    )
                    .mapNotNull { line ->
                        val clean = line.text.replace(Regex("\\s+"), " ").trim()
                        val box = line.boundingBox
                        if (clean.isBlank() || box == null) null else RecognizedTextLine(
                            clean,
                            NormalizedRect(
                                box.left.toFloat() / bitmap.width,
                                box.top.toFloat() / bitmap.height,
                                box.right.toFloat() / bitmap.width,
                                box.bottom.toFloat() / bitmap.height
                            )
                        )
                    }
                    .distinctBy { it.text.lowercase() }
                onSuccess(ScreenTextResult(lines.joinToString(". ") { it.text }, lines))
            }
            .addOnFailureListener(onFailure)
    }

    fun close() {
        recognizer.close()
    }
}
