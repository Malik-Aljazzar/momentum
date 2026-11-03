package com.momentum.app.ui

import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Pixel emojis: every emoji is drawn by the generated "Pixel Emoji" font (pixelart/emoji.py), the fallback of
 * the app's fonts (Theme.kt), so texts, inputs and rule icons show them in pixel art. Here: the picker's data
 * (assets/sprites/emoji/emoji.json = the groups of Unicode's emoji-test.txt with English names and skin-tone
 * variants) and the picker (web/src/ui/emoji.tsx).
 */
@Serializable
data class EmojiEntry(val e: String, val n: String, val v: List<String> = emptyList())

@Serializable
data class EmojiGroup(val name: String, val emoji: List<EmojiEntry>)

@Serializable
private data class EmojiFile(val groups: List<EmojiGroup>)

object Emoji {
    /** The groups' tab icons (the groups of emoji-test.txt; "Component" = skin tones / hair, not offered). */
    val GROUP_ICONS = linkedMapOf(
        "Smileys & Emotion" to "😀", "People & Body" to "👋", "Animals & Nature" to "🐻", "Food & Drink" to "🍔",
        "Travel & Places" to "🚗", "Activities" to "⚽", "Objects" to "💡", "Symbols" to "🔣", "Flags" to "🏁",
    )
    private const val RECENT_MAX = 24
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile private var groups: List<EmojiGroup>? = null

    fun groups(ctx: Context): List<EmojiGroup> = groups ?: synchronized(this) {
        groups ?: runCatching {
            ctx.assets.open("sprites/emoji/emoji.json").use { json.decodeFromString<EmojiFile>(it.readBytes().decodeToString()) }
                .groups.filter { it.name in GROUP_ICONS }
        }.getOrDefault(emptyList()).also { groups = it }
    }

    /** A rule icon is either the name of a sprite (ascii) or one emoji. */
    fun isEmojiIcon(icon: String) = icon.any { it.code > 0x7f }

    /** [emoji] put in for the selection of [v]; the caret goes behind it. */
    fun insert(v: TextFieldValue, emoji: String): TextFieldValue {
        val s = v.selection.min.coerceIn(0, v.text.length)
        val e = v.selection.max.coerceIn(s, v.text.length)
        return TextFieldValue(v.text.substring(0, s) + emoji + v.text.substring(e), TextRange(s + emoji.length))
    }

    /** The character before the caret taken away - a whole emoji (skin tone, ZWJ sequence, flag, keycap) at once;
     *  a selection goes as it is (the picker's delete key, report #64). */
    fun deleteBefore(v: TextFieldValue): TextFieldValue {
        val s = v.selection.min.coerceIn(0, v.text.length)
        val e = v.selection.max.coerceIn(s, v.text.length)
        if (e > s) return TextFieldValue(v.text.substring(0, s) + v.text.substring(e), TextRange(s))
        val cut = lastCharStart(v.text.substring(0, s))
        return TextFieldValue(v.text.substring(0, cut) + v.text.substring(s), TextRange(cut))
    }

    /** Where the last character of [text] starts (UTF-16 index), an emoji sequence counted as one (= emoji.ts). */
    fun lastCharStart(text: String): Int {
        val cps = text.codePoints().toArray()
        if (cps.isEmpty()) return 0
        var i = cps.lastIndex
        if (cps[i] in 0x1F1E6..0x1F1FF) {                     // flags: pairs of regional indicators
            var n = 0
            while (i - n >= 0 && cps[i - n] in 0x1F1E6..0x1F1FF) n++
            if (n % 2 == 0) i--
        } else while (true) {
            if (i > 0 && attaches(cps[i])) { i--; continue }  // skin tone, variation selector, keycap, tag, accent
            if (i >= 2 && cps[i - 1] == 0x200D) { i -= 2; continue }   // joined by a ZWJ
            break
        }
        return (0 until i).sumOf { Character.charCount(cps[it]) }
    }

    /** The end of [text] for a one-line preview: "…" and its last [n] characters (code points). */
    fun tail(text: String, n: Int = 28): String {
        val count = text.codePointCount(0, text.length)
        return if (count <= n) text else "…" + text.substring(text.offsetByCodePoints(text.length, -n))
    }

    private fun attaches(c: Int) = c == 0xFE0F || c == 0xFE0E || c == 0x20E3 || c in 0x1F3FB..0x1F3FF ||
        c in 0xE0020..0xE007F || c in 0x0300..0x036F

    /** The entries whose English name contains [query] (nothing for an empty query). */
    fun search(groups: List<EmojiGroup>, query: String): List<EmojiEntry> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        return groups.flatMap { g -> g.emoji.filter { it.n.contains(q) || it.e == q } }.take(200)
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("emoji", Context.MODE_PRIVATE)

    fun recent(ctx: Context): List<String> = prefs(ctx).getString("recent", "")!!.split('\u0001').filter { it.isNotEmpty() }

