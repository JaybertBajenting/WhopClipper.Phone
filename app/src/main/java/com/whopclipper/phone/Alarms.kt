package com.whopclipper.phone

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object Alarms {
    private data class AlarmStamp(val at: Long, val video: String)
    // Process-local optimization only. A fresh process still re-arms all persisted alarms after reboot.
    private var armed: Map<String, AlarmStamp>? = null
    private var armedPrecisely: Boolean? = null
    private fun manager(context: Context) = context.getSystemService(AlarmManager::class.java)
    fun exactAllowed(context: Context) = Build.VERSION.SDK_INT < 31 || manager(context).canScheduleExactAlarms()
    private fun pending(context: Context, id: String): PendingIntent = PendingIntent.getBroadcast(context, 0,
        Intent(context, ClipAlarmReceiver::class.java).setData(Uri.parse("whopclipper://alarm/$id")).putExtra("clipId", id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    fun schedule(context: Context, id: String, at: Long) {
        val alarm = pending(context, id)
        if (exactAllowed(context)) manager(context).setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarm)
        else manager(context).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarm)
    }
    /** What a notification button sends to reach [check] with this ID, such as "silence:…" to stop a ring. */
    fun broadcast(context: Context, id: String): PendingIntent = pending(context, id)
    fun cancel(context: Context, id: String) { manager(context).cancel(pending(context, id)) }
    @Synchronized fun clear(context: Context) {
        val store = PhoneStore(context)
        val ids = store.state().optJSONArray("alarms") ?: JSONArray()
        cancelExisting(context, (0 until ids.length()).map { ids.getString(it) })
        if (ids.length() > 0) store.update { it.put("alarms", JSONArray()) }
        armed = null
    }
    private fun cancelExisting(context: Context, ids: List<String>) {
        for (id in ids) {
            manager(context).cancel(pending(context, id))
            WorkManager.getInstance(context).cancelUniqueWork("clip-check-$id")
        }
    }
    @Synchronized fun restore(context: Context, force: Boolean = false) {
        val store = PhoneStore(context)
        val snapshot = store.snapshot()
        val desired = snapshot.items.filter { it.clip.videoId.isNotBlank() && it.clip.status in listOf("Scheduled", "Published") && it.deliveryStatus == null }
        val next = desired.associate { it.clip.id to AlarmStamp(it.clip.publishAt, it.clip.videoId) }
        val precise = exactAllowed(context)
        val previous = armed
        val reset = force || previous == null || armedPrecisely != precise
        val removed = snapshot.alarmIds.filter { it !in next }
        val changed = next.keys.filter { reset || previous?.get(it) != next[it] }.toSet()
        // Keep existing visibility retry workers when the schedule itself has not changed.
        cancelExisting(context, removed + changed.filter { it in snapshot.alarmIds })
        val ids = JSONArray()
        // The encrypted schedule is one document. Read it once, rather than decrypting it for every clip.
        desired.forEach { item ->
                val clip = item.clip
                ids.put(clip.id)
                if (clip.id in changed) schedule(context, clip.id, maxOf(clip.publishAt, System.currentTimeMillis() + 1000))
            }
        if (snapshot.alarmIds != (0 until ids.length()).map { ids.getString(it) }) store.update { it.put("alarms", ids) }
        armed = next
        armedPrecisely = precise
    }
    fun retry(context: Context, id: String, attempt: Int) {
        val work = OneTimeWorkRequestBuilder<PublicCheckWorker>()
            .setInputData(workDataOf("clipId" to id, "attempt" to attempt))
            .setInitialDelay(1, TimeUnit.MINUTES).build()
        WorkManager.getInstance(context).enqueueUniqueWork("clip-check-$id", ExistingWorkPolicy.REPLACE, work)
    }
    fun check(context: Context, id: String, attempt: Int = 0) {
        if (id.startsWith("silence:")) { AlarmRing.silence(context, id.removePrefix("silence:")); return }
        if (id == "test") {
            // The test rings exactly as a clip going live will, so what is heard here is what will be heard then.
            if (!AlarmRing.enabled(context) || !AlarmRing.ring(context, "test", "Test alarm", "This is how a clip going live will ring.", null))
                Notifications.test(context, "Test alarm arrived", "Your phone's local alarm is working.")
            return
        }
        val store = PhoneStore(context)
        val clip = synchronized(this) {
            val item = store.snapshot().items.firstOrNull { it.clip.id == id } ?: return
            val current = item.clip
            if (item.deliveryStatus != null || !current.isReadySchedule()) return
            // A stale alarm must never notify early if the PC moved the slot during a sync.
            if (current.publishAt > System.currentTimeMillis() + 1000) { schedule(context, id, current.publishAt); return }
            current
        }
        when (PublicCheck.check(clip)) {
            PublicState.PUBLIC -> finish(context, clip, "public")
            PublicState.OFFLINE -> finish(context, clip, "unverified")
            PublicState.AVAILABLE, PublicState.NOT_PUBLIC -> if (attempt < 5) synchronized(this) {
                if (currentUndelivered(store, clip) != null) retry(context, id, attempt + 1)
            } else finish(context, clip, "not-confirmed")
        }
    }
    private fun currentUndelivered(store: PhoneStore, clip: Clip): Clip? = store.snapshot().items
        .firstOrNull { it.clip.id == clip.id && it.deliveryStatus == null && it.clip.isReadySchedule() &&
            it.clip.videoId == clip.videoId && it.clip.publishAt == clip.publishAt }?.clip

    @Synchronized private fun finish(context: Context, clip: Clip, status: String) {
        val store = PhoneStore(context)
        // Re-read after network I/O so a removed/rescheduled clip does not produce stale news.
        val latest = currentUndelivered(store, clip) ?: return
        // With ringing on, the clip's own notification is posted quietly and stays as the record with its buttons;
        // the alarm rings beside it and goes when it is dismissed.
        val ring = AlarmRing.enabled(context)
        if (Notifications.show(context, latest, status, silent = ring)) {
            store.delivered(latest.id, status)
            if (ring) AlarmRing.ring(context, latest.id, Notifications.title(status), "${latest.title} · ${latest.pageName}", latest.id)
        }
        else armed = armed?.minus(latest.id)
    }
}

