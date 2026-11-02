package com.momentum.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.first
import com.momentum.app.data.UserCard

/** Screens that open on top of the tabs (back button / system back closes them). */
sealed interface Route {
    data class UserProfile(val username: String) : Route
    data class PostDetail(val id: Int) : Route
    data class FollowList(val username: String, val followers: Boolean) : Route
    data object Search : Route
    data object BlockedList : Route
    data object Notifications : Route
    data object Chats : Route
    data class ChatRoom(val id: Int) : Route
    data object NewPost : Route
    data object NewStory : Route
    data object NewChat : Route
    data object NewGroup : Route
    data object Settings : Route
    data class GroupInfo(val id: Int) : Route
    data class ChallengeDetail(val id: Int) : Route
    data class NewChallenge(val conversationId: Int) : Route
    data object FollowRequests : Route
    data object Report : Route
    data object Leaderboard : Route
    data class ChatInfo(val id: Int) : Route
    data object E2ERecovery : Route
    data object E2EUnlock : Route
}

class Nav(val push: (Route) -> Unit, val pop: () -> Unit, val replace: (Route) -> Unit)

val LocalNav = staticCompositionLocalOf { Nav({}, {}, {}) }

@Composable
fun TopBar(title: String, onBack: (() -> Unit)? = LocalNav.current.pop, actions: @Composable RowScope.() -> Unit = {}) {
    Row(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .height(56.dp)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) IconBtn("ui_back", onClick = onBack) else Spacer(Modifier.width(10.dp))
        Text(title, style = T.title.copy(fontSize = 20.sp), maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 4.dp))
        actions()
    }
}

@Composable
fun IconBtn(icon: String, badge: Int = 0, alpha: Float = 1f, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(10.dp),
    ) {
        PixelIcon(icon, 24.dp, alpha = alpha)
        if (badge > 0) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 6.dp, y = (-6).dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Px.Orange)
                    .padding(horizontal = 4.dp),
            ) {
                Text(if (badge > 99) "99+" else "$badge", style = T.block.copy(fontSize = 10.sp, color = Color.Black))
            }
        }
    }
}

/** Person in a list: avatar, name, @username, optional trailing action. */
@Composable
fun UserRow(user: UserCard, onClick: () -> Unit, trailing: @Composable () -> Unit = {}) {
    val S = LocalStrings.current
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(user.displayName, user.avatar, 44.dp)
        Spacer(Modifier.width(12.dp))
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(user.username, style = T.bodyStrong, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false))
            if (user.friend) {
                Spacer(Modifier.width(6.dp))
                Label(S.friend, color = Px.Lime)
            }
        }
        trailing()
    }
}

/** Follow / Following / Follow back. */
@Composable
fun FollowButton(user: UserCard, small: Boolean = true, modifier: Modifier = Modifier, requested: Boolean = false, onToggle: () -> Unit) {
    val S = LocalStrings.current
    if (user.isMe) return
    val muted = user.following || requested
    val label = when {
        user.following -> S.followingButton
        requested -> S.requested
        user.followsMe -> S.followBack
        else -> S.follow
    }
    Box(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (muted) Px.Faint else Px.Text)
            .clickable(onClick = onToggle)
            .padding(horizontal = if (small) 14.dp else 18.dp, vertical = if (small) 9.dp else 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label.uppercase(), style = T.button.copy(color = if (muted) Px.Text else Color.Black,
            fontWeight = FontWeight.Normal))
    }
}

/** Heart that toggles on tap and shows who liked on long press. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun LikeHeart(liked: Boolean, size: androidx.compose.ui.unit.Dp = 24.dp, onToggle: () -> Unit, onShowLikers: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(onClick = onToggle, onLongClick = onShowLikers)
            .padding(10.dp),
    ) {
        if (liked) PixelIcon("heart", size) else PixelIcon("ui_heart", size)
    }
}

/** Bottom sheet listing the people who liked a post or comment (path like "/posts/3/likes"). */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun LikersSheet(vm: com.momentum.app.AppViewModel, path: String, onDismiss: () -> Unit) {
    val S = LocalStrings.current
    val nav = LocalNav.current
    val pages = androidx.compose.runtime.remember(path) {
        UserPages { offset, limit -> vm.req<com.momentum.app.data.UsersResponse>("GET", "$path?offset=$offset&limit=$limit")?.users }
    }
    androidx.compose.runtime.LaunchedEffect(path) { pages.reset() }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    LoadMoreEffect(listState, pages.users?.size ?: 0, pages.hasMore) { pages.more() }
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Px.Sheet) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Text(S.likedBy, style = T.title.copy(fontSize = 20.sp), modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            androidx.compose.foundation.lazy.LazyColumn(state = listState, modifier = Modifier.heightIn(max = 520.dp)) {
                items(pages.users.orEmpty().size, key = { pages.users!![it].username }) { i ->
                    val u = pages.users!![i]
                    UserRow(u, onClick = { onDismiss(); nav.push(Route.UserProfile(u.username)) })
                }
            }
            if (pages.users?.isEmpty() == true) Text("—", style = T.small, modifier = Modifier.padding(20.dp))
        }
    }
}

