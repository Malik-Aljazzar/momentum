package com.momentum.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Where an upload is, for its progress bar: a video shrunk on the phone, the bytes sent, the server working on it. */
sealed interface UploadProgress {
    data class Compressing(val done: Float) : UploadProgress
    data class Sending(val done: Float) : UploadProgress
    data object Processing : UploadProgress
}

/** Bytes sent in pieces, reporting how much went out. */
private class ProgressBody(private val bytes: ByteArray, private val type: MediaType, private val onProgress: (Float) -> Unit) : RequestBody() {
    override fun contentType() = type
    override fun contentLength() = bytes.size.toLong()
    override fun writeTo(sink: okio.BufferedSink) {
        var sent = 0
        while (sent < bytes.size) {
            val n = minOf(64 * 1024, bytes.size - sent)
            sink.write(bytes, sent, n)
            sink.flush()
            sent += n
            onProgress(sent.toFloat() / bytes.size)
        }
    }
}

/** [fromServer]: the text is the server's own explanation (its JSON `error`), not a worded status code. */
class ApiException(message: String, val status: Int, val fromServer: Boolean = true) : Exception(message)

/** Where this build came from: Play builds are updated by Play, the APK by the server's version gate. */
private val CHANNEL = if (com.momentum.app.BuildConfig.PLAY) "play" else "apk"

/** Client for the Quart JSON API (/api/...). */
class Api(var baseUrl: String, var token: String?, var language: String) {
    /** Called when the server says this app is outdated (HTTP 426): the update screen takes over. */
    var onUpdateRequired: () -> Unit = {}

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    // uploads (photos, videos, voice) may take a while on mobile networks
    private val uploadClient = client.newBuilder()
        .writeTimeout(120, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    suspend fun raw(method: String, path: String, body: JsonElement? = null): String =
        withContext(Dispatchers.IO) {
            val payload = when {
                body != null -> body.toString()
                method == "POST" || method == "PUT" -> "{}"
                else -> null
            }
            val request = Request.Builder()
                .url(baseUrl.trimEnd('/') + "/api" + path)
                .method(method, payload?.toRequestBody(JSON))
                .header("Accept-Language", language)
                .header("X-App-Version", com.momentum.app.BuildConfig.VERSION_CODE.toString())
                .header("X-App-Channel", CHANNEL)
                .apply { token?.let { header("Authorization", "Bearer $it") } }
                .build()
            try {
                client.newCall(request).execute().use { res ->
                    val text = res.body?.string().orEmpty()
                    if (res.code == 426) onUpdateRequired()
                    if (!res.isSuccessful) throw failure(text, res.code)
                    text
                }
            } catch (e: IOException) {
                throw ApiException("Server nicht erreichbar", 0, fromServer = false)
            }
        }

    /** POST raw bytes (e.g. an image) and return the response text; [onProgress] gets the part sent (0..1). */
    suspend fun upload(path: String, bytes: ByteArray, contentType: String, onProgress: ((Float) -> Unit)? = null): String = withContext(Dispatchers.IO) {
        val type = contentType.toMediaType()
        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/api" + path)
            .post(if (onProgress != null) ProgressBody(bytes, type, onProgress) else bytes.toRequestBody(type))
            .header("Accept-Language", language)
            .header("X-App-Version", com.momentum.app.BuildConfig.VERSION_CODE.toString())
            .header("X-App-Channel", CHANNEL)
            .apply { token?.let { header("Authorization", "Bearer $it") } }
            .build()
        try {
            uploadClient.newCall(request).execute().use { res ->
                val text = res.body?.string().orEmpty()
                if (res.code == 426) onUpdateRequired()
                if (!res.isSuccessful) throw failure(text, res.code)
                text
            }
        } catch (e: IOException) {
            throw ApiException("Server nicht erreichbar", 0, fromServer = false)
        }
    }

    /** Download e.g. a profile picture; server-relative paths are resolved against baseUrl. */
    suspend fun bytes(url: String): ByteArray? = withContext(Dispatchers.IO) {
        val full = if (url.startsWith("http")) url else baseUrl.trimEnd('/') + url
        try {
            client.newCall(Request.Builder().url(full).build()).execute().use { res ->
                if (res.isSuccessful) res.body?.bytes() else null
            }
        } catch (e: IOException) {
            null
        }
    }

    suspend inline fun <reified T> call(method: String, path: String, body: JsonElement? = null): T =
        json.decodeFromString(raw(method, path, body))

    /** The server's explanation (JSON `error`); without one (nginx while the app restarts, a proxy page) the
     *  status is worded in the user's language: 502/503/504 = the server is down for a moment. */
    private fun failure(text: String, code: Int): ApiException {
        val explained = try {
            json.parseToJsonElement(text).jsonObject["error"]?.jsonPrimitive?.content
        } catch (e: Exception) {
            null
        }
        if (explained != null) return ApiException(explained, code)
        val s = com.momentum.app.ui.stringsFor(if (language == "de") com.momentum.app.ui.Lang.DE else com.momentum.app.ui.Lang.EN)
        return ApiException(if (code in 502..504) s.serverDown(code) else s.serverError(code), code, fromServer = false)
    }

    companion object {
        private val JSON = "application/json".toMediaType()
        val EMPTY = JsonObject(emptyMap())
    }
}
