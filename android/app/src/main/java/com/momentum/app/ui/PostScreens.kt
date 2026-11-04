package com.momentum.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.momentum.app.AppViewModel
import com.momentum.app.data.CommentItem
import com.momentum.app.data.MediaItem
import com.momentum.app.data.Post
import com.momentum.app.data.PostResponse
import com.momentum.app.data.UserCard
import com.momentum.app.data.UsersResponse
import com.momentum.app.secondsSince
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import androidx.compose.foundation.layout.height
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun PostDetailScreen(vm: AppViewModel, id: Int) {
    val S = LocalStrings.current
    val nav = LocalNav.current
    var post by remember { mutableStateOf<Post?>(null) }
    var comment by remember { mutableStateOf("") }
    var likersPath by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<Int?>(null) }      // long-pressed comment: can be deleted
    var replyTo by remember { mutableStateOf<CommentItem?>(null) }  // the comment being answered
    val expanded = remember { mutableStateListOf<Int>() }           // threads with their replies shown
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    likersPath?.let { LikersSheet(vm, it) { likersPath = null } }
    val scope = rememberCoroutineScope()
    val row: @Composable (CommentItem) -> Unit = { c ->
        CommentRow(vm, c, selected == c.id,
            onSelect = { selected = it }, onLikers = { likersPath = "/comments/${c.id}/likes" },
            onPost = { post = it },
            onReply = {
                replyTo = c
                val me = vm.state?.user?.username
                if (c.user.username != me && !comment.contains("@${c.user.username}")) comment = "@${c.user.username} " + comment
                runCatching { focus.requestFocus() }
            })
    }
    LaunchedEffect(id) { post = vm.req<PostResponse>("GET", "/posts/$id")?.post }

    Column(Modifier.fillMaxSize().imePadding()) {
        TopBar(S.comments)
        OfflineStrip(vm.online)
        val p = post ?: run { if (!vm.online) OfflineState(); return@Column }
        LazyColumn(Modifier.weight(1f)) {
            item { PostCard(vm, p, onChange = { post = it.copy(commentList = p.commentList) }, onDeleted = { nav.pop() }, detail = true) }
            // threads: top-level comments, their replies (folded until opened) below them
            val replies = p.commentList.filter { it.parentId != null }.groupBy { it.parentId }
            p.commentList.filter { it.parentId == null }.forEach { top ->
                item(key = top.id) { row(top) }
                val thread = replies[top.id].orEmpty()
                if (thread.isNotEmpty()) {
                    val open = top.id in expanded
                    if (open) items(thread, key = { it.id }) { row(it) }
                    item(key = "t${top.id}") {
                        Text(if (open) S.hideReplies else S.viewReplies(thread.size),
                            style = T.small.copy(fontSize = 12.sp),
                            modifier = Modifier.padding(start = 58.dp, top = 2.dp, bottom = 6.dp)
                                .clickable { if (open) expanded.remove(top.id) else expanded.add(top.id) })
                    }
                }
            }
        }
        MentionSuggestions(vm, comment, Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) { comment = it }
        replyTo?.let { r ->
            Row(Modifier.fillMaxWidth().background(Px.Card).padding(start = 12.dp, end = 4.dp, top = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(S.replyTo(r.user.displayName), style = T.small.copy(fontSize = 12.sp, color = Px.Yellow),
                    modifier = Modifier.weight(1f))
                IconBtn("ui_close", alpha = .6f) { replyTo = null }
            }
        }
        Row(
            Modifier.fillMaxWidth().background(Px.Card).navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            vm.state?.user?.let { Avatar(it, 32.dp) }
            Spacer(Modifier.width(10.dp))
            val sendComment = {
                val text = comment.trim()
                val parent = replyTo
                if (text.isNotEmpty()) scope.launch {
                    vm.req<PostResponse>("POST", "/posts/$id/comments", buildJsonObject {
                        put("text", text)
                        parent?.let { put("parent_id", it.id) }
                    })?.let {
                        post = it.post
                        comment = ""
                        replyTo = null
                        // show the thread the reply landed in
                        parent?.let { c -> (c.parentId ?: c.id).let { t -> if (t !in expanded) expanded.add(t) } }
                    }
                }
            }
            Box(Modifier.weight(1f)) {
                PxTextField(comment, { comment = it }, Modifier.focusRequester(focus), userContent = true, emoji = true, placeholder = S.addComment,
                    imeAction = androidx.compose.ui.text.input.ImeAction.Send, onDone = { sendComment() })
            }
            IconBtn("ui_send", alpha = if (comment.isBlank()) .3f else 1f) { sendComment() }
        }
    }
}

