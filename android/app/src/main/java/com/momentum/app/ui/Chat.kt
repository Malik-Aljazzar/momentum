package com.momentum.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.content.contentReceiver
import androidx.compose.foundation.content.consume
import androidx.compose.foundation.content.hasMediaType
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.momentum.app.AppViewModel
import com.momentum.app.data.ApiException
import com.momentum.app.data.ChatMessage
import com.momentum.app.data.Conversation
import com.momentum.app.data.E2EStore
import com.momentum.app.data.MediaItem
import com.momentum.app.data.MemberKey
import com.momentum.app.data.ConversationResponse
import com.momentum.app.data.ConversationsResponse
import com.momentum.app.data.MessageResponse
import com.momentum.app.data.MessagesResponse
import com.momentum.app.secondsSince
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val POLL_MS = 3000L

@Composable
fun ConversationAvatar(c: Conversation, size: androidx.compose.ui.unit.Dp) {
    if (!c.isGroup) {
        val u = c.members.firstOrNull()
        Avatar(u?.displayName ?: "?", u?.avatar, size)
    } else if (c.avatar != null || c.bannerImage != null) {
        RemoteImage(LocalUrl.current(c.avatar ?: c.bannerImage), Modifier.size(size).clip(RoundedCornerShape(size * 0.28f)))
    } else if (c.banner != null) {
        BannerBackground(c.banner, Modifier.size(size).clip(RoundedCornerShape(size * 0.28f)), still = true)
    } else {
        Avatar(c.name, null, size)
    }
}

/** Name with a small group icon in front for groups (the only marker that it's a group). */
@Composable
fun ConversationName(c: Conversation, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (c.isGroup) {
            PixelIcon("ui_group", 14.dp, alpha = .55f)
            Spacer(Modifier.width(6.dp))
        }
        Text(conversationTitle(c), style = T.bodyStrong, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false))
        if (c.muted) {
            Spacer(Modifier.width(6.dp))
            PixelIcon("ui_muted", 12.dp, alpha = .5f)
        }
    }
}

fun conversationTitle(c: Conversation) = if (c.isGroup) c.name else c.members.firstOrNull()?.displayName ?: "?"

