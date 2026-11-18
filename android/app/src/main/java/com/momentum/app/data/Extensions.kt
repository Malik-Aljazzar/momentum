package com.momentum.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File

// Extensions (server/extensions.py): optional tabs a player downloads in the settings. The page runs in a WebView
// that may not navigate (no token, no app files; its CSP allows no network) and talks to the app through the bridge
// (server/shipped_extensions/sdk.js) - this file answers it with the player's data on the server.
// = web/src/extensions.ts

@Serializable
data class Texts(val de: String = "", val en: String = "") {
    /** in the app's language (the other one if it's missing) */
    fun of(lang: String) = (if (lang == "de") de else en).ifEmpty { en.ifEmpty { de } }
}

/** API 1's single tab (kept by the server for apps from before tab layouts). */
@Serializable
data class ExtensionTabInfo(val title: Texts = Texts(), val icon: String = "ui_dots")

/** One of an extension's tabs (its manifest): placed by the player in settings -> tabs (data/TabLayout.kt). */
@Serializable
data class ExtensionTabDef(val key: String, val name: Texts = Texts(), val icon: String = "ui_dots")

/** A downloaded extension (the state's `extensions`): while it's switched on its tabs are in the choice of tabs. */
@Serializable
data class InstalledExtension(
    val id: String,
    val version: String,
    val name: Texts = Texts(),
    val icon: String = "sparkles",
    val tab: ExtensionTabInfo? = null,
    val enabled: Boolean = false,
    val tabs: List<ExtensionTabDef> = emptyList(),
    /** what else it may do through the bridge (manifest "permissions"): "rules" = see / tap the player's rules */
    val permissions: List<String> = emptyList(),
)

/** An extension in the catalog (settings -> extensions). */
@Serializable
data class CatalogExtension(
    val id: String,
    val version: String,
    val name: Texts = Texts(),
    val description: Texts = Texts(),
    val author: String = "",
    val icon: String = "sparkles",
    val tab: ExtensionTabInfo? = null,
    val tabs: List<ExtensionTabDef> = emptyList(),
    val permissions: List<String> = emptyList(),
    val collections: List<String> = emptyList(),
    val official: Boolean = false,
    val size: Int = 0,
    val installed: Boolean = false,
    val enabled: Boolean = false,
)

@Serializable
data class CatalogResponse(val extensions: List<CatalogExtension> = emptyList(), @SerialName("max_tabs") val maxTabs: Int = 3)

@Serializable
data class InstallResponse(val extension: CatalogExtension, val page: String)

@Serializable
data class ExtensionResponse(val extension: CatalogExtension)

@Serializable
data class ExtensionPageResponse(val id: String = "", val version: String, val page: String)

/** A rule as the bridge shows it to a page (rules.list). */
data class BridgeRule(val id: Int, val name: String, val icon: String, val kind: String, val value: Double, val tappable: Boolean)

/** What a tap asked for by an extension brought (rules.tap): its event (to take it back), the change, capped by the limit. */
data class BridgeTap(val event: Int, val delta: Double, val capped: Boolean)

/** The bridge between an extension's page and the app (pure: the server calls come in as [Deps]). */
object ExtensionBridge {
    /** A bridge message from the page is at most this long (a document is at most 16 KB on the server). */
    const val MAX_MESSAGE = 64 * 1024
    private val ORDERS = setOf("key", "-key", "updated", "-updated")
    private val json = Json { ignoreUnknownKeys = true }

    data class Request(val id: Long, val method: String, val params: JsonObject)

