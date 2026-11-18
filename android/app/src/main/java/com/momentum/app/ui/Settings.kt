package com.momentum.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.momentum.app.AppViewModel
import com.momentum.app.data.AppState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// Settings (web/src/screens/Settings.tsx): a menu of pages, one level deep. Each page with fields edits them locally;
// its always-visible save button stores exactly what changed (profile, game settings, language, password, picture).

/** The settings' menu entries: a page of their own, or (report, news) what they open. = MENU in web/src/screens/Settings.tsx */
private val MENU = listOf("profile" to "ui_edit", "game" to "ui_gauge", "app" to "ui_gear", "tabs" to "ui_grid", "security" to "shield",
    "extensions" to "sparkles", "report" to "bulb", "news" to "star", "legal" to "openbook", "account" to "ui_lock")

@Composable
private fun menuText(key: String): Pair<String, String> {
    val S = LocalStrings.current
    return when (key) {
        "profile" -> S.profile to S.settingsProfileHint
        "game" -> S.settingsGame to S.settingsGameHint
        "app" -> S.settingsApp to S.settingsAppHint
        "tabs" -> S.settingsTabs to S.settingsTabsHint
        "security" -> S.settingsSecurity to S.settingsSecurityHint
        "extensions" -> S.extensionsTitle to S.extensionsHint
        "report" -> S.reportTitle to S.settingsReportHint
        "news" -> S.whatsNewTitle to S.settingsNewsHint
        "legal" -> S.legalSection to S.settingsLegalHint
        else -> S.settingsAccount to S.settingsAccountHint
    }
}

/** The settings opened from the profile: a menu of pages one level deep, or one of its pages ([page]). */
@Composable
fun SettingsRoute(vm: AppViewModel, st: AppState, page: String?) {
    if (page == null) SettingsMenu(vm, st) else Column(Modifier.fillMaxSize()) {
        TopBar(menuText(page).first)
        Box(Modifier.weight(1f)) {
            when (page) {
                "profile" -> ProfileSettings(vm, st)
                "game" -> GameSettings(vm, st)
                "app" -> AppSettings(vm)
                "tabs" -> TabsSettings(vm, st)
                "security" -> SecuritySettings(vm, st)
                "extensions" -> ExtensionsPage(vm)
                "legal" -> LegalSettings(vm)
                else -> AccountSettings(vm, st)
            }
        }
    }
}

/** The menu under the fixed settings banner; the version line at its end (tap 7x: developer mode). */
@Composable
private fun SettingsMenu(vm: AppViewModel, st: AppState) {
    val S = LocalStrings.current
    val nav = LocalNav.current
    var versionTaps by remember { mutableStateOf(0) }
    var showLog by remember { mutableStateOf(false) }
    val e2eOff = com.momentum.app.data.E2EStore.status == com.momentum.app.data.E2EStore.Status.Off
    Column(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .statusBarsPadding()
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp)
                .fillMaxWidth()
                .height(120.dp)
                .clip(CardShape),
        ) {
            SceneBackground(Scene.Settings, Modifier.matchParentSize())
            Box(Modifier.padding(6.dp)) { IconBtn("ui_back", onClick = nav.pop) }
            Column(Modifier.align(Alignment.BottomStart).padding(20.dp)) {
                Label(S.settingsTag)
                FitText(S.settingsTitle, T.title, 28.sp, Modifier.fillMaxWidth())
            }
        }
        LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp), modifier = Modifier.navigationBarsPadding()) {
            item {
                PxCard(padding = 0.dp) {
                    MENU.filter { (key, _) -> key != "security" || st.user.hasPassword || !e2eOff }.forEachIndexed { i, (key, icon) ->
                        if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(Px.Line))
                        val (title, hint) = menuText(key)
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    when (key) {
                                        "report" -> nav.push(Route.Report)
                                        "news" -> showLog = true
                                        else -> nav.push(Route.Settings(key))
                                    }
                                }
                                .semantics { contentDescription = key }
                                .padding(horizontal = 18.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Px.Faint), contentAlignment = Alignment.Center) {
                                PixelIcon(icon, 20.dp)
                            }
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(title, style = T.bodyStrong, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                Text(hint, style = T.small.copy(fontSize = 12.sp), maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            }
                            Text("›", style = T.body.copy(color = Px.Muted))
                        }
                    }
                }
            }
            item {
                // tap 7x to toggle developer mode
                Text("Momentum · v${com.momentum.app.BuildConfig.VERSION_NAME}", style = T.small.copy(color = Color(0x40F4F4F4)),
                    modifier = Modifier.fillMaxWidth().clickable(remember { MutableInteractionSource() }, indication = null) {
                        if (++versionTaps >= 7) { versionTaps = 0; vm.toggleDevMode() }
                    }.padding(horizontal = 8.dp, vertical = 14.dp))
            }
        }
    }
    if (showLog) WhatsNewSheet(vm.changelog(), vm.lang) { showLog = false }
}

