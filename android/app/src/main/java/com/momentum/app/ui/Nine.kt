package com.momentum.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.momentum.app.AppViewModel
import com.momentum.app.data.ElectionResponse
import com.momentum.app.data.ElectionState
import com.momentum.app.data.NineResponse
import com.momentum.app.data.NineTitle
import com.momentum.app.data.UserCard
import com.momentum.app.fmt
import kotlinx.coroutines.delay
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * One of the Nine (web/src/screens/Nine.tsx): the nine titles (server/nine.py) - their animated creatures, the
 * title screens (each with its own colour and animated sky), the rankings' "The Nine" tab and the Larper election.
 */
object NineLore {
    /** a title key while its screen is open (shown above everything by App.kt) */
    var open by mutableStateOf<String?>(null)
    /** opens a route from the screen (set by the main screen, which owns the navigation) */
    var navigate: ((Route) -> Unit)? = null
    /** switches to the election's tab (there while it runs) */
    var openElection: (() -> Unit)? = null
}

/** The critters' sheets: NINE_PX square, NINE_FRAMES frames of NINE_FRAME_MS (pixelart/nine.py). */
const val NINE_PX = 20
const val NINE_FRAMES = 8
const val NINE_FRAME_MS = 120

/** The title's animated creature. */
@Composable
fun NineIcon(key: String, size: Dp, modifier: Modifier = Modifier) {
    val sheet = rememberAsset("nine_$key/sheet.png")
    val s = pixelScale(size.value / NINE_PX)
    var frame by remember { mutableIntStateOf(0) }
    LaunchedEffect(key) {
        while (true) {
            delay(NINE_FRAME_MS.toLong())
            frame = (frame + 1) % NINE_FRAMES
        }
    }
    val d = with(LocalDensity.current) { (NINE_PX * s).toDp() }
    Canvas(modifier.size(d)) {
        drawPixels(sheet, IntOffset(frame * NINE_PX, 0), IntSize(NINE_PX, NINE_PX), IntOffset.Zero, s)
    }
}

private fun unit(S: Strings, key: String, n: Double) = S.nineUnit(key, fmt(n))

private fun dateOf(iso: String, lang: Lang): String = runCatching {
    java.time.Instant.parse(iso).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        .format(java.time.format.DateTimeFormatter.ofPattern(if (lang.code == "de") "d. MMMM" else "d MMMM", java.util.Locale.forLanguageTag(lang.code)))
}.getOrDefault(iso.take(10))

/** "Du: 12 Kommentare · Platz 3 weltweit", or that nothing is counted yet. */
@Composable
private fun Mine(t: NineTitle) {
    val S = LocalStrings.current
    if (t.key == "larper") return
    val m = t.mine
    Text(if (m != null) S.nineMine(unit(S, t.key, m.value), m.rank) else S.nineNotCounted, style = T.small.copy(color = Px.Text))
}

