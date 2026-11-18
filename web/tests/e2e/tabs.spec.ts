// My tabs (settings -> tabs, src/tablayout.ts, server/tablayout.py): the bar's places in order, groups with a tab menu
// at the top (names / icons / both, sliding when they don't fit), hidden tabs, extension tabs placed by hand.
import { expect, type Page, test } from "@playwright/test"
import { api, newUser, openApp, tab } from "./helpers"

async function openTabsEditor(page: Page) {
  await tab(page, "profile").click()
  await page.locator("button[aria-label=settings]").click()
  await page.locator("[data-page=tabs]").click()
  await expect(page.locator(".tabs-editor")).toBeVisible()
}

const row = (page: Page, key: string) => page.locator(`.tabs-editor .tabs-row[data-tab="${key}"]`)
const btn = (page: Page, key: string, label: string) => row(page, key).getByRole("button", { name: label, exact: true })

test("a group in the bar: made in the settings, its tab menu at the top, nothing cut off", async ({ page, request }) => {
  const ann = await newUser(request, "ann")
  await openApp(page, ann.token)
  await expect(page.locator("nav.tabbar button:not([aria-label=election])")).toHaveCount(4)
  await openTabsEditor(page)
  await expect(page.locator(".tabs-count")).toHaveText("4 von 5 Plätzen")
  await expect(btn(page, "profile", "Ausblenden")).toBeDisabled()           // the profile always stays

  // a new group (at the end), named, both names and icons, another icon
  await page.getByRole("button", { name: "Neue Gruppe" }).click()
  await expect(page.locator(".tabs-count")).toHaveText("5 von 5 Plätzen")
  await expect(page.getByRole("button", { name: "Neue Gruppe" })).toBeDisabled()
  const editor = page.locator(".group-editor")
  await editor.locator("input").fill("Social")
  await editor.locator("[data-show=both]").click()
  await editor.locator("button[aria-label=tab_people]").click()
  // one place up (before the profile); the profile into it and out again; two more up (after "rules"); then
  // chats and feed into it
  const group = page.locator(".tabs-group[data-group=g1]")
  await group.getByRole("button", { name: "Nach oben" }).click()
  await btn(page, "profile", "In die Gruppe darüber").click()          // the profile can go into a group too
  await btn(page, "profile", "Aus der Gruppe holen").click()           // and out again
  await group.getByRole("button", { name: "Nach oben" }).first().click()
  await group.getByRole("button", { name: "Nach oben" }).first().click()   // now: rules, g1, chats, feed, profile
  await btn(page, "chats", "In die Gruppe darüber").click()
  await btn(page, "feed", "In die Gruppe darüber").click()
  await expect(page.locator(".tabs-count")).toHaveText("3 von 5 Plätzen")
  await page.screenshot({ path: test.info().outputPath("tabs-editor.png") })
  await page.getByRole("button", { name: "Speichern" }).click()
  await expect(page.locator(".toast")).toContainText("Gespeichert")
  const saved = (await api(request, ann.token, "GET", "/state")).state.settings.tabs
  expect(saved).toEqual([{ tab: "rules" }, { id: "g1", name: "Social", icon: "tab_people", show: "both", tabs: ["chats", "feed"] }, { tab: "profile" }])

  // the bar: rules, the group, the profile
  await page.locator(".topbar button[aria-label=back]").click()
  await page.locator("button[aria-label=back]").first().click()
  await expect(page.locator("nav.tabbar button:not([aria-label=election])")).toHaveCount(3)
  await tab(page, "group:g1").click()
  const menu = page.locator(".group-menu")
  await expect(menu.locator("[data-tab]")).toHaveCount(2)
  await expect(menu.locator("[data-tab=chats]")).toHaveAttribute("aria-selected", "true")     // its first at the start
  await expect(menu.locator("[data-tab=chats]")).toContainText("Nachrichten")
  await expect(menu.locator("[data-tab=chats] .pixel, [data-tab=chats] canvas, [data-tab=chats] img").first()).toBeVisible()
  await menu.locator("[data-tab=feed]").click()
  await expect(menu.locator("[data-tab=feed]")).toHaveAttribute("aria-selected", "true")
  await page.screenshot({ path: test.info().outputPath("group.png") })
  // the menu sits below the status bar, the feed right under it (not pushed down a second time)
  const menuBox = (await menu.boundingBox())!
  expect(menuBox.y).toBeLessThan(80)
  // the profile is still the start page
  await page.reload()
  await expect(page.locator(".competition-card")).toBeVisible()
})

