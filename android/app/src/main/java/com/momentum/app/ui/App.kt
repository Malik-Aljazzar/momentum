package com.momentum.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.momentum.app.AppViewModel
import com.momentum.app.Screen
import kotlinx.coroutines.launch
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import kotlinx.coroutines.delay
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.draw.clip
import kotlinx.serialization.json.put
import kotlin.random.Random

// The main screen's pages come from the player's tab layout (ui/Tabs.kt, data/TabLayout.kt): the bar's places - tabs
// and groups - and the Larper election's page while it runs. The app starts where the own profile is (the main page).

@Composable
fun MomentumApp(vm: AppViewModel) {
    CompositionLocalProvider(LocalStrings provides stringsFor(vm.lang), LocalUrl provides vm::abs,
        LocalImageRetry provides vm.imageRetry, LocalSystemFont provides vm.systemFont) {
        Box(Modifier.fillMaxSize().background(Px.Bg)) {
            AnimatedContent(vm.screen, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "screen") { screen ->
                when (screen) {
                    Screen.Loading -> Box(Modifier.fillMaxSize())
                    Screen.Offline -> OfflineScreen(vm)
                    Screen.Login -> LoginScreen(vm)
                    // the e-mail address first (required: server/mail.py), then the username, then the app
                    Screen.Main -> if (vm.state?.user?.emailConfirm?.required == true) ConfirmEmailScreen(vm)
                        else if (vm.state?.user?.needsUsername == true) UsernameScreen(vm) else MainScreen(vm)
                }
            }
            ToastHost(
                vm.toast,
                Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .imePadding()      // stay above the keyboard
                    .padding(bottom = if (vm.screen == Screen.Main) TAB_BAR_HEIGHT + 16.dp else 32.dp),
            )
            vm.whatsNew?.let { if (vm.screen == Screen.Main) WhatsNewSheet(it, vm.lang) { vm.whatsNew = null } }
            // both want the screen: what's new first, then the limit's change
            if (vm.whatsNew == null) vm.limitNews?.let { if (vm.screen == Screen.Main) LimitChangeSheet(it) { vm.limitNews = null } }
            if (vm.milestone && !vm.hyperspace) MilestoneOverlay(vm) { vm.milestone = false }
            if (vm.hyperspace) HyperspaceOverlay(vm) { vm.hyperspace = false; vm.milestone = false }
            val golden = vm.state?.user?.hyperspace == true
            androidx.compose.runtime.SideEffect { HyperLore.golden = golden }
            HyperLore.open?.let { key ->
                val u = vm.state?.user
                HyperLoreOverlay(key, if (key == HYPERSPACE) u?.hyperspace == true else u?.ultra == true) { HyperLore.open = null }
            }
            NineLore.open?.let { key ->
                NineOverlay(vm, key, onOpen = { r -> NineLore.navigate?.invoke(r) }) { NineLore.open = null }
            }
            CameraHost()                                   // the app's camera, above everything
            RootOverlayHost()                              // full-screen pages like the photo crop
            OverlayEditorHost()                            // text onto a photo / video
            StoryHost(vm)                                  // stories, full screen
            MediaHost(vm)                                  // big view of photos / videos, tapped rule triggers
            if (vm.screen == Screen.Main) CallHost(vm)     // a call: its screen, or the small window when minimized
            if (vm.updateRequired) UpdateScreen(vm)        // blocks everything until the app is updated
        }
    }
}

val TAB_BAR_HEIGHT = 64.dp

/** Padding for scrolling tab content: below the status bar, above the tab bar. */
@Composable
fun tabContentPadding(): PaddingValues {
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    return PaddingValues(start = 16.dp, end = 16.dp, top = top + 12.dp, bottom = bottom + TAB_BAR_HEIGHT + 24.dp)
}

