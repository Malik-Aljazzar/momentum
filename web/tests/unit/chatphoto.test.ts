import { describe, expect, it } from "vitest"
import { photoSize } from "../../src/chatphoto"

// the same cases as android/.../ui/ChatPhotoTest.kt
describe("a single chat photo keeps its shape (report #68)", () => {
  it("is 240 on its long side", () => {
    expect(photoSize(1080, 1440)).toEqual({ width: 180, height: 240 })      // portrait 3:4
    expect(photoSize(1600, 900)).toEqual({ width: 240, height: 135 })       // landscape 16:9
    expect(photoSize(500, 500)).toEqual({ width: 240, height: 240 })
  })
  it("cuts very long or wide ones a little, square without a size", () => {
    expect(photoSize(400, 4000)).toEqual({ width: 120, height: 240 })
    expect(photoSize(4000, 400)).toEqual({ width: 240, height: 120 })
    expect(photoSize(undefined, undefined)).toEqual({ width: 240, height: 240 })
  })
})
