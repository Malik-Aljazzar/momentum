// Story length, the part of a sound, rules in the feed, rankings of the last 24 hours.
import { expect, test } from "@playwright/test"
import { api, newUser, openApp, tab } from "./helpers"

const PNG = Buffer.from("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==", "base64")

/** A silent mono WAV of the given length (a sound file from the phone). */
function wav(seconds: number): Buffer {
  const rate = 8000, n = rate * seconds
  const b = Buffer.alloc(44 + n * 2)
  b.write("RIFF", 0); b.writeUInt32LE(36 + n * 2, 4); b.write("WAVE", 8); b.write("fmt ", 12)
  b.writeUInt32LE(16, 16); b.writeUInt16LE(1, 20); b.writeUInt16LE(1, 22); b.writeUInt32LE(rate, 24)
  b.writeUInt32LE(rate * 2, 28); b.writeUInt16LE(2, 32); b.writeUInt16LE(16, 34); b.write("data", 36); b.writeUInt32LE(n * 2, 40)
  return b
}

/** Playwright's WebKit on Windows can't decode audio at all (an iPhone can): a picked sound reports 40 s. */
async function fakeAudioLength(page: import("@playwright/test").Page) {
  await page.addInitScript(() => {
    const proto = HTMLMediaElement.prototype
    Object.defineProperty(proto, "duration", { configurable: true, get() { return 40 } })
    Object.defineProperty(proto, "onerror", { configurable: true, get() { return null }, set() {} })
    const src = Object.getOwnPropertyDescriptor(proto, "src")!
    Object.defineProperty(proto, "src", {
      configurable: true, get() { return src.get!.call(this) },
      set(v) { src.set!.call(this, v); setTimeout(() => (this as HTMLMediaElement).onloadedmetadata?.(new Event("loadedmetadata")), 30) },
    })
  })
}

async function friends(request: Parameters<typeof newUser>[0]) {
  const ann = await newUser(request, "ann")
  const ben = await newUser(request, "ben")
  await api(request, ann.token, "POST", `/users/${ben.name}/follow`)
  await api(request, ben.token, "POST", `/users/${ann.name}/follow`)
  return { ann, ben }
}

test("a photo story runs 5, 10, 15 or 30 s; its sound part is as long and slides (report #39)", async ({ page, request }) => {
  const ann = await newUser(request, "ann")
  await fakeAudioLength(page)
  await openApp(page, ann.token)
  await tab(page, "feed").click()
  await page.locator("button[aria-label='new post']").click()
  await page.locator("button[aria-label='create story']").click()
  const chooser = page.waitForEvent("filechooser")
  await page.getByText("Galerie").click()
  await (await chooser).setFiles({ name: "s.png", mimeType: "image/png", buffer: PNG })
  await page.locator(".story-length button", { hasText: "15 s" }).click()
  // an own sound of 40 s: the part is 15 s long
  await page.getByText("Sound hinzufügen").click()
  const audio = page.waitForEvent("filechooser")
  await page.getByText("Audiodatei wählen").click()
  await (await audio).setFiles({ name: "beat.wav", mimeType: "audio/wav", buffer: wav(40) })
  const editor = page.locator(".sound-editor")
  await expect(editor).toContainText("0:00 – 0:15")
  const bar = editor.locator(".sound-range")
  const box = (await bar.boundingBox())!
  await page.mouse.move(box.x + box.width * .1, box.y + box.height / 2)
  await page.mouse.down()
  await page.mouse.move(box.x + box.width * .6, box.y + box.height / 2, { steps: 8 })
  await page.mouse.up()
  await expect(editor).not.toContainText("0:00 – 0:15")
  await expect(editor).toContainText("· 15 s")
  await page.screenshot({ path: test.info().outputPath("story-compose.png") })
  await expect(page.getByRole("button", { name: "Teilen" })).toBeEnabled({ timeout: 15_000 })
  await page.getByRole("button", { name: "Teilen" }).click()
  await expect.poll(async () => (await api(request, ann.token, "GET", `/users/${ann.name}/stories`)).stories?.length ?? 0).toBe(1)
  const st = (await api(request, ann.token, "GET", `/users/${ann.name}/stories`)).stories[0]
  expect(st.duration).toBe(15)
  expect(st.sound.duration).toBeGreaterThan(14)
  expect(st.sound.duration).toBeLessThan(16)
})

