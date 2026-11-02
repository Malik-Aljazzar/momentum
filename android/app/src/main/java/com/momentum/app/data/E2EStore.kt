package com.momentum.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

@Serializable data class MyKeys(val public: String? = null, val backup: String? = null)
@Serializable data class MemberKey(@SerialName("user_id") val userId: Int, val username: String, val public: String? = null)
@Serializable data class MemberKeysResponse(val keys: List<MemberKey> = emptyList())

/**
 * End-to-end encrypted chats on this phone (= web/src/e2e.ts): my key (in private prefs, encrypted with a
 * key of the Android Keystore; it stays on logout - the backup on the server is only for a new device),
 * opening and sealing messages, photos / voice messages, safety codes. The crypto: [E2E], scheme: server/e2e.py.
 */
object E2EStore {
    enum class Status { Loading, Ready, Locked, Off }

    var status by mutableStateOf(Status.Loading); private set
    /** the recovery key wasn't confirmed as written down yet: the chat list reminds of it */
    var recoveryPending by mutableStateOf(false); private set
    /** players whose key is no longer the one first seen here (user id -> new key): a warning until confirmed */
    var keyWarnings by mutableStateOf<Map<Int, String>>(emptyMap()); private set

    /** pkcs8 and recovery are encrypted with the Keystore key; backup = as on the server */
    @Serializable private data class Stored(val pkcs8: String, val pub: String, val backup: String, val recovery: String, val noted: Boolean)
    private class Me(val id: Int, val priv: PrivateKey, val pub: String, var stored: Stored)

    private var me: Me? = null
    private var userId: Int? = null
    private var known: Map<String, String> = emptyMap()
    private var ctx: Context? = null
    private val lock = Mutex()
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val memberKeys = mutableMapOf<Int, List<MemberKey>>()

    private fun prefs(c: Context) = c.getSharedPreferences("e2e", Context.MODE_PRIVATE)

    // ---------------------------------------------------------------- the Keystore key that protects mine

    private const val ALIAS = "momentum-e2e"

