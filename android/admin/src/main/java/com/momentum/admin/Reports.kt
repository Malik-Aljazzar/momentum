package com.momentum.admin

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch

/** Bumped when a report changes, so the list reloads. */
object ReportsBus {
    var version by mutableIntStateOf(0)
}

private val STATUSES = listOf("new", "todo", "done")

@Composable
fun ReportsTab(api: Api, onOpen: (Int) -> Unit, onAuthError: () -> Unit) {
    var status by rememberSaveable { mutableStateOf("new") }
    val reports = remember { mutableStateListOf<Report>() }
    val counts = remember { mutableStateMapOf<String, Int>() }
    var end by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val list = rememberLazyListState()

    suspend fun more() {
        if (loading || end) return
        loading = true
        try {
            val page = api.reports(status, reports.size)
            reports += page.reports
            counts.putAll(page.counts)
            end = page.reports.isEmpty()
            error = null
        } catch (e: ApiException) {
            if (e.status == 401 || e.status == 403) onAuthError() else error = e.message
            end = true
        } finally {
            loading = false
        }
    }

    LaunchedEffect(status, ReportsBus.version, reload) {
        reports.clear()
        end = false
        loading = false
        more()
        list.scrollToItem(0)
    }
    val nearEnd by remember { derivedStateOf { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 >= reports.size - 5 } }
    LaunchedEffect(nearEnd, reports.size) { if (nearEnd) more() }

    Column(Modifier.fillMaxSize()) {
        Header("REPORTS") {
            Text(S.t("NEU LADEN", "RELOAD"), style = T.button.copy(color = Px.Yellow, fontSize = 11.sp),
                modifier = Modifier.clickable { reload++ }.padding(6.dp))
        }
        Segments(STATUSES.map { it to "${S.status(it)} ${counts[it] ?: ""}".trim() }, status, StatusColors,
            Modifier.padding(horizontal = 16.dp)) { status = it }
        if (status == "new") {
            Text(S.t("Neu = noch nicht freigegeben. Agenten setzen nur TODO um – stell einen Report auf TODO, wenn er umgesetzt werden soll.",
                "New = not approved yet. Agents only implement TODO – move a report to TODO to get it done."),
                style = T.small, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
        }
        LazyColumn(state = list, contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize()) {
            items(reports, key = { it.id }) { r -> ReportRow(r) { onOpen(r.id) } }
            item {
                when {
                    error != null -> Message(error!!, Px.Orange)
                    loading -> Message("…")
                    reports.isEmpty() -> Message(S.t("Keine Reports", "No reports"))
                }
            }
        }
    }
}

@Composable
fun KindBadge(kind: String) =
    if (kind == "bug") Badge("BUG", Px.Orange) else Badge(S.t("IDEE", "IDEA"), Px.Cyan)

@Composable
private fun ReportRow(r: Report, onClick: () -> Unit) {
    PixelCard(onClick = onClick) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KindBadge(r.kind)
                Text("#${r.id}", style = T.label.copy(color = Px.Text))
                Spacer(Modifier.weight(1f))
                Text(date(r.created_at), style = T.label)
            }
            Text(r.text, style = T.body, maxLines = 3)
            if (r.note.isNotBlank()) Text("→ " + r.note, style = T.small.copy(color = Px.Cyan), maxLines = 2)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("@${r.username}", style = T.small)
                Text(r.platform.uppercase().ifEmpty { "?" }, style = T.label)
                if (r.images.isNotEmpty()) Text("▣ ${r.images.size}", style = T.label.copy(color = Px.Yellow))
            }
        }
    }
}

@Composable
fun ReportScreen(api: Api, id: Int, onBack: () -> Unit, onImage: (Item) -> Unit) {
    var report by remember { mutableStateOf<Report?>(null) }
    var note by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(id) {
        try {
            report = api.report(id).also { note = it.note }
        } catch (e: ApiException) {
            error = e.message
        }
    }
    fun update(status: String? = null, newNote: String? = null) {
        saving = true
        scope.launch {
            try {
                report = api.updateReport(id, status, newNote)
                saved = newNote != null
                error = null
                ReportsBus.version++
            } catch (e: ApiException) {
                error = e.message
            } finally {
                saving = false
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Header("REPORT #$id", onBack = onBack)
        val r = report
        if (r == null) {
            Message(error ?: "…", if (error != null) Px.Orange else Px.Muted)
            return@Column
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 32.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KindBadge(r.kind)
                Badge(S.status(r.status), StatusColors[r.status] ?: Px.Text)
            }
            SectionTitle("Status")
            Segments(STATUSES.map { it to S.status(it) }, r.status, StatusColors) { if (it != r.status && !saving) update(status = it) }
            Text(when (r.status) {
                "new" -> S.t("Wartet auf deine Freigabe – Agenten fassen das nicht an.", "Waiting for your OK – agents leave it alone.")
                "todo" -> S.t("Freigegeben: Agenten setzen das um und stellen es danach auf ERLEDIGT.", "Approved: agents implement it and then mark it DONE.")
                else -> S.t("Erledigt", "Done") + (r.status_at?.let { " · " + date(it) } ?: "")
            }, style = T.small, modifier = Modifier.padding(top = 8.dp))

            SectionTitle(S.t("Inhalt", "Content"))
            PixelCard {
                SelectionContainer { Text(r.text, style = T.body) }
            }
            if (r.images.isNotEmpty()) {
                SectionTitle(S.t("Bilder", "Images") + " (${r.images.size})")
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (img in r.images) {
                        AsyncImage(api.blob(img), null, contentScale = ContentScale.Crop,
                            modifier = Modifier.size(110.dp, 196.dp).background(Px.Card2).border(2.dp, Px.Line)
                                .clickable { onImage(Item(key = img, url = img, thumb = img, size = 0, kind = "image")) })
                    }
                }
            }

            SectionTitle(S.t("Notiz / Aufgabe für Agenten", "Note / task for agents"))
            PixelField(note, { note = it; saved = false }, S.t("z. B. umformulierte Aufgabe", "e.g. the reworded task"),
                singleLine = false, minLines = 3)
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                PixelButton(S.t("NOTIZ SPEICHERN", "SAVE NOTE"), color = Px.Yellow, enabled = !saving && note != r.note) {
                    update(newNote = note)
                }
                Spacer(Modifier.width(12.dp))
                if (saved) Text(S.t("Gespeichert", "Saved"), style = T.small.copy(color = Px.Lime))
            }
            error?.let { Text(it, style = T.body.copy(color = Px.Orange), modifier = Modifier.padding(top = 8.dp)) }

            SectionTitle("Details")
            PixelCard {
                Column {
                    KeyValue(S.t("Von", "From"), "@" + r.username)
                    KeyValue(S.t("Eingereicht", "Filed"), date(r.created_at))
                    KeyValue(S.t("Plattform", "Platform"), r.platform.uppercase().ifEmpty { "?" })
                    KeyValue(S.t("Gerät", "Device"), r.device.ifEmpty { "–" })
                    KeyValue("App", r.app_version.ifEmpty { "–" })
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
