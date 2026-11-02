package com.momentum.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.safeDrawing
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val MAX_ZOOM = 5f
private const val OUTPUT_SIDE = 1024

/** Decode a picked photo (EXIF rotation applied), at most ~2048 px on the long side. */
fun decodeForCrop(context: Context, uri: Uri): Bitmap? = try {
    ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { d, info, _ ->
        d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        val long = max(info.size.width, info.size.height)
        if (long > 2048) {
            val f = 2048f / long
            d.setTargetSize((info.size.width * f).roundToInt(), (info.size.height * f).roundToInt())
        }
    }
} catch (e: Exception) {
    null
}

/**
 * Full-screen square crop: drag to move, pinch to zoom. The crop window always stays
 * inside the photo. Returns a JPEG (max 1024x1024); the backend compresses it below 100 KB.
 */
@Composable
fun CropDialog(source: Bitmap, onCancel: () -> Unit, onCropped: (ByteArray) -> Unit) {
    val S = LocalStrings.current
    val image = remember(source) { source.asImageBitmap() }
    var zoom by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }   // photo center relative to crop center, px
    var crop by remember { mutableFloatStateOf(0f) }          // crop window side, px
    val density = LocalDensity.current

    // photo scale at zoom 1: just covers the crop window
    fun baseScale() = if (crop == 0f) 1f else crop / min(source.width, source.height)
    fun clamp(o: Offset, z: Float): Offset {
        val s = baseScale() * z
        val maxX = (source.width * s - crop) / 2
        val maxY = (source.height * s - crop) / 2
        return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
    }

    fun result(): ByteArray {
        val s = baseScale() * zoom
        val side = (crop / s).roundToInt().coerceAtMost(min(source.width, source.height))
        val cx = source.width / 2f - offset.x / s
        val cy = source.height / 2f - offset.y / s
        val left = (cx - side / 2f).roundToInt().coerceIn(0, source.width - side)
        val top = (cy - side / 2f).roundToInt().coerceIn(0, source.height - side)
        var out = Bitmap.createBitmap(source, left, top, side, side)
        if (side > OUTPUT_SIDE) out = Bitmap.createScaledBitmap(out, OUTPUT_SIDE, OUTPUT_SIDE, true)
        return ByteArrayOutputStream().also { out.compress(Bitmap.CompressFormat.JPEG, 92, it) }.toByteArray()
    }

    // drawn above the app (a dialog window got wrong insets on some phones and hid the buttons)
    RootOverlay(onBack = onCancel) {
        Column(Modifier.fillMaxSize().background(Px.Bg).clickable(enabled = false) {}
            .windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.safeDrawing)) {
            Column(Modifier.padding(20.dp)) {
                Label(S.profile)
                Text(S.cropTitle, style = T.title.copy(fontSize = 22.sp))
            }
            BoxWithConstraints(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .pointerInput(source) {
                        detectTransformGestures { _, pan, gestureZoom, _ ->
                            val z = (zoom * gestureZoom).coerceIn(1f, MAX_ZOOM)
                            offset = clamp(offset + pan, z)
                            zoom = z
                        }
                    },
            ) {
                val w = constraints.maxWidth.toFloat()
                val h = constraints.maxHeight.toFloat()
                crop = min(w, h) - with(density) { 48.dp.toPx() }
                Canvas(Modifier.fillMaxSize()) {
                    val s = baseScale() * zoom
                    val dw = source.width * s
                    val dh = source.height * s
                    val center = Offset(w / 2, h / 2) + offset
                    drawImage(
                        image, srcOffset = IntOffset.Zero, srcSize = IntSize(source.width, source.height),
                        dstOffset = IntOffset((center.x - dw / 2).roundToInt(), (center.y - dh / 2).roundToInt()),
                        dstSize = IntSize(dw.roundToInt(), dh.roundToInt()),
                    )
                    // dim everything outside the crop window
                    val r = 28.dp.toPx()
                    val window = RoundRect((w - crop) / 2, (h - crop) / 2, (w + crop) / 2, (h + crop) / 2, CornerRadius(r))
                    val shade = Path().apply {
                        fillType = PathFillType.EvenOdd
                        addRect(androidx.compose.ui.geometry.Rect(Offset.Zero, Size(w, h)))
                        addRoundRect(window)
                    }
                    drawPath(shade, Color(0xB3000000))
                    drawPath(Path().apply { addRoundRect(window) }, Px.Text, style = Stroke(2.dp.toPx()))
                }
            }
            Text(S.cropHint, style = T.small, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(12.dp))
            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PxButton(S.cancel, Modifier.weight(1f), style = BtnStyle.Ghost, onClick = onCancel)
                PxButton(S.usePhoto, Modifier.weight(1f)) { onCropped(result()) }
            }
        }
    }
}


/**
 * Full-screen content drawn at the app's root, above everything, instead of in a dialog window
 * (those don't get the right insets in the edge-to-edge app). The app's root draws [RootOverlayHost].
 */
object RootOverlays {
    internal val stack = androidx.compose.runtime.mutableStateListOf<Pair<Any, @Composable () -> Unit>>()
}

@Composable
fun RootOverlay(onBack: () -> Unit, content: @Composable () -> Unit) {
    val key = remember { Any() }
    val latest = androidx.compose.runtime.rememberUpdatedState(content)
    val back = androidx.compose.runtime.rememberUpdatedState(onBack)
    androidx.compose.runtime.DisposableEffect(key) {
        RootOverlays.stack.add(key to @Composable {
            androidx.activity.compose.BackHandler { back.value() }
            latest.value()
        })
        onDispose { RootOverlays.stack.removeAll { it.first === key } }
    }
}

@Composable
fun RootOverlayHost() {
    RootOverlays.stack.toList().forEach { (k, c) -> androidx.compose.runtime.key(k) { c() } }
}
