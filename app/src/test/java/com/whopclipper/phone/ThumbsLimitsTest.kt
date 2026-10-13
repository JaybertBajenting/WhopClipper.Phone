package com.whopclipper.phone

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

class ThumbsLimitsTest {
    @Test fun acceptsAnImageExactlyAtTheLimit() {
        val bytes = ByteArray(600_000) { (it % 251).toByte() }
        assertArrayEquals(bytes, readLimitedImage(ByteArrayInputStream(bytes), 600_000))
    }

    @Test fun rejectsAnUnboundedResponseWithoutReadingItAll() {
        var consumed = 0
        val endless = object : InputStream() {
            override fun read(): Int { consumed++; return 42 }
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                bytes.fill(42, offset, offset + length)
                consumed += length
                return length
            }
        }
        assertNull(readLimitedImage(endless, 600_000))
        assertEquals(600_001, consumed)
    }

    @Test fun handlesShortNetworkReadsWithoutDroppingBytes() {
        val bytes = byteArrayOf(1, 2, 3, 4, 5)
        val partial = object : ByteArrayInputStream(bytes) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = super.read(buffer, offset, minOf(2, length))
        }
        assertArrayEquals(bytes, readLimitedImage(partial, 20))
    }

    @Test fun rejectsEmptyResponse() {
        assertNull(readLimitedImage(ByteArrayInputStream(byteArrayOf()), 20))
    }

    @Test fun recoversFromAZeroLengthReadWithoutLoopingForever() {
        val input = object : InputStream() {
            var next = 0
            override fun read(): Int = if (next++ == 0) 12 else -1
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = 0
        }
        assertArrayEquals(byteArrayOf(12), readLimitedImage(input, 20))
    }
}
