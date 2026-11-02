package com.momentum.app.ui

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import com.momentum.app.data.TextOverlay

val OVERLAY_COLORS = linkedMapOf(
    "white" to Color.White, "yellow" to Color(0xFFFFCD75), "cyan" to Color(0xFF73EFF7),
    "pink" to Color(0xFFFF8AD8), "lime" to Color(0xFFA7F070), "black" to Color(0xFF1A1C2C),
)

/** The text over a photo / video, placed by its center at (x, y) of the area, sized by the area's width. */
@Composable
fun OverlayText(o: TextOverlay, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val w = constraints.maxWidth.toFloat()
        val color = OVERLAY_COLORS[o.color] ?: Color.White
        val fontSize = with(density) { (w * o.size).toSp() }
        Text(
            o.text, textAlign = TextAlign.Center,
            // the font the server burns the text in with (server/overlay.py): the preview is the result
            style = TextStyle(fontSize = fontSize, lineHeight = fontSize * 1.1f, fontFamily = Pixelify, fontWeight = FontWeight.Bold, color = color,
                shadow = Shadow(if (o.color == "black") Color(0x88FFFFFF) else Color(0xCC000000), blurRadius = 8f)),
            modifier = Modifier.layout { m, c ->
                val p = m.measure(c.copy(minWidth = 0, minHeight = 0, maxWidth = (c.maxWidth * .9f).toInt()))
                layout(c.maxWidth, c.maxHeight) {
                    p.place((o.x * c.maxWidth - p.width / 2f).toInt().coerceIn(0, maxOf(0, c.maxWidth - p.width)),
                        (o.y * c.maxHeight - p.height / 2f).toInt().coerceIn(0, maxOf(0, c.maxHeight - p.height)))
                }
            },
        )
    }
}

/** Write a text onto a photo / video: type it, drag it into place, pick colour and size. */
@Composable
internal fun OverlayEditor(image: Any?, ratio: Float, initial: TextOverlay?, onDismiss: () -> Unit, onDone: (TextOverlay?) -> Unit) {
    val S = LocalStrings.current
    var o by remember { mutableStateOf(initial ?: TextOverlay("")) }
    androidx.activity.compose.BackHandler { onDismiss() }
    run {
        Column(Modifier.fillMaxSize().background(Color.Black).clickable(enabled = false) {}
            .statusBarsPadding().navigationBarsPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconBtn("ui_close") { onDismiss() }
                Text(S.addText, style = T.title, modifier = Modifier.weight(1f))
                Box(Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp)).background(Px.Text)
                    .clickable { onDone(o.takeIf { it.text.isNotBlank() }) }.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Text(S.done.uppercase(), style = T.button.copy(color = Color.Black))
                }
            }
            // the picture with the text on it: drag the text around
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                val r = ratio.coerceIn(.5f, 2f)
                Box(Modifier.fillMaxWidth().aspectRatio(r, matchHeightConstraintsFirst = maxHeight < maxWidth / r)) {
                    AsyncImage(image, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    BoxWithConstraints(Modifier.fillMaxSize().pointerInput(Unit) {
                        detectDragGestures { change, d ->
                            change.consume()
                            o = o.copy(x = (o.x + d.x / size.width).coerceIn(0f, 1f), y = (o.y + d.y / size.height).coerceIn(0f, 1f))
                        }
                    }) { if (o.text.isNotBlank()) OverlayText(o) }
                }
            }
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PxTextField(o.text, { o = o.copy(text = it.take(120)) }, placeholder = S.overlayHint, userContent = true, emoji = true)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    OVERLAY_COLORS.forEach { (key, c) ->
                        Box(Modifier.size(30.dp).clip(CircleShape).background(c)
                            .border(if (o.color == key) 3.dp else 1.dp, if (o.color == key) Px.Yellow else Color(0x55FFFFFF), CircleShape)
                            .clickable { o = o.copy(color = key) })
                    }
                }
                Slider(o.size, { o = o.copy(size = it) }, valueRange = .03f..0.16f,
                    colors = SliderDefaults.colors(thumbColor = Px.Yellow, activeTrackColor = Px.Yellow, inactiveTrackColor = Px.Line))
            }
        }
    }
}


/** The text editor is drawn above everything by the app's root (like the camera), see [OverlayEditorHost]. */
object OverlayEditing {
    internal class Request(val image: Any?, val ratio: Float, val initial: TextOverlay?, val done: (TextOverlay?) -> Unit)
    internal var request by mutableStateOf<Request?>(null)
    fun open(image: Any?, ratio: Float, initial: TextOverlay?, done: (TextOverlay?) -> Unit) {
        request = Request(image, ratio, initial, done)
    }
}

@Composable
fun OverlayEditorHost() {
    val r = OverlayEditing.request ?: return
    OverlayEditor(r.image, r.ratio, r.initial, onDismiss = { OverlayEditing.request = null }) { o ->
        OverlayEditing.request = null
        r.done(o)
    }
}
