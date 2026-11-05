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

    // link previews in chats (like WhatsApp): the same cases as web/tests/unit/links.test.ts
    @Test
    fun previewsTheFirstLink() {
        assertEquals(listOf("https://www.x.de/a", "https://y.io/b?c=1"), linksIn("a www.x.de/a. b https://y.io/b?c=1, c"))
        assertEquals("https://www.x.de", firstLink("hi www.x.de, and https://y.io"))
        assertEquals(null, firstLink("no link, www. alone"))
    }

    @Test
    fun hostFromTheLinkItself() {
        assertEquals("youtube.com", linkHost("https://www.youtube.com/watch?v=1"))
        assertEquals("de.wikipedia.org", linkHost("https://de.wikipedia.org/wiki/X"))
        assertEquals("", linkHost("nonsense"))
    }

    @Test
    fun widePicturesOverTheText() {
        assertEquals(LinkLayout.Big, linkLayout(true, 480, 252))
        assertEquals(LinkLayout.Small, linkLayout(true, 480, 480))
        assertEquals(LinkLayout.Small, linkLayout(true, 233, 135))
        assertEquals(LinkLayout.None, linkLayout(false, 480, 252))
    }

    @Test
    fun previewOnlyForALinkInTheText() {
        val link = com.momentum.app.data.LinkPreview(url = "https://x.de/a", title = "X")
        fun msg(text: String, sealed: Boolean = false, l: com.momentum.app.data.LinkPreview? = link) =
            com.momentum.app.data.ChatMessage(id = 1, text = text, at = "", sealed = sealed, link = l)
        assertEquals(link, shownLink(msg("see https://x.de/a!")))
        assertEquals(null, shownLink(msg("see https://evil.example/")))      // the sender can't dress up another link
        assertEquals(null, shownLink(msg("", sealed = true)))
        assertEquals(null, shownLink(msg("see https://x.de/a", l = null)))
    }

    @Test
    fun encryptedBodyCarriesThePreview() {
        // what the PWA seals (web/src/e2e.ts sealMessage) opens here; an older body without "l" too
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; explicitNulls = false }
        val body = json.decodeFromString(com.momentum.app.data.E2EBody.serializer(),
            """{"t":"look https://x.de/a","m":null,"l":{"url":"https://x.de/a","title":"X","image":"AAAA","width":480,"height":252}}""")
        assertEquals(com.momentum.app.data.LinkPreview("https://x.de/a", title = "X", image = "AAAA", width = 480, height = 252), body.l)
        assertEquals(null, json.decodeFromString(com.momentum.app.data.E2EBody.serializer(), """{"t":"hi","m":null}""").l)
    }
}
