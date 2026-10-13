package com.whopclipper.phone

import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object ScheduleCipher {
    fun decrypt(ciphertext: String, nonce: String, key: String, pairingId: String, revision: String): ByteArray {
        val decode = Base64.getDecoder()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(decode.decode(key), "AES"), GCMParameterSpec(128, decode.decode(nonce)))
        cipher.updateAAD("whopclipper-phone-v1:$pairingId:$revision".toByteArray(Charsets.UTF_8))
        return cipher.doFinal(decode.decode(ciphertext))
    }
}
