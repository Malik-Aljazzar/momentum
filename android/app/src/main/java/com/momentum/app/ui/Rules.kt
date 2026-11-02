package com.momentum.app.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.nestedScroll
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.toMutableStateList
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.momentum.app.AppViewModel
import com.momentum.app.Pop
import com.momentum.app.data.AppState
import com.momentum.app.data.Rule
import com.momentum.app.fmt
import com.momentum.app.fmtVal
import kotlinx.coroutines.launch

/** What the bottom sheet is editing. */
private sealed interface Editing {
    data class RuleEdit(val rule: Rule?) : Editing
    data object DecayEdit : Editing
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun RulesScreen(vm: AppViewModel, st: AppState, list: LazyListState) {
    val S = LocalStrings.current
    // accelerators and decelerators share the tab: the banner is split, the active half is wider
    var kind by rememberSaveable { mutableStateOf("acc") }
    val acc = kind == "acc"
    val decList = rememberLazyListState()
    var editing by remember { mutableStateOf<Editing?>(null) }
    var detailOf by remember { mutableStateOf<Rule?>(null) }        // subscribed / group / challenge rule
    var decayDetail by remember { mutableStateOf<DecayRef?>(null) }  // the decay I follow
    var limitInfo by remember { mutableStateOf(false) }
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    // long press: selection (then delete / unsubscribe / share / copy to a group)
    var selected by remember { mutableStateOf(setOf<Int>()) }
    val selecting = selected.isNotEmpty()
    var barPx by remember { mutableStateOf(0) }          // the selection bar's height: the list makes room for it
    val density = androidx.compose.ui.platform.LocalDensity.current
    val barDp = with(density) { barPx.toDp() }
    var confirmRemove by remember { mutableStateOf(false) }
    var sharing by remember { mutableStateOf<List<kotlinx.serialization.json.JsonObject>?>(null) }
    var copying by remember { mutableStateOf(false) }
    LaunchedEffect(kind) { selected = emptySet() }
    androidx.activity.compose.BackHandler(enabled = selecting) { selected = emptySet() }

    Column(Modifier.fillMaxSize()) {
    // banner stays fixed; only the list below scrolls
    Box {
    Row(
        Modifier
            .statusBarsPadding()
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp)
            .fillMaxWidth()
            .height(192.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        listOf("acc", "dec").forEach { k ->
            val active = k == kind
            val weight by animateFloatAsState(if (active) .76f else .24f, tween(350), label = "split")
            val color by animateFloatAsState(if (active) 1f else 0f, tween(350), label = "color")
            Box(
                Modifier
                    .weight(weight)
                    .fillMaxHeight()
                    .clip(CardShape)
                    .saturation(color)
                    .clickable(enabled = !active, interactionSource = remember { MutableInteractionSource() }, indication = null) { kind = k },
            ) {
                SceneBackground(if (k == "acc") Scene.Acc else Scene.Dec, Modifier.matchParentSize())
                if (!active) {
                    // the other half: greyed out, a tap switches over
                    Box(Modifier.matchParentSize().background(Color(0x66000000)))
                    Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                        PixelIcon(if (k == "acc") "ui_up" else "ui_down", 28.dp, alpha = .9f)
                        Spacer(Modifier.height(8.dp))
                        Text(if (k == "acc") S.accShort else S.decShort, style = T.block.copy(fontSize = 10.sp, color = Px.Text),
                            maxLines = 1)
                        Text("${st.rules.count { it.kind == k }}", style = T.block.copy(fontSize = 18.sp, color = Px.Text))
                    }
                } else {
                    RulesBanner(st, k == "acc", onLimitInfo = { limitInfo = true }, modifier = Modifier.align(Alignment.BottomStart))
                }
            }
        }
    }
    }
    val listState = if (acc) list else decList
    val rules = st.rules.filter { it.kind == kind }
    val ordered = rules
    Box(Modifier.weight(1f)) {
    LazyColumn(
        Modifier.fillMaxSize(), state = listState, verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 16.dp, end = 16.dp, top = 10.dp,
            // while selecting, the bar covers the bottom: the last cards (and their arrows) can scroll above it
            bottom = TAB_BAR_HEIGHT + if (selecting) maxOf(40.dp, barDp + 28.dp) else 40.dp),
    ) {
        if (!acc) item(key = "decay") {
            DecayCard(st) {
                // my own decay: edit it; a followed one: its detail (stop following there)
                val f = st.decay.follow
                decayDetail = when {
                    f == null -> { editing = Editing.DecayEdit; null }
                    f.kind == "group" && f.decayId != null -> DecayRef.Group(f.decayId)
                    else -> DecayRef.User(f.username ?: f.label)
                }
            }
        }
        items(ordered, key = { it.id }) { r ->
            run {
                Box(Modifier.animateItem()) {
                    val toggle = { if (r.id > 0) selected = if (r.id in selected) selected - r.id else selected + r.id }
                    // exactly one selected: arrows in that card move it up / down step by step
                    val idx = ordered.indexOf(r)
                    val sorting = selected.size == 1 && r.id in selected
                    RuleCard(r, vm.pop, onPopDone = vm::consumePop, selected = if (selecting) r.id in selected else null,
                        onMove = if (sorting) { step ->
                            if (st.settings.haptics) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            // the list stays where it is (it would otherwise follow the card it passed, and the
                            // moved card would leave the screen at the top)
                            listState.requestScrollToItem(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
                            vm.moveRule(r.id, step)
                            // keep the moved card in view
                            val info = listState.layoutInfo
                            val card = info.visibleItemsInfo.firstOrNull { it.key == r.id }
                            if (card != null) scope.launch {
                                val size = (card.size + 10 * view.resources.displayMetrics.density)
                                if (step < 0 && card.offset - size < info.viewportStartOffset) listState.animateScrollBy(-size)
                                // the selection bar covers the bottom part of the list
                                else if (step > 0 && card.offset + 2 * size > info.viewportEndOffset - barPx - (TAB_BAR_HEIGHT.value + 12) * view.resources.displayMetrics.density)
                                    listState.animateScrollBy(size)
                            }
                        } else null, canUp = idx > 0, canDown = idx < ordered.size - 1,
                        onLongPress = {
                            // a long press starts the selection (delete, unsubscribe, share, copy into a group)
                            if (r.id > 0) selected = selected + r.id
                            if (st.settings.haptics) view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        }, onTap = {
                        if (selecting) toggle()
                        else {
                            if (st.settings.haptics) view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            vm.trigger(r)
                        }
                    }, onEdit = { if (selecting) toggle() else if (r.isOwn) editing = Editing.RuleEdit(r) else detailOf = r })
                }
            }
        }
        item(key = "new") {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(22.dp))
                    .drawBehind {
                        drawRoundRect(Color(0x26FFFFFF), cornerRadius = CornerRadius(22.dp.toPx()),
                            style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))))
                    }
                    .clickable { editing = Editing.RuleEdit(null) }
                    .padding(18.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PixelIcon("ui_plus", 16.dp, alpha = .55f)
                Spacer(Modifier.width(10.dp))
                Label(if (acc) S.newAcc else S.newDec)
            }
        }
        if (ordered.size > 1) item(key = "hint") {
            Text(S.dragToSort, style = T.small.copy(fontSize = 12.sp), modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
    if (selecting) {
        val sel = st.rules.filter { it.id in selected }
        val own = sel.filter { it.isOwn }
        val subs = sel.filter { !it.isOwn && it.origin != "challenge" && it.lockedBy == null }
        val copyable = sel.filter { it.origin != "challenge" }
        SelectionBar(S.selectedCount(sel.size), onClose = { selected = emptySet() },
            onAll = { selected = ordered.map { it.id }.filter { it > 0 }.toSet() }, onHeight = { barPx = it }) {
            if (own.isNotEmpty() || subs.isNotEmpty()) SelectionAction(
                when {
                    own.isNotEmpty() && subs.isNotEmpty() -> S.removeN(own.size + subs.size)
                    own.isNotEmpty() -> S.deleteN(own.size)
                    else -> S.unsubscribeN(subs.size)
                }, danger = own.isNotEmpty()) { confirmRemove = true }
            SelectionAction(S.share) {
                sharing = sel.map { r -> kotlinx.serialization.json.buildJsonObject { put("kind", "rule"); put("id", r.rootId ?: r.id) } }
            }
            if (copyable.isNotEmpty()) SelectionAction(S.copyToGroup) { copying = true }
            // visibility of the own rules (subscribed ones belong to their creator)
            fun visibility(rs: List<Rule>, public: Boolean) = scope.launch {
                vm.bulk("/rules/visibility-many", buildJsonObject {
                    put("public", public)
                    put("ids", kotlinx.serialization.json.JsonArray(rs.map { kotlinx.serialization.json.JsonPrimitive(it.id) }))
                })?.let { vm.showToast(S.bulkResult(it.done, it.skipped.size)); selected = emptySet() }
            }
            val visible = own + subs                       // subscribed: shown in my profile or not
            val ownPrivate = visible.filter { !it.public }
            val ownPublic = visible.filter { it.public }
            if (ownPrivate.isNotEmpty()) SelectionAction(S.makePublicN(ownPrivate.size)) { visibility(ownPrivate, true) }
            if (ownPublic.isNotEmpty()) SelectionAction(S.makePrivateN(ownPublic.size)) { visibility(ownPublic, false) }
        }
        if (confirmRemove) PxSheet({ confirmRemove = false }) {
            val locked = sel.size - own.size - subs.size
            Text(S.confirmBulkRemove(own.size, subs.size), style = T.title.copy(fontSize = 20.sp))
            if (locked > 0) Text(S.bulkLockedHint(locked), style = T.small.copy(color = Px.Yellow))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PxButton(S.cancel, Modifier.weight(1f), style = BtnStyle.Ghost) { confirmRemove = false }
                PxButton(S.removeN(own.size + subs.size), Modifier.weight(1f), style = BtnStyle.Danger) {
                    confirmRemove = false
                    scope.launch {
                        vm.bulk("/rules/remove-many", buildJsonObject {
                            put("ids", kotlinx.serialization.json.JsonArray((own + subs).map { kotlinx.serialization.json.JsonPrimitive(it.id) }))
                        })?.let { vm.showToast(S.bulkResult(it.done, it.skipped.size)); selected = emptySet() }
                    }
                }
            }
        }
        if (copying) GroupPicker(vm, title = S.copyToGroup, onDismiss = { copying = false }) { c ->
            copying = false
            scope.launch {
                vm.bulk("/conversations/${c.id}/rules/bulk", buildJsonObject {
                    put("action", "copy"); put("ids", kotlinx.serialization.json.JsonArray(copyable.map { kotlinx.serialization.json.JsonPrimitive(it.id) }))
                })?.let { vm.showToast(S.bulkResult(it.done, it.skipped.size)); selected = emptySet() }
            }
        }
    }
    sharing?.let { SharePicker(vm, it) { sharing = null; selected = emptySet() } }
    }
    }

    if (limitInfo) {
        val l = st.limit
        PxSheet({ limitInfo = false }) {
            Text(S.limitInfoTitle, style = T.title.copy(fontSize = 20.sp))
            SegBar(if (l.cap > 0) l.gained / l.cap else 1.0, 20, if (l.remaining < 1) Px.Orange else Px.Lime)
            S.limitInfo(fmt(l.cap), fmt(l.average), fmt(l.bias), fmt(l.gained), fmt(kotlin.math.floor(l.remaining)))
                .forEach { Text(it, style = T.body) }
            l.nextCap?.let { Text(S.limitForecast(fmt(l.todayAverage), l.todayPlayers, fmt(it)), style = T.body.copy(color = Px.Cyan)) }
            if (l.provisional) Text(S.limitProvisional, style = T.body.copy(color = Px.Yellow))
            if (st.challengeCredit >= 1) {
                Text(S.challengeCreditLine(fmt(st.challengeCredit)), style = T.bodyStrong.copy(color = Px.Yellow))
                Text(S.challengeCreditHint, style = T.small)
            }
        }
    }

    decayDetail?.let { DecayDetailSheet(vm, it, onDismiss = { decayDetail = null }) }
    detailOf?.let { r ->
        val nav = LocalNav.current
        if (r.origin == "challenge" && r.challengeId != null) {
            LaunchedEffect(r.id) { detailOf = null; nav.push(Route.ChallengeDetail(r.challengeId)) }
        } else {
            RuleDetailSheet(vm, r.rootId ?: r.id, onDismiss = { detailOf = null })
        }
    }

    val current = editing
    if (current != null) {
        val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val scope = rememberCoroutineScope()
        val close: () -> Unit = { scope.launch { sheet.hide() }.invokeOnCompletion { editing = null } }
        ModalBottomSheet(
            onDismissRequest = { editing = null }, sheetState = sheet, containerColor = Px.Sheet,
            dragHandle = {
                Box(Modifier.padding(top = 10.dp, bottom = 14.dp).size(40.dp, 4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x33FFFFFF)))
            },
        ) {
            // scrolling in the editor never drags the sheet: a scroll that goes past the top mustn't
            // close it and throw away a rule being written (closing: the handle, back, a tap next to it)
            Box(Modifier.nestedScroll(KeepScroll)) {
                when (current) {
                    is Editing.RuleEdit -> RuleEditor(vm, current.rule, kind, close)
                    Editing.DecayEdit -> DecayEditor(vm, st.decay, close)
                }
            }
        }
    }
}

