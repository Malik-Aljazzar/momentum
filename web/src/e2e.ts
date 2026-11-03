// End-to-end encrypted chats in the app (= android/.../data/E2E.kt + E2EStore): my key on this device,
// opening messages, encrypting new ones, photos / voice messages, safety codes. The crypto itself is in
// e2ecrypto.ts, the scheme in server/e2e.py.
import { signal } from "@preact/signals"
import { useEffect, useState } from "preact/hooks"
import { api, ApiError } from "./api"
import * as C from "./e2ecrypto"
import * as K from "./e2ekeys"
import type { ChatMessage, LastMessage, MediaItem } from "./model"

/** off: this browser can't (no WebCrypto / IndexedDB); locked: my key exists, this device needs the recovery key */
export type E2EState = "loading" | "ready" | "locked" | "off"
export const e2eState = signal<E2EState>("loading")
/** the recovery key wasn't confirmed as written down yet: the chat list reminds of it */
export const recoveryPending = signal(false)
/** players whose key is no longer the one first seen here (user id -> new key): a warning until confirmed */
export const keyWarnings = signal<Record<number, string>>({})

interface Me { id: number; priv: CryptoKey; pub: string; stored: K.StoredKey }
let me: Me | null = null
let userId: number | null = null
let known: Record<string, string> = {}

export interface MessageBody { t?: string; m?: { k: string; mime?: string } | null }
export interface Preview { t?: string; k?: string | null }

function use(id: number, stored: K.StoredKey) {
  me = { id, priv: stored.priv, pub: stored.pub, stored }
  recoveryPending.value = !stored.noted
  e2eState.value = "ready"
}

/** After login / start: my key on this device, made on the first device, or "locked" until unlocked. */
export async function startE2E(id: number) {
  if (!globalThis.crypto?.subtle || typeof indexedDB === "undefined") { e2eState.value = "off"; return }
  if (userId === id && e2eState.value === "ready") return
  userId = id
  known = await K.loadKnown(id)
  const local = await K.loadKey(id)
  let server: { public: string | null; backup: string | null }
  try {
    server = await api.call("GET", "/e2e/me")
  } catch {
    if (local) use(id, local)            // offline: the key on this device works
    return
  }
  if (local && server.public === local.pub) { use(id, local); return }
  if (local && !server.public) {         // the server has none (yet): send mine again
    await api.call("PUT", "/e2e/me", { public: local.pub, backup: local.backup }).catch(() => {})
    use(id, local)
    return
  }
  if (!server.public) {                  // the first device: make the key
    if (await makeKey(id, false)) return
  }
  // made on another device (or reset there): the recovery key opens the backup
  if (local) await K.saveKey(id, null)
  me = null
  e2eState.value = "locked"
}

async function makeKey(id: number, replace: boolean): Promise<boolean> {
  const ident = await C.newIdentity()
  const recovery = C.random(32)
  const backup = await C.makeBackup(ident.pkcs8, recovery)
  try {
    await api.call("PUT", "/e2e/me", { public: ident.pub, backup, replace })
  } catch (e) {
    if (e instanceof ApiError && e.status === 409) return false     // another device was quicker
    throw e
  }
  const stored: K.StoredKey = { priv: await C.importPrivate(ident.pkcs8), pub: ident.pub, backup, recovery: C.formatRecovery(recovery), noted: false }
  await K.saveKey(id, stored)
  use(id, stored)
  return true
}

/** The recovery key typed in: true = this device has my key now. */
export async function unlock(code: string): Promise<boolean> {
  const recovery = C.parseRecovery(code)
  if (!recovery || userId == null) return false
  const server = await api.call<{ public: string | null; backup: string | null }>("GET", "/e2e/me")
  if (!server.backup) return false
  const pkcs8 = await C.openBackup(server.backup, recovery)
  if (!pkcs8 || (await C.publicOf(pkcs8)) !== server.public) return false
  const stored: K.StoredKey = { priv: await C.importPrivate(pkcs8), pub: server.public!, backup: server.backup, recovery: C.formatRecovery(recovery), noted: true }
  await K.saveKey(userId, stored)
  use(userId, stored)
  return true
}

