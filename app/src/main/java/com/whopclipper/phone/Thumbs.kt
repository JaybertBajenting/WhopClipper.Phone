package com.whopclipper.phone

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.AtomicFile
import android.util.Base64
import android.util.LruCache
import androidx.annotation.WorkerThread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** Reads at most [limit] bytes plus the one byte needed to reject an oversized response. */
internal fun readLimitedImage(input: InputStream, limit: Int): ByteArray? {
    require(limit in 1..600_000)
    val output = ByteArrayOutputStream(minOf(limit, 8192))
    val buffer = ByteArray(8192)
    while (output.size() <= limit) {
        val count = input.read(buffer, 0, minOf(buffer.size, limit - output.size() + 1))
        if (count < 0) return output.toByteArray().takeIf { it.isNotEmpty() }
        if (count == 0) {
            val byte = input.read()
            if (byte < 0) return output.toByteArray().takeIf { it.isNotEmpty() }
            output.write(byte)
        } else output.write(buffer, 0, count)
    }
    return null
}

/** Private disk stills plus a small decoded cache. All disk/decode/network work runs on IO. */
object Thumbs {
    private const val MAX_IMAGE_BYTES = 600_000
    private const val MAX_STILL_BYTES = 60_000
    private const val RETRY_AFTER_MILLIS = 5 * 60_000L
    private val safeId = Regex("[A-Za-z0-9-]{1,64}")
    private val videoId = Regex("[A-Za-z0-9_-]{11}")
    private val diskLock = Any()
    private val requests = Array(32) { Mutex() }
    private val downloads = Semaphore(2)
    private val memory = object : LruCache<String, Bitmap>(6 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }
    private val failed = LruCache<String, Long>(256)
    private val versions = HashMap<String, Long>()
    private var retained: Set<String>? = null
    private var scheduleGeneration = 0L
    private val mutableRevision = MutableStateFlow(0L)
    val revision = mutableRevision.asStateFlow()

    private fun dir(context: Context) = File(context.filesDir, "thumbs").apply { mkdirs() }
    private fun file(context: Context, id: String): File? =
        if (safeId.matches(id)) File(dir(context), "$id.jpg") else null
    private fun source(target: File) = File(target.parentFile, "${target.nameWithoutExtension}.source")
    private fun invalidate(id: String) {
        memory.remove(id)
        versions[id] = mutableRevision.value + 1
        mutableRevision.value += 1
    }

