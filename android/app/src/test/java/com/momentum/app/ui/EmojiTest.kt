package com.momentum.app.ui

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pixel emojis: the picker's helpers; the same cases as web/tests/unit/emoji.test.ts. */
class EmojiTest {
    private val groups = listOf(
        EmojiGroup("Smileys & Emotion", listOf(EmojiEntry("😀", "grinning face"), EmojiEntry("🔥", "fire"))),
        EmojiGroup("People & Body", listOf(EmojiEntry("👍", "thumbs up", listOf("👍🏻", "👍🏿")))),
    )

    @Test
    fun emojiIconOrSpriteName() {
        assertFalse(Emoji.isEmojiIcon("star"))
        assertTrue(Emoji.isEmojiIcon("🔥"))
        assertTrue(Emoji.isEmojiIcon("👨‍👩‍👧"))
    }

    @Test
    fun insertAtTheCaret() {
        assertEquals(TextFieldValue("a🔥b", TextRange(3)), Emoji.insert(TextFieldValue("ab", TextRange(1)), "🔥"))
        assertEquals(TextFieldValue("a😀d", TextRange(3)), Emoji.insert(TextFieldValue("abcd", TextRange(1, 3)), "😀"))
        assertEquals(TextFieldValue("ab😀", TextRange(4)), Emoji.insert(TextFieldValue("ab", TextRange(2)), "😀"))
    }

    @Test
    fun deleteTakesAWholeEmojiBack() {
        fun del(text: String, caret: Int = text.length) = Emoji.deleteBefore(TextFieldValue(text, TextRange(caret))).let { it.text to it.selection.start }
        assertEquals("a" to 1, del("ab"))
        assertEquals("hi " to 3, del("hi 🔥"))                          // a surrogate pair
        assertEquals("x" to 1, del("x❤️"))                              // with a variation selector
        assertEquals("x" to 1, del("x👍🏽"))                             // a skin tone
        assertEquals("x" to 1, del("x👩🏽‍💻"))                          // a ZWJ sequence with a skin tone
        assertEquals("x" to 1, del("x👨‍👩‍👧"))                          // a family
        assertEquals("x" to 1, del("x🏳️‍🌈"))                           // a flag with ZWJ
        assertEquals("🇩🇪" to 4, del("🇩🇪🇫🇷"))                          // flags: pairs of regional indicators
        assertEquals("x" to 1, del("x1️⃣"))                             // a keycap
        assertEquals("a😀b" to 3, del("a😀🔥b", 5))                      // at the caret, not at the end
        assertEquals("" to 0, del(""))
        assertEquals(TextFieldValue("ad", TextRange(1)), Emoji.deleteBefore(TextFieldValue("abcd", TextRange(1, 3))))   // a selection
    }

    @Test
    fun previewShowsTheEnd() {
        assertEquals("kurz", Emoji.tail("kurz"))
        assertEquals("…🔥🔥", Emoji.tail("abc🔥🔥", 2))
    }

    @Test
    fun searchByEnglishName() {
        assertEquals(listOf("🔥"), Emoji.search(groups, "fire").map { it.e })
        assertEquals(listOf("😀"), Emoji.search(groups, "FACE").map { it.e })
        assertTrue(Emoji.search(groups, "").isEmpty())
    }

    @Test
    fun everyOfferedGroupHasATabIcon() {
        groups.forEach { assertTrue(it.name, Emoji.GROUP_ICONS.containsKey(it.name)) }
    }
}