/** Recovery key lost: a new key (the old encrypted messages can't be read any more). */
export async function resetKey(): Promise<boolean> {
  if (userId == null) return false
  return makeKey(userId, true)
}

export const recoveryCode = () => me?.stored.recovery ?? null

export async function markRecoveryNoted() {
  if (!me || userId == null) return
  me.stored = { ...me.stored, noted: true }
  await K.saveKey(userId, me.stored)
  recoveryPending.value = false
}

/** Logged out: forget the key in memory (it stays on the device for the next login). */
export function stopE2E() {
  me = null
  userId = null
  e2eState.value = "loading"
  recoveryPending.value = false
  keyWarnings.value = {}
  memberKeys.clear()
}

export const myPublicKey = () => me?.pub ?? null

// ---------------------------------------------------------------- the others' keys

export interface MemberKey { user_id: number; username: string; public: string | null }
/** the members' keys and their fingerprint ("hash"): it goes with every message, so the server can tell
 *  when someone joined / left / reset their key since (409: load again, send again) */
interface KeySet { keys: MemberKey[]; hash?: string }
const memberKeys = new Map<number, Promise<KeySet>>()

function keySet(convId: number, refresh = false): Promise<KeySet> {
  if (refresh || !memberKeys.has(convId)) {
    const p = api.call<KeySet>("GET", `/conversations/${convId}/keys`).then((r) => {
      for (const k of r.keys) if (k.public && k.user_id !== userId) checkKey(k.user_id, k.public)
      return r
    })
    p.catch(() => memberKeys.delete(convId))
    memberKeys.set(convId, p)
  }
  return memberKeys.get(convId)!
}

/** The members' keys of a chat (refresh: after the server said a key / the members changed). */
export const chatKeys = (convId: number, refresh = false): Promise<MemberKey[]> => keySet(convId, refresh).then((r) => r.keys)

/** Trust on first use: a key seen before must stay the same, a new one is shown as a warning. */
function checkKey(uid: number, pub: string) {
  const before = known[String(uid)]
  if (!before) {
    known = { ...known, [String(uid)]: pub }
    if (userId != null) void K.saveKnown(userId, known)
  } else if (before !== pub && keyWarnings.value[uid] !== pub) {
    keyWarnings.value = { ...keyWarnings.value, [uid]: pub }
  }
}

/** "I compared it / it's fine": the new key is the known one now. */
export async function trustKey(uid: number) {
  const pub = keyWarnings.value[uid]
  if (!pub || userId == null) return
  known = { ...known, [String(uid)]: pub }
  await K.saveKnown(userId, known)
  const { [uid]: _, ...rest } = keyWarnings.value
  keyWarnings.value = rest
}

export const safetyCode = C.safetyCode

// ---------------------------------------------------------------- messages

/** A message as the server sent it -> with its text / media key opened (sealed: can't be opened here). */
export async function openMessage(convId: number, m: ChatMessage): Promise<ChatMessage> {
  let out = m
  if (m.e2e) {
    const body = me ? await C.openEnvelope<MessageBody>(me.priv, convId, me.id, m.e2e, "ct") : null
    if (body) {
      out = { ...m, text: body.t ?? "", sealed: false, media: m.media && body.m ? { ...m.media, key: body.m.k, mime: body.m.mime } : m.media }
      if (!m.mine && m.user?.id != null) checkKey(m.user.id, m.e2e.spk)
    } else out = { ...m, sealed: true }
  }
  if (m.reply?.e2e) {
    const pv = me ? await C.openEnvelope<Preview>(me.priv, convId, me.id, m.reply.e2e, "pv") : null
    out = { ...out, reply: { ...m.reply, text: pv?.t ?? "", media_kind: pv?.k ?? m.reply.media_kind } }
  }
  return out
}

export const openMessages = (convId: number, list: ChatMessage[]) => Promise.all(list.map((m) => openMessage(convId, m)))

