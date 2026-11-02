package com.momentum.app.ui

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.math.roundToInt

/** Pixel-art bitmaps from assets/sprites (copied from pixelart/out at build time). */
object Assets {
    private val cache = HashMap<String, ImageBitmap>()

    fun load(context: Context, path: String): ImageBitmap = cache.getOrPut(path) {
        context.assets.open("sprites/$path").use { BitmapFactory.decodeStream(it).asImageBitmap() }
    }
}

@Composable
fun rememberAsset(path: String): ImageBitmap {
    val ctx = LocalContext.current
    return remember(path) { Assets.load(ctx, path) }
}

/** Integer device pixels per art pixel, so scaled pixel art stays crisp. */
@Composable
fun pixelScale(approxDpPerPixel: Float): Int {
    val density = LocalDensity.current.density
    return max(1, (approxDpPerPixel * density).roundToInt())
}

fun DrawScope.drawPixels(
    img: ImageBitmap, src: IntOffset, srcSize: IntSize, dst: IntOffset, scale: Int,
    alpha: Float = 1f, tint: Color? = null,
) = drawImage(
    img, srcOffset = src, srcSize = srcSize, dstOffset = dst,
    dstSize = IntSize(srcSize.width * scale, srcSize.height * scale),
    alpha = alpha, filterQuality = FilterQuality.None,
    colorFilter = tint?.let { ColorFilter.tint(it, BlendMode.SrcIn) },
)

private const val FRAMES = 5

/** Animated vehicle (5-frame sheet, 64x32 per frame). */
@Composable
fun VehicleSprite(
    key: String, dpPerPixel: Float, modifier: Modifier = Modifier,
    animate: Boolean = true, tint: Color? = null, alpha: Float = 1f,
) {
    val sheet = rememberAsset("$key/sheet.png")
    val s = pixelScale(dpPerPixel)
    var frame by remember { mutableIntStateOf(0) }
    if (animate) {
        LaunchedEffect(key) {
            while (true) {
                delay(100)
                frame = (frame + 1) % FRAMES
            }
        }
    }
    val density = LocalDensity.current
    val w = with(density) { (64 * s).toDp() }
    val h = with(density) { (32 * s).toDp() }
    Canvas(modifier.size(w, h)) {
        drawPixels(sheet, IntOffset(frame * 64, 0), IntSize(64, 32), IntOffset.Zero, s, alpha, tint)
    }
}

/** 16x16 icon (accelerator/decelerator icons and ui_* glyphs). */
@Composable
fun PixelIcon(name: String, size: Dp = 32.dp, modifier: Modifier = Modifier, alpha: Float = 1f, tint: Color? = null) {
    if (Emoji.isEmojiIcon(name)) {
        // an emoji as the icon: the pixel emoji font draws it (1.125 em square), the same box as the 16x16 sprites
        val fs = with(LocalDensity.current) { (size / 1.125f).toSp() }
        val tinted = if (tint == null) Modifier else Modifier
            .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
            .drawWithContent { drawContent(); drawRect(tint, blendMode = BlendMode.SrcIn) }
        Box(modifier.size(size).then(tinted).alpha(alpha), contentAlignment = androidx.compose.ui.Alignment.Center) {
            androidx.compose.material3.Text(name, style = T.body.copy(fontSize = fs, lineHeight = fs), maxLines = 1, softWrap = false)
        }
        return
    }
    val img = rememberAsset("icons/$name.png")
    val s = pixelScale(size.value / 16f)
    val density = LocalDensity.current
    val d = with(density) { (16 * s).toDp() }
    Canvas(modifier.size(d)) {
        drawPixels(img, IntOffset.Zero, IntSize(16, 16), IntOffset.Zero, s, alpha, tint)
    }
}
