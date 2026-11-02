package com.momentum.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.momentum.app.AppViewModel
import com.momentum.app.data.Conversation
import com.momentum.app.data.ConversationResponse
import com.momentum.app.data.E2EStore
import com.momentum.app.data.MemberKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// End-to-end encryption on screen (= web/src/screens/Secure.tsx): a direct chat's overview, the encryption switch
// with the safety codes (also in a group's overview), the recovery key, unlocking a new phone.

/** A direct chat's overview: the other person, mute, encryption + safety codes. */
@Composable
fun ChatInfoScreen(vm: AppViewModel, id: Int) {
    val S = LocalStrings.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    var conv by remember { mutableStateOf<Conversation?>(null) }
    LaunchedEffect(id) { conv = vm.req<ConversationResponse>("GET", "/conversations/$id")?.conversation }
    Column(Modifier.fillMaxSize()) {
        TopBar(S.chatInfo)
        OfflineStrip(vm.online)
        val c = conv ?: run { if (!vm.online) OfflineState(); return@Column }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 40.dp)) {
            c.members.firstOrNull()?.let { other ->
                Column(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 8.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Avatar(other.displayName, other.avatar, 84.dp)
                    Text(other.username, style = T.title.copy(fontSize = 22.sp))
                    PxButton(S.viewProfile, style = BtnStyle.Ghost) { nav.push(Route.UserProfile(other.username)) }
                }
            }
            SwitchLine(S.muteChat, S.muteChatHint, c.muted, enabled = true) { v ->
                scope.launch {
                    vm.req<ConversationResponse>("PUT", "/conversations/$id/mute", buildJsonObject { put("muted", v) })
                        ?.let { conv = it.conversation; vm.refresh() }
                }
            }
            EncryptionSection(vm, c) { conv = it }
        }
    }
}

/** The encryption switch (direct: both, group: admins) and everyone's safety code to compare in person. */
@Composable
fun EncryptionSection(vm: AppViewModel, conv: Conversation, onConv: (Conversation) -> Unit) {
    val S = LocalStrings.current
    val scope = rememberCoroutineScope()
    var keys by remember { mutableStateOf(listOf<MemberKey>()) }
    LaunchedEffect(conv.id) { runCatching { keys = E2EStore.chatKeys(vm.api, conv.id, refresh = true) } }
    val me = vm.state?.user?.id
    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Label(S.e2eTitle.uppercase(), Modifier.padding(start = 16.dp, end = 16.dp, top = 22.dp, bottom = 6.dp))
        SwitchLine(S.e2eTitle, S.e2eHint + if (conv.canSwitchE2e) "" else " " + S.e2eOnlyAdmins, conv.e2e, enabled = conv.canSwitchE2e) { v ->
            scope.launch {
                vm.req<ConversationResponse>("PUT", "/conversations/${conv.id}/e2e", buildJsonObject { put("on", v) })?.let { onConv(it.conversation) }
            }
        }
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(S.safetyCodes, style = T.bodyStrong, modifier = Modifier.padding(top = 6.dp))
            Text(S.safetyHint, style = T.small.copy(fontSize = 12.sp))
            E2EStore.myPublicKey()?.let { CodeRow(S.yourCode, E2EStore.safetyCode(it)) }
            keys.filter { it.userId != me }.forEach { k ->
                CodeRow(k.username, k.public?.let(E2EStore::safetyCode) ?: "—", changed = k.userId in E2EStore.keyWarnings) {
                    E2EStore.trustKey(k.userId)
                }
            }
        }
    }
}

/** A switch with its title and hint (the pixel look of the group screen's switches). */
@Composable
private fun SwitchLine(title: String, hint: String, on: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().alpha(if (enabled) 1f else .55f).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = T.body)
            Text(hint, style = T.small.copy(fontSize = 12.sp))
        }
        Spacer(Modifier.width(10.dp))
        androidx.compose.material3.Switch(on, onChange, enabled = enabled, colors = groupSwitchColors())
    }
}

