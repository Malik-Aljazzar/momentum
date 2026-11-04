package com.momentum.app.ui

import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.annotation.OptIn
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import com.momentum.app.AppViewModel
import com.momentum.app.data.MediaItem
import com.momentum.app.data.Post
import com.momentum.app.data.PostResponse
import com.momentum.app.data.PostsResponse
import com.momentum.app.secondsSince
import kotlinx.coroutines.launch
import kotlinx.serialization.json.put
import androidx.media3.common.MediaItem as ExoItem

/** Text where @mentions (open the profile) and web links (open the browser, report #58) are tappable. */
@Composable
fun MentionText(prefixBold: String?, text: String, style: androidx.compose.ui.text.TextStyle = T.body) {
    val nav = LocalNav.current
    val mentionRanges = remember { mutableListOf<IntRange>() }
    val annotated = buildAnnotatedString {
        if (prefixBold != null) {
            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(prefixBold) }
            append("  ")
        }
        mentionRanges.clear()
        textParts(text).forEach { p ->
            when (p) {
                is TextPart.Plain -> append(p.text)
                is TextPart.Mention -> withLink(LinkAnnotation.Clickable(p.user, TextLinkStyles(SpanStyle(color = Px.Cyan))) {
                    nav.push(Route.UserProfile(p.user.lowercase()))
                }) {
                    mentionRanges += length until length + p.text.length
                    append(p.text)
                }
                is TextPart.Link -> withLink(LinkAnnotation.Url(p.url,
                    TextLinkStyles(SpanStyle(color = Px.Cyan, textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline)))) {
                    mentionRanges += length until length + p.text.length
                    append(p.text)
                }
            }
        }
    }
    // usernames and links never get the hyperspace shine, the rest of the text may
    HyperText(annotated, style = style.userText(), skip = mentionRanges.toList())
}

@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedScreen(vm: AppViewModel, list: LazyListState) {
    val S = LocalStrings.current
    val nav = LocalNav.current
    var creating by remember { mutableStateOf(false) }
    if (creating) PxSheet({ creating = false }) {
        Text(S.whatToCreate, style = T.title.copy(fontSize = 20.sp))
        @Composable
        fun option(icon: String, title: String, hint: String, go: () -> Unit) = Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Px.Faint)
                .border(1.dp, Px.Line, RoundedCornerShape(16.dp)).clickable { creating = false; go() }.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PixelIcon(icon, 30.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) { Text(title, style = T.bodyStrong); Text(hint, style = T.small.copy(fontSize = 12.sp)) }
        }
        option("ui_grid", S.newPost, S.postHint) { nav.push(Route.NewPost) }
        option("camera", S.newStory, S.storyHint) { nav.push(Route.NewStory) }
    }
    val posts = remember { mutableStateListOf<Post>() }
    var following by remember { mutableStateOf(-1) }
    var loading by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    var end by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    suspend fun load(reset: Boolean) {
        if (loading) return
        loading = true
        val before = if (reset) null else posts.lastOrNull()?.id
        vm.req<PostsResponse>("GET", "/feed" + (before?.let { "?before=$it" } ?: ""))?.let { res ->
            if (reset) posts.clear()
            posts.addAll(res.posts)
            following = res.following
            end = res.posts.isEmpty()
        }
        loading = false
    }
    LaunchedEffect(vm.feedVersion) { load(true) }
    // infinite scroll
    LaunchedEffect(list) {
        snapshotFlow { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.collect { last ->
            if (!end && posts.isNotEmpty() && last >= posts.size - 2) load(false)
        }
    }
    val unread = vm.state?.unread

    Column(Modifier.fillMaxSize()) {
        TopBar(S.feed, onBack = null) {
            IconBtn("ui_plus") { creating = true }          // post or story?
            IconBtn("ui_heart", badge = unread?.notifications ?: 0) { nav.push(Route.Notifications) }
            IconBtn("ui_cup") { nav.push(Route.Leaderboard()) }          // world top 10 / friends
        }
        OfflineStrip(vm.online)
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = {
                // dedicated flag: true immediately, false when done (even if a load was already running)
                refreshing = true
                scope.launch {
                    try {
                        while (loading) kotlinx.coroutines.delay(50)
                        load(true)
                    } finally {
                        refreshing = false
                    }
                }
            },
            modifier = Modifier.weight(1f),
        ) {
            LazyColumn(state = list, contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = TAB_BAR_HEIGHT + 40.dp)) {
                // stories at the top (like Instagram): mine first, then people with new ones
                item(key = "stories") { StoriesRow(vm) }
                item {
                    Row(
                        Modifier
                            .padding(horizontal = 16.dp, vertical = 6.dp)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(Px.Faint)
                            .clickable { nav.push(Route.Search) }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PixelIcon("ui_search", 16.dp, alpha = .55f)
                        Spacer(Modifier.width(10.dp))
                        Text(S.findPeople, style = T.body.copy(color = Px.Muted))
                    }
                }
                if (posts.isEmpty() && !loading && !vm.online) {
                    item { OfflineState() }
                } else if (posts.isEmpty() && !loading && following >= 0) {
                    item { EmptyFriends(S.emptyFeed, S.emptyFeedSub) }
                }
                items(posts, key = { it.id }) { post ->
                    PostCard(vm, post, onChange = { new -> posts.replaceAll { if (it.id == new.id) new else it } },
                        onDeleted = { posts.removeAll { it.id == post.id } })
                }
            }
        }
    }
}

