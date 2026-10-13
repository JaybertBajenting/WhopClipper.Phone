package com.whopclipper.phone.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.whopclipper.phone.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow

/** All screen actions are explicit, which also lets previews/tests use inert sample data. */
class PhoneActions(
    val tab: (PhoneTab) -> Unit = {}, val filter: (LiveFilter) -> Unit = {},
    val openClip: (String) -> Unit = {}, val closeClip: () -> Unit = {},
    val sync: () -> Unit = {}, val pair: (String) -> Unit = {}, val disconnect: () -> Unit = {},
    val submitted: (String, Boolean) -> Unit = { _, _ -> },
    val scan: () -> Unit = {}, val setting: (String) -> Unit = {},
    val copy: (String) -> Unit = {}, val openWeb: (String) -> Unit = {}, val test: (Int) -> Unit = {},
    val ring: (Boolean) -> Unit = {}
)

@Composable
fun PhoneApp(model: PhoneViewModel, messages: Channel<String>, scan: () -> Unit,
             setting: (String) -> Unit, copy: (String) -> Unit, openWeb: (String) -> Unit, test: (Int) -> Unit) {
    val state by model.uiState.collectAsStateWithLifecycle()
    val actions = remember(model) { PhoneActions(model::selectTab, model::setLiveFilter, model::openClip, model::closeClip,
        model::syncNow, model::pair, model::disconnect, model::setSubmitted, scan, setting, copy, openWeb, test, model::setRing) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(messages) { messages.receiveAsFlow().collect { snackbar.showSnackbar(it) } }
    PhoneContent(state, actions, snackbar)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhoneContent(state: PhoneUiState, actions: PhoneActions, snackbar: SnackbarHostState = remember { SnackbarHostState() }) {
    var showPaste by rememberSaveable { mutableStateOf(false) }
    var showDisconnect by rememberSaveable { mutableStateOf(false) }
    var showSetup by rememberSaveable { mutableStateOf(false) }
    var wasPaired by remember { mutableStateOf<Boolean?>(null) }
    var wasPairing by remember { mutableStateOf(false) }
    var playing by rememberSaveable(state.openClipId) { mutableStateOf(false) }
    val screenStates = rememberSaveableStateHolder()
    LaunchedEffect(state.paired, state.pairing, state.loading) {
        if (!state.loading) {
            if (state.paired && (wasPaired == false || wasPairing && !state.pairing)) {
                showPaste = false
                showSetup = !state.notificationsAllowed || !state.exactAlarmsAllowed
            }
            wasPaired = state.paired
            wasPairing = state.pairing
        }
    }
    val detail = state.openClip
    BackHandler(showPaste || playing || detail != null || !state.paired && state.selectedTab == PhoneTab.SETTINGS) {
        when { showPaste -> if (!state.busy) showPaste = false; playing -> playing = false; detail != null -> actions.closeClip(); else -> actions.tab(PhoneTab.UPCOMING) }
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Column(Modifier.windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = 20.dp, vertical = 12.dp)) {
                if (showPaste || detail != null || !state.paired && state.selectedTab == PhoneTab.SETTINGS) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { if (showPaste) { if (!state.busy) showPaste = false } else if (playing) playing = false else if (detail != null) actions.closeClip() else actions.tab(PhoneTab.UPCOMING) }) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back")
                        }
                        Text(if (showPaste) "Pair with your PC" else if (detail != null) "Clip details" else "Settings", style = MaterialTheme.typography.titleLarge)
                    }
                } else {
                    Text("WHOP CLIPPER", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (!state.paired) "Your clips. On time." else when (state.selectedTab) {
                            PhoneTab.UPCOMING -> "Upcoming"; PhoneTab.LIVE -> "Live"; PhoneTab.SETTINGS -> "Settings"
                        }, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f).semantics { heading() })
                        if (state.paired) IconButton(onClick = actions.sync, enabled = !state.syncing && !state.disconnecting) {
                            if (state.syncing) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                            else Icon(Icons.Outlined.Sync, "Sync schedule")
                        }
                    }
                    if (state.paired) Text(syncLabel(state), style = MaterialTheme.typography.bodySmall,
                        color = if (state.error == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
                }
            }
        },
        bottomBar = {
            if (state.paired && detail == null && !showPaste) NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                listOf(Triple(PhoneTab.UPCOMING, "Upcoming", Icons.Outlined.Schedule),
                    Triple(PhoneTab.LIVE, "Live", Icons.Outlined.CheckCircle),
                    Triple(PhoneTab.SETTINGS, "Settings", Icons.Outlined.Settings)).forEach { (tab, title, icon) ->
                    NavigationBarItem(selected = state.selectedTab == tab, onClick = { actions.tab(tab) },
                        icon = { Icon(icon, null) }, label = { Text(title) })
                }
            }
        }
    ) { insets ->
        Box(Modifier.fillMaxSize().padding(insets), contentAlignment = Alignment.TopCenter) {
            val content = Modifier.widthIn(max = 640.dp).fillMaxWidth()
            when {
                state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                showPaste -> PasteCodeScreen(state.busy, state.error, { showPaste = false }, actions.pair, content)
                detail != null -> key(detail.clip.id) { ClipDetailScreen(detail, state, actions, playing, { playing = it }, content) }
                !state.paired && state.selectedTab != PhoneTab.SETTINGS -> PairingScreen(state, actions.scan, { showPaste = true },
                    { actions.tab(PhoneTab.SETTINGS) }, content)
                state.selectedTab == PhoneTab.SETTINGS -> screenStates.SaveableStateProvider("settings") {
                    SettingsScreen(state, actions, { showPaste = true }, { showDisconnect = true }, content)
                }
                else -> screenStates.SaveableStateProvider("lists") { ClipsScreen(state, actions, { showSetup = true }, content) }
            }
        }
    }
    if (showDisconnect) AlertDialog(
        onDismissRequest = { if (!state.disconnecting) showDisconnect = false },
        title = { Text("Disconnect this phone?") },
        text = { Text("This clears downloaded clips, reminders, and your local submission checklist. You can pair again from your PC.") },
        confirmButton = { TextButton(onClick = { showDisconnect = false; actions.disconnect() }) { Text("Disconnect", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { showDisconnect = false }) { Text("Keep connected") } }
    )
    if (showSetup) ModalBottomSheet(onDismissRequest = { showSetup = false }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Set up your reminders", style = MaterialTheme.typography.headlineSmall)
            Text("Your schedule is connected. These phone settings help reminders arrive when you need them.", style = MaterialTheme.typography.bodyMedium)
            PermissionRow("1. Notifications", "Show clip reminders and their actions.", state.notificationsAllowed) { actions.setting("notifications") }
            PermissionRow("2. Precise alarms", "Use each clip’s scheduled time.", state.exactAlarmsAllowed) { actions.setting("alarms") }
            Text("3. POCO battery settings", style = MaterialTheme.typography.titleMedium)
            Text("Allow Background autostart, select No restrictions for battery, and lock Whop Clipper in Recents. These switches need your check.", style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = { actions.setting("battery") }, modifier = Modifier.fillMaxWidth()) { Text("Open app settings") }
            Button(onClick = { showSetup = false }, modifier = Modifier.fillMaxWidth()) { Text("Continue to my clips") }
        }
    }
}

