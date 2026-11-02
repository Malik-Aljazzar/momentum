package com.momentum.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.momentum.app.AppViewModel
import com.momentum.app.data.LeaderboardResponse
import com.momentum.app.data.RankEntry
import com.momentum.app.fmt

/** Ranked by the momentum, or by what changed today since 0:00 Berlin (reports #40, #45); kept while switching
 *  scopes. */
object RankPeriod {
    var day by mutableStateOf(false)
    val query get() = if (day) "&period=today" else ""
}

/** "+120" / "−40" for the 24 h ranking. */
fun signedShort(n: Double) = (if (n >= 0) "+" else "−") + com.momentum.app.fmtShort(kotlin.math.abs(n))

private fun deltaColor(n: Double) = if (n > 0) Px.Lime else if (n < 0) Px.Orange else Px.Muted

/** Total | 24 h */
@Composable
fun PeriodSwitch(modifier: Modifier = Modifier) {
    val S = LocalStrings.current
    Row(modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(false to S.periodTotal, true to S.period24h).forEach { (day, label) ->
            val on = RankPeriod.day == day
            Box(Modifier.clip(RoundedCornerShape(10.dp)).background(if (on) Px.Yellow else Px.Faint)
                .clickable { RankPeriod.day = day }.padding(horizontal = 12.dp, vertical = 6.dp)) {
                Text(label.uppercase(), style = T.button.copy(fontSize = 12.sp, color = if (on) Color.Black else Px.Text))
            }
        }
    }
}

/** World top 10 with my own place, or me among the people I follow and who follow me. */
@Composable
fun LeaderboardScreen(vm: AppViewModel) {
    val S = LocalStrings.current
    val nav = LocalNav.current
    var scope by rememberSaveable { mutableStateOf("global") }
    var data by remember { mutableStateOf<LeaderboardResponse?>(null) }
    var more by remember { mutableStateOf(false) }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(scope, RankPeriod.day) {
        data = null
        if (scope == "groups") return@LaunchedEffect
        data = vm.req<LeaderboardResponse>("GET", "/leaderboard?scope=$scope&limit=50${RankPeriod.query}")
        more = scope == "friends" && (data?.entries?.size ?: 0) >= 50
    }
    LoadMoreEffect(listState, data?.entries?.size ?: 0, more) {
        val d = data ?: return@LoadMoreEffect
        vm.req<LeaderboardResponse>("GET", "/leaderboard?scope=friends&limit=50&offset=${d.entries.size}${RankPeriod.query}")?.let {
            data = d.copy(entries = (d.entries + it.entries).distinctBy { e -> e.user.username }); more = it.entries.size >= 50
        }
    }
    Column(Modifier.fillMaxSize()) {
        TopBar(S.leaderboardTitle)
        OfflineStrip(vm.online)
        Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp).clip(RoundedCornerShape(14.dp)).background(Px.Faint).padding(4.dp)) {
            listOf("global" to S.scopeGlobal, "friends" to S.scopeFriends, "groups" to S.groupsTitle).forEach { (k, label) ->
                val on = k == scope
                Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(if (on) Px.Text else Color.Transparent)
                        .clickable { scope = k }.padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) { Text(label.uppercase(), style = T.button.copy(color = if (on) Color.Black else Px.Text)) }
            }
        }
        PeriodSwitch(Modifier.padding(bottom = 6.dp))
        if (scope == "groups") { androidx.compose.runtime.key(RankPeriod.day) { GroupLeaderboard(vm) }; return@Column }
        val d = data ?: run { if (!vm.online) OfflineState(); return@Column }
        // my place stays visible above the list
        d.me?.let { me ->
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().clip(CardShape)
                .background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(Color(0xFF3B2A12), Color(0xFF1B1520))))
                .padding(16.dp)) {
                Label(S.yourPlace.uppercase(), color = Px.Yellow)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("${me.rank}.", style = T.block.copy(fontSize = 34.sp))     // the pixel font has no "#"
                    Spacer(Modifier.width(12.dp))
                    Text(me.delta?.let { signedShort(it) + " · " + S.period24h } ?: (fmt(me.momentum) + " Momentum"),
                        style = T.body.copy(color = Px.Text), modifier = Modifier.padding(bottom = 6.dp))
                }
                val total = d.total.takeIf { it > 0 } ?: d.entries.size
                Text(S.placeOf(me.rank, total) + if (!me.competing) " · " + S.paused else "", style = T.small)
            }
        }
        // virtual list: only visible rows are composed
        LazyColumn(state = listState, contentPadding = PaddingValues(bottom = TAB_BAR_HEIGHT + 24.dp)) {
            items(d.entries, key = { it.user.username }) { e -> RankRow(e) { nav.push(Route.UserProfile(e.user.username)) } }
        }
    }
}

