// Rule triggers on post slides, stories + highlights, photos in chats (collage, captions, big view).
import { expect, test } from "@playwright/test"
import { api, newUser, openApp, tab } from "./helpers"

const PNG = Buffer.from("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==", "base64")

async function friends(request: Parameters<typeof newUser>[0]) {
  const ann = await newUser(request, "ann")
  const ben = await newUser(request, "ben")
  await api(request, ann.token, "POST", `/users/${ben.name}/follow`)
  await api(request, ben.token, "POST", `/users/${ann.name}/follow`)
  return { ann, ben }
}
async function upload(request: Parameters<typeof newUser>[0], token: string) {
  const r = await request.post("/api/media", { headers: { Authorization: `Bearer ${token}`, "Content-Type": "image/png" }, data: PNG })
  return (await r.json()).media
}
/** Makes "Aufgabe erledigt" visible on the profile, so it can go with a picture. */
async function publicRule(request: Parameters<typeof newUser>[0], token: string) {
  const st = (await api(request, token, "GET", "/state")).state
  const r = st.rules.find((x: { name: string }) => x.name === "Aufgabe erledigt")
  await api(request, token, "PUT", `/rules/${r.id}`, { kind: r.kind, name: r.name, icon: r.icon, mode: r.mode, base: r.base, public: true })
  return r
}

test("a post's picture taps rules: picked with a multiplier, shown in the corner, a tap shows the rule", async ({ page, request }) => {
  const { ann, ben } = await friends(request)
  const rule = await publicRule(request, ann.token)
  await openApp(page, ann.token)
  await tab(page, "feed").click()
  await page.locator("button[aria-label='new post']").click()
  await page.locator("button[aria-label='create post']").click()
  const chooser = page.waitForEvent("filechooser")
  await page.getByText("Galerie").click()
  await (await chooser).setFiles({ name: "p.png", mimeType: "image/png", buffer: PNG })
  await page.locator(".slide-tile").first().click()
  const pick = page.locator(".rule-pick", { hasText: "Aufgabe erledigt" })
  await pick.locator("button[aria-label=more]").click()
  await pick.locator("button[aria-label=more]").click()
  await expect(pick).toContainText("×2")
  await page.screenshot({ path: test.info().outputPath("slide-sheet.png") })
  await page.locator(".sheet").getByRole("button", { name: "Fertig" }).click()
  await expect(page.locator(".slide-tile")).toContainText("⚡1")
  await page.screenshot({ path: test.info().outputPath("compose.png") })
  await expect(page.getByRole("button", { name: "Posten" })).toBeEnabled({ timeout: 15_000 })
  await page.getByRole("button", { name: "Posten" }).click()
  // x2 - but the daily limit here is 100: the second tap stops there
  await expect.poll(async () => (await api(request, ann.token, "GET", "/state")).state.momentum).toBe(100)
  const post = (await api(request, ben.token, "GET", "/feed")).posts[0]
  expect(post.triggers).toMatchObject([{ slide: 0, rule_id: rule.id, times: 2 }])
  // ben: the pill in the corner, a tap shows the rule like on ann's profile
  await page.evaluate(() => localStorage.clear())
  await openApp(page, ben.token)
  await tab(page, "feed").click()
  const badge = page.locator("article .trigger-badges button").first()
  await expect(badge).toContainText("×2")
  await page.screenshot({ path: test.info().outputPath("feed-badge.png") })
  await badge.click()
  await expect(page.locator(".sheet")).toContainText("Aufgabe erledigt")
})

test("stories: at the top of the feed with a ring, seen after viewing; new from the profile picture, kept in a highlight", async ({ page, request }) => {
  const { ann, ben } = await friends(request)
  const media = await upload(request, ann.token)
  await api(request, ann.token, "POST", "/stories", { media_id: media.id })
  await openApp(page, ben.token)
  await tab(page, "feed").click()
  const bubble = page.locator(".story-bubble", { hasText: ann.name })
  await expect(bubble.locator(".story-ring.new")).toBeVisible()
  await page.screenshot({ path: test.info().outputPath("stories-row.png") })
  await bubble.locator(".story-ring").click()
  const viewer = page.getByRole("dialog", { name: "story" })
  await expect(viewer).toContainText(ann.name)
  await viewer.locator("button[aria-label=close]").click()
  await expect(bubble.locator(".story-ring.seen")).toBeVisible()
  expect((await api(request, ben.token, "GET", "/stories")).rows[0].unseen).toBe(false)

  // ann: a new story from the profile picture, then into a highlight
  await page.evaluate(() => localStorage.clear())
  await openApp(page, ann.token)
  await tab(page, "profile").click()
  await page.locator(".profile-head button[aria-label='new story']").click()
  const chooser = page.waitForEvent("filechooser")
  await page.getByText("Galerie").click()
  await (await chooser).setFiles({ name: "s.png", mimeType: "image/png", buffer: PNG })
  await expect(page.getByRole("button", { name: "Teilen" })).toBeEnabled({ timeout: 15_000 })
  await page.getByRole("button", { name: "Teilen" }).click()
  await expect.poll(async () => (await api(request, ann.token, "GET", `/users/${ann.name}/stories`)).stories.length).toBe(2)
  await page.locator(".highlights-row").getByText("Neu").click()
  await page.getByPlaceholder("Name des Highlights").fill("Läufe")
  await page.locator(".sheet button[aria-label^='story']").first().click()
  await page.locator(".sheet").getByRole("button", { name: "Fertig" }).click()
  await expect(page.locator(".highlights-row")).toContainText("Läufe")
  await page.screenshot({ path: test.info().outputPath("highlights.png") })
  const hls = (await api(request, ben.token, "GET", `/users/${ann.name}/highlights`)).highlights
  expect(hls[0]).toMatchObject({ title: "Läufe" })
  expect(hls[0].stories).toHaveLength(1)
})

