package com.whopclipper.phone

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Pairing keys and local schedule never enter Android backup or plaintext preferences. */
class PhoneStore(context: Context) {
    private val prefs = context.getSharedPreferences("phone-v1", Context.MODE_PRIVATE)
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey("whop-phone-storage", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("whop-phone-storage", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    private fun read(): JSONObject {
        val encoded = prefs.getString("encrypted", null) ?: return JSONObject()
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        return JSONObject(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8))
    }
    private fun write(value: JSONObject) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        check(prefs.edit().putString("encrypted", Base64.encodeToString(cipher.iv + cipher.doFinal(value.toString().toByteArray()), Base64.NO_WRAP)).commit())
        revision.value += 1
    }
    fun state(): JSONObject = synchronized(lock) { read() }
    /** Read and decrypt once, then share immutable values with all screen/card consumers. Call off the UI thread. */
    fun snapshot(): PhoneSnapshot = synchronized(lock) {
        val value = read()
        val pairing = value.optJSONObject("pairing")
        val deliveries = value.optJSONObject("deliveries")
        val submitted = value.optJSONObject("submitted")
        val clips = value.optJSONArray("clips")
        PhoneSnapshot(
            paired = pairing != null,
            projectId = pairing?.optString("projectId"),
            lastSync = value.optLong("lastSync"),
            error = value.optString("error").takeIf { it.isNotBlank() },
            items = if (clips == null) emptyList() else (0 until clips.length()).map { index ->
                val clip = Clip.fromJson(clips.getJSONObject(index))
                PhoneClipItem(clip, deliveries?.optJSONObject(clip.id)?.optString("status"), submitted?.optBoolean(clip.id) == true)
            },
            revision = revision.value,
            alarmIds = value.optJSONArray("alarms")?.let { ids -> (0 until ids.length()).map { ids.getString(it) } } ?: emptyList(),
        )
    }
    fun update(block: (JSONObject) -> Unit) = synchronized(lock) { val state = read(); block(state); write(state) }
    fun pairing(): JSONObject? = state().optJSONObject("pairing")
    fun clips(): List<Clip> = state().optJSONArray("clips")?.let { array ->
        (0 until array.length()).map { Clip.fromJson(array.getJSONObject(it)) }
    } ?: emptyList()
    fun clip(id: String): Clip? = clips().find { it.id == id }
    fun delivery(id: String): JSONObject? = state().optJSONObject("deliveries")?.optJSONObject(id)
    fun delivered(id: String, status: String) = update { state ->
        val deliveries = state.optJSONObject("deliveries") ?: JSONObject()
        deliveries.put(id, JSONObject().put("status", status).put("at", System.currentTimeMillis()))
        state.put("deliveries", deliveries)
    }
    fun submitted(id: String): Boolean = state().optJSONObject("submitted")?.optBoolean(id) ?: false
    fun submit(id: String, value: Boolean) = update { state ->
        state.put("submitted", (state.optJSONObject("submitted") ?: JSONObject()).put(id, value))
    }
    companion object {
        private val lock = Any()
        private val revision = MutableStateFlow(0L)
        /** Every PhoneStore instance shares the same change signal; this contains no schedule or pairing secrets. */
        val revisions: StateFlow<Long> = revision.asStateFlow()
    }
}

data class Clip(val id: String, val title: String, val videoId: String, val publishAt: Long,
                val pageId: String, val pageName: String, val channelId: String,
                val campaignName: String, val campaignUrl: String?, val status: String) {
    val watchUrl: String get() = "https://www.youtube.com/shorts/$videoId"
    fun json(): JSONObject = JSONObject().put("id", id).put("title", title).put("videoId", videoId)
        .put("publishAt", publishAt).put("pageId", pageId).put("pageName", pageName).put("channelId", channelId)
        .put("campaignName", campaignName).put("campaignUrl", campaignUrl ?: JSONObject.NULL).put("status", status)
    companion object {
        fun fromJson(j: JSONObject) = Clip(j.getString("id"), j.getString("title"), j.optString("videoId"), j.getLong("publishAt"),
            j.getString("pageId"), j.getString("pageName"), j.optString("channelId"), j.getString("campaignName"),
            j.optString("campaignUrl").takeIf { it.isNotBlank() && it != "null" }, j.getString("status"))
    }
}

object Pairing {
    fun parse(text: String): JSONObject {
        require(text.length < 12_000) { "That pairing code is too large." }
        val value = if (text.startsWith("whopclipper://pair?data=")) {
            String(Base64.decode(text.substringAfter("data="), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING), Charsets.UTF_8)
        } else text.trim()
        val j = JSONObject(value)
        require(j.getInt("version") == 1) { "This pairing code needs a newer app." }
        require(j.getString("projectId").matches(Regex("[a-z][a-z0-9-]{4,62}"))) { "Invalid Firebase project." }
        require(j.getString("pairingId").matches(Regex("[a-f0-9]{64}"))) { "Invalid pairing ID." }
        require(j.getString("joinSecret").matches(Regex("[a-f0-9]{64}"))) { "Invalid pairing secret." }
        require(Base64.decode(j.getString("encryptionKey"), Base64.NO_WRAP).size == 32) { "Invalid encryption key." }
        require(j.getString("appId").startsWith("1:") && j.getString("apiKey").startsWith("AIza")) { "Invalid Firebase Android settings." }
        require(j.getString("senderId").matches(Regex("[0-9]+"))) { "Invalid Firebase sender." }
        return j
    }
}
