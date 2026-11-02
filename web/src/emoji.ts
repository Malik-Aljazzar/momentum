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