private val codeStyle = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium, fontSize = 15.sp, letterSpacing = .5.sp, color = Px.Text)

@Composable
private fun CodeRow(name: String, code: String, changed: Boolean = false, onOk: () -> Unit = {}) {
    val S = LocalStrings.current
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Px.Card)
        .then(if (changed) Modifier.border(1.dp, Px.Yellow, RoundedCornerShape(12.dp)) else Modifier)
        .padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(name, style = T.small.copy(color = if (changed) Px.Yellow else Px.Text))
        Text(code, style = codeStyle)
        if (changed) Row(verticalAlignment = Alignment.CenterVertically) {
            Text(S.keyChanged(name), style = T.small.copy(fontSize = 12.sp), modifier = Modifier.weight(1f))
            PxButton(S.keyChangedOk, style = BtnStyle.Ghost) { onOk() }
        }
    }
}

/** The recovery key, to write down (shown once after the key was made, later from the settings). */
@Composable
fun RecoveryScreen(vm: AppViewModel) {
    val S = LocalStrings.current
    val nav = LocalNav.current
    val ctx = LocalContext.current
    val code = remember { E2EStore.recoveryCode() }
    Column(Modifier.fillMaxSize()) {
        TopBar(S.recoveryTitle)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { PixelIcon("ui_lock", 40.dp, tint = Px.Yellow) }
            Text(S.recoveryIntro, style = T.body)
            if (code == null) Text(S.unlockCardHint, style = T.small)
            else {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Px.Card).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    code.split(" ").chunked(4).forEach { row ->
                        Row(Modifier.fillMaxWidth()) {
                            row.forEach { g ->
                                Text(g, style = codeStyle.copy(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
                                    modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                            }
                            repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
                PxButton(S.recoveryCopy, Modifier.fillMaxWidth(), style = BtnStyle.Ghost) {
                    val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText(S.recoveryTitle, code))
                    vm.showToast(S.copied)
                }
                PxButton(S.recoveryNoted, Modifier.fillMaxWidth()) { E2EStore.markRecoveryNoted(ctx); nav.pop() }
            }
        }
    }
}

/** A new phone: the recovery key opens the backup of my key; lost -> a new key. */
@Composable
fun UnlockScreen(vm: AppViewModel) {
    val S = LocalStrings.current
    val nav = LocalNav.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    fun go() {
        if (busy || code.isBlank()) return
        busy = true
        scope.launch {
            val ok = withContext(Dispatchers.IO) { E2EStore.unlock(ctx, vm.api, code) }
            busy = false
            if (ok) nav.pop() else vm.showToast(S.unlockWrong, error = true)
        }
    }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().imePadding()) {
            TopBar(S.unlockTitle)
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { PixelIcon("ui_lock", 40.dp, tint = Px.Yellow) }
                Text(S.unlockIntro, style = T.body)
                PxTextField(code, { code = it }, placeholder = S.unlockPlaceholder, onDone = { go() })
                PxButton(if (busy) "…" else S.unlockButton, Modifier.fillMaxWidth().alpha(if (code.isBlank()) .4f else 1f)) { go() }
                Text(S.resetKey, style = T.small.copy(color = Px.Cyan), modifier = Modifier.clickable { confirmReset = true }.padding(8.dp))
            }
        }
        if (confirmReset) PxSheet({ confirmReset = false }) {
            Text(S.resetKeyConfirm, style = T.title.copy(fontSize = 20.sp))
            Text(S.resetKeyWarning, style = T.small)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PxButton(S.cancel, Modifier.weight(1f), style = BtnStyle.Ghost) { confirmReset = false }
                PxButton(S.resetKeyConfirm, Modifier.weight(1f), style = BtnStyle.Danger) {
                    confirmReset = false
                    scope.launch { if (E2EStore.resetKey(ctx, vm.api)) nav.replace(Route.E2ERecovery) }
                }
            }
        }
    }
}
