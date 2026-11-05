// Videos in chats: picked like photos, a square still with ▶ and its length in the bubble, played in the big view.
// Plain chats send the file (the server re-encodes it), encrypted ones shrink (WebCodecs, if there) and seal it here.
// The encrypted one runs in Chromium: Playwright's WebKit on Windows plays no video from a blob: address (iOS does).
import { execFileSync } from "node:child_process"
import { mkdtempSync, readFileSync } from "node:fs"
import { tmpdir } from "node:os"
import { join } from "node:path"
import { chromium, devices, expect, test, type APIRequestContext, type Browser, type Page } from "@playwright/test"
import { api, newUser, openApp, tab } from "./helpers"

/** A 3 s 640x360 test video with a silent sound track (WebKit on Windows plays sound into the real headset),
 *  made with the server's ffmpeg. */
function testVideo(): Buffer {
  const out = join(mkdtempSync(join(tmpdir(), "momentum-e2e-chatvideo-")), "v.mp4")
  const python = process.platform === "win32" ? join("..", ".venv", "Scripts", "python") : "../.venv/bin/python"
  execFileSync(python, ["-c", "import imageio_ffmpeg, subprocess, sys; subprocess.run([imageio_ffmpeg.get_ffmpeg_exe(), '-y', "
    + "'-f', 'lavfi', '-i', 'testsrc=size=640x360:rate=25', '-f', 'lavfi', '-i', 'anullsrc=channel_layout=mono:sample_rate=44100', '-t', '3', "
    + "'-c:v', 'libx264', '-pix_fmt', 'yuv420p', '-c:a', 'aac', '-shortest', sys.argv[1]], check=True, capture_output=True)", out])
  return readFileSync(out)
}

async function friends(request: APIRequestContext) {
  const ann = await newUser(request, "ann")
  const ben = await newUser(request, "ben")
  await api(request, ann.token, "POST", `/users/${ben.name}/follow`)
  await api(request, ben.token, "POST", `/users/${ann.name}/follow`)
  return { ann, ben }
}

async function phone(browser: Browser) {
  const ctx = await browser.newContext({ ...devices["iPhone 13"], baseURL: test.info().project.use.baseURL })
  return ctx.newPage()
}

/** Pick the video in the chat's photo button, give it a caption, send it. */
async function sendVideo(page: Page, caption: string) {
  const chooser = page.waitForEvent("filechooser")
  await page.locator("button[aria-label=photo]").click()
  const file = await chooser
  expect(await file.element().getAttribute("accept")).toBe("image/*,video/*")
  await file.setFiles({ name: "clip.mp4", mimeType: "video/mp4", buffer: testVideo() })
  const queue = page.getByRole("dialog", { name: "photos" })
  await expect(queue.locator("video")).toHaveCount(2)                 // the big preview + the strip's frame
  await queue.getByPlaceholder("Bildunterschrift…").fill(caption)
  await queue.locator("button[aria-label='send photos']").click()
}

/** The bubble's video: a 240 px square with ▶ and its length; a tap plays it in the big view. */
async function playsInTheBigView(page: Page, blob: boolean) {
  const bubble = page.locator(".chat-video").last()
  await expect(bubble).toBeVisible({ timeout: 40_000 })
  const box = (await bubble.boundingBox())!
  expect([Math.round(box.width), Math.round(box.height)]).toEqual([240, 240])
  await expect(bubble.locator(".video-length")).toHaveText("0:03")
  await expect.poll(() => bubble.locator(".fill img").evaluate((i: HTMLImageElement) => (i.complete ? i.naturalWidth : 0)), { timeout: 20_000 }).toBeGreaterThan(0)
  await bubble.click()
  const viewer = page.getByRole("dialog", { name: "viewer" })
  const video = viewer.locator("video")
  await expect(video).toHaveCount(1, { timeout: 20_000 })
  if (blob) expect(await video.getAttribute("src")).toMatch(/^blob:/)
  await expect.poll(() => video.evaluate((v: HTMLVideoElement) => v.currentTime), { timeout: 20_000 }).toBeGreaterThan(.5)
  await viewer.locator("button[aria-label=close]").click()
}

