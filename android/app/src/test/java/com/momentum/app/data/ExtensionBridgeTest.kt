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

    /** A fake app whose extension has [perms]: two tappable rules, one that isn't, one only known on this phone. */
    private class RulesFake(override val permissions: List<String>) : ExtensionBridge.Deps {
        val taps = mutableListOf<Int>()
        val undone = mutableListOf<Int>()
        override suspend fun call(method: String, path: String, body: JsonElement?): JsonElement = JsonObject(emptyMap())
        override fun toast(text: String) {}
        override fun context(): JsonElement = JsonObject(emptyMap())
        override fun rules() = listOf(
            BridgeRule(1, "Sport", "ball", "acc", 50.0, true), BridgeRule(2, "Doom", "tv", "dec", 40.0, true),
            BridgeRule(3, "Auto", "bed", "acc", 10.0, false), BridgeRule(-4, "Neu", "star", "acc", 5.0, true))
        override suspend fun tap(rule: Int): BridgeTap {
            if (rule == 9) throw ApiException("Tageslimit erreicht", 409)
            taps += rule
            return BridgeTap(100 + rule, 50.0, false)
        }
        override suspend fun undo(event: Int) { undone += event }
    }

    @Test
    fun rulesOnlyWithThePermission() = runBlocking {
        val no = RulesFake(emptyList())
        for (m in listOf("rules.list", "rules.tap", "rules.undo")) {
            val a = ExtensionBridge.answer("daylist", ask(m, params("rule" to 1, "event" to 101)), no)
            assertEquals(403, a["error"]!!.jsonObject["status"]!!.jsonPrimitive.int)
            assertEquals("permission rules missing", a["error"]!!.jsonObject["message"]!!.jsonPrimitive.content)
        }
        assertEquals(emptyList<Int>(), no.taps)
        assertEquals(emptyList<Int>(), no.undone)
    }

    @Test
    fun rulesListTapAndUndo() = runBlocking {
        val f = RulesFake(listOf("rules"))
        // only what the player can tap, not rules only known on this phone
        val list = ExtensionBridge.answer("daylist", ask("rules.list"), f)["result"] as kotlinx.serialization.json.JsonArray
        assertEquals(listOf(1, 2), list.map { it.jsonObject["id"]!!.jsonPrimitive.int })
        assertEquals("dec", list[1].jsonObject["kind"]!!.jsonPrimitive.content)
        assertEquals("Sport", list[0].jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals(50.0, list[0].jsonObject["value"]!!.jsonPrimitive.content.toDouble(), 0.0)
        val tap = ExtensionBridge.answer("daylist", ask("rules.tap", params("rule" to 1)), f)["result"]!!.jsonObject
        assertEquals(101, tap["event"]!!.jsonPrimitive.int)
        assertEquals(50.0, tap["delta"]!!.jsonPrimitive.content.toDouble(), 0.0)
        assertEquals("false", tap["capped"]!!.jsonPrimitive.content)
        assertEquals(listOf(1), f.taps)
        val refused = ExtensionBridge.answer("daylist", ask("rules.tap", params("rule" to 9)), f)
        assertEquals(409, refused["error"]!!.jsonObject["status"]!!.jsonPrimitive.int)
        assertEquals(400, ExtensionBridge.answer("daylist", ask("rules.tap"), f)["error"]!!.jsonObject["status"]!!.jsonPrimitive.int)
        assertEquals(JsonPrimitive(true), ExtensionBridge.answer("daylist", ask("rules.undo", params("event" to 101)), f)["result"])
        assertEquals(listOf(101), f.undone)
    }

    @Test
    fun fillsInTheServersAddressAndEscapesLikeEncodeUriComponent() {
        assertEquals("""<base href="https://m.example/"><meta content="font-src https://m.example">""",
            ExtensionBridge.withOrigin("""<base href="__ORIGIN__/"><meta content="font-src __ORIGIN__">""", "https://m.example/"))
        assertEquals("a%20b%2Fc%3F%C3%A4!~*'()-_.", ExtensionBridge.uriComponent("a b/c?ä!~*'()-_."))
    }
}
