package com.momentum.app.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.io.File
import java.security.MessageDigest

/** A change made while offline, sent to the server when the connection is back. */
@Serializable
data class PendingOp(
    val id: String,
    val method: String,
    val path: String,
    val body: JsonElement? = null,
    /** local (negative) event id for triggers, so a pending tap can be undone before it syncs */
    val eventId: Int? = null,
)

/**
 * Small file store under filesDir/offline:
 *  - cached server responses (state, meta, chats, feed, …) to show without internet
 *  - the outbox of pending operations
 *  - temp rule ids (rules created offline) -> client ids
 */
class LocalStore(context: Context, private val json: Json) {
    private val dir = File(context.filesDir, "offline").apply { mkdirs() }

    private fun file(key: String): File {
        val name = MessageDigest.getInstance("SHA-1").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(dir, "$name.json")
    }

    fun write(key: String, text: String) = runCatching { file(key).writeText(text) }

    fun read(key: String): String? = runCatching { file(key).takeIf { it.exists() }?.readText() }.getOrNull()

    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
    }

    // ---- outbox

    private val opsSerializer = ListSerializer(PendingOp.serializer())

    fun loadOps(): MutableList<PendingOp> =
        read("outbox")?.let { runCatching { json.decodeFromString(opsSerializer, it).toMutableList() }.getOrNull() }
            ?: mutableListOf()

    fun saveOps(ops: List<PendingOp>) {
        write("outbox", json.encodeToString(opsSerializer, ops))
    }

    // ---- rules created offline: temp id -> client id

    private val tempSerializer = MapSerializer(Int.serializer(), String.serializer())

    fun loadTempRules(): MutableMap<Int, String> =
        read("temp_rules")?.let { runCatching { json.decodeFromString(tempSerializer, it).toMutableMap() }.getOrNull() }
            ?: mutableMapOf()

    fun saveTempRules(map: Map<Int, String>) {
        write("temp_rules", json.encodeToString(tempSerializer, map))
    }
}
