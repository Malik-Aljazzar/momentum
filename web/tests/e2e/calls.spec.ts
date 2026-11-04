// Calls end to end (server/calls.py, src/calls.ts): two players, each in their own browser, really talk - Chromium's
// fake camera and microphone, WebRTC between the two pages through the test server's signalling.
import { expect, test, type Browser, type Page } from "@playwright/test"
import { api, NEWEST_RELEASE, newUser } from "./helpers"

async function phone(browser: Browser, token: string, open: string): Promise<Page> {
  const ctx = await browser.newContext({ permissions: ["microphone", "camera"] })
  const page = await ctx.newPage()
  await page.addInitScript(([t, seen]) => {
    localStorage.setItem("token", JSON.stringify(t))
    localStorage.setItem("language", "de")
    localStorage.setItem("notify_asked", "1")
    if (localStorage.getItem("changelog_seen") == null) localStorage.setItem("changelog_seen", JSON.stringify(seen))
  }, [token, NEWEST_RELEASE])
  await page.goto(`/app/?open=${open}`)
  await expect(page.locator("nav.tabbar")).toBeVisible()
  return page
}

const screen = (page: Page) => page.getByRole("dialog", { name: "call" })

/** The others' sound arrives (its track is live and gets packets). */
async function hears(page: Page) {
  await expect.poll(() => page.evaluate(() => [...document.querySelectorAll<HTMLAudioElement>("audio.call-sound")]
    .some((a) => (a.srcObject as MediaStream | null)?.getAudioTracks().some((t) => t.readyState === "live" && !t.muted))),
  { timeout: 20_000 }).toBe(true)
}

test("a voice call: ring, answer, talk, camera on, minimize, hang up, the chat line", async ({ browser, request }) => {
  const ann = await newUser(request, "ca"), ben = await newUser(request, "cb")
  await api(request, ann.token, "POST", `/users/${ben.name}/follow`)
  await api(request, ben.token, "POST", `/users/${ann.name}/follow`)
  const conv = (await api(request, ann.token, "POST", "/conversations/direct", { username: ben.name })).conversation
  const a = await phone(browser, ann.token, `chat:${conv.id}`)
  const b = await phone(browser, ben.token, "chats")
  await a.getByRole("button", { name: "Sprachanruf", exact: true }).click()
  await expect(screen(a)).toContainText(/Klingelt|Ruft an/)
  // ben's app rings (over whatever he looks at)
  await expect(screen(b)).toContainText("Eingehender Sprachanruf")
  await expect(screen(b)).toContainText(ann.name)
  await b.getByRole("button", { name: "Annehmen" }).click()
  // connected: the time runs on both sides, and the sound comes through
  for (const p of [a, b]) await expect(p.locator(".call-status")).toHaveText(/^\d+:\d\d$/, { timeout: 20_000 })
  await hears(a)
  await hears(b)

  // ben switches his camera on: ann sees his picture full screen, ben sees himself in the corner
  await b.getByRole("button", { name: "Kamera" }).click()
  await expect.poll(() => a.evaluate(() => [...document.querySelectorAll<HTMLVideoElement>(".call-screen video")]
    .some((v) => v.videoWidth > 0)), { timeout: 20_000 }).toBe(true)
  await expect(b.locator(".call-self video")).toBeVisible()
  // muted: ann sees it
  await b.getByRole("button", { name: "Stumm" }).click()
  await expect(a.locator('.call-who img[src*="ui_micoff"], .call-who [style*="ui_micoff"]')).toHaveCount(1)

  // ann minimizes: the call goes on in the small window, a tap brings it back
  await a.getByRole("button", { name: "Verkleinern" }).click()
  await expect(screen(a)).toHaveCount(0)
  const bubble = a.getByRole("button", { name: "Tippen, um zum Anruf zurückzukehren" })
  await expect(bubble).toBeVisible()
  const box = (await bubble.boundingBox())!
  expect(box.width).toBeGreaterThan(80)
  await bubble.click()
  await expect(screen(a)).toBeVisible()

  await a.waitForTimeout(1200)
  await b.getByRole("button", { name: "Auflegen" }).click()
  await expect(screen(a)).toContainText("Anruf beendet")
  await expect(screen(a)).toHaveCount(0, { timeout: 5000 })
  // the chat tells it: ann called (outgoing), with its length
  await expect(a.locator(".call-line")).toContainText(/Ausgehender Sprachanruf · \d+:\d\d/)
  // nothing keeps the microphone or camera open
  expect(await b.evaluate(() => document.querySelectorAll("audio.call-sound").length)).toBe(0)
})

test("declined, and a group call joined from the chat's bar", async ({ browser, request }) => {
  const ann = await newUser(request, "ga"), ben = await newUser(request, "gb"), cat = await newUser(request, "gc")
  for (const [x, y] of [[ann, ben], [ben, ann], [ann, cat], [cat, ann]]) await api(request, x.token, "POST", `/users/${y.name}/follow`)
  const direct = (await api(request, ann.token, "POST", "/conversations/direct", { username: ben.name })).conversation
  const a = await phone(browser, ann.token, `chat:${direct.id}`)
  const b = await phone(browser, ben.token, "chats")
  await a.getByRole("button", { name: "Videoanruf", exact: true }).click()
  await expect(screen(b)).toContainText("Eingehender Videoanruf")
  await b.getByRole("button", { name: "Ablehnen" }).click()
  await expect(screen(a)).toContainText("Abgelehnt")
  await expect(screen(a)).toHaveCount(0, { timeout: 5000 })
  await expect(a.locator(".call-line").last()).toContainText("Videoanruf · abgelehnt")
  await expect(b.locator(".call-line")).toHaveCount(0)              // ben isn't in the chat screen

  // a group: cat muted it (no ringing), sees the call in the chat and joins
  const group = (await api(request, ann.token, "POST", "/conversations/group", { name: `Call${Date.now() % 100000}`, usernames: [ben.name, cat.name] })).conversation
  await api(request, cat.token, "PUT", `/conversations/${group.id}/mute`, { muted: true })
  const c = await phone(browser, cat.token, `chat:${group.id}`)
  await a.goto(`/app/?open=chat:${group.id}`)
  await a.getByRole("button", { name: "Sprachanruf", exact: true }).click()
  await expect(screen(b)).toContainText("Eingehender Sprachanruf")
  await expect(screen(c)).toHaveCount(0)
  await expect(c.locator(".call-bar")).toContainText("Anruf läuft · 1 dabei", { timeout: 15_000 })
  await c.getByRole("button", { name: "Beitreten" }).click()
  for (const p of [a, c]) await expect(p.locator(".call-status")).toHaveText(/^\d+:\d\d$/, { timeout: 20_000 })
  await hears(a)
  await hears(c)
  await expect(a.locator(".call-tile")).toHaveCount(2)              // a group shows tiles: cat and me
  await b.getByRole("button", { name: "Ablehnen" }).click()
  await c.getByRole("button", { name: "Auflegen" }).click()
  await expect(screen(a)).toHaveCount(0, { timeout: 8000 })         // alone: the call ends
  await expect(a.locator(".call-line").last()).toContainText(/Gruppenanruf · \d+:\d\d/)
})
