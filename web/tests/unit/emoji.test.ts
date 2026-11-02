import { describe, expect, it } from "vitest"
import { insertEmoji, isEmojiIcon, rememberEmoji, recentEmoji, searchEmoji, GROUP_ICONS, type EmojiGroup } from "../../src/emoji"

const groups: EmojiGroup[] = [
  { name: "Smileys & Emotion", emoji: [{ e: "😀", n: "grinning face" }, { e: "🔥", n: "fire" }] },
  { name: "People & Body", emoji: [{ e: "👍", n: "thumbs up", v: ["👍🏻", "👍🏿"] }] },
]

describe("pixel emojis", () => {
  it("tells an emoji icon from a sprite name", () => {
    expect(isEmojiIcon("star")).toBe(false)
    expect(isEmojiIcon("🔥")).toBe(true)
    expect(isEmojiIcon("👨‍👩‍👧")).toBe(true)
  })
  it("puts the pick at the caret and moves the caret behind it", () => {
    expect(insertEmoji("ab", 1, 1, "🔥")).toEqual({ value: "a🔥b", caret: 3 })
    expect(insertEmoji("abcd", 1, 3, "😀")).toEqual({ value: "a😀d", caret: 3 })
    expect(insertEmoji("ab", 9, 9, "😀")).toEqual({ value: "ab😀", caret: 4 })
  })
  it("searches the English names", () => {
    expect(searchEmoji(groups, "fire").map((x) => x.e)).toEqual(["🔥"])
    expect(searchEmoji(groups, "FACE").map((x) => x.e)).toEqual(["😀"])
    expect(searchEmoji(groups, "")).toEqual([])
  })
  it("keeps the recent picks, newest first, without doubles", () => {
    rememberEmoji("🔥"); rememberEmoji("😀"); rememberEmoji("🔥")
    expect(recentEmoji().slice(0, 2)).toEqual(["🔥", "😀"])
  })
  it("has a tab icon for every group it offers", () => {
    for (const g of groups) expect(GROUP_ICONS[g.name]).toBeTruthy()
  })
})
