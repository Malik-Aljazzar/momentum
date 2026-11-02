package com.momentum.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.momentum.app.AppViewModel
import com.momentum.app.data.PostResponse
import com.momentum.app.data.Rule
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.layout.onSizeChanged

/*
 * Making a post or a story (web/src/screens/Compose.tsx): a compact form - pictures in a strip (tap
 * one: text and rules for it), a caption, then small chips for sound, place and tagged people.
 */

private const val MAX_RULES = 5
private const val MAX_TIMES = 10

/** Bumped when a story was shared: the feed's story row loads again. */
object StoriesVersion { var n by mutableStateOf(0) }

/** Rules that may go with a picture: shown on the profile, tappable, not a challenge copy. */
fun profileRules(rules: List<Rule>) = rules.filter { it.id > 0 && it.public && it.tappable && it.challengeId == null }

private sealed interface ComposeSheet {
    data object Sound : ComposeSheet
    data object Place : ComposeSheet
    data object Tags : ComposeSheet
    data class Slide(val d: Draft) : ComposeSheet
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Composer(vm: AppViewModel, story: Boolean) {
    val S = LocalStrings.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val max = if (story) 1 else 10
    val drafts = remember { mutableStateListOf<Draft>() }
    var text by remember { mutableStateOf("") }
    var tags by remember { mutableStateOf(setOf<String>()) }
    var location by remember { mutableStateOf<com.momentum.app.data.Place?>(null) }
    var sheet by remember { mutableStateOf<ComposeSheet?>(null) }
    var busy by remember { mutableStateOf(false) }
    var sound by remember { mutableStateOf<SoundDraft?>(null) }
    var storySecs by remember { mutableIntStateOf(5) }                 // a photo story's length (report #39)
    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) sound = soundDraft(context, uri, null)
    }
    fun add(uris: List<Uri>) = uris.take(max - drafts.size).forEach { uri ->
        val d = Draft(uri, context.contentResolver.getType(uri)?.startsWith("video") == true || uri.toString().endsWith(".mp4"))
        drafts += d
        scope.launch { d.media = vm.uploadMedia(uri); d.failed = d.media == null }
    }
    val many = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { add(it) }
    val one = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { it?.let { u -> add(listOf(u)) } }
    val uploading = drafts.any { it.media == null && !it.failed }
    // a story's sound is exactly as long as the story: the photo's chosen length, or the video's
    val first = drafts.firstOrNull()
    val storyMs: Long? = if (!story) null else if (first?.video == true)
        minOf(30_000L, ((first.media?.duration?.takeIf { it > 0 } ?: 30.0) * 1000).toLong()) else storySecs * 1000L
    LaunchedEffect(storyMs, sound?.uri) {
        val sd = sound ?: return@LaunchedEffect
        if (storyMs != null) {
            val (a, b) = clampRange(sd.durationMs, sd.startMs, sd.endMs, storyMs)
            if (a != sd.startMs || b != sd.endMs) sound = sd.copy(startMs = a, endMs = b)
        }
    }
    val ready = !uploading && !busy && (if (story) drafts.any { it.media != null } else text.isNotBlank() || drafts.any { it.media != null })

    fun publish() {
        busy = true
        scope.launch {
            // the chosen part of the sound goes up first
            val s = sound
            val soundMedia = s?.let { vm.uploadAudioPart(it.uri, it.startMs / 1000.0, (it.endMs - it.startMs) / 1000.0) }
            if (s != null && soundMedia == null) { busy = false; return@launch }
            val done = drafts.filter { it.media != null }
            val triggers = buildJsonArray {
                done.forEachIndexed { slide, d ->
                    d.rules.filter { it.value > 0 }.forEach { (id, times) ->
                        add(buildJsonObject { put("slide", slide); put("rule_id", id); put("times", times) })
                    }
                }
            }
            fun overlayJson(d: Draft) = d.overlay?.let { o ->
                buildJsonObject { put("text", o.text); put("x", o.x); put("y", o.y); put("color", o.color); put("size", o.size) }
            }
            val soundJson = if (s != null && soundMedia != null) buildJsonObject { put("media_id", soundMedia.id); put("title", s.title.trim()) } else null
            val ok = if (story) {
                val body = buildJsonObject {
                    put("media_id", done.first().media!!.id)
                    overlayJson(done.first())?.let { put("overlay", it) }
                    soundJson?.let { put("sound", it) }
                    put("triggers", triggers)
                    if (!done.first().video) put("duration", storySecs)
                }
                (vm.req<JsonObject>("POST", "/stories", body) != null).also { if (it) { vm.showToast(S.storyShared); StoriesVersion.n++ } }
            } else {
                val body = buildJsonObject {
                    put("text", text)
                    put("media_ids", JsonArray(done.map { kotlinx.serialization.json.JsonPrimitive(it.media!!.id) }))
                    put("tags", JsonArray(tags.map { kotlinx.serialization.json.JsonPrimitive(it) }))
                    put("triggers", triggers)
                    location?.let { l -> put("location", buildJsonObject {
                        put("name", l.name); l.lat?.let { put("lat", it) }; l.lon?.let { put("lon", it) }
                    }) }
                    put("overlays", buildJsonObject { done.forEach { d -> overlayJson(d)?.let { put(d.media!!.id.toString(), it) } } })
                    soundJson?.let { put("sound", it) }
                }
                (vm.req<PostResponse>("POST", "/posts", body) != null).also { if (it) { vm.showToast(S.shared); vm.feedVersion++ } }
            }
            if (ok) { if (triggers.isNotEmpty()) vm.refresh(); nav.pop() }
            busy = false
        }
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        TopBar(if (story) S.newStory else S.newPost) {
            Box(
                Modifier.padding(end = 8.dp).clip(RoundedCornerShape(12.dp)).background(if (ready) Px.Text else Px.Faint)
                    .clickable(enabled = ready) { publish() }.padding(horizontal = 16.dp, vertical = 10.dp),
            ) { Text((if (story) S.shareStory else S.publish).uppercase(), style = T.button.copy(color = if (ready) Color.Black else Px.Muted)) }
        }
        if (!vm.online) { OfflineState(); return@Column }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val openCamera = { CameraOverlay.open(true) { add(listOf(it)) } }
            val openGallery = {
                val req = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                if (story) one.launch(req) else many.launch(req)
            }
            // nothing picked yet: camera and gallery side by side over the whole width (report #41)
            if (drafts.isEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AddTile("camera", S.camera, Modifier.weight(1f).height(if (story) 160.dp else 120.dp)) { openCamera() }
                AddTile("ui_image", S.gallery, Modifier.weight(1f).height(if (story) 160.dp else 120.dp)) { openGallery() }
            }
            // the pictures (a tap opens text and rules for that one), then a narrow column to add more
            else LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(drafts) { d -> SlideTile(vm, d, story, onOpen = { sheet = ComposeSheet.Slide(d) }, onRemove = { drafts.remove(d) }) }
                if (drafts.size < max) item {
                    val h = if (story) 81.dp else 56.dp
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        AddTile("camera", S.camera, Modifier.size(72.dp, h)) { openCamera() }
                        AddTile("ui_image", S.gallery, Modifier.size(72.dp, h)) { openGallery() }
                    }
                }
            }
            if (drafts.isNotEmpty()) Text(S.tapToEdit, style = T.small.copy(fontSize = 11.sp))
            if (!story) {
                var captionValue by rememberCursorValue(text)
                Box(Modifier.fillMaxWidth()) {
                    BasicTextField(
                        captionValue, { captionValue = it; text = it.text }, textStyle = T.body.copy(fontSize = 17.sp, lineHeight = 23.sp).userText(),
                        cursorBrush = SolidColor(Px.Yellow),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 84.dp).clip(RoundedCornerShape(14.dp)).background(Px.Faint)
                            .border(1.dp, Px.Line, RoundedCornerShape(14.dp)).padding(start = 12.dp, end = 48.dp, top = 10.dp, bottom = 10.dp),
                        decorationBox = { inner -> if (text.isEmpty()) Text(S.captionHint, style = T.body.copy(fontSize = 17.sp, color = Px.Muted)); inner() },
                    )
                    Box(Modifier.align(Alignment.BottomEnd).padding(2.dp)) {
                        EmojiButton { e -> captionValue = Emoji.insert(captionValue, e); text = captionValue.text }
                    }
                }
                MentionSuggestions(vm, text) { text = it }
            }
            // the rest as small chips
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("note", sound?.let { it.title.ifBlank { S.originalSound } } ?: S.addSound, sound != null) { sheet = ComposeSheet.Sound }
                if (!story) {
                    Chip("compass", location?.name ?: S.addLocation, location != null) { sheet = ComposeSheet.Place }
                    Chip("smile", if (tags.isEmpty()) S.tagPeople else tags.joinToString(", ") { "@$it" }, tags.isNotEmpty()) { sheet = ComposeSheet.Tags }
                }
            }
            location?.let { l -> Text("✕ ${l.name}", style = T.small, modifier = Modifier.clickable { location = null }) }
            // how long a photo story runs
            if (story && first != null && !first.video) Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(S.storyDuration.uppercase(), style = T.label)
                for (n in listOf(5, 10, 15, 30)) {
                    val on = storySecs == n
                    Box(Modifier.clip(RoundedCornerShape(10.dp)).background(if (on) Px.Text else Px.Faint).clickable { storySecs = n }
                        .padding(horizontal = 12.dp, vertical = 8.dp)) {
                        Text("$n s", style = T.button.copy(color = if (on) Px.Bg else Px.Text))
                    }
                }
            }
            sound?.let { sd -> SoundEditor(sd, onChange = { sound = it }, onRemove = { sound = null }, fixedMs = storyMs) }
        }
    }

    when (val sh = sheet) {
        is ComposeSheet.Slide -> PxSheet({ sheet = null }) {
            val d = sh.d
            Row(verticalAlignment = Alignment.Top) {
                Box(Modifier.size(96.dp, 120.dp).clip(RoundedCornerShape(12.dp)).background(Px.Faint)) {
                    val thumb = d.media?.let { vm.abs(it.thumb) } ?: if (!d.video) d.uri.toString() else null
                    if (thumb != null) AsyncImage(thumb, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    d.overlay?.let { OverlayText(it) }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PxButton("Aa · " + S.textOnImage, Modifier.fillMaxWidth(), style = BtnStyle.Ghost) {
                        val ratio = d.media?.let { m -> if (m.width > 0 && m.height > 0) m.width.toFloat() / m.height else null } ?: if (story) 9f / 16f else 4f / 5f
                        OverlayEditing.open(if (d.video) d.media?.thumb?.let(vm::abs) else d.uri, ratio, d.overlay) { o -> d.overlay = o }
                    }
                    PxButton(S.delete, Modifier.fillMaxWidth(), style = BtnStyle.Danger) { drafts.remove(d); sheet = null }
                }
            }
            RulePicker(vm, d.rules) { d.rules = it }
            PxButton(S.done, Modifier.fillMaxWidth()) { sheet = null }
        }
        ComposeSheet.Place -> PlaceSheet(vm, { sheet = null }) { location = it; sheet = null }
        ComposeSheet.Tags -> PxSheet({ sheet = null }) {
            Text(S.tagPeople, style = T.title.copy(fontSize = 20.sp))
            ConnectedPeople(vm, "tag") { people ->
                LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    items(people.size, key = { people[it].username }) { i ->
                        val u = people[i]
                        val on = u.username in tags
                        UserRow(u, onClick = { tags = if (on) tags - u.username else tags + u.username }) {
                            Box(Modifier.size(22.dp).clip(RoundedCornerShape(6.dp)).background(if (on) Px.Lime else Px.Faint))
                        }
                    }
                }
            }
            PxButton(S.done, Modifier.fillMaxWidth()) { sheet = null }
        }
        ComposeSheet.Sound -> PxSheet({ sheet = null }) {
            Text(S.addSound, style = T.title.copy(fontSize = 20.sp))
            // a song like on Instagram (Deezer's 30 s preview), taken like an own sound file
            SongSearch(vm) { song ->
                scope.launch {
                    val bytes = vm.api.bytes("/api/songs/${song.id}/preview")
                    if (bytes == null) { vm.showToast(S.uploadFailed, error = true); return@launch }
                    val file = java.io.File(java.io.File(context.cacheDir, "songs").apply { mkdirs() }, "song_${song.id}.mp3")
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { file.writeBytes(bytes) }
                    sound = soundDraft(context, Uri.fromFile(file), listOf(song.artist, song.title).filter { it.isNotBlank() }.joinToString(" – "))
                    sheet = null
                }
            }
            Label(S.ownSound.uppercase())
            PxButton(S.pickAudioFile, Modifier.fillMaxWidth(), style = BtnStyle.Ghost) { sheet = null; audioPicker.launch("audio/*") }
            Row(verticalAlignment = Alignment.CenterVertically) {
                MicButton(onRecording = {}, onHint = { vm.showToast(it) }) { file, _ ->
                    sheet = null
                    sound = soundDraft(context, Uri.fromFile(file), S.originalSound)
                }
                Text(S.recordSound, style = T.body)
            }
        }
        null -> {}
    }
}