/** Notices a double tap without taking the taps away from the content (video, pager, sound). */
private fun Modifier.doubleTap(onSingle: (() -> Unit)? = null, onDouble: () -> Unit) = pointerInput(Unit) {
    val slop = viewConfiguration.touchSlop
    var lastUp = 0L
    var lastPos = Offset.Zero
    kotlinx.coroutines.coroutineScope {
    var single: kotlinx.coroutines.Job? = null
    awaitPointerEventScope {
        var down = Offset.Zero
        while (true) {
            val e = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
            val ch = e.changes.singleOrNull() ?: continue
            if (ch.changedToDown()) down = ch.position
            if (!ch.changedToUp() || (ch.position - down).getDistance() > slop) continue      // a swipe, not a tap
            if (ch.uptimeMillis - lastUp < 300 && (ch.position - lastPos).getDistance() < slop * 4) {
                lastUp = 0L
                single?.cancel()
                onDouble()
            } else {
                lastUp = ch.uptimeMillis
                lastPos = ch.position
                // one tap only (after the double-tap time, not on a trigger pill): onSingle
                if (onSingle != null) {
                    single?.cancel()
                    single = launch {
                        kotlinx.coroutines.delay(300)
                        if (System.currentTimeMillis() - MediaViewing.badgeTapAt > 500) onSingle()
                    }
                }
            }
        }
    }
    }
}

