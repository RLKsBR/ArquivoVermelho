package com.rlks.voicecontroller

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ScreenshotStore {
    private const val RELATIVE_FOLDER = "Pictures/Voice Controller/TFT Captures"

    fun save(
        context: Context,
        bitmap: Bitmap,
        category: String = "test",
        saveAsOcrSample: Boolean = false
    ): Uri {
        val resolver = context.contentResolver
        val safeCategory = category.lowercase().replace(Regex("[^a-z0-9]+"), "_")
        val fileName = "tft_${safeCategory}_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val folder = if (saveAsOcrSample) "$RELATIVE_FOLDER/OCR Samples" else RELATIVE_FOLDER
                put(MediaStore.Images.Media.RELATIVE_PATH, folder)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("O Android não criou o arquivo da screenshot")
        try {
            resolver.openOutputStream(uri)?.use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 92, output)) {
                    "Falha ao comprimir a screenshot"
                }
            } ?: error("O Android não abriu o arquivo da screenshot")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.update(uri, ContentValues().apply {
                    put(MediaStore.Images.Media.IS_PENDING, 0)
                }, null, null)
            }
            return uri
        } catch (error: Throwable) {
            resolver.delete(uri, null, null)
            throw error
        }
    }
}