test("the poster sees who saw a story", async ({ page, request }) => {
  const { ann, ben } = await friends(request)
  const media = await upload(request, ann.token)
  const st = (await api(request, ann.token, "POST", "/stories", { media_id: media.id })).story
  await api(request, ben.token, "POST", `/stories/${st.id}/seen`)
  await openApp(page, ann.token)
  await tab(page, "profile").click()
  await page.locator(".profile-head .story-ring").click()
  const viewer = page.getByRole("dialog", { name: "story" })
  await expect(viewer.locator("button[aria-label=viewers]")).toContainText("1")
  await viewer.locator("button[aria-label=viewers]").click()
  await expect(page.locator(".sheet")).toContainText("Gesehen von")
  await expect(page.locator(".sheet")).toContainText(ben.name)
  await page.screenshot({ path: test.info().outputPath("story-viewers.png") })
})

test("chat photos: several at once with captions, one collage, the big view slides; a single one shows its caption", async ({ page, request }) => {
  const { ann, ben } = await friends(request)
  const conv = (await api(request, ann.token, "POST", "/conversations/direct", { username: ben.name })).conversation
  await openApp(page, ann.token)
  await tab(page, "chats").click()
  await page.getByText(ben.name).first().click()
  const chooser = page.waitForEvent("filechooser")
  await page.locator("button[aria-label=photo]").click()
  await (await chooser).setFiles([1, 2, 3, 4, 5].map((i) => ({ name: `p${i}.png`, mimeType: "image/png", buffer: PNG })))
  const queue = page.getByRole("dialog", { name: "photos" })
  await expect(queue).toContainText("1 / 5")
  await queue.getByPlaceholder("Bildunterschrift…").fill("Gipfel")
  await queue.locator("button[aria-label='send photos']").click()
  await expect.poll(async () => (await api(request, ben.token, "GET", `/conversations/${conv.id}/messages`)).messages.length, { timeout: 20_000 }).toBe(5)
  const msgs = (await api(request, ben.token, "GET", `/conversations/${conv.id}/messages`)).messages
  expect(msgs[0].text).toBe("Gipfel")
  // one collage: 4 visible, "+1" on the last
  const collage = page.locator(".collage")
  await expect(collage).toHaveCount(1)
  await expect(collage.locator(".chat-photo")).toHaveCount(4)
  await expect(collage).toContainText("+1")
  await page.screenshot({ path: test.info().outputPath("collage.png") })
  await collage.locator(".chat-photo").first().click()
  const viewer = page.getByRole("dialog", { name: "viewer" })
  await expect(viewer).toContainText("1 / 5")
  await expect(viewer).toContainText("Gipfel")
  await viewer.locator("button[aria-label=close]").click()
  // a text in between ends the group; a single photo shows its caption under it
  await api(request, ann.token, "POST", `/conversations/${conv.id}/messages`, { text: "und dann" })
  const one = await upload(request, ann.token)
  await api(request, ann.token, "POST", `/conversations/${conv.id}/messages`, { media_id: one.id, text: "Abstieg" })
  await expect(page.locator(".route").last().getByText("Abstieg", { exact: true })).toBeVisible({ timeout: 20_000 })  // arrives by polling
  await expect(page.locator(".collage")).toHaveCount(1)
})

test("the feed's + asks first: post or story", async ({ page, request }) => {
  const { ann } = await friends(request)
  await openApp(page, ann.token)
  await tab(page, "feed").click()
  await page.locator("button[aria-label='new post']").click()
  await expect(page.getByText("Was möchtest du teilen?")).toBeVisible()
  await page.locator("button[aria-label='create story']").click()
  await expect(page.locator(".route .topbar").last()).toContainText(/Neue Story/i)
  await expect(page.getByRole("button", { name: "Teilen" })).toBeVisible()
})

