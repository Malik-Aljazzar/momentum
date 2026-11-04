package com.momentum.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.momentum.app.AppViewModel
import com.momentum.app.data.ConversationResponse
import com.momentum.app.data.NotificationItem
import com.momentum.app.data.NotificationsResponse
import com.momentum.app.data.Post
import com.momentum.app.data.PostsResponse
import com.momentum.app.data.Profile
import com.momentum.app.data.ProfileResponse
import com.momentum.app.data.UserCard
import com.momentum.app.data.UsersResponse
import com.momentum.app.fmt
import com.momentum.app.signed
import com.momentum.app.secondsSince
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Toggle following; returns the updated card. */
suspend fun AppViewModel.toggleFollow(u: UserCard): UserCard? =
    req<ProfileResponse>(if (u.following) "DELETE" else "POST", "/users/${u.username}/follow")?.profile?.card()

/** Profile tabs (like Instagram); the first one is the momentum card with the banner. */
enum class ProfileSection(val icon: String) { Momentum("ui_gauge"), Posts("ui_grid"), Rules("ui_rules"), History("ui_clock") }

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ProfileScreen(vm: AppViewModel, username: String, isTab: Boolean, list: LazyListState = rememberLazyListState()) {
    val S = LocalStrings.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    var profile by remember { mutableStateOf<Profile?>(null) }
    var posts by remember { mutableStateOf<List<Post>>(emptyList()) }
    var reload by remember { mutableIntStateOf(0) }
    var section by remember(username) { mutableStateOf(ProfileSection.Momentum) }
    var openRule by remember { mutableStateOf<Int?>(null) }
    var openDecay by remember { mutableStateOf<String?>(null) }
    var sharing by remember { mutableStateOf(false) }
    var morePosts by remember { mutableStateOf(true) }
    var menu by remember { mutableStateOf(false) }
    var badge by remember { mutableStateOf<com.momentum.app.data.ProfileBadge?>(null) }   // tapped: why they have it
    var confirmBlock by remember { mutableStateOf(false) }
    LaunchedEffect(username, reload, vm.state?.user) {
        profile = vm.req<ProfileResponse>("GET", "/users/$username")?.profile
        val first = vm.req<PostsResponse>("GET", "/users/$username/posts?limit=30")?.posts.orEmpty()
        posts = first
        morePosts = first.size >= 30
    }
    // older posts as the grid is scrolled (3 per row)
    LoadMoreEffect(list, posts.size / 3, morePosts && section == ProfileSection.Posts, threshold = 4) {
        val older = vm.req<PostsResponse>("GET", "/users/$username/posts?limit=30&before=${posts.lastOrNull()?.id ?: 0}")?.posts
        if (older != null) { posts = (posts + older).distinctBy { it.id }; morePosts = older.size >= 30 }
    }

    Column(Modifier.fillMaxSize()) {
        TopBar(username, onBack = if (isTab) null else nav.pop) {
            if (profile != null) IconBtn("ui_share", alpha = .8f) { sharing = true }
            if (profile?.isMe == true) IconBtn("ui_gear") { nav.push(Route.Settings) }
            profile?.takeIf { !it.isMe }?.let { p ->
                Box {
                    IconBtn("ui_dots", alpha = .8f) { menu = true }
                    androidx.compose.material3.DropdownMenu(menu, { menu = false }, containerColor = Px.Card2) {
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text(if (p.blocked) S.unblockUser else S.blockUser, style = T.body.copy(color = if (p.blocked) Px.Text else Px.Orange)) },
                            onClick = {
                                menu = false
                                if (p.blocked) scope.launch { vm.req<ProfileResponse>("DELETE", "/users/${p.username}/block")?.let { profile = it.profile; reload++ } }
                                else confirmBlock = true
                            })
                    }
                }
            }
        }
        if (confirmBlock) profile?.let { p ->
            PxSheet({ confirmBlock = false }) {
                Text(S.blockConfirm(p.username), style = T.title.copy(fontSize = 20.sp))
                Text(S.blockConfirmHint, style = T.small)
                PxButton(S.blockUser, Modifier.fillMaxWidth(), style = BtnStyle.Danger) {
                    confirmBlock = false
                    scope.launch { vm.req<ProfileResponse>("POST", "/users/${p.username}/block")?.let { profile = it.profile; reload++ } }
                }
            }
        }
        OfflineStrip(vm.online)
        val p = profile ?: run { if (!vm.online) OfflineState(); return@Column }
        LazyColumn(state = list, contentPadding = PaddingValues(bottom = TAB_BAR_HEIGHT + 40.dp)) {
            item {
                // like Instagram: the picture on the left, the numbers next to it, then name and bio
                Column(Modifier.padding(horizontal = 16.dp)) {
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        // the picture: my own starts a story (or shows the running one), others show theirs
                        StoryAvatar(p.card(), 80.dp, if (p.story.active > 0) p.story.unseen else null,
                            plus = if (p.isMe) ({ nav.push(Route.NewStory) }) else null,
                            onClick = {
                                if (p.story.active == 0) { if (p.isMe) nav.push(Route.NewStory) }
                                else scope.launch {
                                    val list = vm.req<com.momentum.app.data.StoriesResponse>("GET", "/users/${p.username}/stories")?.stories.orEmpty()
                                    if (list.isNotEmpty()) StoryViewing.open = StoryViewing.Open(
                                        listOf(com.momentum.app.data.StoryRow(p.card(), list, p.story.unseen)), 0) { reload++ }
                                }
                            })
                        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.SpaceEvenly) {
                            Stat(p.posts, S.posts) {}
                            // private account: the numbers stay visible, the lists only for followers
                            Stat(p.followersCount, S.followers) { if (!p.locked) nav.push(Route.FollowList(p.username, true)) }
                            Stat(p.followingCount, S.followingLabel) { if (!p.locked) nav.push(Route.FollowList(p.username, false)) }
                        }
                    }
                    Row(Modifier.padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(p.displayName, style = T.bodyStrong, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false))
                        if (p.private) { Spacer(Modifier.width(6.dp)); PixelIcon("ui_lock", 14.dp, alpha = .7f) }
                        if (p.friend) { Spacer(Modifier.width(8.dp)); Label(S.friend, color = Px.Lime) }
                        // the King of Reporters: tap = why
                        p.badges.firstOrNull { it.key == "reporter_king" }?.let { b ->
                            Spacer(Modifier.width(8.dp))
                            Label("👑 " + S.reporterKing, color = Px.Yellow, modifier = Modifier.clickable { badge = b })
                        }
                    }
                    // One of the Nine: the titles this player holds; tap = the title screen
                    if (p.titles.isNotEmpty()) Row(Modifier.padding(start = 2.dp, top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        p.titles.forEach { k ->
                            NineIcon(k, 28.dp, Modifier.clickable { NineLore.open = k }.semantics { contentDescription = S.nineName(k) })
                        }
                    }
                    if (p.bio.isNotBlank()) {
                        Box(Modifier.padding(start = 2.dp, end = 4.dp, top = 2.dp)) { MentionText(null, p.bio) }
                    }
                    Spacer(Modifier.height(12.dp))
                    if (!p.locked) HighlightsRow(vm, p.username, p.isMe)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (p.isMe) {
                            PxButton(S.settingsTitle, Modifier.weight(1f), style = BtnStyle.Ghost) { nav.push(Route.Settings) }
                        } else if (p.blocked) {
                            PxButton(S.unblockUser, Modifier.weight(1f), style = BtnStyle.Ghost) {
                                scope.launch { vm.req<ProfileResponse>("DELETE", "/users/${p.username}/block")?.let { profile = it.profile; reload++ } }
                            }
                        } else {
                            FollowButton(p.card(), small = false, modifier = Modifier.weight(1f), requested = p.requested) {
                                scope.launch {
                                    // follow, or for private accounts: request / withdraw the request
                                    vm.req<ProfileResponse>(if (p.following || p.requested) "DELETE" else "POST", "/users/${p.username}/follow")
                                        ?.let { profile = it.profile; reload++ }
                                }
                            }
                            if (p.following || p.followsMe) {
                                PxButton(S.message, Modifier.weight(1f), style = BtnStyle.Ghost) {
                                    scope.launch {
                                        vm.req<ConversationResponse>("POST", "/conversations/direct",
                                            buildJsonObject { put("username", p.username) })?.let { nav.push(Route.ChatRoom(it.conversation.id)) }
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                }
            }
            if (p.locked) {
                // private: the momentum stays visible, the rest only for followers
                if (!p.blocked) item { ProfileHero(vm, p) }
                item {
                    Box(Modifier.fillMaxWidth().height(1.dp).background(Px.Line))
                    if (p.blocked) EmptyHint(S.blockedTitle, S.blockedSub, "ui_lock") else EmptyHint(S.lockedTitle, S.lockedSub, "ui_lock")
                }
                return@LazyColumn
            }
            // posts / rules / history as tabs, like posts and reels on Instagram
            stickyHeader { ProfileTabBar(section) { section = it } }
            when (section) {
                ProfileSection.Momentum -> item { Spacer(Modifier.height(12.dp)); ProfileHero(vm, p) }
                ProfileSection.Posts -> {
                    if (posts.isEmpty()) item { EmptyHint(S.noPosts, icon = "ui_image") }
                    items(posts.chunked(3)) { row ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = 1.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            row.forEach { post -> Box(Modifier.weight(1f)) { PostTile(vm, post) { nav.push(Route.PostDetail(post.id)) } } }
                            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                        Spacer(Modifier.height(2.dp))
                    }
                }
                ProfileSection.Rules -> {
                    p.decay?.let { d -> item(key = "decay") { DecayRow(d) { openDecay = p.username } } }
                    if (p.rules.isEmpty() && p.decay == null) item { EmptyHint(S.noPublicRules, if (p.isMe) S.noPublicRulesMine else null, "ui_rules") }
                    items(p.rules, key = { "r${it.id}" }) { r -> PublicRuleRow(r) { openRule = r.id } }
                }
                ProfileSection.History -> {
                    if (p.history.isEmpty()) item { EmptyHint(S.noHistory, if (p.isMe && p.rules.isEmpty()) S.noPublicRulesMine else null, "ui_clock") }
                    items(p.history) { HistoryRow(it) }
                }
            }
        }
    }
    openRule?.let { RuleDetailSheet(vm, it, onDismiss = { openRule = null }) }
    badge?.let { b ->
        PxSheet({ badge = null }) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("👑", style = T.body.copy(fontSize = 22.sp))
                Text(S.reporterKing, style = T.block.copy(fontSize = 20.sp, color = Px.Yellow))
            }
            Spacer(Modifier.height(8.dp))
            Text(S.reporterKingWhy(b.count, b.bugs, b.features), style = T.body)
            Spacer(Modifier.height(4.dp))
            Text(S.reporterKingHint, style = T.body.copy(color = Px.Muted, fontSize = 14.sp))
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Stat(b.bugs, S.reportKindBug) {}
                Stat(b.features, S.reportKindFeature) {}
            }
            Spacer(Modifier.height(16.dp))
        }
    }
    openDecay?.let { DecayDetailSheet(vm, DecayRef.User(it), onDismiss = { openDecay = null; reload++ }) }
    if (sharing) profile?.let { p ->
        SharePicker(vm, buildJsonObject { put("kind", "profile"); put("username", p.username) }) { sharing = false }
    }
}