@Composable
fun ChatsScreen(vm: AppViewModel, isTab: Boolean = false,
                listState: androidx.compose.foundation.lazy.LazyListState = androidx.compose.foundation.lazy.rememberLazyListState()) {
    val S = LocalStrings.current
    val nav = LocalNav.current
    var convs by remember { mutableStateOf<List<Conversation>?>(null) }
    // the top chats whenever the list comes up (its tab, back from a chat: report #66)
    val onTop = LocalOnTop.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(onTop) { if (onTop) { listState.scrollToItem(0); StoryRings.load(vm) } }
    LaunchedEffect(E2EStore.status) {
        while (true) {
            // encrypted last messages: their preview is opened here
            if (vm.foreground) vm.req<ConversationsResponse>("GET", "/conversations")?.let { r ->
                val atTop = !listState.canScrollBackward
                convs = withContext(Dispatchers.Default) { r.conversations.map { c -> c.last?.let { c.copy(last = E2EStore.openLast(c.id, it)) } ?: c } }
                // a chat moving to the top stays in view (the list would keep the old first one there)
                if (atTop) listState.requestScrollToItem(0)
            }
            delay(POLL_MS * 3)
        }
    }
    Column(Modifier.fillMaxSize()) {
        TopBar(S.chats, onBack = if (isTab) null else nav.pop) {
            IconBtn("ui_plus") { nav.push(Route.NewChat) }
            IconBtn("chat") { nav.push(Route.NewGroup) }
        }
        OfflineStrip(vm.online)
        val list = convs ?: run { if (!vm.online) OfflineState(); return@Column }
        if (list.isEmpty()) S.empty("chats").let { (t, sub) -> EmptyFriends(t, sub) }
        LazyColumn(state = listState,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = if (isTab) TAB_BAR_HEIGHT + 24.dp else 0.dp)) {
            if (E2EStore.status == E2EStore.Status.Locked) item(key = "e2e-unlock") {
                E2ECard(S.unlockCard, S.unlockCardHint) { nav.push(Route.E2EUnlock) }
            }
            if (E2EStore.status == E2EStore.Status.Ready && E2EStore.recoveryPending) item(key = "e2e-recovery") {
                E2ECard(S.recoveryCard, S.recoveryCardHint) { nav.push(Route.E2ERecovery) }
            }
            items(list, key = { it.id }) { c ->
                Row(
                    Modifier.fillMaxWidth().clickable { nav.push(Route.ChatRoom(c.id)) }.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // a person with new stories: the ring, a tap on it shows them (report #67)
                    val ring = if (c.isGroup) null else StoryRings.unseen(c.members.firstOrNull()?.username)
                    if (ring != null) StoryAvatar(ring.user, 42.dp, true, onClick = { StoryRings.open(vm, ring, scope) })
                    else ConversationAvatar(c, 52.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        ConversationName(c)
                        val last = c.last
                        val typingNow = typingShort(S, c.typing)
                        if (typingNow != null) {
                            Text(typingNow, maxLines = 1, overflow = TextOverflow.Ellipsis, style = T.small.copy(color = Px.Lime))
                        } else if (c.call != null) {
                            Text((if (c.call.video) "📹 " else "📞 ") + S.callRunning(c.call.joined), maxLines = 1,
                                overflow = TextOverflow.Ellipsis, style = T.small.copy(color = Px.Lime))
                        } else if (last != null) {
                            val who = when {
                                last.mine -> "${S.you}: "
                                c.isGroup -> "${last.sender}: "
                                else -> ""
                            }
                            val body = when {
                                last.kind == "call" && last.call != null -> callLine(S, last.call)
                                last.kind != null -> chatLine(S, last.kind, if (last.mine) null else last.sender,
                                    if (last.targetMe) null else last.target?.displayName.orEmpty())
                                last.sealed -> who + "🔒 " + S.e2eNewMessage
                                else -> who + last.text.ifBlank {
                                    when {
                                        last.mediaKind == "audio" -> "♪ ${S.voiceMessage}"
                                        last.mediaKind == "video" -> "▶ ${S.videoLabel}"
                                        last.hasMedia -> "▣ ${S.photo}"
                                        last.shareKind == "rule" -> "↗ ${S.sharedRule}"
                                        last.shareKind == "post" -> "↗ ${S.sharedPost}"
                                        last.shareKind == "profile" -> "↗ ${S.sharedProfile}"
                                        last.shareKind == "story" -> "↗ ${S.sharedStory}"
                                        last.shareKind == "challenge" -> "▲ ${S.challenge}"
                                        else -> ""
                                    }
                                }
                            }
                            Text((if (c.mentioned) S.mentionedPreview + " · " else "") + body + " · " + (secondsSince(last.at)?.let(S::timeAgo) ?: ""),
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = T.small.copy(color = if (c.mentioned) Px.Yellow else if (c.unread > 0) Px.Text else Px.Muted).userText())
                        } else if (c.isGroup) Text(S.members(c.members.size + 1), style = T.small)
                    }
                    if (c.mentioned) {
                        Box(Modifier.clip(RoundedCornerShape(8.dp)).background(Px.Yellow).padding(horizontal = 7.dp, vertical = 2.dp)) {
                            Text("@", style = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, lineHeight = 14.sp,    // the pixel font has no "@"
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Black, color = Color.Black))
                        }
                        Spacer(Modifier.width(6.dp))
                    }
                    if (c.unread > 0) {
                        Box(Modifier.clip(RoundedCornerShape(8.dp)).background(Px.Orange).padding(horizontal = 7.dp, vertical = 2.dp)) {
                            Text("${c.unread}", style = T.block.copy(fontSize = 11.sp, color = Color.Black))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ChatRoomScreen(vm: AppViewModel, id: Int) {
    val S = LocalStrings.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    var conv by remember { mutableStateOf<Conversation?>(null) }
    val messages = remember { mutableStateListOf<ChatMessage>() }
    var text by remember { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    var sendingPhoto by remember { mutableStateOf(false) }
    // a video on its way: shrunk here, sent, the server's turn (a bar above the input field)
    var videoBar by remember { mutableStateOf(false) }
    var videoProgress by remember { mutableStateOf<com.momentum.app.data.UploadProgress?>(null) }
    var readUpto by remember { mutableStateOf(0) }       // read receipts: my messages up to here were read
    var openRule by remember { mutableStateOf<Int?>(null) }
    var recordingSince by remember { mutableStateOf<Long?>(null) }
    var replyTo by remember { mutableStateOf<ChatMessage?>(null) }
    var flash by remember { mutableStateOf<Int?>(null) }          // quoted message jumped to: lit up briefly
    var typing by remember { mutableStateOf(listOf<com.momentum.app.data.Typing>()) }   // others typing / recording
    val list = rememberLazyListState()
    val cacheKey = "get:/conversations/$id/messages"
    var olderLeft by remember { mutableStateOf(true) }
    LoadMoreEffect(list, messages.size, olderLeft && vm.online, threshold = 8) {
        val first = messages.firstOrNull()?.id ?: return@LoadMoreEffect
        val res = vm.req<MessagesResponse>("GET", "/conversations/$id/messages?before=$first") ?: return@LoadMoreEffect
        val older = withContext(Dispatchers.Default) { res.messages.map { E2EStore.openMessage(id, it) } }
            .filter { m -> messages.none { it.id == m.id } }
        messages.addAll(0, older)
        olderLeft = res.messages.size >= 50
    }

    suspend fun poll() {
        val after = messages.lastOrNull()?.id
        val res = vm.req<MessagesResponse>("GET", "/conversations/$id/messages" + (after?.let { "?after=$it" } ?: "")) ?: return
        readUpto = maxOf(readUpto, res.readUpto)      // from the server or the cached chat
        typing = res.typing
        // encrypted ones are opened on this phone
        val new = withContext(Dispatchers.Default) { res.messages.filter { m -> messages.none { it.id == m.id } }.map { E2EStore.openMessage(id, it) } }
        // reactions of the latest messages may have changed
        if (res.reactedIds.isNotEmpty()) {
            val recent = res.reactedIds.toSet()
            messages.forEachIndexed { i, m ->
                if (m.id in recent) {
                    val now = res.reactions[m.id.toString()].orEmpty()
                    if (now != m.reactions) messages[i] = m.copy(reactions = now)
                }
            }
        }
        if (new.isNotEmpty()) {
            messages.addAll(new)
            list.animateScrollToItem(0)          // new messages pull the chat down
        }
        if (vm.online) {      // keep the whole chat cached, so it opens offline like WhatsApp (encrypted ones as they came)
            vm.cacheWrite(cacheKey, vm.api.json.encodeToString(MessagesResponse.serializer(),
                MessagesResponse(messages.takeLast(200).map { E2EStore.closeMessage(it) }, readUpto)))
        }
    }
    LaunchedEffect(id) {
        conv = vm.req<ConversationResponse>("GET", "/conversations/$id")?.conversation
        StoryRings.load(vm)                       // the pictures' rings: new stories (report #67)
        var n = 0
        while (true) {
            // in the background: no polling (it would mark messages as read -> no notification)
            if (vm.foreground) {
                poll()
                com.momentum.app.data.Notifier.clearChat(vm.getApplication(), id)    // read: its notifications go away
                // a group's running call (its "join" bar) comes with the chat itself
                if (conv?.isGroup == true && ++n % 2 == 0) runCatching {
                    conv = vm.api.call<ConversationResponse>("GET", "/conversations/$id").conversation
                }
            }
            delay(POLL_MS)
        }
    }
    // a call here ended: its chat line at once, the bar goes away
    val callHere = com.momentum.app.data.Calls.view?.takeIf { it.info.conversationId == id }?.phase
    LaunchedEffect(callHere) {
        if (callHere != com.momentum.app.data.CallPhase.Ended) return@LaunchedEffect
        poll()
        vm.req<ConversationResponse>("GET", "/conversations/$id")?.let { conv = it.conversation }
    }
    val askCall = rememberCallPermissions()
    fun ring(video: Boolean) {
        val c = conv ?: return
        askCall(video) { camera ->
            com.momentum.app.data.Calls.start(id, video, conversationTitle(c),
                if (c.isGroup) c.avatar else c.members.firstOrNull()?.avatar, camera)
        }
    }
    // encrypted: the members' keys (who can't read new messages yet, whose safety code changed)
    var keys by remember { mutableStateOf(listOf<MemberKey>()) }
    LaunchedEffect(conv?.e2e, E2EStore.status) {
        if (conv?.e2e == true && E2EStore.status == E2EStore.Status.Ready) runCatching { keys = E2EStore.chatKeys(vm.api, id) }
    }
    // unlocked meanwhile: open what couldn't be opened before
    LaunchedEffect(E2EStore.status) {
        if (E2EStore.status != E2EStore.Status.Ready || messages.none { it.sealed }) return@LaunchedEffect
        val all = withContext(Dispatchers.Default) { messages.toList().map { E2EStore.openMessage(id, it) } }
        messages.clear(); messages.addAll(all)
    }
    // encrypted chats: text and the media key go into the envelope, only members can open it (E2EStore)
    suspend fun send(text: String = "", media: MediaItem? = null, reply: ChatMessage? = replyTo, retry: Boolean = true,
                     link: com.momentum.app.data.LinkPreview? = null) {
        replyTo = null
        if (!vm.online) { vm.showToast(S.needsInternet, error = true); return }
        // an answer to a swiped message quotes it
        val base = buildJsonObject {
            reply?.let { put("reply_to", it.id) }
            media?.let { put("media_id", it.id) }
            if (conv?.e2e != true && text.isNotBlank()) put("text", text)
            // the link's preview (encrypted: inside the envelope)
            if (conv?.e2e != true && link != null) put("link", vm.api.json.encodeToJsonElement(com.momentum.app.data.LinkPreview.serializer(), link))
        }
        try {
            val body = if (conv?.e2e == true) {
                if (E2EStore.status != E2EStore.Status.Ready) { vm.showToast(S.e2eNeedsUnlock, error = true); return }
                JsonObject(base + E2EStore.sealMessage(vm.api, id, text, media?.key?.let { Triple(it, media.mime.orEmpty(), media.kind) }, link))
            } else base
            val m = E2EStore.openMessage(id, vm.api.call<MessageResponse>("POST", "/conversations/$id/messages", body).message)
            if (messages.none { it.id == m.id }) messages.add(m)
            list.animateScrollToItem(0)
        } catch (e: ApiException) {
            // the encryption was switched / a key or the members changed in the meantime: reload and send again
            if (e.status == 409 && retry) {
                val fresh = vm.req<ConversationResponse>("GET", "/conversations/$id")?.conversation
                fresh?.let { conv = it }
                runCatching { E2EStore.chatKeys(vm.api, id, refresh = true) }
                // a photo / voice message is uploaded encrypted or plain already: it goes again if the chat still matches
                if (media == null || (fresh != null && fresh.e2e == (media.key != null))) return send(text, media, reply, retry = false, link = link)
            }
            if (e.status == 0) vm.showToast(S.needsInternet, error = true) else vm.onError(e)
        }
    }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val queue = remember { mutableStateListOf<QueuedPhoto>() }
    // photos and videos (camera: photo or video; gallery: both)
    val photoPicker = rememberMediaPicker(video = true, multiple = true) { uris ->
        queue += uris.take(MAX_PHOTOS - queue.size).map { queuedOf(ctx, it) }
    }
    /** An encrypted chat's video: shrunk and sealed here with its still (the server can't re-encode it). */
    suspend fun sendSecureVideo(q: QueuedPhoto, caption: String) {
        videoProgress = com.momentum.app.data.UploadProgress.Compressing(0f)
        videoBar = true
        try {
            val v = com.momentum.app.data.secureVideo(ctx, q.uri) { videoProgress = com.momentum.app.data.UploadProgress.Compressing(it) }
            if (v == null) { vm.showToast(S.videoCantSend, error = true); return }
            val (key, full, thumb) = withContext(Dispatchers.Default) { E2EStore.sealMedia(v.bytes, v.still) }
            videoProgress = com.momentum.app.data.UploadProgress.Sending(0f)
            val seconds = "%.1f".format(java.util.Locale.US, v.duration)
            val m = vm.uploadEncrypted("/encrypted?kind=video&width=${v.width}&height=${v.height}&duration=$seconds", full) {
                videoProgress = if (it < 1f) com.momentum.app.data.UploadProgress.Sending(it) else com.momentum.app.data.UploadProgress.Processing
            } ?: return
            // without a still (unreadable frame) the bubble stays dark with its play badge
            val md = thumb?.let { vm.uploadEncrypted("/${m.id}/thumb", it) } ?: m
            send(caption, md.copy(key = key, mime = "video/mp4"))
        } finally {
            videoBar = false
        }
    }
    fun sendQueue() {
        val items = queue.toList()
        queue.clear()
        scope.launch {
            sendingPhoto = true
            // one message per photo / video, in order, each with its own caption
            for (q in items) {
                val caption = q.caption.trim()
                if (conv?.e2e == true) {
                    // encrypted here: the server can't shrink it, so it gets the picture and its small copy ready-made
                    if (E2EStore.status != E2EStore.Status.Ready) { vm.showToast(S.e2eNeedsUnlock, error = true); break }
                    if (q.video) { sendSecureVideo(q, caption); continue }
                    val p = withContext(Dispatchers.IO) { com.momentum.app.data.securePhoto(ctx, q.uri) } ?: continue
                    val (key, full, thumb) = withContext(Dispatchers.Default) { E2EStore.sealMedia(p.full, p.thumb) }
                    val m = vm.uploadEncrypted("/encrypted?kind=image&width=${p.width}&height=${p.height}", full) ?: continue
                    val md = vm.uploadEncrypted("/${m.id}/thumb", thumb!!) ?: continue
                    send(caption, md.copy(key = key, mime = "image/jpeg"))
                } else if (q.video) {
                    // plain: the server re-encodes it (a big one is shrunk here first)
                    videoProgress = null
                    videoBar = true
                    try { vm.uploadMedia(q.uri) { videoProgress = it }?.let { m -> send(caption, m) } } finally { videoBar = false }
                } else vm.uploadMedia(q.uri)?.let { m -> send(caption, m) }
            }
            sendingPhoto = false
        }
    }

    // the others see "X schreibt…" / "X nimmt eine Sprachnachricht auf…" (report #32): repeated while it lasts,
    // quietly (no error toasts for a hint)
    fun ping(kind: String?) {
        scope.launch { runCatching { vm.api.raw("POST", "/conversations/$id/typing", buildJsonObject { put("kind", kind) }) } }
    }
    var typingSent by remember { mutableStateOf(0L) }
    LaunchedEffect(text) {
        val now = System.currentTimeMillis()
        if (text.isNotBlank() && now - typingSent > 3000) { typingSent = now; ping("text") }
        else if (text.isBlank() && typingSent > 0) { typingSent = 0; ping(null) }
    }
    val recording = recordingSince != null
    var audioSent by remember { mutableStateOf(false) }
    LaunchedEffect(recording) {
        if (recording) { audioSent = true; while (true) { ping("audio"); delay(3000) } }
        else if (audioSent) { audioSent = false; ping(null) }
    }

    // a picture on the clipboard (e.g. a screenshot that was copied): offered above the input field (report #31),
    // each one only once and for a few seconds (report #53)
    val windowFocused = androidx.compose.ui.platform.LocalWindowInfo.current.isWindowFocused
    var clipImage by remember { mutableStateOf(false) }
    LaunchedEffect(windowFocused, queue.size) {
        if (windowFocused) {
            val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val stamp = cm.primaryClipDescription?.timestamp ?: 0L
            if (clipboardHasImage(ctx) && stamp != clipOffered) {
                clipOffered = stamp
                clipImage = true
                delay(CLIP_OFFER_MS)
            }
            clipImage = false
        }
    }
    fun pasteFromClipboard() {
        val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipImage = false
        val clip = cm.primaryClip ?: return
        val uris = (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }.mapNotNull { copyPasted(ctx, it) }
        queue += uris.take(MAX_PHOTOS - queue.size).map { QueuedPhoto(it) }
    }

    // a web link in the text: its preview is fetched while typing and goes with the message (like WhatsApp)
    val linkDraft = rememberLinkDraft(vm, text)
    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().imePadding()) {
        val c = conv
        TopBar(c?.let(::conversationTitle) ?: "") {
            // calls (server/calls.py): video and voice
            if (c != null && c.member) {
                IconBtn("ui_cam") { ring(true) }
                IconBtn("ui_handset") { ring(false) }
            }
            // group overview: members, group rules, challenges
            if (c != null) IconBtn(if (c.muted) "ui_muted" else "ui_bell", alpha = if (c.muted) .55f else .9f) {
                scope.launch {
                    vm.req<ConversationResponse>("PUT", "/conversations/$id/mute", buildJsonObject { put("muted", !c.muted) })
                        ?.let { conv = it.conversation; vm.showToast(if (it.conversation.muted) S.chatMuted else S.chatUnmuted); vm.refresh() }
                }
            }
            if (c?.isGroup == true) IconBtn("ui_group") { nav.push(Route.GroupInfo(id)) }
            if (c?.isGroup == false) IconBtn("ui_dots") { nav.push(Route.ChatInfo(id)) }
        }
        OfflineStrip(vm.online)
        // a group's call is on: who's in, join
        val running = c?.call
        if (c?.isGroup == true && running != null && running.me != "joined" && callHere == null) {
            Row(Modifier.fillMaxWidth().background(Px.Lime.copy(alpha = .12f)).padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                PixelIcon(if (running.video) "ui_cam" else "ui_handset", 18.dp, tint = Px.Lime)
                Spacer(Modifier.width(10.dp))
                Text(S.callRunning(running.joined), style = T.small.copy(color = Px.Lime), modifier = Modifier.weight(1f))
                PxButton(S.callJoin) { ring(running.video) }
            }
        }
        // reversed: the newest message is item 0 at the bottom, so the chat opens there
        val tz = vm.state?.settings?.timezone ?: "Europe/Berlin"
        val rows = withDays(photoGroups(messages), tz).asReversed()
        LazyColumn(Modifier.weight(1f).padding(horizontal = 12.dp), state = list, reverseLayout = true,
            verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.Bottom),
            // reversed list: "bottom" is the gap above the input field
            contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 8.dp, bottom = 14.dp)) {
            // photos one person sent in a row: one collage (the newest message carries it); a separator above
            // each day's first message: today, yesterday, weekday, date (report #49)
            items(rows, key = { it.key }) { row ->
                if (row.day != null) {
                    Box(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp), contentAlignment = Alignment.Center) {
                        Text(chatDayLabel(row.day, tz, S), style = T.small.copy(fontSize = 12.sp),
                            modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(Px.Card2).padding(horizontal = 10.dp, vertical = 3.dp))
                    }
                    return@items
                }
                val m = row.m!!
                if (m.kind == "call" && m.call != null) { CallLineView(S, m, tz) { ring(it) }; return@items }
                if (m.kind != null) { SystemLine(S, m); return@items }
                val photos = row.photos
                Bubble(vm, m, photos = photos, tz = tz, last = row.last, showSender = c?.isGroup == true, read = m.id <= readUpto, onOpenRule = { openRule = it },
                    highlighted = flash == m.id, onReply = { replyTo = m },
                    onReactions = { r -> val i = messages.indexOfFirst { it.id == m.id }; if (i >= 0) messages[i] = messages[i].copy(reactions = r) },
                    onQuote = { qid ->
                        val idx = rows.indexOfFirst { r -> r.m?.id == qid || r.photos.any { it.id == qid } }
                        if (idx >= 0) scope.launch {
                            list.animateScrollToItem(idx)
                            flash = qid
                            kotlinx.coroutines.delay(1200)
                            flash = null
                        }
                    })
            }
            if (c?.e2e == true && !(olderLeft && messages.size >= 50)) item(key = "e2e-note") {
                E2ENote(S.e2eChatNote) { nav.push(if (c.isGroup) Route.GroupInfo(id) else Route.ChatInfo(id)) }
            }
        }
        val changed = keys.filter { it.userId in E2EStore.keyWarnings }
        if (c?.e2e == true && changed.isNotEmpty()) Row(
            Modifier.fillMaxWidth().background(Px.Yellow.copy(alpha = .12f))
                .clickable { nav.push(if (c.isGroup) Route.GroupInfo(id) else Route.ChatInfo(id)) }.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PixelIcon("ui_lock", 14.dp, tint = Px.Yellow)
            Spacer(Modifier.width(8.dp))
            Text(S.keyChanged(changed.joinToString(", ") { it.username }), style = T.small.copy(color = Px.Yellow))
        }
        typingLine(S, typing)?.let {
            Text(it, style = T.small.copy(color = Px.Lime), maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
        }
        MentionSuggestions(vm, text, Modifier.padding(horizontal = 12.dp, vertical = 4.dp), scope = "&members_of=$id") { text = it }
        if (clipImage && vm.online) Row(
            Modifier.fillMaxWidth().background(Px.Card).clickable { pasteFromClipboard() }.padding(start = 14.dp, end = 4.dp, top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PixelIcon("ui_image", 18.dp)
            Spacer(Modifier.width(10.dp))
            Text(S.pasteImage, style = T.small.copy(color = Px.Cyan), modifier = Modifier.weight(1f))
            IconBtn("ui_close", alpha = .6f) { clipImage = false }
        }
        replyTo?.let { r ->
            // answering: the quoted message above the input field
            Row(Modifier.fillMaxWidth().background(Px.Card).padding(start = 12.dp, end = 4.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(3.dp).height(36.dp).background(Px.Yellow))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(S.replyingTo(if (r.mine) S.you else r.user?.username.orEmpty()), style = T.small.copy(fontSize = 12.sp, color = Px.Yellow))
                    Text(previewOf(S, r.text, r.media?.kind, r.share?.kind), style = T.small.copy(color = Px.Text).userText(),
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconBtn("ui_close", alpha = .6f) { replyTo = null }
            }
        }
        if (recordingSince == null && !(c?.e2e == true && E2EStore.status != E2EStore.Status.Ready)) DraftLink(linkDraft)
        if (videoBar) Box(Modifier.fillMaxWidth().background(Px.Card).padding(horizontal = 48.dp, vertical = 6.dp)) {
            UploadBar(videoProgress)
        }
        if (c?.e2e == true && E2EStore.status == E2EStore.Status.Locked) Box(
            Modifier.fillMaxWidth().background(Px.Card).navigationBarsPadding().padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            PxButton(S.unlockTitle, Modifier.fillMaxWidth()) { nav.push(Route.E2EUnlock) }
        } else Row(
            Modifier.fillMaxWidth().background(Px.Card).navigationBarsPadding().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconBtn("ui_image", alpha = if (sendingPhoto || !vm.online) .3f else .8f) {
                if (!vm.online) vm.showToast(S.needsInternet, error = true)
                else if (!sendingPhoto) photoPicker.open()
            }
            val sendText = {
                val t = text.trim()
                if (t.isNotEmpty()) {
                    text = ""
                    val link = linkDraft.preview
                    scope.launch { send(t, link = link) }
                }
            }
            Box(Modifier.weight(1f)) {
                val since = recordingSince
                if (since != null) RecordingIndicator(since, S.releaseToSend)
                else PxTextField(text, { text = it }, userContent = true, emoji = true, placeholder = if (vm.online) S.typeMessage else S.offlineStale,
                    imeAction = androidx.compose.ui.text.input.ImeAction.Send, onDone = { sendText() },
                    modifier = Modifier.pastedImages { uris ->
                        if (vm.online) queue += uris.mapNotNull { copyPasted(ctx, it) }.take(MAX_PHOTOS - queue.size).map { QueuedPhoto(it) }
                    })
            }
            if (text.isBlank()) {
                // voice message: hold to record, release to send
                MicButton(onRecording = { recordingSince = it }, onHint = { vm.showToast(it) }) { file, seconds ->
                    scope.launch {
                        if (conv?.e2e == true) {
                            if (E2EStore.status != E2EStore.Status.Ready) vm.showToast(S.e2eNeedsUnlock, error = true)
                            else {
                                val (key, sealed, _) = withContext(Dispatchers.IO) { E2EStore.sealMedia(file.readBytes()) }
                                vm.uploadEncrypted("/encrypted?kind=audio&duration=${"%.1f".format(java.util.Locale.US, seconds)}", sealed)
                                    ?.let { send(media = it.copy(key = key, mime = "audio/mp4")) }
                            }
                        } else vm.uploadMedia(android.net.Uri.fromFile(file), "audio/mp4")?.let { send(media = it) }
                        file.delete()
                    }
                }
            } else IconBtn("ui_send") { sendText() }
        }
    }
    // the photos to send: a layer over the chat (not a dialog window - those were cut off at the bottom
    // on Android 15+, hiding caption and send: reports #31, #34, #35)
    if (queue.isNotEmpty()) PhotoQueue(queue, onAdd = { photoPicker.open() }, onSend = { sendQueue() }, onClose = { queue.clear() })
    }
    openRule?.let { RuleDetailSheet(vm, it, onDismiss = { openRule = null }) }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun Bubble(vm: AppViewModel, m: ChatMessage, photos: List<ChatMessage> = emptyList(), tz: String, last: Boolean = true, showSender: Boolean, read: Boolean, onOpenRule: (Int) -> Unit,
                   highlighted: Boolean = false, onReply: () -> Unit = {}, onQuote: (Int) -> Unit = {},
                   onReactions: (List<com.momentum.app.data.Reaction>) -> Unit = {}) {
    val nav = LocalNav.current
    val S = LocalStrings.current
    val view = androidx.compose.ui.platform.LocalView.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var collageRect by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    // a single video (never in a photo group): opened in the big view; an encrypted one is decrypted first
    val video = m.media?.takeIf { it.isVideo && !m.sealed }
    var opening by remember { mutableStateOf(false) }
    fun openVideo(md: MediaItem) = scope.launch {
        if (opening) return@launch
        opening = true
        try {
            if (md.archived) {
                // only the still is left: shown as a photo
                val still = if (md.encrypted) E2EStore.mediaFile(ctx, vm.api, md, thumb = true) else md.thumb
                if (still != null) MediaViewing.open(listOf(ViewerItem(md.copy(kind = "image", url = still, thumb = still), m.text)))
                else vm.showToast(S.notAvailable, error = true)
            } else if (!md.encrypted) MediaViewing.open(listOf(ViewerItem(md, m.text)))
            else {
                val file = E2EStore.mediaFile(ctx, vm.api, md, thumb = false)
                if (file == null) vm.showToast(S.notAvailable, error = true)
                else {
                    val still = E2EStore.mediaFile(ctx, vm.api, md, thumb = true)
                    MediaViewing.open(listOf(ViewerItem(md.copy(url = file, thumb = still ?: md.thumb), m.text)))
                }
            }
        } finally {
            opening = false
        }
    }
    // swipe to the right to answer (like WhatsApp)
    val swipe = remember { androidx.compose.animation.core.Animatable(0f) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val trigger = with(density) { 64.dp.toPx() }
    var armed by remember { mutableStateOf(false) }
    // reactions: hold for the emoji row, double tap for a heart (like Instagram)
    var picking by remember { mutableStateOf(false) }
    val link = if (photos.size > 1) null else shownLink(m)
    var readers by remember { mutableStateOf(false) }
    var reactors by remember { mutableStateOf(false) }
    fun react(emoji: String) = scope.launch {
        vm.req<com.momentum.app.data.ReactionsResponse>("PUT", "/messages/${m.id}/reaction", buildJsonObject { put("emoji", emoji) })
            ?.let { onReactions(it.reactions) }
    }
    Box(Modifier.fillMaxWidth()
        .background(if (highlighted) Px.Yellow.copy(alpha = .12f) else Color.Transparent)
        .pointerInput(m.id) {
            detectHorizontalDragGestures(
                onDragEnd = {
                    if (swipe.value >= trigger) onReply()
                    armed = false
                    scope.launch { swipe.animateTo(0f) }
                },
                onDragCancel = { armed = false; scope.launch { swipe.animateTo(0f) } },
            ) { change, dx ->
                val next = (swipe.value + dx * .6f).coerceIn(0f, trigger * 1.4f)
                if (next >= trigger && !armed) { armed = true; view.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP) }
                if (next < trigger) armed = false
                scope.launch { swipe.snapTo(next) }
                change.consume()
            }
        }) {
    Box(Modifier.align(Alignment.CenterStart).padding(start = 6.dp).alpha((swipe.value / trigger).coerceIn(0f, 1f))) {
        PixelIcon("ui_back", 18.dp, alpha = if (armed) 1f else .6f)
    }
    Row(Modifier.fillMaxWidth().offset { androidx.compose.ui.unit.IntOffset(swipe.value.toInt(), 0) },
        horizontalArrangement = if (m.mine) Arrangement.End else Arrangement.Start) {
        // the picture only next to the last message of a run (the others keep its space); with new stories the
        // ring, a tap shows them (report #67)
        if (!m.mine && m.user != null) {
            val ring = StoryRings.unseen(m.user.username)
            if (last) Box(Modifier.align(Alignment.Bottom).clickable {
                if (ring != null) StoryRings.open(vm, ring, scope) else nav.push(Route.UserProfile(m.user.username))
            }) {
                if (ring != null) StoryAvatar(ring.user, 20.dp, true) else Avatar(m.user.displayName, m.user.avatar, 28.dp)
            } else Spacer(Modifier.width(28.dp))
            Spacer(Modifier.width(8.dp))
        }
        Column(horizontalAlignment = if (m.mine) Alignment.End else Alignment.Start) {
        if (picking) androidx.compose.ui.window.Popup(
            alignment = if (m.mine) Alignment.TopEnd else Alignment.TopStart,
            offset = androidx.compose.ui.unit.IntOffset(0, with(density) { -52.dp.roundToPx() }),
            onDismissRequest = { picking = false },
        ) {
            Row(Modifier.clip(RoundedCornerShape(24.dp)).background(Px.Card2).border(1.dp, Px.Line, RoundedCornerShape(24.dp))
                .padding(horizontal = 6.dp, vertical = 4.dp)) {
                REACTIONS.forEach { e ->
                    val mine = m.reactions.any { it.emoji == e && it.mine }
                    Text(e, fontSize = 24.sp, modifier = Modifier.clip(RoundedCornerShape(16.dp))
                        .background(if (mine) Px.Faint else Color.Transparent)
                        .clickable { picking = false; react(e) }.padding(horizontal = 6.dp, vertical = 4.dp))
                }
                // my message: who read it (report #44)
                if (m.mine) Box(Modifier.clip(RoundedCornerShape(16.dp)).clickable { picking = false; readers = true }
                    .padding(horizontal = 8.dp, vertical = 8.dp)) { PixelIcon("ui_eye", 20.dp) }
            }
        }
        if (readers) ReadersSheet(vm, m.id) { readers = false }
        if (reactors) ReactorsSheet(vm, m.id) { reactors = false }
        Column(
            Modifier.widthIn(max = 280.dp)
                .pointerInput(m.id, m.reactions, video) {
                    detectTapGestures(
                        onLongPress = { view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS); picking = true },
                        onDoubleTap = { if (m.reactions.none { it.emoji == REACTIONS[0] && it.mine }) react(REACTIONS[0]) },
                        // a tap on a photo (after the double-tap time): the big view of the group, each with its caption
                        onTap = { at ->
                            if (video != null) openVideo(video)
                            else if (photos.isNotEmpty()) scope.launch {
                                // encrypted photos: decrypted on the phone first
                                val items = photos.map { p ->
                                    val md = p.media!!
                                    if (!md.encrypted) ViewerItem(md, p.text)
                                    else E2EStore.mediaFile(ctx, vm.api, md, thumb = false).let { f -> ViewerItem(md.copy(url = f ?: md.url, thumb = f ?: md.thumb), p.text) }
                                }
                                MediaViewing.open(items, collageIndex(at, collageRect, photos.size))
                            }
                        },
                    )
                }
                .clip(RoundedCornerShape(18.dp))
                // a shared post is a card of its own: no bubble around it
                .background(if (m.share?.kind == "post" && m.text.isBlank()) Color.Transparent
                    else if (m.mine) Color(0xFF2B4A3A) else if (m.mentionsMe) Color(0xFF3A3424) else Px.Card2)
                .then(if (m.mentionsMe) Modifier.border(1.5.dp, Px.Yellow, RoundedCornerShape(18.dp)) else Modifier)
                .padding(if (m.media != null || m.share != null || link != null) 4.dp else 0.dp),
        ) {
            if (showSender && !m.mine && m.user != null) {
                Text(m.user.displayName, style = T.small.copy(fontSize = 12.sp, color = Px.Yellow),
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp))
            }
            m.reply?.let { r ->
                // the quoted message; a tap jumps to it
                Row(Modifier.padding(start = 8.dp, end = 8.dp, top = 8.dp).clip(RoundedCornerShape(10.dp))
                    .background(Color(0x33000000)).clickable { onQuote(r.id) }.padding(end = 10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(3.dp).height(38.dp).background(if (r.mine) Px.Lime else Px.Yellow))
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.padding(vertical = 5.dp)) {
                        Text(if (r.mine) S.you else r.user, style = T.small.copy(fontSize = 11.sp, color = if (r.mine) Px.Lime else Px.Yellow))
                        Text(previewOf(S, r.text, r.mediaKind, r.shareKind), style = T.small.copy(fontSize = 13.sp, color = Px.Text).userText(),
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            if (m.sealed) Row(Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                PixelIcon("ui_lock", 14.dp, alpha = .7f)
                Spacer(Modifier.width(6.dp))
                Text(if (E2EStore.status == E2EStore.Status.Locked) S.e2eLocked else S.e2eSealed, style = T.small)
            }
            if (photos.size > 1) Collage(vm, photos, Modifier.onGloballyPositioned { collageRect = it.boundsInParent() })
            else m.media?.takeIf { !m.sealed }?.let {
                if (it.kind == "audio") ChatAudio(vm, it)
                else if (it.isVideo) ChatVideo(vm, it, opening)
                else ChatImage(vm, it, Modifier.size(CHAT_MEDIA_DP.dp).clip(RoundedCornerShape(14.dp)).testTag("chat-photo"))
            }
            m.share?.let { ShareCard(vm, it, onOpenRule) }
            link?.let { LinkCard(it) { view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS); picking = true } }
            // a collage's captions are in the big view; a single photo / video shows it right under it
            if (m.text.isNotBlank() && photos.size <= 1) {
                Box(Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp)) {
                    MentionText(null, m.text)
                }
            }
            // a photo / video: when only its small copy (the still) is left (report #48); the time once per run of
            // the same minute (report #49); mine: ✓ sent, eye: read (by everyone in the chat)
            // under a photo / video / collage: as wide as it (the countdown may wrap), so the bubble keeps its square
            val expiring = if (photos.isNotEmpty()) photos else if (video != null) listOf(m) else emptyList()
            if (last || m.mine || expiring.isNotEmpty()) Row(
                (if (expiring.isNotEmpty()) Modifier.width((CHAT_MEDIA_DP + 8).dp) else Modifier.align(Alignment.End))
                    .padding(start = 12.dp, end = 10.dp, bottom = 6.dp, top = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                if (expiring.isNotEmpty()) Text(photoExpiry(S, expiring), style = T.small.copy(fontSize = 10.sp, color = Px.Muted), modifier = Modifier.weight(1f))
                if (last) Text(chatClock(m.at, tz), style = T.small.copy(fontSize = 11.sp, color = Px.Muted))
                if (m.mine) {
                    if (read) PixelIcon("ui_eye", 14.dp, tint = Px.Lime)
                    else Text("✓", style = T.small.copy(fontSize = 11.sp, color = Px.Muted))
                }
            } else Spacer(Modifier.height(8.dp))
        }
        // the reactions under the bubble; a tap on my own takes it back, holding shows who reacted (report #63)
        if (m.reactions.isNotEmpty()) Row(
            Modifier.offset(y = (-6).dp).padding(horizontal = 8.dp).clip(RoundedCornerShape(12.dp)).background(Px.Card)
                .border(1.dp, Px.Line, RoundedCornerShape(12.dp)).padding(horizontal = 4.dp, vertical = 1.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            m.reactions.forEach { r ->
                Text(if (r.count > 1) "${r.emoji} ${r.count}" else r.emoji, fontSize = 14.sp,
                    color = if (r.mine) Px.Yellow else Px.Text,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp))
                        .combinedClickable(onLongClick = {
                            view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS); reactors = true
                        }) { react(r.emoji) }
                        .padding(horizontal = 3.dp).testTag("reaction"))
            }
        }
        }
    }
    }
}


/** A line from the chat itself: who switched the encryption. */
@Composable
private fun SystemLine(S: Strings, m: ChatMessage) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
        Text(chatLine(S, m.kind.orEmpty(), if (m.mine) null else m.user?.displayName.orEmpty(),
            if (m.targetMe) null else m.target?.displayName.orEmpty()),
            style = T.small.copy(fontSize = 12.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center),
            modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(Px.Card2).padding(horizontal = 10.dp, vertical = 4.dp))
    }
}

/** A call in the chat: its kind and length, missed ones in red; a tap calls back the same way. */
@Composable
private fun CallLineView(S: Strings, m: ChatMessage, tz: String, onCall: (Boolean) -> Unit) {
    val c = m.call ?: return
    val color = if (callMissed(c)) Px.Orange else Px.Text
    Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
        Row(Modifier.clip(RoundedCornerShape(10.dp)).background(Px.Card2).clickable { onCall(c.video) }
            .padding(horizontal = 12.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            PixelIcon(if (c.video) "ui_cam" else "ui_handset", 14.dp, tint = color)
            Spacer(Modifier.width(6.dp))
            Text(callLine(S, c, emoji = false), style = T.small.copy(fontSize = 12.sp, color = color))
            Spacer(Modifier.width(6.dp))
            Text(chatClock(m.at, tz), style = T.small.copy(fontSize = 11.sp))
        }
    }
}

/** At the start of an encrypted chat (like WhatsApp): a tap shows the details. */
@Composable
private fun E2ENote(text: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 10.dp).clip(RoundedCornerShape(12.dp))
        .background(Px.Yellow.copy(alpha = .1f)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        PixelIcon("ui_lock", 14.dp, tint = Px.Yellow)
        Spacer(Modifier.width(8.dp))
        Text(text, style = T.small.copy(fontSize = 12.sp, color = Px.Yellow))
    }
}

