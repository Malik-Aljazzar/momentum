// Adaptive video streams (server/hls.py): the stream where the browser plays HLS, else the MP4; once a stream fails,
// every video plays its MP4 until the app starts again (= Video.kt).
import { afterEach, describe, expect, it, vi } from "vitest"

async function load(hls: string) {
  vi.resetModules()
  vi.spyOn(HTMLMediaElement.prototype, "canPlayType").mockImplementation((t) => (t === "application/vnd.apple.mpegurl" ? hls as CanPlayTypeResult : ""))
  return await import("../../src/ui/video")
}

describe("video source", () => {
  afterEach(() => vi.restoreAllMocks())

  it("plays the stream where HLS plays (Safari)", async () => {
    const v = await load("maybe")
    expect(v.canPlayHls()).toBe(true)
    expect(v.videoSrc("/media/1/a.mp4", "/media/1/a_hls1/master.m3u8")).toBe("/media/1/a_hls1/master.m3u8")
    expect(v.videoSrc("/media/1/b.mp4")).toBe("/media/1/b.mp4")                         // no stream yet
    expect(v.videoSrc("/media/1/b.mp4", null)).toBe("/media/1/b.mp4")
  })

  it("plays the file where HLS doesn't play", async () => {
    const v = await load("")
    expect(v.canPlayHls()).toBe(false)
    expect(v.videoSrc("/media/1/a.mp4", "/media/1/a_hls1/master.m3u8")).toBe("/media/1/a.mp4")
  })

  it("a failed stream switches every video to its file (Android's Chrome: HLS yes, these HEVC streams no)", async () => {
    const v = await load("maybe")
    v.streamFailed(undefined)                                                            // a video without a stream failed
    expect(v.videoSrc("/media/1/a.mp4", "/media/1/a_hls1/master.m3u8")).toBe("/media/1/a_hls1/master.m3u8")
    v.streamFailed("/media/1/a_hls1/master.m3u8")
    expect(v.videoSrc("/media/1/a.mp4", "/media/1/a_hls1/master.m3u8")).toBe("/media/1/a.mp4")
    expect(v.videoSrc("/media/1/c.mp4", "/media/1/c_hls1/master.m3u8")).toBe("/media/1/c.mp4")
  })
})
