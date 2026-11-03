package com.momentum.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import com.momentum.app.AppViewModel
import com.momentum.app.data.AppState
import com.momentum.app.fmt
import com.momentum.app.fmtShort
import com.momentum.app.secondsSince
import com.momentum.app.signed

@Composable
fun HomeScreen(vm: AppViewModel, st: AppState, list: LazyListState) {
    val S = LocalStrings.current
    val meta = vm.meta ?: return
    val vs = meta.vehicles
    val idx = st.vehicle.index.coerceIn(0, vs.lastIndex)
    val v = vs[idx]
    val next = vs.getOrNull(idx + 1)
    val d = st.decay
    var undoing by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<com.momentum.app.data.Event?>(null) }
    undoing?.let { UndoSheet(vm, it) { undoing = null } }

    LazyColumn(state = list, contentPadding = tabContentPadding(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(st.user, 36.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(S.hello, style = T.small.copy(fontSize = 12.sp))
                    Text(st.user.displayName, style = T.bodyStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Row(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (d.streak > 0) Px.Orange.copy(alpha = .2f) else Px.Faint)
                        .padding(start = 8.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PixelIcon("storm", 16.dp)
                    Spacer(Modifier.width(6.dp))
                    Label(S.days(d.streak), color = if (d.streak > 0) Px.Yellow else Px.Text)
                }
            }
        }

        item { ConfirmReminder(vm) }
        item { Hero(st, v.key, S.vehicle(v.key, v.label), v.category, idx, vs.size) }

        item {
            PxCard {
                if (next != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Label(S.nextMilestone)
                            Text(S.vehicle(next.key, next.label), style = T.bodyStrong.copy(fontSize = 20.sp))
                            Text(S.toGo(fmt((st.vehicle.nextAt ?: 0.0) - st.momentum)), style = T.small)
                        }
                        VehicleSprite(next.key, 2f, animate = false, tint = Color.White, alpha = .25f)
                    }
                    Spacer(Modifier.height(14.dp))
                    SegBar(st.vehicle.progress(st.momentum), 24)
                } else {
                    Label(S.allDone)
                    Text(S.allDoneSub, style = T.bodyStrong.copy(fontSize = 20.sp))
                }
            }
        }

        item {
            PxCard(padding = 0.dp) {
                Label(S.garage, Modifier.padding(start = 20.dp, top = 18.dp))
                LazyRow(
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 18.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    itemsIndexed(vs) { i, g ->
                        val locked = i > idx
                        Column(
                            Modifier
                                .clip(RoundedCornerShape(18.dp))
                                .background(Px.Faint)
                                .then(if (i == idx) Modifier.border(2.dp, Px.Yellow, RoundedCornerShape(18.dp)) else Modifier)
                                .padding(10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            VehicleSprite(g.key, 2f, animate = false, tint = if (locked) Color.Black else null,
                                alpha = if (locked) .55f else 1f)
                            Spacer(Modifier.height(6.dp))
                            Label(if (locked) fmtShort(st.vehicle.milestones.getOrElse(i) { 0.0 }) else S.vehicle(g.key, g.label),
                                color = if (i == idx) Px.Yellow else Px.Muted)
                        }
                    }
                }
            }
        }

        if (st.settings.showHistory && st.events.isNotEmpty()) {
            item {
                PxCard {
                    Label(S.history, Modifier.padding(bottom = 8.dp))
                    st.events.take(12).forEachIndexed { i, e ->
                        if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(Px.Line))
                        HistoryEntry(vm, e) { undoing = e }
                    }
                }
            }
        }
    }
}