/** A page's content; pages with fields get their always-visible save button ([save]) over its end. */
@Composable
internal fun PageBody(save: (@Composable () -> Unit)? = null, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = if (save != null) 120.dp else 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.navigationBarsPadding(),
            content = content,
        )
        if (save != null) Box(Modifier.align(Alignment.BottomCenter)) { save() }
    }
}

/** Always visible; enabled when something changed. */
@Composable
internal fun SaveBar(vm: AppViewModel, dirty: Boolean, saving: Boolean, onSave: () -> Unit) {
    val S = LocalStrings.current
    Box(
        Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Px.Bg, Px.Bg)))
            .navigationBarsPadding()
            .padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 12.dp),
    ) {
        PxButton(if (saving) "…" else S.save, Modifier.fillMaxWidth().alpha(if (dirty) 1f else .4f), style = BtnStyle.Primary) {
            if (!dirty) vm.showToast(S.nothingToSave) else if (!saving) onSave()
        }
    }
}

@Composable
private fun ProfileSettings(vm: AppViewModel, st: AppState) {
    val S = LocalStrings.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val navTo = LocalNav.current
    var bio by remember(st.user.bio) { mutableStateOf(st.user.bio) }
    var bioEdited by remember { mutableStateOf(false) }
    var username by remember(st.user.username) { mutableStateOf(st.user.username) }
    var private by remember(st.user.private) { mutableStateOf(st.user.private) }
    var banners by remember(st.user.banners) { mutableStateOf(st.user.banners) }
    var pickBanner by remember { mutableStateOf<String?>(null) }        // vehicle type being picked
    var avatar by remember { mutableStateOf<ByteArray?>(null) }     // cropped, not yet uploaded
    var cropSource by remember { mutableStateOf<Bitmap?>(null) }
    var saving by remember { mutableStateOf(false) }
    val body = buildJsonObject {
        if (bio.trim() != st.user.bio) put("bio", bio.trim())
        if (username.trim().lowercase() != st.user.username) put("username", username.trim().lowercase())
        if (private != st.user.private) put("private", private)
        if (banners.land != st.user.banners.land) put("banner", banners.land)
        if (banners.air != st.user.banners.air) put("banner_air", banners.air)
        if (banners.space != st.user.banners.space) put("banner_space", banners.space)
    }
    val dirty = body.isNotEmpty() || avatar != null
    fun save() = scope.launch {
        saving = true
        var ok = true
        avatar?.let { if (vm.uploadAvatar(it)) avatar = null else ok = false }
        if (ok && body.isNotEmpty()) ok = vm.save("PUT", "/settings", body, quiet = true)
        saving = false
        if (ok) vm.showToast(S.saved)
    }
    val picker = rememberMediaPicker { uris ->
        uris.firstOrNull()?.let { uri -> scope.launch { cropSource = withContext(Dispatchers.IO) { decodeForCrop(context, uri) } } }
    }
    val pendingAvatar = remember(avatar) {
        avatar?.let { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
    }
    PageBody({ SaveBar(vm, dirty, saving) { save() } }) {
        item {
            PxCard {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.clickable { picker.open() }, horizontalAlignment = Alignment.CenterHorizontally) {
                            if (pendingAvatar != null) {
                                androidx.compose.foundation.Image(pendingAvatar, null, modifier = Modifier.size(64.dp).clip(RoundedCornerShape(18.dp)))
                            } else Avatar(st.user, 64.dp)
                            Text(S.change, style = T.small.copy(fontSize = 12.sp))
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(S.username, style = T.small)
                            PxTextField(username, { username = it.lowercase().filter { c -> c.isLetterOrDigit() || c == '_' || c == '.' } },
                                placeholder = "name")
                        }
                    }
                    Text(S.usernameHint, style = T.small.copy(fontSize = 12.sp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(S.bio, style = T.small, modifier = Modifier.weight(1f))
                        Text("${bio.length}/150", style = T.small.copy(fontSize = 12.sp, color = if (bio.length > 150) Px.Orange else Px.Muted))
                    }
                    BioField(bio, { if (it.length <= 150) { bio = it; bioEdited = true } }, S.bioHint)
                    // suggestions only while typing (a saved bio may well end with "@name")
                    if (bioEdited) MentionSuggestions(vm, bio) { if (it.length <= 150) bio = it }
                    st.user.email?.let { Text("${S.email}: $it", style = T.small) }
                    st.user.googleEmail?.let { Text("${S.googleAccount}: $it", style = T.small) }
                    if (st.user.avatar != null && avatar == null) {
                        PxButton(S.removePhoto, Modifier.fillMaxWidth(), style = BtnStyle.Ghost) {
                            scope.launch { vm.save("DELETE", "/settings/avatar", quiet = true) }
                        }
                    }
                }
            }
        }
        item {
            // one background per vehicle type; the current vehicle's is shown on the profile
            Section(S.banner) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("land", "air", "space").forEach { kind ->
                        val key = banners.of(kind)
                        Column(Modifier.weight(1f)) {
                            Box(Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(12.dp)).clickable { pickBanner = kind }) {
                                BannerBackground(key ?: defaultBanner(sceneFor(kind)), Modifier.matchParentSize(), still = true)
                            }
                            Text(S.bannerKind(kind), style = T.small.copy(fontSize = 12.sp, color = Px.Text), modifier = Modifier.padding(top = 4.dp))
                            HyperText(key?.let(S::bannerName) ?: S.bannerAuto, style = T.small.copy(fontSize = 11.sp), maxLines = 1)
                        }
                    }
                }
            }
        }
        item {
            Section(S.privateAccount) {
                Toggle(S.privateAccount, private) { private = it }
                Text(S.privateAccountHint, style = T.small.copy(fontSize = 12.sp))
                Text(S.blockedAccounts + "  ›", style = T.body.copy(color = Px.Cyan),
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { navTo.push(Route.BlockedList) }.padding(vertical = 6.dp))
            }
        }
    }
    cropSource?.let { bmp ->
        CropDialog(bmp, onCancel = { cropSource = null }) { jpeg ->
            cropSource = null
            avatar = jpeg
        }
    }
    pickBanner?.let { kind ->
        BannerPicker(banners.of(kind), allowAuto = true, autoKey = defaultBanner(sceneFor(kind)), kind = kind,
            hyperspace = if (kind == "space") st.user.hyperspace else null, ultra = kind == "space" && st.user.ultra, onDismiss = { pickBanner = null }) { key ->
            banners = when (kind) { "air" -> banners.copy(air = key); "space" -> banners.copy(space = key); else -> banners.copy(land = key) }
            pickBanner = null
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GameSettings(vm: AppViewModel, st: AppState) {
    val S = LocalStrings.current
    val scope = rememberCoroutineScope()
    val s = st.settings
    var timezone by remember(s.timezone) { mutableStateOf(s.timezone) }
    var haptics by remember(s.haptics) { mutableStateOf(s.haptics) }
    var dailyLimit by remember(s.dailyLimit) { mutableStateOf(s.dailyLimit) }
    var pickTz by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val body = buildJsonObject {
        if (timezone != s.timezone) put("timezone", timezone)
        if (haptics != s.haptics) put("haptics", haptics)
        if (dailyLimit != s.dailyLimit) put("daily_limit", dailyLimit)
    }
    fun save() = scope.launch {
        saving = true
        val ok = vm.save("PUT", "/settings", body, quiet = true)
        saving = false
        if (ok) vm.showToast(S.saved)
    }
    PageBody({ SaveBar(vm, body.isNotEmpty(), saving) { save() } }) {
        item {
            Section(S.competition) {
                Toggle(S.dailyLimitSetting, dailyLimit) { dailyLimit = it }
                Text(S.dailyLimitHint, style = T.small.copy(fontSize = 12.sp))
            }
        }
        item {
            Section(S.settingsGame) {
                Text(S.timezone, style = T.small)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0x0DFFFFFF))
                        .clickable { pickTz = true }
                        .padding(horizontal = 14.dp, vertical = 13.dp),
                ) {
                    Text(timezone, style = T.body, modifier = Modifier.weight(1f))
                    Text("▾", style = T.body.copy(color = Px.Muted))
                }
                Toggle(S.haptics, haptics) { haptics = it }
            }
        }
    }
    if (pickTz) {
        ModalBottomSheet(onDismissRequest = { pickTz = false }, containerColor = Px.Sheet) {
            TimezonePicker(vm.meta?.timezones.orEmpty(), timezone) {
                pickTz = false
                timezone = it
            }
        }
    }
}