@Composable
private fun SlideTile(vm: AppViewModel, d: Draft, story: Boolean, onOpen: () -> Unit, onRemove: () -> Unit) {
    val S = LocalStrings.current
    val n = d.rules.count { it.value > 0 }
    Box(Modifier.size(96.dp, if (story) 170.dp else 120.dp).clip(RoundedCornerShape(14.dp)).background(Px.Faint)
        .border(1.dp, Px.Line, RoundedCornerShape(14.dp)).clickable(onClick = onOpen)) {
        val thumb = d.media?.let { vm.abs(it.thumb) } ?: if (!d.video) d.uri.toString() else null
        if (thumb != null) AsyncImage(thumb, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        d.overlay?.let { OverlayText(it) }
        if (d.video) PixelIcon("ui_video", 14.dp, Modifier.align(Alignment.BottomStart).padding(6.dp))
        if (d.media == null) Box(Modifier.fillMaxSize().background(Color(0x99000000)), contentAlignment = Alignment.Center) {
            Text(if (d.failed) "!" else S.uploading, style = T.small.copy(color = Px.Text))
        }
        Box(Modifier.align(Alignment.TopStart)) { IconBtn("ui_close", alpha = .85f) { onRemove() } }
        if (n > 0) Text("⚡$n", style = T.block.copy(fontSize = 10.sp, color = Px.Yellow),
            modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).clip(RoundedCornerShape(8.dp)).background(Color(0x990A0C14))
                .padding(horizontal = 5.dp, vertical = 2.dp))
    }
}