/** Thin strip under a top bar: the content is cached and not updating. */
@Composable
fun OfflineStrip(online: Boolean) {
    if (online) return
    val S = LocalStrings.current
    Row(
        Modifier.fillMaxWidth().background(Px.Orange.copy(alpha = .16f)).padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PixelIcon("storm", 16.dp)
        Spacer(Modifier.width(8.dp))
        Text(S.offlineStale, style = T.small.copy(color = Px.Yellow))
    }
}

/** Whole area needs the internet and nothing is cached. */
@Composable
fun OfflineState() {
    val S = LocalStrings.current
    Column(Modifier.fillMaxWidth().padding(vertical = 48.dp, horizontal = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        PixelIcon("storm", 64.dp)
        Spacer(Modifier.height(14.dp))
        Text(S.offlineTitle, style = T.bodyStrong.copy(fontSize = 18.sp))
        Text(S.offlineSub, style = T.small, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp))
    }
}

/** Grey centered hint for empty lists. */
@Composable
fun EmptyHint(title: String, sub: String? = null, icon: String = "chat") {
    Column(Modifier.fillMaxWidth().padding(vertical = 48.dp, horizontal = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        PixelIcon(icon, 48.dp, alpha = .8f)
        Spacer(Modifier.height(12.dp))
        Text(title, style = T.bodyStrong.copy(fontSize = 18.sp))
        if (sub != null) Text(sub, style = T.small, modifier = Modifier.padding(top = 4.dp))
    }
}


private val MENTION_TAIL = Regex("(^|\\s)@([a-z0-9_.]{0,30})$", RegexOption.IGNORE_CASE)

/**
 * Suggestions while typing "@name" at the end of [text] (like Instagram): exact match first,
 * then people I follow / who follow me (ranked by the server). Picking completes the name.
 */
@Composable
fun MentionSuggestions(vm: com.momentum.app.AppViewModel, text: String, modifier: Modifier = Modifier,
                       /** extra query, e.g. "&members_of=<chat>" to only suggest a chat's members */
                       scope: String = "", onPick: (String) -> Unit) {
    val query = MENTION_TAIL.find(text)?.groupValues?.get(2)
    var users by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<List<UserCard>>(emptyList()) }
    androidx.compose.runtime.LaunchedEffect(query) {
        if (query == null || !vm.online) { users = emptyList(); return@LaunchedEffect }
        kotlinx.coroutines.delay(150)          // debounce typing
        users = vm.req<com.momentum.app.data.UsersResponse>("GET", "/mentions?q=" + java.net.URLEncoder.encode(query, "UTF-8") + scope)
            ?.users.orEmpty()
    }
    if (query == null || users.isEmpty()) return
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Px.Card2).padding(vertical = 4.dp)) {
        users.take(5).forEach { u ->
            Row(
                Modifier.fillMaxWidth().clickable { onPick(text.dropLast(query.length) + u.username + " ") }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Avatar(u.displayName, u.avatar, 28.dp)
                Spacer(Modifier.width(10.dp))
                Text(u.username, style = T.bodyStrong, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (u.friend || u.following) {
                    Spacer(Modifier.width(8.dp))
                    Label(if (u.friend) LocalStrings.current.friend else LocalStrings.current.followingButton,
                        color = if (u.friend) Px.Lime else Px.Muted)
                }
            }
        }
    }
}


/**
 * Infinite scroll: calls [load] once the list shows one of its last [threshold] items. [count] is
 * the number of loaded items; when it changes (a page arrived) the watch starts again.
 */
@Composable
fun LoadMoreEffect(state: androidx.compose.foundation.lazy.LazyListState, count: Int, hasMore: Boolean,
                   threshold: Int = 6, load: suspend () -> Unit) {
    androidx.compose.runtime.LaunchedEffect(state, count, hasMore) {
        if (!hasMore || count == 0) return@LaunchedEffect
        androidx.compose.runtime.snapshotFlow { state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .first { it >= state.layoutInfo.totalItemsCount - threshold }
        load()
    }
}

/** Pages of [size] users (offset based) for long people lists. */
class UserPages(private val size: Int = 30, private val fetch: suspend (offset: Int, limit: Int) -> List<UserCard>?) {
    var users by androidx.compose.runtime.mutableStateOf<List<UserCard>?>(null)
    var hasMore by androidx.compose.runtime.mutableStateOf(true)
    private var loading = false

    suspend fun reset() { users = null; hasMore = true; more() }

    suspend fun more() {
        if (loading || !hasMore) return
        loading = true
        val page = fetch(users?.size ?: 0, size)
        if (page != null) {
            users = (users.orEmpty() + page).distinctBy { it.username }
            hasMore = page.size >= size
        } else if (users == null) users = emptyList()
        loading = false
    }

    fun update(u: UserCard) { users = users?.map { if (it.username == u.username) u else it } }
}