/** One comment: avatar, the username on its own line above the text, time + "reply", like heart.
 *  Replies are indented and get a smaller avatar. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun CommentRow(
    vm: AppViewModel, c: CommentItem, isSelected: Boolean,
    onSelect: (Int?) -> Unit, onLikers: () -> Unit, onPost: (Post) -> Unit, onReply: () -> Unit,
) {
    val S = LocalStrings.current
    val nav = LocalNav.current
    val view = androidx.compose.ui.platform.LocalView.current
    val scope = rememberCoroutineScope()
    val reply = c.parentId != null
    Row(
        Modifier.fillMaxWidth()
            .background(if (isSelected) Px.Faint else Color.Transparent)
            .combinedClickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
                onClick = { onSelect(null) },
                onLongClick = {
                    if (c.canDelete) {
                        view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                        onSelect(c.id)
                    }
                },
            )
            .padding(start = if (reply) 58.dp else 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
    ) {
        Box(Modifier.clickable { nav.push(Route.UserProfile(c.user.username)) }) {
            Avatar(c.user.displayName, c.user.avatar, if (reply) 24.dp else 32.dp)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(c.user.displayName, style = T.small.copy(fontSize = 13.sp, color = Px.Text,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
                modifier = Modifier.clickable { nav.push(Route.UserProfile(c.user.username)) })
            MentionText(null, c.text)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(secondsSince(c.at)?.let(S::timeAgo).orEmpty(), style = T.small.copy(fontSize = 12.sp))
                Spacer(Modifier.width(14.dp))
                Text(S.reply, style = T.small.copy(fontSize = 12.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
                    modifier = Modifier.clickable { onReply() })
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            LikeHeart(c.liked, 16.dp, onShowLikers = onLikers, onToggle = {
                scope.launch {
                    vm.req<PostResponse>(if (c.liked) "DELETE" else "POST", "/comments/${c.id}/like")?.let { onPost(it.post) }
                }
            })
            if (c.likes > 0) Text("${c.likes}", style = T.small.copy(fontSize = 11.sp), modifier = Modifier.clickable { onLikers() })
        }
        // the delete button only after a long press on the comment
        if (c.canDelete && isSelected) IconBtn("ui_close", alpha = .8f) {
            onSelect(null)
            scope.launch { vm.req<PostResponse>("DELETE", "/comments/${c.id}")?.let { onPost(it.post) } }
        }
    }
}

/** A picked file being uploaded (or done). */
internal class Draft(val uri: Uri, val video: Boolean) {
    var media by mutableStateOf<MediaItem?>(null)
    var failed by mutableStateOf(false)
    /** while uploading: where it is (the tile's progress bar) */
    var progress by mutableStateOf<com.momentum.app.data.UploadProgress?>(null)
    var overlay by mutableStateOf<com.momentum.app.data.TextOverlay?>(null)
    /** rules tapped with this picture when posting: rule id -> times */
    var rules by mutableStateOf(mapOf<Int, Int>())
}

internal const val SOUND_SECONDS = 30

