// Extensions (server/extensions.py): downloaded and switched on in the settings, their tabs placed by hand (settings ->
// tabs), their page in a sandboxed iframe that stores its data through the bridge (server/shipped_extensions/sdk.js) -
// the journal.
import { expect, test } from "@playwright/test"
import { api, newUser, openApp, tab } from "./helpers"

test("the journal extension: download it, write in its tab, its data is on the server; off and removed again", async ({ page, request }) => {
  const errors: string[] = []
  // the test's own start script (openApp: localStorage) runs in the sandboxed frame too and is refused there -
  // not the app's error, rather the sandbox at work
  page.on("pageerror", (e) => { if (!String(e.stack).includes("web-inspector://")) errors.push(String(e)) })
  const ann = await newUser(request, "ann")
  await openApp(page, ann.token)
  await tab(page, "profile").click()
  await page.locator("button[aria-label=settings]").click()
  await page.locator("[data-page=extensions]").click()
  const card = page.locator(".extension-card[data-ext=journal]")
  await expect(card).toContainText("Tagebuch")
  await expect(card).toContainText("Von Momentum · 2 Tabs")
  await card.getByRole("button", { name: "Herunterladen" }).click()
  await expect(card.getByText("Aktiv")).toBeVisible()
  // its tabs wait to be placed: straight to settings -> tabs
  await expect(card.locator(".tabs-waiting")).toContainText("Einstellungen → Tabs")
  await page.screenshot({ path: test.info().outputPath("extensions.png") })
  await card.getByRole("button", { name: "Tabs anordnen" }).click()
  await page.locator(".tabs-hidden [data-tab='ext:journal:today'] button").click()        // no groups: into the bar
  await page.getByRole("button", { name: "Speichern" }).click()
  await expect(page.locator(".toast")).toContainText("Gespeichert")
  await page.locator(".topbar button[aria-label=back]").click()
  await expect(card.locator(".tabs-waiting")).toBeVisible()            // "entries" still waits (hidden)

  // its "today" tab: the page runs in a sandbox (scripts only, no network) and talks through the bridge
  await page.locator(".topbar button[aria-label=back]").click()
  await page.locator("button[aria-label=back]").first().click()
  const journalTab = tab(page, "ext:journal:today")
  await expect(journalTab).toBeVisible()
  await journalTab.click()
  const frame = page.locator("iframe.extension-frame")
  await expect(frame).toHaveAttribute("sandbox", "allow-scripts")
  const inside = page.frameLocator("iframe.extension-frame")
  await expect(inside.locator("#title")).toHaveText("Tagebuch")          // the bridge told it the language
  await expect(inside.locator("#editor")).toBeVisible()                 // the bridge told it which tab: today's entry only
  await expect(inside.locator("#entries")).toBeHidden()
  // the pager has glided to the tab (a click meanwhile can miss)
  await expect.poll(async () => Math.round((await frame.boundingBox())!.x)).toBe(0)
  const mood = inside.locator("button[aria-label='mood 4']")
  await mood.click()
  await expect(mood).toHaveClass(/on/)
  await inside.locator("#text").fill("Erster Eintrag im Tagebuch")
  await inside.locator("#save").click()
  await expect(page.locator(".toast")).toContainText("Gespeichert")     // the app's toast, asked for by the page
  await expect(inside.locator("#status")).toHaveText("Gespeichert")
  await page.screenshot({ path: test.info().outputPath("journal.png") })
  const docs = (await api(request, ann.token, "GET", "/extensions/journal/data/entries")).docs
  expect(docs).toHaveLength(1)
  expect(docs[0].value).toEqual({ text: "Erster Eintrag im Tagebuch", mood: 4 })
  expect(docs[0].key).toMatch(/^\d{4}-\d\d-\d\d$/)
  // the page itself reaches nothing: no token, no app storage, no network
  const peek = await page.frame({ url: /about:srcdoc/ })?.evaluate(async () => {
    let storage = "none"
    try { storage = String(localStorage.getItem("token")) } catch { storage = "blocked" }
    let net = "blocked"
    try { await fetch("/api/state"); net = "open" } catch { net = "blocked" }
    return { storage, net, origin: location.origin }
  })
  expect(peek).toEqual({ storage: "blocked", net: "blocked", origin: "null" })

  // after a restart the tab is still there and shows the entry
  await page.reload()
  await tab(page, "ext:journal:today").click()
  await expect(page.frameLocator("iframe.extension-frame").locator("#text")).toHaveValue("Erster Eintrag im Tagebuch")

  // switched off: the tab goes; removed: its data too
  await tab(page, "profile").click()
  await page.locator("button[aria-label=settings]").click()
  await page.locator("[data-page=extensions]").click()
  await card.getByText("Aktiv").click()
  await expect.poll(async () => (await api(request, ann.token, "GET", "/state")).state.extensions[0].enabled).toBe(false)
  await card.getByRole("button", { name: "Entfernen" }).click()
  await card.getByRole("button", { name: /Sicher/ }).click()
  await expect(card.getByRole("button", { name: "Herunterladen" })).toBeVisible()
  expect((await api(request, ann.token, "GET", "/state")).state.extensions).toEqual([])
  await page.locator(".topbar button[aria-label=back]").click()
  await page.locator("button[aria-label=back]").first().click()
  await expect(tab(page, "ext:journal:today")).toHaveCount(0)
  // removed: its tab left the layout too
  expect((await api(request, ann.token, "GET", "/state")).state.settings.tabs.some((t: any) => String(t.tab).startsWith("ext:"))).toBe(false)
  expect(errors).toEqual([])
})