/** A message back to how the server sent it (text and media key out again, the envelope stays): what
 *  the device's chat cache keeps - it's opened again when the chat loads. */
export function closeMessage(m: ChatMessage): ChatMessage {
  let out = m
  if (m.e2e) out = { ...m, text: "", sealed: undefined, media: m.media ? { ...m.media, key: undefined, mime: undefined } : m.media }
  if (m.reply?.e2e) out = { ...out, reply: { ...m.reply, text: "" } }
  return out
}

/** The chat list's line for an encrypted last message. */
export async function openLast(convId: number, last: LastMessage): Promise<LastMessage> {
  if (!last.e2e) return last
  const pv = me ? await C.openEnvelope<Preview>(me.priv, convId, me.id, last.e2e, "pv") : null
  return pv ? { ...last, text: pv.t ?? "", media_kind: pv.k ?? last.media_kind, sealed: false } : { ...last, sealed: true }
}

/** "@tim" in a text: the server can't read it, so the sender names the tagged accounts. */
export function mentionNames(text: string): string[] {
  return [...new Set([...text.matchAll(/@([a-z0-9_.]{3,30})/gi)].map((x) => x[1].replace(/\.+$/, "").toLowerCase()))]
}

/** A new message for everyone in the chat who has a key (me too: my other devices). */
export async function sealMessage(convId: number, text: string, media?: { key: string; mime: string; kind: string } | null) {
  if (!me) throw new Error("locked")
  const set = await keySet(convId)
  const readers = set.keys.filter((k): k is MemberKey & { public: string } => !!k.public)
  const body: MessageBody = { t: text, m: media ? { k: media.key, mime: media.mime } : null }
  const preview: Preview = { t: text.slice(0, 180), k: media?.kind ?? null }
  return { e2e: { ...await C.encryptEnvelope(me, convId, readers, body, preview), kh: set.hash }, mentions: mentionNames(text) }
}

// ---------------------------------------------------------------- photos, voice messages

/** Encrypt a photo / voice message for upload: a new key per item (sent inside the message). */
export async function sealMedia(full: Blob, thumb?: Blob | null) {
  const key = C.random(32)
  return {
    key: C.b64(key),
    full: new Blob([await C.encryptMedia(key, new Uint8Array(await full.arrayBuffer()))]),
    thumb: thumb ? new Blob([await C.encryptMedia(key, new Uint8Array(await thumb.arrayBuffer()))]) : null,
  }
}

const opened = new Map<string, Promise<string | null>>()

/** A local (blob:) address of the decrypted picture / sound; plain media keep their address. */
export function mediaUrl(media: MediaItem, which: "url" | "thumb"): Promise<string | null> {
  const src = media[which]
  if (!media.encrypted) return Promise.resolve(src)
  if (!media.key) return Promise.resolve(null)
  const id = `${src}#${media.key}`
  if (!opened.has(id)) {
    const p = (async () => {
      try {
        const res = await fetch(src)
        if (!res.ok) return null
        const plain = await C.decryptMedia(media.key!, new Uint8Array(await res.arrayBuffer()))
        const type = which === "thumb" || media.kind === "image" ? "image/jpeg" : media.mime || "audio/mp4"
        return URL.createObjectURL(new Blob([plain], { type: which === "url" && media.mime ? media.mime : type }))
      } catch {
        return null
      }
    })()
    p.then((u) => { if (!u) opened.delete(id) })
    opened.set(id, p)
  }
  return opened.get(id)!
}

/** The address to show a medium with (null while it's being decrypted). */
export function useMediaUrl(media: MediaItem | null | undefined, which: "url" | "thumb"): string | null {
  const plain = media && !media.encrypted ? media[which] : null
  const [url, setUrl] = useState<string | null>(plain)
  useEffect(() => {
    if (!media) { setUrl(null); return }
    if (!media.encrypted) { setUrl(media[which]); return }
    let alive = true
    setUrl(null)
    void mediaUrl(media, which).then((u) => { if (alive) setUrl(u) })
    return () => { alive = false }
  }, [media?.[which], media?.key])
  return url
}