@Composable
private fun AppSettings(vm: AppViewModel) {
    val S = LocalStrings.current
    val scope = rememberCoroutineScope()
    var lang by remember(vm.lang) { mutableStateOf(vm.lang) }
    var systemFont by remember(vm.systemFont) { mutableStateOf(vm.systemFont) }
    var notifications by remember(vm.notifications) { mutableStateOf(vm.notifications) }
    var server by remember(vm.serverUrl) { mutableStateOf(vm.serverUrl) }
    val serverChange = vm.devMode && server.trim().trimEnd('/') != vm.serverUrl
    val dirty = lang != vm.lang || systemFont != vm.systemFont || notifications != vm.notifications || serverChange
    fun save() = scope.launch {
        if (lang != vm.lang) vm.setLanguage(lang)
        if (systemFont != vm.systemFont) vm.changeSystemFont(systemFont)
        if (notifications != vm.notifications) vm.changeNotifications(notifications)
        vm.showToast(stringsFor(lang).saved)
        if (serverChange) { vm.setServer(server); vm.boot() }
    }
    PageBody({ SaveBar(vm, dirty, false) { save() } }) {
        item {
            Section(S.language) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(S.language, style = T.body, modifier = Modifier.weight(1f))
                    LanguageChoice(lang) { lang = it }
                }
            }
        }
        item {
            Section(S.notificationsSetting) {
                Toggle(S.notificationsSetting, notifications) { notifications = it }
                // whether Android lets us show them (a denied permission / switched-off channel is the usual reason)
                val ctx = LocalContext.current
                val allowed = androidx.core.app.NotificationManagerCompat.from(ctx).areNotificationsEnabled()
                if (notifications) {
                    Text(if (allowed) S.notificationsAllowed else S.notificationsBlocked,
                        style = T.small.copy(color = if (allowed) Px.Lime else Px.Orange))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!allowed) PxButton(S.openSystemSettings, Modifier.weight(1f), style = BtnStyle.Ghost) {
                            ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.packageName))
                        }
                        PxButton(S.testNotification, Modifier.weight(1f), style = BtnStyle.Ghost) {
                            com.momentum.app.data.Notifier.test(ctx, S.testNotification, S.testNotificationText)
                        }
                    }
                }
            }
        }
        item {
            Section(S.systemFontSetting) {
                Toggle(S.systemFontSetting, systemFont) { systemFont = it }
                Text(S.systemFontHint, style = T.small.copy(fontSize = 12.sp))
            }
        }
        if (vm.devMode) item {
            Section(S.devSection) {
                Text(S.server, style = T.small)
                PxTextField(server, { server = it }, keyboardType = KeyboardType.Uri)
            }
        }
    }
}

