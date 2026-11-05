// Adaptive video streams (server/hls.py): where the browser plays HLS the feed asks for the stream; a stream that
// doesn't play falls back to the MP4, which then plays (= Video.kt).
import { execFileSync } from "node:child_process"
import { mkdtempSync, readFileSync } from "node:fs"
import { tmpdir } from "node:os"
import { join } from "node:path"
import { expect, test } from "@playwright/test"
import { api, newUser, openApp, tab } from "./helpers"

function testVideo(): Buffer {
  const out = join(mkdtempSync(join(tmpdir(), "momentum-e2e-stream-")), "v.mp4")
  const python = process.platform === "win32" ? join("..", ".venv", "Scripts", "python") : "../.venv/bin/python"
  execFileSync(python, ["-c", "import imageio_ffmpeg, subprocess, sys; subprocess.run([imageio_ffmpeg.get_ffmpeg_exe(), '-y', '-f', 'lavfi', "
    + "'-i', 'testsrc=size=320x240:rate=10', '-t', '8', '-pix_fmt', 'yuv420p', sys.argv[1]], check=True, capture_output=True)", out])
  return readFileSync(out)
}

test.setTimeout(120_000)

test("a feed video asks for its stream and falls back to the file when the stream doesn't play", async ({ page, request }) => {
  const ann = await newUser(request, "ann")
  const up = await request.post("/api/media", { headers: { Authorization: `Bearer ${ann.token}`, "Content-Type": "video/mp4" }, data: testVideo() })
  expect(up.ok(), await up.text()).toBeTruthy()
  const media = (await up.json()).media
  const post = (await api(request, ann.token, "POST", "/posts", { text: "Stream", media_ids: [media.id] })).post
  expect((await (await request.post("/api/test/streams")).json()).done).toBeGreaterThanOrEqual(1)
  const hls: string = (await api(request, ann.token, "GET", `/posts/${post.id}`)).post.media[0].hls
  expect(hls).toMatch(/\/master\.m3u8$/)

  // a browser that plays HLS (like Safari on the iPhone), whose stream fails
  await page.addInitScript(() => {
    const can = HTMLMediaElement.prototype.canPlayType
    HTMLMediaElement.prototype.canPlayType = function (type: string) {
      return type === "application/vnd.apple.mpegurl" ? "maybe" : can.call(this, type)
    }
  })
  const asked: string[] = []
  await page.route("**/master.m3u8", (route) => { asked.push(route.request().url()); return route.fulfill({ status: 404 }) })
  await openApp(page, ann.token)
  await tab(page, "feed").click()
  const video = page.locator("article", { hasText: "Stream" }).locator("video")
  await expect.poll(() => asked.length, { timeout: 30_000 }).toBeGreaterThan(0)
  expect(asked[0]).toContain(hls)
  await expect(video).toHaveAttribute("src", media.url)
  await expect.poll(() => video.evaluate((v: HTMLVideoElement) => v.currentTime), { timeout: 40_000 }).toBeGreaterThan(1)
})