/** One history line. Milestones show their vehicle and no points; acc/dec can be undone by tapping. */
@Composable
private fun HistoryEntry(vm: AppViewModel, e: com.momentum.app.data.Event, onUndo: () -> Unit) {
    val S = LocalStrings.current
    val vehicle = if (e.kind == "milestone") vm.meta?.vehicles?.firstOrNull { it.key == e.label } else null
    Row(
        Modifier.fillMaxWidth().then(if (e.undoable) Modifier.clickable(onClick = onUndo) else Modifier).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            vehicle != null -> Box(Modifier.width(32.dp), contentAlignment = Alignment.Center) { VehicleSprite(vehicle.key, 0.5f, animate = false) }
            e.icon.isNotEmpty() -> PixelIcon(e.icon, 24.dp)
            else -> Box(Modifier.size(24.dp).clip(RoundedCornerShape(6.dp)).background(Px.Faint))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            val label = when {
                vehicle != null -> S.milestoneEvent(S.vehicle(vehicle.key, vehicle.label))
                e.label.isNotEmpty() -> e.label
                e.kind == "daily" -> S.dailyDecay
                e.kind == "report" -> S.reportBonus
                else -> S.resetDone
            }
            Text(label, style = T.body.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                color = if (vehicle != null) Px.Yellow else Px.Text), maxLines = 1, overflow = TextOverflow.Ellipsis)
            val time = secondsSince(e.at)?.let(S::timeAgo).orEmpty()
            val extra = when {
                e.id < 0 -> " · ${S.pendingSync}"
                e.autoFrom != null -> " · ${S.autoTapped(e.autoFrom)}"
                else -> ""
            }
            Text(time + extra, style = T.small, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (e.kind != "milestone") {        // events without points show no number
            Text(signed(e.delta, ::fmt), style = T.block.copy(fontSize = 14.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Normal,
                color = if (e.delta >= 0) Px.Lime else Px.Orange))
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun UndoSheet(vm: AppViewModel, e: com.momentum.app.data.Event, onDismiss: () -> Unit) {
    val S = LocalStrings.current
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Px.Sheet) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(S.undoTitle, style = T.title.copy(fontSize = 20.sp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (e.icon.isNotEmpty()) PixelIcon(e.icon, 32.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(e.label, style = T.bodyStrong)
                    Text(secondsSince(e.at)?.let(S::timeAgo).orEmpty(), style = T.small)
                }
                Text(signed(e.delta, ::fmt), style = T.block.copy(fontSize = 18.sp, color = if (e.delta >= 0) Px.Lime else Px.Orange))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PxButton(S.cancel, Modifier.weight(1f), style = BtnStyle.Ghost, onClick = onDismiss)
                PxButton(S.undo, Modifier.weight(1f), style = BtnStyle.Danger) {
                    vm.undo(e)
                    onDismiss()
                }
            }
        }
    }
}

@Composable
private fun Hero(st: AppState, key: String, label: String, category: String, idx: Int, count: Int) {
    val S = LocalStrings.current
    val scene = sceneFor(category)
    val land = scene == Scene.Land
    val shown by animateFloatAsState(st.momentum.toFloat(), tween(700), label = "momentum")
    val d = st.decay

    Box(
        Modifier
            .fillMaxWidth()
            .height(440.dp)
            .competitionGlow(st.settings.dailyLimit)
            .clip(CardShape),
    ) {
        val bkey = st.user.banner ?: defaultBanner(scene)
        BannerBackground(bkey, Modifier.matchParentSize(), groundDp = 120f, road = land, speed = vehicleSpeed(key))
        Column(Modifier.padding(start = 22.dp, end = 22.dp, top = 22.dp)) {
            ScoreLabel(st.settings.dailyLimit)
            Spacer(Modifier.height(4.dp))
            // sized for the final value so the count-up doesn't jitter; never wraps
            FitText(fmt(shown.toDouble()), T.block, 76.sp, Modifier.fillMaxWidth(), fitFor = fmt(st.momentum).padStart(4, '0'))
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                PixelIcon("star", 16.dp)
                Spacer(Modifier.width(8.dp))
                Text(label, style = T.body.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium))
                Text("  · ${S.stage(idx + 1, count)}", style = T.body.copy(color = Px.Muted))
            }
        }
        VehicleSprite(key, 4f, Modifier.align(Alignment.BottomCenter).padding(bottom = if (land) 112.dp else 150.dp), night = bannerNight(bkey))
        Row(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .then(if (land) Modifier else Modifier.background(Brush.verticalGradient(listOf(Color.Transparent, Color(0x59000000)))))
                .padding(start = 22.dp, end = 22.dp, bottom = 18.dp, top = if (land) 0.dp else 40.dp),
        ) {
            listOf(S.today to signed(d.todayGain, ::fmt), S.goalToday to fmt(d.threshold), S.tonight to "−" + fmt(d.tonight))
                .forEach { (k, value) ->
                    Column(Modifier.weight(1f)) {
                        Label(k, color = Px.Muted)
                        Text(value, style = T.block.copy(fontSize = 17.sp))
                    }
                }
        }
    }
}