/** Momentum is the centre of a profile: big number over the current vehicle's scene. */
@Composable
private fun ProfileHero(vm: AppViewModel, p: Profile) {
    val S = LocalStrings.current
    val vs = vm.meta?.vehicles ?: return
    val idx = p.vehicle.coerceIn(0, vs.lastIndex)
    val v = vs[idx]
    val next = vs.getOrNull(idx + 1)
    val scene = sceneFor(v.category)
    val land = scene == Scene.Land
    Column(Modifier.padding(horizontal = 16.dp)) {
        Box(Modifier.fillMaxWidth().height(300.dp).competitionGlow(p.competing).clip(CardShape)) {
            BannerBackground(p.banner ?: defaultBanner(scene), Modifier.matchParentSize(), groundDp = 70f, road = land, speed = vehicleSpeed(v.key))
            Column(Modifier.padding(20.dp)) {
                ScoreLabel(p.competing)
                FitText(fmt(p.momentum), T.block, 64.sp, Modifier.fillMaxWidth())
                Text("${S.vehicle(v.key, v.label)} · ${S.stage(idx + 1, vs.size)}", style = T.small.copy(color = Px.Text))
                if (!p.competing) Text(S.rankedPaused(fmt(p.rankedMomentum)), style = T.small.copy(fontSize = 12.sp))
            }
            // road vehicles stand on the road, planes fly just above the landscape, spaceships stay low
            // (the text block above needs the room)
            val lift = when (scene) { Scene.Land -> 64.dp; Scene.Air -> 44.dp; else -> 20.dp }
            VehicleSprite(v.key, 2.5f, Modifier.align(Alignment.BottomCenter).padding(bottom = lift), night = bannerNight(p.banner ?: defaultBanner(scene)))
        }
        if (next != null && p.nextAt != null) {
            Spacer(Modifier.height(10.dp))
            SegBar(if (p.nextAt > p.reachedAt) (p.momentum - p.reachedAt) / (p.nextAt - p.reachedAt) else 1.0, 24)
            Text(S.toGo(fmt(p.nextAt - p.momentum)) + " · " + S.vehicle(next.key, next.label), style = T.small,
                modifier = Modifier.padding(top = 6.dp))
        }
        Spacer(Modifier.height(6.dp))
    }
}

