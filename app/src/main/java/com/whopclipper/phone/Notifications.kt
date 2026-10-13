package com.whopclipper.phone

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

object Notifications {
    private fun manager(context: Context) = context.getSystemService(NotificationManager::class.java)
    fun allowed(context: Context) = manager(context).areNotificationsEnabled() &&
        (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
    private fun activity(context: Context, clip: Clip, action: String) = PendingIntent.getActivity(context, 0,
        Intent(context, MainActivity::class.java).setData(Uri.parse("whopclipper://notification/${clip.id}/$action"))
            .putExtra("clipId", clip.id).putExtra("action", action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    fun title(status: String) = when (status) {
        "public" -> "Your clip is live on YouTube"
        "unverified" -> "Your clip's scheduled time has arrived"
        else -> "Clip visibility needs checking"
    }
    fun show(context: Context, clip: Clip, status: String, silent: Boolean = false): Boolean {
        if (!allowed(context)) return false
        val channel = "page-${clip.pageId}"
        manager(context).createNotificationChannel(NotificationChannel(channel, clip.pageName, NotificationManager.IMPORTANCE_HIGH))
        val title = title(status)
        val explanation = when (status) {
            "public" -> "Public visibility confirmed."
            "unverified" -> "Phone offline or YouTube unavailable. Public visibility not checked."
            else -> "YouTube has not confirmed this clip is public. Check it before submitting."
        }
        val builder = NotificationCompat.Builder(context, channel).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title).setContentText("${clip.title} · ${clip.pageName} · ${clip.campaignName}")
            .setStyle(NotificationCompat.BigTextStyle().bigText("${clip.title}\n${clip.pageName} · ${clip.campaignName}\n$explanation"))
            .setContentIntent(activity(context, clip, if (clip.campaignUrl != null) "campaign" else "details"))
            .setAutoCancel(true).setOnlyAlertOnce(true).setSilent(silent)
            .addAction(0, "Copy link", activity(context, clip, "copy"))
            .addAction(0, "Watch", activity(context, clip, "watch"))
        if (clip.campaignUrl != null) builder.addAction(0, "Open campaign", activity(context, clip, "campaign"))
        manager(context).notify("clip-${clip.id}", 1, builder.build())
        return true
    }
    fun refresh(context: Context) {
        val clips = PhoneStore(context).snapshot().items.associateBy { it.clip.id }
        manager(context).activeNotifications.forEach { notification ->
            val tag = notification.tag ?: return@forEach
            if (!tag.startsWith("clip-")) return@forEach
            val id = tag.removePrefix("clip-")
            val item = clips[id]
            if (item == null || item.clip.status !in listOf("Scheduled", "Published")) manager(context).cancel(tag, 1)
            else item.deliveryStatus?.let { show(context, item.clip, it, silent = true) }
        }
    }
    fun test(context: Context, title: String, message: String) {
        if (!allowed(context)) return
        manager(context).createNotificationChannel(NotificationChannel("diagnostics", "Test notifications", NotificationManager.IMPORTANCE_HIGH))
        manager(context).notify("test", 2, NotificationCompat.Builder(context, "diagnostics").setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title).setContentText(message).setAutoCancel(true).build())
    }
}