@Composable
private fun SecuritySettings(vm: AppViewModel, st: AppState) {
    val S = LocalStrings.current
    val scope = rememberCoroutineScope()
    val navTo = LocalNav.current
    var oldPw by remember { mutableStateOf("") }
    var newPw by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    val dirty = oldPw.isNotEmpty() && newPw.isNotEmpty()
    fun save() = scope.launch {
        saving = true
        val ok = vm.call("POST", "/settings/password", buildJsonObject { put("old", oldPw); put("new", newPw) })
        saving = false
        if (ok) { oldPw = ""; newPw = ""; vm.showToast(S.saved) }
    }
    val e2e = com.momentum.app.data.E2EStore.status
    PageBody(if (st.user.hasPassword) ({ SaveBar(vm, dirty, saving) { save() } }) else null) {
        if (st.user.hasPassword) item {
            Section(S.changePassword) {
                PxTextField(oldPw, { oldPw = it }, placeholder = S.oldPassword, password = true)
                PxTextField(newPw, { newPw = it }, placeholder = S.newPassword, password = true)
            }
        }
        // encrypted chats: the recovery key (or unlock this phone)
        if (e2e != com.momentum.app.data.E2EStore.Status.Off) item {
            val locked = e2e == com.momentum.app.data.E2EStore.Status.Locked
            Section(if (locked) S.unlockTitle else S.recoveryTitle) {
                PxButton((if (locked) S.unlockTitle else S.recoveryTitle) + "  ›", Modifier.fillMaxWidth(), style = BtnStyle.Ghost) {
                    navTo.push(if (locked) Route.E2EUnlock else Route.E2ERecovery)
                }
            }
        }
    }
}

