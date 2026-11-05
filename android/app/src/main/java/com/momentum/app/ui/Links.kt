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

/** The web links of a text, as they open (server/linkpreview.py links_in). */
fun linksIn(text: String): List<String> = textParts(text).mapNotNull { (it as? TextPart.Link)?.url }

/** The first web link of a text: its preview goes with the message (like WhatsApp). */
fun firstLink(text: String): String? = linksIn(text).firstOrNull()

/** The host a link preview names - always taken from the link itself, never from the sender: "www.x.de/a" -> "x.de". */
fun linkHost(url: String): String =
    runCatching { java.net.URI(url).host?.removePrefix("www.") }.getOrNull().orEmpty()

enum class LinkLayout { Big, Small, None }

/** A link preview's picture: wide ones over the text, others small beside it, none without one. */
fun linkLayout(hasImage: Boolean, width: Int, height: Int): LinkLayout = when {
    !hasImage -> LinkLayout.None
    width >= 300 && height > 0 && width >= 1.3 * height -> LinkLayout.Big
    else -> LinkLayout.Small
}

/** The preview a message shows (sent along by the sender): only for a link that is in its text, once opened. */
fun shownLink(m: com.momentum.app.data.ChatMessage): com.momentum.app.data.LinkPreview? =
    m.link?.takeIf { !m.sealed && it.url in linksIn(m.text) }
