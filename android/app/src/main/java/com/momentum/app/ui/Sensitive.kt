package com.momentum.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.momentum.app.AppViewModel
import com.momentum.app.data.MediaItem

/** Photos / videos flagged by the content filter (server/moderation.py) that were tapped open - for this app run,
 *  in every view (= web/src/screens/media.tsx `revealed`). */
object Sensitive {
    var revealed by mutableStateOf(emptySet<Int>())
    fun covered(m: MediaItem) = m.sensitive && m.cover != null && m.id !in revealed
    fun reveal(m: MediaItem) { revealed = revealed + m.id }
}

/** A flagged photo / video: its pixel mosaic with a warning; a tap (with [buttonOnly]: on "show" only - stories,
 *  where taps elsewhere go to the next story) shows the media. */
@Composable
fun SensitiveCover(vm: AppViewModel, media: MediaItem, modifier: Modifier = Modifier,
                   contentScale: ContentScale = ContentScale.Crop, buttonOnly: Boolean = false) {
    val S = LocalStrings.current
    val open = {
        MediaViewing.badgeTapAt = System.currentTimeMillis()      // not also a tap on the picture (no big view)
        Sensitive.reveal(media)
    }
    val quiet = remember { MutableInteractionSource() }
    Box(modifier.fillMaxSize().background(Color(0xFF0B0C12))
        .let { if (buttonOnly) it else it.clickable(interactionSource = quiet, indication = null, onClick = open) }) {
        RemoteImage(vm.abs(media.cover), Modifier.fillMaxSize(), contentScale = contentScale)
        Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PixelIcon("ui_eye", 28.dp)
            Text(S.sensitiveTitle, style = T.bodyStrong.copy(color = Color.White), textAlign = TextAlign.Center)
            Text(S.sensitiveHint, style = T.small.copy(color = Color.White.copy(alpha = .8f)), textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 260.dp))
            PxButton(S.sensitiveShow, Modifier.padding(top = 4.dp), style = BtnStyle.Ghost, onClick = open)
        }
    }
}
