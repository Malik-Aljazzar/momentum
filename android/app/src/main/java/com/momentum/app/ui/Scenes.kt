package com.momentum.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import kotlin.math.max
import kotlin.math.roundToInt
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.floor
import kotlin.random.Random

/** Background scenes for cards: stepped color bands + dither + animated pixel layers. */
enum class Scene { Land, Air, Space, Acc, Dec, Settings }

fun sceneFor(category: String) = when (category) {
    "air" -> Scene.Air
    "space" -> Scene.Space
    else -> Scene.Land
}

private fun bands(scene: Scene): List<Pair<Float, Color>> = when (scene) {
    Scene.Land -> listOf(0f to Color(0xFF232A4F), .22f to Color(0xFF2E2B55), .36f to Color(0xFF48295A),
        .48f to Color(0xFF6F2F57), .58f to Color(0xFF96435A), .66f to Color(0xFFB86455))
    Scene.Air -> listOf(0f to Color(0xFF22305E), .20f to Color(0xFF2B4282), .38f to Color(0xFF3459A0),
        .56f to Color(0xFF3F73B8), .72f to Color(0xFF5791C8))
    Scene.Space -> listOf(0f to Color(0xFF07080F), .30f to Color(0xFF0D0F1D), .55f to Color(0xFF141832),
        .75f to Color(0xFF1C2246))
    Scene.Acc -> listOf(0f to Color(0xFF14232A), .30f to Color(0xFF173634), .55f to Color(0xFF1D4A3C),
        .78f to Color(0xFF275D43))
    Scene.Dec -> listOf(0f to Color(0xFF1C111C), .30f to Color(0xFF2C1424), .55f to Color(0xFF40182C),
        .78f to Color(0xFF561D33))
    Scene.Settings -> listOf(0f to Color(0xFF12141D), .35f to Color(0xFF1A1F2E), .65f to Color(0xFF232A3D))
}

/** Seconds since the scene appeared; one frame clock drives all layers of a scene. */
@Composable
internal fun rememberClock(): State<Float> {
    val t = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        while (true) withFrameNanos { t.floatValue = (it - start) / 1e9f }
    }
    return t
}

/** 0..1 progress of a loop with the given period. */
internal fun phase(seconds: Float, periodMs: Int) = (seconds * 1000f / periodMs) % 1f

internal fun DrawScope.blit(img: ImageBitmap, x: Float, y: Float, scale: Int, alpha: Float = 1f) =
    drawPixels(img, IntOffset.Zero, IntSize(img.width, img.height), IntOffset(x.toInt(), y.toInt()), scale, alpha)

/** Repeat an image horizontally, shifted left by phase (0..1) of one tile. */
internal fun DrawScope.tileX(img: ImageBitmap, scale: Int, y: Float, phase: Float, alpha: Float = 1f) {
    val w = img.width * scale
    var x = -floor(phase * w)
    while (x < size.width) {
        blit(img, x, y, scale, alpha)
        x += w
    }
}

/** Like tileX, but every art row is shifted sideways by [offset](row) pixels (heat shimmer). */
internal fun DrawScope.tileXWavy(img: ImageBitmap, scale: Int, y: Float, phase: Float, offset: (Int) -> Float) {
    val w = img.width * scale
    val x0 = -floor(phase * w)
    for (r in 0 until img.height) {
        val o = offset(r).roundToInt()
        var x = x0 - w
        while (x < size.width + w) {
            drawImage(
                img, srcOffset = IntOffset(0, r), srcSize = IntSize(img.width, 1),
                dstOffset = IntOffset(x.toInt() + o, y.toInt() + r * scale), dstSize = IntSize(w, scale),
                filterQuality = androidx.compose.ui.graphics.FilterQuality.None,
            )
            x += w
        }
    }
}

/** A soft round glow (an ellipse with [ry]), drawn only within its own box. */
internal fun DrawScope.glowAt(x: Float, y: Float, r: Float, color: Color, ry: Float = r) {
    if (r <= 0f || ry <= 0f) return
    withTransform({ translate(x, y); scale(1f, ry / r, Offset.Zero) }) {
        drawRect(
            Brush.radialGradient(listOf(color, Color.Transparent), Offset.Zero, r),
            Offset(-r, -r), Size(2 * r, 2 * r),
        )
    }
}

/** Relative luminance (0..1). */
internal fun luminance(c: Color) = .2126f * c.red + .7152f * c.green + .0722f * c.blue

/** Repeat an image over the whole area, shifted vertically by phase (0..1) of one tile. */
internal fun DrawScope.tileAll(img: ImageBitmap, scale: Int, phase: Float, alpha: Float) {
    val w = img.width * scale
    val h = img.height * scale
    var y = -h + phase * h
    while (y < size.height) {
        var x = -w.toFloat()
        while (x < size.width + w) {
            blit(img, x, y, scale, alpha)
            x += w
        }
        y += h
    }
}

