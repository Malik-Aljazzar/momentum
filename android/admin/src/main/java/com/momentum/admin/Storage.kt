package com.momentum.admin

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToLong

private fun pct(part: Long, whole: Long) = if (whole <= 0) 0f else part.toFloat() / whole

@Composable
fun StorageTab(api: Api, onOpen: (String) -> Unit, onAuthError: () -> Unit) {
    var data by remember { mutableStateOf<Storage?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(reload) {
        loading = true
        try {
            data = api.storage(refresh = reload > 0)
            error = null
        } catch (e: ApiException) {
            if (e.status == 401 || e.status == 403) onAuthError() else error = e.message
        } finally {
            loading = false
        }
    }

    Column(Modifier.fillMaxSize()) {
        Header(S.t("SPEICHER", "STORAGE")) {
            Text(if (loading) "…" else S.t("NEU MESSEN", "REMEASURE"), style = T.button.copy(color = Px.Yellow, fontSize = 11.sp),
                modifier = Modifier.clickable(enabled = !loading) { reload++ }.padding(6.dp))
        }
        val d = data
        if (d == null) {
            Message(error ?: "…", if (error != null) Px.Orange else Px.Muted)
            return@Column
        }
        val groups = d.categories.groupBy { it.group }.mapValues { (_, cs) -> cs.sumOf { it.bytes } }
        val biggest = d.categories.maxOfOrNull { it.bytes }?.coerceAtLeast(1) ?: 1
        LazyColumn(contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { UsageCard(d, groups) }
            item {
                if (editing) LimitEditor(api, d, onDone = { new -> new?.let { data = it }; editing = false })
                else LimitCard(d) { editing = true }
            }
            item { DiskCard(d) }
            item { SectionTitle(S.t("Kategorien · größte zuerst", "Categories · largest first")) }
            items(d.categories, key = { it.key }) { c -> CategoryRow(c, biggest, d.used) { onOpen(c.key) } }
            error?.let { item { Text(it, style = T.body.copy(color = Px.Orange)) } }
        }
    }
}

@Composable
private fun UsageCard(d: Storage, groups: Map<String, Long>) {
    val share = pct(d.used, d.limit)
    val warn = share >= 0.9f
    PixelCard(border = if (warn) Px.Orange else Px.Line) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(S.t("BELEGT", "USED"), style = T.label)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(bytes(d.used), style = T.hero)
                Spacer(Modifier.width(10.dp))
                Text(S.t("von ", "of ") + bytes(d.limit), style = T.body.copy(color = Px.Muted), modifier = Modifier.padding(bottom = 4.dp))
            }
            // the bar is the limit; each group its share of it
            StackedBar(GroupColors.mapNotNull { (g, c) -> groups[g]?.let { pct(it, d.limit) to c } }, height = 26.dp)
            Row {
                Text(String.format(Locale.US, "%.1f %%", share * 100) + S.t(" des Limits", " of the limit"),
                    style = T.label.copy(color = if (warn) Px.Orange else Px.Text))
                Spacer(Modifier.weight(1f))
                Text(bytes((d.limit - d.used).coerceAtLeast(0)) + S.t(" frei", " left"), style = T.label)
            }
            // what the used space is made of (readable even while it's a sliver of the limit)
            Text(S.t("ZUSAMMENSETZUNG", "BREAKDOWN"), style = T.label, modifier = Modifier.padding(top = 4.dp))
            StackedBar(GroupColors.mapNotNull { (g, c) -> groups[g]?.let { pct(it, d.used) to c } }, height = 12.dp)
            if (warn) Text("▲ " + S.t("Fast voll: ab 100 % werden Uploads und neue Inhalte abgelehnt.",
                "Almost full: at 100 % uploads and new content are refused."), style = T.small.copy(color = Px.Orange))
            // legend: two columns, identity never by color alone
            val entries = GroupColors.filterKeys { it in groups }.toList()
            for (row in entries.chunked(2)) {
                Row(Modifier.fillMaxWidth()) {
                    for ((g, c) in row) {
                        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                            Swatch(c)
                            Spacer(Modifier.width(6.dp))
                            Column {
                                Text(S.group(g), style = T.small.copy(color = Px.Text), maxLines = 1)
                                Text(bytes(groups[g] ?: 0), style = T.label)
                            }
                        }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun LimitCard(d: Storage, onEdit: () -> Unit) {
    PixelCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("LIMIT", style = T.label)
                Text(bytes(d.limit), style = T.number)
                Text(if (d.limit_set) S.t("DB + Dateien dürfen nie mehr belegen.", "DB + files never take more.")
                     else S.t("Nicht gesetzt: die ganze Festplatte.", "Not set: the whole disk."), style = T.small)
            }
            PixelButton(S.t("ÄNDERN", "CHANGE"), color = Px.Yellow, onClick = onEdit)
        }
    }
}

