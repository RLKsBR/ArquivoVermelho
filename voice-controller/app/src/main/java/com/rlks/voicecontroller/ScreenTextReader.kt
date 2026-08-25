package com.rlks.voicecontroller

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

class ScreenTextReader {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    fun read(
        bitmap: Bitmap,
        onSuccess: (String) -> Unit,
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
                    .map { it.text.replace(Regex("\\s+"), " ").trim() }
                    .filter { it.isNotBlank() }
                    .distinctBy { it.lowercase() }
                onSuccess(lines.joinToString(". "))
            }
            .addOnFailureListener(onFailure)
    }

    fun close() {
        recognizer.close()
    }
}