/** Icon tabs under the profile header; stays pinned while scrolling. */
@Composable
private fun ProfileTabBar(current: ProfileSection, onSelect: (ProfileSection) -> Unit) {
    Column(Modifier.fillMaxWidth().background(Px.Bg)) {
        Row(Modifier.fillMaxWidth()) {
            ProfileSection.entries.forEach { sec ->
                val on = sec == current
                Column(
                    Modifier.weight(1f).clickable { onSelect(sec) }.padding(top = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    PixelIcon(sec.icon, 20.dp, alpha = if (on) 1f else .4f)
                    Spacer(Modifier.height(10.dp))
                    Box(Modifier.fillMaxWidth().height(2.dp).background(if (on) Px.Text else Color.Transparent))
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Px.Line))
    }
}

@Composable
private fun PublicRuleRow(r: com.momentum.app.data.PublicRule, onClick: () -> Unit) {
    val S = LocalStrings.current
    val acc = r.kind == "acc"
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(Px.Faint), contentAlignment = Alignment.Center) {
            PixelIcon(r.icon, 24.dp)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(r.name, style = T.bodyStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (acc) S.accTitle else S.decTitle, style = T.small)
        }
        Text((if (acc) "+" else "−") + com.momentum.app.fmtVal(r.value),
            style = T.block.copy(fontSize = 16.sp, color = if (acc) Px.Lime else Px.Orange))
    }
}