/** A reminder above the chat list: save the recovery key / unlock this phone. */
@Composable
private fun E2ECard(title: String, hint: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 10.dp).clip(RoundedCornerShape(16.dp))
        .background(Px.Card).border(1.dp, Px.Yellow, RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically) {
        PixelIcon("ui_lock", 22.dp, tint = Px.Yellow)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = T.bodyStrong)
            Text(hint, style = T.small)
        }
    }
}

/** The address to show a chat medium with: an encrypted one is decrypted into the cache first (null meanwhile). */
@Composable
private fun rememberMediaUrl(vm: AppViewModel, media: MediaItem, thumb: Boolean): String? {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val plain = vm.abs(if (thumb) media.thumb else media.url)
    var url by remember(media.url, media.thumb, media.key, thumb) { mutableStateOf(if (media.encrypted) null else plain) }
    if (media.encrypted) LaunchedEffect(media.url, media.thumb, media.key, thumb) { url = E2EStore.mediaFile(ctx, vm.api, media, thumb) }
    return url
}

/** A chat photo (an encrypted one is opened here first): the full picture - the 480 px thumb is blurry on a
 *  240 dp bubble (≈ 720 real pixels) - with the thumb meanwhile; archived photos only have the thumb (= Chat.tsx). */
@Composable
private fun ChatImage(vm: AppViewModel, media: MediaItem, modifier: Modifier) =
    RemoteImage(rememberMediaUrl(vm, media, thumb = false), modifier,
        fallback = { RemoteImage(rememberMediaUrl(vm, media, thumb = true), Modifier.fillMaxSize()) })

