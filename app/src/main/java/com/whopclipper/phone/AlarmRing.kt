package com.whopclipper.phone

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat

/**
 * Rings like a clock alarm when a clip goes live. A reminder used to be an ordinary notification: one ping, and none
 * at all with Do Not Disturb on, which is how the first test "alarm" came and went unnoticed.
 *
 * The ring is a notification in the alarm category, on a channel whose sound is the phone's alarm tone played at
 * alarm volume, repeating until it is answered. Android lets alarms through Do Not Disturb, and a notification needs
 * no service to be started from the background, which a clip's check (done minutes after its alarm fires) could not
 * do. Its full-screen page shows over the lock screen where the phone allows that.
 */
object AlarmRing {
    private const val CHANNEL = "clip-alarm"
    private const val ID = 3
    /** How long it rings unanswered. A clip going live at 3 am must not ring until morning. */
    const val RING_MILLIS = 5 * 60_000L

    private fun manager(context: Context) = context.getSystemService(NotificationManager::class.java)
    // Kept apart from the encrypted schedule, which is wiped whole when the phone is paired again.
    private fun settings(context: Context) = context.getSharedPreferences("phone-settings", Context.MODE_PRIVATE)
    fun enabled(context: Context): Boolean = settings(context).getBoolean("ring", true)
    fun setEnabled(context: Context, value: Boolean) { settings(context).edit().putBoolean("ring", value).apply() }
    /** Android 14 and later can withhold the full-screen page; the ring itself does not depend on it. */
    fun fullScreenAllowed(context: Context): Boolean = Build.VERSION.SDK_INT < 34 || manager(context).canUseFullScreenIntent()

    /** Starts ringing. [key] names the ring so it can be stopped: a clip's ID, or "test". */
    fun ring(context: Context, key: String, title: String, text: String, clipId: String?): Boolean {
        if (!Notifications.allowed(context)) return false
        val sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        manager(context).createNotificationChannel(NotificationChannel(CHANNEL, "Clip alarm", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Rings like an alarm when a clip goes live"
            setSound(sound, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            enableVibration(true); vibrationPattern = longArrayOf(0, 700, 500, 700, 500)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        })
        val page = PendingIntent.getActivity(context, 0,
            Intent(context, AlarmActivity::class.java).setData(Uri.parse("whopclipper://ring/$key"))
                .putExtra("key", key).putExtra("title", title).putExtra("text", text).putExtra("clipId", clipId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = Alarms.broadcast(context, "silence:$key")
        val notification = NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title).setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_ALARM).setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setFullScreenIntent(page, true).setContentIntent(page)
            // Swiping it away stops it too.
            .setDeleteIntent(stop).addAction(0, "Dismiss", stop).build()
        // Repeats the sound until the notification is answered or cancelled, which is what makes it an alarm.
        notification.flags = notification.flags or Notification.FLAG_INSISTENT
        manager(context).notify("ring-$key", ID, notification)
        Alarms.schedule(context, "silence:$key", System.currentTimeMillis() + RING_MILLIS)
        return true
    }

    fun silence(context: Context, key: String) {
        manager(context).cancel("ring-$key", ID)
        Alarms.cancel(context, "silence:$key")
    }
}

/** The page a ring opens: over the lock screen, with the clip's name and a way to stop the sound. */
class AlarmActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        @Suppress("DEPRECATION")
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        show(intent)
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); show(intent) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun show(incoming: Intent) {
        val key = incoming.getStringExtra("key") ?: run { finish(); return }
        val clipId = incoming.getStringExtra("clipId")
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setBackgroundColor(Color.rgb(16, 28, 23))
            setPadding(dp(28), dp(48), dp(28), dp(48))
        }
        root.addView(TextView(this).apply {
            text = incoming.getStringExtra("title").orEmpty(); textSize = 28f; setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE); gravity = Gravity.CENTER
        })
        root.addView(TextView(this).apply {
            text = incoming.getStringExtra("text").orEmpty(); textSize = 18f
            setTextColor(Color.rgb(200, 214, 206)); gravity = Gravity.CENTER; setPadding(0, dp(16), 0, dp(40))
        })
        if (clipId != null) root.addView(Button(this).apply {
            text = "Open clip"; isAllCaps = false; textSize = 18f
            setOnClickListener {
                AlarmRing.silence(applicationContext, key)
                startActivity(Intent(this@AlarmActivity, MainActivity::class.java).putExtra("clipId", clipId).putExtra("action", "details")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
                finish()
            }
        }, LinearLayout.LayoutParams(-1, dp(64)).apply { bottomMargin = dp(12) })
        root.addView(Button(this).apply {
            text = "Dismiss"; isAllCaps = false; textSize = 18f
            setOnClickListener { AlarmRing.silence(applicationContext, key); finish() }
        }, LinearLayout.LayoutParams(-1, dp(64)))
        setContentView(root)
    }
}