/** The heart that pops up on a double tap: grows with a little bounce, then fades. */
@Composable
private fun BigHeart(key: Int) {
    val k = remember(key) { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(key) { k.animateTo(1f, androidx.compose.animation.core.tween(900, easing = androidx.compose.animation.core.LinearEasing)) }
    val v = k.value
    if (v >= 1f) return
    val scale = if (v < .25f) v / .25f * 1.2f else if (v < .4f) 1.2f - (v - .25f) / .15f * .2f else 1f
    val alpha = if (v < .7f) 1f else 1f - (v - .7f) / .3f
    Box(Modifier.graphicsLayer { scaleX = scale; scaleY = scale; this.alpha = alpha }) { PixelIcon("heart", 96.dp) }
}

@Composable
fun PostCard(vm: AppViewModel, post: Post, onChange: (Post) -> Unit, onDeleted: () -> Unit, detail: Boolean = false) {
    val S = LocalStrings.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    var showLikers by remember { mutableStateOf(false) }
    var sharing by remember { mutableStateOf(false) }
    if (showLikers) LikersSheet(vm, "/posts/${post.id}/likes") { showLikers = false }

    Column(Modifier.fillMaxWidth().padding(bottom = 18.dp)) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).clickable { nav.push(Route.UserProfile(post.user.username)) },
                verticalAlignment = Alignment.CenterVertically) {
                Avatar(post.user.displayName, post.user.avatar, 36.dp)
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(post.user.displayName, style = T.bodyStrong)
                    // the place under the name (like Instagram), then the time
                    post.location?.let { Text(it.name, style = T.small.copy(fontSize = 12.sp, color = Px.Text), maxLines = 1) }
                    Text(secondsSince(post.at)?.let(S::timeAgo).orEmpty(), style = T.small.copy(fontSize = 12.sp))
                }
            }
            if (post.mine) {
                Box {
                    IconBtn("ui_dots", alpha = .7f) { menu = true }
                    DropdownMenu(menu, { menu = false }, containerColor = Px.Card2) {
                        DropdownMenuItem(text = { Text(S.deletePost, style = T.body.copy(color = Px.Orange)) }, onClick = {
                            menu = false
                            scope.launch { if (vm.call("DELETE", "/posts/${post.id}")) onDeleted() }
                        })
                    }
                }
            }
        }

        val sound = post.sound
        val soundUrl = sound?.let { vm.abs(it.url) }
        var openRule by remember { mutableStateOf(false) }
        // double tap on the picture likes the post (never unlikes) and a big heart pops up
        var hearts by remember { mutableStateOf(0) }
        var page by remember { mutableStateOf(0) }
        // one tap on a photo / video: the big view of the post's pictures
        Box(Modifier.doubleTap(onSingle = if (post.media.isEmpty()) null else ({
            MediaViewing.open(post.media.mapIndexed { i, m -> ViewerItem(m, triggers = post.triggers.filter { it.slide == i }) }, page)
        })) {
            hearts++
            if (!post.liked) scope.launch { vm.req<PostResponse>("POST", "/posts/${post.id}/like")?.let { onChange(it.post) } }
        }, contentAlignment = Alignment.Center) {
        when {
            // a sound over photos plays like a video's sound; videos play it instead of their own
            post.kind == "rule" && post.rule != null -> FeedRuleTile(post.rule) { openRule = true }
            post.kind == "milestone" -> MilestoneTile(vm, post.vehicle.orEmpty(), Modifier.fillMaxWidth().aspectRatio(1.5f))
            post.media.isNotEmpty() && soundUrl != null && post.media.none { it.isVideo } ->
                SoundArea(soundUrl) { MediaPager(vm, post.media, triggers = post.triggers, onPage = { page = it }) }
            post.media.isNotEmpty() -> MediaPager(vm, post.media, soundUrl, post.triggers) { page = it }
            soundUrl != null -> SoundArea(soundUrl, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
                .clip(RoundedCornerShape(14.dp)).background(Px.Faint)) {
                Box(Modifier.fillMaxWidth().padding(14.dp)) { SoundLabel(sound.title) }
            }
        }
        if (hearts > 0) BigHeart(hearts)
        }
        if (sound != null && post.media.isNotEmpty()) SoundLabel(sound.title, Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp))

        Row(Modifier.padding(horizontal = 6.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            LikeHeart(post.liked, onShowLikers = { showLikers = true }, onToggle = {
                scope.launch {
                    vm.req<PostResponse>(if (post.liked) "DELETE" else "POST", "/posts/${post.id}/like")?.let { onChange(it.post) }
                }
            })
            IconBtn("ui_comment") { if (!detail) nav.push(Route.PostDetail(post.id)) }
            IconBtn("ui_share", alpha = .85f) { sharing = true }
        }
        if (openRule) post.rule?.let { RuleDetailSheet(vm, it.id, onDismiss = { openRule = false }) }
        if (sharing) {
            SharePicker(vm, kotlinx.serialization.json.buildJsonObject {
                put("kind", "post"); put("id", post.id)
            }) { sharing = false }
        }
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (post.likes > 0) Text(S.likes(post.likes), style = T.bodyStrong, modifier = Modifier.clickable { showLikers = true })
            if (post.kind == "milestone") {
                val v = vm.meta?.vehicles?.firstOrNull { it.key == post.vehicle }
                MentionText(post.user.displayName, S.milestonePost(v?.let { S.vehicle(it.key, it.label) } ?: ""))
            }
            post.rule?.let { MentionText(post.user.displayName, if (it.updated) S.ruleUpdatedPost else S.ruleSharedPost) }
            if (post.text.isNotBlank()) MentionText(if (post.kind == "milestone") null else post.user.displayName, post.text)
            if (post.tags.isNotEmpty()) {
                MentionText(null, "${S.with} " + post.tags.joinToString(", ") { "@${it.username}" }, T.small)
            }
            if (!detail && post.comments > 0) {
                Text(S.viewComments(post.comments), style = T.small,
                    modifier = Modifier.clickable { nav.push(Route.PostDetail(post.id)) })
            }
        }
    }
}

