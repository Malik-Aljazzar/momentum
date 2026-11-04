// Feed videos like on Instagram: scrolled away and back, a video starts over; another screen over the feed
// (the comments) goes on at the same moment, and so does the feed afterwards.
import { execFileSync } from "node:child_process"
import { mkdtempSync, readFileSync } from "node:fs"
import { tmpdir } from "node:os"
import { join } from "node:path"
import { expect, test, type APIRequestContext, type Locator } from "@playwright/test"
import { api, newUser, openApp, tab } from "./helpers"

const PNG = Buffer.from("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==", "base64")

/** A 12 s test video, made with the server's ffmpeg. */
function testVideo(): Buffer {
  const out = join(mkdtempSync(join(tmpdir(), "momentum-e2e-video-")), "v.mp4")
  const python = process.platform === "win32" ? join("..", ".venv", "Scripts", "python") : "../.venv/bin/python"
  execFileSync(python, ["-c", "import imageio_ffmpeg, subprocess, sys; subprocess.run([imageio_ffmpeg.get_ffmpeg_exe(), '-y', '-f', 'lavfi', "
    + "'-i', 'testsrc=size=320x240:rate=10', '-t', '12', '-pix_fmt', 'yuv420p', sys.argv[1]], check=True, capture_output=True)", out])
  return readFileSync(out)
}

async function post(request: APIRequestContext, token: string, type: string, data: Buffer, text: string) {
  const r = await request.post("/api/media", { headers: { Authorization: `Bearer ${token}`, "Content-Type": type }, data })
  expect(r.ok(), await r.text()).toBeTruthy()
  return (await api(request, token, "POST", "/posts", { text, media_ids: [(await r.json()).media.id] })).post
}

const time = (v: Locator) => v.evaluate((el: HTMLVideoElement) => el.currentTime)

test.setTimeout(120_000)

test("feed videos: scrolled away and back they start over, the comments go on at the same moment", async ({ page, request }) => {
  const ann = await newUser(request, "ann")
  for (let i = 0; i < 5; i++) await post(request, ann.token, "image/png", PNG, `Foto ${i}`)
  await post(request, ann.token, "video/mp4", testVideo(), "Video")          // the newest: on top of the feed
  await openApp(page, ann.token)
  await tab(page, "feed").click()
  const video = page.locator("article", { hasText: "Video" }).locator("video")
  await expect.poll(() => time(video), { timeout: 40_000 }).toBeGreaterThan(2.5)

  // the feed's scroll box (marked, so it can be found again when the video isn't there)
  await video.evaluate((v) => {
    let el = v.parentElement
    while (el && !(el.scrollHeight > el.clientHeight + 10 && /auto|scroll/.test(getComputedStyle(el).overflowY))) el = el.parentElement
    el!.dataset.testScroll = "1"
  })
  const scroll = (y: number) => page.locator("[data-test-scroll]").evaluate((el, y) => { el.scrollTop = y }, y)
  await scroll(1600)
  await page.waitForTimeout(600)
  await scroll(0)
  // from the start again, and it plays
  await expect.poll(() => time(video)).toBeLessThan(1.5)
  await expect.poll(() => time(video), { timeout: 40_000 }).toBeGreaterThan(2.5)

  // the comments over the feed: the same moment there, and back in the feed
  const before = await time(video)
  await page.locator("article", { hasText: "Video" }).locator("button[aria-label=comments]").click()
  const detail = page.locator("video").last()
  await expect.poll(async () => (await page.locator("video").count()) > 1 ? await time(detail) : -1).toBeGreaterThan(before - .2)
  await expect.poll(() => time(detail), { timeout: 40_000 }).toBeGreaterThan(before + 1)
  const there = await time(detail)
  await page.locator("button[aria-label=back]").last().click()
  await expect(page.locator("video")).toHaveCount(1)
  await expect.poll(() => time(video)).toBeGreaterThan(there - .2)
})