/** Takes the scroll and fling a list left over, so they don't reach (and drag) the sheet around it. */
private object KeepScroll : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
    override fun onPostScroll(consumed: Offset, available: Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource) = available
    override suspend fun onPostFling(consumed: androidx.compose.ui.unit.Velocity, available: androidx.compose.ui.unit.Velocity) = available
}

/** The active half of the split banner: title, current momentum and the daily limit. */
@Composable
private fun RulesBanner(st: AppState, acc: Boolean, onLimitInfo: () -> Unit, modifier: Modifier) {
    val S = LocalStrings.current
    Column(modifier.padding(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Label(if (acc) S.accTag else S.decTag, Modifier.weight(1f))
            // current momentum, counting along with every tap
            val shown by animateFloatAsState(st.momentum.toFloat(), tween(600), label = "momentum")
            Column(horizontalAlignment = Alignment.End) {
                Label(if (st.settings.dailyLimit) "MOMENTUM" else S.unranked.uppercase(),
                    color = if (st.settings.dailyLimit) Px.Yellow else Px.Muted)
                Text(fmt(shown.toDouble()), style = T.block.copy(fontSize = 20.sp))
            }
        }
        FitText(if (acc) S.accTitle else S.decTitle, T.title, 28.sp, Modifier.fillMaxWidth())
        Spacer(Modifier.height(6.dp))
        if (acc && !st.settings.dailyLimit) {
            Text(S.noLimitBanner, style = T.body.copy(color = Px.Muted), maxLines = 2)
        } else if (acc && st.limit.cap < 1e8) {
            // tap: how the limit comes about
            Column(Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onLimitInfo)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(S.limitLeft(fmt(kotlin.math.floor(st.limit.remaining)), fmt(st.limit.cap)) +
                        if (st.limit.provisional) " · " + S.limitProvisionalShort else "",
                        style = T.body.copy(color = Px.Muted), modifier = Modifier.weight(1f, fill = false), maxLines = 1)
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.size(20.dp).clip(RoundedCornerShape(10.dp)).background(Color(0x26FFFFFF)),
                        contentAlignment = Alignment.Center) { Text("?", style = T.block.copy(fontSize = 11.sp, color = Px.Text)) }
                }
                Spacer(Modifier.height(8.dp))
                SegBar(if (st.limit.cap > 0) st.limit.gained / st.limit.cap else 1.0, 16,
                    if (st.limit.remaining < 1) Px.Orange else Px.Lime)
            }
        } else {
            Text(if (acc) S.accSub else S.decSub, style = T.body.copy(color = Px.Muted), maxLines = 2)
        }
    }
}

