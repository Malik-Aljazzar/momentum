package com.momentum.app.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** An encrypted chat's video that can't be shrunk on the phone (= web/src/chatvideo.ts). */
class ChatVideoTest {
    @Test
    fun aVideoGoesAsItIsOnlyWhenItsShortAndSmallEnough() {
        assertTrue(sendsAsItIs(5_000_000, 12.0))
        assertTrue(sendsAsItIs(5_000_000, 60.4))
        assertTrue(sendsAsItIs(5_000_000, 0.0))                 // length unknown: by its size
        assertFalse(sendsAsItIs(5_000_000, 61.0))
        assertFalse(sendsAsItIs(SECURE_VIDEO_MAX_BYTES + 1, 10.0))
        assertFalse(sendsAsItIs(0, 10.0))
        assertEquals(100L * 1024 * 1024, SECURE_VIDEO_MAX_BYTES)     // server e2e.MAX_VIDEO
    }

    @Test
    fun aBigFileIsntReadPastTheLimit() {
        val data = ByteArray(200_000) { it.toByte() }
        assertArrayEquals(data, readCapped(data.inputStream(), 200_000))
        assertNull(readCapped(data.inputStream(), 199_999))
    }
}