/** Limit between 1 GB-ish and the whole disk: pixel slider, GB field and quick picks. */
@Composable
private fun LimitEditor(api: Api, d: Storage, onDone: (Storage?) -> Unit) {
    val total = d.disk_total
    var value by remember { mutableStateOf(d.limit.coerceAtMost(total)) }
    var text by remember { mutableStateOf(gb(value)) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun set(v: Long) { value = v.coerceIn(1, total); text = gb(value) }
    fun save(v: Long?) {
        busy = true
        scope.launch {
            try { onDone(api.setLimit(v)) } catch (e: ApiException) { error = e.message } finally { busy = false }
        }
    }
    PixelCard(border = Px.Yellow) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(S.t("LIMIT ÄNDERN", "CHANGE LIMIT"), style = T.label.copy(color = Px.Yellow))
            Text(bytes(value), style = T.number)
            PixelSlider(pct(value, total), marker = pct(d.used, total)) { set((it * total).roundToLong()) }
            Row {
                Text(S.t("belegt ", "used ") + bytes(d.used), style = T.label)
                Spacer(Modifier.weight(1f))
                Text(S.t("Platte ", "disk ") + bytes(total), style = T.label)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                PixelField(text, { t ->
                    text = t
                    t.replace(',', '.').toDoubleOrNull()?.let { value = (it * 1e9).roundToLong().coerceIn(1, total) }
                }, "GB", Modifier.weight(1f), number = true)
                Spacer(Modifier.width(8.dp))
                Text("GB", style = T.button)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (f in listOf(25, 50, 75)) {
                    PixelButton("$f %", Modifier.weight(1f)) { set(total * f / 100) }
                }
                PixelButton("MAX", Modifier.weight(1f)) { set(total) }
            }
            if (value < d.used) Text("▲ " + S.t("Unter dem aktuellen Verbrauch: alle Uploads und neuen Inhalte werden sofort abgelehnt.",
                "Below current usage: all uploads and new content are refused right away."), style = T.small.copy(color = Px.Orange))
            error?.let { Text(it, style = T.body.copy(color = Px.Orange)) }
            if (confirm) {
                Text(S.t("Wirklich unter den Verbrauch setzen?", "Really set it below usage?"), style = T.body)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PixelButton(S.t("NEIN", "NO"), Modifier.weight(1f)) { confirm = false }
                    PixelButton(S.t("JA", "YES"), Modifier.weight(1f), color = Px.Orange, filled = true, enabled = !busy) { save(value) }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PixelButton(S.t("ABBRECHEN", "CANCEL"), Modifier.weight(1f)) { onDone(null) }
                    PixelButton(S.t("SPEICHERN", "SAVE"), Modifier.weight(1f), color = Px.Yellow, filled = true, enabled = !busy) {
                        if (value < d.used) confirm = true else save(if (value >= total) null else value)
                    }
                }
            }
        }
    }
}

private fun gb(b: Long) = String.format(Locale.US, "%.1f", b / 1e9)