/** Absolute local timestamp, e.g. "17.10.2026, 14:32" / "Oct 17, 2026, 2:32 PM". */
private fun stamp(iso: String, lang: Lang): String = try {
    val pattern = if (lang == Lang.DE) "dd.MM.yyyy, HH:mm" else "MMM d, yyyy, h:mm a"
    java.time.OffsetDateTime.parse(iso).atZoneSameInstant(java.time.ZoneId.systemDefault())
        .format(java.time.format.DateTimeFormatter.ofPattern(pattern, java.util.Locale(lang.code)))
} catch (e: Exception) {
    ""
}

@Composable
private fun HistoryRow(e: com.momentum.app.data.HistoryItem) {
    val S = LocalStrings.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        PixelIcon(e.icon, 24.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(e.label, style = T.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(stamp(e.at, S.lang), style = T.small.copy(fontSize = 12.sp))
        }
        Text(signed(e.delta, ::fmt), style = T.block.copy(fontSize = 14.sp, color = if (e.delta >= 0) Px.Lime else Px.Orange))
    }
}

@Composable
private fun Stat(n: Int, label: String, onClick: () -> Unit) {
    Column(Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text("$n", style = T.block.copy(fontSize = 18.sp))
        Text(label, style = T.small.copy(fontSize = 12.sp))
    }
}

/** Square grid tile: first photo/video frame, milestone scene, or text. */
@Composable
fun PostTile(vm: AppViewModel, post: Post, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().aspectRatio(1f).background(Px.Card2).clickable(onClick = onClick)) {
        val first = post.media.firstOrNull()
        when {
            post.kind == "milestone" -> MilestoneTile(vm, post.vehicle.orEmpty(), Modifier.fillMaxSize(), dpPerPixel = 1.5f)
            first != null -> {
                RemoteImage(vm.abs(first.thumb), Modifier.fillMaxSize())
                if (first.isVideo) PixelIcon("ui_video", 16.dp, Modifier.align(Alignment.TopEnd).padding(6.dp))
                if (post.media.size > 1) PixelIcon("ui_feed", 16.dp, Modifier.align(Alignment.TopStart).padding(6.dp))
            }
            else -> Text(post.text, style = T.small.copy(color = Px.Text), maxLines = 5, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(10.dp))
        }
    }
}