/** Draws the content with reduced colour (0 = grey, 1 = full colour). */
private fun Modifier.saturation(amount: Float) = drawWithContent {
    if (amount >= 1f) { drawContent(); return@drawWithContent }
    val paint = androidx.compose.ui.graphics.Paint().apply {
        colorFilter = androidx.compose.ui.graphics.ColorFilter.colorMatrix(
            androidx.compose.ui.graphics.ColorMatrix().apply { setToSaturation(amount) })
    }
    drawIntoCanvas { it.saveLayer(androidx.compose.ui.geometry.Rect(androidx.compose.ui.geometry.Offset.Zero, size), paint) }
    drawContent()
    drawIntoCanvas { it.restore() }
}

@Composable
private fun DecayCard(st: AppState, onClick: () -> Unit) {
    val S = LocalStrings.current
    val d = st.decay
    PxCard(brush = Brush.linearGradient(listOf(Color(0xFF2B1830), Color(0xFF191220))), onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PixelIcon("calendar", 48.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Label(S.dailyDecay)
                val f = d.follow
                val color = if (d.lockedBy != null) Px.Yellow else Px.Cyan
                if (f?.kind == "group") Row(verticalAlignment = Alignment.CenterVertically) {
                    // "subscribed · [group icon] group · decay", like group rules
                    Text(S.subscribedLabel + " · ", style = T.small.copy(fontSize = 12.sp, color = color), maxLines = 1)
                    PixelIcon("ui_group", 12.dp, tint = color)
                    Spacer(Modifier.width(4.dp))
                    Text(f.label + (f.name?.takeIf { it != f.label }?.let { " · $it" } ?: ""), maxLines = 1,
                        style = T.small.copy(fontSize = 12.sp, color = color), overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                } else if (f != null) Text(S.subscribedLabel + " · " + f.label, style = T.small.copy(fontSize = 12.sp, color = color), maxLines = 1)
                Text("−" + fmt(d.tonight), style = T.block.copy(fontSize = 28.sp, color = Px.Orange))
                Text(S.decayInfo(fmt(d.base), d.streak), style = T.small)
            }
            if (d.follow == null) PixelIcon("ui_edit", 16.dp, Modifier.align(Alignment.Top), alpha = .45f)
        }
        if (d.catalystMode != "none") {
            val frac = if (d.threshold > 0) d.todayGain / d.threshold else 1.0
            Spacer(Modifier.height(14.dp))
            Text(S.collectedToday(fmt(d.todayGain), fmt(d.threshold)), style = T.small)
            Spacer(Modifier.height(6.dp))
            SegBar(frac, 20, if (frac >= 1) Px.Lime else Px.Yellow)
        }
    }
}

