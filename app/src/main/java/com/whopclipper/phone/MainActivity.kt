package com.whopclipper.phone

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.*
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.whopclipper.phone.ui.PhoneApp
import com.whopclipper.phone.ui.PhoneTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Android integrations stay here; screens render state and send actions to the ViewModel. */
class MainActivity : ComponentActivity() {
    private val model: PhoneViewModel by viewModels()
    private val messages = kotlinx.coroutines.channels.Channel<String>(kotlinx.coroutines.channels.Channel.BUFFERED)
    private val scanner = registerForActivityResult(ScanContract()) { result -> result.contents?.let(model::pair) }
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { model.refreshPermissions() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleNotification(intent)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.events.collect { event ->
                    when (event) {
                        is PhoneUiEvent.Message -> messages.send(event.text)
                        is PhoneUiEvent.CopyLink -> copy(event.url)
                        is PhoneUiEvent.OpenLink -> openWeb(event.url)
                        PhoneUiEvent.RestartRequired -> {
                            startActivity(Intent.makeRestartActivityTask(ComponentName(this@MainActivity, MainActivity::class.java)))
                            Runtime.getRuntime().exit(0)
                        }
                    }
                }
            }
        }
        setContent {
            val dark = isSystemInDarkTheme()
            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
            PhoneTheme(dark) { PhoneApp(model, messages, ::scan, ::openSetting, ::copy, ::openWeb, ::testReminder) }
        }
    }

    override fun onResume() {
        super.onResume()
        model.refreshPermissions()
        model.refreshTime()
        lifecycleScope.launch(Dispatchers.IO) {
            PhoneSync.request(applicationContext)
            Alarms.restore(applicationContext)
        }
    }
    override fun onConfigurationChanged(newConfig: Configuration) { super.onConfigurationChanged(newConfig); model.refreshTime() }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); handleNotification(intent) }
    private fun handleNotification(incoming: Intent) {
        val id = incoming.getStringExtra("clipId") ?: return
        val action = incoming.getStringExtra("action") ?: "details"
        incoming.removeExtra("clipId"); incoming.removeExtra("action")
        model.handleNotification(id, action)
    }
    private fun scan() {
        scanner.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            .setPrompt("Scan the private QR in Publishing → Phone app on your PC")
            .setBeepEnabled(false).setOrientationLocked(false))
    }
    private fun copy(url: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("YouTube link", url))
        if (Build.VERSION.SDK_INT < 33) messages.trySend("YouTube link copied")
    }
    private fun openWeb(url: String) {
        val uri = Uri.parse(url)
        if (uri.scheme !in listOf("http", "https") || uri.host.isNullOrBlank()) {
            messages.trySend("This link is invalid. Update the campaign on your PC."); return
        }
        try { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
        catch (_: android.content.ActivityNotFoundException) { messages.trySend("No app is available to open this link.") }
    }
    private fun openSetting(setting: String) {
        if (setting == "notifications" && Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED &&
            !getPreferences(MODE_PRIVATE).getBoolean("notificationAsked", false)) {
            getPreferences(MODE_PRIVATE).edit().putBoolean("notificationAsked", true).apply()
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        val action = when (setting) {
            "notifications" -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            "alarms" -> if (Build.VERSION.SDK_INT >= 31) Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")) else null
            "fullscreen" -> if (Build.VERSION.SDK_INT >= 34) Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName"))
                else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            "autostart" -> Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"))
            else -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
        }
        if (action == null) { messages.trySend("Precise alarms are already available."); return }
        try { startActivity(action) }
        catch (_: Exception) {
            try { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
            catch (_: Exception) { messages.trySend("Open Android Settings → Apps → Whop Clipper.") }
        }
    }
    private fun testReminder(delayMinutes: Int) {
        if (!Notifications.allowed(this)) { messages.trySend("Allow notifications before running this test."); return }
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                if (delayMinutes == 0) Notifications.test(applicationContext, "Your test notification", "Notifications are working on this phone.")
                else Alarms.schedule(applicationContext, "test", System.currentTimeMillis() + delayMinutes * 60_000L)
            }
            messages.send(if (delayMinutes == 0) "Test notification sent." else
                "Test alarm set for ${if (delayMinutes == 1) "1 minute" else "8 hours"} from now. Lock the phone to check delivery.")
        }
    }
}