enum class PublicState { PUBLIC, AVAILABLE, NOT_PUBLIC, OFFLINE }
object PublicCheck {
    private fun get(url: String): Pair<Int, String> {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 1500; connection.readTimeout = 1500; connection.instanceFollowRedirects = false
            connection.setRequestProperty("User-Agent", "WhopClipperPhone/1.0")
            val code = connection.responseCode
            val text = if (code == 200) connection.inputStream.use { readLimitedImage(it, 500_000)?.toString(Charsets.UTF_8).orEmpty() } else ""
            return code to text
        } finally { connection.disconnect() }
    }
    fun check(clip: Clip): PublicState {
      return try {
        // Channel feeds contain public videos. oEmbed alone also accepts unlisted videos,
        // so a successful oEmbed response is deliberately not proof of public visibility.
        if (clip.channelId.matches(Regex("UC[A-Za-z0-9_-]{22}"))) {
            val (code, feed) = get("https://www.youtube.com/feeds/videos.xml?channel_id=${clip.channelId}")
            if (code == 200 && feed.contains("<yt:videoId>${clip.videoId}</yt:videoId>")) return PublicState.PUBLIC
        }
        val (code, _) = get("https://www.youtube.com/oembed?url=https%3A%2F%2Fwww.youtube.com%2Fwatch%3Fv%3D${clip.videoId}&format=json")
        when (code) { 200 -> PublicState.AVAILABLE; 400, 401, 403, 404 -> PublicState.NOT_PUBLIC; else -> PublicState.OFFLINE }
      } catch (_: java.io.IOException) { PublicState.OFFLINE }
    }
}

class ClipAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("clipId") ?: return
        val pending = goAsync()
        val wakeLock = context.getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WhopClipper:public-check")
        wakeLock.acquire(9000)
        executor.execute {
            try { Alarms.check(context, id) }
            catch (_: Exception) { if (id != "test") Alarms.retry(context, id, 1) }
            finally { if (wakeLock.isHeld) wakeLock.release(); pending.finish() }
        }
    }
    companion object { private val executor = Executors.newCachedThreadPool() }
}

class PublicCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val id = inputData.getString("clipId") ?: return@withContext Result.failure()
        Alarms.check(applicationContext, id, inputData.getInt("attempt", 0))
        Result.success()
    }
}

class RestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED, AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED)) return
        val pending = goAsync()
        executor.execute {
            try { Alarms.restore(context, force = true); PhoneSync.request(context) }
            catch (error: Exception) { android.util.Log.e("WhopClipperAlarms", "Could not restore reminders", error) }
            finally { pending.finish() }
        }
    }
    companion object { private val executor = Executors.newSingleThreadExecutor() }
}
