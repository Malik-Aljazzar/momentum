package com.momentum.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.ime
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.momentum.app.AppViewModel
import com.momentum.app.data.Highlight
import com.momentum.app.data.HighlightsResponse
import com.momentum.app.data.StoriesResponse
import com.momentum.app.data.Story
import com.momentum.app.data.StoryRow
import com.momentum.app.data.StoryRowsResponse
import com.momentum.app.data.UserCard
import com.momentum.app.secondsSince
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/*
 * Stories (24 h) and highlights (web/src/screens/Stories.tsx): the row at the top of the feed, the
 * full screen viewer, the profile picture's ring and the highlights on the profile.
 */

private val RING_NEW = Brush.linearGradient(listOf(Color(0xFFFFCD75), Color(0xFFEF7D57), Color(0xFFB13E53), Color(0xFFA77BFF)))

/** The open story viewer: whose stories (rows), starting at row; a highlight when opened from one. */
object StoryViewing {
    class Open(val rows: List<StoryRow>, val row: Int, val highlight: Highlight? = null, val onChange: () -> Unit = {})
    var open by mutableStateOf<Open?>(null)
}

/** Who has stories (the feed's row): rings on profile pictures elsewhere, in the chats (report #67). */
object StoryRings {
    var rows by mutableStateOf<List<StoryRow>>(emptyList())
    suspend fun load(vm: AppViewModel) { vm.req<StoryRowsResponse>("GET", "/stories")?.let { rows = it.rows } }
    /** The stories of [username] while some are new to me (never mine), else null. */
    fun unseen(username: String?) = rows.firstOrNull { it.user.username == username && !it.user.isMe && it.unseen }
    fun open(vm: AppViewModel, row: StoryRow, scope: kotlinx.coroutines.CoroutineScope) {
        StoryViewing.open = StoryViewing.Open(listOf(row), 0) { scope.launch { load(vm) } }
    }
}

@Composable
fun StoryHost(vm: AppViewModel) {
    val o = StoryViewing.open ?: return
    androidx.compose.runtime.key(o) {
        CoverLayer { StoryViewer(vm, o) { StoryViewing.open = null; o.onChange() } }
    }
}

/** A profile picture with the story ring: colourful while there's something new, grey once seen. */
@Composable
fun StoryAvatar(user: UserCard, size: Dp, ring: Boolean?, onClick: (() -> Unit)? = null, plus: (() -> Unit)? = null) {
    // ring: true = new, false = seen, null = no stories
    Box(Modifier.then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)) {
        val shape = RoundedCornerShape(size * .32f)
        Box(
            Modifier.then(
                when (ring) {
                    true -> Modifier.background(RING_NEW, shape).padding(3.dp)
                    false -> Modifier.background(Px.Line, shape).padding(3.dp)
                    null -> Modifier
                },
            ),
        ) {
            Box(Modifier.background(Px.Bg, RoundedCornerShape(size * .3f)).padding(if (ring != null) 2.dp else 0.dp)) {
                Avatar(user.displayName, user.avatar, size)
            }
        }
        if (plus != null) Box(
            Modifier.align(Alignment.BottomEnd).offset(2.dp, 2.dp).size(22.dp).clip(RoundedCornerShape(8.dp)).background(Px.Bg)
                .padding(2.dp).clip(RoundedCornerShape(6.dp)).background(Px.Cyan).clickable(onClick = plus),
            contentAlignment = Alignment.Center,
        ) { Text("+", style = T.block.copy(fontSize = 14.sp, color = Color.Black)) }
    }
}

