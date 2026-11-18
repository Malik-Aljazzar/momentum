package com.momentum.app.ui

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.momentum.app.AppViewModel
import com.momentum.app.data.CatalogExtension
import com.momentum.app.data.CatalogResponse
import com.momentum.app.data.ExtensionBridge
import com.momentum.app.data.ExtensionPages
import com.momentum.app.data.ExtensionResponse
import com.momentum.app.data.InstallResponse
import com.momentum.app.data.InstalledExtension
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// Extensions (server/extensions.py; = web/src/screens/Extensions.tsx): the settings page to download, switch on / off
// and remove them, and an extension's tabs - its page in a WebView per tab, answered by the bridge (data/Extensions.kt).
// Where the tabs go (the bar, a group, hidden) the player decides in settings -> tabs (ui/TabsEditor.kt).

/** Settings -> Extensions: the catalog, each with download / on-off / remove. */
@Composable
fun ExtensionsPage(vm: AppViewModel) {
    val S = LocalStrings.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val pages = ExtensionPages(LocalContext.current)
    // my tabs as shown: an extension whose tabs are placed nowhere says where they wait
    val st = vm.state
    val layout = st?.let { com.momentum.app.data.TabLayout.effectiveLayout(it.settings.tabs, com.momentum.app.data.TabLayout.availableTabs(it.extensions)) }
    var list by remember { mutableStateOf<List<CatalogExtension>?>(null) }
    var more by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    suspend fun load(offset: Int = 0) {
        val r = vm.req<CatalogResponse>("GET", "/extensions?offset=$offset&limit=50") ?: return
        list = if (offset > 0) list.orEmpty() + r.extensions else r.extensions
        more = r.extensions.size >= 50
    }
    LaunchedEffect(Unit) { load() }
    LoadMoreEffect(listState, list?.size ?: 0, more) { load(list?.size ?: 0) }
    fun update(e: CatalogExtension) { list = list?.map { if (it.id == e.id) e else it } }
    fun act(id: String, f: suspend () -> Unit) {
        if (busy != null) return
        busy = id
        scope.launch { try { f() } finally { busy = null } }
    }
    val lang = vm.lang.code
    LazyColumn(state = listState, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.navigationBarsPadding()) {
        item { Text(S.extensionsIntro, style = T.small) }
        val l = list
        if (l == null) { if (!vm.online) item { OfflineState() } }
        else if (l.isEmpty()) item { EmptyHint(S.extensionsNone, icon = "sparkles") }
        else items(l, key = { it.id }) { e ->
            PxCard(Modifier.semantics { contentDescription = "extension ${e.id}" }) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.Top) {
                        Box(Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(Px.Faint), contentAlignment = Alignment.Center) {
                            PixelIcon(e.icon, 32.dp)
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(e.name.of(lang), style = T.bodyStrong)
                            Text((if (e.official) S.extensionOfficial else S.extensionBy(e.author)) +
                                (if (e.tabs.isNotEmpty()) " · " + S.extensionTabsCount(e.tabs.size) else "") + " · ${maxOf(1, Math.round(e.size / 1024f))} KB",
                                style = T.small.copy(fontSize = 12.sp, color = if (e.official) Px.Yellow else Px.Muted))
                        }
                    }
                    Text(e.description.of(lang), style = T.body.copy(color = Px.Muted))
                    val waiting = e.installed && e.enabled && layout != null && e.tabs.any {
                        com.momentum.app.data.TabLayout.locate(layout, com.momentum.app.data.TabLayout.extKey(e.id, it.key)) == null
                    }
                    if (waiting) {
                        Text(S.extensionTabsWaiting, style = T.small.copy(color = Px.Yellow))
                        PxButton(S.extensionArrangeTabs, Modifier.fillMaxWidth(), style = BtnStyle.Ghost) { nav.push(Route.Settings("tabs")) }
                    }
                    if (!e.installed) {
                        PxButton(if (busy == e.id) "…" else S.extensionDownload, Modifier.fillMaxWidth()) {
                            act(e.id) {
                                val r = vm.req<InstallResponse>("POST", "/extensions/${e.id}/install") ?: return@act
                                pages.store(e.id, r.extension.version, r.page)          // downloaded: it opens offline too
                                update(r.extension)
                                vm.refresh()                                              // its tabs come with the state
                            }
                        }
                    } else {
                        Toggle(S.extensionActive, e.enabled) { on ->
                            act(e.id) {
                                vm.req<ExtensionResponse>("PUT", "/extensions/${e.id}", buildJsonObject { put("enabled", on) })
                                    ?.let { update(it.extension); vm.refresh() }
                            }
                        }
                        ConfirmButton(S.extensionRemove, S.extensionRemoveConfirm) {
                            act(e.id) {
                                if (!vm.call("DELETE", "/extensions/${e.id}")) return@act
                                pages.forget(e.id)
                                update(e.copy(installed = false, enabled = false))
                                vm.refresh()
                            }
                        }
                    }
                }
            }
        }
        item {
            Text(S.extensionsCommunity, style = T.small.copy(textAlign = TextAlign.Center),
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
        }
    }
}

