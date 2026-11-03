// Reports #63-#68 and the upload progress bar: who reacted, the chat list at its top, story rings in the chats,
// single chat photos in their own shape, a video playing while making a post, the bar while uploading.
import { execFileSync } from "node:child_process"
import { mkdtempSync, readFileSync } from "node:fs"
import { tmpdir } from "node:os"
import { join } from "node:path"
import { expect, test, type APIRequestContext, type Page } from "@playwright/test"
import { api, longPress, newUser, openApp, tab } from "./helpers"

const PNG = Buffer.from("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==", "base64")

async function friends(request: APIRequestContext) {
  const ann = await newUser(request, "ann")
  const ben = await newUser(request, "ben")
  await api(request, ann.token, "POST", `/users/${ben.name}/follow`)
  await api(request, ben.token, "POST", `/users/${ann.name}/follow`)
  return { ann, ben }
}

/** A picture of [w] x [h] made in the page and uploaded as [token]. */
async function uploadPicture(page: Page, token: string, w: number, h: number) {
  return page.evaluate(async ({ token, w, h }) => {
    const c = document.createElement("canvas")
    c.width = w; c.height = h
    const g = c.getContext("2d")!
    g.fillStyle = "#b13e53"; g.fillRect(0, 0, w, h)
    const blob = await new Promise<Blob>((ok) => c.toBlob((b) => ok(b!), "image/png"))
    const r = await fetch("/api/media", { method: "POST", headers: { Authorization: `Bearer ${token}`, "Content-Type": "image/png" }, body: blob })
    return (await r.json()).media
  }, { token, w, h })
}

test("holding a reaction shows who reacted with what (report #63)", async ({ page, request }) => {
  const { ann, ben } = await friends(request)
  const conv = (await api(request, ann.token, "POST", "/conversations/direct", { username: ben.name })).conversation
  await api(request, ann.token, "POST", `/conversations/${conv.id}/messages`, { text: "Wer kommt mit?" })
  const mid = (await api(request, ann.token, "GET", `/conversations/${conv.id}/messages`)).messages[0].id
  await api(request, ben.token, "PUT", `/messages/${mid}/reaction`, { emoji: "🔥" })
  await openApp(page, ann.token)
  await tab(page, "chats").click()
  await page.getByText(ben.name).first().click()
  const chip = page.locator(".reactions button").first()
  await expect(chip).toHaveText("🔥")
  await longPress(page, chip)
  const sheet = page.locator(".sheet", { hasText: "Reaktionen" })
  await expect(sheet).toContainText(ben.name)
  await expect(sheet).toContainText("🔥")
  // holding didn't count as a tap: the reaction is still only ben's
  expect((await api(request, ann.token, "GET", `/conversations/${conv.id}/messages`)).messages[0].reactions)
    .toEqual([{ emoji: "🔥", count: 1, mine: false }])
})

test("a friend's new story: the ring in the chat list and the chat, a tap shows it (report #67)", async ({ page, request }) => {
  const { ann, ben } = await friends(request)
  const conv = (await api(request, ann.token, "POST", "/conversations/direct", { username: ben.name })).conversation
  await api(request, ben.token, "POST", `/conversations/${conv.id}/messages`, { text: "Schau mal" })
  const r = await request.post("/api/media", { headers: { Authorization: `Bearer ${ben.token}`, "Content-Type": "image/png" }, data: PNG })
  await api(request, ben.token, "POST", "/stories", { media_id: (await r.json()).media.id })
  await openApp(page, ann.token)
  await tab(page, "chats").click()
  const ring = page.locator(".chat-story .story-ring.new")
  await expect(ring).toBeVisible()
  // in the chat: the ring at ben's picture next to his message
  await page.getByText(ben.name).first().click()
  await expect(page.locator(".msg-avatar .story-ring.new")).toBeVisible()
  await page.locator(".msg-avatar").click()
  const viewer = page.getByRole("dialog", { name: "story" })
  await expect(viewer).toContainText(ben.name)
  await viewer.locator("button[aria-label=close]").click()
  await expect(page.locator(".msg-avatar .story-ring")).toHaveCount(0)        // seen: just the picture again
  await page.locator("button[aria-label=back]").first().click()
  await expect(ring).toHaveCount(0)
  expect((await api(request, ann.token, "GET", "/stories")).rows.find((x: any) => x.user.username === ben.name).unseen).toBe(false)
})

