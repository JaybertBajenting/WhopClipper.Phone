package com.whopclipper.phone

import android.app.Application
import android.graphics.Bitmap
import android.util.Base64
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.unit.Density
import com.whopclipper.phone.ui.PhoneActions
import com.whopclipper.phone.ui.PhoneContent
import com.whopclipper.phone.ui.PhoneTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.ByteArrayOutputStream

/** Exercise the real screen callbacks without a ViewModel, Firebase, or network images. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w360dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class PhoneUiTest {
    @get:Rule val compose = createComposeRule()

    private val clip = Clip(
        id = "ui-test-clip", title = "A clip ready to submit", videoId = "sampleVid01",
        publishAt = 1_780_000_000_000L, pageId = "page-one", pageName = "My YouTube page",
        channelId = "channel-one", campaignName = "My campaign", campaignUrl = "https://whop.com/my-campaign/",
        status = "Published",
    )

    @Before fun seedLocalThumbnail() = runBlocking(Dispatchers.IO) {
        val context = RuntimeEnvironment.getApplication()
        Thumbs.prune(context, emptySet())
        val bitmap = Bitmap.createBitmap(8, 12, Bitmap.Config.ARGB_8888)
        val bytes = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes)
        bitmap.recycle()
        Thumbs.save(context, clip.id, Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP))
    }

    @Test fun firstRunOffersScanAndPasteWithoutAskingForPermissions() {
        var scans = 0
        val settings = mutableListOf<String>()
        show(PhoneUiState(loading = false), PhoneActions(scan = { scans++ }, setting = settings::add))

        compose.onNodeWithText("Set up your reminders").assertDoesNotExist()
        compose.onNodeWithText("Scan PC QR").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("Paste code").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("Pair with your PC").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(1, scans)
            assertEquals(emptyList<String>(), settings)
        }
    }

    @Test fun liveActionsCopyAndOpenTheSelectedClipsUrls() {
        val copied = mutableListOf<String>()
        val opened = mutableListOf<String>()
        show(liveState(), PhoneActions(copy = copied::add, openWeb = opened::add))

        compose.onNodeWithContentDescription("Copy YouTube link").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithContentDescription("Open Whop campaign").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(listOf(clip.watchUrl), copied)
            assertEquals(listOf(clip.campaignUrl), opened)
        }
    }

    @Test fun submissionChecklistIsExplicitlyLocalAndSendsOneChange() {
        val changes = mutableListOf<Pair<String, Boolean>>()
        show(liveState(), PhoneActions(submitted = { id, submitted -> changes.add(id to submitted) }))

        compose.onNodeWithText("Checklist on this phone only").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Mark link submitted").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf(clip.id to true), changes) }
    }

    @Test fun submittedChecklistCanBeReversed() {
        val changes = mutableListOf<Pair<String, Boolean>>()
        show(liveState(submitted = true).copy(liveFilter = LiveFilter.SUBMITTED),
            PhoneActions(submitted = { id, submitted -> changes.add(id to submitted) }))

        compose.onNodeWithText("Marked submitted").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf(clip.id to false), changes) }
    }

    @Test fun missingCampaignExplainsHowToFixItWithoutOfferingABrokenLink() {
        val opened = mutableListOf<String>()
        show(liveState(clip.copy(campaignUrl = null)), PhoneActions(openWeb = opened::add))

        compose.onNodeWithText("Campaign link missing. Add it on your PC, then sync.")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Open Whop campaign").assertDoesNotExist()
        compose.onNodeWithContentDescription("Open Whop campaign").assertDoesNotExist()
        compose.runOnIdle { assertEquals(emptyList<String>(), opened) }
    }

    @Test fun pairingErrorKeepsRecoveryActionsAvailable() {
        val error = "Invalid pairing code. Get a fresh code from Publishing → Phone app on your PC."
        show(PhoneUiState(loading = false, error = error))

        compose.onNodeWithText(error).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Scan PC QR").performScrollTo().assertIsEnabled()
        compose.onNodeWithText("Paste code").performScrollTo().assertIsEnabled()
    }

    @Test fun pairingActionsRemainReachableWithDoubleFontSizeInDarkMode() {
        show(PhoneUiState(loading = false), fontScale = 2f, dark = true)

        compose.onNodeWithText("Scan PC QR").performScrollTo().assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("Paste code").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("Pair with your PC").assertIsDisplayed()
    }

    @Test fun detailsAndSettingsReturnToTheSameUpcomingScrollPosition() {
        val entries = (1..20).map { index -> PhoneClipItem(clip.copy(id = "clip-$index", videoId = "", title = "Scheduled clip $index",
            publishAt = clip.publishAt + index * 60_000L, status = "Scheduled"), null, false) }
        val screen = mutableStateOf(PhoneUiState(loading = false, paired = true, clips = entries, upcoming = entries,
            notificationsAllowed = true, exactAlarmsAllowed = true, now = clip.publishAt))
        compose.setContent {
            PhoneTheme { PhoneContent(screen.value, PhoneActions(
                openClip = { screen.value = screen.value.copy(openClipId = it) },
                closeClip = { screen.value = screen.value.copy(openClipId = null) },
                tab = { screen.value = screen.value.copy(selectedTab = it) }
            )) }
        }
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Scheduled clip 15"))
        compose.onNodeWithText("Scheduled clip 15").performClick()
        compose.onNodeWithText("Clip details").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Scheduled clip 15").assertIsDisplayed()
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Connection").assertIsDisplayed()
        compose.onNodeWithText("Upcoming").performClick()
        compose.onNodeWithText("Scheduled clip 15").assertIsDisplayed()
    }

    @Test fun manyUpcomingClipsShowFourCompleteRowsAndOpenTheLastClip() {
        val entries = (1..40).map { index -> PhoneClipItem(clip.copy(id = "clip-$index", videoId = "",
            title = "Scheduled clip $index with a longer title", publishAt = clip.publishAt + index * 60_000L,
            status = "Scheduled"), null, false) }
        val opened = mutableListOf<String>()
        show(PhoneUiState(loading = false, paired = true, clips = entries, upcoming = entries,
            notificationsAllowed = true, exactAlarmsAllowed = true, now = clip.publishAt),
            PhoneActions(openClip = opened::add))

        val viewport = compose.onNode(hasScrollToIndexAction()).fetchSemanticsNode().boundsInRoot
        for (index in 1..4) {
            val row = compose.onNodeWithText("Scheduled clip $index with a longer title").assertIsDisplayed()
                .fetchSemanticsNode().boundsInRoot
            assertTrue("Clip $index must fit completely in the initial viewport", row.top >= viewport.top && row.bottom <= viewport.bottom)
        }
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Scheduled clip 40 with a longer title"))
        compose.onNodeWithText("Scheduled clip 40 with a longer title").performClick()
        compose.runOnIdle { assertEquals(listOf("clip-40"), opened) }
    }

    @Test fun compactLiveControlsRemainReachableWithDoubleFontSize() {
        val copied = mutableListOf<String>()
        val opened = mutableListOf<String>()
        val changes = mutableListOf<Pair<String, Boolean>>()
        show(liveState(), PhoneActions(copy = copied::add, openWeb = opened::add,
            submitted = { id, submitted -> changes.add(id to submitted) }), fontScale = 2f)

        compose.onNodeWithContentDescription("Copy YouTube link").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("Open Whop campaign").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("Mark link submitted").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(listOf(clip.watchUrl), copied)
            assertEquals(listOf(clip.campaignUrl), opened)
            assertEquals(listOf(clip.id to true), changes)
        }
    }

    @Test fun upcomingAndLiveYoutubeButtonsOpenTheirVideoWithoutOpeningDetails() {
        val upcoming = PhoneClipItem(clip.copy(status = "Scheduled", publishAt = clip.publishAt + 120_000), null, false)
        val live = PhoneClipItem(clip.copy(id = "live-clip", videoId = "otherVid002"), "public", false)
        val screen = mutableStateOf(PhoneUiState(loading = false, paired = true, clips = listOf(upcoming, live),
            upcoming = listOf(upcoming), live = listOf(live), notificationsAllowed = true, exactAlarmsAllowed = true,
            now = clip.publishAt + 60_000))
        val opened = mutableListOf<String>()
        val details = mutableListOf<String>()
        compose.setContent {
            PhoneTheme { PhoneContent(screen.value, PhoneActions(openWeb = opened::add, openClip = details::add,
                tab = { screen.value = screen.value.copy(selectedTab = it) })) }
        }

        compose.onNodeWithText("YouTube").assertIsEnabled().performClick()
        compose.onNodeWithText("Live").performClick()
        compose.onNodeWithContentDescription("Open ${clip.title} in YouTube").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(listOf(upcoming.clip.watchUrl, live.clip.watchUrl), opened)
            assertEquals(emptyList<String>(), details)
        }
    }

    @Test fun youtubeButtonIsDisabledUntilThePcHasUploadedTheVideo() {
        val item = PhoneClipItem(clip.copy(videoId = "", status = "Uploading"), null, false)
        show(PhoneUiState(loading = false, paired = true, clips = listOf(item), upcoming = listOf(item),
            notificationsAllowed = true, exactAlarmsAllowed = true))
        compose.onNodeWithContentDescription("Open ${clip.title} in YouTube").assertIsNotEnabled()
    }

    private fun liveState(value: Clip = clip, submitted: Boolean = false): PhoneUiState {
        val item = PhoneClipItem(value, "public", submitted)
        return PhoneUiState(
            loading = false, paired = true, clips = listOf(item), live = listOf(item),
            selectedTab = PhoneTab.LIVE, notificationsAllowed = true, exactAlarmsAllowed = true,
            now = value.publishAt + 60_000,
        )
    }

    private fun show(state: PhoneUiState, actions: PhoneActions = PhoneActions(), fontScale: Float = 1f, dark: Boolean = false) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                PhoneTheme(dark) { PhoneContent(state, actions) }
            }
        }
    }
}