test("extension tabs: in the choice once switched on, placed by hand - into the bar and into a group that slides", async ({ page, request }) => {
  const ann = await newUser(request, "ann")
  await api(request, ann.token, "POST", "/extensions/daylist/install")
  const group = { id: "g1", name: "Alles", icon: "tab_star", show: "both", tabs: ["rules", "chats", "feed"] }
  await api(request, ann.token, "PUT", "/settings", { tabs: [group, { tab: "profile" }] })
  await openApp(page, ann.token)
  await expect(page.locator("nav.tabbar button:not([aria-label=election])")).toHaveCount(2)            // not placed yet: no daylist tab
  await openTabsEditor(page)
  const hidden = page.locator(".tabs-hidden")
  await expect(hidden.locator("[data-tab='ext:daylist:list']")).toContainText("Aufgaben")
  await expect(hidden.locator("[data-tab='ext:daylist:list']")).toContainText("Daylist")
  // into the group: where to?
  await hidden.locator("[data-tab='ext:daylist:list'] button").click()
  await page.locator(".tabs-where [data-group=g1]").click()
  await hidden.locator("[data-tab='ext:daylist:done'] button").click()
  await page.getByRole("button", { name: "Unten in die Leiste" }).click()
  await expect(page.locator(".tabs-hidden")).toContainText("Alles ist eingeordnet.")
  await page.getByRole("button", { name: "Speichern" }).click()
  await expect(page.locator(".toast")).toContainText("Gespeichert")
  await page.locator(".topbar button[aria-label=back]").click()
  await page.locator("button[aria-label=back]").first().click()

  // the group: 4 tabs with names and icons don't fit on an iPhone - the menu slides, no name is cut off
  await tab(page, "group:g1").click()
  const menu = page.locator(".group-menu")
  await expect(menu.locator("[data-tab]")).toHaveCount(4)
  const sizes = await menu.evaluate((el) => ({
    scroll: el.scrollWidth, width: el.clientWidth,
    cut: [...el.querySelectorAll(".t-button")].filter((t) => t.scrollWidth > t.clientWidth + 1).length,
  }))
  expect(sizes.cut).toBe(0)
  expect(sizes.scroll).toBeGreaterThan(sizes.width)
  await menu.locator("[data-tab='ext:daylist:list']").click()
  await expect(menu.locator("[data-tab='ext:daylist:list']")).toBeInViewport()
  const tasks = page.frameLocator(".tab-group[data-group=g1] iframe.extension-frame")
  await expect(tasks.locator("#title")).toHaveText("Aufgaben")             // its task list (the context said which tab)
  await expect(tasks.locator("#quick")).toBeVisible()
  await page.screenshot({ path: test.info().outputPath("group-slides.png") })

  // the bar's daylist tab is the other one: done today
  await tab(page, "ext:daylist:done").click()
  await expect(page.locator("iframe.extension-frame")).toHaveCount(2)          // the group's one stays as it was
  const done = page.frameLocator("[data-ext-tab='daylist:done'] iframe.extension-frame")
  await expect(done.locator("#title")).toHaveText("Erledigt")
  await expect(done.locator("#noneDone")).toBeVisible()
  await expect(done.locator("#quick")).toHaveCount(0)

  // switched off: its tabs leave the bar and the group; on again: back in their places
  await api(request, ann.token, "PUT", "/extensions/daylist", { enabled: false })
  await page.reload()
  await expect(tab(page, "ext:daylist:done")).toHaveCount(0)
  await tab(page, "group:g1").click()
  await expect(page.locator(".group-menu [data-tab]")).toHaveCount(3)
  await api(request, ann.token, "PUT", "/extensions/daylist", { enabled: true })
  await page.reload()
  await expect(tab(page, "ext:daylist:done")).toBeVisible()
})