/** The feed's top row: my story first (+ for a new one), then people with stories, new ones first. */
@Composable
fun StoriesRow(vm: AppViewModel) {
    val S = LocalStrings.current
    val nav = LocalNav.current
    val me = vm.state?.user ?: return
    var rows by remember { mutableStateOf<List<StoryRow>>(emptyList()) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(StoriesVersion.n, vm.feedVersion, reload) {
        vm.req<StoryRowsResponse>("GET", "/stories")?.let { rows = it.rows; StoryRings.rows = it.rows }
    }
    val mine = rows.firstOrNull { it.user.isMe }
    fun open(r: StoryRow) { StoryViewing.open = StoryViewing.Open(rows, rows.indexOf(r)) { reload++ } }
    val meCard = UserCard(username = me.username, displayName = me.displayName, avatar = me.avatar, isMe = true)
    LazyRow(Modifier.fillMaxWidth().padding(vertical = 6.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        item(key = "me") {
            Column(Modifier.width(68.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                StoryAvatar(meCard, 58.dp, if (mine != null) false else null,
                    onClick = { if (mine != null) open(mine) else nav.push(Route.NewStory) }, plus = { nav.push(Route.NewStory) })
                Spacer(Modifier.height(4.dp))
                Text(S.yourStory, style = T.small.copy(fontSize = 11.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        items(rows.filter { !it.user.isMe }, key = { it.user.username }) { r ->
            Column(Modifier.width(68.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                StoryAvatar(r.user, 58.dp, r.unseen, onClick = { open(r) })
                Spacer(Modifier.height(4.dp))
                Text(r.user.username, style = T.small.copy(fontSize = 11.sp, color = if (r.unseen) Px.Text else Px.Muted),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun StoryViewer(vm: AppViewModel, o: StoryViewing.Open, onClose: () -> Unit) {
    val S = LocalStrings.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    BackHandler { onClose() }
    val highlight = o.highlight
    var row by remember { mutableIntStateOf(o.row) }
    var stories by remember { mutableStateOf(o.rows.map { it.stories }) }
    var i by remember { mutableIntStateOf(if (highlight != null) 0 else maxOf(0, o.rows.getOrNull(o.row)?.stories?.indexOfFirst { !it.seen } ?: 0)) }
    var progress by remember { mutableFloatStateOf(0f) }
    var paused by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    var viewers by remember { mutableStateOf(false) }
    var sharing by remember { mutableStateOf(false) }
    var reply by remember { mutableStateOf("") }
    var replySent by remember { mutableStateOf(0) }          // "Gesendet" shown in the viewer (toasts sit under it)
    LaunchedEffect(replySent) { if (replySent > 0) { kotlinx.coroutines.delay(2500); replySent = 0 } }
    // typing an answer (keyboard up) holds the story
    val keyboardUp = androidx.compose.foundation.layout.WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current) > 0
    val holding = picking || viewers || sharing || keyboardUp || reply.isNotEmpty()
    val list = stories.getOrNull(row).orEmpty()
    val st: Story? = list.getOrNull(i)
    val user = o.rows.getOrNull(row)?.user
    fun next() {
        when {
            i + 1 < list.size -> i++
            highlight == null && row + 1 < o.rows.size -> { row++; i = 0 }
            else -> onClose()
        }
    }
    fun prev() {
        when {
            i > 0 -> i--
            highlight == null && row > 0 -> { row--; i = maxOf(0, (stories.getOrNull(row)?.size ?: 1) - 1) }
        }
    }
    // the story's sound
    val context = androidx.compose.ui.platform.LocalContext.current
    val audio = remember { androidx.media3.exoplayer.ExoPlayer.Builder(context).build().apply { repeatMode = androidx.media3.common.Player.REPEAT_MODE_ONE } }
    DisposableEffect(audio) { onDispose { audio.release() } }
    LaunchedEffect(st?.id) {
        progress = 0f
        val s = st ?: run { onClose(); return@LaunchedEffect }
        if (!s.mine && !s.seen) {
            vm.req<JsonObject>("POST", "/stories/${s.id}/seen")
            stories = stories.mapIndexed { r, l -> if (r == row) l.map { if (it.id == s.id) it.copy(seen = true) else it } else l }
        }
        val snd = s.sound
        if (snd != null) { audio.setMediaItem(androidx.media3.common.MediaItem.fromUri(vm.abs(snd.url)!!)); audio.prepare(); audio.play() } else audio.stop()
    }
    // images run 5 s, videos as long as they are (the clip's length from the server)
    LaunchedEffect(st?.id, paused, holding) {
        val s = st ?: return@LaunchedEffect
        if (paused || holding) { audio.pause(); return@LaunchedEffect }
        if (s.sound != null) audio.play()
        // a photo runs as long as chosen (report #39)
        val total = if (s.media.isVideo && s.media.duration > 0) (s.media.duration * 1000).toLong() else (s.duration * 1000).toLong().coerceAtLeast(1000L)
        val start = System.currentTimeMillis() - (progress * total).toLong()
        while (true) {
            progress = ((System.currentTimeMillis() - start).toFloat() / total).coerceAtMost(1f)
            if (progress >= 1f) { next(); break }
            kotlinx.coroutines.delay(32)
        }
    }
    if (st == null || user == null) return
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Box(Modifier.fillMaxSize().pointerInput(st.id) {
            awaitEachGesture {
                awaitFirstDown()
                paused = true
                val up = waitForUpOrCancellation()
                paused = false
                if (up != null) { if (up.position.x < size.width * .3f) prev() else next() }
            }
        }) {
            if (st.media.isVideo) VideoPlayer(vm.abs(st.media.url)!!, vm.abs(st.media.thumb), playing = !paused && !holding)
            else RemoteImage(vm.abs(st.media.url), Modifier.fillMaxSize(), contentScale = ContentScale.Fit,
                fallback = { RemoteImage(vm.abs(st.media.thumb), Modifier.fillMaxSize(), contentScale = ContentScale.Fit) })
        }
        Column(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 8.dp)) {
            // one bar per story, the current one filling up
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                list.forEachIndexed { k, _ ->
                    Box(Modifier.weight(1f).height(3.dp).background(Color.White.copy(alpha = .3f))) {
                        Box(Modifier.fillMaxWidth(if (k < i) 1f else if (k == i) progress else 0f).height(3.dp).background(Color.White))
                    }
                }
            }
            Row(Modifier.padding(top = 8.dp, start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).clickable { onClose(); nav.push(Route.UserProfile(user.username)) }, verticalAlignment = Alignment.CenterVertically) {
                    Avatar(user.displayName, user.avatar, 30.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(highlight?.title ?: user.username, style = T.bodyStrong.copy(color = Color.White))
                    Spacer(Modifier.width(8.dp))
                    Text(secondsSince(st.at)?.let(S::timeAgo).orEmpty(), style = T.small.copy(color = Color.White.copy(alpha = .7f)))
                }
                IconBtn("ui_close") { onClose() }
            }
            st.sound?.let { Text("♪ ${it.title}", style = T.small.copy(color = Color.White), maxLines = 1, modifier = Modifier.padding(start = 6.dp)) }
        }
        TriggerList(st.triggers, Modifier.align(Alignment.TopStart).statusBarsPadding().padding(top = if (st.sound != null) 104.dp else 84.dp))
        if (st.mine) Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBtn("ui_share") { sharing = true }
            // who saw it
            Row(Modifier.clip(RoundedCornerShape(12.dp)).background(Color(0x9E0A0C14)).clickable { viewers = true }
                .padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                PixelIcon("ui_eye", 18.dp)
                Spacer(Modifier.width(6.dp))
                Text("${st.views ?: 0}", style = T.block.copy(fontSize = 13.sp, color = Color.White))
            }
            if (highlight != null) PxButton(S.removeFromHighlight, Modifier.weight(1f), style = BtnStyle.Ghost) {
                scope.launch {
                    if (vm.req<JsonObject>("PUT", "/highlights/${highlight.id}", buildJsonObject { put("remove", JsonArray(listOf(JsonPrimitive(st.id)))) }) != null) {
                        stories = stories.mapIndexed { r, l -> if (r == row) l.filter { it.id != st.id } else l }
                        if (list.size <= 1) onClose()
                    }
                }
            } else {
                PxButton(S.addToHighlight, Modifier.weight(1f), style = BtnStyle.Ghost) { picking = true }
                PxButton(S.delete, style = BtnStyle.Danger) {
                    scope.launch {
                        if (vm.req<JsonObject>("DELETE", "/stories/${st.id}") != null) {
                            stories = stories.mapIndexed { r, l -> if (r == row) l.filter { it.id != st.id } else l }
                            StoriesVersion.n++
                            if (list.size <= 1) onClose() else if (i >= list.size - 1) i--
                        }
                    }
                }
            }
        }
        // others' stories: quick reactions, an answer (a message to the author), share it into a chat (report #33)
        if (!st.mine) Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().imePadding().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            fun answer(text: String) {
                val t = text.trim()
                if (t.isEmpty()) return
                scope.launch {
                    // the author's chat; encrypted: sent as a chat message with the story attached (the server can't read it)
                    val conv = vm.req<com.momentum.app.data.ConversationResponse>("POST", "/conversations/direct",
                        buildJsonObject { put("username", user?.username.orEmpty()) })?.conversation ?: return@launch
                    val e2e = com.momentum.app.data.E2EStore
                    if (conv.e2e && e2e.status != com.momentum.app.data.E2EStore.Status.Ready) { vm.showToast(S.e2eNeedsUnlock, error = true); return@launch }
                    val sent = if (conv.e2e) {
                        val sealed = runCatching { e2e.sealMessage(vm.api, conv.id, t) }.getOrNull() ?: return@launch
                        vm.req<JsonObject>("POST", "/conversations/${conv.id}/messages", JsonObject(sealed + ("share" to buildJsonObject {
                            put("kind", "story"); put("id", st.id)
                        })))
                    } else vm.req<JsonObject>("POST", "/stories/${st.id}/reply", buildJsonObject { put("text", t) })
                    if (sent != null) {
                        reply = ""
                        replySent++
                    }
                }
            }
            if (replySent > 0) Text("✓ " + S.storyReplySent, style = T.bodyStrong.copy(color = Px.Lime),
                modifier = Modifier.align(Alignment.CenterHorizontally).clip(RoundedCornerShape(12.dp))
                    .background(Color(0xB30A0C14)).padding(horizontal = 14.dp, vertical = 8.dp))
            if (st.canReply) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                STORY_REACTIONS.forEach { e ->
                    Text(e, style = T.body.copy(fontSize = 26.sp), modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { answer(e) }.padding(6.dp))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (st.canReply) {
                    Box(Modifier.weight(1f)) {
                        PxTextField(reply, { reply = it.take(2000) }, placeholder = S.replyToStory, userContent = true, emoji = true,
                            imeAction = androidx.compose.ui.text.input.ImeAction.Send, onDone = { answer(reply) })
                    }
                    if (reply.isNotBlank()) IconBtn("ui_send") { answer(reply) }
                } else Spacer(Modifier.weight(1f))
                IconBtn("ui_share") { sharing = true }
            }
        }
        if (sharing) SharePicker(vm, buildJsonObject { put("kind", "story"); put("id", st.id) }) { sharing = false }
        if (picking) HighlightPicker(vm, st) { picking = false }
        if (viewers) ViewersSheet(vm, st, onOpen = { u -> viewers = false; onClose(); nav.push(Route.UserProfile(u)) }) { viewers = false }
    }
}

/** Quick reactions to a story: sent at once as a message to the author. */
private val STORY_REACTIONS = listOf("❤️", "😂", "😮", "😢", "🔥", "👏")

/** A story shared into a chat, opened from its card (404: gone or not visible any more). */
suspend fun openSharedStory(vm: AppViewModel, id: Int) {
    val st = vm.req<com.momentum.app.data.StoryResponse>("GET", "/stories/$id")?.story ?: return
    StoryViewing.open = StoryViewing.Open(listOf(StoryRow(st.user, listOf(st))), 0)
}

/** Who saw my story, the latest first. */
@Composable
private fun ViewersSheet(vm: AppViewModel, story: Story, onOpen: (String) -> Unit, onDismiss: () -> Unit) {
    val S = LocalStrings.current
    val pages = remember(story.id) {
        UserPages { offset, limit -> vm.req<com.momentum.app.data.StoryViewersResponse>("GET", "/stories/${story.id}/viewers?offset=$offset&limit=$limit")?.viewers?.map { it.user } }
    }
    LaunchedEffect(story.id) { pages.reset() }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    LoadMoreEffect(listState, pages.users?.size ?: 0, pages.hasMore) { pages.more() }
    PxSheet(onDismiss) {
        Text("${S.seenBy} · ${story.views ?: 0}", style = T.title.copy(fontSize = 20.sp))
        val users = pages.users
        if (users != null && users.isEmpty()) Text(S.noViewsYet, style = T.small)
        else androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 380.dp), state = listState) {
            items(users.orEmpty(), key = { it.username }) { u -> UserRow(u, onClick = { onOpen(u.username) }) }
        }
    }
}

/** Put a story into one of my highlights, or a new one. */
@Composable
private fun HighlightPicker(vm: AppViewModel, story: Story, onDismiss: () -> Unit) {
    val S = LocalStrings.current
    val scope = rememberCoroutineScope()
    var hls by remember { mutableStateOf<List<Highlight>>(emptyList()) }
    var title by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { vm.req<HighlightsResponse>("GET", "/users/${vm.state?.user?.username}/highlights")?.let { hls = it.highlights } }
    fun done() { vm.showToast(S.saved); onDismiss() }
    PxSheet(onDismiss) {
        Text(S.addToHighlight, style = T.title.copy(fontSize = 20.sp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(hls, key = { it.id }) { h ->
                Column(Modifier.width(70.dp).clickable {
                    scope.launch { if (vm.req<JsonObject>("PUT", "/highlights/${h.id}", buildJsonObject { put("add", JsonArray(listOf(JsonPrimitive(story.id)))) }) != null) done() }
                }, horizontalAlignment = Alignment.CenterHorizontally) {
                    HighlightCircle(vm, h)
                    Text(h.title, style = T.small.copy(fontSize = 11.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Label(S.newHighlight.uppercase())
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { PxTextField(title, { title = it.take(30) }, placeholder = S.highlightName, userContent = true, emoji = true) }
            Spacer(Modifier.width(8.dp))
            PxButton(S.done) {
                if (title.isNotBlank()) scope.launch {
                    if (vm.req<JsonObject>("POST", "/highlights", buildJsonObject {
                            put("title", title.trim()); put("story_ids", JsonArray(listOf(JsonPrimitive(story.id))))
                        }) != null) done()
                }
            }
        }
    }
}

@Composable
private fun HighlightCircle(vm: AppViewModel, h: Highlight, size: Dp = 62.dp) {
    Box(Modifier.size(size + 4.dp).background(Px.Line, RoundedCornerShape(50)).padding(2.dp)) {
        Box(Modifier.fillMaxSize().clip(RoundedCornerShape(50)).background(Px.Faint).border(2.dp, Px.Bg, RoundedCornerShape(50))) {
            h.cover?.let { RemoteImage(vm.abs(it), Modifier.fillMaxSize()) }
        }
    }
}

/** The highlights on a profile (circles under the bio); my own get a "new" circle. */
@Composable
fun HighlightsRow(vm: AppViewModel, username: String, mine: Boolean) {
    val S = LocalStrings.current
    var hls by remember(username) { mutableStateOf<List<Highlight>>(emptyList()) }
    var reload by remember { mutableIntStateOf(0) }
    var creating by remember { mutableStateOf(false) }
    LaunchedEffect(username, StoriesVersion.n, reload) { vm.req<HighlightsResponse>("GET", "/users/$username/highlights")?.let { hls = it.highlights } }
    if (hls.isEmpty() && !mine) return
    LazyRow(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        items(hls, key = { it.id }) { h ->
            Column(Modifier.width(70.dp).clickable {
                if (h.stories.isNotEmpty()) StoryViewing.open = StoryViewing.Open(listOf(StoryRow(h.stories.first().user, h.stories)), 0, h) { reload++ }
            }, horizontalAlignment = Alignment.CenterHorizontally) {
                HighlightCircle(vm, h)
                Spacer(Modifier.height(4.dp))
                Text(h.title, style = T.small.copy(fontSize = 11.sp, color = Px.Text), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (mine) item(key = "new") {
            Column(Modifier.width(70.dp).clickable { creating = true }, horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(66.dp).border(2.dp, Px.Line, RoundedCornerShape(50)), contentAlignment = Alignment.Center) {
                    Text("+", style = T.block.copy(fontSize = 22.sp, color = Px.Muted))
                }
                Spacer(Modifier.height(4.dp))
                Text(S.newHighlight, style = T.small.copy(fontSize = 11.sp))
            }
        }
    }
    if (creating) NewHighlightSheet(vm, { creating = false }) { creating = false; reload++ }
}

/** A new highlight: a name and stories from my archive. */
@Composable
private fun NewHighlightSheet(vm: AppViewModel, onDismiss: () -> Unit, onDone: () -> Unit) {
    val S = LocalStrings.current
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf("") }
    var archive by remember { mutableStateOf<List<Story>?>(null) }
    var picked by remember { mutableStateOf(setOf<Int>()) }
    LaunchedEffect(Unit) { archive = vm.req<StoriesResponse>("GET", "/stories/archive?limit=60")?.stories.orEmpty() }
    PxSheet(onDismiss) {
        Text(S.highlights, style = T.title.copy(fontSize = 20.sp))
        PxTextField(title, { title = it.take(30) }, placeholder = S.highlightName, userContent = true, emoji = true)
        Label(S.pickStories.uppercase())
        val list = archive.orEmpty()
        if (archive != null && list.isEmpty()) Text(S.noStoriesYet, style = T.small)
        else LazyVerticalGrid(GridCells.Fixed(4), Modifier.heightIn(max = 320.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(list.size) { k ->
                val st = list[k]
                val on = st.id in picked
                Box(Modifier.aspectRatio(9f / 16f).clip(RoundedCornerShape(8.dp))
                    .then(if (on) Modifier.border(2.dp, Px.Cyan, RoundedCornerShape(8.dp)) else Modifier)
                    .clickable { picked = if (on) picked - st.id else picked + st.id }) {
                    RemoteImage(vm.abs(st.media.thumb), Modifier.fillMaxSize())
                    if (on) Box(Modifier.align(Alignment.TopEnd).padding(4.dp).size(18.dp).clip(RoundedCornerShape(5.dp)).background(Px.Cyan),
                        contentAlignment = Alignment.Center) { Text("✓", style = T.block.copy(fontSize = 11.sp, color = Color.Black)) }
                }
            }
        }
        PxButton(S.done, Modifier.fillMaxWidth()) {
            if (title.isBlank()) { vm.showToast(S.nameMissing, error = true); return@PxButton }
            scope.launch {
                if (vm.req<JsonObject>("POST", "/highlights", buildJsonObject {
                        put("title", title.trim()); put("story_ids", JsonArray(picked.map { JsonPrimitive(it) }))
                    }) != null) onDone()
            }
        }
    }
}
