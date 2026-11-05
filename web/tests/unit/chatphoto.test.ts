import { describe, expect, it } from "vitest"
import { CHAT_MEDIA, videoLength } from "../../src/chatphoto"
import { isVideoFile } from "../../src/chatvideo"
import type { ChatMessage } from "../../src/model"
import { photoGroups, previewOf } from "../../src/screens/Chat"
import { S } from "../../src/strings"

// the same cases as android/.../ui/ChatPhotoTest.kt
describe("chat photos and videos", () => {
  it("are one square, whatever their shape", () => {
    expect(CHAT_MEDIA).toBe(240)
  })
  it("a video shows its length as m:ss", () => {
    expect(videoLength(0)).toBe("")
    expect(videoLength(undefined)).toBe("")
    expect(videoLength(7.2)).toBe("0:07")
    expect(videoLength(59.6)).toBe("1:00")
    expect(videoLength(605)).toBe("10:05")
  })
})

describe("chat videos", () => {
  const msg = (id: number, kind: string | null, mine = true) =>
    ({ id, text: "", at: "2026-11-05T10:00:00Z", mine, user: { username: "ann" }, reactions: [], media: kind ? { id, kind, url: "", thumb: "" } : null }) as unknown as ChatMessage
  it("are their own bubble: photos around them don't make one collage with them", () => {
    const groups = photoGroups([msg(1, "image"), msg(2, "image"), msg(3, "video"), msg(4, "image")])
    expect(groups.map((g) => g.photos.map((p) => p.id))).toEqual([[1, 2], [], [4]])
    expect(groups[1].m.id).toBe(3)
  })
  it("say what they are in the chat list and quotes", () => {
    const s = S.value
    expect(previewOf(s, "", "video")).toBe(`▶ ${s.videoLabel}`)
    expect(previewOf(s, "Gipfel", "video")).toBe("Gipfel")
    expect(previewOf(s, "", "image")).toBe(`▣ ${s.photo}`)
  })
  it("a picked file is a video by its type, or its name when the type is missing", () => {
    expect(isVideoFile(new File([], "a.mp4", { type: "video/mp4" }))).toBe(true)
    expect(isVideoFile(new File([], "IMG_1.MOV"))).toBe(true)
    expect(isVideoFile(new File([], "a.jpg", { type: "image/jpeg" }))).toBe(false)
  })
})
