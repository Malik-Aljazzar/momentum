import { describe, expect, it } from "vitest"
import type { MediaItem } from "../../src/model"
import { isCovered, reveal, revealed } from "../../src/screens/media"

const media = (over: Partial<MediaItem>): MediaItem => ({ id: 1, kind: "image", url: "/media/1/a.webp", thumb: "/media/1/a_thumb.webp", ...over })

// the same cases as android/.../ui/SensitiveTest.kt
describe("flagged media (server/moderation.py) stays covered until tapped", () => {
  it("covers only flagged media that has a cover", () => {
    revealed.value = new Set()
    expect(isCovered(media({}))).toBe(false)
    expect(isCovered(media({ sensitive: true }))).toBe(false)             // nothing to show instead
    expect(isCovered(media({ sensitive: true, cover: "/media/1/a_cover.webp" }))).toBe(true)
  })
  it("a tap opens it everywhere for this run, only that one", () => {
    revealed.value = new Set()
    const a = media({ id: 7, sensitive: true, cover: "/c7.webp" })
    const b = media({ id: 8, sensitive: true, cover: "/c8.webp" })
    reveal(a)
    expect(isCovered(a)).toBe(false)
    expect(isCovered({ ...a })).toBe(false)                                // the same media in another view
    expect(isCovered(b)).toBe(true)
  })
})
