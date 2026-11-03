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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import com.momentum.app.AppViewModel
import com.momentum.app.data.GoogleSignIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.put
import androidx.compose.ui.draw.alpha

/** DE | EN switch. */
@Composable
fun LanguageSwitch(vm: AppViewModel, modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Px.Faint)
            .padding(3.dp),
    ) {
        Lang.entries.forEach { l ->
            val on = vm.lang == l
            Box(
                Modifier
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (on) Px.Text else Color.Transparent)
                    .clickable { vm.setLanguage(l) }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            ) {
                Text(l.code.uppercase(), style = T.button.copy(color = if (on) Color.Black else Px.Muted))
            }
        }
    }
}

@Composable
fun LoginScreen(vm: AppViewModel) {
    val S = LocalStrings.current
    var register by remember { mutableStateOf(false) }
    var forgot by remember { mutableStateOf(false) }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var server by remember { mutableStateOf(vm.serverUrl) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val submit: () -> Unit = {
        if (!busy && user.isNotBlank() && pass.isNotEmpty()) {
            busy = true
            if (vm.devMode) vm.setServer(server)
            scope.launch {
                vm.auth(user.trim(), pass, register)
                busy = false
            }
        }
    }

    var logoTaps by remember { mutableStateOf(0) }
    // locked after too many wrong passwords: straight to the new password (the code is on its way)
    val locked = vm.lockedLogin
    androidx.compose.runtime.LaunchedEffect(locked) { if (locked != null) forgot = true }

    // Fills exactly one screen; it only becomes scrollable when the keyboard takes space.
    Box(Modifier.fillMaxSize()) {
        SceneBackground(Scene.Space, Modifier.matchParentSize(), sun = false)
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding(),
        ) {
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .heightIn(min = maxHeight)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.End) { LanguageSwitch(vm) }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Label(S.tagline)
                    FitText(
                        "Momentum", T.block, 48.sp,
                        Modifier
                            .fillMaxWidth(.9f)
                            .clickable(remember { MutableInteractionSource() }, indication = null) {
                                if (++logoTaps >= 7) { logoTaps = 0; vm.toggleDevMode() }
                            },
                    )
                    Spacer(Modifier.height(20.dp))
                    VehicleSprite("gauge", 3.5f)
                    Spacer(Modifier.height(24.dp))
                    PxCard(
                        Modifier.widthIn(max = 420.dp),
                        brush = Brush.verticalGradient(listOf(Color(0xE61B1E2B), Color(0xE613151D))),
                    ) {
                        if (forgot) androidx.compose.runtime.key(locked) {
                            ForgotPassword(vm, locked?.first ?: user, locked?.second) { vm.lockedLogin = null; forgot = false }
                        }
                        else Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            PxTextField(user, { user = it }, placeholder = if (register) S.email else S.emailOrUsername,
                                keyboardType = KeyboardType.Email, imeAction = ImeAction.Next)
                            PxTextField(pass, { pass = it }, placeholder = S.password, password = true, imeAction = ImeAction.Go, onDone = submit)
                            PxButton(if (register) S.register else S.login, Modifier.fillMaxWidth(), onClick = submit)
                            if (!register && vm.meta?.mail == true) Text(S.forgotPassword, style = T.small.copy(color = Px.Cyan),
                                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().clickable { forgot = true }.padding(4.dp))
                            vm.meta?.googleClientId?.let { clientId ->
                                val context = LocalContext.current
                                PxButton(S.googleSignIn, Modifier.fillMaxWidth(), style = BtnStyle.Ghost) {
                                    if (!busy) {
                                        busy = true
                                        if (vm.devMode) vm.setServer(server)
                                        scope.launch {
                                            try {
                                                GoogleSignIn.idToken(context, clientId)?.let { vm.googleAuth(it) }
                                            } catch (e: Exception) {
                                                vm.showToast(S.googleFailed, error = true)
                                            }
                                            busy = false
                                        }
                                    }
                                }
                            }
                            val uri = androidx.compose.ui.platform.LocalUriHandler.current
                            Text(
                                S.acceptTerms,
                                style = T.small.copy(fontSize = 12.sp, color = Px.Muted),
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { runCatching { uri.openUri((if (vm.devMode) server else vm.serverUrl).trimEnd('/') + "/terms") } },
                            )
                            Text(
                                if (register) S.toLogin else S.toRegister,
                                style = T.body.copy(color = Px.Muted),
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { register = !register }
                                    .padding(8.dp),
                            )
                            if (vm.devMode) {
                                Label(S.server)
                                PxTextField(server, { server = it }, keyboardType = KeyboardType.Uri)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

private val GLASS = Brush.verticalGradient(listOf(Color(0xE61B1E2B), Color(0xE613151D)))

/** The 6 digits of a mailed code (spaces allowed while typing). */
private fun codeInput(v: String) = v.filter { it.isDigit() || it == ' ' }.take(7)
private fun fullCode(v: String) = v.count { it.isDigit() } == 6

/** "Forgot password": a code to the account's confirmed address, then the code and a new password (logs in).
 *  [note]: the account is locked and the code already on its way - the server's words. */
@Composable
private fun ForgotPassword(vm: AppViewModel, initial: String, note: String?, onBack: () -> Unit) {
    val S = LocalStrings.current
    val scope = rememberCoroutineScope()
    var loginId by remember { mutableStateOf(initial) }
    var sent by remember { mutableStateOf(note != null) }
    var code by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val send: () -> Unit = {
        if (!busy && loginId.isNotBlank()) {
            busy = true
            scope.launch { if (vm.forgotPassword(loginId.trim())) sent = true; busy = false }
        }
    }
    val store: () -> Unit = {
        if (!busy && fullCode(code) && pass.length >= 6) {
            busy = true
            scope.launch { vm.resetPassword(loginId.trim(), code, pass); busy = false }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(S.newPassword, style = T.bodyStrong)
        Text(if (sent) note ?: S.forgotSent else S.forgotHint, style = T.small)
        PxTextField(loginId, { loginId = it }, placeholder = S.emailOrUsername, keyboardType = KeyboardType.Email, imeAction = ImeAction.Next)
        if (sent) {
            PxTextField(code, { code = codeInput(it) }, placeholder = S.confirmCode, keyboardType = KeyboardType.Number, imeAction = ImeAction.Next)
            PxTextField(pass, { pass = it }, placeholder = S.newPassword, password = true, imeAction = ImeAction.Go, onDone = store)
        }
        PxButton(if (sent) S.savePassword else S.sendCode, Modifier.fillMaxWidth(), onClick = if (sent) store else send)
        if (sent) Text(S.resendCode, style = T.small.copy(color = Px.Cyan), textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().clickable { send() }.padding(4.dp))
        Text(S.backToLogin, style = T.body.copy(color = Px.Muted), textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().clickable { onBack() }.padding(8.dp))
    }
}

/** Confirming the e-mail address (required, server/mail.py): the mailed code; another one after a minute, another
 *  address (typed wrong), log out. Opened before the deadline (reminder on the home tab) it has a back arrow. */
@Composable
fun ConfirmEmailScreen(vm: AppViewModel, opened: Boolean = false) {
    val S = LocalStrings.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val email = vm.state?.user?.email.orEmpty()
    val sent = vm.confirmSentTo == email
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var wait by remember { mutableStateOf(if (sent) 60 else 0) }
    var other by remember { mutableStateOf(false) }
    var newMail by remember { mutableStateOf("") }
    androidx.compose.runtime.LaunchedEffect(wait) { if (wait > 0) { kotlinx.coroutines.delay(1000); wait-- } }
    fun send(to: String? = null) {
        if (busy) return
        busy = true
        scope.launch {
            vm.sendConfirmCode(to)?.let { wait = it; other = false; code = "" }
            busy = false
        }
    }
    val submit: () -> Unit = {
        if (!busy && fullCode(code)) {
            busy = true
            scope.launch { if (vm.confirmEmail(code) && opened) nav.pop(); busy = false }
        }
    }
    val card: @Composable () -> Unit = {
        PxCard(Modifier.widthIn(max = 420.dp), brush = GLASS) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FitText(S.confirmEmailTitle, T.title, 22.sp, Modifier.fillMaxWidth())
                Text(if (sent) S.confirmEmailSent(email) else S.confirmEmailAsk(email), style = T.small)
                // another address (typed wrong): only that, until it's sent or cancelled
                if (other) {
                    PxTextField(newMail, { newMail = it.trim() }, placeholder = S.newEmail, keyboardType = KeyboardType.Email,
                        imeAction = ImeAction.Go, onDone = { send(newMail) })
                    PxButton(S.sendCode, Modifier.fillMaxWidth().alpha(if ('@' in newMail) 1f else .4f)) { if ('@' in newMail) send(newMail) }
                    Text(S.cancel, style = T.small, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().clickable { other = false }.padding(4.dp))
                } else {
                    if (sent) {
                        PxTextField(code, { code = codeInput(it) }, placeholder = S.confirmCode, keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Go, onDone = submit)
                        PxButton(S.confirmButton, Modifier.fillMaxWidth().alpha(if (fullCode(code)) 1f else .4f), onClick = submit)
                        Text(if (wait > 0) S.resendIn(wait) else S.resendCode, style = T.small.copy(color = if (wait > 0) Px.Muted else Px.Cyan),
                            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().clickable(enabled = wait <= 0) { send() }.padding(4.dp))
                        Text(S.checkSpam, style = T.small.copy(fontSize = 12.sp))
                    } else PxButton(S.sendCode, Modifier.fillMaxWidth()) { send() }
                    Text(S.otherAddress, style = T.small, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().clickable { other = true }.padding(4.dp))
                }
                if (!opened) Text(S.logout, style = T.body.copy(color = Px.Muted), textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().clickable { vm.logout() }.padding(8.dp))
            }
        }
    }
    if (opened) {
        Column(Modifier.fillMaxSize().imePadding()) {
            TopBar(S.confirmEmailTitle)
            Column(Modifier.verticalScroll(rememberScrollState()).fillMaxWidth().padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally) { card() }
        }
        return
    }
    Box(Modifier.fillMaxSize()) {
        SceneBackground(Scene.Space, Modifier.matchParentSize())
        BoxWithConstraints(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            Column(
                Modifier.verticalScroll(rememberScrollState()).heightIn(min = maxHeight).fillMaxWidth().padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) { card() }
        }
    }
}

/** Before the deadline: a reminder card (home tab) that opens the confirm screen. */
@Composable
fun ConfirmReminder(vm: AppViewModel) {
    val S = LocalStrings.current
    val nav = LocalNav.current
    val due = vm.state?.user?.emailConfirm?.takeIf { !it.required } ?: return
    val date = runCatching {
        java.time.Instant.parse(due.due).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
            .format(java.time.format.DateTimeFormatter.ofPattern(if (vm.lang.code == "de") "d. MMMM" else "d MMMM",
                java.util.Locale.forLanguageTag(vm.lang.code)))
    }.getOrDefault(due.due.take(10))
    Column(
        Modifier.fillMaxWidth().padding(bottom = 14.dp).clip(RoundedCornerShape(16.dp)).background(Px.Card)
            .border(1.dp, Px.Yellow, RoundedCornerShape(16.dp)).clickable { nav.push(Route.ConfirmEmail) }.padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(S.confirmDue(date), style = T.small.copy(color = Px.Text))
        Text(S.confirmNow.uppercase(), style = T.button.copy(color = Px.Yellow))
    }
}

/** Shown right after signing up (and for old accounts): pick the public @username. */
@Composable
fun UsernameScreen(vm: AppViewModel) {
    val S = LocalStrings.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val submit: () -> Unit = {
        if (!busy && name.length >= 3) {
            busy = true
            scope.launch {
                vm.save("PUT", "/settings", kotlinx.serialization.json.buildJsonObject { put("username", name) }, quiet = true)
                busy = false
            }
        }
    }
    Box(Modifier.fillMaxSize()) {
        SceneBackground(Scene.Space, Modifier.matchParentSize())
        BoxWithConstraints(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            Column(
                Modifier.verticalScroll(rememberScrollState()).heightIn(min = maxHeight).fillMaxWidth().padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                VehicleSprite("friends", 3f)
                Spacer(Modifier.height(20.dp))
                PxCard(Modifier.widthIn(max = 420.dp), brush = Brush.verticalGradient(listOf(Color(0xE61B1E2B), Color(0xE613151D)))) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        FitText(S.chooseUsername, T.title, 22.sp, Modifier.fillMaxWidth())
                        Text(S.chooseUsernameSub, style = T.small)
                        PxTextField(name, { name = it.lowercase().filter { c -> c.isLetterOrDigit() || c == '_' || c == '.' }.take(30) },
                            placeholder = "@name", imeAction = ImeAction.Go, onDone = submit)
                        Text(S.usernameHint, style = T.small.copy(fontSize = 12.sp))
                        PxButton(S.next, Modifier.fillMaxWidth().alpha(if (name.length >= 3) 1f else .4f), onClick = submit)
                        Text(S.logout, style = T.body.copy(color = Px.Muted), textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().clickable { vm.logout() }.padding(8.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun OfflineScreen(vm: AppViewModel) {
    val S = LocalStrings.current
    var server by remember { mutableStateOf(vm.serverUrl) }
    Box(Modifier.fillMaxSize()) {
        SceneBackground(Scene.Settings, Modifier.matchParentSize())
        Column(
            Modifier.align(Alignment.Center).padding(24.dp).widthIn(max = 420.dp).imePadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            PixelIcon("storm", 64.dp)
            Text(S.offline, style = T.body.copy(color = Px.Muted), textAlign = TextAlign.Center)
            Label(S.server)
            PxTextField(server, { server = it }, keyboardType = KeyboardType.Uri)
            PxButton(S.retry, Modifier.fillMaxWidth()) {
                vm.setServer(server)
                vm.boot()
            }
        }
        LanguageSwitch(vm, Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(16.dp))
    }
}