@Composable
fun UserListScreen(vm: AppViewModel, title: String, empty: Pair<String, String>? = null,
                   load: suspend (offset: Int, limit: Int) -> List<UserCard>?) {
    val S = LocalStrings.current
    val nav = LocalNav.current
    val pages = remember(title) { UserPages(fetch = load) }
    LaunchedEffect(title) { pages.reset() }
    Column(Modifier.fillMaxSize()) {
        TopBar(title)
        PeopleList(vm, pages.users, onChange = pages::update, onEnd = { pages.more() }, hasMore = pages.hasMore) {
            nav.push(Route.UserProfile(it.username))
        }
        if (pages.users?.isEmpty() == true) (empty ?: S.empty("")).let { (t, sub) -> EmptyFriends(t, sub) }
    }
}

@Composable
private fun PeopleList(vm: AppViewModel, users: List<UserCard>?, onChange: (UserCard) -> Unit,
                       onEnd: (suspend () -> Unit)? = null, hasMore: Boolean = false, onOpen: (UserCard) -> Unit) {
    val scope = rememberCoroutineScope()
    val state = androidx.compose.foundation.lazy.rememberLazyListState()
    if (onEnd != null) LoadMoreEffect(state, users?.size ?: 0, hasMore) { onEnd() }
    LazyColumn(state = state) {
        items(users.orEmpty(), key = { it.username }) { u ->
            UserRow(u, onClick = { onOpen(u) }) {
                FollowButton(u) { scope.launch { vm.toggleFollow(u)?.let(onChange) } }
            }
        }
    }
}

@Composable
fun SearchScreen(vm: AppViewModel) {
    val S = LocalStrings.current
    val nav = LocalNav.current
    var q by remember { mutableStateOf("") }
    var groups by remember { mutableStateOf<List<com.momentum.app.data.GroupHit>>(emptyList()) }
    val pages = remember(q) {
        UserPages { offset, limit ->
            vm.req<UsersResponse>("GET", "/users/search?q=${java.net.URLEncoder.encode(q.trim(), "UTF-8")}&offset=$offset&limit=$limit")?.users
        }
    }
    val users = if (q.isBlank()) null else pages.users
    LaunchedEffect(q) {
        if (q.isBlank()) { groups = emptyList(); return@LaunchedEffect }
        delay(300)   // debounce typing
        pages.reset()
        groups = vm.req<com.momentum.app.data.GroupsResponse>("GET", "/groups/search?q=${java.net.URLEncoder.encode(q.trim(), "UTF-8")}")
            ?.groups.orEmpty()
    }
    Column(Modifier.fillMaxSize()) {
        TopBar(S.findPeople)
        if (!vm.online) { OfflineState(); return@Column }
        Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) { PxTextField(q, { q = it }, placeholder = S.searchHint) }
        if (groups.isNotEmpty()) {
            // public groups: a horizontal row above the people
            Label(S.groupsTitle.uppercase(), Modifier.padding(start = 16.dp, top = 10.dp, bottom = 6.dp))
            androidx.compose.foundation.lazy.LazyRow(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(groups.size, key = { groups[it].id }) { i ->
                    val gr = groups[i]
                    Box(Modifier.size(150.dp, 96.dp).clip(RoundedCornerShape(16.dp)).clickable { nav.push(Route.GroupInfo(gr.id)) }) {
                        BannerBackground(gr.banner ?: "dusk", Modifier.matchParentSize(), still = true)
                        Box(Modifier.matchParentSize().background(Color(0x55000000)))
                        Column(Modifier.align(Alignment.BottomStart).padding(10.dp)) {
                            Text(gr.name, style = T.bodyStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(S.members(gr.members) + if (gr.open) " · " + S.tagOpen else "", style = T.small.copy(fontSize = 11.sp, color = Px.Text))
                        }
                    }
                }
            }
        }
        PeopleList(vm, users, onChange = pages::update, onEnd = { pages.more() }, hasMore = pages.hasMore) {
            nav.push(Route.UserProfile(it.username))
        }
        if (users?.isEmpty() == true) EmptyHint(S.noUsers, icon = "ui_search")
    }
}

/** The comment an activity is about: at most two lines behind a quote bar (= CommentPreview in web/.../Profile.tsx). */
@Composable
private fun CommentPreview(text: String) {
    HyperText(
        text, T.small.userText(),
        Modifier.padding(top = 3.dp, bottom = 2.dp)
            .drawBehind { drawRect(Px.Muted, size = androidx.compose.ui.geometry.Size(2.dp.toPx(), size.height)) }
            .padding(start = 8.dp),
        maxLines = 2, overflow = TextOverflow.Ellipsis,
    )
}

