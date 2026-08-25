package com.rlks.voicecontroller

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

object AppNotifications {
    private const val CHANNEL_CALIBRATION = "voice_controller_calibration"
    private const val CHANNEL_STATUS = "voice_controller_status"
    private const val CHANNEL_ERRORS = "voice_controller_errors"

    private const val CALIBRATION_ID = 4101
    private const val STATUS_ID = 4102
    private const val ERROR_ID = 4103

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(
                    CHANNEL_CALIBRATION,
                    "Calibração do TFT",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Mostra qual ponto deve ser tocado durante a calibração."
                },
                NotificationChannel(
                    CHANNEL_STATUS,
                    "Status do Voice Controller",
                    NotificationManager.IMPORTANCE_LOW
                ),
                NotificationChannel(
                    CHANNEL_ERRORS,
                    "Erros do Voice Controller",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "Avisa quando voz, calibração, captura ou gestos falham."
                }
            )
        )
    }

    fun showCalibration(context: Context, instruction: String) {
        notify(
            context = context,
            id = CALIBRATION_ID,
            channel = CHANNEL_CALIBRATION,
            title = "Calibração do TFT",
            text = instruction,
            ongoing = true,
            category = Notification.CATEGORY_PROGRESS
        )
    }

    fun clearCalibration(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(CALIBRATION_ID)
    }

    fun showStatus(context: Context, text: String) {
        notify(
            context = context,
            id = STATUS_ID,
            channel = CHANNEL_STATUS,
            title = "Voice Controller",
            text = text,
            ongoing = false,
            category = Notification.CATEGORY_STATUS
        )
    }

    fun showError(context: Context, text: String) {
        notify(
            context = context,
            id = ERROR_ID,
            channel = CHANNEL_ERRORS,
            title = "Voice Controller: atenção",
            text = text,
            ongoing = false,
            category = Notification.CATEGORY_ERROR
        )
    }

    private fun notify(
        context: Context,
        id: Int,
        channel: String,
        title: String,
        text: String,
        ongoing: Boolean,
        category: String
    ) {
        if (!canPost(context)) return
        createChannels(context)

        val openApp = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = Notification.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification_mic)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(openApp)
            .setCategory(category)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .setOnlyAlertOnce(ongoing)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .build()

        context.getSystemService(NotificationManager::class.java).notify(id, notification)
    }

    private fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
}
