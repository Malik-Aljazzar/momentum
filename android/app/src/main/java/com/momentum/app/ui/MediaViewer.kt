package com.momentum.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.momentum.app.AppViewModel
import com.momentum.app.data.MediaItem
import com.momentum.app.data.Trigger

/** A picture in the big view: the photo / video, its caption, the rules tapped with it. */
data class ViewerItem(val media: MediaItem, val caption: String? = null, val triggers: List<Trigger> = emptyList())

/**
 * Big view of photos / videos (posts, stories, chat) and the detail of a tapped rule trigger -
 * opened from anywhere, drawn above everything by the app's root ([MediaHost]).
 */
object MediaViewing {
    var items by mutableStateOf<List<ViewerItem>?>(null)
    var start by mutableStateOf(0)
    /** a tapped trigger: its rule's detail (like on the poster's profile) */
    var rule by mutableStateOf<Int?>(null)
    /** when a trigger was tapped: a tap on it isn't also a tap on the picture (no big view then) */
    var badgeTapAt = 0L
    fun open(list: List<ViewerItem>, index: Int = 0) { if (list.isNotEmpty()) { start = index; items = list } }
}

@Composable
fun MediaHost(vm: AppViewModel) {
    MediaViewing.items?.let { CoverLayer { MediaViewer(vm, it, MediaViewing.start) { MediaViewing.items = null } } }
    MediaViewing.rule?.let { RuleDetailSheet(vm, it, onDismiss = { MediaViewing.rule = null }) }
}

/** The rules tapped with a picture: small pills in its top right corner, a tap shows the rule. */
@Composable
fun TriggerBadges(triggers: List<Trigger>, modifier: Modifier = Modifier) {
    if (triggers.isEmpty()) return
    Column(modifier.padding(8.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        triggers.forEach { t ->
            Row(
                Modifier.clip(RoundedCornerShape(10.dp)).background(Color(0x730A0C14))
                    .clickable { MediaViewing.badgeTapAt = System.currentTimeMillis(); t.ruleId?.let { MediaViewing.rule = it } }.padding(horizontal = 6.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PixelIcon(t.icon, 14.dp)
                if (t.times > 1) {
                    Spacer(Modifier.width(4.dp))
                    Text("×${t.times}", style = T.block.copy(fontSize = 10.sp, color = if (t.kind == "acc") Px.Lime else Px.Orange))
                }
            }
        }
    }
}

/** A story's rules (report #30): each on its own line - icon, the name (sliding when it's long),
 *  how often, and the points it gave / took. A tap shows the rule. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun TriggerList(triggers: List<Trigger>, modifier: Modifier = Modifier) {
    if (triggers.isEmpty()) return
    Column(modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        triggers.forEach { t ->
            Row(
                Modifier.clip(RoundedCornerShape(12.dp)).background(Color(0x9E0A0C14))
                    .clickable { MediaViewing.badgeTapAt = System.currentTimeMillis(); t.ruleId?.let { MediaViewing.rule = it } }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PixelIcon(t.icon, 18.dp)
                Spacer(Modifier.width(8.dp))
                Text(t.name, style = T.small.copy(fontSize = 13.sp, color = Color.White), maxLines = 1,
                    modifier = Modifier.widthIn(max = 170.dp).basicMarquee())
                if (t.times > 1) {
                    Spacer(Modifier.width(8.dp))
                    Text("×${t.times}", style = T.small.copy(fontSize = 12.sp, color = Color.White.copy(alpha = .75f)))
                }
                if (t.points != 0.0) {
                    Spacer(Modifier.width(8.dp))
                    Text(com.momentum.app.signed(t.points) { com.momentum.app.fmt(it) },
                        style = T.block.copy(fontSize = 12.sp, color = if (t.points > 0) Px.Lime else Px.Orange))
                }
            }
        }
    }
}

/** Full screen, swipe sideways through the pictures, X (or back) closes; the caption under each. */
@Composable
private fun MediaViewer(vm: AppViewModel, items: List<ViewerItem>, start: Int, onClose: () -> Unit) {
    BackHandler { onClose() }
    val pager = rememberPagerState(initialPage = start.coerceIn(0, items.lastIndex)) { items.size }
    Box(Modifier.fillMaxSize().background(Color.Black).clickable(enabled = false) {}) {
        HorizontalPager(pager, Modifier.fillMaxSize()) { page ->
            val it = items[page]
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (Sensitive.covered(it.media)) SensitiveCover(vm, it.media, contentScale = ContentScale.Fit)
                    else if (it.media.isVideo) VideoPlayer(vm.abs(it.media.url)!!, vm.abs(it.media.thumb), playing = pager.currentPage == page, hls = vm.abs(it.media.hls))
                    else RemoteImage(vm.abs(it.media.url), Modifier.fillMaxSize(), contentScale = ContentScale.Fit,
                        fallback = { RemoteImage(vm.abs(it.media.thumb), Modifier.fillMaxSize(), contentScale = ContentScale.Fit) })
                    TriggerBadges(it.triggers, Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = 52.dp))
                }
                if (!it.caption.isNullOrBlank()) {
                    Text(it.caption, style = T.body.userText().copy(color = Px.Text),
                        modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp))
                }
            }
        }
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 8.dp, end = 16.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBtn("ui_close") { onClose() }
            Spacer(Modifier.weight(1f))
            if (items.size > 1) Text("${pager.currentPage + 1} / ${items.size}", style = T.small.copy(color = Px.Text))
        }
    }
}