/** Songs for a post's sound (Deezer via the server): search, listen to a preview, pick one. */
@Composable
internal fun SongSearch(vm: AppViewModel, onPick: (com.momentum.app.data.Song) -> Unit) {
    val S = LocalStrings.current
    val context = androidx.compose.ui.platform.LocalContext.current
    var q by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<com.momentum.app.data.Song>>(emptyList()) }
    var playing by remember { mutableStateOf<Long?>(null) }
    val player = remember { androidx.media3.exoplayer.ExoPlayer.Builder(context).build() }
    androidx.compose.runtime.DisposableEffect(player) { onDispose { player.release() } }
    LaunchedEffect(q) {
        kotlinx.coroutines.delay(350)                    // wait until typing pauses
        val term = q.trim()
        hits = if (term.length < 2) emptyList()
        else vm.req<com.momentum.app.data.SongsResponse>("GET", "/songs?q=" + java.net.URLEncoder.encode(term, "UTF-8"))?.songs.orEmpty()
    }
    LaunchedEffect(playing) {
        val id = playing
        if (id == null) player.stop()
        else { player.setMediaItem(androidx.media3.common.MediaItem.fromUri(vm.abs("/api/songs/$id/preview")!!)); player.prepare(); player.play() }
    }
    PxTextField(q, { q = it.take(80) }, placeholder = S.searchSong, userContent = true)
    androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 320.dp)) {
        items(hits.size) { i ->
            val song = hits[i]
            Row(Modifier.fillMaxWidth().clickable { playing = null; onPick(song) }.padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                RemoteImage(song.cover, Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(song.title, style = T.bodyStrong, maxLines = 1)
                    Text(song.artist, style = T.small.copy(fontSize = 12.sp), maxLines = 1)
                }
                Box(Modifier.size(36.dp).clip(RoundedCornerShape(18.dp)).background(Px.Card2)
                    .clickable { playing = if (playing == song.id) null else song.id }, contentAlignment = Alignment.Center) {
                    PixelIcon(if (playing == song.id) "ui_pause" else "ui_play", 16.dp)
                }
            }
        }
    }
    if (hits.isNotEmpty()) Text(S.songsByDeezer, style = T.small.copy(fontSize = 11.sp))
}

/** Pick the post's place: search (OpenStreetMap via the server) or just take the typed name. */
@Composable
internal fun PlaceSheet(vm: AppViewModel, onDismiss: () -> Unit, onPick: (com.momentum.app.data.Place) -> Unit) {
    val S = LocalStrings.current
    var q by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<com.momentum.app.data.Place>>(emptyList()) }
    LaunchedEffect(q) {
        kotlinx.coroutines.delay(350)                    // wait until typing pauses
        val term = q.trim()
        hits = if (term.length < 2) emptyList()
        else vm.req<com.momentum.app.data.PlacesResponse>("GET", "/places?q=" + java.net.URLEncoder.encode(term, "UTF-8"))?.places.orEmpty()
    }
    PxSheet(onDismiss) {
        Text(S.addLocation, style = T.title.copy(fontSize = 20.sp))
        PxTextField(q, { q = it.take(80) }, placeholder = S.searchPlace, userContent = true)
        androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 380.dp)) {
            if (q.trim().length >= 2) item {
                Text(S.useTypedPlace(q.trim()), style = T.body.copy(color = Px.Cyan),
                    modifier = Modifier.fillMaxWidth().clickable { onPick(com.momentum.app.data.Place(q.trim())) }.padding(vertical = 12.dp))
            }
            items(hits.size) { i ->
                val h = hits[i]
                Column(Modifier.fillMaxWidth().clickable { onPick(h) }.padding(vertical = 10.dp)) {
                    Text(h.name, style = T.bodyStrong)
                    if (h.detail.isNotBlank()) Text(h.detail, style = T.small.copy(fontSize = 12.sp))
                }
            }
        }
    }
}

/** A sound picked for a new post: the file, its name, its length and where the used part starts. */
internal data class SoundDraft(val uri: Uri, val title: String, val durationMs: Long, val startMs: Long = 0,
                               val endMs: Long = SOUND_SECONDS * 1000L)

internal fun soundDraft(context: android.content.Context, uri: Uri, title: String?): SoundDraft? = runCatching {
    val r = android.media.MediaMetadataRetriever()
    r.setDataSource(context, uri)
    val ms = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
    val tag = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_TITLE)
    r.release()
    val name = title ?: tag ?: context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }?.substringBeforeLast('.') ?: ""
    // unknown length: the first 30 s, like before (the server cuts what is there)
    SoundDraft(uri, name.take(60), ms, 0, if (ms > 0) minOf(ms, SOUND_SECONDS * 1000L) else SOUND_SECONDS * 1000L)
}.getOrNull()

