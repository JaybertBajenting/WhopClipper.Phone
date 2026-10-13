package com.whopclipper.phone

import org.junit.Assert.*
import org.junit.Test

class PhoneScheduleTest {
    private fun item(id: String, at: Long, video: String = "abcdefghijk", delivery: String? = null,
                     submitted: Boolean = false, status: String = "Scheduled") = PhoneClipItem(
        Clip(id, "Clip $id", video, at, "page", "My page", "", "Campaign", "https://whop.com/example", status), delivery, submitted,
    )

    @Test fun `scheduled clip moves exactly once at its scheduled time without claiming visibility`() {
        val clip = item("next", 10_000)
        val before = PhoneSchedule.at(listOf(clip), 9_999)
        assertEquals(listOf(clip), before.upcoming)
        assertTrue(before.live.isEmpty())
        assertEquals(10_000L, before.nextChangeAt)
        val due = PhoneSchedule.at(listOf(clip), 10_000)
        assertTrue(due.upcoming.isEmpty())
        assertEquals(listOf(clip), due.live)
        assertNull(due.live.single().deliveryStatus)
        assertNull(due.nextChangeAt)
    }

    @Test fun `past slot still waiting for upload stays visible in upcoming`() {
        val clip = item("upload", 100, video = "")
        val result = PhoneSchedule.at(listOf(clip), 200)
        assertEquals(listOf(clip), result.upcoming)
        assertTrue(result.live.isEmpty())
        assertNull(result.nextChangeAt)
    }

    @Test fun `pending PC uploads stay visible and cancelled uploads do not`() {
        val queued = item("queued", 500, video = "", status = "Queued")
        val uploading = item("uploading", 600, video = "", status = "Uploading")
        val processing = item("processing", 650, status = "Processing")
        val cancelled = item("cancelled", 700, video = "", status = "Cancelled")
        val result = PhoneSchedule.at(listOf(cancelled, processing, uploading, queued), 800)
        assertEquals(listOf(queued, uploading, processing), result.upcoming)
        assertTrue(result.live.isEmpty())
    }

    @Test fun `needs submission comes before submitted clips and each group is newest first`() {
        val old = item("old", 100)
        val recent = item("recent", 300)
        val done = item("done", 400, submitted = true)
        assertEquals(listOf(recent, old, done), PhoneSchedule.at(listOf(old, done, recent), 500).live)
    }

    @Test fun `filters keep the checklist reversible and show completed count separately`() {
        val pending = item("pending", 100)
        val submitted = item("done", 100, submitted = true)
        val state = PhoneUiState(live = listOf(pending, submitted))
        assertEquals(listOf(pending), state.filteredLive)
        assertEquals(listOf(submitted), state.copy(liveFilter = LiveFilter.SUBMITTED).filteredLive)
        assertEquals(listOf(pending, submitted), state.copy(liveFilter = LiveFilter.ALL).filteredLive)
    }

    @Test fun `earlier and inconclusive statuses remain distinguishable`() {
        val clips = listOf("public", "earlier", "unverified", "not-confirmed").mapIndexed { index, status ->
            item(status, index.toLong(), delivery = status)
        }
        val result = PhoneSchedule.at(clips, 100)
        assertTrue(result.upcoming.isEmpty())
        assertEquals(clips.map { it.deliveryStatus }.toSet(), result.live.map { it.deliveryStatus }.toSet())
    }

    @Test fun `only a future undelivered video creates a time boundary`() {
        val upload = item("upload", 500, video = "")
        val delivered = item("delivered", 600, delivery = "public")
        val later = item("later", 800)
        val earlier = item("earlier", 700)
        assertEquals(700L, PhoneSchedule.at(listOf(upload, delivered, later, earlier), 400).nextChangeAt)
        assertNull(PhoneSchedule.at(listOf(upload, delivered), 400).nextChangeAt)
    }

    @Test fun `removed detail resolves to nothing and cancelled clip never appears as live`() {
        val cancelled = item("cancelled", 100, status = "Cancelled")
        assertTrue(PhoneSchedule.at(listOf(cancelled), 200).live.isEmpty())
        assertTrue(PhoneSchedule.at(listOf(cancelled), 50).upcoming.isEmpty())
        assertNull(PhoneSchedule.at(listOf(cancelled), 50).nextChangeAt)
        assertNull(PhoneUiState(clips = listOf(cancelled), openClipId = cancelled.clip.id).openClip)
        assertNull(PhoneUiState(clips = emptyList(), openClipId = "removed").openClip)
    }

    @Test fun `large schedules classify every clip once and have deterministic order`() {
        val clips = (1..2000).map { item(it.toString(), it * 1000L) }.reversed()
        val result = PhoneSchedule.at(clips, 1_000_000)
        assertEquals(1000, result.upcoming.size)
        assertEquals(1000, result.live.size)
        assertEquals("1001", result.upcoming.first().clip.id)
        assertEquals("1000", result.live.first().clip.id)
        assertEquals(1_001_000L, result.nextChangeAt)
    }
}
