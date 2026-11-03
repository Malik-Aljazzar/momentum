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
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
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

// accelerators and decelerators share one tab; messages sit in the middle
enum class Tab(val icon: String) { Home("ui_home"), Rules("ui_updown"), Chats("ui_chat"), Feed("ui_feed"), Profile("") }

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
            if (vm.milestone && !vm.hyperspace) MilestoneOverlay(vm) { vm.milestone = false }
            if (vm.hyperspace) HyperspaceOverlay(vm) { vm.hyperspace = false; vm.milestone = false }
            val golden = vm.state?.user?.hyperspace == true
            androidx.compose.runtime.SideEffect { HyperLore.golden = golden }
            HyperLore.open?.let { key ->
                val u = vm.state?.user
                HyperLoreOverlay(key, if (key == HYPERSPACE) u?.hyperspace == true else u?.ultra == true) { HyperLore.open = null }
            }
            CameraHost()                                   // the app's camera, above everything
            RootOverlayHost()                              // full-screen pages like the photo crop
            OverlayEditorHost()                            // text onto a photo / video
            StoryHost(vm)                                  // stories, full screen
            MediaHost(vm)                                  // big view of photos / videos, tapped rule triggers
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
    // tabs live in a pager: tap the bar or swipe sideways
    val pager = rememberPagerState { Tab.entries.size }
    val tab = Tab.entries[pager.currentPage]
    // one scroll state per tab, kept while switching (like Instagram)
    val lists: Map<Tab, LazyListState> = Tab.entries.associateWith { rememberLazyListState() }
    val stack = remember { mutableStateListOf<Route>() }
    val scope = rememberCoroutineScope()
    val nav = remember {
        // my own profile opened from anywhere: the profile tab, like tapping it
        fun open(r: Route): Boolean {
            if (r is Route.UserProfile && r.username == vm.state?.user?.username) {
                stack.clear()
                scope.launch { pager.animateScrollToPage(Tab.Profile.ordinal) }
                return true
            }
            return false
        }
        Nav(push = { if (!open(it)) stack.add(it) }, pop = { stack.removeLastOrNull() },
            replace = { stack.removeLastOrNull(); if (!open(it)) stack.add(it) })
    }

    // opened from a notification
    LaunchedEffect(vm.openRequest) {
        val r = vm.openRequest ?: return@LaunchedEffect
        vm.openRequest = null
        stack.clear()
        pager.scrollToPage(if (r > 0) Tab.Chats.ordinal else Tab.Feed.ordinal)
        stack.add(if (r > 0) Route.ChatRoom(r) else Route.Notifications)
    }
    LaunchedEffect(Unit) {   // keep unread badges fresh
        while (true) {
            delay(20_000)
            if (vm.foreground) vm.refresh()       // in the background the notification check takes over
        }
    }
    BackHandler(enabled = stack.isNotEmpty() || tab != Tab.Home) {
        if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) else scope.launch { pager.animateScrollToPage(0) }
    }

    CompositionLocalProvider(LocalNav provides nav) {
        Box(Modifier.fillMaxSize()) {
            val state = vm.state ?: return@Box
            HorizontalPager(pager, Modifier.fillMaxSize(), key = { it }) { page ->
                // a tab covered by an opened screen (or not the current one) doesn't play videos
                CompositionLocalProvider(LocalOnTop provides (stack.isEmpty() && pager.currentPage == page)) {
                when (Tab.entries[page]) {
                    Tab.Home -> HomeScreen(vm, state, lists.getValue(Tab.Home))
                    Tab.Rules -> RulesScreen(vm, state, lists.getValue(Tab.Rules))
                    Tab.Chats -> ChatsScreen(vm, isTab = true, listState = lists.getValue(Tab.Chats))
                    Tab.Feed -> FeedScreen(vm, lists.getValue(Tab.Feed))
                    Tab.Profile -> ProfileScreen(vm, state.user.username, isTab = true, list = lists.getValue(Tab.Profile))
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
            TabBar(tab, state, Modifier.align(Alignment.BottomCenter)) { selected ->
                stack.clear()
                scope.launch {
                    if (selected == tab) lists.getValue(tab).animateScrollToItem(0)
                    else pager.animateScrollToPage(selected.ordinal)
                }
            }
            stack.lastOrNull()?.let { route ->
                // the message list and other people's profiles keep the tab bar, like Instagram
                val keepTabs = route == Route.Chats || route is Route.UserProfile || route == Route.Leaderboard
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
        Route.Leaderboard -> LeaderboardScreen(vm)
        is Route.ChatInfo -> ChatInfoScreen(vm, route.id)
        Route.E2ERecovery -> RecoveryScreen(vm)
        Route.E2EUnlock -> UnlockScreen(vm)
        Route.ConfirmEmail -> ConfirmEmailScreen(vm, opened = true)
        Route.Settings -> Column(Modifier.fillMaxSize()) {
            // fixed banner; only the settings below scroll
            Box(
                Modifier
                    .statusBarsPadding()
                    .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp)
                    .fillMaxWidth()
                    .height(120.dp)
                    .clip(CardShape),
            ) {
                SceneBackground(Scene.Settings, Modifier.matchParentSize())
                Box(Modifier.padding(6.dp)) { IconBtn("ui_back", onClick = LocalNav.current.pop) }
                Column(Modifier.align(Alignment.BottomStart).padding(20.dp)) {
                    Label(S.settingsTag)
                    FitText(S.settingsTitle, T.title, 28.sp, Modifier.fillMaxWidth())
                }
            }
            Box(Modifier.weight(1f)) { SettingsScreen(vm, state, rememberLazyListState(), overlay = true) }
        }
    }
}

@Composable
private fun TabBar(current: Tab, state: com.momentum.app.data.AppState, modifier: Modifier, onSelect: (Tab) -> Unit) {
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
            Tab.entries.forEach { t ->
                val selected = t == current
                Column(
                    Modifier
                        .weight(1f)
                        .clickable(remember { MutableInteractionSource() }, indication = null) { onSelect(t) }
                        .padding(vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (t == Tab.Profile) {
                        // profile tab shows your own avatar, like Instagram
                        Box(Modifier.alpha(if (selected) 1f else .55f)) { Avatar(state.user, 24.dp) }
                    } else {
                        Box {
                            PixelIcon(t.icon, 24.dp, alpha = if (selected) 1f else .4f)
                            val badge = when (t) {
                                Tab.Chats -> state.unread.messages
                                Tab.Feed -> state.unread.notifications
                                else -> 0
                            }
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
            SceneBackground(sceneFor(v.category), Modifier.matchParentSize(), groundDp = 0f)
            Column(
                Modifier.align(Alignment.Center).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Label(S.milestoneReached)
                Text(S.levelUp, style = T.block.copy(fontSize = 52.sp, lineHeight = 54.sp), textAlign = TextAlign.Center)
                Spacer(Modifier.height(28.dp))
                VehicleSprite(v.key, 5f)
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
                VehicleSprite(vm.meta?.vehicles?.lastOrNull()?.key ?: "kreuzer", scale.coerceAtLeast(1f))
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
        BannerBackground("dusk", Modifier.matchParentSize(), groundDp = 160f)
        VehicleSprite("motorroller", 4f, Modifier.align(Alignment.BottomCenter).padding(bottom = 150.dp))
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
