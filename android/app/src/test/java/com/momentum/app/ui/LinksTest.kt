package com.momentum.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** Links in user text are clickable (report #58); the same cases as web/tests/unit/links.test.ts. */
class LinksTest {
    @Test
    fun linksWithoutTrailingPunctuation() {
        assertEquals(
            listOf(TextPart.Plain("look: "), TextPart.Link("https://example.com/a?b=1", "https://example.com/a?b=1"), TextPart.Plain(". ok")),
            textParts("look: https://example.com/a?b=1. ok"),
        )
        assertEquals(
            listOf(TextPart.Plain("("), TextPart.Link("https://www.momentum.de", "www.momentum.de"), TextPart.Plain(")")),
            textParts("(www.momentum.de)"),
        )
    }

    @Test
    fun mentionsNextToLinks() {
        assertEquals(
            listOf(TextPart.Mention("max_1", "@max_1"), TextPart.Plain(" see "), TextPart.Link("http://x.io/@foo", "http://x.io/@foo")),
            textParts("@max_1 see http://x.io/@foo"),
        )
    }

    @Test
    fun plainText() {
        assertEquals(listOf(TextPart.Plain("no link here, www. alone")), textParts("no link here, www. alone"))
    }
}
