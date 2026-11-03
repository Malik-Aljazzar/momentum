import { describe, expect, it } from "vitest"
import { deleteBefore, insertEmoji, isEmojiIcon, rememberEmoji, recentEmoji, searchEmoji, tail, GROUP_ICONS, type EmojiGroup } from "../../src/emoji"

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
  it("takes a whole emoji back at the caret (the picker's delete key)", () => {
    const del = (text: string, caret = text.length) => { const r = deleteBefore(text, caret, caret); return [r.value, r.caret] }
    expect(del("ab")).toEqual(["a", 1])
    expect(del("hi 🔥")).toEqual(["hi ", 3])                     // a surrogate pair
    expect(del("x❤️")).toEqual(["x", 1])                         // with a variation selector
    expect(del("x👍🏽")).toEqual(["x", 1])                        // a skin tone
    expect(del("x👩🏽‍💻")).toEqual(["x", 1])                     // a ZWJ sequence with a skin tone
    expect(del("x👨‍👩‍👧")).toEqual(["x", 1])                     // a family
    expect(del("x🏳️‍🌈")).toEqual(["x", 1])                      // a flag with ZWJ
    expect(del("🇩🇪🇫🇷")).toEqual(["🇩🇪", 4])                     // flags: pairs of regional indicators
    expect(del("x1️⃣")).toEqual(["x", 1])                        // a keycap
    expect(del("a😀🔥b", 5)).toEqual(["a😀b", 3])                 // at the caret, not at the end
    expect(del("")).toEqual(["", 0])
    expect(deleteBefore("abcd", 1, 3)).toEqual({ value: "ad", caret: 1 })   // a selection
  })
  it("previews the end of the text", () => {
    expect(tail("kurz")).toBe("kurz")
    expect(tail("abc🔥🔥", 2)).toBe("…🔥🔥")
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
