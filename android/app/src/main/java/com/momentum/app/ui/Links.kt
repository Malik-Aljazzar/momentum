package com.momentum.app.ui

// User text split into plain text, web links and @mentions (= web/src/links.ts, report #58).

sealed interface TextPart {
    val text: String
    data class Plain(override val text: String) : TextPart
    data class Link(val url: String, override val text: String) : TextPart
    data class Mention(val user: String, override val text: String) : TextPart
}

private val LINK = Regex("(?:https?://|www\\.)[^\\s<>\"]+", RegexOption.IGNORE_CASE)
private val MENTION = Regex("@([A-Za-z0-9_.\\-]{3,40})")
/** Punctuation right after a link belongs to the sentence ("see example.com."). */
private val TRAILING = Regex("[.,;:!?'\")\\]}]+$")
private val DOMAIN = Regex("[A-Za-z0-9]\\.[A-Za-z]{2,}")
private val SCHEME = Regex("^https?://", RegexOption.IGNORE_CASE)

fun textParts(text: String): List<TextPart> {
    val out = mutableListOf<TextPart>()
    fun plain(t: String) {
        var last = 0
        MENTION.findAll(t).forEach { m ->
            if (m.range.first > last) out += TextPart.Plain(t.substring(last, m.range.first))
            out += TextPart.Mention(m.groupValues[1], m.value)
            last = m.range.last + 1
        }
        if (last < t.length) out += TextPart.Plain(t.substring(last))
    }
    var last = 0
    LINK.findAll(text).forEach { m ->
        val link = m.value.replace(TRAILING, "")
        if (!DOMAIN.containsMatchIn(link)) return@forEach   // "www." or "http://x" alone is no link
        plain(text.substring(last, m.range.first))
        out += TextPart.Link(if (SCHEME.containsMatchIn(link)) link else "https://$link", link)
        last = m.range.first + link.length
    }
    plain(text.substring(last))
    return out
}