/** A voice message (an encrypted one is opened here first). */
@Composable
private fun ChatAudio(vm: AppViewModel, media: MediaItem) {
    val url = rememberMediaUrl(vm, media, thumb = false)
    if (url != null) AudioBubble(url, media.duration) else Spacer(Modifier.width(220.dp).height(52.dp))
}

/** A single chat photo or video: a square, center-cropped (like WhatsApp), in dp (= chatphoto.ts CHAT_MEDIA). */
internal const val CHAT_MEDIA_DP = 240

/** A chat video's length on its bubble: "0:07", "1:00", "10:05"; empty without one (= chatphoto.ts videoLength). */
internal fun videoLength(seconds: Double): String {
    if (seconds <= 0) return ""
    val s = Math.round(seconds).toInt()
    return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
}

/** A chat video: its still in the square with a play badge and its length; archived: only the still (= Chat.tsx). */
@Composable
private fun ChatVideo(vm: AppViewModel, media: MediaItem, opening: Boolean) {
    Box(Modifier.size(CHAT_MEDIA_DP.dp).clip(RoundedCornerShape(14.dp)).background(Color.Black).testTag("chat-video")) {
        RemoteImage(rememberMediaUrl(vm, media, thumb = true), Modifier.fillMaxSize())
        if (!media.archived) {
            Box(Modifier.align(Alignment.Center).size(44.dp).clip(androidx.compose.foundation.shape.CircleShape)
                .background(Color(0x99000000)).alpha(if (opening) .45f else 1f), contentAlignment = Alignment.Center) {
                PixelIcon("ui_play", 20.dp)
            }
            videoLength(media.duration).takeIf { it.isNotEmpty() }?.let {
                Text(it, style = T.small.copy(fontSize = 11.sp, color = Color.White),
                    modifier = Modifier.align(Alignment.BottomStart).padding(6.dp).clip(RoundedCornerShape(8.dp))
                        .background(Color(0x99000000)).padding(horizontal = 6.dp, vertical = 2.dp))
            }
        }
    }
}