test("camera: asks photo or video, then opens the camera for that one type (Chrome ignores capture with both)", async ({ page, request }) => {
  const { ann } = await friends(request)
  await openApp(page, ann.token)
  await tab(page, "feed").click()
  await page.locator("button[aria-label='new post']").click()
  await page.locator("button[aria-label='create story']").click()
  await page.getByText("Kamera").click()
  const chooser = page.waitForEvent("filechooser")
  await page.getByRole("button", { name: "Video aufnehmen" }).click()
  const input = (await chooser).element()
  expect(await input.getAttribute("accept")).toBe("video/*")
  expect(await input.getAttribute("capture")).toBe("environment")
})

test("stories: quick reaction and answer go to the author's chat, shared into a chat it opens again (report #33)", async ({ page, request }) => {
  const { ann, ben } = await friends(request)
  const media = await upload(request, ann.token)
  await api(request, ann.token, "POST", "/stories", { media_id: media.id })
  await openApp(page, ben.token)
  await tab(page, "feed").click()
  await page.locator(".story-bubble", { hasText: ann.name }).locator(".story-ring").click()
  const viewer = page.getByRole("dialog", { name: "story" })
  await viewer.locator("button[aria-label='react 🔥']").click()
  await expect(page.getByText("Gesendet")).toBeVisible()
  await viewer.locator(".story-reply input").fill("Stark!")
  await page.screenshot({ path: test.info().outputPath("story-reply.png") })
  await viewer.locator("button[aria-label='send reply']").click()
  const convs = (await api(request, ann.token, "GET", "/conversations")).conversations
  // the answer opens the author's chat first, then sends: wait for it
  await expect.poll(async () => (await api(request, ann.token, "GET", `/conversations/${convs[0].id}/messages`)).messages.length).toBe(2)
  const msgs = (await api(request, ann.token, "GET", `/conversations/${convs[0].id}/messages`)).messages
  expect(msgs.map((m: { text: string }) => m.text)).toEqual(["🔥", "Stark!"])
  expect(msgs[1].share).toMatchObject({ kind: "story", available: true })
  // share it into the chat with ann
  await viewer.locator("button[aria-label='share story']").click()
  await page.locator(".sheet .row").filter({ hasText: ann.name }).first().click()
  await expect.poll(async () => (await api(request, ann.token, "GET", `/conversations/${convs[0].id}/messages`)).messages.length).toBe(3)

  // ann: the card in the chat opens the story
  await page.evaluate(() => localStorage.clear())
  await openApp(page, ann.token)
  await tab(page, "chats").click()
  await page.getByText(ben.name).first().click()
  const card = page.locator(".story-share").last()
  await expect(card).toContainText("Story ansehen")
  await page.screenshot({ path: test.info().outputPath("story-card.png") })
  await card.click()
  await expect(page.getByRole("dialog", { name: "story" })).toBeVisible()
})

test("chat: who is typing / recording shows up, a pasted picture goes into the photo queue (reports #31, #32)", async ({ page, request }) => {
  const { ann, ben } = await friends(request)
  const conv = (await api(request, ann.token, "POST", "/conversations/direct", { username: ben.name })).conversation
  await openApp(page, ann.token)
  await tab(page, "chats").click()
  await page.getByText(ben.name).first().click()
  await api(request, ben.token, "POST", `/conversations/${conv.id}/typing`, { kind: "text" })
  await expect(page.locator(".typing-line")).toHaveText(`${ben.name} schreibt…`, { timeout: 10_000 })
  await api(request, ben.token, "POST", `/conversations/${conv.id}/typing`, { kind: "audio" })
  await expect(page.locator(".typing-line")).toContainText("Sprachnachricht", { timeout: 10_000 })
  await page.screenshot({ path: test.info().outputPath("typing.png") })
  // typing here tells ben
  await page.locator("input.field").last().fill("Hallo")
  await expect.poll(async () => (await api(request, ben.token, "GET", `/conversations/${conv.id}/messages`)).typing).toEqual([{ username: ann.name, kind: "text" }])
  // paste a screenshot
  await page.evaluate((b64) => {
    const bytes = Uint8Array.from(atob(b64), (ch) => ch.charCodeAt(0))
    const dt = new DataTransfer()
    dt.items.add(new File([bytes], "shot.png", { type: "image/png" }))
    document.dispatchEvent(new ClipboardEvent("paste", { clipboardData: dt, bubbles: true }))
  }, PNG.toString("base64"))
  await expect(page.getByRole("dialog", { name: "photos" })).toBeVisible()
})
