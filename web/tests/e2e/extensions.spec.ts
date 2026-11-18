// Extensions (server/extensions.py): downloaded and switched on in the settings, a tab of their own, their page in a
// sandboxed iframe that stores its data through the bridge (server/shipped_extensions/sdk.js) - the journal.
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
  await expect(card).toContainText("Von Momentum · Eigener Tab")
  await card.getByRole("button", { name: "Herunterladen" }).click()
  await expect(card.getByText("Aktiv")).toBeVisible()
  await page.screenshot({ path: test.info().outputPath("extensions.png") })

  // its tab: the page runs in a sandbox (scripts only, no network) and talks through the bridge
  await page.locator(".topbar button[aria-label=back]").click()
  await page.locator("button[aria-label=back]").first().click()
  const journalTab = tab(page, "ext:journal")
  await expect(journalTab).toBeVisible()
  await journalTab.click()
  const frame = page.locator("iframe.extension-frame")
  await expect(frame).toHaveAttribute("sandbox", "allow-scripts")
  const inside = page.frameLocator("iframe.extension-frame")
  await expect(inside.locator("#title")).toHaveText("Tagebuch")          // the bridge told it the language
  await expect(inside.getByText("Noch keine Einträge")).toBeVisible()
  // the pager has glided to the tab (a click meanwhile can miss)
  await expect.poll(async () => Math.round((await frame.boundingBox())!.x)).toBe(0)
  const mood = inside.locator("button[aria-label='mood 4']")
  await mood.click()
  await expect(mood).toHaveClass(/on/)
  await inside.locator("#text").fill("Erster Eintrag im Tagebuch")
  await inside.locator("#save").click()
  await expect(page.locator(".toast")).toContainText("Gespeichert")     // the app's toast, asked for by the page
  await expect(inside.locator(".entry")).toHaveCount(1)
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
  await tab(page, "ext:journal").click()
  await expect(page.frameLocator("iframe.extension-frame").locator(".entry")).toContainText("Erster Eintrag im Tagebuch")

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
  await expect(tab(page, "ext:journal")).toHaveCount(0)
  expect(errors).toEqual([])
})