    /** Remembers a picked emoji at the front of the "recent" row. */
    fun remember(ctx: Context, e: String) {
        val list = (listOf(e) + recent(ctx).filter { it != e }).take(RECENT_MAX)
        prefs(ctx).edit().putString("recent", list.joinToString("\u0001")).apply()
    }
}

/** The emoji picker: recent row, the groups as tabs (their first emoji), a search by the English name, variants on a long press.
 *  With [text] (typing in a field) it stays open after a pick and shows what's written, with a delete key (report #64);
 *  without (a rule icon: one emoji) a pick closes it. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun EmojiPicker(onPick: (String) -> Unit, onDismiss: () -> Unit, text: String? = null, onDelete: () -> Unit = {}) {
    val S = LocalStrings.current
    val ctx = LocalContext.current
    val groups = remember { Emoji.groups(ctx) }
    val recent = remember { Emoji.recent(ctx) }
    var tab by remember { mutableStateOf(if (recent.isEmpty()) 0 else -1) }
    var query by remember { mutableStateOf("") }
    var variants by remember { mutableStateOf<EmojiEntry?>(null) }
    // the "system font" setting: the phone's own emojis here too (report #61)
    val glyphs = T.body.userText()
    EditorSheet(onDismiss) { close ->
        val pick = { e: String -> Emoji.remember(ctx, e); onPick(e); if (text == null) close() }
        val list = when {
            query.isNotBlank() -> Emoji.search(groups, query)
            tab < 0 -> recent.map { EmojiEntry(it, "") }
            else -> groups.getOrNull(tab)?.emoji.orEmpty()
        }
        Column(Modifier.fillMaxWidth().height(480.dp).imePadding().padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (text != null) Row(Modifier.clip(RoundedCornerShape(14.dp)).background(Px.Faint).padding(start = 14.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(Emoji.tail(text).ifEmpty { " " }, style = glyphs, maxLines = 1, overflow = TextOverflow.Clip,
                    modifier = Modifier.weight(1f).testTag("emoji-preview"))
                IconBtn("ui_backspace", alpha = if (text.isEmpty()) .3f else .8f) { onDelete() }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { PxTextField(query, { query = it }, placeholder = S.emojiSearch) }
                IconBtn("ui_close", alpha = .6f) { close() }
            }
            if (query.isBlank()) LazyRow(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                if (recent.isNotEmpty()) item("recent") { EmojiTab("🕐", tab < 0, glyphs) { tab = -1 } }
                items(groups.size, key = { groups[it].name }) { i -> EmojiTab(Emoji.GROUP_ICONS[groups[i].name] ?: "", tab == i, glyphs) { tab = i } }
            }
            Label(if (query.isNotBlank()) S.emojiTitle else if (tab < 0) S.emojiRecent else S.emojiGroup(groups.getOrNull(tab)?.name ?: ""))
            variants?.let { v ->
                Row(Modifier.clip(RoundedCornerShape(14.dp)).background(Px.Faint).padding(4.dp)) {
                    (listOf(v.e) + v.v).forEach { e -> EmojiCell(e, glyphs) { pick(e) } }
                }
            }
            if (list.isEmpty() && groups.isNotEmpty()) Text(S.emojiNone, style = T.small)
            LazyVerticalGrid(GridCells.Adaptive(44.dp), Modifier.weight(1f)) {
                items(list.size, key = { list[it].e }) { i ->
                    val x = list[i]
                    EmojiCell(x.e, glyphs, onLongClick = if (x.v.isNotEmpty()) ({ variants = x }) else null) { pick(x.e) }
                }
            }
        }
    }
}

@Composable
private fun EmojiTab(e: String, on: Boolean, glyphs: TextStyle, onClick: () -> Unit) {
    Box(Modifier.size(40.dp, 36.dp).clip(RoundedCornerShape(10.dp)).background(if (on) Px.Faint else androidx.compose.ui.graphics.Color.Transparent)
        .clickable(onClick = onClick).alpha(if (on) 1f else .45f), contentAlignment = Alignment.Center) {
        Text(e, style = glyphs.copy(fontSize = 22.sp, lineHeight = 36.sp), textAlign = TextAlign.Center)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EmojiCell(e: String, glyphs: TextStyle, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    Box(Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).combinedClickable(onLongClick = onLongClick, onClick = onClick),
        contentAlignment = Alignment.Center) {
        Text(e, style = glyphs.copy(fontSize = 26.sp, lineHeight = 44.sp), maxLines = 1, softWrap = false, textAlign = TextAlign.Center)
    }
}

/** The smiley next to a text input: opens the picker, which stays open while picking; [value] is the field's text
 *  and caret, [onChange] gets it with the emoji put in / the last character taken away. */
@Composable
fun EmojiButton(value: TextFieldValue, alpha: Float = .8f, onChange: (TextFieldValue) -> Unit) {
    var open by remember { mutableStateOf(false) }
    IconBtn("smile", alpha = alpha) { open = true }
    if (open) EmojiPicker(onPick = { onChange(Emoji.insert(value, it)) }, onDismiss = { open = false },
        text = value.text, onDelete = { onChange(Emoji.deleteBefore(value)) })
}