test("a post's sound: drag both ends of the part (5-30 s)", async ({ page, request }) => {
  const ann = await newUser(request, "ann")
  await fakeAudioLength(page)
  await openApp(page, ann.token)
  await tab(page, "feed").click()
  await page.locator("button[aria-label='new post']").click()
  await page.locator("button[aria-label='create post']").click()
  await page.locator("textarea").fill("Mit Sound")
  await page.getByText("Sound hinzufügen").click()
  const audio = page.waitForEvent("filechooser")
  await page.getByText("Audiodatei wählen").click()
  await (await audio).setFiles({ name: "beat.wav", mimeType: "audio/wav", buffer: wav(40) })
  const editor = page.locator(".sound-editor")
  await expect(editor).toContainText("0:00 – 0:30")
  // the left end to the middle: 0:20 - 0:30
  const box = (await editor.locator(".sound-range").boundingBox())!
  await page.mouse.move(box.x + 2, box.y + box.height / 2)
  await page.mouse.down()
  await page.mouse.move(box.x + box.width * .5, box.y + box.height / 2, { steps: 8 })
  await page.mouse.up()
  await expect(editor).toContainText("0:20 – 0:30")
  await expect(editor).toContainText("· 10 s")
  await page.getByRole("button", { name: "Posten" }).click()
  await expect.poll(async () => (await api(request, ann.token, "GET", "/feed")).posts.length).toBe(1)
  const sound = (await api(request, ann.token, "GET", "/feed")).posts[0].sound
  expect(sound.duration).toBeGreaterThan(9)
  expect(sound.duration).toBeLessThan(11)
})

test("a rule made public shows up in the followers' feed; a tap opens it", async ({ page, request }) => {
  const { ann, ben } = await friends(request)
  const r = (await api(request, ann.token, "GET", "/state")).state.rules.find((x: { kind: string; mode: string }) => x.kind === "acc" && x.mode === "constant")
  await api(request, ann.token, "PUT", `/rules/${r.id}`, { kind: r.kind, name: r.name, icon: r.icon, mode: r.mode, base: r.base, public: true })
  await openApp(page, ben.token)
  await tab(page, "feed").click()
  const tile = page.locator(".feed-rule").first()
  await expect(tile).toContainText(r.name)
  await expect(page.locator("article").first()).toContainText("hat eine neue Regel geteilt")
  await page.screenshot({ path: test.info().outputPath("feed-rule.png") })
  await tile.click()
  await expect(page.locator(".sheet")).toContainText(r.name)
})

test("leaderboard: total or today with +/- (reports #40, #45)", async ({ page, request }) => {
  const { ann, ben } = await friends(request)
  const r = (await api(request, ben.token, "GET", "/state")).state.rules.find((x: { kind: string }) => x.kind === "acc")
  await api(request, ben.token, "POST", `/rules/${r.id}/trigger`)
  await openApp(page, ann.token)
  await tab(page, "feed").click()
  await page.locator("button[aria-label='leaderboard']").click()
  await page.getByRole("button", { name: "Freunde" }).click()
  await page.getByRole("button", { name: "Heute" }).click()
  const first = page.locator(".list-item").first()
  await expect(first).toContainText(ben.name)
  await expect(first.locator(".rank-delta")).toContainText("+")
  await page.screenshot({ path: test.info().outputPath("leaderboard-24h.png") })
})


test("composer: camera and gallery side by side over the whole width, then a narrow column (report #41)", async ({ page, request }) => {
  const ann = await newUser(request, "ann")
  await openApp(page, ann.token)
  await tab(page, "feed").click()
  await page.locator("button[aria-label='new post']").click()
  await page.locator("button[aria-label='create post']").click()
  const tiles = page.locator(".add-row .add-tile")
  await expect(tiles).toHaveCount(2)
  const [a, b] = [(await tiles.nth(0).boundingBox())!, (await tiles.nth(1).boundingBox())!]
  const width = page.viewportSize()!.width
  expect(Math.abs(a.y - b.y)).toBeLessThan(2)                              // one row
  expect(a.width + b.width).toBeGreaterThan(width * .8)                    // together the whole width
  await page.screenshot({ path: test.info().outputPath("composer-empty.png") })
  const chooser = page.waitForEvent("filechooser")
  await tiles.nth(1).click()
  await (await chooser).setFiles({ name: "p.png", mimeType: "image/png", buffer: PNG })
  await expect(page.locator(".slide-tile")).toHaveCount(1)
  const small = (await page.locator(".add-tile").first().boundingBox())!
  expect(small.width).toBeLessThan(90)                                     // a narrow column next to the picture
  await page.screenshot({ path: test.info().outputPath("composer-picked.png") })
})