/** Shared milestone: the vehicle driving through its scene. */
@Composable
fun MilestoneTile(vm: AppViewModel, vehicle: String, modifier: Modifier, dpPerPixel: Float = 3f) {
    val v = vm.meta?.vehicles?.firstOrNull { it.key == vehicle } ?: return
    Box(modifier.clip(RoundedCornerShape(4.dp))) {
        val scene = sceneFor(v.category)
        SceneBackground(scene, Modifier.matchParentSize(), groundDp = if (scene == Scene.Land) 24f * dpPerPixel else 0f, speed = vehicleSpeed(v.key))
        VehicleSprite(v.key, dpPerPixel, Modifier.align(if (scene == Scene.Land) Alignment.BottomCenter else Alignment.Center)
            .padding(bottom = if (scene == Scene.Land) (22 * dpPerPixel).dp else 0.dp), night = scene != Scene.Air)
        PixelIcon("star", (8 * dpPerPixel).dp, Modifier.align(Alignment.TopStart).padding((4 * dpPerPixel).dp))
    }
}

@OptIn(UnstableApi::class)
@kotlin.OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaPager(vm: AppViewModel, media: List<MediaItem>, soundUrl: String? = null,
               triggers: List<com.momentum.app.data.Trigger> = emptyList(), onPage: (Int) -> Unit = {}) {
    val first = media.first()
    val ratio = if (first.width > 0 && first.height > 0) (first.width.toFloat() / first.height).coerceIn(0.8f, 1.91f) else 1f
    val pager = rememberPagerState { media.size }
    LaunchedEffect(pager.currentPage) { onPage(pager.currentPage) }
    Box(Modifier.fillMaxWidth().aspectRatio(ratio).background(Color(0xFF0B0C12))) {
        HorizontalPager(pager, Modifier.fillMaxSize()) { page ->
            val item = media[page]
            Box(Modifier.fillMaxSize()) {
                if (item.isVideo) VideoPlayer(vm.abs(item.url)!!, vm.abs(item.thumb), playing = pager.currentPage == page, soundUrl = soundUrl)
                else RemoteImage(vm.abs(item.url), Modifier.fillMaxSize())
                item.overlay?.let { OverlayText(it) }
                // the rules tapped with this slide: subtle, in the corner
                TriggerBadges(triggers.filter { it.slide == page }, Modifier.align(Alignment.TopEnd))
            }
        }
        if (media.size > 1) {
            Row(Modifier.align(Alignment.BottomCenter).padding(10.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                repeat(media.size) { i ->
                    Box(Modifier.size(6.dp).clip(RoundedCornerShape(1.dp))
                        .background(if (i == pager.currentPage) Px.Text else Px.Text.copy(alpha = .35f)))
                }
            }
        }
    }
}

/** A rule shared in the feed: its icon, name and value big; a tap shows it (and lets others subscribe). */
@Composable
private fun FeedRuleTile(rule: com.momentum.app.data.FeedRule, onOpen: () -> Unit) {
    val acc = rule.kind == "acc"
    val tint = if (acc) Px.Lime else Px.Orange
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp).clip(RoundedCornerShape(28.dp))
            .background(androidx.compose.ui.graphics.Brush.linearGradient(
                listOf(if (acc) Color(0xFF1D3A2C) else Color(0xFF3A1D2A), Color(0xFF13151D))))
            .border(2.dp, tint.copy(alpha = .35f), RoundedCornerShape(28.dp))
            .clickable(onClick = onOpen).padding(horizontal = 16.dp, vertical = 26.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RuleIconBox(rule.icon, 72.dp)
        Text(rule.name, style = T.title.copy(fontSize = 22.sp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Text((if (acc) "+" else "−") + com.momentum.app.fmt(rule.base), style = T.block.copy(fontSize = 26.sp, color = tint))
    }
}