test.setTimeout(120_000)

test("a video in a plain chat: sent with its caption, the other side plays it", async ({ page, browser, request }) => {
  const { ann, ben } = await friends(request)
  const conv = (await api(request, ann.token, "POST", "/conversations/direct", { username: ben.name })).conversation
  await openApp(page, ann.token)
  await tab(page, "chats").click()
  await page.getByText(ben.name).first().click()
  await sendVideo(page, "Gipfelsturm")
  await expect(page.locator(".video-upload")).toBeVisible()           // the progress bar while it goes up
  await expect.poll(async () => (await api(request, ben.token, "GET", `/conversations/${conv.id}/messages`)).messages.length, { timeout: 40_000 }).toBe(1)
  const msg = (await api(request, ben.token, "GET", `/conversations/${conv.id}/messages`)).messages[0]
  expect(msg.media.kind).toBe("video")
  expect(msg.text).toBe("Gipfelsturm")
  await expect(page.locator(".video-upload")).toHaveCount(0)

  const b = await phone(browser)
  await openApp(b, ben.token)
  await tab(b, "chats").click()
  await expect(b.getByText(/▶ Video|Gipfelsturm/).first()).toBeVisible()
  await b.getByText(ann.name).first().click()
  await expect(b.locator(".bubble").getByText("Gipfelsturm")).toBeVisible()
  await playsInTheBigView(b, false)
  await b.screenshot({ path: test.info().outputPath("chat-video.png") })
  await b.context().close()                 // left open it keeps polling and playing: later tests got slow (report #65's bar)
})

test("a video in an encrypted chat: shrunk and sealed on the phone, opened on the other one", async ({ request, baseURL }) => {
  const { ann, ben } = await friends(request)
  const conv = (await api(request, ann.token, "POST", "/conversations/direct", { username: ben.name })).conversation
  await api(request, ann.token, "PUT", `/conversations/${conv.id}/e2e`, { on: true })      // the test server's chats start plain
  const cr = await chromium.launch()
  test.info().annotations.push({ type: "browser", description: "chromium (WebCodecs + blob videos)" })
  const android = async () => (await cr.newContext({ ...devices["Pixel 7"], baseURL })).newPage()
  const a = await android(), b = await android()
  await openApp(a, ann.token)
  await openApp(b, ben.token)
  for (const u of [ann, ben]) await expect.poll(async () => (await api(request, u.token, "GET", "/e2e/me")).public).toBeTruthy()
  await tab(a, "chats").click()
  await a.getByText(ben.name).first().click()
  await expect(a.locator(".e2e-note")).toBeVisible()
  await sendVideo(a, "nur für dich")
  await expect.poll(async () => (await api(request, ben.token, "GET", `/conversations/${conv.id}/messages`)).messages.filter((m: any) => m.media).length, { timeout: 60_000 }).toBe(1)
  const stored = (await api(request, ben.token, "GET", `/conversations/${conv.id}/messages`)).messages.find((m: any) => m.media)
  expect(stored.media).toMatchObject({ kind: "video", encrypted: true })
  expect(stored.media.duration).toBeGreaterThan(2.5)
  expect(stored.media.thumb).not.toBe(stored.media.url)                  // the still came along
  expect(JSON.stringify(stored)).not.toContain("nur für dich")
  // the server keeps ciphertext: no MP4 header in the blob
  const blob = Buffer.from(await (await request.get(stored.media.url)).body())
  expect(blob.subarray(4, 8).toString("latin1")).not.toBe("ftyp")

  await tab(b, "chats").click()
  await expect(b.getByText(/▶ Video|nur für dich/).first()).toBeVisible({ timeout: 15_000 })     // the chat list's preview
  await b.getByText(ann.name).first().click()
  await expect(b.locator(".bubble").getByText("nur für dich")).toBeVisible()
  await playsInTheBigView(b, true)
  await cr.close()
})
