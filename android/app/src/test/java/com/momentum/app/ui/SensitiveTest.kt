package com.momentum.app.ui

import com.momentum.app.data.MediaItem
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Flagged media (server/moderation.py) stays covered until tapped; the same cases as web/tests/unit/moderation.test.ts. */
class SensitiveTest {
    private fun media(id: Int = 1, sensitive: Boolean = false, cover: String? = null) =
        MediaItem(id = id, kind = "image", url = "/media/1/a.webp", thumb = "/media/1/a_thumb.webp", sensitive = sensitive, cover = cover)

    @Test
    fun coversOnlyFlaggedMediaThatHasACover() {
        Sensitive.revealed = emptySet()
        assertFalse(Sensitive.covered(media()))
        assertFalse(Sensitive.covered(media(sensitive = true)))                 // nothing to show instead
        assertTrue(Sensitive.covered(media(sensitive = true, cover = "/media/1/a_cover.webp")))
    }

    @Test
    fun aTapOpensItEverywhereForThisRunOnlyThatOne() {
        Sensitive.revealed = emptySet()
        val a = media(7, true, "/c7.webp")
        val b = media(8, true, "/c8.webp")
        Sensitive.reveal(a)
        assertFalse(Sensitive.covered(a))
        assertFalse(Sensitive.covered(a.copy()))                                // the same media in another view
        assertTrue(Sensitive.covered(b))
    }
}
