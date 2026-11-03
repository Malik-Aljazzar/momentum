// @vitest-environment node
// The chat encryption (src/e2ecrypto.ts): it must open what the Python reference and the Android app make
// (e2e-vector.json comes from server/tests/e2e_crypto.py) and its own messages.
import { readFileSync } from "node:fs"
import { join } from "node:path"
import { describe, expect, it } from "vitest"
import * as C from "../../src/e2ecrypto"
import { closeMessage, mentionNames } from "../../src/e2e"

const v = JSON.parse(readFileSync(join(__dirname, "e2e-vector.json"), "utf8"))

describe("chat encryption", () => {
  it("opens the reference message, its preview, the backup and a medium", async () => {
    const priv = await C.importPrivate(C.unb64(v.reader_pkcs8))
    const body = await C.openEnvelope<{ t: string }>(priv, v.conversation, v.reader, v.envelope, "ct")
    expect(body?.t).toBe(v.text)
    expect((await C.openEnvelope<{ t: string }>(priv, v.conversation, v.reader, v.envelope, "pv"))?.t).toBe(v.text)
    // the wrong chat or reader: nothing (the key is bound to both)
    expect(await C.openEnvelope(priv, v.conversation + 1, v.reader, v.envelope, "ct")).toBeNull()
    expect(await C.openEnvelope(priv, v.conversation, v.sender, v.envelope, "ct")).toBeNull()
    const pkcs8 = await C.openBackup(v.backup, C.unb64(v.recovery))
    expect(pkcs8).not.toBeNull()
    expect(await C.publicOf(pkcs8!)).toBe(v.reader_public)
    expect(await C.openBackup(v.backup, C.random(32))).toBeNull()
    expect(C.b64(await C.decryptMedia(v.media_key, C.unb64(v.media)))).toBe(v.media_plain)
  })

  it("encrypts for every reader, signed by the sender's key", async () => {
    const ann = await C.newIdentity(), ben = await C.newIdentity(), eve = await C.newIdentity()
    const annKey = { priv: await C.importPrivate(ann.pkcs8), pub: ann.pub }
    const env = await C.encryptEnvelope(annKey, 12, [{ user_id: 1, public: ann.pub }, { user_id: 2, public: ben.pub }],
      { t: "geheim 🔒", m: null }, { t: "geheim", k: null })
    const forBen = { ...env, key: env.keys["2"] }
    expect((await C.openEnvelope<{ t: string }>(await C.importPrivate(ben.pkcs8), 12, 2, forBen, "ct"))?.t).toBe("geheim 🔒")
    // the sender reads it on another device too
    expect((await C.openEnvelope<{ t: string }>(annKey.priv, 12, 1, { ...env, key: env.keys["1"] }, "pv"))?.t).toBe("geheim")
    // someone else's key opens nothing; a forged sender key neither
    expect(await C.openEnvelope(await C.importPrivate(eve.pkcs8), 12, 2, forBen, "ct")).toBeNull()
    expect(await C.openEnvelope(await C.importPrivate(ben.pkcs8), 12, 2, { ...forBen, spk: eve.pub }, "ct")).toBeNull()
  })

  it("writes and reads the recovery key forgivingly", () => {
    const raw = C.random(32)
    const text = C.formatRecovery(raw)
    expect(text.split(" ")).toHaveLength(13)
    expect(C.b64(C.parseRecovery(text)!)).toBe(C.b64(raw))
    const typed = text.toLowerCase().replace(/ /g, "-").replace(/O/gi, "0")
    expect(C.b64(C.parseRecovery(typed)!)).toBe(C.b64(raw))
    expect(C.parseRecovery("ABCD EFGH")).toBeNull()
  })

  it("makes the same safety code for the same key", async () => {
    const code = await C.safetyCode(v.reader_public)
    expect(code).toMatch(/^(\d{5} ){5}\d{5}$/)
    expect(await C.safetyCode(v.reader_public)).toBe(code)
    expect(await C.safetyCode(v.sender_public)).not.toBe(code)
    expect(code).toBe(v.reader_safety_code)
  })

  it("caches an opened message without its text and media key", () => {
    const env = { v: 1, eph: "e", spk: "s", pv: "p", ct: "c", key: "k" }
    const opened = { id: 1, at: "", text: "geheim", sealed: false, e2e: env, media: { id: 2, kind: "image", url: "/m", thumb: "/t", encrypted: true, key: "K", mime: "image/jpeg" },
      reply: { id: 0, text: "zitat", e2e: env } }
    const closed = closeMessage(opened)
    expect(JSON.stringify(closed)).not.toMatch(/geheim|zitat|"K"|jpeg/)
    expect(closed.e2e).toBe(env)
    expect(closed.media?.encrypted).toBe(true)
    const plain = { id: 3, at: "", text: "offen" }
    expect(closeMessage(plain)).toBe(plain)
  })

  it("names the tagged accounts for the server", () => {
    expect(mentionNames("hi @Tim. und @anna_b, nochmal @tim")).toEqual(["tim", "anna_b"])
  })
})
