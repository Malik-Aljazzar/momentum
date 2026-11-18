package com.momentum.app.data

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The extension bridge (server/shipped_extensions/sdk.js <-> the app); the same cases as web/tests/unit/extensions.test.ts. */
class ExtensionBridgeTest {
    /** A fake app: records the server calls, answers from a little table. */
    private class Fake(val answers: Map<String, Any> = emptyMap()) : ExtensionBridge.Deps {
        val calls = mutableListOf<Triple<String, String, JsonElement?>>()
        val toasts = mutableListOf<String>()
        override suspend fun call(method: String, path: String, body: JsonElement?): JsonElement {
            calls += Triple(method, path, body)
            return when (val a = answers["$method $path"]) {
                is ApiException -> throw a
                is JsonElement -> a
                else -> JsonObject(emptyMap())
            }
        }
        override fun toast(text: String) { toasts += text }
        override fun context(): JsonElement = buildJsonObject {
            put("api", 1); put("lang", "de"); put("extension", buildJsonObject { put("id", "journal"); put("version", "1.0.0") })
        }
    }

    private fun ask(method: String, params: JsonObject = JsonObject(emptyMap()), id: Long = 7) =
        ExtensionBridge.Request(id, method, params)

    private fun params(vararg pairs: Pair<String, Any>) = buildJsonObject {
        pairs.forEach { (k, v) -> when (v) { is String -> put(k, v); is Number -> put(k, v); is JsonElement -> put(k, v) } }
    }

    @Test
    fun takesOnlyItsOwnMessages() {
        assertEquals(ask("context"), ExtensionBridge.parse("""{"momentum":1,"id":7,"method":"context","params":{}}"""))
        assertEquals(ask("context"), ExtensionBridge.parse("""{"momentum":1,"id":7,"method":"context"}"""))
        assertNull(ExtensionBridge.parse("""{"id":1,"method":"context"}"""))              // not the bridge's
        assertNull(ExtensionBridge.parse("""{"momentum":1,"id":"1","method":"context"}"""))
        assertNull(ExtensionBridge.parse("{oops"))
        assertNull(ExtensionBridge.parse("""{"momentum":1,"id":1,"method":"data.put","params":{"value":"${"x".repeat(ExtensionBridge.MAX_MESSAGE)}"}}"""))
    }

    @Test
    fun answersWithTheContextAndShowsShortToasts() = runBlocking {
        val f = Fake()
        val a = ExtensionBridge.answer("journal", ask("context"), f)
        assertEquals("journal", a["result"]!!.jsonObject["extension"]!!.jsonObject["id"]!!.jsonPrimitive.content)
        ExtensionBridge.answer("journal", ask("toast", params("text" to "x".repeat(300))), f)
        assertEquals(140, f.toasts[0].length)
    }

    @Test
    fun reachesOnlyItsOwnDataEveryPartEscaped() = runBlocking {
        val f = Fake(mapOf(
            "GET /extensions/journal/data/entries/2026-11-18" to buildJsonObject {
                put("doc", buildJsonObject { put("key", "2026-11-18"); put("value", buildJsonObject { put("text", "hi") }) })
            },
        ))
        val got = ExtensionBridge.answer("journal", ask("data.get", params("collection" to "entries", "key" to "2026-11-18")), f)
        assertEquals(buildJsonObject { put("text", "hi") }, got["result"])
        ExtensionBridge.answer("journal", ask("data.put", params("collection" to "entries", "key" to "a/../b", "value" to 1)), f)
        ExtensionBridge.answer("journal", ask("data.delete", params("collection" to "entries", "key" to "k")), f)
        ExtensionBridge.answer("journal", ask("data.list", params("collection" to "entries", "offset" to -5, "limit" to 1000, "order" to "drop table")), f)
        assertEquals(listOf(
            "GET /extensions/journal/data/entries/2026-11-18",
            "PUT /extensions/journal/data/entries/a%2F..%2Fb",
            "DELETE /extensions/journal/data/entries/k",
            "GET /extensions/journal/data/entries?offset=0&limit=100&order=key",
        ), f.calls.map { "${it.first} ${it.second}" })
        assertEquals(buildJsonObject { put("value", 1) }, f.calls[1].third)
    }

    @Test
    fun aMissingDocumentIsNullOtherErrorsCarryTheirStatus() = runBlocking {
        val f = Fake(mapOf(
            "GET /extensions/journal/data/entries/none" to ApiException("Not found", 404),
            "PUT /extensions/journal/data/entries/big" to ApiException("value.text: wrong length", 422),
        ))
        val none = ExtensionBridge.answer("journal", ask("data.get", params("collection" to "entries", "key" to "none")), f)
        assertEquals(JsonNull, none["result"])
        val big = ExtensionBridge.answer("journal", ask("data.put", params("collection" to "entries", "key" to "big", "value" to JsonObject(emptyMap()))), f)
        assertEquals("value.text: wrong length", big["error"]!!.jsonObject["message"]!!.jsonPrimitive.content)
        assertEquals(422, big["error"]!!.jsonObject["status"]!!.jsonPrimitive.int)
        val unknown = ExtensionBridge.answer("journal", ask("app.token"), f)
        assertEquals(400, unknown["error"]!!.jsonObject["status"]!!.jsonPrimitive.int)
        assertEquals(JsonPrimitive(7L), unknown["id"])
    }

    @Test
    fun theContextSaysWhichTabThePageIs() {
        val ext = InstalledExtension("journal", "1.1.0", Texts("Tagebuch", "Journal"), "openbook", enabled = true,
            tabs = listOf(ExtensionTabDef("today", Texts("Heute", "Today"), "tab_pen")))
        val c = ExtensionBridge.context("de", "ann", "Ann", ext, "today")
        assertEquals("today", c["tab"]!!.jsonPrimitive.content)
        assertEquals("de", c["lang"]!!.jsonPrimitive.content)
        assertEquals(1, c["api"]!!.jsonPrimitive.int)
        assertEquals("ann", c["user"]!!.jsonObject["username"]!!.jsonPrimitive.content)
        assertEquals("1.1.0", c["extension"]!!.jsonObject["version"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, ExtensionBridge.context("en", "ann", "Ann", ext, null)["tab"])
    }

    @Test
    fun fillsInTheServersAddressAndEscapesLikeEncodeUriComponent() {
        assertEquals("""<base href="https://m.example/"><meta content="font-src https://m.example">""",
            ExtensionBridge.withOrigin("""<base href="__ORIGIN__/"><meta content="font-src __ORIGIN__">""", "https://m.example/"))
        assertEquals("a%20b%2Fc%3F%C3%A4!~*'()-_.", ExtensionBridge.uriComponent("a b/c?ä!~*'()-_."))
    }
}
