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
