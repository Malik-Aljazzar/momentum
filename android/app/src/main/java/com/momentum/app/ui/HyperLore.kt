package com.momentum.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** Tapping a shining hyperspace word opens the lore screen (shown above everything by App.kt). */
object HyperLore {
    /** HYPERSPACE or ULTRA_HYPERSPACE while the lore screen is open */
    var open by mutableStateOf<String?>(null)
    /** the viewer reached hyperspace: only then does "Ultra-Hyperspace" shine golden */
    var golden by mutableStateOf(false)
}

private class LoreStar(val x: Float, val y: Float, val size: Int, val speed: Float, val seed: Float, val color: Int)

/**
 * The hyperspace lore: a mystical star sky, a slogan in the middle and the hyperspace background
 * below it - still and locked until it's unlocked, animated afterwards. Tap anywhere to close.
 * The golden ultra version shows its (secret) background only once it's unlocked.
 */
@Composable
fun HyperLoreOverlay(key: String, unlocked: Boolean, onClose: () -> Unit) {
    val ultra = key == ULTRA_HYPERSPACE
    val colors = if (ultra) GOLD_COLORS else HYPER_COLORS
    val S = LocalStrings.current
    BackHandler { onClose() }
    val clock = rememberClock()
    val fade = remember { Animatable(0f) }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    LaunchedEffect(Unit) {
        // the keyboard goes away (tapped in a text being typed, e.g. a comment)
        keyboard?.hide()
        focus.clearFocus()
        fade.animateTo(1f, tween(900))
    }
    val stars = remember {
        val r = Random(7)
        List(170) { LoreStar(r.nextFloat(), r.nextFloat(), if (r.nextFloat() < .12f) 2 else 1, .4f + r.nextFloat(), r.nextFloat() * 6.28f, r.nextInt(5)) }
    }
    Box(
        Modifier.fillMaxSize().graphicsLayer { alpha = fade.value }.background(Color(0xFF02030A))
            .clickable(remember { MutableInteractionSource() }, indication = null) { onClose() },
    ) {
        Canvas(Modifier.fillMaxSize()) { loreSky(clock.value, stars, colors) }
        Column(
            Modifier.fillMaxSize().padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            val t = clock.value
            HyperText(S.bannerName(key).uppercase(), style = T.block.copy(fontSize = if (ultra) 24.sp else 30.sp), textAlign = TextAlign.Center)
            Spacer(Modifier.height(28.dp))
            // the slogan appears letter by letter and shimmers in the hyperspace colors
            val text = if (ultra) S.ultraLore else S.hyperLore
            val shown = ((t - .5f) / 2.4f * text.length).toInt().coerceIn(0, text.length)
            val shift = t * 240f
            Text(
                // the rest is already laid out (invisible), so the lines don't jump while it types
                androidx.compose.ui.text.buildAnnotatedString {
                    append(text.take(shown))
                    pushStyle(androidx.compose.ui.text.SpanStyle(brush = androidx.compose.ui.graphics.SolidColor(Color.Transparent)))
                    append(text.drop(shown))
                    pop()
                },
                style = T.title.copy(
                    fontSize = 22.sp, lineHeight = 30.sp, textAlign = TextAlign.Center,
                    brush = Brush.linearGradient(listOf(Color.White, colors[0], Color.White, colors[2], Color.White),
                        start = Offset(shift - 500f, 0f), end = Offset(shift, 120f), tileMode = TileMode.Mirror),
                ),
                modifier = Modifier.fillMaxWidth(),            // no fixed height: big fonts need 4-5 lines
            )
            val glow = (sin(t * 1.6f) * .5f + .5f)
            if (!ultra || unlocked) Spacer(Modifier.height(36.dp))
            if (!ultra || unlocked) Box(
                Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(16.dp))
                    .border(2.dp, Brush.linearGradient(colors.map { it.copy(alpha = .5f + glow * .5f) }), RoundedCornerShape(16.dp)),
            ) {
                BannerBackground(key, Modifier.matchParentSize(), still = !unlocked)
                if (!unlocked) Box(Modifier.matchParentSize().background(Color(0xB3000000)), contentAlignment = Alignment.Center) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PixelIcon("ui_lock", 18.dp)
                        Text("  " + S.hyperspaceLocked, style = T.small.copy(color = Px.Text))
                    }
                }
            }
        }
    }
}

/** Nebula clouds drifting in the hyperspace colors, twinkling pixel stars, now and then a shooting star. */
private fun DrawScope.loreSky(t: Float, stars: List<LoreStar>, colors: List<Color>) {
    val w = size.width
    val h = size.height
    val dp = density
    // nebulae: big soft glows that breathe and wander slowly
    listOf(
        Triple(colors[2], Offset(.25f, .3f), .55f),
        Triple(colors[1], Offset(.8f, .55f), .6f),
        Triple(colors[0], Offset(.4f, .82f), .45f),
    ).forEachIndexed { i, (c, at, r) ->
        val breathe = sin(t * .5f + i * 2.1f) * .5f + .5f
        val center = Offset((at.x + sin(t * .07f + i) * .06f) * w, (at.y + cos(t * .05f + i * 1.7f) * .05f) * h)
        drawRect(Brush.radialGradient(listOf(c.copy(alpha = .16f + breathe * .14f), Color.Transparent), center, r * w * (1f + breathe * .15f)))
    }
    val palette = listOf(Color.White, Color.White, colors[0], colors[1], colors[2])
    val px = 2f * dp
    stars.forEach { s ->
        val a = (sin(t * s.speed * 1.8f + s.seed) * .5f + .5f).let { .15f + .85f * it * it }
        // a slow pull towards the center: the stars drift inwards, like looking into hyperspace
        val k = (t * .012f * s.speed + s.seed) % 1f
        val x = w / 2 + (s.x - .5f) * w * (1.25f - k * .25f)
        val y = h / 2 + (s.y - .5f) * h * (1.25f - k * .25f)
        val c = palette[s.color].copy(alpha = a)
        val d = px * s.size
        drawRect(c, Offset(x, y), Size(d, d))
        if (s.size == 2 && a > .7f) {        // the bright ones get a pixel cross
            val arm = c.copy(alpha = (a - .6f) * 1.6f)
            drawRect(arm, Offset(x - d, y + d / 4), Size(d * 3, d / 2))
            drawRect(arm, Offset(x + d / 4, y - d), Size(d / 2, d * 3))
        }
    }
    // a shooting star every 4.5 s
    val p = phase(t, 4500)
    if (p < .25f) {
        val f = p / .25f
        val n = (t / 4.5f).toInt()
        val sx = w * (.2f + (n * .37f % 1f) * .7f) - f * w * .5f
        val sy = h * (.08f + (n * .53f % 1f) * .3f) + f * h * .18f
        val a = sin(f * PI).toFloat()
        drawLine(Brush.linearGradient(listOf(Color.White.copy(alpha = a), colors[0].copy(alpha = a * .5f), Color.Transparent),
            Offset(sx, sy), Offset(sx + 90 * dp, sy - 32 * dp)), Offset(sx, sy), Offset(sx + 90 * dp, sy - 32 * dp), strokeWidth = 2 * dp)
    }
}