/** Who read one of my messages (report #44). */
@Composable
private fun ReadersSheet(vm: AppViewModel, messageId: Int, onDismiss: () -> Unit) {
    val S = LocalStrings.current
    val nav = LocalNav.current
    var data by remember { mutableStateOf<com.momentum.app.data.ReadersResponse?>(null) }
    LaunchedEffect(messageId) { data = vm.req<com.momentum.app.data.ReadersResponse>("GET", "/messages/$messageId/readers") }
    PxSheet(onDismiss) {
        Text(S.readBy, style = T.title.copy(fontSize = 20.sp))
        val d = data ?: return@PxSheet
        LazyColumn(Modifier.heightIn(max = 380.dp)) {
            items(d.readers, key = { it.username }) { u -> UserRow(u, onClick = { onDismiss(); nav.push(Route.UserProfile(u.username)) }) }
        }
        if (d.unread > 0) Text(S.notReadYet(d.unread), style = T.small)
    }
}

/** Who reacted with what (report #63): everyone with their emoji, a tap opens the profile. */
@Composable
private fun ReactorsSheet(vm: AppViewModel, messageId: Int, onDismiss: () -> Unit) {
    val S = LocalStrings.current
    val nav = LocalNav.current
    var data by remember { mutableStateOf<com.momentum.app.data.ReactorsResponse?>(null) }
    LaunchedEffect(messageId) { data = vm.req<com.momentum.app.data.ReactorsResponse>("GET", "/messages/$messageId/reactions") }
    PxSheet(onDismiss) {
        Text(S.reactions, style = T.title.copy(fontSize = 20.sp))
        val d = data ?: return@PxSheet
        LazyColumn(Modifier.heightIn(max = 380.dp)) {
            items(d.reactions, key = { it.user.username }) { r ->
                UserRow(r.user, onClick = { onDismiss(); nav.push(Route.UserProfile(r.user.username)) }) {
                    Text(r.emoji, fontSize = 22.sp)
                }
            }
        }
    }
}