@Composable
fun RankRow(e: RankEntry, onClick: () -> Unit) {
    val S = LocalStrings.current
    val u = e.user
    Row(
        Modifier.fillMaxWidth().background(if (u.isMe) Px.Faint else Color.Transparent).clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("${e.rank}", style = T.block.copy(fontSize = 16.sp, color = if (e.rank <= 3) Px.Yellow else Px.Muted),
            modifier = Modifier.width(36.dp))
        Avatar(u.displayName, u.avatar, 36.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(u.username, style = T.bodyStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!e.competing) Text(S.paused, style = T.small.copy(fontSize = 12.sp))
        }
        if (e.rank == 1) { PixelIcon("trophy", 18.dp); Spacer(Modifier.width(8.dp)) }
        val delta = e.delta
        if (delta != null) Text(signedShort(delta), style = T.block.copy(fontSize = 16.sp, color = deltaColor(delta)))
        else Text(com.momentum.app.fmtShort(e.momentum), style = T.block.copy(fontSize = 16.sp, color = if (e.competing) Px.Text else Px.Muted))
    }
}


/** Public groups worldwide, by the total momentum of their members; my groups stand out. */
@Composable
private fun GroupLeaderboard(vm: AppViewModel) {
    val S = LocalStrings.current
    val nav = LocalNav.current
    var data by remember { mutableStateOf<com.momentum.app.data.GroupRankingResponse?>(null) }
    var more by remember { mutableStateOf(false) }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(Unit) {
        data = vm.req<com.momentum.app.data.GroupRankingResponse>("GET", "/leaderboard?scope=groups&limit=50${RankPeriod.query}")
        more = (data?.groups?.size ?: 0) >= 50
    }
    LoadMoreEffect(listState, data?.groups?.size ?: 0, more) {
        val d = data ?: return@LoadMoreEffect
        vm.req<com.momentum.app.data.GroupRankingResponse>("GET", "/leaderboard?scope=groups&limit=50&offset=${d.groups.size}${RankPeriod.query}")?.let {
            data = d.copy(groups = (d.groups + it.groups).distinctBy { g -> g.id }); more = it.groups.size >= 50
        }
    }
    val d = data ?: run { if (!vm.online) OfflineState(); return }
    if (d.groups.isEmpty()) { EmptyHint(S.noPublicGroups, icon = "ui_group"); return }
    LazyColumn(state = listState, contentPadding = PaddingValues(bottom = TAB_BAR_HEIGHT + 24.dp)) {
        items(d.groups, key = { it.id }) { gr ->
            Row(
                Modifier.fillMaxWidth().background(if (gr.member) Px.Faint else Color.Transparent)
                    .clickable { nav.push(Route.GroupInfo(gr.id)) }.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("${gr.rank}", style = T.block.copy(fontSize = 16.sp, color = if (gr.rank <= 3) Px.Yellow else Px.Muted),
                    modifier = Modifier.width(36.dp))
                Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp))) {
                    BannerBackground(gr.banner ?: "dusk", Modifier.matchParentSize(), still = true)
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(gr.name, style = T.bodyStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(S.members(gr.members), style = T.small.copy(fontSize = 12.sp))
                }
                if (gr.rank == 1) { PixelIcon("trophy", 18.dp); Spacer(Modifier.width(8.dp)) }
                val delta = gr.delta
                if (delta != null) Text(signedShort(delta), style = T.block.copy(fontSize = 16.sp, color = deltaColor(delta)))
                else Text(com.momentum.app.fmtShort(gr.total), style = T.block.copy(fontSize = 16.sp))
            }
        }
    }
}
