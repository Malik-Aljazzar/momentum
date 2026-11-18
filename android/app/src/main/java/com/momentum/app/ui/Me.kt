package com.momentum.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.momentum.app.AppViewModel
import com.momentum.app.data.AppState
import com.momentum.app.data.Event
import com.momentum.app.fmt
import com.momentum.app.secondsSince
import com.momentum.app.signed

// What only my own profile shows (it's the main page; web/src/screens/Me.tsx): the notes that want my attention, the
// whole history (every tap, undoable) and today's numbers. Before 2026-11-18 these lived on a home tab.

/** The cards above my profile's sections: confirm the address, notifications off, the election. */
@Composable
fun MyNotes(vm: AppViewModel) {
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ConfirmReminder(vm)
        NotifyReminder(vm)
        ElectionCard(vm)
    }
}

/** Today's numbers under my competition card: gained, the goal, what the night takes. */
@Composable
fun todayStats(st: AppState): List<Pair<String, String>> {
    val S = LocalStrings.current
    val d = st.decay
    return listOf(S.today to signed(d.todayGain, ::fmt), S.goalToday to fmt(d.threshold), S.tonight to "−" + fmt(d.tonight))
}

/** My whole history, newest first: the state's latest entries (live, also offline), older ones page by page
 *  (GET /api/events?before=). Taps can be undone. Holds the pages loaded so far; [items] puts them into a list. */
class MyHistory {
    var older by mutableStateOf<List<Event>>(emptyList())
    var more by mutableStateOf(true)
    var undoing by mutableStateOf<Event?>(null)
    private var loading = false

    fun all(st: AppState): List<Event> {
        val head = st.events
        val seen = head.mapTo(HashSet()) { it.id }
        return head + older.filter { it.id !in seen }
    }

    fun hasMore(st: AppState) = more && st.events.size >= 30

    suspend fun loadMore(vm: AppViewModel, st: AppState) {
        if (loading) return
        val last = all(st).lastOrNull { it.id > 0 } ?: run { more = false; return }
        loading = true
        val page = vm.req<com.momentum.app.data.EventsResponse>("GET", "/events?before=${last.id}&limit=30")?.events
        loading = false
        if (page == null) return
        older = (older + page).distinctBy { it.id }
        more = page.size >= 30
    }
}

/** The history's rows in a profile's list. */
fun LazyListScope.myHistoryItems(vm: AppViewModel, st: AppState, h: MyHistory) {
    val all = h.all(st)
    if (all.isEmpty()) {
        item(key = "noHistory") { EmptyHint(LocalStrings.current.noHistory, icon = "ui_clock") }
        return
    }
    itemsIndexed(all, key = { _, e -> "e${e.id}" }) { i, e ->
        Column {
            if (i > 0) Box(Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(1.dp).background(Px.Line))
            HistoryEntry(vm, e) { h.undoing = e }
        }
    }
}

/** Notifications off: calls ring only while the app is open, messages come only when you look in - a card that says
 *  so and what helps (data/Notifier.kt notifyHint = web NotifyReminder in screens/Me.tsx). "Not now": a week's peace. */
@Composable
private fun NotifyReminder(vm: AppViewModel) {
    val S = LocalStrings.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val prefs = androidx.compose.runtime.remember { com.momentum.app.data.Prefs(ctx) }
    var check by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }    // again on return
    var hiddenUntil by androidx.compose.runtime.remember { androidx.compose.runtime.mutableLongStateOf(prefs.notifyHintUntil) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) check++ }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    // Android's question; refused for good it doesn't come any more -> the system settings
    val ask = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted -> if (!granted) com.momentum.app.data.Notifier.openSettings(ctx, callsOnly = false); check++ }
    val hint = androidx.compose.runtime.remember(check, hiddenUntil, vm.notifications) {
        com.momentum.app.data.notifyHint(
            wanted = vm.notifications,
            enabled = androidx.core.app.NotificationManagerCompat.from(ctx).areNotificationsEnabled(),
            callsOn = com.momentum.app.data.Notifier.callsOn(ctx),
            canAsk = android.os.Build.VERSION.SDK_INT >= 33 && !com.momentum.app.data.Notifier.allowed(ctx),
            hiddenUntil = hiddenUntil, now = System.currentTimeMillis(),
        )
    } ?: return
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Px.Card)
            .border(1.dp, Px.Orange, RoundedCornerShape(16.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PixelIcon("ui_bell", 24.dp)
            Spacer(Modifier.width(10.dp))
            Text(S.notifyOffTitle, style = T.bodyStrong)
        }
        Text(if (hint == com.momentum.app.data.NotifyHint.Calls) S.notifyCallsOff else S.notifyOffText, style = T.small.copy(color = Px.Text))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val asks = hint == com.momentum.app.data.NotifyHint.Ask
            PxButton(if (asks) S.allowNotifications else S.openSystemSettings, Modifier.weight(1f)) {
                if (asks) ask.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                else com.momentum.app.data.Notifier.openSettings(ctx, callsOnly = hint == com.momentum.app.data.NotifyHint.Calls)
            }
            PxButton(S.notNow, Modifier.weight(1f), style = BtnStyle.Ghost) {
                hiddenUntil = System.currentTimeMillis() + com.momentum.app.data.NOTIFY_HINT_DAYS * 86_400_000L
                prefs.notifyHintUntil = hiddenUntil
            }
        }
    }
}

/** One history line. Milestones show their vehicle and no points; acc/dec can be undone by tapping. */
@Composable
private fun HistoryEntry(vm: AppViewModel, e: Event, onUndo: () -> Unit) {
    val S = LocalStrings.current
    val vehicle = if (e.kind == "milestone") vm.meta?.vehicles?.firstOrNull { it.key == e.label } else null
    Row(
        Modifier.fillMaxWidth().then(if (e.undoable) Modifier.clickable(onClick = onUndo) else Modifier)
            .padding(horizontal = 16.dp, vertical = 10.dp),
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
fun UndoSheet(vm: AppViewModel, e: Event, onDismiss: () -> Unit) {
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