    /** What the app gives the bridge (the server calls and the rest), swapped out in tests. */
    interface Deps {
        /** The server's answer as JSON; throws [ApiException] (with its status) when it fails. */
        suspend fun call(method: String, path: String, body: JsonElement? = null): JsonElement
        fun toast(text: String)
        fun context(): JsonElement
        /** the extension's manifest permissions ("rules": rules.list / rules.tap / rules.undo) */
        val permissions: List<String> get() = emptyList()
        /** the player's rules (in the state's order) */
        fun rules(): List<BridgeRule> = emptyList()
        /** taps a rule online like the player would; throws [ApiException] */
        suspend fun tap(rule: Int): BridgeTap = throw ApiException("offline", 0, fromServer = false)
        /** takes a tap back; throws [ApiException] */
        suspend fun undo(event: Int): Unit = throw ApiException("offline", 0, fromServer = false)
    }

    /** The page's message (JSON text) as a request; null when it's something else. */
    fun parse(text: String): Request? {
        if (text.length > MAX_MESSAGE) return null
        val m = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
        if ((m["momentum"] as? JsonPrimitive)?.doubleOrNull != 1.0) return null
        val id = (m["id"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull ?: return null
        val method = (m["method"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        return Request(id, method, m["params"] as? JsonObject ?: JsonObject(emptyMap()))
    }

    /** = encodeURIComponent: every path part escaped. */
    fun uriComponent(s: String): String {
        val keep = "-_.!~*'()"
        val out = StringBuilder()
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xFF
            val ch = c.toChar()
            if (c < 128 && (ch.isLetterOrDigit() || ch in keep)) out.append(ch)
            else out.append('%').append("0123456789ABCDEF"[c shr 4]).append("0123456789ABCDEF"[c and 15])
        }
        return out.toString()
    }

    private fun str(p: JsonObject, key: String): String = when (val v = p[key]) {
        null, is JsonNull -> ""
        is JsonPrimitive -> v.content
        else -> v.toString()
    }

    private fun num(p: JsonObject, key: String): Double? = (p[key] as? JsonPrimitive)?.let { it.doubleOrNull ?: it.contentOrNull?.toDoubleOrNull() }

    private fun ok(id: Long, result: JsonElement) = buildJsonObject { put("momentum", 1); put("id", id); put("result", result) }
    private fun error(id: Long, message: String, status: Int) = buildJsonObject {
        put("momentum", 1); put("id", id)
        put("error", buildJsonObject { put("message", message); put("status", status) })
    }

    /** Answers one request of extension [id] (the server checks everything again: its own data only). */
    suspend fun answer(id: String, r: Request, deps: Deps): JsonObject {
        val p = r.params
        val base = "/extensions/${uriComponent(id)}/data/${uriComponent(str(p, "collection"))}"
        return try {
            when (r.method) {
                "context" -> ok(r.id, deps.context())
                "toast" -> { deps.toast(str(p, "text").take(140)); ok(r.id, JsonPrimitive(true)) }
                "data.get" -> try {
                    val doc = deps.call("GET", "$base/${uriComponent(str(p, "key"))}").jsonObject["doc"]?.jsonObject
                    ok(r.id, doc?.get("value") ?: JsonNull)
                } catch (e: ApiException) {
                    if (e.status == 404) ok(r.id, JsonNull) else throw e
                }
                "data.put" -> ok(r.id, deps.call("PUT", "$base/${uriComponent(str(p, "key"))}",
                    buildJsonObject { put("value", p["value"] ?: JsonNull) }).jsonObject["doc"] ?: JsonNull)
                "data.delete" -> { deps.call("DELETE", "$base/${uriComponent(str(p, "key"))}"); ok(r.id, JsonPrimitive(true)) }
                "data.list" -> {
                    val offset = maxOf(0, kotlin.math.floor(num(p, "offset") ?: 0.0).toInt())
                    val limit = (kotlin.math.floor(num(p, "limit") ?: 30.0).toInt().takeIf { it >= 1 } ?: 30).coerceAtMost(100)
                    val order = str(p, "order").takeIf { it in ORDERS } ?: "key"
                    ok(r.id, deps.call("GET", "$base?offset=$offset&limit=$limit&order=${uriComponent(order)}"))
                }
                "rules.list", "rules.tap", "rules.undo" ->
                    if ("rules" !in deps.permissions) error(r.id, "permission rules missing", 403)
                    else when (r.method) {
                        // only what the player can tap themselves, and not rules only known on this phone yet
                        "rules.list" -> ok(r.id, JsonArray(deps.rules().filter { it.tappable && it.id > 0 }.map {
                            buildJsonObject { put("id", it.id); put("name", it.name); put("icon", it.icon); put("kind", it.kind); put("value", it.value) }
                        }))
                        "rules.tap" -> {
                            val rule = num(p, "rule")?.toInt()
                            if (rule == null) error(r.id, "rule expected", 400) else {
                                val t = deps.tap(rule)
                                ok(r.id, buildJsonObject { put("event", t.event); put("delta", t.delta); put("capped", t.capped) })
                            }
                        }
                        else -> {
                            val event = num(p, "event")?.toInt()
                            if (event == null) error(r.id, "event expected", 400) else { deps.undo(event); ok(r.id, JsonPrimitive(true)) }
                        }
                    }
                else -> error(r.id, "unknown method ${r.method}", 400)
            }
        } catch (e: ApiException) {
            error(r.id, e.message ?: "error", e.status)
        } catch (e: Exception) {
            error(r.id, e.message ?: e.toString(), 0)
        }
    }

    /** What `momentum.ready` resolves to: the API, language, player, extension and which of its tabs the page is
     *  ([tab] = the manifest's key; = appDeps' context in web/src/extensions.ts). */
    fun context(lang: String, username: String, displayName: String, ext: InstalledExtension, tab: String?) = buildJsonObject {
        put("api", 1)
        put("lang", lang)
        put("user", buildJsonObject { put("username", username); put("display_name", displayName) })
        put("extension", buildJsonObject { put("id", ext.id); put("version", ext.version) })
        put("tab", tab)
    }

    /** The page with the server's address filled in (its <base> and CSP, server/extensions.py compose). */
    fun withOrigin(page: String, origin: String) = page.replace("__ORIGIN__", origin.trimEnd('/'))

    /** An event for the page ("show" / "hide"). */
    fun event(name: String) = buildJsonObject { put("momentum", 1); put("event", name) }.toString()
}

/** Downloaded pages, kept on the phone (they open offline, too): files/extensions/<id>.json = {version, page}. */
class ExtensionPages(context: Context) {
    private val dir = File(context.filesDir, "extensions")
    private val json = Json { ignoreUnknownKeys = true }
    private val safeId = Regex("^[a-z][a-z0-9_]{1,39}$")

    @Serializable
    private data class Stored(val version: String, val page: String)

    private fun file(id: String) = if (safeId.matches(id)) File(dir, "$id.json") else null

    suspend fun store(id: String, version: String, page: String) = withContext(Dispatchers.IO) {
        val f = file(id) ?: return@withContext
        runCatching { dir.mkdirs(); f.writeText(json.encodeToString(Stored.serializer(), Stored(version, page))) }
    }

    suspend fun forget(id: String) = withContext(Dispatchers.IO) { file(id)?.delete() }

    private suspend fun stored(id: String): Stored? = withContext(Dispatchers.IO) {
        file(id)?.takeIf { it.exists() }?.let { f -> runCatching { json.decodeFromString(Stored.serializer(), f.readText()) }.getOrNull() }
    }

    /** The extension's page: the stored copy of this version, else downloaded again (null: offline and none stored). */
    suspend fun pageOf(api: Api, ext: InstalledExtension): String? {
        val old = stored(ext.id)
        if (old?.version == ext.version) return old.page
        return try {
            val r = api.call<ExtensionPageResponse>("GET", "/extensions/${ExtensionBridge.uriComponent(ext.id)}/page")
            store(ext.id, r.version, r.page)
            r.page
        } catch (e: ApiException) {
            old?.page                       // offline: the older copy is better than nothing
        }
    }
}
