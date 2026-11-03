package com.momentum.app.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The chat encryption must open what the Python reference and the PWA make (e2e-vector.json comes from
 *  server/tests/e2e_crypto.py) and its own messages. */
class E2ETest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val v = Json.parseToJsonElement(javaClass.classLoader!!.getResource("e2e-vector.json")!!.readText()).jsonObject
    private fun s(name: String) = v[name]!!.jsonPrimitive.content

    @Test fun opensTheReferenceMessage() {
        val priv = E2E.privateKey(E2E.unb64(s("reader_pkcs8")))
        val env = json.decodeFromString(ReaderEnvelope.serializer(), v["envelope"].toString())
        val conv = v["conversation"]!!.jsonPrimitive.int
        val reader = v["reader"]!!.jsonPrimitive.int
        val body = json.decodeFromString(E2EBody.serializer(), E2E.openEnvelope(priv, conv, reader, env, preview = false)!!)
        assertEquals(s("text"), body.t)
        assertEquals(s("text"), json.decodeFromString(E2EPreview.serializer(), E2E.openEnvelope(priv, conv, reader, env, preview = true)!!).t)
        assertNull(E2E.openEnvelope(priv, conv + 1, reader, env, preview = false))          // bound to the chat ...
        assertNull(E2E.openEnvelope(priv, conv, v["sender"]!!.jsonPrimitive.int, env, preview = false))   // ... and the reader
    }

    @Test fun opensTheBackupAndMedia() {
        val pkcs8 = E2E.openBackup(s("backup"), E2E.unb64(s("recovery")))!!
        assertTrue(E2E.matches(E2E.privateKey(pkcs8), s("reader_public")))
        assertTrue(!E2E.matches(E2E.privateKey(pkcs8), s("sender_public")))
        assertNull(E2E.openBackup(s("backup"), E2E.random(32)))
        assertArrayEquals(E2E.unb64(s("media_plain")), E2E.decryptMedia(s("media_key"), E2E.unb64(s("media"))))
    }

    @Test fun encryptsForEveryReader() {
        val (annPk, annPub) = E2E.newIdentity()
        val (benPk, benPub) = E2E.newIdentity()
        val (evePk, evePub) = E2E.newIdentity()
        val ann = E2E.privateKey(annPk)
        val env = E2E.encryptEnvelope(ann, annPub, 12, mapOf(1 to annPub, 2 to benPub),
            """{"t":"geheim 🔒"}""", """{"t":"geheim"}""")
        fun forUser(uid: Int) = ReaderEnvelope(env.v, env.eph, env.spk, env.pv, env.ct, env.keys[uid.toString()])
        assertEquals("""{"t":"geheim 🔒"}""", E2E.openEnvelope(E2E.privateKey(benPk), 12, 2, forUser(2), preview = false))
        assertEquals("""{"t":"geheim"}""", E2E.openEnvelope(ann, 12, 1, forUser(1), preview = true))
        assertNull(E2E.openEnvelope(E2E.privateKey(evePk), 12, 2, forUser(2), preview = false))
        assertNull(E2E.openEnvelope(E2E.privateKey(benPk), 12, 2, forUser(2).copy(spk = evePub), preview = false))
    }

    @Test fun refusesAPointOffTheCurve() {
        // ECDH with a point that isn't on P-256 could leak the private key: such a key opens nothing
        val off = E2E.b64(byteArrayOf(4) + ByteArray(64))
        assertTrue(runCatching { E2E.publicKey(off) }.isFailure)
        assertTrue(runCatching { E2E.publicKey(s("reader_public")) }.isSuccess)
        val priv = E2E.privateKey(E2E.unb64(s("reader_pkcs8")))
        val env = json.decodeFromString(ReaderEnvelope.serializer(), v["envelope"].toString())
        assertNull(E2E.openEnvelope(priv, v["conversation"]!!.jsonPrimitive.int, v["reader"]!!.jsonPrimitive.int, env.copy(eph = off), preview = false))
    }

    @Test fun cachesAnOpenedMessageClosed() {
        val env = ReaderEnvelope(1, "e", "s", "p", "c", "k")
        val opened = ChatMessage(id = 1, text = "geheim", at = "", e2e = env, sealed = false,
            media = MediaItem(id = 2, kind = "image", url = "/m", thumb = "/t", encrypted = true, key = "K", mime = "image/jpeg"),
            reply = ReplyRef(id = 0, text = "zitat", e2e = env))
        val closed = E2EStore.closeMessage(opened)
        val text = json.encodeToString(ChatMessage.serializer(), closed)
        assertTrue(!text.contains("geheim") && !text.contains("zitat") && !text.contains("\"K\"") && !text.contains("jpeg"))
        assertEquals(env, closed.e2e)
        assertTrue(closed.media!!.encrypted)
        val plain = ChatMessage(id = 3, text = "offen", at = "")
        assertEquals(plain, E2EStore.closeMessage(plain))
    }

    @Test fun recoveryKeyAndSafetyCode() {
        val raw = E2E.random(32)
        val text = E2E.formatRecovery(raw)
        assertEquals(13, text.split(" ").size)
        assertArrayEquals(raw, E2E.parseRecovery(text))
        assertArrayEquals(raw, E2E.parseRecovery(text.lowercase().replace(" ", "-").replace("O", "0").replace("o", "0")))
        assertNull(E2E.parseRecovery("ABCD EFGH"))
        assertEquals(s("reader_safety_code"), E2E.safetyCode(s("reader_public")))
        assertNotEquals(E2E.safetyCode(s("reader_public")), E2E.safetyCode(s("sender_public")))
    }
}
