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