/** What the page may ask the app for: its data on the server (the token stays here), a toast, who's playing and which
 *  of the extension's tabs it is ([tab]). */
private class AppDeps(private val vm: AppViewModel, private val tab: String?, private val ext: () -> InstalledExtension) : ExtensionBridge.Deps {
    override suspend fun call(method: String, path: String, body: JsonElement?): JsonElement =
        vm.api.json.parseToJsonElement(vm.api.raw(method, path, body))

    override fun toast(text: String) = vm.showToast(text)

    override fun context(): JsonElement {
        val u = vm.state?.user
        return ExtensionBridge.context(vm.lang.code, u?.username.orEmpty(), u?.displayName.orEmpty(), ext(), tab)
    }
}

/** The page's way in: `window.MomentumAndroid.post(json)` (sdk.js); answers go back through `window.__momentumReceive`. */
private class JsBridge(private val web: WebView, private val scope: CoroutineScope, private val id: String,
                       private val deps: ExtensionBridge.Deps) {
    @JavascriptInterface
    fun post(text: String) {
        val r = ExtensionBridge.parse(text) ?: return
        scope.launch {                                   // the main thread: the answer goes back into the page there
            val answer = ExtensionBridge.answer(id, r, deps).toString()
            web.evaluateJavascript("window.__momentumReceive && window.__momentumReceive(${org.json.JSONObject.quote(answer)})", null)
        }
    }
}

/**
 * One of an extension's tabs ([tab] = its manifest key): the extension's page in a WebView that may only run its
 * scripts - no navigation, no files, no window.open; its CSP allows no network. The page learns which tab it is from
 * the context. [visible]: on screen now - the page hears "show" / "hide".
 */
@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable
fun ExtensionTab(vm: AppViewModel, id: String, tab: String?, visible: Boolean) {
    val S = LocalStrings.current
    val ext = vm.state?.extensions?.firstOrNull { it.id == id } ?: return
    val current by rememberUpdatedState(ext)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var html by remember(id, ext.version) { mutableStateOf<String?>(null) }
    var failed by remember(id, ext.version) { mutableStateOf(false) }
    var web by remember { mutableStateOf<WebView?>(null) }
    LaunchedEffect(id, ext.version) {
        val page = ExtensionPages(context).pageOf(vm.api, ext)
        if (page != null) html = ExtensionBridge.withOrigin(page, vm.serverUrl) else failed = true
    }
    LaunchedEffect(visible, web) {
        web?.evaluateJavascript("window.__momentumReceive && window.__momentumReceive(" +
            org.json.JSONObject.quote(ExtensionBridge.event(if (visible) "show" else "hide")) + ")", null)
    }
    DisposableEffect(Unit) { onDispose { web = null } }
    Box(Modifier.fillMaxSize().background(Px.Bg).statusBarsPadding().navigationBarsPadding().padding(bottom = TAB_BAR_HEIGHT)) {
        val page = html
        if (page != null) {
            androidx.compose.runtime.key(page) {
                AndroidView(
                    factory = { c ->
                        WebView(c).apply {
                            setBackgroundColor(android.graphics.Color.BLACK)
                            settings.javaScriptEnabled = true
                            settings.allowFileAccess = false
                            settings.allowContentAccess = false
                            settings.domStorageEnabled = false
                            settings.javaScriptCanOpenWindowsAutomatically = false
                            settings.setSupportMultipleWindows(false)
                            settings.setGeolocationEnabled(false)
                            // the page stays where it is: no links out, no other pages in this WebView
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
                            }
                            addJavascriptInterface(JsBridge(this, scope, id, AppDeps(vm, tab) { current }), "MomentumAndroid")
                            loadDataWithBaseURL(null, page, "text/html", "utf-8", null)
                            web = this
                        }
                    },
                    onRelease = { it.removeJavascriptInterface("MomentumAndroid"); it.destroy() },
                    modifier = Modifier.fillMaxSize().semantics { contentDescription = "extension ${ext.id}:${tab.orEmpty()}" },
                )
            }
        } else if (failed) EmptyHint(ext.name.of(vm.lang.code), S.extensionLoadFailed, ext.icon)
    }
}

