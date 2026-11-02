import { describe, expect, it } from "vitest"
import { frontCameraJpeg } from "../../src/ui/media"

const jpeg = (exif: string) => new Uint8Array([0xff, 0xd8, 0xff, 0xe1, ...[...exif].map((c) => c.charCodeAt(0))])

describe("front camera photos are mirrored like the preview (report #55)", () => {
  it("knows the iPhone's front lenses", () => {
    expect(frontCameraJpeg(jpeg("Exif\0\0...iPhone 13 front camera 2.71mm f/2.2\0"))).toBe(true)
    expect(frontCameraJpeg(jpeg("Exif\0\0...iPhone 15 Pro front TrueDepth camera 2.69mm f/1.9\0"))).toBe(true)
  })
  it("leaves the back cameras and other files alone", () => {
    expect(frontCameraJpeg(jpeg("Exif\0\0...iPhone 13 back dual wide camera 5.1mm f/1.6\0"))).toBe(false)
    expect(frontCameraJpeg(new TextEncoder().encode("front camera"))).toBe(false)
  })
})