@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun RuleCard(r: Rule, pop: Pop?, onPopDone: (Pop) -> Unit, selected: Boolean? = null, onLongPress: () -> Unit = {},
                     onMove: ((Int) -> Unit)? = null, canUp: Boolean = false, canDown: Boolean = false,
                     onTap: () -> Unit, onEdit: () -> Unit) {
    val S = LocalStrings.current
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .97f else 1f, label = "press")
    val acc = r.isAcc
    // can't be tapped: a try greys the card out and shows the "no tap" finger for a moment
    val view = LocalView.current
    var blocked by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val block = remember { Animatable(0f) }
    LaunchedEffect(blocked) {
        if (blocked == 0) return@LaunchedEffect
        block.animateTo(1f, tween(140))
        kotlinx.coroutines.delay(650)
        block.animateTo(0f, tween(320))
    }

    Box {
        // two parts: the big tap area (counts the points) and, behind a pixel line, the info button
        Row(
            Modifier
                .fillMaxWidth()
                .height(androidx.compose.foundation.layout.IntrinsicSize.Min)
                .scale(scale)
                .clip(RoundedCornerShape(22.dp))
                .background(
                    Brush.linearGradient(
                        if (acc) listOf(Color(0xFF1B3532), Color(0xFF131E22)) else listOf(Color(0xFF3A1A2A), Color(0xFF1E1219)),
                    ),
                )
                .then(if (selected == true) Modifier.border(2.dp, Px.Yellow, RoundedCornerShape(22.dp)) else Modifier)
                .saturation(1f - block.value),
        ) {
        Row(
            Modifier
                .weight(1f)
                .combinedClickable(source, indication = null, onLongClick = onLongPress, onClick = {
                    if (selected == null && !r.tappable) {
                        blocked++
                        view.performHapticFeedback(HapticFeedbackConstants.REJECT)
                    } else onTap()
                })
                .padding(start = 14.dp, top = 14.dp, bottom = 14.dp, end = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(Color(0x12FFFFFF)),
                contentAlignment = Alignment.Center,
            ) { PixelIcon(r.icon, 32.dp) }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                // one line: a long name slides to its end and back instead of wrapping
                SlidingText(r.name, T.bodyStrong.copy(fontSize = 17.sp, lineHeight = 20.sp))
                if (r.origin == "group") Row(verticalAlignment = Alignment.CenterVertically) {
                    // "subscribed · [group icon] name", like "subscribed · name" for people
                    Text(S.subscribedLabel + " · ", style = T.small.copy(fontSize = 12.sp, color = Px.Cyan), maxLines = 1)
                    PixelIcon("ui_group", 12.dp, tint = Px.Cyan)
                    Spacer(Modifier.width(4.dp))
                    Text(r.originLabel, style = T.small.copy(fontSize = 12.sp, color = Px.Cyan), maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                } else if (!r.isOwn) Text(S.origin(r.origin, r.originLabel), style = T.small.copy(fontSize = 12.sp,
                    color = if (r.origin == "challenge") Px.Yellow else Px.Cyan), maxLines = 1)
                if (r.idleDays > 0 && r.idlePaused) Text(S.idlePausedShort, style = T.small.copy(fontSize = 12.sp), maxLines = 1)
            }
            Text((if (acc) "+" else "−") + fmtVal(r.value),
                style = T.block.copy(fontSize = 20.sp, color = if (acc) Px.Lime else Px.Orange))
            if (onMove != null) {
                Spacer(Modifier.width(8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    PixelArrowButton(up = true, enabled = canUp) { onMove(-1) }
                    PixelArrowButton(up = false, enabled = canDown) { onMove(1) }
                }
            }
        }
        PixelDivider(Modifier.fillMaxHeight().padding(vertical = 10.dp))
        // info: own rules open the editor, others their details (and the checkbox while selecting)
        Box(
            Modifier.width(58.dp).fillMaxHeight().clickable(onClick = onEdit),
            contentAlignment = Alignment.Center,
        ) {
            if (selected != null) PixelCheckbox(selected) else PixelInfoIcon()
        }
        }
        if (block.value > 0f) {
            Box(Modifier.matchParentSize().clip(RoundedCornerShape(22.dp)).background(Color.Black.copy(alpha = .35f * block.value)),
                contentAlignment = Alignment.Center) {
                PixelIcon("ui_notap", 48.dp, alpha = block.value, modifier = Modifier.scale(.7f + .3f * block.value))
            }
        }
        if (pop != null && pop.ruleId == r.id) {
            key(pop.nonce) {
                FloatText(pop.text, if (acc) Px.Lime else Px.Orange, Modifier.align(Alignment.CenterEnd).padding(end = 48.dp)) {
                    onPopDone(pop)
                }
            }
        }
    }
}

/** "+400" rising and fading out. */
@Composable
private fun FloatText(text: String, color: Color, modifier: Modifier, onDone: () -> Unit) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        progress.animateTo(1f, tween(900))
        onDone()   // consumed: coming back to the screen must not replay it
    }
    if (progress.value >= 1f) return
    Text(
        text,
        style = T.block.copy(fontSize = 26.sp, color = color, fontWeight = FontWeight.Bold),
        modifier = modifier
            .offset(y = (-48 * progress.value).dp)
            .alpha(1f - progress.value),
    )
}