/** Name, the part that is used and a preview. Posts: drag both ends (5-30 s). Stories: the part is as long as the
 *  story ([fixedMs]) and only slides. Song previews are 30 s long, so before a post's sound always started at the
 *  song's start. */
@Composable
internal fun SoundEditor(sd: SoundDraft, onChange: (SoundDraft) -> Unit, onRemove: () -> Unit, fixedMs: Long? = null) {
    val S = LocalStrings.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val preview = remember(sd.uri) { androidx.media3.exoplayer.ExoPlayer.Builder(context).build().apply {
        setMediaItem(androidx.media3.common.MediaItem.fromUri(sd.uri)); prepare() } }
    var playing by remember { mutableStateOf(false) }
    androidx.compose.runtime.DisposableEffect(preview) { onDispose { preview.release() } }
    LaunchedEffect(playing, sd.startMs, sd.endMs) {
        if (playing) {
            preview.seekTo(sd.startMs); preview.play()
            kotlinx.coroutines.delay((sd.endMs - sd.startMs).coerceAtLeast(500L))
            playing = false
        } else preview.pause()
    }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Px.Faint).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(36.dp).clip(RoundedCornerShape(18.dp)).background(Px.Card2).clickable { playing = !playing },
                contentAlignment = Alignment.Center) { PixelIcon(if (playing) "ui_pause" else "ui_play", 16.dp) }
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f)) { PxTextField(sd.title, { onChange(sd.copy(title = it.take(60))) }, placeholder = S.soundTitle) }
            IconBtn("ui_close", alpha = .6f) { onRemove() }
        }
        val fmtS = { ms: Long -> "%d:%02d".format(ms / 60000, ms / 1000 % 60) }
        Text(S.soundPart(fmtS(sd.startMs), fmtS(sd.endMs)) + " · ${(sd.endMs - sd.startMs + 500) / 1000} s", style = T.small)
        if (sd.durationMs > 0) SoundRange(sd, fixedMs) { a, b -> playing = false; onChange(sd.copy(startMs = a, endMs = b)) }
        Text(if (fixedMs != null) S.soundStoryHint else S.soundRangeHint, style = T.small.copy(fontSize = 11.sp))
    }
}

internal const val SOUND_MIN_MS = 5_000L

/** Keeps a part inside the sound: 5-30 s long (a story: exactly its length), within 0..duration
 *  (same as clampRange in web/src/screens/Feed.tsx). */
internal fun clampRange(durationMs: Long, startMs: Long, endMs: Long, fixedMs: Long? = null): Pair<Long, Long> {
    val dur = durationMs.coerceAtLeast(0L)
    if (fixedMs != null) {
        val len = minOf(fixedMs, dur)
        val a = startMs.coerceAtMost(dur - len).coerceAtLeast(0L)
        return a to a + len
    }
    val maxLen = minOf(SOUND_SECONDS * 1000L, dur)
    val minLen = minOf(SOUND_MIN_MS, maxLen)
    var a = startMs.coerceAtMost(dur - minLen).coerceAtLeast(0L)
    val b = maxOf(a + minLen, minOf(endMs, dur, a + maxLen))
    if (b - a > maxLen) a = b - maxLen
    return a to b
}

/** How long a story's sound part is: the photo's chosen length, or the video's - at most SOUND_SECONDS (videos run up
 *  to 60 s, the part loops then). Unknown video length: 30 s. Same as storySoundMs in web/src/screens/Feed.tsx. */
internal fun storySoundMs(video: Boolean, videoSeconds: Double?, photoSeconds: Int): Long =
    if (video) minOf(SOUND_SECONDS * 1000L, ((videoSeconds?.takeIf { it > 0 } ?: 30.0) * 1000).toLong())
    else photoSeconds * 1000L