@Composable
private fun PasteCodeScreen(busy: Boolean, error: String?, dismiss: () -> Unit, pair: (String) -> Unit, modifier: Modifier) {
    // Pairing contains secrets: keep it out of saved instance state and clear on dismiss.
    var code by remember { mutableStateOf("") }
    Column(modifier.fillMaxHeight().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Panel(Modifier.fillMaxWidth()) {
            Text("Connect your schedule", style = MaterialTheme.typography.titleLarge)
            Text("On your PC, open Publishing → Phone app → Copy pairing code. Keep this code private.")
            OutlinedTextField(code, { if (it.length <= 12_000) code = it }, enabled = !busy, label = { Text("Paste code") },
                modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 4,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                visualTransformation = PasswordVisualTransformation())
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (error != null) Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            Button(onClick = { pair(code) }, enabled = code.isNotBlank() && !busy, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "Connecting…" else "Connect phone") }
            TextButton(onClick = dismiss, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
        }
    }
}

@Composable
fun Panel(modifier: Modifier = Modifier, highlighted: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier, shape = RoundedCornerShape(24.dp),
        color = if (highlighted) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        border = if (highlighted) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
fun SectionTitle(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp).semantics { heading() })
}

@Composable
fun StatusPill(label: String, warning: Boolean = false) {
    Surface(color = if (warning) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        contentColor = if (warning) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
        shape = RoundedCornerShape(8.dp)) {
        Text(label, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, description: String, action: String? = null, onAction: () -> Unit = {}) {
    Panel(Modifier.fillMaxWidth()) {
        Icon(icon, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (action != null) OutlinedButton(onClick = onAction) { Text(action) }
    }
}

fun syncLabel(state: PhoneUiState): String = when {
    state.syncing -> "Syncing your schedule…"
    state.error != null -> if (state.lastSync > 0) "Couldn't sync · saved ${shortDateTime(state.lastSync)}" else "Couldn't sync · try again"
    state.lastSync > 0 -> "Last synced ${shortDateTime(state.lastSync)}"
    else -> "Waiting for the first sync"
}

fun shortDateTime(time: Long): String = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(time))
