package com.momentum.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Adaptive video streams (server/hls.py); = web/tests/unit/stream.test.ts. */
class VideoStreamsTest {
    @Test
    fun theStreamUntilOneFails() {
        VideoStreams.reset()
        val url = "https://x/media/1/a.mp4"
        val hls = "https://x/media/1/a_hls1/master.m3u8"
        assertEquals(hls, VideoStreams.source(url, hls))
        assertEquals("https://x/media/1/b.mp4", VideoStreams.source("https://x/media/1/b.mp4", null))     // no stream yet
        VideoStreams.failed()
        assertEquals(url, VideoStreams.source(url, hls))
        // every video plays its file now (one failed try, not one per video)
        assertEquals("https://x/media/1/c.mp4", VideoStreams.source("https://x/media/1/c.mp4", "https://x/media/1/c_hls1/master.m3u8"))
        VideoStreams.reset()
    }

    @Test
    fun aVideoThatDoesNotPlayLoadsOnlyItsStart() {
        val ms = VideoStreams.PREFETCH_MS * 1000
        assertFalse(VideoStreams.prefetched(playWhenReady = false, bufferedUs = ms - 1))
        assertTrue(VideoStreams.prefetched(playWhenReady = false, bufferedUs = ms))
        assertFalse(VideoStreams.prefetched(playWhenReady = true, bufferedUs = 60_000_000))                // playing: on
    }
}