/** The title screen: its creature, name, the (tall) tale, how to get it, who holds it and where I stand. */
@Composable
fun NineOverlay(vm: AppViewModel, key: String, onOpen: (Route) -> Unit, onClose: () -> Unit) {
    val S = LocalStrings.current
    val color = NINE_COLORS[key] ?: Px.Yellow
    BackHandler { onClose() }
    var data by remember { mutableStateOf<NineResponse?>(null) }
    LaunchedEffect(key) { data = vm.req<NineResponse>("GET", "/nine") }
    val clock = rememberClock()
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { keyboard?.hide() }
    val lore = S.nineLore(key)
    val shown = ((clock.value - .4f) / 2.6f * lore.length).toInt().coerceIn(0, lore.length)
    val t = data?.titles?.firstOrNull { it.key == key }
    val e = data?.election
    val shift = clock.value * 240f
    // closes with the X (pinned: stays while the tale scrolls) or a tap on the free space, like the Hyperspace screen
    Box(Modifier.fillMaxSize().background(Color.Black).clickable(remember { MutableInteractionSource() }, indication = null) { onClose() }) {
        BannerBackground("nine_$key", Modifier.fillMaxSize())
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(horizontal = 22.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(end = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                Label(S.oneOfNine.uppercase(), color = color)
            }
            Spacer(Modifier.height(10.dp))
            NineIcon(key, 160.dp)
            Spacer(Modifier.height(14.dp))
            Text(S.nineName(key).uppercase(), style = T.block.copy(fontSize = 30.sp, color = color), textAlign = TextAlign.Center)
            Spacer(Modifier.height(14.dp))
            Text(
                androidx.compose.ui.text.buildAnnotatedString {
                    append(lore.take(shown))
                    pushStyle(androidx.compose.ui.text.SpanStyle(brush = androidx.compose.ui.graphics.SolidColor(Color.Transparent)))
                    append(lore.drop(shown))
                    pop()
                },
                style = T.title.copy(
                    fontSize = 19.sp, lineHeight = 27.sp, textAlign = TextAlign.Center,
                    brush = Brush.linearGradient(listOf(Color.White, color, Color.White, color, Color.White),
                        start = androidx.compose.ui.geometry.Offset(shift - 500f, 0f), end = androidx.compose.ui.geometry.Offset(shift, 120f),
                        tileMode = androidx.compose.ui.graphics.TileMode.Mirror),
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(18.dp))
            NineCard(color) {
                Label(S.nineHowTitle.uppercase(), color = color)
                Spacer(Modifier.height(6.dp))
                Text(S.nineHow(key), style = T.body)
            }
            Spacer(Modifier.height(12.dp))
            NineCard(color) {
                Label(S.nineHolder.uppercase(), color = color)
                Spacer(Modifier.height(8.dp))
                val holder = t?.holder
                if (data == null) {
                    if (!vm.online) OfflineState()
                } else if (holder != null) {
                    Row(Modifier.fillMaxWidth().clickable { onClose(); onOpen(Route.UserProfile(holder.username)) },
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Avatar(holder.displayName, holder.avatar, 40.dp)
                        Column(Modifier.weight(1f)) {
                            Text(holder.username, style = T.bodyStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            t.value?.let { Text(unit(S, key, it), style = T.small) }
                        }
                        PixelIcon("trophy", 18.dp)
                    }
                } else {
                    Text(S.nineNobody, style = T.body.copy(color = Px.Muted))
                }
                if (t != null) { Spacer(Modifier.height(6.dp)); Mine(t) }
                if (key == "larper" && e != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (e.open) S.electionVotes(e.voters, e.needed) + (e.closesAt?.let { " · " + S.electionEnds(dateOf(it, vm.lang)) } ?: "")
                        else e.nextAt?.let { S.electionNext(dateOf(it, vm.lang)) } ?: S.electionClosed,
                        style = T.small.copy(color = Px.Text),
                    )
                    if (e.open) {
                        Spacer(Modifier.height(10.dp))
                        PxButton(S.electionOpenAction) { onClose(); NineLore.openElection?.invoke() }
                    }
                }
            }
            Spacer(Modifier.height(40.dp))
        }
        Box(
            Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = 12.dp, end = 12.dp).size(48.dp).clip(CircleShape)
                .background(Color(0x8C000000)).clickable { onClose() }.semantics { contentDescription = "close" },
            contentAlignment = Alignment.Center,
        ) { PixelIcon("ui_close", 20.dp) }
    }
}

@Composable
private fun NineCard(color: Color, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0x73000000))
            .border(1.dp, color.copy(alpha = .6f), RoundedCornerShape(16.dp))
            .clickable(remember { MutableInteractionSource() }, indication = null) {}      // a tap in a card doesn't close
            .padding(14.dp),
    ) { content() }
}