/** Soft checkerboard dither (2dp cells) as one repeating shader instead of thousands of rects. */
internal fun ditherBrush(density: Float): ShaderBrush {
    val cell = max(1, (2 * density).roundToInt())
    val bmp = Bitmap.createBitmap(cell * 2, cell * 2, Bitmap.Config.ARGB_8888)
    val shade = android.graphics.Color.argb(0x29, 0, 0, 0)
    for (y in 0 until cell * 2) for (x in 0 until cell * 2) {
        if ((x < cell) == (y < cell)) bmp.setPixel(x, y, shade)
    }
    return ShaderBrush(ImageShader(bmp.asImageBitmap(), TileMode.Repeated, TileMode.Repeated))
}

/**
 * @param groundDp height of the road in the land scene; vehicles stand on its top edge.
 * @param speed how fast the world passes by (the vehicle's, vehicleSpeed; 1 = a car).
 */
@Composable
fun SceneBackground(scene: Scene, modifier: Modifier = Modifier, groundDp: Float = 120f, sun: Boolean = true, speed: Float = 1f) {
    // device pixels per art pixel for the different layer sizes (~2x, 3x, 4x in dp)
    val s2 = pixelScale(2f)
    val s3 = pixelScale(3f)
    val s4 = pixelScale(4f)
    val far = rememberAsset("icons/scene_mountains_far.png")
    val near = rememberAsset("icons/scene_mountains_near.png")
    val cloud = rememberAsset("icons/scene_cloud.png")
    val sunImg = rememberAsset("icons/scene_sun.png")
    val planet = rememberAsset("icons/scene_planet.png")
    val rain = rememberAsset("icons/scene_rain.png")
    val up = rememberAsset("icons/ui_up.png")
    val stars = remember { List(70) { Triple(Random.nextFloat(), Random.nextFloat(), Random.nextFloat()) } }

    val clock = rememberClock()

    Box(modifier) {
        // static layers are recorded once; only the middle layer is redrawn every frame
        Spacer(Modifier.matchParentSize().drawWithCache {
            val b = bands(scene)
            onDrawBehind {
                b.forEachIndexed { i, (start, color) ->
                    val end = if (i + 1 < b.size) b[i + 1].first else 1f
                    drawRect(color, Offset(0f, start * size.height), Size(size.width, (end - start) * size.height + 1))
                }
            }
        })
        Canvas(Modifier.matchParentSize().graphicsLayer()) {
            val t = clock.value
            val dp = density
            when (scene) {
                Scene.Land -> {
                    val ground = size.height - groundDp * dp
                    // sun sits above the mountains, independent of how tall the card is
                    if (sun) blit(sunImg, size.width * .64f, maxOf(size.height * .30f, ground - 150 * dp).coerceAtMost(ground - 70 * dp), s4, .85f)
                    tileX(far, s4, ground - far.height * s4, phase(t, (40_000 / speed).toInt()), .8f)
                    tileX(near, s4, ground - near.height * s4, phase(t, (16_000 / speed).toInt()))
                    drawRect(Color(0xFF2A2F45), Offset(0f, ground), Size(size.width, 6 * dp))
                    drawRect(Color(0xFF1A1C2C), Offset(0f, ground + 6 * dp), Size(size.width, size.height))
                    val dash = 32 * dp
                    var x = -phase(t, (450 / speed).toInt()) * dash
                    while (x < size.width) {
                        drawRect(Color(0x8CFFCD75), Offset(x, ground + 30 * dp), Size(dash / 2, 4 * dp))
                        x += dash
                    }
                }
                Scene.Air -> {
                    tileX(cloud, s3, size.height * .26f, phase(t, (9_000 / speed).toInt()), .8f)
                    tileX(cloud, s2, size.height * .62f, phase(t, (5_000 / speed).toInt()), .55f)
                }
                Scene.Space, Scene.Settings -> {
                    val slow = phase(t, (40_000 / speed).toInt())
                    val fast = phase(t, (9_000 / speed).toInt())
                    stars.forEach { (fx, fy, depth) ->
                        val x = ((fx - if (depth > .6f) fast else slow) % 1f + 1f) % 1f
                        val dot = if (depth > .85f) 2 * dp else dp * 1.2f
                        drawRect(Px.Text.copy(alpha = .3f + depth * .6f), Offset(x * size.width, fy * size.height), Size(dot, dot))
                    }
                    if (scene == Scene.Space && sun) {     // `sun` = sky decoration (sun or planet)
                        blit(planet, size.width - planet.width * s3 - 22 * dp, minOf(size.height * .30f, 120 * dp), s3, .75f)
                    }
                }
                Scene.Acc -> tileAll(up, s2, 1f - phase(t, 3_000), .07f)
                Scene.Dec -> tileAll(rain, s3, phase(t, 900), .45f)
            }
        }
        Spacer(Modifier.matchParentSize().drawWithCache {
            val brush = ditherBrush(density)
            onDrawBehind { drawRect(brush) }
        })
    }
}