@Composable
private fun LegalSettings(vm: AppViewModel) {
    val S = LocalStrings.current
    // privacy policy + terms (server/legal.html), also the Play Store listing's links
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    val base = vm.serverUrl.trimEnd('/')
    PageBody {
        item {
            Section(S.legalSection) {
                PxButton(S.privacyPolicy, Modifier.fillMaxWidth(), style = BtnStyle.Ghost) { runCatching { uri.openUri("$base/privacy") } }
                PxButton(S.termsOfUse, Modifier.fillMaxWidth(), style = BtnStyle.Ghost) { runCatching { uri.openUri("$base/terms") } }
            }
        }
    }
}

@Composable
private fun AccountSettings(vm: AppViewModel, st: AppState) {
    val S = LocalStrings.current
    val scope = rememberCoroutineScope()
    var lowering by remember { mutableStateOf(false) }
    PageBody {
        item {
            Section(S.settingsAccount) {
                PxButton(S.logout, Modifier.fillMaxWidth(), style = BtnStyle.Ghost) { vm.logout() }
            }
        }
        item {
            Section(S.dangerZone) {
                PxButton(S.lowerMomentum, Modifier.fillMaxWidth(), style = BtnStyle.Ghost) { lowering = true }
                ConfirmButton(S.deleteAccount, S.tapAgain) {
                    scope.launch { if (vm.call("DELETE", "/account")) vm.logout(callApi = false) }
                }
            }
        }
    }
    if (lowering) LowerMomentumSheet(vm, st.momentum) { lowering = false }
}

/** Multi-line bio input in the same look as PxTextField. */
@Composable
private fun BioField(value: String, onChange: (String) -> Unit, placeholder: String) {
    var tfv by rememberCursorValue(value)
    Box(Modifier.fillMaxWidth()) {
        androidx.compose.foundation.text.BasicTextField(
            tfv, { tfv = it; if (it.text != value) onChange(it.text) }, textStyle = T.body.userText(), cursorBrush = androidx.compose.ui.graphics.SolidColor(Px.Yellow),
            minLines = 3, maxLines = 6,
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { inner ->
                Box(
                    Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0x0DFFFFFF))
                        .border(1.dp, Px.Line, RoundedCornerShape(14.dp))
                        .padding(start = 14.dp, end = 48.dp, top = 12.dp, bottom = 12.dp),
                ) {
                    if (value.isEmpty()) Text(placeholder, style = T.body.copy(color = Px.Muted))
                    inner()
                }
            },
        )
        Box(Modifier.align(Alignment.BottomEnd).padding(2.dp)) {
            EmojiButton(tfv) { tfv = it; onChange(it.text) }
        }
    }
}

/** DE | EN choice that only changes local state (applied on save). */
@Composable
private fun LanguageChoice(current: Lang, onPick: (Lang) -> Unit) {
    Row(Modifier.clip(RoundedCornerShape(12.dp)).background(Px.Faint).padding(3.dp)) {
        Lang.entries.forEach { l ->
            val on = current == l
            Box(
                Modifier
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (on) Px.Text else Color.Transparent)
                    .clickable { onPick(l) }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            ) { Text(l.code.uppercase(), style = T.button.copy(color = if (on) Color.Black else Px.Muted)) }
        }
    }
}

@Composable
fun Section(title: String, content: @Composable () -> Unit) {
    PxCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Label(title)
            content()
        }
    }
}