@Composable
fun NotificationsScreen(vm: AppViewModel) {
    val S = LocalStrings.current
    val nav = LocalNav.current
    var items by remember { mutableStateOf<List<NotificationItem>?>(null) }
    var requests by remember { mutableIntStateOf(0) }
    var more by remember { mutableStateOf(true) }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(Unit) {
        vm.req<NotificationsResponse>("GET", "/notifications?limit=30")?.let {
            items = it.items; requests = it.requests; more = it.items.size >= 30
            com.momentum.app.data.Notifier.clearActivity(vm.getApplication())    // seen: its notifications go away
        }
        vm.refresh()   // clears the badge
    }
    LoadMoreEffect(listState, items?.size ?: 0, more) {
        vm.req<NotificationsResponse>("GET", "/notifications?limit=30&before=${items?.lastOrNull()?.id ?: 0}")?.let {
            items = (items.orEmpty() + it.items).distinctBy { n -> n.id }; more = it.items.size >= 30
        }
    }
    Column(Modifier.fillMaxSize()) {
        TopBar(S.activity)
        OfflineStrip(vm.online)
        val list = items ?: run { if (!vm.online) OfflineState(); return@Column }
        if (list.isEmpty() && requests == 0) EmptyHint(S.noActivity, icon = "ui_heart")
        LazyColumn(state = listState) {
            if (requests > 0) item {
                Row(
                    Modifier.fillMaxWidth().clickable { nav.push(Route.FollowRequests) }.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(40.dp).clip(RoundedCornerShape(20.dp)).background(Px.Faint), contentAlignment = Alignment.Center) {
                        PixelIcon("ui_lock", 18.dp)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(S.followRequests, style = T.bodyStrong)
                        Text(S.requestsCount(requests), style = T.small)
                    }
                    Box(Modifier.size(8.dp).background(Px.Orange))
                }
            }
            items(list, key = { it.id }) { n ->
                Row(
                    Modifier.fillMaxWidth()
                        .background(if (n.read) Px.Bg else Px.Faint)
                        .clickable {
                            nav.push(when {
                                n.challenge != null -> Route.ChallengeDetail(n.challenge.id)
                                n.kind == "request" -> Route.FollowRequests
                                n.postId != null -> Route.PostDetail(n.postId)
                                n.group != null -> Route.GroupInfo(n.group.id)
                                else -> Route.UserProfile(n.actor.username)
                            })
                        }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Avatar(n.actor.displayName, n.actor.avatar, 40.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        val ch = n.challenge
                        when {
                            n.kind == "ch_end" && ch != null -> Text(S.challengeResult(ch.title, ch.rank.takeIf { ch.mode == "ranking" },
                                fmt(ch.reward)), style = T.body)
                            n.kind == "ch_cancel" && ch != null -> MentionText(n.actor.displayName, S.challengeCancelled(ch.title))
                            else -> MentionText(n.actor.displayName, activityText(S, n.kind, n.rule?.name, n.group?.name))
                        }
                        n.comment?.takeIf { it.isNotEmpty() }?.let { CommentPreview(it) }
                        Text(secondsSince(n.at)?.let(S::timeAgo).orEmpty(), style = T.small.copy(fontSize = 12.sp))
                    }
                    if (n.postThumb != null) {
                        RemoteImage(vm.abs(n.postThumb), Modifier.size(44.dp).clip(RoundedCornerShape(6.dp)))
                    }
                }
            }
        }
    }
}

/** The people I blocked (settings); their profile has "unblock". */
@Composable
fun BlockedListScreen(vm: AppViewModel) {
    val S = LocalStrings.current
    UserListScreen(vm, S.blockedAccounts, S.blockedAccounts to S.noBlocked) { offset, limit ->
        vm.req<UsersResponse>("GET", "/blocks?offset=$offset&limit=$limit")?.users
    }
}

@Composable
fun FollowListScreen(vm: AppViewModel, username: String, followers: Boolean) {
    val S = LocalStrings.current
    val mine = vm.state?.user?.username == username
    val ctx = (if (followers) "followers" else "following") + if (mine) "" else "Of"
    UserListScreen(vm, if (followers) S.followers else S.followingLabel, S.empty(ctx, username)) {
        offset, limit -> vm.req<UsersResponse>("GET", "/users/$username/" + (if (followers) "followers" else "following") +
            "?offset=$offset&limit=$limit")?.users
    }
}

@Composable
fun CenteredText(text: String) = Text(text, style = T.small, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(24.dp))
