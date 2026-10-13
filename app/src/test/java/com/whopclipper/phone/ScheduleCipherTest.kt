package com.whopclipper.phone

import org.junit.Assert.assertEquals
import org.junit.Test
import javax.crypto.AEADBadTagException

class ScheduleCipherTest {
    private val key = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8="
    private val nonce = "AAECAwQFBgcICQoL"
    private val ciphertext = "PCCgfreWq3TjY626ncsbAeqm9BbKIAIBdwxvOiD2kvpzldxiRODRTA=="
    @Test fun readsSharedCrossPlatformVector() {
        assertEquals("{\"version\":1,\"clips\":[]}", String(ScheduleCipher.decrypt(ciphertext, nonce, key, "pairing", "revision"), Charsets.UTF_8))
    }
    @Test(expected = AEADBadTagException::class) fun rejectsDifferentPairing() {
        ScheduleCipher.decrypt(ciphertext, nonce, key, "wrong-pairing", "revision")
    }
    @Test(expected = AEADBadTagException::class) fun rejectsDifferentRevision() {
        ScheduleCipher.decrypt(ciphertext, nonce, key, "pairing", "wrong-revision")
    }
    @Test(expected = AEADBadTagException::class) fun rejectsChangedCiphertext() {
        ScheduleCipher.decrypt("A" + ciphertext.substring(1), nonce, key, "pairing", "revision")
    }
}
