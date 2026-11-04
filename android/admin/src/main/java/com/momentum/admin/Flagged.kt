package com.momentum.admin

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch

private val REVIEWS = listOf("waiting", "ok", "keep")
private val ReviewColors = mapOf("waiting" to Px.Yellow, "ok" to Px.Lime, "keep" to Px.Cyan)

private fun reviewLabel(r: String) = when (r) {
    "waiting" -> S.t("OFFEN", "OPEN"); "ok" -> "OK"; else -> S.t("VERDECKT", "COVERED")
}

/** The content filter's review list (server/moderation.py): posted photos / videos it found doubtful. They show
 *  behind a pixel cover until checked here: OK = shown normally, covered = stays covered, delete = the post / story
 *  is removed. */
@Composable
fun FlaggedTab(api: Api, onOpen: (Item) -> Unit, onAuthError: () -> Unit) {
    var review by rememberSaveable { mutableStateOf("waiting") }
    val items = remember { mutableStateListOf<FlaggedMedia>() }
    val counts = remember { mutableStateMapOf<String, Int>() }
    var end by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val list = rememberLazyListState()

    suspend fun more() {
        if (loading || end) return
        loading = true
        try {
            val page = api.flagged(review, items.size)
            items += page.items
            counts.putAll(page.counts)
            end = page.items.isEmpty()
            error = null
        } catch (e: ApiException) {
            if (e.status == 401 || e.status == 403) onAuthError() else error = e.message
            end = true
        } finally {
            loading = false
        }
    }

    LaunchedEffect(review, reload) {
        items.clear()
        end = false
        loading = false
        more()
        list.scrollToItem(0)
    }
    val nearEnd by remember { derivedStateOf { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 >= items.size - 5 } }
    LaunchedEffect(nearEnd, items.size) { if (nearEnd) more() }

    fun act(item: FlaggedMedia, action: String) {
        scope.launch {
            try {
                api.reviewFlagged(item.media_id, action)
                items.remove(item)
                counts[review] = ((counts[review] ?: 1) - 1).coerceAtLeast(0)
                if (action != "remove") counts[action] = (counts[action] ?: 0) + 1
            } catch (e: ApiException) {
                if (e.status == 401 || e.status == 403) onAuthError() else error = e.message
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Header(S.t("PRÜFEN", "REVIEW")) {
            Text(S.t("NEU LADEN", "RELOAD"), style = T.button.copy(color = Px.Yellow, fontSize = 11.sp),
                modifier = Modifier.clickable { reload++ }.padding(6.dp))
        }
        Segments(REVIEWS.map { it to "${reviewLabel(it)} ${counts[it] ?: ""}".trim() }, review, ReviewColors,
            Modifier.padding(horizontal = 16.dp)) { review = it }
        if (review == "waiting") {
            Text(S.t("Vom Filter als zweifelhaft erkannt (Nacktheit/Sex). In der App verpixelt, bis du entscheidest. Eindeutige Fälle lehnt der Server direkt ab.",
                "Found doubtful by the filter (nudity / sex). Pixelated in the app until you decide. The server refuses clear cases right away."),
                style = T.small, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
        }
        LazyColumn(state = list, contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize()) {
            items(items, key = { it.media_id }) { f -> FlaggedRow(api, f, onOpen = { onOpen(f.asItem()) }, onAction = { act(f, it) }) }
            item {
                when {
                    error != null -> Message(error!!, Px.Orange)
                    loading -> Message("…")
                    items.isEmpty() -> Message(S.t("Nichts zu prüfen", "Nothing to review"))
                }
            }
        }
    }
}

@Composable
private fun FlaggedRow(api: Api, f: FlaggedMedia, onOpen: () -> Unit, onAction: (String) -> Unit) {
    val uri = LocalUriHandler.current
    var confirm by remember(f.media_id) { mutableStateOf(false) }      // deleting takes a second tap
    PixelCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(96.dp).background(Px.Card2).border(2.dp, Px.Line).clickable(onClick = onOpen)) {
                    AsyncImage(api.blob(f.thumb), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Badge(if (f.story_id != null) "STORY" else "POST", if (f.story_id != null) Px.Orange else Px.Cyan)
                        if (f.kind == "video") Badge("VIDEO", Px.Muted)
                    }
                    Text("@${f.username}", style = T.body, maxLines = 1)
                    Text(S.t("Filter: ", "Filter: ") + "${(f.score * 100).toInt()} %", style = T.block.copy(fontSize = 15.sp,
                        color = if (f.score >= .8) Px.Orange else Px.Yellow))
                    Text(date(f.created_at), style = T.label)
                }
            }
            if (f.kind == "video") PixelButton(S.t("VIDEO ANSEHEN", "WATCH VIDEO"), Modifier.fillMaxWidth(), color = Px.Muted) {
                uri.openUri(api.url(f.url))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (f.review != "ok") PixelButton("OK", Modifier.weight(1f), color = Px.Lime) { onAction("ok") }
                if (f.review != "keep") PixelButton(S.t("VERDECKT", "COVERED"), Modifier.weight(1f), color = Px.Cyan) { onAction("keep") }
                PixelButton(if (confirm) S.t("SICHER?", "SURE?") else S.t("LÖSCHEN", "DELETE"), Modifier.weight(1f), color = Px.Red,
                    filled = confirm) { if (confirm) onAction("remove") else confirm = true }
            }
        }
    }
}

private fun FlaggedMedia.asItem() = Item(key = url.trimStart('/'), url = url, thumb = thumb, size = 0, kind = kind,
    username = username, media_id = media_id, post_id = post_id, story_id = story_id, created_at = created_at)
