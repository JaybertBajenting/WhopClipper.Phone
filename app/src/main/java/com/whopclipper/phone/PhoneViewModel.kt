package com.whopclipper.phone

import android.app.Application
import android.os.PowerManager
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Lifecycle-owned screen state. No Activity, WebView, pairing key, or mutable JSON is retained here. */
@OptIn(ExperimentalCoroutinesApi::class)
class PhoneViewModel(application: Application, private val savedState: SavedStateHandle) : AndroidViewModel(application) {
    private val context: Application get() = getApplication()
    private val store = PhoneStore(context)
    private val eventChannel = Channel<PhoneUiEvent>(Channel.BUFFERED)
    val events: Flow<PhoneUiEvent> = eventChannel.receiveAsFlow()

    private data class Navigation(val tab: PhoneTab, val filter: LiveFilter, val clipId: String?)
    private data class Operations(val syncing: Boolean = false, val pairing: Boolean = false,
                                  val disconnecting: Boolean = false, val error: String? = null,
                                  val syncErrorSince: Long? = null) {
        val busy get() = syncing || pairing || disconnecting
    }
    private data class Permissions(val notifications: Boolean = false, val alarms: Boolean = false, val battery: Boolean = false,
                                   val ring: Boolean = true, val fullScreen: Boolean = true)
    private data class TimedSnapshot(val snapshot: PhoneSnapshot, val schedule: PhoneSchedule, val now: Long)

    private val operations = MutableStateFlow(Operations())
    private val permissions = MutableStateFlow(Permissions())
    private val clockRefresh = MutableStateFlow(0L)
    private val navigation = combine(
        savedState.getStateFlow("tab", PhoneTab.UPCOMING.name),
        savedState.getStateFlow("filter", LiveFilter.NEEDS_SUBMISSION.name),
        savedState.getStateFlow<String?>("clipId", null),
    ) { tab, filter, clipId ->
        Navigation(PhoneTab.entries.firstOrNull { it.name == tab } ?: PhoneTab.UPCOMING,
            LiveFilter.entries.firstOrNull { it.name == filter } ?: LiveFilter.NEEDS_SUBMISSION, clipId)
    }

    private val snapshots = PhoneStore.revisions.mapLatest {
        withContext(Dispatchers.IO) { store.snapshot() }
    }.catch { error ->
        if (error is CancellationException) throw error
        Log.e("WhopClipperStore", "Could not read the saved schedule", error)
        emit(PhoneSnapshot(error = "Could not read the saved schedule. Reopen the app and try again."))
    }

    /** No polling timer. Reclassify at the next scheduled boundary, and stop waiting when the UI is not observed. */
    private val timedSnapshots = combine(snapshots, clockRefresh) { snapshot, _ -> snapshot }
        .transformLatest { snapshot ->
            while (true) {
                val now = System.currentTimeMillis()
                val schedule = PhoneSchedule.at(snapshot.items, now)
                emit(TimedSnapshot(snapshot, schedule, now))
                val next = schedule.nextChangeAt ?: break
                delay((next - System.currentTimeMillis()).coerceAtLeast(1L))
            }
        }.flowOn(Dispatchers.Default)

    val uiState: StateFlow<PhoneUiState> = combine(timedSnapshots, navigation, operations, permissions) { timed, nav, work, allowed ->
        val snapshot = timed.snapshot
        PhoneUiState(
            loading = false, paired = snapshot.paired, projectId = snapshot.projectId,
            clips = snapshot.items, upcoming = timed.schedule.upcoming, live = timed.schedule.live,
            selectedTab = nav.tab, liveFilter = nav.filter,
            openClipId = nav.clipId?.takeIf { id -> snapshot.items.any { it.clip.id == id } },
            syncing = work.syncing, pairing = work.pairing, disconnecting = work.disconnecting,
            lastSync = snapshot.lastSync, error = work.error?.takeUnless {
                // A successful background retry also clears a previous foreground sync error.
                work.syncErrorSince != null && snapshot.lastSync > work.syncErrorSince
            } ?: snapshot.error,
            notificationsAllowed = allowed.notifications, exactAlarmsAllowed = allowed.alarms,
            batteryUnrestricted = allowed.battery, ringAlarm = allowed.ring, fullScreenAllowed = allowed.fullScreen,
            now = timed.now, revision = snapshot.revision,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(stopTimeoutMillis = 0), PhoneUiState())

    init { refreshPermissions() }

    fun selectTab(tab: PhoneTab) { savedState["tab"] = tab.name; closeClip() }
    fun setLiveFilter(filter: LiveFilter) { savedState["filter"] = filter.name }
    fun openClip(id: String) { savedState["clipId"] = id }
    fun closeClip() { savedState["clipId"] = null }
    fun refreshTime() { clockRefresh.value += 1 }
    fun refreshPermissions() {
        viewModelScope.launch {
            permissions.value = withContext(Dispatchers.IO) {
                Permissions(Notifications.allowed(context), Alarms.exactAllowed(context),
                    context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName),
                    AlarmRing.enabled(context), AlarmRing.fullScreenAllowed(context))
            }
        }
    }
    fun setRing(value: Boolean) {
        permissions.value = permissions.value.copy(ring = value)
        viewModelScope.launch { withContext(Dispatchers.IO) { AlarmRing.setEnabled(context, value) } }
    }