/** Square-thumb slider on a pixel track; `marker` shows where the current usage is. */
@Composable
private fun PixelSlider(value: Float, marker: Float, onChange: (Float) -> Unit) {
    Canvas(
        Modifier.fillMaxWidth().height(32.dp)
            .pointerInput(Unit) { detectTapGestures { onChange((it.x / size.width).coerceIn(0f, 1f)) } }
            .pointerInput(Unit) { detectDragGestures { change, _ -> onChange((change.position.x / size.width).coerceIn(0f, 1f)) } },
    ) {
        val track = 8.dp.toPx()
        val y = (size.height - track) / 2
        drawRect(Px.Card2, Offset(0f, y), Size(size.width, track))
        drawRect(Px.Yellow, Offset(0f, y), Size(size.width * value, track))
        val mx = size.width * marker.coerceIn(0f, 1f)
        drawRect(Px.Orange, Offset(mx - 1.dp.toPx(), 0f), Size(2.dp.toPx(), size.height))
        val t = 20.dp.toPx()
        val tx = (size.width * value - t / 2).coerceIn(0f, size.width - t)
        drawRect(Px.Text, Offset(tx, (size.height - t) / 2), Size(t, t))
        drawRect(Px.Bg, Offset(tx + 6.dp.toPx(), (size.height - t) / 2 + 6.dp.toPx()), Size(t - 12.dp.toPx(), t - 12.dp.toPx()))
    }
}

@Composable
private fun DiskCard(d: Storage) {
    val other = (d.disk_total - d.disk_free - d.used).coerceAtLeast(0)
    PixelCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(S.t("FESTPLATTE", "DISK"), style = T.label)
            Box {
                StackedBar(listOf(pct(d.used, d.disk_total) to Px.Yellow, pct(other, d.disk_total) to Color(0xFF4A4F63)), height = 14.dp)
                // where the limit sits on the disk
                Canvas(Modifier.fillMaxWidth().height(14.dp)) {
                    val x = size.width * pct(d.limit, d.disk_total).coerceIn(0f, 1f)
                    drawRect(Px.Text, Offset((x - 2.dp.toPx()).coerceAtLeast(0f), 0f), Size(2.dp.toPx(), size.height))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Legend(Px.Yellow, "Momentum", bytes(d.used))
                Legend(Color(0xFF4A4F63), S.t("Anderes", "Other"), bytes(other))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Legend(Px.Faint, S.t("Frei", "Free"), bytes(d.disk_free))
                Legend(Px.Text, "Limit", bytes(d.limit))
            }
            Text(S.t("Gesamt ", "Total ") + bytes(d.disk_total) + " · DB " + bytes(d.db_bytes) + S.t(" · Dateien ", " · files ") + bytes(d.blob_bytes),
                style = T.small)
        }
    }
}

@Composable
private fun Legend(color: Color, label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(color).border(1.dp, Px.Line))
        Spacer(Modifier.width(6.dp))
        Text("$label ", style = T.small)
        Text(value, style = T.small.copy(color = Px.Text))
    }
}

@Composable
private fun CategoryRow(c: Category, biggest: Long, used: Long, onClick: () -> Unit) {
    val color = GroupColors[c.group] ?: Px.Muted
    PixelCard(onClick = onClick) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Swatch(color, 12.dp)
                Spacer(Modifier.width(8.dp))
                Text(S.category(c.key), style = T.bodyStrong, modifier = Modifier.weight(1f), maxLines = 1)
                Text(bytes(c.bytes), style = T.block.copy(fontSize = 14.sp))
            }
            StackedBar(listOf(pct(c.bytes, biggest) to color), height = 6.dp)
            Row {
                Text(count(c.count) + if (c.type == "db") S.t(" Zeilen", if (c.count == 1L) " row" else " rows")
                     else S.t(if (c.count == 1L) " Datei" else " Dateien", if (c.count == 1L) " file" else " files"), style = T.label)
                Spacer(Modifier.weight(1f))
                Text(String.format(Locale.US, "%.1f %%", pct(c.bytes, used) * 100), style = T.label)
                if (c.type == "blob" && c.count > 0) Text(" · Ø " + bytes(c.bytes / c.count), style = T.label)
            }
        }
    }
}

