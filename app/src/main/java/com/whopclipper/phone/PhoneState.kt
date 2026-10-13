package com.whopclipper.phone

enum class PhoneTab { UPCOMING, LIVE, SETTINGS }
enum class LiveFilter { NEEDS_SUBMISSION, SUBMITTED, ALL }

data class PhoneClipItem(val clip: Clip, val deliveryStatus: String?, val submitted: Boolean)

/** PC jobs still being uploaded belong in Upcoming, while cancelled/failed jobs never offer actions. */
private val ActiveScheduleStatuses = setOf("Scheduled", "Published", "Queued", "Validating", "Uploading", "Processing")
internal fun Clip.isActiveSchedule() = status in ActiveScheduleStatuses
internal fun Clip.isReadySchedule() = videoId.isNotBlank() && (status == "Scheduled" || status == "Published")

/** Public display data only. Credentials never enter UI state or SavedStateHandle. */
data class PhoneSnapshot(
    val paired: Boolean = false,
    val projectId: String? = null,
    val lastSync: Long = 0,
    val error: String? = null,
    val items: List<PhoneClipItem> = emptyList(),
    val revision: Long = 0,
    val alarmIds: List<String> = emptyList(),
)

data class PhoneUiState(
    val loading: Boolean = true,
    val paired: Boolean = false,
    val projectId: String? = null,
    val clips: List<PhoneClipItem> = emptyList(),
    val upcoming: List<PhoneClipItem> = emptyList(),
    val live: List<PhoneClipItem> = emptyList(),
    val selectedTab: PhoneTab = PhoneTab.UPCOMING,
    val liveFilter: LiveFilter = LiveFilter.NEEDS_SUBMISSION,
    val openClipId: String? = null,
    val syncing: Boolean = false,
    val pairing: Boolean = false,
    val disconnecting: Boolean = false,
    val lastSync: Long = 0,
    val error: String? = null,
    val notificationsAllowed: Boolean = false,
    val exactAlarmsAllowed: Boolean = false,
    val batteryUnrestricted: Boolean = false,
    /** A clip going live rings like an alarm until dismissed, rather than pinging once. */
    val ringAlarm: Boolean = true,
    val fullScreenAllowed: Boolean = true,
    val now: Long = 0,
    val revision: Long = 0,
) {
    val openClip: PhoneClipItem? get() = clips.firstOrNull { it.clip.id == openClipId && it.clip.isActiveSchedule() }
    val filteredLive: List<PhoneClipItem> get() = when (liveFilter) {
        LiveFilter.NEEDS_SUBMISSION -> live.filterNot { it.submitted }
        LiveFilter.SUBMITTED -> live.filter { it.submitted }
        LiveFilter.ALL -> live
    }
    val busy: Boolean get() = syncing || pairing || disconnecting
}

data class PhoneSchedule(val upcoming: List<PhoneClipItem>, val live: List<PhoneClipItem>, val nextChangeAt: Long?) {
    companion object {
        /** Classification is pure and can be checked without Firebase, the Keystore, or an Android device. */
        fun at(items: List<PhoneClipItem>, now: Long): PhoneSchedule {
            val upcoming = ArrayList<PhoneClipItem>()
            val live = ArrayList<PhoneClipItem>()
            var nextChange: Long? = null
            for (item in items) {
                val clip = item.clip
                if (!clip.isActiveSchedule()) continue
                if (item.deliveryStatus == null && (clip.publishAt > now || !clip.isReadySchedule())) upcoming.add(item)
                if ((item.deliveryStatus != null || clip.publishAt <= now) && clip.isReadySchedule()) live.add(item)
                if (item.deliveryStatus == null && clip.publishAt > now && clip.isReadySchedule() &&
                    (nextChange == null || clip.publishAt < nextChange)) nextChange = clip.publishAt
            }
            upcoming.sortWith(compareBy<PhoneClipItem> { it.clip.publishAt }.thenBy { it.clip.id })
            // Completed checklist items stay below the ones that still need attention, even with the All filter.
            live.sortWith(compareBy<PhoneClipItem> { it.submitted }.thenByDescending { it.clip.publishAt }.thenBy { it.clip.id })
            return PhoneSchedule(upcoming, live, nextChange)
        }
    }
}

sealed interface PhoneUiEvent {
    data class Message(val text: String) : PhoneUiEvent
    data object RestartRequired : PhoneUiEvent
    data class CopyLink(val url: String) : PhoneUiEvent
    data class OpenLink(val url: String) : PhoneUiEvent
}