    fun syncNow() {
        if (operations.value.busy) return
        operations.value = Operations(syncing = true)
        viewModelScope.launch {
            try {
                if (!withContext(Dispatchers.IO) { store.snapshot().paired }) {
                    eventChannel.send(PhoneUiEvent.Message("Pair with your PC first"))
                } else performSync()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                operations.value = operations.value.copy(error = "Could not read your saved schedule. Reopen the app and try again.")
            } finally { operations.value = operations.value.copy(syncing = false) }
        }
    }

    private suspend fun performSync() {
        try {
            withTimeout(30_000) { PhoneSync.sync(context) }
            val error = withContext(Dispatchers.IO) { store.snapshot().error }
            if (error == null) eventChannel.send(PhoneUiEvent.Message("Schedule synced"))
        } catch (error: TimeoutCancellationException) {
            operations.value = operations.value.copy(error = "Sync took too long. Your saved schedule is still available. Try again when connected.",
                syncErrorSince = System.currentTimeMillis())
            withContext(Dispatchers.IO) { PhoneSync.request(context) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            operations.value = operations.value.copy(error = PhoneSync.failureMessage(error), syncErrorSince = System.currentTimeMillis())
            withContext(Dispatchers.IO) { PhoneSync.request(context) }
        }
    }

    fun pair(code: String) {
        if (operations.value.busy) return
        operations.value = Operations(pairing = true)
        viewModelScope.launch {
            try {
                val parsed = withContext(Dispatchers.IO) {
                    try { Pairing.parse(code) } catch (error: Exception) {
                        throw IllegalArgumentException("Invalid pairing code. Get a fresh code from Publishing → Phone app on your PC.", error)
                    }
                }
                val restart = PhoneSync.pair(context, parsed)
                selectTab(PhoneTab.UPCOMING)
                if (restart) eventChannel.send(PhoneUiEvent.RestartRequired)
                else {
                    operations.value = Operations(syncing = true)
                    eventChannel.send(PhoneUiEvent.Message("Paired with your PC. Downloading your schedule…"))
                    performSync()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.e("WhopClipperSync", "Pairing failed", error)
                operations.value = operations.value.copy(error = if (error is IllegalArgumentException) error.message
                    else "Could not pair. Reopen the app and try again.")
            } finally { operations.value = operations.value.copy(pairing = false, syncing = false) }
        }
    }

    fun disconnect() {
        if (operations.value.busy) return
        operations.value = Operations(disconnecting = true)
        viewModelScope.launch {
            try {
                PhoneSync.disconnect(context)
                selectTab(PhoneTab.UPCOMING)
                setLiveFilter(LiveFilter.NEEDS_SUBMISSION)
                eventChannel.send(PhoneUiEvent.Message("This phone is disconnected"))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.e("WhopClipperSync", "Disconnect failed", error)
                operations.value = operations.value.copy(error = "Could not disconnect. Try again.")
            } finally { operations.value = operations.value.copy(disconnecting = false) }
        }
    }

    fun setSubmitted(id: String, value: Boolean) {
        viewModelScope.launch {
            try {
                var found = false
                withContext(Dispatchers.IO) {
                    store.update { state ->
                        val clips = state.optJSONArray("clips")
                        found = clips != null && (0 until clips.length()).any { clips.getJSONObject(it).optString("id") == id }
                        if (found) state.put("submitted", (state.optJSONObject("submitted") ?: org.json.JSONObject()).put(id, value))
                    }
                }
                eventChannel.send(PhoneUiEvent.Message(if (!found) "This clip is no longer in your schedule"
                    else if (value) "Marked submitted on this phone" else "Marked as needing submission"))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                eventChannel.send(PhoneUiEvent.Message("Could not save your checklist. Try again."))
            }
        }
    }

    /** Read once at action time so notification buttons use the latest campaign and never a stale Intent URL. */
    fun handleNotification(clipId: String, action: String?) {
        viewModelScope.launch {
            val snapshot = try { withContext(Dispatchers.IO) { store.snapshot() } }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { eventChannel.send(PhoneUiEvent.Message("Could not open this clip. Reopen the app and try again.")); return@launch }
            val item = snapshot.items.firstOrNull { it.clip.id == clipId && it.clip.isActiveSchedule() }
            if (!snapshot.paired || item == null) {
                selectTab(if (snapshot.paired) PhoneTab.LIVE else PhoneTab.UPCOMING)
                eventChannel.send(PhoneUiEvent.Message(if (snapshot.paired) "This clip is no longer in your schedule" else "Pair with your PC to view this clip"))
                return@launch
            }
            val clip = item.clip
            selectTab(if (clip.publishAt > System.currentTimeMillis() || clip.videoId.isBlank()) PhoneTab.UPCOMING else PhoneTab.LIVE)
            openClip(clip.id)
            when (action) {
                "copy" -> if (clip.videoId.isBlank()) eventChannel.send(PhoneUiEvent.Message("Your PC is still uploading this clip"))
                    else eventChannel.send(PhoneUiEvent.CopyLink(clip.watchUrl))
                "campaign" -> if (clip.campaignUrl == null) eventChannel.send(PhoneUiEvent.Message("Campaign link missing. Add it on your PC and sync again."))
                    else eventChannel.send(PhoneUiEvent.OpenLink(clip.campaignUrl))
                "watch" -> if (clip.videoId.isBlank()) eventChannel.send(PhoneUiEvent.Message("Your PC is still uploading this clip"))
                    else eventChannel.send(PhoneUiEvent.OpenLink(clip.watchUrl))
                // Legacy play and details notifications open the same stable detail screen; playback is explicit.
                else -> Unit
            }
        }
    }
}