/** One category: its files (largest first, with size per file) or its DB tables. */
@Composable
fun CategoryScreen(api: Api, key: String, onBack: () -> Unit, onOpen: (Item) -> Unit) {
    val items = remember { mutableStateListOf<Item>() }
    var tables by remember { mutableStateOf<List<Table>>(emptyList()) }
    var total by remember { mutableIntStateOf(0) }
    var end by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val list = rememberLazyListState()
    suspend fun more() {
        if (loading || end) return
        loading = true
        try {
            val page = api.category(key, items.size)
            items += page.items
            tables = page.tables
            total = page.total
            end = page.items.isEmpty() || items.size >= page.total
        } catch (e: ApiException) {
            error = e.message
            end = true
        } finally {
            loading = false
        }
    }
    LaunchedEffect(key) { more() }
    val nearEnd by remember { derivedStateOf { (list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= items.size - 6 } }
    LaunchedEffect(nearEnd, items.size) { if (nearEnd) more() }

    Column(Modifier.fillMaxSize()) {
        Header(S.category(key).uppercase(), onBack = onBack)
        LazyColumn(state = list, contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (key.startsWith("db_")) {
                item { Text(S.t("Tabellen, Größe anteilig an der DB-Datei geschätzt", "Tables, size estimated as a share of the DB file"), style = T.small) }
                items(tables, key = { it.table }) { t ->
                    PixelCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(t.table, style = T.bodyStrong)
                                Text(count(t.rows) + S.t(" Zeilen", " rows"), style = T.label)
                            }
                            Text(bytes(t.bytes), style = T.block.copy(fontSize = 14.sp))
                        }
                    }
                }
            } else {
                item { Text("${count(total.toLong())} " + S.t("Dateien · größte zuerst", "files · largest first"), style = T.small) }
                items(items, key = { it.key }) { it -> FileRow(api, it) { onOpen(it) } }
            }
            item {
                when {
                    error != null -> Message(error!!, Px.Orange)
                    loading -> Message("…")
                    items.isEmpty() && tables.isEmpty() -> Message(S.t("Leer", "Empty"))
                }
            }
        }
    }
}

private fun kindLabel(kind: String) = when (kind) {
    "video" -> "VIDEO"; "audio" -> "AUDIO"; "image" -> S.t("BILD", "IMAGE"); else -> S.t("DATEI", "FILE")
}

@Composable
private fun FileRow(api: Api, it: Item, onClick: () -> Unit) {
    PixelCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(56.dp).background(Px.Card2).border(2.dp, Px.Line), contentAlignment = Alignment.Center) {
                if (it.kind == "audio") Text("♪", style = T.block.copy(fontSize = 20.sp, color = Px.Muted))
                else AsyncImage(api.blob(it.thumb), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(bytes(it.size), style = T.block.copy(fontSize = 15.sp))
                Text(listOfNotNull(
                    it.username.takeIf { u -> u.isNotEmpty() }?.let { u -> "@$u" },
                    it.created_at?.let(::date),
                ).joinToString(" · ").ifEmpty { it.key }, style = T.small, maxLines = 1)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(kindLabel(it.kind), style = T.label)
                    if (it.width > 0) Text("${it.width}×${it.height}", style = T.label)
                    if (it.duration > 0) Text(String.format(Locale.US, "%.0f s", it.duration), style = T.label)
                }
            }
        }
    }
}

/** A file full screen with its details. */
@Composable
fun Viewer(api: Api, item: Item, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Header(if (item.size > 0) bytes(item.size) else S.t("BILD", "IMAGE"), onBack = onBack)
        Box(Modifier.weight(1f).fillMaxWidth().background(Px.Card), contentAlignment = Alignment.Center) {
            if (item.kind == "audio") Text("♪", style = T.hero.copy(color = Px.Muted))
            else AsyncImage(api.blob(if (item.kind == "video") item.thumb else item.url), null,
                contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        }
        Column(Modifier.padding(16.dp)) {
            if (item.username.isNotEmpty()) KeyValue(S.t("Von", "By"), "@" + item.username)
            item.created_at?.let { KeyValue(S.t("Hochgeladen", "Uploaded"), date(it)) }
            if (item.width > 0) KeyValue(S.t("Maße", "Dimensions"), "${item.width}×${item.height}")
            item.post_id?.let { KeyValue("Post", "#$it") }
            item.story_id?.let { KeyValue("Story", "#$it") }
            KeyValue(S.t("Datei", "File"), item.key)
            if (item.kind == "video") Text(S.t("Vorschaubild – das Video selbst ist größer.", "Preview frame – the video itself is bigger."), style = T.small)
        }
    }
}