test("the chat list shows its top chats whenever it comes up (report #66)", async ({ page, request }) => {
  const ann = await newUser(request, "ann")
  for (let i = 0; i < 10; i++) {
    const p = await newUser(request, "p")
    await api(request, ann.token, "POST", `/users/${p.name}/follow`)
    await api(request, p.token, "POST", `/users/${ann.name}/follow`)
    const conv = (await api(request, ann.token, "POST", "/conversations/direct", { username: p.name })).conversation
    await api(request, p.token, "POST", `/conversations/${conv.id}/messages`, { text: `Hallo ${i}` })
  }
  await openApp(page, ann.token)
  await tab(page, "chats").click()
  const list = page.locator(".chat-list")
  await expect(list.locator(".list-item")).toHaveCount(10)
  await list.evaluate((el) => { el.scrollTop = el.scrollHeight })
  expect(await list.evaluate((el) => el.scrollTop)).toBeGreaterThan(0)
  await tab(page, "feed").click()
  await tab(page, "chats").click()
  await expect.poll(() => list.evaluate((el) => el.scrollTop)).toBe(0)
  await expect(list.locator(".list-item").first()).toContainText("Hallo 9")
})

test("a single chat photo keeps its shape (report #68)", async ({ page, request }) => {
  const { ann, ben } = await friends(request)
  const conv = (await api(request, ann.token, "POST", "/conversations/direct", { username: ben.name })).conversation
  await openApp(page, ann.token)
  const media = await uploadPicture(page, ben.token, 300, 600)
  await api(request, ben.token, "POST", `/conversations/${conv.id}/messages`, { media_id: media.id })
  await tab(page, "chats").click()
  await page.getByText(ben.name).first().click()
  const img = page.locator(".chat-photo img").first()
  await expect(img).toBeVisible()
  const box = (await img.boundingBox())!
  expect(Math.round(box.height)).toBe(240)
  expect(Math.round(box.width)).toBe(120)                    // portrait, not cut to a square
})

test("making a post: a video plays right away, the tile shows the upload's progress (report #65)", async ({ page, request }) => {
  const ann = await newUser(request, "ann")
  // a 1 s video made with the server's ffmpeg
  const dir = mkdtempSync(join(tmpdir(), "momentum-e2e-video-"))
  const out = join(dir, "v.mp4")
  const python = process.platform === "win32" ? join("..", ".venv", "Scripts", "python") : "../.venv/bin/python"
  execFileSync(python, ["-c", "import imageio_ffmpeg, subprocess, sys; subprocess.run([imageio_ffmpeg.get_ffmpeg_exe(), '-y', '-f', 'lavfi', "
    + "'-i', 'testsrc=size=320x240:rate=10', '-t', '1', '-pix_fmt', 'yuv420p', sys.argv[1]], check=True, capture_output=True)", out])
  await openApp(page, ann.token)
  // the server's answer waits a moment: the bar shows meanwhile
  let release = () => {}
  const held = new Promise<void>((ok) => { release = ok })
  await page.route("**/api/media", async (route) => { await held; await route.continue() })
  await tab(page, "feed").click()
  await page.locator("button[aria-label='new post']").click()
  await page.locator("button[aria-label='create post']").click()
  const chooser = page.waitForEvent("filechooser")
  await page.getByText("Galerie").click()
  await (await chooser).setFiles({ name: "v.mp4", mimeType: "video/mp4", buffer: readFileSync(out) })
  const tile = page.locator(".slide-tile").first()
  await expect(tile.locator("video")).toHaveAttribute("src", /^blob:/)            // the preview, from the file
  await expect(tile.getByTestId("upload-bar")).toBeVisible()
  await page.screenshot({ path: test.info().outputPath("upload-bar.png") })
  release()
  await expect(tile.getByTestId("upload-bar")).toBeHidden({ timeout: 30_000 })
  await expect(page.getByRole("button", { name: "Posten" })).toBeEnabled()
  await page.getByRole("button", { name: "Posten" }).click()
  await expect.poll(async () => (await api(request, ann.token, "GET", `/users/${ann.name}/posts`)).posts.length).toBe(1)
})