    /** Null is a normal result: placeholders must not prevent reading or acting on a clip. */
    suspend fun thumbnail(context: Context, clip: Clip, allowNetwork: Boolean): Bitmap? = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val key = "${clip.id}:${clip.videoId}"
        requests[(key.hashCode() and Int.MAX_VALUE) % requests.size].withLock {
            currentCoroutineContext().ensureActive()
            synchronized(diskLock) {
                val target = file(app, clip.id)
                // A re-upload must not keep the old YouTube still just because the local clip ID survived.
                val sourceId = target?.let { readSource(it) }
                if (target != null && sourceId != null && sourceId != clip.videoId) {
                    AtomicFile(target).delete()
                    source(target).delete()
                    invalidate(clip.id)
                }
            }
            load(app, clip.id)?.let { return@withLock it }
            if (!allowNetwork || !videoId.matches(clip.videoId)) return@withLock null
            val lastFailed = failed.get(key)
            if (lastFailed != null && android.os.SystemClock.elapsedRealtime() - lastFailed < RETRY_AFTER_MILLIS) return@withLock null
            downloads.withPermit {
                val coroutine = currentCoroutineContext()
                coroutine.ensureActive()
                if (!fetchBounded(app, clip) { coroutine.ensureActive() }) failed.put(key, android.os.SystemClock.elapsedRealtime())
            }
            load(app, clip.id)
        }
    }

    @WorkerThread
    fun load(context: Context, id: String): Bitmap? = synchronized(diskLock) {
        memory.get(id)?.let { return@synchronized it }
        val target = file(context, id) ?: return@synchronized null
        val bytes = try {
            AtomicFile(target).openRead().use { readLimitedImage(it, MAX_IMAGE_BYTES) }
        } catch (_: IOException) { null } ?: return@synchronized null
        decodeSmall(bytes)?.also { memory.put(id, it) }
    }

    /** PC stills are immutable until their bytes change. Avoid decoding/writing unchanged sync data. */
    @WorkerThread
    fun save(context: Context, id: String, base64: String) {
        if (base64.length > 90_000 || !safeId.matches(id)) return
        val bytes = try { Base64.decode(base64, Base64.DEFAULT) } catch (_: IllegalArgumentException) { return }
        if (bytes.size !in 1..MAX_STILL_BYTES) return
        synchronized(diskLock) {
            val target = file(context, id) ?: return
            val previous = try { AtomicFile(target).openRead().use { readLimitedImage(it, MAX_STILL_BYTES) } } catch (_: IOException) { null }
            if (previous != null && previous.contentEquals(bytes)) return
            val bitmap = decodeSmall(bytes) ?: return
            if (writeAtomic(target, bytes)) {
                source(target).delete()
                invalidate(id)
                memory.put(id, bitmap)
            }
        }
    }

    /** Also invalidates downloads already in progress, so disconnect cannot repopulate old images. */
    @WorkerThread
    fun prune(context: Context, keep: Set<String>) = synchronized(diskLock) {
        if (retained != keep) {
            retained = keep.toSet()
            scheduleGeneration++
        }
        var changed = false
        dir(context).listFiles()?.forEach { file ->
            val id = file.name.substringBefore('.')
            if (id !in keep) { file.delete(); memory.remove(id); versions.remove(id); changed = true }
        }
        memory.snapshot().keys.filter { it !in keep }.forEach { memory.remove(it); changed = true }
        versions.keys.retainAll(keep)
        if (keep.isEmpty()) { memory.evictAll(); failed.evictAll(); versions.clear() }
        if (changed) mutableRevision.value += 1
    }

    /** Kept for callers outside Compose; a busy downloader simply leaves the placeholder in place. */
    @WorkerThread
    fun fetch(context: Context, clip: Clip): Boolean {
        if (!downloads.tryAcquire()) return false
        return try { fetchBounded(context.applicationContext, clip) } finally { downloads.release() }
    }

    @WorkerThread
    private fun fetchBounded(context: Context, clip: Clip, checkActive: () -> Unit = {}): Boolean {
        if (!safeId.matches(clip.id) || !videoId.matches(clip.videoId)) return false
        val stamp = synchronized(diskLock) {
            if (retained?.contains(clip.id) == false) return false
            scheduleGeneration to versions[clip.id]
        }
        for (name in listOf("oar2", "sddefault", "hqdefault")) {
            checkActive()
            val bytes = get("https://i.ytimg.com/vi/${clip.videoId}/$name.jpg") ?: continue
            checkActive()
            val full = decodeSmall(bytes, preserveWideDetail = true) ?: continue
            val tall = if (full.height > full.width) full else middle(full)
            val small = scaled(tall)
            val output = ByteArrayOutputStream()
            if (!small.compress(Bitmap.CompressFormat.JPEG, 80, output)) continue
            val jpeg = output.toByteArray()
            if (jpeg.size > MAX_IMAGE_BYTES) continue
            return synchronized(diskLock) {
                if (retained?.contains(clip.id) == false || stamp != (scheduleGeneration to versions[clip.id])) return@synchronized false
                val target = file(context, clip.id) ?: return@synchronized false
                if (!writeAtomic(target, jpeg)) return@synchronized false
                // No credentials or URLs go on disk; this identifies which upload supplied the picture.
                if (!writeAtomic(source(target), clip.videoId.toByteArray())) {
                    AtomicFile(target).delete()
                    return@synchronized false
                }
                invalidate(clip.id)
                memory.put(clip.id, small)
                true
            }
        }
        return false
    }

    private fun decodeSmall(bytes: ByteArray, preserveWideDetail: Boolean = false): Bitmap? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        // Bound dimensions before allocation, including deliberately tiny compressed image bombs.
        if (options.outWidth !in 1..8192 || options.outHeight !in 1..8192 || options.outWidth.toLong() * options.outHeight > 16_000_000) return null
        // Wide YouTube images need enough source pixels for the narrow portrait crop in the middle.
        val wide = preserveWideDetail && options.outWidth >= options.outHeight
        val targetWidth = if (wide) 768 else 216
        val targetHeight = if (wide) 576 else 384
        var sample = 1
        while (options.outWidth / (sample * 2) >= targetWidth || options.outHeight / (sample * 2) >= targetHeight) sample *= 2
        options.inJustDecodeBounds = false
        options.inSampleSize = sample
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.let { if (wide) it else scaled(it) }
    }

    private fun scaled(bitmap: Bitmap): Bitmap {
        val factor = minOf(1f, 216f / bitmap.width, 384f / bitmap.height)
        return if (factor >= 1f) bitmap else Bitmap.createScaledBitmap(bitmap,
            maxOf(1, (bitmap.width * factor).toInt()), maxOf(1, (bitmap.height * factor).toInt()), true)
    }

    /** A wide YouTube still includes letterboxing around the vertical clip. */
    private fun middle(wide: Bitmap): Bitmap {
        val pictureHeight = maxOf(1, minOf(wide.height, wide.width * 9 / 16))
        val width = maxOf(1, pictureHeight * 9 / 16)
        return Bitmap.createBitmap(wide, (wide.width - width) / 2, (wide.height - pictureHeight) / 2, width, pictureHeight)
    }

    private fun writeAtomic(target: File, bytes: ByteArray): Boolean {
        val file = AtomicFile(target)
        var stream: java.io.FileOutputStream? = null
        return try {
            stream = file.startWrite()
            stream.write(bytes)
            file.finishWrite(stream)
            true
        } catch (_: IOException) { file.failWrite(stream); false }
    }

    private fun readSource(target: File): String? = try {
        AtomicFile(source(target)).openRead().use { readLimitedImage(it, 32)?.toString(Charsets.UTF_8) }
    } catch (_: IOException) { null }

    private fun get(url: String): ByteArray? {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 4000
            connection.readTimeout = 6000
            connection.instanceFollowRedirects = false
            if (connection.responseCode != 200 || connection.contentLengthLong > MAX_IMAGE_BYTES) null
            else connection.inputStream.use { readLimitedImage(it, MAX_IMAGE_BYTES) }
        } catch (_: IOException) { null } finally { connection.disconnect() }
    }
}
