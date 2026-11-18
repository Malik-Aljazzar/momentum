package com.momentum.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.momentum.app.AppViewModel
import com.momentum.app.data.AppState
import com.momentum.app.data.TabItem
import com.momentum.app.data.TabLayout

// The main screen's pages (server/tablayout.py, data/TabLayout.kt; = the PWA's pager in web/src/app.tsx): one per
// place in the bottom bar - a tab, or a group whose page has a tab menu at the top - and the Larper election's while it
// runs (server/nine.py), at the end.

const val ELECTION = "election"

/** My tabs as shown now. */
fun layoutOf(st: AppState?): List<TabItem> =
    TabLayout.effectiveLayout(st?.settings?.tabs, TabLayout.availableTabs(st?.extensions))

/** The pager's pages: the layout's places, then the election while it runs. */
fun pagesOf(st: AppState?, layout: List<TabItem>): List<String> =
    TabLayout.pagesWith(layout, st?.user?.election == true)

/** The page a tab lives on ([key] = a tab key or [ELECTION]); null when it's hidden. */
fun pageOfTab(layout: List<TabItem>, key: String): String? =
    if (key == ELECTION) ELECTION else TabLayout.locate(layout, key)?.let { at -> at.group?.let(TabLayout::groupPage) ?: key }

/** A tab's pixel icon (the profile shows the avatar instead: [TabGlyph]). */
fun tabIcon(st: AppState, key: String) = when (key) {
    "rules" -> "ui_updown"
    "chats" -> "ui_chat"
    "feed" -> "ui_feed"
    else -> TabLayout.extTab(st.extensions, key)?.icon ?: "ui_dots"
}

/** A tab's name: the standard ones in the app's words, an extension's tab by its manifest. */
@Composable
fun tabName(st: AppState, key: String, lang: String): String {
    val S = LocalStrings.current
    return when (key) {
        "rules" -> S.rulesTitle
        "chats" -> S.chats
        "feed" -> S.feed
        "profile" -> S.profile
        else -> TabLayout.extTab(st.extensions, key)?.name?.of(lang) ?: key
    }
}

/** The badge a tab carries: unread messages (chats), new activity (feed). */
fun tabBadge(st: AppState, key: String) = when (key) {
    "chats" -> st.unread.messages
    "feed" -> st.unread.notifications
    else -> 0
}

/** A tab's icon: the avatar for the profile, else its pixel glyph. */
@Composable
fun TabGlyph(st: AppState, key: String, size: Dp, alpha: Float) {
    if (key == "profile") Box(Modifier.alpha(if (alpha < 1f) .55f else 1f)) { Avatar(st.user, size) }
    else PixelIcon(tabIcon(st, key), size, alpha = alpha)
}

/** Kept but out of the way: laid out at full size (lists keep their place), placed far off-screen (not drawn, no
 *  touches). */
private fun Modifier.shownIf(on: Boolean) = layout { measurable, constraints ->
    val p = measurable.measure(constraints)
    layout(p.width, p.height) { if (on) p.place(0, 0) else p.place(100_000, 0) }
}

/** One tab's screen (in the bar or in a group). */
@Composable
fun TabContent(vm: AppViewModel, st: AppState, key: String, lists: Map<String, LazyListState>, visible: Boolean) {
    when (key) {
        "rules" -> RulesScreen(vm, st, lists.getValue("rules"))
        "chats" -> ChatsScreen(vm, isTab = true, listState = lists.getValue("chats"))
        "feed" -> FeedScreen(vm, lists.getValue("feed"))
        "profile" -> ProfileScreen(vm, st.user.username, isTab = true, list = lists.getValue("profile"))
        else -> TabLayout.parseExtKey(key)?.let { (ext, tab) -> ExtensionTab(vm, ext, tab, visible) }
    }
}

/**
 * A group's page: its tab menu at the top (it takes the status bar), the chosen tab below. Tabs are composed when first
 * chosen and kept while switching (their state stays). [onPick]: a tab in the menu was tapped.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GroupPage(vm: AppViewModel, st: AppState, group: TabItem, leaf: String?, visible: Boolean,
              lists: Map<String, LazyListState>, onPick: (String) -> Unit) {
    val S = LocalStrings.current
    Column(Modifier.fillMaxSize()) {
        GroupTabMenu(vm, st, group, leaf, onPick)
        Box(Modifier.weight(1f).fillMaxWidth().consumeWindowInsets(WindowInsets.statusBars)) {
            if (leaf == null) {
                EmptyHint(S.tabsGroupEmpty, icon = group.icon)
                return@Box
            }
            val visited = remember(group.id) { LinkedHashSet<String>() }
            visited += leaf
            visited.filter { it in group.tabs }.forEach { k ->
                key(k) {
                    val on = k == leaf
                    Box(Modifier.fillMaxSize().shownIf(on)) {
                        CompositionLocalProvider(LocalOnTop provides (visible && on)) {
                            TabContent(vm, st, k, lists, visible && on)
                        }
                    }
                }
            }
        }
    }
}

/**
 * A group's tab menu, built like the profile's section tabs: the group's tabs as names, icons or both (the group's
 * `show`), the chosen one underlined. Never cut off: each takes at least an equal share of the width, and the row
 * scrolls sideways when they don't fit (like the ranking's scopes).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GroupTabMenu(vm: AppViewModel, st: AppState, group: TabItem, current: String?, onPick: (String) -> Unit) {
    val lang = vm.lang.code
    Column(Modifier.fillMaxWidth().background(Px.Bg).statusBarsPadding()) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val share = if (group.tabs.isEmpty()) maxWidth else maxWidth / group.tabs.size
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                group.tabs.forEach { k ->
                    val on = k == current
                    val bring = remember { BringIntoViewRequester() }
                    LaunchedEffect(on) { if (on) bring.bringIntoView() }
                    val alpha = if (on) 1f else .4f
                    Column(
                        Modifier
                            .widthIn(min = share)
                            .bringIntoViewRequester(bring)
                            .clickable { onPick(k) }
                            .semantics { contentDescription = "group tab $k" }
                            .drawBehind {
                                if (on) drawRect(Px.Text, Offset(0f, size.height - 2.dp.toPx()), Size(size.width, 2.dp.toPx()))
                            }
                            .padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box {
                            Row(Modifier.height(24.dp), verticalAlignment = Alignment.CenterVertically) {
                                if (group.show != "names") TabGlyph(st, k, 20.dp, alpha)
                                if (group.show == "both") Spacer(Modifier.width(8.dp))
                                if (group.show != "icons") Text(tabName(st, k, lang).uppercase(), maxLines = 1, softWrap = false,
                                    style = T.button.copy(color = Px.Text.copy(alpha = alpha)))
                            }
                            if (tabBadge(st, k) > 0) Box(Modifier.align(Alignment.TopEnd).offset(x = 6.dp, y = (-2).dp).size(8.dp).background(Px.Orange))
                        }
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Px.Line))
    }
}
