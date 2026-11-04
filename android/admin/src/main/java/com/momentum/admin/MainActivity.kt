package com.momentum.admin

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import kotlinx.coroutines.launch

/** Where the app is: one of the four tabs, or a screen opened from one. */
sealed interface Screen {
    data object Tabs : Screen
    data class ReportDetail(val id: Int) : Screen
    data class CategoryDetail(val key: String) : Screen
    data class Viewer(val item: Item) : Screen
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val prefs = getSharedPreferences("admin", Context.MODE_PRIVATE)
        val api = Api(prefs.getString("server", null) ?: BuildConfig.DEFAULT_SERVER, prefs.getString("token", null))
        setContent {
            // images come from the admin API: Coil sends the admin session along
            setSingletonImageLoaderFactory { ctx ->
                ImageLoader.Builder(ctx).components { add(OkHttpNetworkFetcherFactory(callFactory = { api.imageClient })) }.build()
            }
            MaterialTheme(colorScheme = darkColorScheme(background = Px.Bg, surface = Px.Card, primary = Px.Text)) {
                AdminApp(api) { server, token ->
                    prefs.edit().putString("server", server).putString("token", token).apply()
                }
            }
        }
    }
}

@Composable
fun AdminApp(api: Api, save: (String, String?) -> Unit) {
    var loggedIn by remember { mutableStateOf(api.token != null) }
    var tab by remember { mutableIntStateOf(0) }
    var stack by remember { mutableStateOf(listOf<Screen>(Screen.Tabs)) }
    val scope = rememberCoroutineScope()
    fun open(s: Screen) { stack = stack + s }
    fun back() { stack = stack.dropLast(1) }

    Box(Modifier.fillMaxSize().background(Px.Bg).statusBarsPadding().navigationBarsPadding().imePadding()) {
        if (!loggedIn) {
            Login(api) { save(api.baseUrl, api.token); loggedIn = true }
            return@Box
        }
        val logout = {
            scope.launch { api.logout() }
            api.token = null
            save(api.baseUrl, null)
            loggedIn = false
        }
        BackHandler(stack.size > 1) { back() }
        // the tabs stay composed underneath, so lists keep their place when coming back
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) {
                when (tab) {
                    0 -> ReportsTab(api, onOpen = { open(Screen.ReportDetail(it)) }, onAuthError = logout)
                    1 -> FlaggedTab(api, onOpen = { open(Screen.Viewer(it)) }, onAuthError = logout)
                    2 -> StorageTab(api, onOpen = { open(Screen.CategoryDetail(it)) }, onAuthError = logout)
                    else -> StatsTab(api, onLogout = logout, onAuthError = logout)
                }
            }
            TabBar(tab) { tab = it }
        }
        when (val top = stack.last()) {
            is Screen.ReportDetail -> Overlay { ReportScreen(api, top.id, onBack = ::back, onImage = { open(Screen.Viewer(it)) }) }
            is Screen.CategoryDetail -> Overlay { CategoryScreen(api, top.key, onBack = ::back, onOpen = { open(Screen.Viewer(it)) }) }
            is Screen.Viewer -> Overlay { Viewer(api, top.item, onBack = ::back) }
            Screen.Tabs -> {}
        }
    }
}

@Composable
private fun Overlay(content: @Composable () -> Unit) {
    // swallows taps so the tab underneath doesn't get them
    Box(Modifier.fillMaxSize().background(Px.Bg).clickable(enabled = true, indication = null,
        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }) {}) { content() }
}

@Composable
private fun TabBar(tab: Int, onTab: (Int) -> Unit) {
    val tabs = listOf("REPORTS", S.t("PRÜFEN", "REVIEW"), S.t("SPEICHER", "STORAGE"), S.t("STATISTIK", "STATS"))
    Row(Modifier.fillMaxWidth().background(Px.Card).border(2.dp, Px.Line)) {
        tabs.forEachIndexed { i, label ->
            val on = i == tab
            Column(
                Modifier.weight(1f).clickable { onTab(i) }.padding(top = 0.dp, bottom = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.fillMaxWidth().height(4.dp).background(if (on) Px.Yellow else Px.Card))
                Spacer(Modifier.height(12.dp))
                Text(label, style = T.button.copy(fontSize = 12.sp, color = if (on) Px.Yellow else Px.Muted))
            }
        }
    }
}

/** Screen header: back arrow (pixel chevron) and title, actions on the right. */
@Composable
fun Header(title: String, onBack: (() -> Unit)? = null, actions: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) {
            Text("<", style = T.block.copy(fontSize = 22.sp), modifier = Modifier.clickable(onClick = onBack).padding(end = 14.dp))
        }
        Text(title, style = T.block.copy(fontSize = 18.sp), modifier = Modifier.weight(1f), maxLines = 1)
        actions()
    }
}

@Composable
private fun Login(api: Api, onDone: () -> Unit) {
    var server by remember { mutableStateOf(api.baseUrl) }
    var login by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Spacer(Modifier.height(40.dp))
        Text("MOMENTUM", style = T.block.copy(fontSize = 30.sp))
        Text("ADMIN", style = T.block.copy(fontSize = 30.sp, color = Px.Red))
        Text(S.t("Nur für Admin-Konten.", "Admin accounts only."), style = T.small)
        Spacer(Modifier.height(12.dp))
        SectionTitle("Server")
        PixelField(server, { server = it.trim() }, "https://…")
        PixelField(login, { login = it.trim() }, S.t("E-Mail oder Benutzername", "E-mail or username"))
        PixelField(password, { password = it }, S.t("Passwort", "Password"), password = true)
        PixelField(code, { c -> code = c.filter { it.isDigit() }.take(6) }, S.t("Code aus der Authenticator-App", "Code from the authenticator app"),
            number = true)
        Text(S.t("Der Schlüssel dafür kommt von: python -m server.admin grant <name>", "The key for it comes from: python -m server.admin grant <name>"),
            style = T.small)
        error?.let { Text(it, style = T.body.copy(color = Px.Orange)) }
        PixelButton(if (busy) "…" else "LOGIN", Modifier.fillMaxWidth(), color = Px.Yellow, filled = true,
            enabled = !busy && login.isNotEmpty() && password.isNotEmpty() && code.length == 6) {
            // release builds talk to servers over HTTPS only (passwords and sessions never in plain text)
            if (!BuildConfig.DEBUG && !server.startsWith("https://")) {
                error = S.t("Nur https://-Server erlaubt", "Only https:// servers allowed")
                return@PixelButton
            }
            busy = true
            error = null
            scope.launch {
                try {
                    api.baseUrl = server
                    api.token = api.login(login, password, code)
                    onDone()
                } catch (e: ApiException) {
                    api.token = null
                    code = ""
                    error = e.message
                } finally {
                    busy = false
                }
            }
        }
    }
}
