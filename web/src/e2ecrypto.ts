// The chat encryption, as pure functions on WebCrypto (the scheme is described in server/e2e.py; the same
// in Kotlin: android/.../data/E2E.kt, in Python: server/tests/e2e_crypto.py). The app and the service
// worker (notifications) use it.

const te = new TextEncoder()
const td = new TextDecoder()
const subtle = () => globalThis.crypto.subtle
const P256: EcKeyImportParams = { name: "ECDH", namedCurve: "P-256" }
type Bytes = Uint8Array<ArrayBuffer>

export function b64(bytes: ArrayBuffer | Uint8Array): string {
  const u = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes)
  let s = ""
  for (let i = 0; i < u.length; i += 0x8000) s += String.fromCharCode(...u.subarray(i, i + 0x8000))
  return btoa(s)
}

export function unb64(s: string): Bytes {
  const bin = atob(s)
  const out = new Uint8Array(bin.length)
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i)
  return out
}

function cat(...parts: Uint8Array[]): Bytes {
  const out = new Uint8Array(parts.reduce((n, p) => n + p.length, 0))
  let at = 0
  for (const p of parts) { out.set(p, at); at += p.length }
  return out
}

export const random = (n: number): Bytes => globalThis.crypto.getRandomValues(new Uint8Array(n))

async function hkdfKey(ikm: Bytes, salt: Bytes, info: string): Promise<CryptoKey> {
  const base = await subtle().importKey("raw", ikm, "HKDF", false, ["deriveKey"])
  return subtle().deriveKey({ name: "HKDF", hash: "SHA-256", salt, info: te.encode(info) }, base,
    { name: "AES-GCM", length: 256 }, false, ["encrypt", "decrypt"])
}

const aesKey = (raw: Bytes) => subtle().importKey("raw", raw, "AES-GCM", false, ["encrypt", "decrypt"])

/** iv || AES-256-GCM(key, iv, plain, aad) */
async function seal(key: CryptoKey, plain: Bytes, aad?: string): Promise<Bytes> {
  const iv = random(12)
  const ct = await subtle().encrypt({ name: "AES-GCM", iv, ...(aad != null ? { additionalData: te.encode(aad) } : {}) }, key, plain)
  return cat(iv, new Uint8Array(ct))
}

async function unseal(key: CryptoKey, data: Bytes, aad?: string): Promise<Bytes> {
  const plain = await subtle().decrypt({ name: "AES-GCM", iv: data.slice(0, 12), ...(aad != null ? { additionalData: te.encode(aad) } : {}) },
    key, data.slice(12))
  return new Uint8Array(plain)
}

const publicKey = (value: string) => subtle().importKey("raw", unb64(value), P256, true, [])

async function ecdh(priv: CryptoKey, pub: CryptoKey): Promise<Bytes> {
  return new Uint8Array(await subtle().deriveBits({ name: "ECDH", public: pub }, priv, 256))
}

// ---------------------------------------------------------------- my key

/** A new key pair: the private key as PKCS#8 (for the backup) and the public key (raw point, base64). */
export async function newIdentity(): Promise<{ pkcs8: Bytes; pub: string }> {
  const kp = await subtle().generateKey(P256, true, ["deriveBits"]) as CryptoKeyPair
  return { pkcs8: new Uint8Array(await subtle().exportKey("pkcs8", kp.privateKey)), pub: b64(await subtle().exportKey("raw", kp.publicKey)) }
}

/** The private key for this device: it can be used, never read out again. */
export const importPrivate = (pkcs8: Bytes) => subtle().importKey("pkcs8", pkcs8, P256, false, ["deriveBits"])

/** The public key (raw point, base64) belonging to a PKCS#8 private key. */
export async function publicOf(pkcs8: Bytes): Promise<string> {
  const jwk = await subtle().exportKey("jwk", await subtle().importKey("pkcs8", pkcs8, P256, true, ["deriveBits"]))
  const pad = (s: string) => unb64(s.replace(/-/g, "+").replace(/_/g, "/") + "=".repeat((4 - (s.length % 4)) % 4))
  return b64(cat(new Uint8Array([4]), pad(jwk.x!), pad(jwk.y!)))
}

// ---------------------------------------------------------------- recovery key + backup

const B32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

/** 32 bytes as 52 letters/digits in groups of four ("ABCD EFGH ..."). */
export function formatRecovery(bytes: Uint8Array): string {
  let bits = 0, value = 0, out = ""
  for (const b of bytes) {
    value = (value << 8) | b
    bits += 8
    while (bits >= 5) { out += B32[(value >>> (bits - 5)) & 31]; bits -= 5 }
  }
  if (bits > 0) out += B32[(value << (5 - bits)) & 31]
  return out.match(/.{1,4}/g)!.join(" ")
}

