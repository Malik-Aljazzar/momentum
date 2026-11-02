package com.momentum.admin

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun StatsTab(api: Api, onLogout: () -> Unit, onAuthError: () -> Unit) {
    var stats by remember { mutableStateOf<Stats?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(reload) {
        try {
            stats = api.stats()
            error = null
        } catch (e: ApiException) {
            if (e.status == 401 || e.status == 403) onAuthError() else error = e.message
        }
    }
    Column(Modifier.fillMaxSize()) {
        Header(S.t("STATISTIK", "STATS")) {
            Text(S.t("NEU LADEN", "RELOAD"), style = T.button.copy(color = Px.Yellow, fontSize = 11.sp),
                modifier = Modifier.clickable { reload++ }.padding(6.dp))
        }
        val st = stats
        if (st == null) {
            Message(error ?: "…", if (error != null) Px.Orange else Px.Muted)
            return@Column
        }
        val t = st.totals
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Tiles(listOf(
                S.t("Nutzer", "Users") to t["users"],
                S.t("Neu (7 T.)", "New (7 d)") to t["new_users_7d"],
                S.t("Aktiv heute", "Active today") to t["active_today"],
                S.t("Aktiv 7 T.", "Active 7 d") to t["active_7d"],
                S.t("Aktiv 30 T.", "Active 30 d") to t["active_30d"],
                S.t("Regeln", "Rules") to t["rules"],
            ))
            SectionTitle(S.t("Aktive Spieler · 30 Tage", "Active players · 30 days"))
            DayBars(st.days, Px.Yellow) { it.active }
            SectionTitle(S.t("Neue Konten · 30 Tage", "New accounts · 30 days"))
            DayBars(st.days, Px.Lime) { it.signups }
            SectionTitle(S.t("Posts · 30 Tage", "Posts · 30 days"))
            DayBars(st.days, GroupColors.getValue("posts")) { it.posts }
            SectionTitle(S.t("Nachrichten · 30 Tage", "Messages · 30 days"))
            DayBars(st.days, GroupColors.getValue("chat")) { it.messages }
            SectionTitle(S.t("Inhalte", "Content"))
            Tiles(listOf(
                "Posts" to t["posts"], S.t("Nachrichten", "Messages") to t["messages"],
                S.t("Stories live", "Stories live") to t["stories_live"], S.t("Stories gesamt", "Stories total") to t["stories"],
                S.t("Gruppen", "Groups") to t["groups"], "Challenges" to t["challenges"],
            ))
            SectionTitle("Reports")
            Tiles(listOf(S.status("new") to t["reports_new"], "TODO" to t["reports_todo"], S.status("done") to t["reports_done"]),
                colors = listOf(Px.Yellow, Px.Cyan, Px.Lime), columns = 3)
            Spacer(Modifier.height(16.dp))
            PixelButton("LOGOUT", Modifier.fillMaxWidth(), color = Px.Orange, onClick = onLogout)
        }
    }
}

/** KPI tiles: the number big, its label small. */
@Composable
private fun Tiles(values: List<Pair<String, Int?>>, colors: List<Color> = emptyList(), columns: Int = 2) {
    for ((r, row) in values.chunked(columns).withIndex()) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            row.forEachIndexed { i, (label, n) ->
                PixelCard(Modifier.weight(1f)) {
                    Column {
                        Text(count((n ?: 0).toLong()), style = T.number.copy(color = colors.getOrNull(r * columns + i) ?: Px.Text))
                        Text(label.uppercase(), style = T.label, maxLines = 1)
                    }
                }
            }
            repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

/** One measure per day as thin bars; tap a bar for its day and value (the last day is shown by default). */
@Composable
private fun DayBars(days: List<Day>, color: Color, value: (Day) -> Int) {
    var selected by remember(days) { mutableIntStateOf(days.lastIndex) }
    val max = (days.maxOfOrNull(value) ?: 0).coerceAtLeast(1)
    PixelCard {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            days.getOrNull(selected)?.let { d ->
                Row {
                    Text(count(value(d).toLong()), style = T.number)
                    Spacer(Modifier.weight(1f))
                    Text(d.day.substring(8, 10) + "." + d.day.substring(5, 7) + ".", style = T.label.copy(color = Px.Text),
                        modifier = Modifier.padding(top = 8.dp))
                }
            }
            Canvas(
                Modifier.fillMaxWidth().height(84.dp).pointerInput(days) {
                    detectTapGestures { selected = (it.x / size.width * days.size).toInt().coerceIn(0, days.lastIndex) }
                },
            ) {
                val slot = size.width / days.size
                val gap = 2.dp.toPx()
                // recessive grid: the max and the baseline
                drawRect(Px.Line, Offset(0f, 0f), Size(size.width, 1.dp.toPx()))
                drawRect(Px.Line, Offset(0f, size.height - 1.dp.toPx()), Size(size.width, 1.dp.toPx()))
                days.forEachIndexed { i, d ->
                    val h = size.height * value(d) / max
                    val c = if (i == selected) color else color.copy(alpha = 0.55f)
                    if (h > 0) drawRect(c, Offset(i * slot + gap / 2, size.height - h), Size(slot - gap, h))
                }
            }
            Row {
                Text(S.t("max ", "max ") + count(max.toLong()), style = T.label)
                Spacer(Modifier.weight(1f))
                Text(S.t("heute", "today"), style = T.label)
            }
        }
    }
}
