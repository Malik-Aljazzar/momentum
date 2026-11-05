// User text split into plain text, web links and @mentions (= android/.../ui/Links.kt, report #58).

export type TextPart = { text: string } | { url: string; text: string } | { user: string; text: string }

const LINK = /(?:https?:\/\/|www\.)[^\s<>"]+/gi
const MENTION = /@([A-Za-z0-9_.\-]{3,40})/g
/** Punctuation right after a link belongs to the sentence ("see example.com."). */
const TRAILING = /[.,;:!?'")\]}]+$/

export function textParts(text: string): TextPart[] {
  const out: TextPart[] = []
  const plain = (t: string) => {
    let last = 0
    for (const m of t.matchAll(MENTION)) {
      if (m.index! > last) out.push({ text: t.slice(last, m.index) })
      out.push({ user: m[1], text: m[0] })
      last = m.index! + m[0].length
    }
    if (last < t.length) out.push({ text: t.slice(last) })
  }
  let last = 0
  for (const m of text.matchAll(LINK)) {
    const link = m[0].replace(TRAILING, "")
    if (!/[A-Za-z0-9]\.[A-Za-z]{2,}/.test(link)) continue   // "www." or "http://x" alone is no link
    plain(text.slice(last, m.index))
    out.push({ url: /^https?:\/\//i.test(link) ? link : "https://" + link, text: link })
    last = m.index! + link.length
  }
  plain(text.slice(last))
  return out
}

/** The preview a message shows (sent along by the sender, server/linkpreview.py): only for a link that is in its
 *  text, and only once it's opened (not sealed). */
export function shownLink<L extends { url: string }>(m: { link?: L | null; sealed?: boolean; text?: string }): L | null {
  const l = m.link
  if (!l || m.sealed || typeof l.url !== "string" || !linksIn(m.text ?? "").includes(l.url)) return null
  return l
}

/** The web links of a text, as they open (server/linkpreview.py links_in). */
export const linksIn = (text: string): string[] => textParts(text).flatMap((p) => ("url" in p ? [p.url] : []))

/** The first web link of a text: its preview goes with the message (like WhatsApp). */
export const firstLink = (text: string): string | null => linksIn(text)[0] ?? null

/** The host a link preview names - always taken from the link itself, never from the sender: "www.x.de/a" -> "x.de". */
export function linkHost(url: string): string {
  try { return new URL(url).hostname.replace(/^www\./, "") } catch { return "" }
}

/** A link preview's picture: wide ones over the text ("big"), others small beside it, none without one. */
export function linkLayout(p: { image?: string | null; width?: number; height?: number }): "big" | "small" | "none" {
  if (!p.image) return "none"
  const w = p.width ?? 0, h = p.height ?? 0
  return w >= 300 && h > 0 && w >= 1.3 * h ? "big" : "small"
}