/** The whole sound as a pixel bar, the used part lit: drag its ends (posts) or the part itself. */
@Composable
private fun SoundRange(sd: SoundDraft, fixedMs: Long?, onChange: (Long, Long) -> Unit) {
    val dur = sd.durationMs.toFloat()
    val cur by androidx.compose.runtime.rememberUpdatedState(sd)
    val bars = remember { List(48) { i -> .3f + ((i * 37) % 11) * .06f + ((i * 13) % 5) * .04f } }
    androidx.compose.foundation.Canvas(
        Modifier.fillMaxWidth().height(48.dp).background(Px.Card2).pointerInput(fixedMs) {
            var mode = 0          // 1 start, 2 end, 3 move
            var grab = 0f
            fun msAt(x: Float) = (x / size.width).coerceIn(0f, 1f) * dur
            detectDragGestures(
                onDragStart = { o ->
                    val ms = msAt(o.x)
                    val near = { v: Long -> kotlin.math.abs((ms - v) / dur) * size.width < 22.dp.toPx() }
                    val d = cur
                    mode = if (fixedMs == null && near(d.startMs)) 1 else if (fixedMs == null && near(d.endMs)) 2 else 3
                    grab = ms - d.startMs
                    if (mode == 3 && (ms < d.startMs || ms > d.endMs)) {         // outside: the part jumps there
                        grab = (d.endMs - d.startMs) / 2f
                        val (a, b) = clampRange(d.durationMs, (ms - grab).toLong(), (ms - grab).toLong() + (d.endMs - d.startMs), fixedMs)
                        onChange(a, b)
                    }
                },
                onDrag = { change, _ ->
                    val ms = msAt(change.position.x)
                    val d = cur
                    val (a, b) = when (mode) {
                        1 -> clampRange(d.durationMs, maxOf(minOf(ms.toLong(), d.endMs - SOUND_MIN_MS), d.endMs - SOUND_SECONDS * 1000L), d.endMs)
                        2 -> clampRange(d.durationMs, d.startMs, maxOf(ms.toLong(), d.startMs + SOUND_MIN_MS))
                        else -> clampRange(d.durationMs, (ms - grab).toLong(), (ms - grab).toLong() + (d.endMs - d.startMs), fixedMs)
                    }
                    onChange(a, b)
                },
            )
        },
    ) {
        val n = bars.size
        val gap = 2.dp.toPx()
        val w = (size.width - 8.dp.toPx() - gap * (n - 1)) / n
        bars.forEachIndexed { i, h ->
            val at = (i + .5f) / n * dur
            val on = at >= sd.startMs && at <= sd.endMs
            val bh = (size.height - 12.dp.toPx()) * h.coerceAtMost(1f)
            drawRect(if (on) Px.Yellow else Px.Line, Offset(4.dp.toPx() + i * (w + gap), (size.height - bh) / 2), Size(w, bh))
        }
        // the used part: a frame, square handles on its ends (posts)
        val x0 = sd.startMs / dur * size.width
        val x1 = sd.endMs / dur * size.width
        val stroke = 2.dp.toPx()
        drawRect(Px.Yellow, Offset(x0, 0f), Size(x1 - x0, size.height), style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
        if (fixedMs == null) for (x in listOf(x0, x1)) {
            drawRect(Color.Black, Offset(x - 6.dp.toPx(), 11.dp.toPx()), Size(12.dp.toPx(), 26.dp.toPx()))
            drawRect(Px.Yellow, Offset(x - 5.dp.toPx(), 12.dp.toPx()), Size(10.dp.toPx(), 24.dp.toPx()))
        }
    }
}

/** Everyone I follow or who follows me (for tagging, DMs and groups). */
@Composable
fun ConnectedPeople(vm: AppViewModel, context: String, content: @Composable (List<UserCard>) -> Unit) {
    val me = vm.state?.user?.username ?: return
    var people by remember { mutableStateOf<List<UserCard>?>(null) }
    LaunchedEffect(me) {
        val a = vm.req<UsersResponse>("GET", "/users/$me/following")?.users.orEmpty()
        val b = vm.req<UsersResponse>("GET", "/users/$me/followers")?.users.orEmpty()
        people = (a + b).distinctBy { it.username }.sortedBy { it.displayName.lowercase() }
    }
    val list = people ?: return
    if (list.isEmpty()) LocalStrings.current.empty(context).let { (t, sub) -> EmptyFriends(t, sub) } else content(list)
}
