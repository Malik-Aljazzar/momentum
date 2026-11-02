package com.momentum.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import kotlin.math.PI
import kotlin.math.sin

/*
 * "Hyperspace" / "Hyperraum" is a special word: wherever it stands as a word of its own in the UI
 * (not inside another word, not as part of a @username), it gets an animated gradient and sparkles.
 * Tapping it opens the hyperspace lore (HyperLore.kt).
 */

private val HYPER = Regex("(?<![\\p{L}\\p{N}_@.])(hyperspace|hyperraum)(?![\\p{L}\\p{N}_])", RegexOption.IGNORE_CASE)

// "Ultra-Hyperspace": golden, but only for people who reached hyperspace themselves (others see plain text)
private val ULTRA_WORD = Regex("(?<![\\p{L}\\p{N}_@.])ultra[- ]?(hyperspace|hyperraum)(?![\\p{L}\\p{N}_])", RegexOption.IGNORE_CASE)

internal val GOLD_COLORS = listOf(Color(0xFFFFF3C4), Color(0xFFFFCD45), Color(0xFFE89A2C), Color(0xFFFFCD45), Color(0xFFFFF3C4))

internal val HYPER_COLORS = listOf(Color(0xFF73EFF7), Color(0xFF5B7CFF), Color(0xFFA77BFF), Color(0xFF5B7CFF), Color(0xFF73EFF7))   // cyan, blue, purple

fun hyperRanges(text: String): List<IntRange> = HYPER.findAll(text).map { it.range }.toList()

/** Text that makes the hyperspace word shine; everything else renders like a normal Text. */
@Composable
fun HyperText(
    text: AnnotatedString,
    style: TextStyle,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    softWrap: Boolean = true,
    overflow: TextOverflow = TextOverflow.Clip,
    textAlign: TextAlign? = null,
    skip: List<IntRange> = emptyList(),          // e.g. @mentions: usernames never shine
) {
    val golden = HyperLore.golden
    val (ranges, gold) = remember(text.text, skip, golden) {
        fun free(r: IntRange) = skip.none { it.first <= r.first && r.last <= it.last }
        val ultra = ULTRA_WORD.findAll(text.text).map { it.range }.filter(::free).toList()
        hyperRanges(text.text).filter { r -> free(r) && ultra.none { r.first in it } } to (if (golden) ultra else emptyList())
    }
    if (ranges.isEmpty() && gold.isEmpty()) {
        Text(text, style = style, modifier = modifier, maxLines = maxLines, softWrap = softWrap, overflow = overflow, textAlign = textAlign)
        return
    }
    val t by rememberInfiniteTransition(label = "hyper").animateFloat(0f, 1f, infiniteRepeatable(tween(2600, easing = LinearEasing)), label = "t")
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val shift = t * 600f
    val brush = Brush.linearGradient(HYPER_COLORS, start = Offset(shift - 300f, 0f), end = Offset(shift, 60f), tileMode = TileMode.Mirror)
    val goldBrush = Brush.linearGradient(GOLD_COLORS, start = Offset(shift - 300f, 0f), end = Offset(shift, 60f), tileMode = TileMode.Mirror)
    val styled = buildAnnotatedString {
        append(text)
        ranges.forEach {
            addStyle(SpanStyle(brush = brush), it.first, it.last + 1)
            addLink(androidx.compose.ui.text.LinkAnnotation.Clickable("hyper") { HyperLore.open = HYPERSPACE }, it.first, it.last + 1)
        }
        gold.forEach {
            addStyle(SpanStyle(brush = goldBrush), it.first, it.last + 1)
            addLink(androidx.compose.ui.text.LinkAnnotation.Clickable("ultra") { HyperLore.open = ULTRA_HYPERSPACE }, it.first, it.last + 1)
        }
    }
    Text(
        styled, style = style, maxLines = maxLines, softWrap = softWrap, overflow = overflow, textAlign = textAlign,
        onTextLayout = { layout = it },
        modifier = modifier.drawWithContent {
            drawContent()
            val l = layout ?: return@drawWithContent
            (ranges + gold).forEachIndexed { n, r ->
                val end = minOf(r.last, l.layoutInput.text.length - 1)
                if (r.first > end) return@forEachIndexed
                var box = l.getBoundingBox(r.first)
                for (i in r.first + 1..end) box = box.union(l.getBoundingBox(i))
                sparkles(box, t, n, if (n >= ranges.size) Color(0xFFFFF0B0) else Color(0xFFBFF6FF))
            }
        },
    )
}

@Composable
fun HyperText(text: String, style: TextStyle, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE,
              softWrap: Boolean = true, overflow: TextOverflow = TextOverflow.Clip, textAlign: TextAlign? = null) =
    HyperText(AnnotatedString(text), style, modifier, maxLines, softWrap, overflow, textAlign)

private fun Rect.union(o: Rect) = Rect(minOf(left, o.left), minOf(top, o.top), maxOf(right, o.right), maxOf(bottom, o.bottom))

/** Pixel stars twinkling around the word, each on its own beat. */
private fun DrawScope.sparkles(box: Rect, t: Float, seed: Int, tint: Color) {
    val px = (box.height / 9f).coerceIn(1.5f, 5f)
    val spots = listOf(-.04f to .1f, .22f to -.18f, .5f to .05f, .74f to -.2f, 1.02f to .2f, .36f to .95f, .86f to .9f)
    spots.forEachIndexed { i, (fx, fy) ->
        val phase = ((t * 2f + i * .37f + seed * .21f) % 1f)
        val a = sin(phase * PI).toFloat().let { it * it }
        if (a < .05f) return@forEachIndexed
        val cx = box.left + fx * box.width
        val cy = box.top + fy * box.height
        val c = (if (i % 3 == 0) tint else Color.White).copy(alpha = a)
        val arm = if (i % 2 == 0) 2 else 1
        // a plus-shaped pixel star
        drawRect(c, Offset(cx - px / 2, cy - px / 2), androidx.compose.ui.geometry.Size(px, px))
        for (k in 1..arm) {
            val aa = c.copy(alpha = a * (1f - k * .3f))
            drawRect(aa, Offset(cx - px / 2 + k * px, cy - px / 2), androidx.compose.ui.geometry.Size(px, px))
            drawRect(aa, Offset(cx - px / 2 - k * px, cy - px / 2), androidx.compose.ui.geometry.Size(px, px))
            drawRect(aa, Offset(cx - px / 2, cy - px / 2 + k * px), androidx.compose.ui.geometry.Size(px, px))
            drawRect(aa, Offset(cx - px / 2, cy - px / 2 - k * px), androidx.compose.ui.geometry.Size(px, px))
        }
    }
}
