// Pixel emojis: every emoji is drawn by the generated "Pixel Emoji" font (pixelart/emoji.py), listed last in every
// font stack (theme.css) so that it only gets the emojis. This is the picker's data (sprites/emoji/emoji.json: the
// groups of Unicode's emoji-test.txt with English names and skin-tone variants) and the helpers around it.
import { local } from "./storage"

export interface EmojiEntry { e: string; n: string; v?: string[] }
export interface EmojiGroup { name: string; emoji: EmojiEntry[] }

/** The groups' tab icons (the groups of emoji-test.txt; "Component" = skin tones / hair, not offered). */
export const GROUP_ICONS: Record<string, string> = {
  "Smileys & Emotion": "😀", "People & Body": "👋", "Animals & Nature": "🐻", "Food & Drink": "🍔", "Travel & Places": "🚗",
  Activities: "⚽", Objects: "💡", Symbols: "🔣", Flags: "🏁",
}

let loading: Promise<EmojiGroup[]> | null = null

/** The emoji groups (fetched once; empty when offline and not cached - the service worker precaches it). */
export function loadEmoji(): Promise<EmojiGroup[]> {
  loading ??= fetch("/app/sprites/emoji/emoji.json").then((r) => r.json())
    .then((d: { groups: EmojiGroup[] }) => d.groups.filter((g) => g.name in GROUP_ICONS))
    .catch(() => { loading = null; return [] })
  return loading
}

/** A rule icon is either the name of a sprite (ascii) or one emoji. */
export const isEmojiIcon = (icon: string) => /[^\x00-\x7f]/.test(icon)

/** [emoji] put in for the selection [start, end) of [value]; the caret goes behind it. */
export function insertEmoji(value: string, start: number, end: number, emoji: string): { value: string; caret: number } {
  const s = Math.max(0, Math.min(start, value.length)), e = Math.max(s, Math.min(end, value.length))
  return { value: value.slice(0, s) + emoji + value.slice(e), caret: s + emoji.length }
}

/** The character before the caret taken away - a whole emoji (skin tone, ZWJ sequence, flag, keycap) at once;
 *  a selection goes as it is (the picker's delete key, report #64). */
export function deleteBefore(value: string, start: number, end: number): { value: string; caret: number } {
  const s = Math.max(0, Math.min(start, value.length)), e = Math.max(s, Math.min(end, value.length))
  if (e > s) return { value: value.slice(0, s) + value.slice(e), caret: s }
  const cut = lastCharStart(value.slice(0, s))
  return { value: value.slice(0, cut) + value.slice(s), caret: cut }
}

const isRegional = (c: number) => c >= 0x1f1e6 && c <= 0x1f1ff
const attaches = (c: number) => c === 0xfe0f || c === 0xfe0e || c === 0x20e3 || (c >= 0x1f3fb && c <= 0x1f3ff) ||
  (c >= 0xe0020 && c <= 0xe007f) || (c >= 0x300 && c <= 0x36f)

/** Where the last character of [text] starts (UTF-16 index), an emoji sequence counted as one (= Emoji.kt). */
export function lastCharStart(text: string): number {
  const cps = Array.from(text, (ch) => ch.codePointAt(0)!)
  if (!cps.length) return 0
  let i = cps.length - 1
  if (isRegional(cps[i])) {                                  // flags: pairs of regional indicators
    let n = 0
    while (i - n >= 0 && isRegional(cps[i - n])) n++
    if (n % 2 === 0) i--
  } else for (;;) {
    if (i > 0 && attaches(cps[i])) { i--; continue }        // skin tone, variation selector, keycap, tag, accent
    if (i >= 2 && cps[i - 1] === 0x200d) { i -= 2; continue } // joined by a ZWJ
    break
  }
  return cps.slice(0, i).reduce((n, c) => n + (c > 0xffff ? 2 : 1), 0)
}

/** The end of [text] for a one-line preview: "…" and its last [n] characters (code points). */
export function tail(text: string, n = 28): string {
  const chars = Array.from(text)
  return chars.length <= n ? text : "…" + chars.slice(-n).join("")
}

const RECENT_KEY = "emoji_recent", RECENT_MAX = 24

export const recentEmoji = (): string[] => local.get<string[]>(RECENT_KEY, [])

/** Remembers a picked emoji at the front of the "recent" row. */
export function rememberEmoji(e: string) {
  local.set(RECENT_KEY, [e, ...recentEmoji().filter((x) => x !== e)].slice(0, RECENT_MAX))
}

/** The entries whose English name contains [query] (all of the group when the query is empty). */
export function searchEmoji(groups: EmojiGroup[], query: string): EmojiEntry[] {
  const q = query.trim().toLowerCase()
  if (!q) return []
  return groups.flatMap((g) => g.emoji.filter((x) => x.n.includes(q) || x.e === q)).slice(0, 200)
}
