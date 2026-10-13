package com.whopclipper.phone.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.whopclipper.phone.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private data class DayGroup(val label: String, val items: List<PhoneClipItem>)

@Composable
fun ClipsScreen(state: PhoneUiState, actions: PhoneActions, setup: () -> Unit, modifier: Modifier = Modifier) {
    val upcoming = state.selectedTab == PhoneTab.UPCOMING
    val upcomingScroll = rememberLazyListState()
    val liveScroll = rememberLazyListState()
    val live = remember(state.live, state.liveFilter) { state.filteredLive }
    val clips = if (upcoming) state.upcoming else live
    val zone = ZoneId.systemDefault()
    val configuration = LocalConfiguration.current
    val groups = remember(clips, upcoming, state.liveFilter, zone, configuration) {
        val formatter = DateTimeFormatter.ofPattern("EEEE, d MMM", Locale.getDefault())
        clips.groupBy {
            Pair(!upcoming && state.liveFilter == LiveFilter.ALL && it.submitted,
                Instant.ofEpochMilli(it.clip.publishAt).atZone(zone).toLocalDate())
        }.map { (group, entries) ->
            val prefix = if (!upcoming && state.liveFilter == LiveFilter.ALL) (if (group.first) "Submitted · " else "To submit · ") else ""
            DayGroup(prefix + group.second.format(formatter), entries)
        }
    }
    LazyColumn(modifier, state = if (upcoming) upcomingScroll else liveScroll,
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.error != null) item("sync-error", "banner") {
            Panel(Modifier.fillMaxWidth()) {
                Text("Schedule needs attention", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
                Text(state.error, style = MaterialTheme.typography.bodyMedium)
                if (state.lastSync > 0) Text("Showing your last downloaded schedule.", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = actions.sync, enabled = !state.syncing) { Text("Retry sync") }
            }
        }
        if (!state.notificationsAllowed || !state.exactAlarmsAllowed) item("permissions", "banner") {
            Panel(Modifier.fillMaxWidth(), highlighted = true) {
                Text("Finish reminder setup", style = MaterialTheme.typography.titleMedium)
                Text("Allow notifications and precise alarms to get clip reminders.", style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = setup) { Text("Set up reminders") }
            }
        }
        if (!upcoming) item("live-filter", "filters") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Copy the link. Open the campaign. Track what you've submitted.", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { FilterChip(state.liveFilter == LiveFilter.NEEDS_SUBMISSION, { actions.filter(LiveFilter.NEEDS_SUBMISSION) }, label = { Text("To submit · ${state.live.count { !it.submitted }}") }) }
                    item { FilterChip(state.liveFilter == LiveFilter.SUBMITTED, { actions.filter(LiveFilter.SUBMITTED) }, label = { Text("Submitted · ${state.live.count { it.submitted }}") }) }
                    item { FilterChip(state.liveFilter == LiveFilter.ALL, { actions.filter(LiveFilter.ALL) }, label = { Text("All") }) }
                }
            }
        }
        if (clips.isEmpty()) item("empty", "empty") {
            EmptyState(if (upcoming) Icons.Outlined.Schedule else Icons.Outlined.TaskAlt,
                when { upcoming -> "Your next clip starts here"; state.liveFilter == LiveFilter.SUBMITTED -> "No links marked submitted"; else -> "You're all caught up" },
                when { upcoming -> "Schedule a clip on your PC, then sync this phone to see it here."
                    state.liveFilter == LiveFilter.SUBMITTED -> "After submitting a link on Whop, mark it submitted here to keep track."
                    else -> "Clips appear here when their scheduled time arrives. Your checklist stays on this phone." },
                if (state.syncing) null else "Sync now", actions.sync)
        }
        if (upcoming && clips.isNotEmpty()) item("timezone", "caption") {
            Text("Times follow your phone · ${zone.id.replace('_', ' ')}", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        groups.forEach { group ->
            item("day-${group.label}", "date") { SectionTitle(group.label) }
            items(group.items, key = { it.clip.id }, contentType = { "clip" }) { item ->
                ClipCard(item, upcoming, upcoming && item.clip.id == clips.firstOrNull()?.clip?.id, actions)
            }
        }
    }
}