/** What's new (web: WhatsNewOverlay in screens/Overlays.tsx): the releases' points, newest first. */
@Composable
fun WhatsNewSheet(releases: List<com.momentum.app.data.Release>, lang: Lang, onDismiss: () -> Unit) {
    val S = LocalStrings.current
    PxSheet(onDismiss) {
        Label(S.whatsNewTitle, color = Px.Yellow)
        releases.forEach { r ->
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(r.version, style = T.small)
                r.lines(lang.code).forEach { line ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.padding(top = 7.dp).size(6.dp).background(Px.Lime))   // a pixel bullet
                        Text(line, style = T.body, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
        PxButton(S.whatsNewOk, Modifier.fillMaxWidth()) { onDismiss() }
    }
}

/** A day's first start: the daily limit changed since the day before (web: LimitChangeOverlay in screens/Overlays.tsx). */
@Composable
fun LimitChangeSheet(news: com.momentum.app.data.LimitNews, onDismiss: () -> Unit) {
    val S = LocalStrings.current
    PxSheet(onDismiss) {
        Label(if (news.up) S.limitUpTitle else S.limitDownTitle, color = Px.Yellow)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(com.momentum.app.fmt(news.prev), style = T.block.copy(fontSize = 20.sp, color = Px.Muted), modifier = Modifier.alignByBaseline())
            Text("→", style = T.block.copy(fontSize = 20.sp), modifier = Modifier.alignByBaseline())
            Text(com.momentum.app.fmt(news.cap), style = T.block.copy(fontSize = 32.sp, color = if (news.up) Px.Lime else Px.Orange),
                modifier = Modifier.alignByBaseline())
        }
        Text(S.limitChangeText(com.momentum.app.fmt(news.prev), com.momentum.app.fmt(news.cap)), style = T.body)
        Text(S.limitChangeWhy(com.momentum.app.fmt(news.average)), style = T.small)
        PxButton(S.whatsNewOk, Modifier.fillMaxWidth()) { onDismiss() }
    }
}

@Composable
fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.clickable { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = T.body, modifier = Modifier.weight(1f))
        Switch(
            checked, onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Px.Lime, checkedTrackColor = Px.Lime.copy(alpha = .3f), checkedBorderColor = Color.Transparent,
                uncheckedThumbColor = Px.Muted, uncheckedTrackColor = Px.Faint, uncheckedBorderColor = Color.Transparent,
            ),
        )
    }
}

/** Destructive action: first tap arms it, second tap within 3 s runs it. */
@Composable
fun ConfirmButton(label: String, confirmLabel: String, onConfirm: () -> Unit) {
    var armed by remember { mutableStateOf(false) }
    LaunchedEffect(armed) {
        if (armed) {
            delay(3000)
            armed = false
        }
    }
    PxButton(if (armed) confirmLabel else label, Modifier.fillMaxWidth(), style = BtnStyle.Danger) {
        if (armed) { armed = false; onConfirm() } else armed = true
    }
}

@Composable
private fun TimezonePicker(zones: List<String>, current: String, onPick: (String) -> Unit) {
    val S = LocalStrings.current
    var query by remember { mutableStateOf("") }
    val filtered = remember(query, zones) { zones.filter { it.contains(query.trim(), ignoreCase = true) } }
    Column(Modifier.padding(horizontal = 20.dp).navigationBarsPadding()) {
        PxTextField(query, { query = it }, placeholder = S.searchTimezone)
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.heightIn(max = 420.dp)) {
            items(filtered) { tz ->
                Text(
                    tz,
                    style = T.body.copy(color = if (tz == current) Px.Yellow else Px.Text),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick(tz) }
                        .padding(vertical = 12.dp),
                )
            }
        }
    }
}


/** Set the own momentum down to a chosen value (0 = start over); replaces "reset to 0". */
@Composable
private fun LowerMomentumSheet(vm: AppViewModel, current: Double, onDone: () -> Unit) {
    val S = LocalStrings.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("0") }
    var confirm by remember { mutableStateOf(false) }
    val value = text.toLongOrNull()
    val valid = value != null && value < current
    PxSheet(onDone) {
        Text(S.lowerMomentum, style = T.title.copy(fontSize = 20.sp))
        Text(S.lowerMomentumHint(com.momentum.app.fmt(current)), style = T.small)
        PxTextField(text, { v -> text = v.filter(Char::isDigit).take(12); confirm = false }, keyboardType = KeyboardType.Number)
        if (!valid && text.isNotEmpty()) Text(S.lowerMomentumInvalid, style = T.small.copy(color = Px.Orange))
        PxButton(
            if (confirm) S.tapAgain else S.lowerMomentumTo(com.momentum.app.fmt((value ?: 0L).toDouble())),
            Modifier.fillMaxWidth().alpha(if (valid) 1f else .4f), style = BtnStyle.Danger,
        ) {
            if (!valid) return@PxButton
            if (!confirm) { confirm = true; return@PxButton }
            scope.launch {
                if (vm.save("POST", "/momentum/lower", kotlinx.serialization.json.buildJsonObject { put("value", value!!) })) onDone()
            }
        }
    }
}