@Composable
private fun AddTile(icon: String, label: String, modifier: Modifier, onClick: () -> Unit) {
    var big by remember { mutableStateOf(false) }
    Column(
        modifier.onSizeChanged { big = it.height > 200 }.clip(RoundedCornerShape(14.dp)).border(2.dp, Px.Line, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        PixelIcon(icon, if (big) 28.dp else 18.dp, alpha = .8f)
        Spacer(Modifier.height(4.dp))
        Text(label, style = T.small.copy(fontSize = 11.sp))
    }
}

@Composable
private fun Chip(icon: String, label: String, on: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(12.dp)).background(if (on) Px.Cyan.copy(alpha = .1f) else Px.Faint)
            .border(BorderStroke(1.dp, if (on) Px.Cyan else Px.Line), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PixelIcon(icon, 16.dp)
        Spacer(Modifier.width(6.dp))
        Text(label, style = T.small.copy(fontSize = 13.sp, color = if (on) Px.Cyan else Px.Text), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Up to 5 profile rules for a picture, each tapped up to 10 times. */
@Composable
private fun RulePicker(vm: AppViewModel, picked: Map<Int, Int>, onChange: (Map<Int, Int>) -> Unit) {
    val S = LocalStrings.current
    val rules = profileRules(vm.state?.rules.orEmpty())
    val count = picked.count { it.value > 0 }
    fun set(id: Int, n: Int) {
        if (n > 0 && (picked[id] ?: 0) == 0 && count >= MAX_RULES) { vm.showToast(S.maxRulesPerSlide, error = true); return }
        onChange(picked + (id to n.coerceIn(0, MAX_TIMES)))
    }
    Label("${S.slideRules} · $count/$MAX_RULES".uppercase())
    Text(if (rules.isEmpty()) S.noProfileRules else S.slideRulesHint, style = T.small.copy(fontSize = 12.sp))
    LazyColumn(Modifier.heightIn(max = 300.dp)) {
        items(rules, key = { it.id }) { r ->
            val n = picked[r.id] ?: 0
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                PixelIcon(r.icon, 24.dp)
                Spacer(Modifier.width(10.dp))
                Text(r.name, style = T.body.copy(color = if (n > 0) Px.Text else Px.Muted), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f))
                StepBtn("−") { set(r.id, n - 1) }
                Text(if (n > 0) "×$n" else "–", style = T.block.copy(fontSize = 13.sp, color = if (r.isAcc) Px.Lime else Px.Orange),
                    modifier = Modifier.width(34.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                StepBtn("+") { set(r.id, n + 1) }
            }
        }
    }
}