@Composable
private fun ClipCard(item: PhoneClipItem, upcoming: Boolean, featured: Boolean, actions: PhoneActions) {
    val clip = item.clip
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, if (featured) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)) {
      Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = "View clip details") { actions.openClip(clip.id) },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ClipThumbnail(clip, !upcoming, Modifier.width(52.dp).height(82.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(timeLabel(clip.publishAt), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    if (upcoming) Text(if (!clip.isReadySchedule()) "Uploading" else if (featured) "Next up" else "Scheduled",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(clip.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(clip.pageName, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(clip.campaignName, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Surface(onClick = { actions.openWeb(clip.watchUrl) }, enabled = clip.videoId.isNotBlank(),
                modifier = Modifier.widthIn(min = 48.dp).semantics {
                    contentDescription = "Open ${clip.title} in YouTube"
                    if (clip.videoId.isBlank()) stateDescription = "YouTube link available after upload"
                }, shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = if (clip.videoId.isNotBlank()) MaterialTheme.colorScheme.onSecondaryContainer
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = .38f)) {
                Column(Modifier.padding(horizontal = 8.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(Icons.Outlined.SmartDisplay, null, Modifier.size(20.dp))
                    Text("YouTube", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        if (!upcoming) {
            val (label, _) = visibilityLabel(item)
            Text(label, style = MaterialTheme.typography.labelMedium,
                color = if (item.deliveryStatus == "not-confirmed") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            ClipActions(item, actions, compact = true)
        }
        if (clip.campaignUrl == null) Text("Campaign link missing. Add it on your PC, then sync.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error)
    }
    }
}

@Composable
fun ClipActions(item: PhoneClipItem, actions: PhoneActions, compact: Boolean = false) {
    val clip = item.clip
    if (compact) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { actions.copy(clip.watchUrl) }, enabled = clip.videoId.isNotBlank(),
                modifier = Modifier.weight(1f).semantics { contentDescription = "Copy YouTube link" }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
                Text("Copy link")
            }
            if (clip.campaignUrl != null) OutlinedButton(onClick = { actions.openWeb(clip.campaignUrl) },
                modifier = Modifier.weight(1f).semantics { contentDescription = "Open Whop campaign" }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
                Text("Campaign")
            }
        }
    } else {
        Button(onClick = { actions.copy(clip.watchUrl) }, enabled = clip.videoId.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Outlined.ContentCopy, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Copy YouTube link")
        }
        if (clip.campaignUrl != null) OutlinedButton(onClick = { actions.openWeb(clip.campaignUrl) }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Open Whop campaign")
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = item.submitted, role = Role.Checkbox, onValueChange = { actions.submitted(clip.id, it) }),
        verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = item.submitted, onCheckedChange = null, modifier = Modifier.padding(end = 8.dp).size(24.dp))
        Column(Modifier.weight(1f)) {
            Text(if (item.submitted) "Marked submitted" else "Mark link submitted", style = MaterialTheme.typography.labelLarge)
            Text("Checklist on this phone only", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun VisibilityStatus(item: PhoneClipItem) {
    val (label, explanation) = visibilityLabel(item)
    StatusPill(label, item.deliveryStatus == "not-confirmed")
    Text(explanation, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private fun visibilityLabel(item: PhoneClipItem): Pair<String, String> = when (item.deliveryStatus) {
        "public" -> "Public on YouTube" to "Public visibility confirmed."
        "unverified" -> "Visibility not checked" to "The scheduled time arrived. Check YouTube before submitting."
        "not-confirmed" -> "Needs a visibility check" to "YouTube hasn't confirmed this clip is public."
        "earlier" -> "Earlier clip · check visibility" to "This clip was scheduled before this phone first synced it. Check YouTube."
        else -> "Scheduled time reached" to "Public visibility hasn't been confirmed yet."
}

@Composable
fun ClipDetailScreen(item: PhoneClipItem, state: PhoneUiState, actions: PhoneActions,
                     playing: Boolean, play: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val clip = item.clip
    val isUpcoming = (clip.publishAt > state.now || !clip.isReadySchedule()) && item.deliveryStatus == null
    Column(modifier.fillMaxHeight().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (playing && !isUpcoming && clip.videoId.isNotBlank()) {
            ClipPlayer(clip, actions.openWeb, Modifier.fillMaxWidth())
            TextButton(onClick = { play(false) }, modifier = Modifier.fillMaxWidth()) { Text("Close player") }
        } else {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                ClipThumbnail(clip, !isUpcoming, Modifier.width(144.dp).height(256.dp))
            }
            if (!isUpcoming && clip.videoId.isNotBlank()) Button(onClick = { play(true) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.PlayArrow, null); Spacer(Modifier.width(8.dp)); Text("Play clip")
            }
        }
        Text(clip.title, style = MaterialTheme.typography.headlineSmall)
        Panel(Modifier.fillMaxWidth()) {
            DetailLine("YouTube page", clip.pageName)
            DetailLine("Campaign", clip.campaignName)
            DetailLine("Scheduled", "${dateLabel(clip.publishAt)} · ${timeLabel(clip.publishAt)}")
            if (isUpcoming) {
                StatusPill(if (!clip.isReadySchedule()) "Waiting for upload" else "Scheduled")
                Text("Until publish time, YouTube may require you to sign in as the channel owner to watch.", style = MaterialTheme.typography.bodyMedium)
            } else VisibilityStatus(item)
        }
        Panel(Modifier.fillMaxWidth()) {
            if (!isUpcoming) ClipActions(item, actions)
            if (clip.videoId.isNotBlank()) TextButton(onClick = { actions.openWeb(clip.watchUrl) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Watch in YouTube")
            } else Text("Your PC is still uploading this clip. Sync again once the upload finishes.", style = MaterialTheme.typography.bodyMedium)
            if (isUpcoming && clip.campaignUrl != null) OutlinedButton(onClick = { actions.openWeb(clip.campaignUrl) }, modifier = Modifier.fillMaxWidth()) { Text("Open Whop campaign") }
            if (clip.campaignUrl == null) Text("Campaign link missing. Add it on your PC, then sync.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
fun timeLabel(time: Long): String {
    val context = LocalContext.current
    val config = LocalConfiguration.current
    return remember(time, config, ZoneId.systemDefault(), android.text.format.DateFormat.is24HourFormat(context)) {
        android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(time))
    }
}

@Composable
fun dateLabel(time: Long): String {
    val config = LocalConfiguration.current
    val zone = ZoneId.systemDefault()
    return remember(time, config, zone) { DateTimeFormatter.ofPattern("EEE, d MMM", Locale.getDefault()).format(Instant.ofEpochMilli(time).atZone(zone)) }
}