test("my message: who read it (report #44)", async ({ page, request }) => {
  const { ann, ben } = await friends(request)
  const conv = (await api(request, ann.token, "POST", "/conversations/direct", { username: ben.name })).conversation
  await api(request, ann.token, "POST", `/conversations/${conv.id}/messages`, { text: "Gelesen?" })
  await api(request, ben.token, "GET", `/conversations/${conv.id}/messages`)
  await openApp(page, ann.token)
  await tab(page, "chats").click()
  await page.getByText(ben.name).first().click()
  const bubble = page.locator(".bubble", { hasText: "Gelesen?" })
  const box = (await bubble.boundingBox())!
  await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2)
  await page.mouse.down()
  await page.waitForTimeout(700)
  await page.mouse.up()
  await page.locator("button[aria-label='read by']").click()
  await expect(page.locator(".sheet .readers")).toContainText(ben.name)
})

test("limit info: where tomorrow's limit is heading (report #46)", async ({ page, request }) => {
  const ann = await newUser(request, "ann")
  await openApp(page, ann.token)
  await tab(page, "rules").click()
  await page.getByText("?", { exact: true }).first().click()
  await expect(page.locator(".limit-forecast")).toContainText("morgen voraussichtlich")
})

test("chat: time on each message, day separator, countdown under a photo (reports #48, #49)", async ({ page, request }) => {
  const { ann, ben } = await friends(request)
  const conv = (await api(request, ann.token, "POST", "/conversations/direct", { username: ben.name })).conversation
  await api(request, ann.token, "POST", `/conversations/${conv.id}/messages`, { text: "Hallo Ben" })
  const res = await request.post("/api/media", { headers: { Authorization: `Bearer ${ann.token}`, "Content-Type": "image/png" }, data: PNG })
  const media = (await res.json()).media
  await api(request, ann.token, "POST", `/conversations/${conv.id}/messages`, { media_id: media.id })
  await openApp(page, ben.token)
  await tab(page, "chats").click()
  await page.getByText(ann.name).first().click()
  await expect(page.locator(".day-sep")).toHaveText(["Heute"])
  // text and photo in the same minute: picture and time only once, at the last one
  await expect(page.locator(".bubble", { hasText: "Hallo Ben" }).locator(".msg-time")).toHaveCount(0)
  await expect(page.locator(".bubble .msg-time")).toHaveCount(1)
  await expect(page.locator(".bubble .msg-time")).toHaveText(/^\d\d:\d\d$/)
  await expect(page.locator(".msg-avatar")).toHaveCount(1)
  await expect(page.locator(".photo-expiry")).toContainText(/Gelöscht in 1[34] T \d\d:\d\d/)
  await page.screenshot({ path: test.info().outputPath("chat-times.png") })
})

test("home card: number, badge and vehicle lie above the banner (report #47)", async ({ page, request }) => {
  const ann = await newUser(request, "ann")
  await openApp(page, ann.token)
  const badge = page.getByText("Wettbewerb", { exact: true }).first()
  await expect(badge).toBeVisible()
  const box = (await badge.boundingBox())!
  const top = await page.evaluate(([x, y]) => document.elementFromPoint(x, y)?.tagName, [box.x + box.width / 2, box.y + box.height / 2])
  expect(top).not.toBe("CANVAS")
})

test("no browser menu on a long press (report #50)", async ({ page, request }) => {
  const ann = await newUser(request, "ann")
  await openApp(page, ann.token)
  const prevented = await page.evaluate(() => {
    const e = new MouseEvent("contextmenu", { bubbles: true, cancelable: true })
    document.querySelector("canvas")!.dispatchEvent(e)
    return e.defaultPrevented
  })
  expect(prevented).toBe(true)
})
