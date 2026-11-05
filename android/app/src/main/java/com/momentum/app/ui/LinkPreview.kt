package com.momentum.app.ui

// Link previews in chats, like WhatsApp (= web/src/linkpreview.tsx; server/linkpreview.py): while a message with
// a web link is typed, its preview is fetched and shown above the input field (x = without); it is sent along
// with the message - inside the envelope in an encrypted chat. In the chat it shows as a card in the bubble;
// only for a link that is in the text (shownLink), with the host from that link.

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.momentum.app.AppViewModel
import com.momentum.app.data.LinkPreview
import com.momentum.app.data.LinkPreviewResponse
import kotlinx.coroutines.delay

private const val WAIT_MS = 600L           // typing pause before the preview is fetched
/** previews fetched this run (null: the page has none / can't be reached) */
private val fetched = mutableStateMapOf<String, LinkPreview?>()

/** The link preview for the text being typed: url (null: no link / dismissed), the preview when it came,
 *  loading meanwhile; dismiss = send without it. */
class LinkDraft(val url: String?, val preview: LinkPreview?, val loading: Boolean, val dismiss: () -> Unit)

@Composable
fun rememberLinkDraft(vm: AppViewModel, text: String): LinkDraft {
    val url = remember(text) { firstLink(text) }
    var dismissed by remember { mutableStateOf<String?>(null) }
    val blank = text.isBlank()
    LaunchedEffect(blank) { if (blank) dismissed = null }
    val active = url?.takeIf { it != dismissed }
    LaunchedEffect(active) {
        if (active == null || fetched.containsKey(active) || !vm.online) return@LaunchedEffect
        delay(WAIT_MS)
        val got = runCatching {
            vm.api.call<LinkPreviewResponse>("GET", "/link-preview?url=" + java.net.URLEncoder.encode(active, "UTF-8")).preview
        }.getOrNull()
        if (fetched.size >= 200) fetched.remove(fetched.keys.first())
        fetched[active] = got?.takeIf { it.url == active }
    }
    return LinkDraft(active, active?.let { fetched[it] }, active != null && !fetched.containsKey(active) && vm.online) { dismissed = url }
}

/** The preview's small picture (base64 WebP) as a bitmap; null without one / when it's damaged. */
@Composable
private fun rememberPreviewImage(b64: String?): ImageBitmap? = remember(b64) {
    b64?.let {
        runCatching {
            val bytes = Base64.decode(it, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        }.getOrNull()
    }
}

/** Above the input field: the preview that will go with the message (or that it's coming); x = without. */
@Composable
fun DraftLink(draft: LinkDraft) {
    val S = LocalStrings.current
    val p = draft.preview
    if (p == null && !draft.loading) return
    Row(Modifier.fillMaxWidth().background(Px.Card).padding(start = 12.dp, end = 4.dp, top = 8.dp).testTag("draft-link"),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(3.dp).height(40.dp).background(Px.Cyan))
        Spacer(Modifier.width(10.dp))
        rememberPreviewImage(p?.image)?.let {
            Image(it, null, Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop)
            Spacer(Modifier.width(10.dp))
        }
        Column(Modifier.weight(1f)) {
            if (p != null) {
                Text(p.title.ifBlank { p.description.ifBlank { linkHost(p.url) } }, style = T.bodyStrong.copy(fontSize = 14.sp).userText(),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(linkHost(p.url), style = T.small.copy(fontSize = 12.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            } else Text(S.linkPreviewLoading, style = T.small)
        }
        IconBtn("ui_close", alpha = .6f) { draft.dismiss() }
    }
}

/** The preview in a chat bubble: a tap opens the page, holding is the bubble's (reactions). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LinkCard(link: LinkPreview, onLongPress: () -> Unit = {}) {
    val uri = LocalUriHandler.current
    val bmp = rememberPreviewImage(link.image)
    val layout = linkLayout(bmp != null, link.width, link.height)
    Column(Modifier.width(260.dp).clip(RoundedCornerShape(12.dp)).background(Color(0x33000000))
        .combinedClickable(onLongClick = onLongPress) { runCatching { uri.openUri(link.url) } }.testTag("link-card")) {
        if (layout == LinkLayout.Big && bmp != null) {
            val ratio = (link.width.toFloat() / link.height.coerceAtLeast(1)).coerceIn(1.3f, 2.4f)
            Image(bmp, null, Modifier.fillMaxWidth().aspectRatio(ratio).background(Color(0xFF0B0C12)), contentScale = ContentScale.Crop)
        }
        if (layout == LinkLayout.Small && bmp != null) Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Image(bmp, null, Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop)
            Spacer(Modifier.width(10.dp))
            LinkWords(link, Modifier.weight(1f))
        } else LinkWords(link, Modifier.padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 9.dp))
    }
}

@Composable
private fun LinkWords(link: LinkPreview, modifier: Modifier) = Column(modifier) {
    if (link.title.isNotBlank()) Text(link.title, style = T.bodyStrong.copy(fontSize = 14.sp, lineHeight = 18.sp).userText(),
        maxLines = 2, overflow = TextOverflow.Ellipsis)
    if (link.description.isNotBlank()) Text(link.description, style = T.small.copy(fontSize = 12.sp, lineHeight = 16.sp, color = Px.Text.copy(alpha = .8f)).userText(),
        maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
    Text(linkHost(link.url), style = T.small.copy(fontSize = 11.sp), maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = 2.dp))
}