    private fun keystoreKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build())
        return gen.generateKey()
    }

    private fun protect(plain: ByteArray): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, keystoreKey()) }
        return E2E.b64(c.iv + c.doFinal(plain))
    }

    private fun unprotect(value: String): ByteArray {
        val d = E2E.unb64(value)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, keystoreKey(), GCMParameterSpec(128, d, 0, 12)) }
        return c.doFinal(d, 12, d.size - 12)
    }

    private fun load(c: Context, uid: Int): Stored? =
        prefs(c).getString("key_$uid", null)?.let { runCatching { json.decodeFromString(Stored.serializer(), it) }.getOrNull() }

    private fun save(c: Context, uid: Int, s: Stored?) {
        prefs(c).edit().apply { if (s == null) remove("key_$uid") else putString("key_$uid", json.encodeToString(Stored.serializer(), s)) }.apply()
    }

    private fun use(uid: Int, s: Stored): Boolean = runCatching {
        me = Me(uid, E2E.privateKey(unprotect(s.pkcs8)), s.pub, s)
        recoveryPending = !s.noted
        status = Status.Ready
        true
    }.getOrDefault(false)

    // ---------------------------------------------------------------- my key

    /** After login / start: my key on this phone, made on the first device, or Locked until unlocked. */
    suspend fun start(c: Context, api: Api, uid: Int) = withContext(Dispatchers.IO) {
        lock.withLock {
            if (userId == uid && status == Status.Ready) return@withLock
            ctx = c.applicationContext
            userId = uid
            prefs(c).edit().putInt("current", uid).apply()
            known = loadKnown(c, uid)
            val local = load(c, uid)
            val server = try {
                api.call<MyKeys>("GET", "/e2e/me")
            } catch (e: ApiException) {
                if (local != null) use(uid, local)          // offline: the key on this phone works
                return@withLock
            }
            if (local != null && server.public == local.pub && use(uid, local)) return@withLock
            if (local != null && server.public == null) {   // the server has none (yet): send mine again
                runCatching { api.raw("PUT", "/e2e/me", buildJsonObject { put("public", local.pub); put("backup", local.backup) }) }
                use(uid, local)
                return@withLock
            }
            if (server.public == null && makeKey(c, api, uid, replace = false)) return@withLock
            // made on another device (or reset there): the recovery key opens the backup
            if (local != null) save(c, uid, null)
            me = null
            status = Status.Locked
        }
    }

    private suspend fun makeKey(c: Context, api: Api, uid: Int, replace: Boolean): Boolean {
        val (pkcs8, pub) = E2E.newIdentity()
        val recovery = E2E.random(32)
        val backup = E2E.makeBackup(pkcs8, recovery)
        try {
            api.raw("PUT", "/e2e/me", buildJsonObject { put("public", pub); put("backup", backup); put("replace", replace) })
        } catch (e: ApiException) {
            if (e.status == 409) return false            // another device was quicker
            throw e
        }
        val stored = Stored(protect(pkcs8), pub, backup, protect(E2E.formatRecovery(recovery).toByteArray()), noted = false)
        save(c, uid, stored)
        return use(uid, stored)
    }

    /** The recovery key typed in: true = this phone has my key now. */
    suspend fun unlock(c: Context, api: Api, code: String): Boolean = withContext(Dispatchers.IO) {
        val uid = userId ?: return@withContext false
        val recovery = E2E.parseRecovery(code) ?: return@withContext false
        val server = runCatching { api.call<MyKeys>("GET", "/e2e/me") }.getOrNull() ?: return@withContext false
        val pkcs8 = server.backup?.let { E2E.openBackup(it, recovery) } ?: return@withContext false
        val pub = server.public ?: return@withContext false
        if (!E2E.matches(E2E.privateKey(pkcs8), pub)) return@withContext false
        val stored = Stored(protect(pkcs8), pub, server.backup, protect(E2E.formatRecovery(recovery).toByteArray()), noted = true)
        save(c, uid, stored)
        use(uid, stored)
    }

    /** Recovery key lost: a new key (the old encrypted messages can't be read any more). */
    suspend fun resetKey(c: Context, api: Api): Boolean = withContext(Dispatchers.IO) {
        val uid = userId ?: return@withContext false
        runCatching { makeKey(c, api, uid, replace = true) }.getOrDefault(false)
    }

    fun recoveryCode(): String? = me?.stored?.let { runCatching { String(unprotect(it.recovery)) }.getOrNull() }

    fun markRecoveryNoted(c: Context) {
        val m = me ?: return
        m.stored = m.stored.copy(noted = true)
        save(c, m.id, m.stored)
        recoveryPending = false
    }

    fun myPublicKey(): String? = me?.pub

    /** Logged out: forget the key in memory (it stays on the phone for the next login). */
    fun stop() {
        me = null
        userId = null
        status = Status.Loading
        recoveryPending = false
        keyWarnings = emptyMap()
        memberKeys.clear()
    }

    // ---------------------------------------------------------------- the others' keys

    private val knownSerializer = MapSerializer(String.serializer(), String.serializer())

    private fun loadKnown(c: Context, uid: Int): Map<String, String> =
        prefs(c).getString("known_$uid", null)?.let { runCatching { json.decodeFromString(knownSerializer, it) }.getOrNull() } ?: emptyMap()

    private fun saveKnown() {
        val c = ctx ?: return
        val uid = userId ?: return
        prefs(c).edit().putString("known_$uid", json.encodeToString(knownSerializer, known)).apply()
    }

    /** The members' keys of a chat (refresh: after the server said a key changed). */
    suspend fun chatKeys(api: Api, convId: Int, refresh: Boolean = false): List<MemberKey> {
        if (!refresh) memberKeys[convId]?.let { return it }
        val keys = api.call<MemberKeysResponse>("GET", "/conversations/$convId/keys").keys
        keys.forEach { k -> if (k.public != null && k.userId != userId) checkKey(k.userId, k.public) }
        memberKeys[convId] = keys
        return keys
    }

    /** Trust on first use: a key seen before must stay the same, a new one is shown as a warning. */
    private fun checkKey(uid: Int, pub: String) {
        val before = known[uid.toString()]
        if (before == null) {
            known = known + (uid.toString() to pub)
            saveKnown()
        } else if (before != pub && keyWarnings[uid] != pub) {
            keyWarnings = keyWarnings + (uid to pub)
        }
    }

    /** "I compared it / it's fine": the new key is the known one now. */
    fun trustKey(uid: Int) {
        val pub = keyWarnings[uid] ?: return
        known = known + (uid.toString() to pub)
        saveKnown()
        keyWarnings = keyWarnings - uid
    }

    fun safetyCode(pub: String) = E2E.safetyCode(pub)

    // ---------------------------------------------------------------- messages

    /** A message as the server sent it -> with its text / media key opened (sealed: can't be opened here). */
    fun openMessage(convId: Int, m: ChatMessage): ChatMessage {
        val my = me
        var out = m
        val env = m.e2e
        if (env != null) {
            val body = my?.let { E2E.openEnvelope(it.priv, convId, it.id, env, preview = false) }
                ?.let { runCatching { json.decodeFromString(E2EBody.serializer(), it) }.getOrNull() }
            out = if (body != null) {
                if (!m.mine && m.user?.id != null) checkKey(m.user.id, env.spk)
                m.copy(text = body.t, sealed = false,
                    media = m.media?.let { md -> body.m?.let { md.copy(key = it.k, mime = it.mime) } ?: md })
            } else m.copy(sealed = true)
        }
        val reply = m.reply
        val replyEnv = reply?.e2e
        if (reply != null && replyEnv != null) {
            val pv = my?.let { openPreview(it, convId, replyEnv) }
            out = out.copy(reply = reply.copy(text = pv?.t.orEmpty(), mediaKind = pv?.k ?: reply.mediaKind))
        }
        return out
    }

    private fun openPreview(my: Me, convId: Int, env: ReaderEnvelope): E2EPreview? =
        E2E.openEnvelope(my.priv, convId, my.id, env, preview = true)
            ?.let { runCatching { json.decodeFromString(E2EPreview.serializer(), it) }.getOrNull() }

    /** The chat list's line for an encrypted last message. */
    fun openLast(convId: Int, last: LastMessage): LastMessage {
        val env = last.e2e ?: return last
        val pv = me?.let { openPreview(it, convId, env) } ?: return last.copy(sealed = true)
        return last.copy(text = pv.t, mediaKind = pv.k ?: last.mediaKind, sealed = false)
    }

    /** A notification's text in the background (the app may not run: my key from the phone's storage). */
    fun previewText(c: Context, convId: Int, env: ReaderEnvelope): E2EPreview? {
        val my = me ?: run {
            val uid = prefs(c).getInt("current", -1).takeIf { it > 0 } ?: return null
            val s = load(c, uid) ?: return null
            runCatching { Me(uid, E2E.privateKey(unprotect(s.pkcs8)), s.pub, s) }.getOrNull()
        } ?: return null
        return openPreview(my, convId, env)
    }

    /** "@tim" in a text: the server can't read it, so the sender names the tagged accounts. */
    fun mentionNames(text: String): List<String> =
        Regex("@([a-z0-9_.]{3,30})", RegexOption.IGNORE_CASE).findAll(text).map { it.groupValues[1].trimEnd('.').lowercase() }.distinct().toList()

    /** A new message for everyone in the chat who has a key (me too: my other devices) -> {e2e, mentions}. */
    suspend fun sealMessage(api: Api, convId: Int, text: String, media: Triple<String, String, String>? = null): JsonObject =
        withContext(Dispatchers.Default) {
            val my = me ?: throw IllegalStateException("locked")
            val readers = chatKeys(api, convId).mapNotNull { k -> k.public?.let { k.userId to it } }.toMap()
            val body = json.encodeToString(E2EBody.serializer(), E2EBody(text, media?.let { E2EMediaKey(it.first, it.second) }))
            val preview = json.encodeToString(E2EPreview.serializer(), E2EPreview(text.take(180), media?.third))
            val env = E2E.encryptEnvelope(my.priv, my.pub, convId, readers, body, preview)
            buildJsonObject {
                put("e2e", json.encodeToJsonElement(Envelope.serializer(), env))
                put("mentions", JsonArray(mentionNames(text).map { JsonPrimitive(it) }))
            }
        }

    /** A photo / voice message for upload: a new key per item (sent inside the message). */
    fun sealMedia(full: ByteArray, thumb: ByteArray? = null): Triple<String, ByteArray, ByteArray?> {
        val key = E2E.random(32)
        return Triple(E2E.b64(key), E2E.encryptMedia(key, full), thumb?.let { E2E.encryptMedia(key, it) })
    }

    /** A decrypted copy of an encrypted photo / voice message in the cache (file:// address); null: failed. */
    suspend fun mediaFile(c: Context, api: Api, media: MediaItem, thumb: Boolean): String? {
        val src = if (thumb) media.thumb else media.url
        val key = media.key ?: return null
        val name = MessageDigest.getInstance("SHA-256").digest("$src#$key".toByteArray()).joinToString("") { "%02x".format(it) }.take(32)
        val ext = if (thumb || media.kind == "image") "jpg" else if (media.mime?.contains("webm") == true) "webm" else "m4a"
        val file = File(File(c.cacheDir, "e2e").apply { mkdirs() }, "$name.$ext")
        if (file.exists()) return "file://${file.absolutePath}"
        return withContext(Dispatchers.IO) {
            val bytes = api.bytes(src) ?: return@withContext null
            runCatching { file.writeBytes(E2E.decryptMedia(key, bytes)); "file://${file.absolutePath}" }.getOrNull()
        }
    }
}