@Composable
private fun MainScreen(vm: AppViewModel) {
    // the pages live in a pager: tap the bar or swipe sideways; the app starts on the own profile (in a group: that
    // group, showing the profile)
    val layout = layoutOf(vm.state)
    val pages = pagesOf(vm.state, layout)
    val currentLayout = androidx.compose.runtime.rememberUpdatedState(layout)
    val currentPages = androidx.compose.runtime.rememberUpdatedState(pages)
    // the tab each group shows (its first one until another is picked)
    val chosen = remember {
        androidx.compose.runtime.mutableStateMapOf<String, String>().also { m ->
            com.momentum.app.data.TabLayout.locate(layout, "profile")?.group?.let { m[it] = "profile" }
        }
    }
    val pager = rememberPagerState(initialPage = pages.indexOf(pageOfTab(layout, "profile")).coerceAtLeast(0)) { pages.size }
    val page = pages.getOrElse(pager.currentPage) { "profile" }
    val leaf = com.momentum.app.data.TabLayout.leafOf(layout, page, chosen)
    // one scroll state per tab, kept while switching (like Instagram)
    val lists: Map<String, LazyListState> = com.momentum.app.data.TabLayout.STANDARD.associateWith { rememberLazyListState() }
    val stack = remember { mutableStateListOf<Route>() }
    val scope = rememberCoroutineScope()
    /** Goes to where a tab lives (its page in the bar, or its group showing it); false when it's hidden. */
    fun showTab(key: String, instant: Boolean = false): Boolean {
        val l = currentLayout.value
        val p = pageOfTab(l, key) ?: return false
        com.momentum.app.data.TabLayout.locate(l, key)?.group?.let { chosen[it] = key }
        val i = currentPages.value.indexOf(p)
        if (i < 0) return false
        scope.launch { if (instant) pager.scrollToPage(i) else pager.animateScrollToPage(i) }
        return true
    }
    val nav = remember {
        // my own profile opened from anywhere: the profile tab, like tapping it
        fun open(r: Route): Boolean {
            if (r is Route.UserProfile && r.username == vm.state?.user?.username) {
                stack.clear()
                showTab("profile")
                return true
            }
            return false
        }
        Nav(push = { if (!open(it)) stack.add(it) }, pop = { stack.removeLastOrNull() },
            replace = { stack.removeLastOrNull(); if (!open(it)) stack.add(it) })
    }

    // opened from a notification: its tab if it's placed somewhere, the screen on top either way
    LaunchedEffect(vm.openRequest) {
        val r = vm.openRequest ?: return@LaunchedEffect
        vm.openRequest = null
        stack.clear()
        showTab(if (r > 0) "chats" else "feed", instant = true)
        stack.add(if (r > 0) Route.ChatRoom(r) else Route.Notifications)
    }
    LaunchedEffect(Unit) {   // keep unread badges fresh
        while (true) {
            delay(20_000)
            if (vm.foreground) vm.refresh()       // in the background the notification check takes over
        }
    }
    val onProfile = page == pageOfTab(layout, "profile") && leaf == "profile"
    BackHandler(enabled = stack.isNotEmpty() || !onProfile) {
        if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) else showTab("profile")
    }

    // a page came or went (the layout changed, an extension switched on / off, the election): stay on the shown page -
    // or, if that one went away, land on the profile
    val shownPages = remember { arrayOf(pages) }
    LaunchedEffect(pages) {
        val before = shownPages[0]
        shownPages[0] = pages
        if (before == pages) return@LaunchedEffect
        val was = before.getOrNull(pager.currentPage) ?: return@LaunchedEffect
        val i = pages.indexOf(was).takeIf { it >= 0 } ?: pages.indexOf(pageOfTab(layout, "profile"))
        if (i >= 0 && i != pager.currentPage) pager.scrollToPage(i)
    }
    androidx.compose.runtime.SideEffect {
        NineLore.navigate = { nav.push(it) }
        NineLore.openElection = {
            stack.clear()
            showTab(ELECTION)
        }
    }
    CompositionLocalProvider(LocalNav provides nav) {
        Box(Modifier.fillMaxSize()) {
            val state = vm.state ?: return@Box
            HorizontalPager(pager, Modifier.fillMaxSize(), key = { pages.getOrNull(it) ?: "$it" }) { i ->
                val p = pages.getOrElse(i) { "profile" }
                // a page covered by an opened screen (or not the current one) doesn't play videos
                val shown = stack.isEmpty() && pager.currentPage == i
                CompositionLocalProvider(LocalOnTop provides shown) {
                    val groupId = com.momentum.app.data.TabLayout.pageGroupId(p)
                    when {
                        p == ELECTION -> ElectionScreen(vm)
                        groupId != null -> layout.firstOrNull { it.isGroup && it.id == groupId }?.let { g ->
                            val l = com.momentum.app.data.TabLayout.leafOf(layout, p, chosen)
                            GroupPage(vm, state, g, l, shown, lists) { k ->
                                if (k == l) scope.launch { lists[k]?.animateScrollToItem(0) } else chosen[g.id] = k
                            }
                        }
                        else -> TabContent(vm, state, p, lists, shown)
                    }
                }
            }
            if (!vm.online || vm.pending > 0) {
                Row(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(bottom = TAB_BAR_HEIGHT + 10.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color(0xF02B1830))
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PixelIcon("storm", 16.dp)
                    Spacer(Modifier.width(8.dp))
                    val S = LocalStrings.current
                    Text(if (vm.pending > 0) S.offlinePending(vm.pending) else S.offlineNow, style = T.small.copy(color = Px.Text))
                }
            }
            TabBar(page, pages, layout, state, Modifier.align(Alignment.BottomCenter)) { selected ->
                stack.clear()
                scope.launch {
                    // the current page again: its (shown) tab scrolls to the top
                    if (selected == page) leaf?.let { lists[it] }?.animateScrollToItem(0)
                    else pager.animateScrollToPage(pages.indexOf(selected))
                }
            }
            stack.lastOrNull()?.let { route ->
                // the message list and other people's profiles keep the tab bar, like Instagram
                val keepTabs = route == Route.Chats || route is Route.UserProfile || route is Route.Leaderboard
                Box(
                    Modifier
                        .fillMaxSize()
                        .then(if (keepTabs) Modifier.padding(bottom = TAB_BAR_HEIGHT + 1.dp +
                            WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()) else Modifier)
                        .background(Px.Bg)
                        .clickable(remember { MutableInteractionSource() }, indication = null) {},
                ) {
                    key(stack.size, route) { RouteScreen(vm, state, route) }
                }
            }
        }
    }
}

