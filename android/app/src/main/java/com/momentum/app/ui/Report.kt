package com.momentum.app.ui

import android.net.Uri
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.momentum.app.AppViewModel
import com.momentum.app.BuildConfig
import com.momentum.app.data.MediaItem
import kotlinx.coroutines.launch
import com.momentum.app.fmt
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private class Attachment(val uri: Uri) {
    var media by mutableStateOf<MediaItem?>(null)
    var failed by mutableStateOf(false)
}

@Serializable
private data class ReportSent(val bonus: Double = 0.0)

/** Bug report or feature request with a description and screenshots/photos. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReportScreen(vm: AppViewModel) {
    val S = LocalStrings.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    var kind by remember { mutableStateOf("bug") }
    var text by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    val files = remember { mutableStateListOf<Attachment>() }
    val picker = rememberMediaPicker(multiple = true) { uris ->
        uris.take(6 - files.size).forEach { uri ->
            val a = Attachment(uri)
            files += a
            scope.launch { a.media = vm.uploadMedia(uri); a.failed = a.media == null }
        }
    }
    val uploading = files.any { it.media == null && !it.failed }
    val ready = text.trim().length >= 3 && !uploading && !sending

    Column(Modifier.fillMaxSize().imePadding()) {
        TopBar(S.reportTitle)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("bug" to S.reportBug, "feature" to S.reportFeature).forEach { (k, label) ->
                    val on = kind == k
                    Box(
                        Modifier.clip(RoundedCornerShape(12.dp)).background(if (on) Px.Text else Px.Faint).clickable { kind = k }
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                    ) { Text(label.uppercase(), style = T.button.copy(color = if (on) Color.Black else Px.Text)) }
                }
            }
            Text(if (kind == "bug") S.reportBugHint else S.reportFeatureHint, style = T.small)
            BasicTextField(
                text, { if (it.length <= 4000) text = it }, textStyle = T.body.userText(), cursorBrush = SolidColor(Px.Yellow),
                minLines = 6,
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { inner ->
                    Box(
                        Modifier.clip(RoundedCornerShape(14.dp)).background(Color(0x0DFFFFFF))
                            .border(1.dp, Px.Line, RoundedCornerShape(14.dp)).padding(14.dp).heightIn(min = 140.dp),
                    ) {
                        if (text.isEmpty()) Text(S.reportPlaceholder, style = T.body.userText().copy(color = Px.Muted))
                        inner()
                    }
                },
            )
            Label(S.reportImages.uppercase())
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                files.forEach { a ->
                    Box(Modifier.size(84.dp).clip(RoundedCornerShape(12.dp)).background(Px.Card2)) {
                        AsyncImage(a.uri, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()
                            .alpha(if (a.media == null) .45f else 1f))
                        Box(Modifier.align(Alignment.TopEnd).clickable { files.remove(a) }.padding(4.dp)) { PixelIcon("ui_close", 14.dp) }
                        if (a.failed) PixelIcon("storm", 20.dp, Modifier.align(Alignment.Center))
                    }
                }
                if (files.size < 6) {
                    Box(
                        Modifier.size(84.dp).clip(RoundedCornerShape(12.dp)).background(Px.Faint).clickable { picker.open() },
                        contentAlignment = Alignment.Center,
                    ) { PixelIcon("ui_plus", 20.dp, alpha = .7f) }
                }
            }
            PxButton(if (sending) "…" else S.reportSend, Modifier.fillMaxWidth().alpha(if (ready) 1f else .4f)) {
                if (!ready) return@PxButton
                sending = true
                scope.launch {
                    val body = buildJsonObject {
                        put("kind", kind); put("text", text.trim())
                        put("media_ids", JsonArray(files.mapNotNull { it.media?.id }.map { JsonPrimitive(it) }))
                        put("platform", "android")
                        put("app_version", BuildConfig.VERSION_NAME)
                        put("device", "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}")
                    }
                    vm.req<ReportSent>("POST", "/reports", body)?.let { sent ->
                        vm.showToast(if (sent.bonus >= 1) S.reportThanksBonus(fmt(sent.bonus)) else S.reportThanks)
                        if (sent.bonus >= 1) vm.refresh()        // the bonus shows in the history
                        nav.pop()
                    }
                    sending = false
                }
            }
        }
    }
}