private val REACTIONS = listOf("❤️", "😂", "😮", "😢", "🔥", "👍")

/** One line for a quoted message: its text, or what it contains. */
internal fun previewOf(S: Strings, text: String, mediaKind: String?, shareKind: String?) = text.ifBlank {
    when {
        mediaKind == "audio" -> "♪ ${S.voiceMessage}"
        mediaKind == "video" -> "▶ ${S.videoLabel}"
        mediaKind != null -> "▣ ${S.photo}"
        shareKind == "rule" -> "↗ ${S.sharedRule}"
        shareKind == "post" -> "↗ ${S.sharedPost}"
        shareKind == "profile" -> "↗ ${S.sharedProfile}"
        shareKind == "story" -> "↗ ${S.sharedStory}"
        shareKind == "challenge" -> S.challenge
        else -> ""
    }
}

/** Pictures the keyboard inserts (its clipboard / screenshot suggestions) go to the photo queue. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun Modifier.pastedImages(onImages: (List<android.net.Uri>) -> Unit): Modifier =
    this.then(Modifier.contentReceiver { content ->
        if (!content.hasMediaType(androidx.compose.foundation.content.MediaType.Image)) return@contentReceiver content
        val uris = mutableListOf<android.net.Uri>()
        val rest = content.consume { item -> item.uri?.let { uris += it; true } ?: false }
        if (uris.isNotEmpty()) onImages(uris)
        rest
    })

/** "anna schreibt…", "anna und ben schreiben…", "anna nimmt eine Sprachnachricht auf…" (report #32). */
fun typingLine(S: Strings, t: List<com.momentum.app.data.Typing>): String? {
    val audio = t.filter { it.kind == "audio" }
    val text = t.filter { it.kind != "audio" }
    return when {
        audio.size == 1 -> S.recordingOne(audio[0].username)
        audio.size > 1 -> S.recordingMany(audio.size)
        text.size == 1 -> S.typingOne(text[0].username)
        text.size == 2 -> S.typingTwo(text[0].username, text[1].username)
        text.size > 2 -> S.typingMany(text.size)
        else -> null
    }
}

