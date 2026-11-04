// Content filter (server/moderation.py; the e2e server's stand-in model: cyan = refused, magenta = covered).
import { expect, test } from "@playwright/test"
import { api, newUser, openApp, tab } from "./helpers"

const CYAN = Buffer.from("iVBORw0KGgoAAAANSUhEUgAAABAAAAAQCAIAAACQkWg2AAAAGklEQVR42mNk+P+fgRTAxEAiGNUwqmHoaAAASU8CHjSYd9sAAAAASUVORK5CYII=", "base64")
const MAGENTA = Buffer.from("iVBORw0KGgoAAAANSUhEUgAAABAAAAAQCAIAAACQkWg2AAAAGklEQVR42mP8z/CfgRTAxEAiGNUwqmHoaAAASk4CHiUULOQAAAAASUVORK5CYII=", "base64")

async function friends(request: Parameters<typeof newUser>[0]) {
  const ann = await newUser(request, "ann")
  const ben = await newUser(request, "ben")
  await api(request, ann.token, "POST", `/users/${ben.name}/follow`)
  await api(request, ben.token, "POST", `/users/${ann.name}/follow`)
  return { ann, ben }
}
async function upload(request: Parameters<typeof newUser>[0], token: string, data: Buffer) {
  const r = await request.post("/api/media", { headers: { Authorization: `Bearer ${token}`, "Content-Type": "image/png" }, data })
  return (await r.json()).media
}

test("a clear case is refused when posting, with the reason", async ({ page, request }) => {
  const { ann } = await friends(request)
  await openApp(page, ann.token)
  await tab(page, "feed").click()
  await page.locator("button[aria-label='new post']").click()
  await page.locator("button[aria-label='create post']").click()
  const chooser = page.waitForEvent("filechooser")
  await page.getByText("Galerie").click()
  await (await chooser).setFiles({ name: "p.png", mimeType: "image/png", buffer: CYAN })
  await expect(page.getByRole("button", { name: "Posten" })).toBeEnabled({ timeout: 15_000 })
  await page.getByRole("button", { name: "Posten" }).click()
  await expect(page.locator(".toast")).toContainText("Nacktheit oder sexuelle Inhalte")
  await page.screenshot({ path: test.info().outputPath("refused.png") })
  expect((await api(request, ann.token, "GET", `/users/${ann.name}/posts`)).posts).toHaveLength(0)
})

test("a doubtful picture waits behind its cover in the feed and the big view until tapped", async ({ page, request }) => {
  const { ann, ben } = await friends(request)
  const md = await upload(request, ann.token, MAGENTA)
  const post = (await api(request, ann.token, "POST", "/posts", { media_ids: [md.id], text: "Strand" })).post
  expect(post.media[0].sensitive).toBe(true)
  await openApp(page, ben.token)
  await tab(page, "feed").click()
  const card = page.locator("article", { hasText: "Strand" })
  const cover = card.locator(".sensitive-cover")
  await expect(cover).toContainText("Sensibler Inhalt")
  const box = (await cover.boundingBox())!
  expect(box.width).toBeGreaterThan(300)                       // the whole slide, not a 0 px box
  expect(box.height).toBeGreaterThan(300)
  await expect(card.locator(`img[src$="${post.media[0].url}"]`)).toHaveCount(0)     // the real picture isn't loaded
  await page.screenshot({ path: test.info().outputPath("covered.png") })
  // a tap shows it (and doesn't open the big view yet); then a tap on the picture opens the big view, uncovered
  await cover.click()
  await expect(cover).toHaveCount(0)
  await expect(card.locator(`img[src$="${post.media[0].url}"]`)).toHaveCount(1)
  await expect(page.locator(".media-viewer")).toHaveCount(0)
  await card.locator("[data-pager]").click()
  await expect(page.locator(".media-viewer")).toBeVisible()
  await expect(page.locator(".media-viewer .sensitive-cover")).toHaveCount(0)
})

test("a doubtful story stands still behind its cover until 'show' is tapped", async ({ page, request }) => {
  const { ann, ben } = await friends(request)
  const md = await upload(request, ann.token, MAGENTA)
  await api(request, ann.token, "POST", "/stories", { media_id: md.id, duration: 5 })
  await openApp(page, ben.token)
  await tab(page, "feed").click()
  await page.locator(".story-bubble", { hasText: ann.name }).locator(".story-ring").click()
  const viewer = page.locator(".story-viewer")
  await expect(viewer.locator(".sensitive-cover")).toContainText("Sensibler Inhalt")
  await page.waitForTimeout(6000)                              // longer than the story: still there, still covered
  await expect(viewer.locator(".sensitive-cover")).toBeVisible()
  await page.screenshot({ path: test.info().outputPath("story-covered.png") })
  await viewer.getByRole("button", { name: "Anzeigen" }).click()
  await expect(viewer.locator(".sensitive-cover")).toHaveCount(0)
  await expect(viewer).toHaveCount(0, { timeout: 10_000 })     // now it runs its 5 s and closes
})
