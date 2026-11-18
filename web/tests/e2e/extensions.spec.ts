// Extensions (server/extensions.py): downloaded and switched on in the settings, their tabs placed by hand (settings ->
// tabs), their page in a sandboxed iframe that talks through the bridge (server/shipped_extensions/sdk.js) - the
// daylist: recurring tasks that tap the player's rules (permission "rules"), a done tab that takes them back.
import { expect, type Page, test } from "@playwright/test"
import { api, newUser, openApp, tab } from "./helpers"

const frameOf = (page: Page, key: string) => page.frameLocator(`[data-ext-tab='daylist:${key}'] iframe.extension-frame`)

test("the daylist: tasks tap the linked rules once done (also after 3 taps), the done tab takes them back, one-offs from the list", async ({ page, request }) => {
  test.setTimeout(120_000)                         // a long walk: ~15 s alone, much more while the whole suite runs
  const errors: string[] = []
  // the test's own start script (openApp: localStorage) runs in the sandboxed frame too and is refused there -
  // not the app's error, rather the sandbox at work
  page.on("pageerror", (e) => { if (!String(e.stack).includes("web-inspector://")) errors.push(String(e)) })
  const ann = await newUser(request, "ann")
  await api(request, ann.token, "PUT", "/settings", { daily_limit: false })        // every tap counts
  const acc = (await api(request, ann.token, "GET", "/state")).state.rules.find((r: any) => r.kind === "acc")
  await openApp(page, ann.token)
  await tab(page, "profile").click()
  await page.locator("button[aria-label=settings]").click()
  await page.locator("[data-page=extensions]").click()
  const card = page.locator(".extension-card[data-ext=daylist]")
  await expect(card).toContainText("Daylist")
  await expect(card).toContainText("Von Momentum · 2 Tabs")
  await expect(card.locator(".ext-permission")).toContainText("Darf deine antippbaren Regeln sehen und für dich antippen")
  await expect(page.locator(".extension-card[data-ext=journal]")).toHaveCount(0)          // retired
  await card.getByRole("button", { name: "Herunterladen" }).click()
  await expect(card.getByText("Aktiv")).toBeVisible()
  // its tabs wait to be placed: straight to settings -> tabs
  await expect(card.locator(".tabs-waiting")).toContainText("Einstellungen → Tabs")
  await card.getByRole("button", { name: "Tabs anordnen" }).click()
  await page.locator(".tabs-hidden [data-tab='ext:daylist:list'] button").click()        // no groups: into the bar
  await page.getByRole("button", { name: "Speichern" }).click()
  await expect(page.locator(".toast")).toContainText("Gespeichert")
  // the done tab instead of the feed (the bar is full now)
  await api(request, ann.token, "PUT", "/settings", { tabs: [{ tab: "rules" }, { tab: "chats" }, { tab: "ext:daylist:list" }, { tab: "ext:daylist:done" }, { tab: "profile" }] })
  await page.reload()
  // the placed tab's page was already loading: WebKit reports a request cut off by the reload as "access control checks"
  errors.splice(0, errors.length, ...errors.filter((e) => !e.includes("due to access control checks")))

  // the task list: sandboxed, empty, the pencil in the corner edits the tasks
  await tab(page, "ext:daylist:list").click()
  const frame = page.locator("[data-ext-tab='daylist:list'] iframe.extension-frame")
  await expect(frame).toHaveAttribute("sandbox", "allow-scripts")
  await expect.poll(async () => Math.round((await frame.boundingBox())!.x)).toBe(0)        // the pager has glided there
  const list = frameOf(page, "list")
  await expect(list.locator("#title")).toHaveText("Aufgaben")
  await expect(list.getByText("Noch keine Aufgaben")).toBeVisible()
  await list.locator("#edit").click()
  await list.locator("#new").click()
  await list.locator("#name").fill("Beten")
  // its icon: the app's picker over the page, the same icons and emojis as a rule's (bridge pickIcon)
  await list.locator("#icon").click()
  const icons = page.locator(".sheet[aria-label=icons]")
  await expect(icons).toContainText("Icon wählen")
  await icons.locator("[data-icon=star]").click()
  await expect(icons).toHaveCount(0)
  await expect(list.locator("#icon img")).toHaveAttribute("src", "sprites/icons/star.png")
  await expect.poll(() => list.locator("#icon img").evaluate((i: HTMLImageElement) => i.naturalWidth)).toBeGreaterThan(0)
  await expect(list.locator("#name")).toHaveValue("Beten")                              // typed text stays
  await list.locator("#times .plus").click()
  await list.locator("#times .plus").click()
  await expect(list.locator("#times")).toContainText("3×")
  await list.locator("#addRule").click()
  await list.locator(`.pick[data-rule='${acc.id}']`).click()
  await list.locator(`.link[data-rule='${acc.id}'] .plus`).click()                      // two taps of it
  await expect(list.locator(`.link[data-rule='${acc.id}']`)).toContainText("×2")
  await page.screenshot({ path: test.info().outputPath("daylist-task.png") })
  await list.locator("#saveTask").click()
  // a weekday task that isn't due today: in the editor's list, not in today's
  const wd = ((new Date().getDay() + 6) % 7) + 1                                          // Monday 1 .. Sunday 7
  const other = (wd % 7) + 1
  await list.locator("#new").click()
  await list.locator("#name").fill("Wäsche")
  // an emoji as its icon; closing the picker without a pick keeps it
  await list.locator("#icon").click()
  await icons.locator("button[aria-label=emoji]").click()
  await page.locator("[data-testid=emoji-grid] .emoji-cell").first().click()
  await expect(icons).toHaveCount(0)
  const emoji = (await list.locator("#icon .emo").textContent())!
  expect(emoji.length).toBeGreaterThan(0)
  await list.locator("#icon").click()
  await page.locator(".sheet-scrim").last().click({ position: { x: 30, y: 30 } })       // above the sheet
  await expect(icons).toHaveCount(0)
  await expect(list.locator("#icon .emo")).toHaveText(emoji)
  await list.locator("[data-type=weekdays]").click()
  await list.locator(`[data-wd='${wd}']`).click()
  await list.locator(`[data-wd='${other}']`).click()
  await list.locator("#saveTask").click()
  await expect(list.locator("#all .task")).toHaveCount(2)
  await expect(list.locator("#all")).toContainText("Jeden Tag")
  await expect(list.locator("#all .task", { hasText: "Wäsche" }).locator(".ticon .emo")).toHaveText(emoji)
  await list.locator("#edit").click()                                                    // back to today's list

  // three taps until it counts; then the rule is tapped twice for me
  const row = list.locator(".task", { hasText: "Beten" })
  await expect(list.locator("#open .task")).toHaveCount(1)
  await expect(row.locator(".pips i")).toHaveCount(3)                                    // a little bar fills up
  await expect(row.locator(".ticon img")).toHaveAttribute("src", "sprites/icons/star.png")
  await expect(row.locator(".pips i.on")).toHaveCount(0)
  await row.click()
  await expect(row.locator(".pips i.on")).toHaveCount(1)
  await row.click()
  await expect(row.locator(".pips i.on")).toHaveCount(2)
  // one tap too many? one back, and again
  await row.locator(".back1").click()
  await expect(row.locator(".pips i.on")).toHaveCount(1)
  await row.click()
  await expect(row.locator(".pips i.on")).toHaveCount(2)
  await page.screenshot({ path: test.info().outputPath("daylist-list.png") })
  const before = (await api(request, ann.token, "GET", "/state")).state.momentum
  await row.click()
  await expect(page.locator(".toast")).toContainText("Erledigt: Beten")
  await expect(list.locator("#allDone")).toBeVisible()
  const taps = (await api(request, ann.token, "GET", "/events?limit=10")).events.filter((e: any) => e.label === acc.name)
  expect(taps).toHaveLength(2)
  expect((await api(request, ann.token, "GET", "/state")).state.momentum).toBeGreaterThan(before)

  // the done tab: there it is - undone, the taps are taken back and it's in the list again (one tap short)
  await tab(page, "ext:daylist:done").click()
  const done = frameOf(page, "done")
  await expect(done.locator("#title")).toHaveText("Erledigt")
  await expect(done.locator(".done-row")).toHaveCount(1)
  await expect(done.locator(".done-row")).toContainText("Beten")
  await expect(done.locator(".done-row .ticon img")).toHaveAttribute("src", "sprites/icons/star.png")
  await page.screenshot({ path: test.info().outputPath("daylist-done.png") })
  await done.locator(".done-row .undo").click()
  await expect(page.locator(".toast")).toContainText("Zurück in der Liste: Beten")
  await expect(done.locator("#noneDone")).toBeVisible()
  expect((await api(request, ann.token, "GET", "/events?limit=10")).events.filter((e: any) => e.label === acc.name)).toHaveLength(0)
  expect((await api(request, ann.token, "GET", "/state")).state.momentum).toBe(before)
  await tab(page, "ext:daylist:list").click()
  await expect(list.locator(".task", { hasText: "Beten" }).locator(".pips i.on")).toHaveCount(2)

  // a one-off from the list itself
  await list.locator("#quick").fill("Einkaufen")
  await list.locator("#quick").press("Enter")
  const once = list.locator(".task", { hasText: "Einkaufen" })
  await expect(once).toBeVisible()
  await once.click()
  await expect(page.locator(".toast")).toContainText("Erledigt: Einkaufen")
  await expect(once).toHaveCount(0)
  const tasks = (await api(request, ann.token, "GET", "/extensions/daylist/data/tasks")).docs
  expect(tasks).toHaveLength(3)
  expect(tasks.find((d: any) => d.value.name === "Beten").value.icon).toBe("star")
  expect(tasks.find((d: any) => d.value.name === "Wäsche").value.icon).toBe(emoji)
  expect(tasks.find((d: any) => d.value.name === "Einkaufen").value.icon).toBeUndefined()
  expect(tasks.find((d: any) => d.value.name === "Einkaufen").value.done).toMatch(/^\d{4}-\d\d-\d\d$/)

  // the page itself reaches nothing: no token, no app storage, no network
  const peek = await page.frame({ url: /about:srcdoc/ })?.evaluate(async () => {
    let storage = "none"
    try { storage = String(localStorage.getItem("token")) } catch { storage = "blocked" }
    let net = "blocked"
    try { await fetch("/api/state"); net = "open" } catch { net = "blocked" }
    return { storage, net, origin: location.origin }
  })
  expect(peek).toEqual({ storage: "blocked", net: "blocked", origin: "null" })

  // removed: its tabs and its data go
  await tab(page, "profile").click()
  await page.locator("button[aria-label=settings]").click()
  await page.locator("[data-page=extensions]").click()
  await card.getByRole("button", { name: "Entfernen" }).click()
  await card.getByRole("button", { name: /Sicher/ }).click()
  await expect(card.getByRole("button", { name: "Herunterladen" })).toBeVisible()
  expect((await api(request, ann.token, "GET", "/state")).state.extensions).toEqual([])
  await page.locator(".topbar button[aria-label=back]").click()
  await page.locator("button[aria-label=back]").first().click()
  await expect(tab(page, "ext:daylist:list")).toHaveCount(0)
  expect((await api(request, ann.token, "GET", "/state")).state.settings.tabs.some((t: any) => String(t.tab).startsWith("ext:"))).toBe(false)
  expect(errors).toEqual([])
})