/** The rankings' tab: all nine titles, who holds them, where I stand; a tap opens the title screen. */
@Composable
fun NineTab(vm: AppViewModel) {
    val S = LocalStrings.current
    var data by remember { mutableStateOf<NineResponse?>(null) }
    LaunchedEffect(Unit) { data = vm.req<NineResponse>("GET", "/nine") }
    val d = data ?: run { if (!vm.online) OfflineState(); return }
    LazyColumn(contentPadding = PaddingValues(bottom = TAB_BAR_HEIGHT + 24.dp)) {
        items(d.titles.size, key = { d.titles[it].key }) { i ->
            val t = d.titles[i]
            Row(
                Modifier.fillMaxWidth().clickable { NineLore.open = t.key }.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                NineIcon(t.key, 40.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(S.nineName(t.key), style = T.bodyStrong.copy(color = NINE_COLORS[t.key] ?: Px.Text))
                    val h = t.holder
                    if (h != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Avatar(h.displayName, h.avatar, 18.dp)
                        Text(h.username + (t.value?.let { " · " + unit(S, t.key, it) } ?: ""), style = T.small.copy(color = Px.Text),
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    } else Text(S.nineNobody, style = T.small)
                    Mine(t)
                }
                PixelIcon("ui_back", 14.dp, Modifier.graphicsLayer { scaleX = -1f }, alpha = .5f)
            }
        }
    }
}

/** The Larper election: the running one (vote, who leads, my vote) or when the next comes. */
@Composable
fun ElectionTab(vm: AppViewModel) {
    val S = LocalStrings.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    var e by remember { mutableStateOf<ElectionState?>(null) }
    var q by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<UserCard>>(emptyList()) }
    LaunchedEffect(Unit) { e = vm.req<ElectionResponse>("GET", "/nine/election")?.election }
    LaunchedEffect(q) {
        val term = q.trim()
        if (term.isEmpty()) { hits = emptyList(); return@LaunchedEffect }
        delay(300)
        hits = vm.req<com.momentum.app.data.UsersResponse>("GET", "/users/search?q=${java.net.URLEncoder.encode(term, "UTF-8")}&limit=20")?.users ?: emptyList()
    }
    val st = e ?: run { if (!vm.online) OfflineState(); return }
    val me = vm.state?.user?.username
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = TAB_BAR_HEIGHT + 24.dp)) {
        Column(
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().clip(CardShape)
                .background(Brush.linearGradient(listOf(Color(0xFF2A1A3B), Color(0xFF15121F)))).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NineIcon("larper", 40.dp)
                Text(S.nineName("larper"), style = T.block.copy(fontSize = 20.sp, color = NINE_COLORS.getValue("larper")))
            }
            if (st.open) {
                Text(S.electionIntro, style = T.body)
                Text(S.electionVotes(st.voters, st.needed) + (st.closesAt?.let { " · " + S.electionEnds(dateOf(it, vm.lang)) } ?: ""),
                    style = T.small.copy(color = Px.Text))
                st.myVote?.let { Label(S.electionMyVote(it.username).uppercase(), color = NINE_COLORS.getValue("larper")) }
            } else {
                Text(S.electionClosed, style = T.body)
                st.nextAt?.let { Text(S.electionNext(dateOf(it, vm.lang)), style = T.small.copy(color = Px.Text)) }
            }
        }
        @Composable
        fun row(u: UserCard, right: @Composable () -> Unit) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Avatar(u.displayName, u.avatar, 36.dp)
                Text(u.username, style = T.bodyStrong, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).clickable { nav.push(Route.UserProfile(u.username)) })
                right()
            }
        }
        st.larper?.let {
            Label(S.electionCurrentLarper.uppercase(), Modifier.padding(start = 16.dp, top = 6.dp, bottom = 2.dp))
            row(it) { NineIcon("larper", 24.dp) }
        }
        if (st.open) {
            Box(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) { PxTextField(q, { q = it }, placeholder = S.electionSearch) }
            hits.filter { it.username != me }.forEach { u ->
                val voted = st.myVote?.username == u.username
                row(u) {
                    PxButton(if (voted) S.electionVoted else S.electionVote, style = if (voted) BtnStyle.Ghost else BtnStyle.Primary) {
                        scope.launch {
                            vm.req<ElectionResponse>("POST", "/nine/election/vote", buildJsonObject { put("username", u.username) })?.let {
                                e = it.election
                                vm.showToast(S.electionVoted)
                            }
                        }
                    }
                }
            }
            if (st.top.isNotEmpty()) {
                Label(S.electionLeading.uppercase(), Modifier.padding(start = 16.dp, top = 10.dp, bottom = 2.dp))
                st.top.forEach { c -> row(c.user) { Text("${c.votes}", style = T.block.copy(fontSize = 16.sp)) } }
            }
        }
    }
}

/** The election's own tab (only there while it runs: App.kt `Tab.Election`). */
@Composable
fun ElectionScreen(vm: AppViewModel) {
    val S = LocalStrings.current
    Column(Modifier.fillMaxSize()) {
        TopBar(S.electionTitle, onBack = null)
        ElectionTab(vm)
    }
}

/** The home tab's hint while the election is on. */
@Composable
fun ElectionCard(vm: AppViewModel) {
    val S = LocalStrings.current
    val nav = LocalNav.current
    if (vm.state?.user?.election != true) return
    val color = NINE_COLORS.getValue("larper")
    Row(
        Modifier.fillMaxWidth().padding(bottom = 14.dp).clip(RoundedCornerShape(16.dp)).background(Px.Card)
            .border(1.dp, color, RoundedCornerShape(16.dp)).clickable { NineLore.openElection?.invoke() }.padding(14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        NineIcon("larper", 40.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(S.electionOpenHint, style = T.small.copy(color = Px.Text))
            Text(S.electionOpenAction.uppercase(), style = T.button.copy(color = color, fontWeight = FontWeight.Bold))
        }
    }
}
