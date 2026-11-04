package com.momentum.admin

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class ApiException(message: String, val status: Int) : Exception(message)

@Serializable
data class Report(
    val id: Int, val kind: String, val text: String, val note: String = "", val status: String,
    val username: String = "", val platform: String = "", val device: String = "", val app_version: String = "",
    val created_at: String? = null, val status_at: String? = null, val images: List<String> = emptyList(),
)

@Serializable
data class ReportPage(val reports: List<Report>, val counts: Map<String, Int>)

@Serializable
data class Category(val key: String, val group: String, val type: String, val bytes: Long, val count: Long)

@Serializable
data class Storage(
    val used: Long, val db_bytes: Long, val blob_bytes: Long, val limit: Long, val limit_set: Boolean,
    val disk_total: Long, val disk_free: Long, val categories: List<Category>,
)

@Serializable
data class Item(
    val key: String, val url: String, val thumb: String, val size: Long, val kind: String, val username: String = "",
    val media_id: Int? = null, val post_id: Int? = null, val story_id: Int? = null,
    val width: Int = 0, val height: Int = 0, val duration: Double = 0.0, val created_at: String? = null,
)

@Serializable
data class Table(val table: String, val category: String, val rows: Long, val bytes: Long)

@Serializable
data class CategoryPage(val items: List<Item>, val tables: List<Table>, val total: Int)

/** A posted photo / video the content filter found doubtful (server/moderation.py); [thumb] / [url] are the real ones. */
@Serializable
data class FlaggedMedia(
    val media_id: Int, val kind: String, val score: Double, val review: String, val url: String, val thumb: String,
    val cover: String = "", val username: String = "", val post_id: Int? = null, val story_id: Int? = null,
    val created_at: String? = null,
)

@Serializable
data class FlaggedPage(val items: List<FlaggedMedia>, val counts: Map<String, Int>)

@Serializable
data class Day(val day: String, val active: Int, val signups: Int, val posts: Int, val messages: Int)

@Serializable
data class Stats(val totals: Map<String, Int>, val days: List<Day>)

/** Client for the admin endpoints under /api/admin (and the login). */
class Api(var baseUrl: String, var token: String?) {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)          // the storage overview lists the whole blob store
        .build()
    val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    fun url(path: String) = baseUrl.trimEnd('/') + path

    /** A stored file ("/media/…", "/reports/…", "/avatars/…"), served only with the admin session. */
    fun blob(path: String) = url("/api/admin/blob/" + path.trimStart('/'))

    /** Same client with the session attached: for images (Coil). Only ever sent to our own server. */
    val imageClient: OkHttpClient by lazy {
        client.newBuilder().addInterceptor { chain ->
            val req = chain.request()
            val own = req.url.toString().startsWith(baseUrl.trimEnd('/') + "/")
            chain.proceed(if (own && token != null) req.newBuilder().header("Authorization", "Bearer $token").build() else req)
        }.build()
    }

    private suspend fun call(method: String, path: String, body: JsonElement? = null): String = withContext(Dispatchers.IO) {
        val payload = body?.toString() ?: if (method == "PUT" || method == "POST") "{}" else null
        val request = Request.Builder()
            .url(url("/api$path"))
            .method(method, payload?.toRequestBody("application/json".toMediaType()))
            .header("Accept-Language", S.lang)
            .apply { token?.let { header("Authorization", "Bearer $it") } }
            .build()
        try {
            client.newCall(request).execute().use { res ->
                val text = res.body?.string().orEmpty()
                if (!res.isSuccessful) {
                    val msg = runCatching { json.parseToJsonElement(text).jsonObject["error"]?.jsonPrimitive?.content }.getOrNull()
                    throw ApiException(msg ?: "HTTP ${res.code}", res.code)
                }
                text
            }
        } catch (e: IOException) {
            throw ApiException(S.t("Server nicht erreichbar", "Server unreachable"), 0)
        }
    }

    /** Admin login: password + the code from the authenticator app; returns an admin-only session. */
    suspend fun login(login: String, password: String, code: String): String {
        val body = buildJsonObject { put("login", login); put("password", password); put("code", code) }
        return json.parseToJsonElement(call("POST", "/admin/login", body)).jsonObject["token"]!!.jsonPrimitive.content
    }

    suspend fun me(): String = json.parseToJsonElement(call("GET", "/admin/me")).jsonObject["username"]!!.jsonPrimitive.content

    suspend fun logout() {
        runCatching { call("POST", "/admin/logout") }
    }

    suspend fun reports(status: String, offset: Int, limit: Int = 30): ReportPage =
        json.decodeFromString(call("GET", "/admin/reports?status=$status&offset=$offset&limit=$limit"))

    suspend fun report(id: Int): Report =
        json.decodeFromJsonElement(Report.serializer(), json.parseToJsonElement(call("GET", "/admin/reports/$id")).jsonObject["report"]!!)

    suspend fun updateReport(id: Int, status: String? = null, note: String? = null): Report {
        val body = buildJsonObject {
            status?.let { put("status", it) }
            note?.let { put("note", it) }
        }
        return json.decodeFromJsonElement(Report.serializer(),
            json.parseToJsonElement(call("PUT", "/admin/reports/$id", body)).jsonObject["report"]!!)
    }

    suspend fun stats(): Stats = json.decodeFromString(call("GET", "/admin/stats"))

    /** review: waiting | ok | keep */
    suspend fun flagged(review: String, offset: Int, limit: Int = 30): FlaggedPage =
        json.decodeFromString(call("GET", "/admin/flagged?review=$review&offset=$offset&limit=$limit"))

    /** action: ok (shown normally) | keep (stays covered) | remove (its post / story is deleted) */
    suspend fun reviewFlagged(mediaId: Int, action: String) {
        call("PUT", "/admin/flagged/$mediaId", buildJsonObject { put("action", action) })
    }

    suspend fun storage(refresh: Boolean = false): Storage =
        json.decodeFromString(call("GET", "/admin/storage" + if (refresh) "?refresh=1" else ""))

    suspend fun category(key: String, offset: Int, limit: Int = 40): CategoryPage =
        json.decodeFromString(call("GET", "/admin/storage/$key?offset=$offset&limit=$limit"))

    /** null = the whole disk */
    suspend fun setLimit(bytes: Long?): Storage {
        val body = buildJsonObject { put("limit", bytes?.let { JsonPrimitive(it) } ?: JsonNull) }
        return json.decodeFromString(call("PUT", "/admin/storage/limit", body))
    }
}