/** What the player typed (spaces, dashes, small letters, 0/1/8 for O/I/B are fine) -> the 32 bytes, or null. */
export function parseRecovery(text: string): Bytes | null {
  const clean = text.toUpperCase().replace(/0/g, "O").replace(/1/g, "I").replace(/8/g, "B").replace(/[^A-Z2-7]/g, "")
  if (clean.length !== 52) return null
  const out = new Uint8Array(32)
  let bits = 0, value = 0, at = 0
  for (const ch of clean) {
    value = (value << 5) | B32.indexOf(ch)
    bits += 5
    if (bits >= 8 && at < 32) { out[at++] = (value >>> (bits - 8)) & 255; bits -= 8 }
  }
  return at === 32 ? out : null
}

const backupKey = (recovery: Bytes) => hkdfKey(recovery, new Uint8Array(32), "momentum-e2e-backup-v1")

export async function makeBackup(pkcs8: Bytes, recovery: Bytes): Promise<string> {
  return b64(await seal(await backupKey(recovery), pkcs8, "momentum-backup"))
}

/** The private key (PKCS#8) from the server's backup - null: wrong recovery key. */
export async function openBackup(backup: string, recovery: Bytes): Promise<Bytes | null> {
  try {
    return await unseal(await backupKey(recovery), unb64(backup), "momentum-backup")
  } catch {
    return null
  }
}

// ---------------------------------------------------------------- messages

/** What the server stores: one wrapped message key per reader. */
export interface Envelope { v: number; eph: string; spk: string; ct: string; pv: string; keys: Record<string, string>; kh?: string }
/** What one reader gets back: their wrapped key ("key"), the preview, the body unless it's only a preview. */
export interface ReaderEnvelope { v: number; eph: string; spk: string; pv: string; ct?: string; key?: string | null }

const wrapInfo = (conv: number, uid: number) => `momentum-wrap-v1:${conv}:${uid}`

export async function encryptEnvelope(me: { priv: CryptoKey; pub: string }, conv: number, readers: { user_id: number; public: string }[],
  body: unknown, preview: unknown): Promise<Envelope> {
  const k = random(32)
  const kk = await aesKey(k)
  const eph = await subtle().generateKey(P256, true, ["deriveBits"]) as CryptoKeyPair
  const ephRaw = new Uint8Array(await subtle().exportKey("raw", eph.publicKey))
  const keys: Record<string, string> = {}
  for (const r of readers) {
    const reader = await publicKey(r.public)
    const wk = await hkdfKey(cat(await ecdh(eph.privateKey, reader), await ecdh(me.priv, reader)), ephRaw, wrapInfo(conv, r.user_id))
    keys[String(r.user_id)] = b64(await seal(wk, k))
  }
  const aad = `momentum-msg-v1:${conv}`
  return {
    v: 1, eph: b64(ephRaw), spk: me.pub, keys,
    ct: b64(await seal(kk, te.encode(JSON.stringify(body)), aad)),
    pv: b64(await seal(kk, te.encode(JSON.stringify(preview)), aad + ":pv")),
  }
}

/** The body ("ct") or the preview ("pv") of a message for me - null: not for me / damaged / not from that key. */
export async function openEnvelope<T>(priv: CryptoKey, conv: number, uid: number, env: ReaderEnvelope, part: "ct" | "pv"): Promise<T | null> {
  try {
    const data = env[part]
    if (!env.key || !data) return null
    const ephRaw = unb64(env.eph)
    const z = cat(await ecdh(priv, await publicKey(env.eph)), await ecdh(priv, await publicKey(env.spk)))
    const k = await unseal(await hkdfKey(z, ephRaw, wrapInfo(conv, uid)), unb64(env.key))
    const plain = await unseal(await aesKey(k), unb64(data), `momentum-msg-v1:${conv}` + (part === "pv" ? ":pv" : ""))
    return JSON.parse(td.decode(plain)) as T
  } catch {
    return null
  }
}

// ---------------------------------------------------------------- media

export async function encryptMedia(key: Bytes, data: Bytes): Promise<Bytes> {
  return seal(await aesKey(key), data)
}

export async function decryptMedia(key: string, data: Bytes): Promise<Bytes> {
  return unseal(await aesKey(unb64(key)), data)
}

// ---------------------------------------------------------------- safety codes

/** 30 digits from a public key: people compare them in person (the same on both phones = nobody in between). */
export async function safetyCode(pub: string): Promise<string> {
  const h = new Uint8Array(await subtle().digest("SHA-256", unb64(pub)))
  const groups: string[] = []
  for (let i = 0; i < 6; i++) {
    let n = 0
    for (let j = 0; j < 5; j++) n = n * 256 + h[i * 5 + j]
    groups.push(String(n % 100000).padStart(5, "0"))
  }
  return groups.join(" ")
}
