package com.momentum.app.ui

import com.momentum.app.data.ChatMessage
import com.momentum.app.data.MediaItem
import org.junit.Assert.assertEquals
import org.junit.Test

/** Chat photos and videos: one square (like WhatsApp), a video's length, previews; the same cases as
 *  web/tests/unit/chatphoto.test.ts. */
class ChatPhotoTest {
    @Test
    fun singlePhotosAndVideosAreSquare() {
        assertEquals(240, CHAT_MEDIA_DP)
    }

    @Test
    fun aVideosLengthOnItsBubble() {
        assertEquals("", videoLength(0.0))
        assertEquals("0:07", videoLength(7.2))
        assertEquals("1:00", videoLength(59.6))
        assertEquals("10:05", videoLength(605.0))
    }

    private fun msg(id: Int, kind: String, archived: Boolean = false, expires: String? = null) =
        ChatMessage(id = id, at = "2026-11-05T10:00:00Z", mine = true,
            media = MediaItem(id = id, kind = kind, url = "/m/$id", thumb = "/t/$id", archived = archived, expiresAt = expires))

    @Test
    fun videosNeverJoinACollage() {
        val groups = photoGroups(listOf(msg(1, "image"), msg(2, "image"), msg(3, "video"), msg(4, "image"), msg(5, "video"), msg(6, "video")))
        assertEquals(listOf(2, 3, 4, 5, 6), groups.map { it.first.id })
        assertEquals(listOf(2, 0, 1, 0, 0), groups.map { it.second.size })
    }

    @Test
    fun previewsSayWhatItIs() {
        assertEquals("▶ Video", previewOf(En, "", "video", null))
        assertEquals("▣ Photo", previewOf(En, "", "image", null))
        assertEquals("♪ Voice message", previewOf(En, "", "audio", null))
        assertEquals("look", previewOf(En, "look", "video", null))
    }

    @Test
    fun anArchivedVideoKeepsOnlyItsStill() {
        assertEquals(En.videoArchived, photoExpiry(En, listOf(msg(1, "video", archived = true))))
        assertEquals(En.photoArchived, photoExpiry(En, listOf(msg(1, "image", archived = true))))
        assertEquals("", photoExpiry(En, listOf(msg(1, "video"))))          // no date: nothing to count down
    }
}
