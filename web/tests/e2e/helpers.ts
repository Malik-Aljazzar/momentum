import { type APIRequestContext, expect, type Page } from "@playwright/test"
import { readFileSync } from "node:fs"

/** The newest version in CHANGELOG.md: a test device has already seen it (no what's-new sheet at start). */
export const NEWEST_RELEASE = readFileSync(new URL("../../../CHANGELOG.md", import.meta.url), "utf8").match(/^## (.+)$/m)![1].trim()

let counter = 0

/** The last code the test server "mailed" to [email] (server/testserver.py). */
export async function mailedCode(request: APIRequestContext, email: string): Promise<string> {
  let code: string | null = null
  await expect.poll(async () => (code = (await (await request.get(`/api/test/mail?to=${encodeURIComponent(email)}`)).json()).code)).toBeTruthy()
  return code!
}

/** A fresh account (sign-up, the mailed code, username) via the API; returns its token and name. */
export async function newUser(request: APIRequestContext, prefix = "u"): Promise<{ token: string; name: string }> {
  const name = `${prefix}${Date.now().toString(36)}${counter++}`.slice(0, 30)
  const email = `${name}@example.com`
  const r = await request.post("/api/auth/register", { data: { email, password: "geheim1", language: "de" } })
  expect(r.ok(), await r.text()).toBeTruthy()
  const { token } = await r.json()
  const confirmed = await request.post("/api/auth/email/confirm", { headers: { Authorization: `Bearer ${token}` }, data: { code: await mailedCode(request, email) } })
  expect(confirmed.ok(), await confirmed.text()).toBeTruthy()
  const s = await request.put("/api/settings", { headers: { Authorization: `Bearer ${token}` }, data: { username: name } })
  expect(s.ok(), await s.text()).toBeTruthy()
  return { token, name }
}

export async function api<T = any>(request: APIRequestContext, token: string, method: string, path: string, data?: unknown): Promise<T> {
  const r = await request.fetch("/api" + path, { method, headers: { Authorization: `Bearer ${token}` }, data })
  expect(r.ok(), `${method} ${path}: ${await r.text()}`).toBeTruthy()
  return r.json()
}

/** Opens the PWA logged in as [token] (German texts). */
export async function openApp(page: Page, token: string, lang = "de") {
  await page.addInitScript(([t, l, seen]) => {
    localStorage.setItem("token", JSON.stringify(t))
    localStorage.setItem("language", l)
    if (localStorage.getItem("changelog_seen") == null) localStorage.setItem("changelog_seen", JSON.stringify(seen))
  }, [token, lang, NEWEST_RELEASE])
  await page.goto("/app/")
  await expect(page.locator("nav.tabbar")).toBeVisible()
}

/** Bottom tab by its aria-label (home, rules, chats, feed, profile). */
export const tab = (page: Page, name: string) => page.locator(`nav.tabbar button[aria-label="${name}"]`)

/** Long press with a finger (touch), like holding a card on the phone. */
export async function longPress(page: Page, selector: string | ReturnType<Page["locator"]>) {
  const el = typeof selector === "string" ? page.locator(selector) : selector
  let box = (await el.boundingBox())!
  for (let i = 0; i < 20; i++) {                  // the tab may still be scrolling in
    await page.waitForTimeout(100)
    const next = (await el.boundingBox())!
    if (next.x === box.x && next.y === box.y) break
    box = next
  }
  const x = box.x + box.width / 3, y = box.y + box.height / 2
  await page.mouse.move(x, y)
  await page.mouse.down()
  await page.waitForTimeout(700)
  await page.mouse.up()
}
