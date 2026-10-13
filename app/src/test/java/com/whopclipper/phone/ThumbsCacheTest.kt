package com.whopclipper.phone

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Base64
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ThumbsCacheTest {
    private lateinit var context: Context

    @Before fun reset() {
        context = RuntimeEnvironment.getApplication()
        Thumbs.prune(context, emptySet())
    }

    @Test fun cachesDecodedImagesAndDoesNotInvalidateAnIdenticalSync() {
        val encoded = still(Color.RED)
        Thumbs.save(context, "clip", encoded)
        val first = Thumbs.load(context, "clip")
        assertNotNull(first)
        val revision = Thumbs.revision.value
        Thumbs.save(context, "clip", encoded)
        assertEquals(revision, Thumbs.revision.value)
        assertSame(first, Thumbs.load(context, "clip"))
    }

    @Test fun changedPcStillReplacesTheCachedPicture() {
        Thumbs.save(context, "clip", still(Color.RED))
        assertEquals(Color.RED, Thumbs.load(context, "clip")!!.getPixel(0, 0))
        Thumbs.save(context, "clip", still(Color.BLUE))
        assertEquals(Color.BLUE, Thumbs.load(context, "clip")!!.getPixel(0, 0))
    }

    @Test fun pruneClearsBothFilesAndDecodedCache() {
        Thumbs.save(context, "clip", still(Color.RED))
        Thumbs.prune(context, setOf("clip"))
        assertNotNull(Thumbs.load(context, "clip"))
        Thumbs.prune(context, emptySet())
        assertNull(Thumbs.load(context, "clip"))
        assertFalse(File(context.filesDir, "thumbs/clip.jpg").exists())
    }

    @Test fun changedVideoIdCannotReuseAnOldYoutubePicture() = runBlocking {
        Thumbs.save(context, "clip", still(Color.RED))
        File(context.filesDir, "thumbs/clip.source").writeText("oldVideo001")
        val clip = Clip("clip", "Clip", "newVideo001", 1, "page", "Page", "channel", "Campaign", null, "Published")
        assertNull(Thumbs.thumbnail(context, clip, allowNetwork = false))
        assertNull(Thumbs.load(context, "clip"))
    }

    @Test fun oversizedPairingStillCannotReplaceAValidImage() {
        Thumbs.save(context, "clip", still(Color.RED))
        Thumbs.save(context, "clip", "A".repeat(90_001))
        assertEquals(Color.RED, Thumbs.load(context, "clip")!!.getPixel(0, 0))
    }

    private fun still(color: Int): String {
        val bitmap = Bitmap.createBitmap(32, 48, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        val output = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
        bitmap.recycle()
        return Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
    }
}