@Composable
private fun RouteScreen(vm: AppViewModel, state: com.momentum.app.data.AppState, route: Route) {
    val S = LocalStrings.current
    when (route) {
        is Route.UserProfile -> ProfileScreen(vm, route.username, isTab = false)
        is Route.PostDetail -> PostDetailScreen(vm, route.id)
        is Route.FollowList -> FollowListScreen(vm, route.username, route.followers)
        Route.BlockedList -> BlockedListScreen(vm)
        Route.Search -> SearchScreen(vm)
        Route.Notifications -> NotificationsScreen(vm)
        Route.Chats -> ChatsScreen(vm)
        is Route.ChatRoom -> ChatRoomScreen(vm, route.id)
        Route.NewPost -> Composer(vm, story = false)
        Route.NewStory -> Composer(vm, story = true)
        Route.NewChat -> NewChatScreen(vm)
        Route.NewGroup -> NewGroupScreen(vm)
        is Route.GroupInfo -> GroupInfoScreen(vm, route.id)
        is Route.ChallengeDetail -> ChallengeDetailScreen(vm, route.id)
        is Route.NewChallenge -> NewChallengeScreen(vm, route.conversationId)
        Route.FollowRequests -> FollowRequestsScreen(vm)
        Route.Report -> ReportScreen(vm)
        is Route.Leaderboard -> LeaderboardScreen(vm, route.scope)
        is Route.ChatInfo -> ChatInfoScreen(vm, route.id)
        Route.E2ERecovery -> RecoveryScreen(vm)
        Route.E2EUnlock -> UnlockScreen(vm)
        Route.ConfirmEmail -> ConfirmEmailScreen(vm, opened = true)
        // the menu under its fixed banner, or one of its pages
        is Route.Settings -> SettingsRoute(vm, state, route.page)
    }
}

