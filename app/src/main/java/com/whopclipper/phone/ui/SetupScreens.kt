package com.whopclipper.phone.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.whopclipper.phone.*

@Composable
fun PairingScreen(state: PhoneUiState, scan: () -> Unit, paste: () -> Unit, settings: () -> Unit, modifier: Modifier = Modifier) {
    LazyColumn(modifier, contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item("welcome") {
        Panel(Modifier.fillMaxWidth(), highlighted = true) {
            Icon(Icons.Outlined.PhoneAndroid, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
            Text("A little less checking.\nA lot more clipping.", style = MaterialTheme.typography.headlineMedium)
            Text("Keep your schedule close. Get a reminder, copy your video link, and open the right Whop campaign.", style = MaterialTheme.typography.bodyLarge)
        }
        }
        item("pairing") {
        Panel(Modifier.fillMaxWidth()) {
            Text("Connect to your PC", style = MaterialTheme.typography.titleLarge)
            Text("1. Open Whop Clipper on your PC.\n2. Go to Publishing → Phone app.\n3. Show the pairing QR, then scan it here.", style = MaterialTheme.typography.bodyMedium)
            Button(onClick = scan, enabled = !state.pairing, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.QrCodeScanner, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("Scan PC QR")
            }
            OutlinedButton(onClick = paste, enabled = !state.pairing, modifier = Modifier.fillMaxWidth()) { Text("Paste code") }
            if (state.pairing) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("Connecting to your schedule…", style = MaterialTheme.typography.bodyMedium)
            }
            Text("Your pairing code is private. Share it only with your own phone.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.error != null) Text(state.error, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }
        }
        item("settings") { TextButton(onClick = settings, modifier = Modifier.fillMaxWidth()) { Text("Reminder settings & tests") } }
    }
}

@Composable
fun SettingsScreen(state: PhoneUiState, actions: PhoneActions, paste: () -> Unit, disconnect: () -> Unit, modifier: Modifier = Modifier) {
    LazyColumn(modifier, contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item("connection") {
            Panel(Modifier.fillMaxWidth()) {
                SectionTitle("Connection")
                StatusPill(if (state.paired) "Connected to your PC schedule" else "Not paired")
                if (state.paired) {
                    Text(syncLabel(state), style = MaterialTheme.typography.bodyMedium)
                    Text("Firebase project: ${state.projectId.orEmpty()}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (state.error != null) Text(state.error, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    Button(onClick = actions.sync, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                        Text(if (state.syncing) "Syncing…" else "Sync now")
                    }
                }
                OutlinedButton(onClick = actions.scan, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("Scan PC QR") }
                TextButton(onClick = paste, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("Paste code") }
                if (state.paired) TextButton(onClick = disconnect, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                    Text(if (state.disconnecting) "Disconnecting…" else "Disconnect this phone", color = MaterialTheme.colorScheme.error)
                }
            }
        }
        item("permissions") {
            Panel(Modifier.fillMaxWidth()) {
                SectionTitle("Reminders")
                PermissionRow("Notifications", "Allow reminders and choose sounds for each YouTube page.", state.notificationsAllowed) { actions.setting("notifications") }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                PermissionRow("Precise alarms", "Remind you at the scheduled time. Without this, timing is approximate.", state.exactAlarmsAllowed) { actions.setting("alarms") }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Ring like an alarm", style = MaterialTheme.typography.titleMedium)
                        Text("Rings at alarm volume until you dismiss it, for up to 5 minutes, even on Do Not Disturb. Off gives one normal notification.",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = state.ringAlarm, onCheckedChange = actions.ring)
                }
                if (state.ringAlarm) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    PermissionRow("Full-screen alarm", "Show the alarm over the lock screen. Without this it still rings, as a notification.", state.fullScreenAllowed) { actions.setting("fullscreen") }
                }
            }
        }
        item("poco") {
            Panel(Modifier.fillMaxWidth()) {
                SectionTitle("POCO battery setup")
                Text("Help reminders work with the screen off.", style = MaterialTheme.typography.bodyMedium)
                StatusPill(if (state.batteryUnrestricted) "Android battery exemption allowed" else "Check battery restrictions", !state.batteryUnrestricted)
                Text("1. Enable Background autostart.\n2. Set Battery saver to No restrictions.\n3. Lock Whop Clipper in your recent apps.", style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = { actions.setting("autostart") }, modifier = Modifier.fillMaxWidth()) { Text("Open autostart settings") }
                TextButton(onClick = { actions.setting("battery") }, modifier = Modifier.fillMaxWidth()) { Text("Open app & battery settings") }
                Text("HyperOS menu names vary. This app can't verify the autostart switch or Recents lock.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item("tests") {
            Panel(Modifier.fillMaxWidth()) {
                SectionTitle("Test your reminders")
                Text("Try a local notification, then lock the phone for an alarm test.", style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = { actions.test(0) }, modifier = Modifier.fillMaxWidth()) { Text("Test notification now") }
                OutlinedButton(onClick = { actions.test(1) }, modifier = Modifier.fillMaxWidth()) { Text("Test alarm in 1 minute") }
                TextButton(onClick = { actions.test(480) }, modifier = Modifier.fillMaxWidth()) { Text("Test alarm in 8 hours") }
                Text("For a network test, use Send test push in the PC's Phone app tab. Setting an alarm test replaces any earlier alarm test.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item("about") {
            Text("Whop Clipper · Your submission checklist is saved on this phone. Marking a link submitted does not send it to Whop.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun PermissionRow(title: String, detail: String, allowed: Boolean, action: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(if (allowed) Icons.Outlined.CheckCircle else Icons.Outlined.Info, null,
                tint = if (allowed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        }
        Text(if (allowed) "Allowed" else "Needs your permission", style = MaterialTheme.typography.labelMedium,
            color = if (allowed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
        Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = action) { Text(if (allowed) "Manage" else "Allow in settings") }
    }
}