/** The chat list: "schreibt…" / "nimmt Audio auf…" (groups with the name). */
private fun typingShort(S: Strings, t: List<com.momentum.app.data.Typing>): String? =
    if (t.size > 1) typingLine(S, t)
    else t.firstOrNull()?.let { it.username + " " + if (it.kind == "audio") S.recordingShort else S.typingShort }

/** Copies a picture from the clipboard / keyboard into the app's cache: it stays sendable when the clipboard
 *  changes (report #31). */
private fun copyPasted(ctx: android.content.Context, uri: android.net.Uri): android.net.Uri? = runCatching {
    val dir = java.io.File(ctx.cacheDir, "pasted").apply { mkdirs() }
    val type = ctx.contentResolver.getType(uri).orEmpty()
    val file = java.io.File(dir, "paste_${System.currentTimeMillis()}." + when {
        type.endsWith("png") -> "png"; type.endsWith("gif") -> "gif"; type.endsWith("webp") -> "webp"; else -> "jpg"
    })
    ctx.contentResolver.openInputStream(uri)!!.use { input -> file.outputStream().use { input.copyTo(it) } }
    android.net.Uri.fromFile(file)
}.getOrNull()

/** How long the clipboard picture is offered; the clipboard's timestamp of the last one offered (in any chat). */
private const val CLIP_OFFER_MS = 6_000L
private var clipOffered = -1L

/** Is there a picture on the clipboard? Reads only its type (no "pasted from clipboard" notice). */
private fun clipboardHasImage(ctx: android.content.Context): Boolean {
    val d = (ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).primaryClipDescription
        ?: return false
    return (0 until d.mimeTypeCount).any { d.getMimeType(it).startsWith("image/") }
}

@Composable
fun NewChatScreen(vm: AppViewModel) {
    val S = LocalStrings.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize()) {
        TopBar(S.newMessage)
        ConnectedPeople(vm, "chat") { people ->
            LazyColumn {
                items(people, key = { it.username }) { u ->
                    UserRow(u, onClick = {
                        scope.launch {
                            vm.req<ConversationResponse>("POST", "/conversations/direct", buildJsonObject { put("username", u.username) })
                                ?.let { nav.replace(Route.ChatRoom(it.conversation.id)) }
                        }
                    })
                }
            }
        }
    }
}

@Composable
fun NewGroupScreen(vm: AppViewModel) {
    val S = LocalStrings.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf(setOf<String>()) }
    val ready = name.isNotBlank() && picked.isNotEmpty()
    Column(Modifier.fillMaxSize().imePadding()) {
        TopBar(S.newGroup) {
            Box(
                Modifier.padding(end = 8.dp).clip(RoundedCornerShape(12.dp)).background(if (ready) Px.Text else Px.Faint)
                    .clickable(enabled = ready) {
                        scope.launch {
                            val body = buildJsonObject {
                                put("name", name.trim())
                                put("usernames", JsonArray(picked.map { JsonPrimitive(it) }))
                            }
                            vm.req<ConversationResponse>("POST", "/conversations/group", body)?.let { nav.replace(Route.ChatRoom(it.conversation.id)) }
                        }
                    }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) { Text(S.create.uppercase(), style = T.button.copy(color = if (ready) Color.Black else Px.Muted)) }
        }
        Box(Modifier.padding(16.dp)) { PxTextField(name, { name = it }, placeholder = S.groupName, emoji = true) }
        Text(S.pickPeople, style = T.small, modifier = Modifier.padding(horizontal = 16.dp))
        ConnectedPeople(vm, "group") { people ->
            LazyColumn {
                items(people, key = { it.username }) { u ->
                    val on = u.username in picked
                    UserRow(u, onClick = { picked = if (on) picked - u.username else picked + u.username }) {
                        Box(Modifier.size(22.dp).clip(RoundedCornerShape(6.dp)).background(if (on) Px.Lime else Px.Faint))
                    }
                }
            }
        }
    }
}


private const val MAX_PHOTOS = 10

/** A photo / video picked for sending, with its caption. */
private class QueuedPhoto(val uri: android.net.Uri, val video: Boolean = false) { var caption by mutableStateOf("") }

/** A picked file for the queue: a video by its type (file:// from the in-app camera: by its extension). */
private fun queuedOf(ctx: android.content.Context, uri: android.net.Uri): QueuedPhoto {
    val type = ctx.contentResolver.getType(uri) ?: uri.lastPathSegment?.substringAfterLast('.', "")?.lowercase()
        ?.let { android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }
    return QueuedPhoto(uri, type?.startsWith("video/") == true)
}

/** Photos group into collages; videos never do (each one is its own bubble). */
internal fun ChatMessage.isPhoto() = media?.kind == "image" && share == null

