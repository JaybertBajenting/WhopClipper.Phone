package com.whopclipper.phone

import android.app.Application
import android.content.Context
import androidx.work.*
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class PhoneApplication : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    override fun onCreate() {
        super.onCreate()
        applicationScope.launch {
            try { PhoneSync.start(this@PhoneApplication) }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { android.util.Log.e("WhopClipperSync", "Phone initialization failed", error) }
        }
    }
}

object PhoneSync {
    private val mutex = Mutex()
    private var firestoreConfigured = false
    /** A pairing for another Firebase project is waiting for the app to restart; nothing syncs until it has. */
    @Volatile private var restartPending = false
    /** A clip already this long past its time when the phone first hears of it is listed, never announced. */
    const val EARLIER_MILLIS = 6 * 60 * 60 * 1000L
    private fun running(context: Context) = FirebaseApp.getApps(context).firstOrNull { it.name == FirebaseApp.DEFAULT_APP_NAME }
    private fun sameProject(app: FirebaseApp, p: JSONObject) = app.options.projectId == p.optString("projectId") &&
        app.options.applicationId == p.optString("appId") && app.options.apiKey == p.optString("apiKey") &&
        app.options.gcmSenderId == p.optString("senderId")
    suspend fun start(context: Context) = withContext(Dispatchers.IO) { mutex.withLock {
        initialize(context)
        periodic(context)
    } }
    /**
     * Stores a new pairing. Returns true when the app has to be restarted to use it.
     *
     * Firebase is started once per process and then kept. Deleting the FirebaseApp and making another with the same
     * name, which this did on every scan, leaves two of Firebase's own DataStores open on one file: the second scan
     * either crashed the app ("Internal error in Cloud Firestore") or left every sync failing until it was closed.
     * A pairing for the same Firebase project needs only a new sign-in. One for a different project needs new
     * Firebase options, which only a fresh process can take.
     */
    suspend fun pair(context: Context, pairing: JSONObject): Boolean = withContext(Dispatchers.IO) { mutex.withLock {
        val app = running(context)
        forget(context, app)
        PhoneStore(context).update { it.put("pairing", pairing) }
        restartPending = app != null && !sameProject(app, pairing)
        if (!restartPending) { initialize(context); periodic(context) }
        restartPending
    } }
    suspend fun disconnect(context: Context) = withContext(Dispatchers.IO) { mutex.withLock { forget(context, running(context)) } }
    /** Signs this phone out and clears what it knows. Firebase itself stays up; see [pair]. */
    private suspend fun forget(context: Context, app: FirebaseApp?) {
        // Clear before network cleanup. An in-flight visibility check uses this same monitor before posting.
        synchronized(Alarms) {
            Alarms.clear(context)
            PhoneStore(context).update { state -> state.keys().asSequence().toList().forEach { state.remove(it) } }
            context.getSystemService(android.app.NotificationManager::class.java).cancelAll()
        }
        if (app != null) {
            try { withTimeoutToken { FirebaseMessaging.getInstance().deleteToken().await() } }
            catch (_: TimeoutCancellationException) { /* Token cleanup is best effort when offline. */ }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { }
            FirebaseAuth.getInstance(app).signOut()
        }
        Thumbs.prune(context, emptySet())
    }
    private suspend fun withTimeoutToken(block: suspend () -> Unit) = kotlinx.coroutines.withTimeout(5000) { block() }
    private fun initialize(context: Context): FirebaseApp? {
        val p = PhoneStore(context).pairing() ?: return null
        return FirebaseApp.getApps(context).firstOrNull { it.name == FirebaseApp.DEFAULT_APP_NAME }
            ?: FirebaseApp.initializeApp(context, FirebaseOptions.Builder()
                .setProjectId(p.getString("projectId")).setApplicationId(p.getString("appId"))
                .setApiKey(p.getString("apiKey")).setGcmSenderId(p.getString("senderId")).build())
    }
    suspend fun sync(context: Context) = withContext(Dispatchers.IO) { mutex.withLock {
        try { syncInternal(context) }
        catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            android.util.Log.e("WhopClipperSync", "Schedule sync failed", error)
            if (error is com.google.firebase.firestore.FirebaseFirestoreException &&
                error.code == com.google.firebase.firestore.FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                synchronized(Alarms) {
                    Alarms.clear(context)
                    PhoneStore(context).update { it.remove("clips"); it.put("error", failureMessage(error)) }
                    context.getSystemService(android.app.NotificationManager::class.java).cancelAll()
                }
            } else {
                // A temporary network/setup error leaves the last downloaded clips and local alarms intact.
                PhoneStore(context).update { it.put("error", failureMessage(error)) }
            }
            throw error
        }
    } }
    fun failureMessage(error: Exception): String = when (error) {
        is com.google.firebase.firestore.FirebaseFirestoreException -> if (error.code == com.google.firebase.firestore.FirebaseFirestoreException.Code.PERMISSION_DENIED)
            "Pairing was revoked, or Firebase rules need attention. Check your PC and pair again."
            else "Couldn't sync. Your saved schedule is still available. Check your connection and retry."
        is com.google.firebase.auth.FirebaseAuthException -> "Enable Anonymous sign-in in Firebase Authentication, then sync again."
        else -> "Couldn't sync. Check your connection and the Firebase setup on your PC."
    }
    private suspend fun syncInternal(context: Context) {
        if (restartPending) return
        val store = PhoneStore(context)
        val p = store.pairing() ?: return
        val app = initialize(context) ?: return
        val auth = FirebaseAuth.getInstance(app)
        val user = auth.currentUser ?: auth.signInAnonymously().await().user ?: error("Sign-in failed")
        val messaging = FirebaseMessaging.getInstance().apply { isAutoInitEnabled = true }
        val token = messaging.token.await()
        val db = FirebaseFirestore.getInstance(app)
        // Plaintext disk persistence is disabled: our own cache is protected by Android Keystore.
        if (!firestoreConfigured) {
            try { db.firestoreSettings = com.google.firebase.firestore.FirebaseFirestoreSettings.Builder().setPersistenceEnabled(false).build() }
            catch (_: IllegalStateException) { /* Already configured by this process. */ }
            firestoreConfigured = true
        }
        val doc = db.collection("phonePairings").document(p.getString("pairingId"))
        // Registration requires the QR's join secret; the rules allow only this UID's device document.
        val registration = doc.collection("devices").document(user.uid)
        val existing = registration.get(Source.SERVER).await()
        if (!existing.exists()) registration.set(mapOf("joinSecret" to p.getString("joinSecret"), "fcmToken" to token)).await()
        else if (existing.getString("fcmToken") != token) registration.update("fcmToken", token).await()
        val snapshot = doc.get(Source.SERVER).await()
        if (!snapshot.exists() || snapshot.getBoolean("active") != true) {
            synchronized(Alarms) {
                Alarms.clear(context)
                store.update { it.remove("clips"); it.put("error", "This phone was disconnected. Pair again from your PC.") }
                context.getSystemService(android.app.NotificationManager::class.java).cancelAll()
            }
            return
        }
        val revision = snapshot.getString("revision") ?: error("Missing schedule revision")
        val plain = ScheduleCipher.decrypt(snapshot.getString("ciphertext") ?: error("Missing schedule"),
            snapshot.getString("nonce") ?: error("Missing nonce"), p.getString("encryptionKey"), p.getString("pairingId"), revision)
        val payload = JSONObject(String(plain, Charsets.UTF_8))
        require(payload.getInt("version") == 1)
        val items = payload.getJSONArray("clips")
        require(items.length() <= 2000)
        for (i in 0 until items.length()) {
            val clip = Clip.fromJson(items.getJSONObject(i))
            require(clip.videoId.isEmpty() || clip.videoId.matches(Regex("[A-Za-z0-9_-]{11}")))
        }
        // The stills the PC sent for clips that are not public yet become files; they are not kept in the encrypted
        // state, which is rewritten whole on every change.
        val ids = HashSet<String>()
        for (i in 0 until items.length()) {
            val item = items.getJSONObject(i)
            ids.add(item.getString("id"))
            if (!item.isNull("thumb")) Thumbs.save(context, item.getString("id"), item.getString("thumb"))
            item.remove("thumb")
        }
        Thumbs.prune(context, ids)
        val previous = store.clips().associateBy { it.id }
        val now = System.currentTimeMillis()
        synchronized(Alarms) { store.update { state ->
            val deliveries = state.optJSONObject("deliveries") ?: JSONObject()
            for (i in 0 until items.length()) {
                val clip = Clip.fromJson(items.getJSONObject(i))
                val old = previous[clip.id]
                if (old != null && (old.publishAt != clip.publishAt || old.videoId != clip.videoId)) {
                    deliveries.remove(clip.id)
                    if (old.videoId != clip.videoId) state.optJSONObject("submitted")?.remove(clip.id)
                }
                // The PC sends a month of clips. Pairing must not announce the ones that went live days ago: the
                // first real pairing buzzed "your clip is live" for a clip six days old.
                if (old == null && clip.publishAt < now - EARLIER_MILLIS && deliveries.optJSONObject(clip.id) == null)
                    deliveries.put(clip.id, JSONObject().put("status", "earlier").put("at", now))
            }
            state.put("deliveries", deliveries); state.put("clips", items)
            state.put("lastSync", System.currentTimeMillis()); state.put("revision", revision); state.remove("error")
        } }
        Alarms.restore(context)
        Notifications.refresh(context)
    }
    fun request(context: Context) {
        if (PhoneStore(context).pairing() == null) return
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
        WorkManager.getInstance(context).enqueueUniqueWork("phone-sync-now", ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }
    fun periodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(1, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("phone-sync-hourly", ExistingPeriodicWorkPolicy.KEEP, request)
    }
}

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try { PhoneSync.sync(applicationContext); Result.success() }
    catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        // Errors were recorded inside PhoneSync's mutex, so a failed old sync cannot overwrite a new pairing.
        if (runAttemptCount < 5) Result.retry() else Result.failure()
    }
}

class PhoneMessagingService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        val pairingId = PhoneStore(this).pairing()?.optString("pairingId") ?: return
        if (message.data["pairingId"] != pairingId) return
        if (message.data["type"] == "test") Notifications.test(this, "Test push received", "Your PC reached this phone through Firebase.")
        PhoneSync.request(this)
    }
    override fun onNewToken(token: String) { PhoneSync.request(this) }
}