/** The bottom bar: one icon per page (a group shows its own icon; the profile the avatar, the election the Larper). */
@Composable
private fun TabBar(current: String, pages: List<String>, layout: List<com.momentum.app.data.TabItem>,
                   state: com.momentum.app.data.AppState, modifier: Modifier, onSelect: (String) -> Unit) {
    Column(modifier.fillMaxWidth().background(Px.Bg)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Px.Line))
        Row(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .height(TAB_BAR_HEIGHT),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            pages.forEach { p ->
                val selected = p == current
                Column(
                    Modifier
                        .weight(1f)
                        .clickable(remember { MutableInteractionSource() }, indication = null) { onSelect(p) }
                        .padding(vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (p == "profile") {
                        // profile tab shows your own avatar, like Instagram
                        Box(Modifier.alpha(if (selected) 1f else .55f).semantics { contentDescription = p }) { Avatar(state.user, 24.dp) }
                    } else if (p == ELECTION) {
                        // the election's tab: the Larper himself, animated, while the vote is on
                        // a bit bigger than the other icons, laid out at their 24 dp so the selection dot stays in line
                        Box(Modifier.size(24.dp).wrapContentSize(unbounded = true), contentAlignment = Alignment.Center) {
                            NineIcon("larper", 28.dp, Modifier.semantics { contentDescription = "election" })
                        }
                    } else {
                        // a group: its own icon, and a badge when one of its tabs has one
                        val group = com.momentum.app.data.TabLayout.pageGroupId(p)?.let { id -> layout.firstOrNull { it.isGroup && it.id == id } }
                        Box(Modifier.semantics { contentDescription = p }) {
                            PixelIcon(group?.icon ?: tabIcon(state, p), 24.dp, alpha = if (selected) 1f else .4f)
                            val badge = group?.tabs?.sumOf { tabBadge(state, it) } ?: tabBadge(state, p)
                            if (badge > 0) Box(Modifier.align(Alignment.TopEnd).offset(x = 4.dp, y = (-2).dp).size(8.dp).background(Px.Orange))
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Box(Modifier.size(4.dp).background(if (selected) Px.Text else Color.Transparent))
                }
            }
        }
    }
}

// ---------------------------------------------------------------- milestone

private class Confetto(val x: Float, val delay: Float, val speed: Float, val dx: Float, val color: Color)

private val CONFETTI_COLORS = listOf(Color(0xFFFFCD75), Color(0xFFEF7D57), Color(0xFFA7F070), Color(0xFF73EFF7),
    Color(0xFFF4F4F4), Color(0xFFB13E53), Color(0xFF41A6F6))

@Composable
private fun MilestoneOverlay(vm: AppViewModel, onClose: () -> Unit) {
    val S = LocalStrings.current
    val meta = vm.meta ?: return
    val state = vm.state ?: return
    val v = meta.vehicles[state.vehicle.index]
    val scope = rememberCoroutineScope()
    val confetti = remember {
        List(70) { Confetto(Random.nextFloat(), Random.nextFloat() * .6f, .35f + Random.nextFloat() * .4f,
            (Random.nextFloat() - .5f) * 120f, CONFETTI_COLORS[it % CONFETTI_COLORS.size]) }
    }
    var t by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        val start = withFrameMillis { it }
        while (t < 4000) t = withFrameMillis { it } - start
    }

    Dialog(onClose, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize()) {
            SceneBackground(sceneFor(v.category), Modifier.matchParentSize(), groundDp = 0f, speed = vehicleSpeed(v.key))
            Column(
                Modifier.align(Alignment.Center).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Label(S.milestoneReached)
                Text(S.levelUp, style = T.block.copy(fontSize = 52.sp, lineHeight = 54.sp), textAlign = TextAlign.Center)
                Spacer(Modifier.height(28.dp))
                VehicleSprite(v.key, 5f, night = v.category != "air")
                Spacer(Modifier.height(28.dp))
                Text("${S.nowDriving} ${S.vehicle(v.key, v.label)}", style = T.bodyStrong.copy(fontSize = 18.sp))
                Spacer(Modifier.height(20.dp))
                // milestones appear in the feed only if the user decides to share them
                PxButton(S.shareMilestone, Modifier.width(260.dp)) {
                    scope.launch {
                        if (vm.req<com.momentum.app.data.PostResponse>("POST", "/posts/milestone",
                                kotlinx.serialization.json.buildJsonObject { put("vehicle", v.key) }) != null) {
                            vm.showToast(S.shared)
                            onClose()
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                PxButton(S.next, Modifier.width(260.dp), style = BtnStyle.Ghost, onClick = onClose)
            }
            Canvas(Modifier.matchParentSize()) {
                val sec = t / 1000f
                confetti.forEach { c ->
                    val p = (sec - c.delay) * c.speed
                    if (p < 0 || p > 1.2f) return@forEach
                    val y = p * size.height * 1.1f - 12.dp.toPx()
                    val x = c.x * size.width + c.dx * p * density
                    drawRect(c.color, Offset(x, y), Size(8.dp.toPx(), 8.dp.toPx()))
                }
            }
        }
    }
}


/** Easter egg at 1,000,000 momentum: the spaceship accelerates and jumps into hyperspace. */
@Composable
private fun HyperspaceOverlay(vm: AppViewModel, onClose: () -> Unit) {
    val S = LocalStrings.current
    val scope = rememberCoroutineScope()
    var t by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        val start = withFrameMillis { it }
        while (t < 6000) t = withFrameMillis { it } - start
    }
    val sec = t / 1000f
    Dialog(onClose, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            // 0-1.6 s: cosmos, ship trembles; then the jump: hyperspace streaks + white flash
            if (sec < 1.6f) BannerBackground("cosmos", Modifier.matchParentSize())
            else BannerBackground(com.momentum.app.ui.HYPERSPACE, Modifier.matchParentSize())
            val shake = if (sec < 1.6f) (kotlin.math.sin(sec * 60f) * sec * 2f) else 0f
            val scale = when {
                sec < 1.6f -> 5f
                sec < 2.2f -> 5f - (sec - 1.6f) * 6f              // shoots into the distance
                else -> 1.4f + kotlin.math.sin(sec * 3f) * .05f
            }
            Box(Modifier.align(Alignment.Center).offset(x = shake.dp)) {
                // the last vehicle (the one that reaches hyperspace)
                VehicleSprite(vm.meta?.vehicles?.lastOrNull()?.key ?: "kreuzer", scale.coerceAtLeast(1f), night = true)
            }
            val flash = when {
                sec in 1.6f..2.6f -> 1f - (sec - 1.6f)
                else -> 0f
            }
            if (flash > 0f) Box(Modifier.matchParentSize().background(Color.White.copy(alpha = flash.coerceIn(0f, 1f))))
            if (sec > 2.4f) {
                Column(
                    Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(start = 24.dp, end = 24.dp, bottom = 36.dp)
                        .alpha(((sec - 2.4f) * 2f).coerceIn(0f, 1f)),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    FitText(S.hyperspaceTitle, T.block, 44.sp, Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    HyperText(S.hyperspaceSub, style = T.body, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(16.dp))
                    PxButton(S.useAsBackground, Modifier.width(280.dp)) {
                        scope.launch {
                            vm.save("PUT", "/settings", kotlinx.serialization.json.buildJsonObject {
                                put("banner_space", com.momentum.app.ui.HYPERSPACE)
                            })
                            onClose()
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    PxButton(S.next, Modifier.width(280.dp), style = BtnStyle.Ghost, onClick = onClose)
                }
            }
        }
    }
}


/** Outdated app: nothing works until the new version is installed (download on the website). */
@Composable
private fun UpdateScreen(vm: AppViewModel) {
    val S = LocalStrings.current
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    BackHandler(enabled = true) {}                       // can't be dismissed
    Box(
        Modifier
            .fillMaxSize()
            .background(Px.Bg)
            .clickable(remember { MutableInteractionSource() }, indication = null) {},
    ) {
        BannerBackground("dusk", Modifier.matchParentSize(), groundDp = 160f, speed = vehicleSpeed("motorroller"))
        VehicleSprite("motorroller", 4f, Modifier.align(Alignment.BottomCenter).padding(bottom = 150.dp), night = true)
        Column(
            Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(start = 28.dp, end = 28.dp, top = 72.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Label(S.updateTag.uppercase(), color = Px.Yellow)
            Spacer(Modifier.height(10.dp))
            FitText(S.updateTitle, T.block, 40.sp, Modifier.fillMaxWidth())
            Spacer(Modifier.height(14.dp))
            Text(S.updateText, style = T.body, textAlign = TextAlign.Center)
            Spacer(Modifier.height(26.dp))
            PxButton(S.updateButton, Modifier.width(280.dp)) { runCatching { uri.openUri(vm.updateUrl) } }
            Spacer(Modifier.height(10.dp))
            Text("v" + com.momentum.app.BuildConfig.VERSION_NAME + (vm.meta?.app?.versionName?.takeIf { it.isNotEmpty() }
                ?.let { "  →  v$it" } ?: ""), style = T.small.copy(fontSize = 12.sp))
        }
    }
}