/** A row of the chat list: a day separator or a message (with the photos it carries). */
private class ChatRow(val key: String, val day: java.time.LocalDate? = null, val m: ChatMessage? = null, val photos: List<ChatMessage> = emptyList(),
                      /** the last message of a run one person sent within the same minute: picture and time */
                      val last: Boolean = true)

/** The message groups with a day separator before each new day (in the player's time zone). */
private fun withDays(groups: List<Pair<ChatMessage, List<ChatMessage>>>, tz: String): List<ChatRow> {
    val out = mutableListOf<ChatRow>()
    var last: java.time.LocalDate? = null
    groups.forEachIndexed { i, (m, photos) ->
        val day = chatDay((photos.firstOrNull() ?: m).at, tz)
        if (day != null && day != last) { out += ChatRow("day-$day", day = day); last = day }
        val next = groups.getOrNull(i + 1)?.first
        val end = next == null || next.mine != m.mine || next.user?.username != m.user?.username ||
            chatMinute(next.at, tz) != chatMinute(m.at, tz)
        out += ChatRow("m-${m.id}", m = m, photos = photos, last = end)
    }
    return out
}

/** Under chat photos / a video: "deleted in 13 d 04:12" until only the small copy (the still) is left, then "archived". */
internal fun photoExpiry(S: Strings, photos: List<ChatMessage>): String {
    val media = photos.mapNotNull { it.media }
    if (media.isNotEmpty() && media.all { it.archived }) return if (media.all { it.isVideo }) S.videoArchived else S.photoArchived
    val left = media.mapNotNull { it.expiresAt }.minOrNull()?.let { photoCountdown(it) } ?: return ""
    return S.deletedIn(left.first, left.second)
}

/** Messages for the list; photos the same person sent one after another become one group. */
internal fun photoGroups(messages: List<ChatMessage>): List<Pair<ChatMessage, List<ChatMessage>>> {
    val out = mutableListOf<Pair<ChatMessage, MutableList<ChatMessage>>>()
    for (m in messages) {
        val last = out.lastOrNull()
        if (last != null && m.isPhoto() && last.first.isPhoto() && m.reply == null && last.first.mine == m.mine && last.first.user?.username == m.user?.username) {
            last.second += m
            out[out.lastIndex] = m to last.second
        } else out += m to (if (m.isPhoto()) mutableListOf(m) else mutableListOf())
    }
    return out
}

/** Which photo of the collage a tap at [at] (bubble coordinates) hit. */
private fun collageIndex(at: androidx.compose.ui.geometry.Offset, rect: androidx.compose.ui.geometry.Rect?, n: Int): Int {
    if (rect == null || n <= 1 || !rect.contains(at)) return 0
    val right = at.x - rect.left > rect.width / 2
    val lower = at.y - rect.top > rect.height / 2
    return when (minOf(n, 4)) {
        2 -> if (right) 1 else 0
        3 -> if (!right) 0 else if (lower) 2 else 1
        else -> (if (lower) 2 else 0) + (if (right) 1 else 0)
    }
}

/** Up to 4 photos in a grid; more: "+N" over the last one. */
@Composable
private fun Collage(vm: AppViewModel, photos: List<ChatMessage>, modifier: Modifier = Modifier) {
    val shown = photos.take(4)
    val more = photos.size - 4
    @Composable
    fun cell(p: ChatMessage, i: Int, mod: Modifier) = Box(mod.clip(RoundedCornerShape(10.dp))) {
        ChatImage(vm, p.media!!, Modifier.fillMaxSize())
        if (i == 3 && more > 0) Box(Modifier.fillMaxSize().background(Color(0x80000000)), contentAlignment = Alignment.Center) {
            Text("+$more", style = T.block.copy(fontSize = 22.sp, color = Color.White))
        }
    }
    val gap = 3.dp
    Column(modifier.width(240.dp), verticalArrangement = Arrangement.spacedBy(gap)) {
        when (shown.size) {
            2 -> Row(horizontalArrangement = Arrangement.spacedBy(gap)) { shown.forEachIndexed { i, p -> cell(p, i, Modifier.weight(1f).height(170.dp)) } }
            3 -> Row(Modifier.height(239.dp), horizontalArrangement = Arrangement.spacedBy(gap)) {
                cell(shown[0], 0, Modifier.weight(1f).fillMaxHeight())
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(gap)) {
                    cell(shown[1], 1, Modifier.fillMaxWidth().weight(1f)); cell(shown[2], 2, Modifier.fillMaxWidth().weight(1f))
                }
            }
            else -> shown.chunked(2).forEachIndexed { r, pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) { pair.forEachIndexed { k, p -> cell(p, r * 2 + k, Modifier.weight(1f).height(118.dp)) } }
            }
        }
    }
}

/** The photos picked for sending: a big preview with its caption, the strip, add more, send. */
@Composable
private fun PhotoQueue(queue: MutableList<QueuedPhoto>, onAdd: () -> Unit, onSend: () -> Unit, onClose: () -> Unit) {
    val S = LocalStrings.current
    var sel by remember { mutableStateOf(0) }
    val i = sel.coerceIn(0, queue.lastIndex)
    val cur = queue.getOrNull(i) ?: return
    androidx.activity.compose.BackHandler { onClose() }
    // a full-screen layer that swallows touches; the bottom bar (caption + send) stays above the navigation bar /
    // gesture area and the keyboard
    Box(Modifier.fillMaxSize().background(Color.Black).pointerInput(Unit) { detectTapGestures { } }) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.statusBarsPadding().padding(start = 8.dp, top = 8.dp, end = 8.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconBtn("ui_close") { onClose() }
                Text("${i + 1} / ${queue.size}", style = T.small.copy(color = Px.Text))
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                // a video plays muted right away (like a post's, report #65)
                if (cur.video) androidx.compose.runtime.key(cur.uri) { VideoPlayer(cur.uri.toString(), null, playing = true, preview = true) }
                else coil3.compose.AsyncImage(cur.uri, null, contentScale = androidx.compose.ui.layout.ContentScale.Fit, modifier = Modifier.fillMaxSize())
            }
            Column(Modifier.fillMaxWidth().background(Px.Card)
                .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime)).padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(queue.size) { k ->
                        Box(Modifier.size(54.dp)) {
                            val tile = Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp))
                                .then(if (k == i) Modifier.border(2.dp, Px.Cyan, RoundedCornerShape(8.dp)) else Modifier)
                                .clickable { sel = k }
                            if (queue[k].video) QueuedVideoTile(queue[k].uri, tile)
                            else coil3.compose.AsyncImage(queue[k].uri, null, contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = tile)
                            Box(Modifier.align(Alignment.TopEnd).size(18.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xB3000000))
                                .clickable { queue.removeAt(k); sel = maxOf(0, k - 1) }, contentAlignment = Alignment.Center) {
                                Text("✕", style = T.block.copy(fontSize = 10.sp, color = Color.White))
                            }
                        }
                    }
                    if (queue.size < MAX_PHOTOS) item {
                        Box(Modifier.size(54.dp).border(2.dp, Px.Line, RoundedCornerShape(8.dp)).clickable { onAdd() }, contentAlignment = Alignment.Center) {
                            PixelIcon("ui_plus", 18.dp)
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        androidx.compose.runtime.key(cur) { PxTextField(cur.caption, { cur.caption = it.take(500) }, placeholder = S.captionForPhoto, userContent = true, emoji = true) }
                    }
                    IconBtn("ui_send") { onSend() }
                }
            }
        }
    }
}

/** A queued video in the strip: its first frame with a small video badge. */
@Composable
private fun QueuedVideoTile(uri: android.net.Uri, modifier: Modifier) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var frame by remember(uri) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    LaunchedEffect(uri) { frame = withContext(Dispatchers.IO) { com.momentum.app.data.videoFrame(ctx, uri)?.asImageBitmap() } }
    Box(modifier.background(Px.Faint)) {
        frame?.let { androidx.compose.foundation.Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        PixelIcon("ui_video", 14.dp, Modifier.align(Alignment.BottomStart).padding(4.dp))
    }
}
