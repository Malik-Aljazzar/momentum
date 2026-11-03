package com.momentum.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** A single chat photo keeps its shape (report #68); the same cases as web/tests/unit/chatphoto.test.ts. */
class ChatPhotoTest {
    @Test
    fun longSideIs240() {
        assertEquals(180 to 240, photoSize(1080, 1440))       // portrait 3:4
        assertEquals(240 to 135, photoSize(1600, 900))        // landscape 16:9
        assertEquals(240 to 240, photoSize(500, 500))
    }

    @Test
    fun veryLongOrWideOnesAreCutSquareWithoutASize() {
        assertEquals(120 to 240, photoSize(400, 4000))
        assertEquals(240 to 120, photoSize(4000, 400))
        assertEquals(240 to 240, photoSize(0, 0))
    }
}
