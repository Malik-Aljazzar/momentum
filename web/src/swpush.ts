// The service worker's part of encrypted chats (scripts/sw-plugin.ts bundles it into sw.js): the server
// sends only the encrypted preview of a message; it's opened here with the key on this phone.
import { openEnvelope, type ReaderEnvelope } from "./e2ecrypto"
import { loadKey } from "./e2ekeys"

/** server/push.py web_payload: the preview for this reader + what to show for a photo / voice message */
export interface PushE2E extends ReaderEnvelope { conv: number; reader: number; sender?: string; photo?: string; voice?: string }

/** The notification's text, or null (no key on this phone, not for this key): the server's fallback stays. */
export async function pushText(e: PushE2E): Promise<string | null> {
  const key = await loadKey(e.reader)
  if (!key) return null
  const pv = await openEnvelope<{ t?: string; k?: string | null }>(key.priv, e.conv, e.reader, e, "pv")
  if (!pv) return null
  const text = pv.t?.trim() || (pv.k === "audio" ? e.voice ?? "" : pv.k ? e.photo ?? "" : "")
  return (e.sender ? `${e.sender}: ` : "") + text
}
