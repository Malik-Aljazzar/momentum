package com.momentum.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** The used part of a post's / story's sound; the same cases as web/tests/unit/sound.test.ts. */
class SoundTest {
    @Test
    fun postsTake5To30SecondsInsideTheSound() {
        assertEquals(0L to 30_000L, clampRange(30_000, 0, 30_000))
        assertEquals(12_000L to 17_000L, clampRange(30_000, 12_000, 13_000))        // at least 5 s
        assertEquals(10_000L to 40_000L, clampRange(120_000, 10_000, 80_000))       // at most 30 s
        assertEquals(0L to 3_000L, clampRange(3_000, 0, 3_000))                     // shorter than 5 s: all of it
    }

    @Test
    fun storySoundIsAsLongAsTheStoryAtMost30Seconds() {
        assertEquals(15_000L, storySoundMs(false, null, 15))                        // a photo: its chosen length
        assertEquals(12_000L, storySoundMs(true, 12.0, 5))                          // a video: its length
        assertEquals(30_000L, storySoundMs(true, 55.0, 5))                          // up to 60 s (report #69): loops
        assertEquals(30_000L, storySoundMs(true, 0.0, 5))                           // unknown length
    }
}
