// The own profile is the main page (2026-11-18): no home tab, today's numbers on the competition card, the whole
// history (undoable), the vehicles to swipe through (not reached = silhouette), the thunder cloud on the decay card.
import { expect, test } from "@playwright/test"
import { api, newUser, openApp, tab } from "./helpers"

test("the app starts on my profile: competition card with today's numbers, no settings button under the highlights", async ({ page, request }) => {
  const ann = await newUser(request, "ann")
  await openApp(page, ann.token)
  await expect(tab(page, "home")).toHaveCount(0)
  const card = page.locator(".competition-card")
  await expect(card).toBeVisible()
  // on screen right away (the profile is the last tab: the app jumps there, not scrolls)
  const box = (await card.boundingBox())!
  expect(box.x).toBeGreaterThanOrEqual(0)
  expect(box.x + box.width).toBeLessThanOrEqual(page.viewportSize()!.width)
  await expect(card.locator(".today-stats")).toContainText("Heute")
  await expect(card.locator(".today-stats")).toContainText("Ziel heute")
  await expect(card.locator(".today-stats")).toContainText("Heute Nacht")
  await expect(page.getByRole("button", { name: "Einstellungen", exact: true })).toHaveCount(0)
  await expect(page.locator("button[aria-label=settings]")).toBeVisible()      // the gear at the top stays
  await page.screenshot({ path: test.info().outputPath("own-profile.png") })
})

test("swiping the competition card shows the other vehicles, the ones not reached as a silhouette", async ({ page, request }) => {
  const ann = await newUser(request, "ann")
  await openApp(page, ann.token)
  const card = page.locator(".competition-card")
  const label = card.locator(".vehicle-label")
  await expect(label).toHaveText("Fahrrad · Stufe 1/13")
  const strip = card.locator(".vehicle-strip")
  await strip.evaluate((el) => el.scrollTo({ left: el.clientWidth * 2, behavior: "instant" }))
  await expect(label).toHaveText("Stufe 3/13 · ab 2.000 Momentum")
  // a silhouette: the sprite drawn black, not animated
  const silhouette = card.locator('[data-vehicle="kleinwagen"] .pixel')
  await expect(silhouette).toHaveCount(1)
  expect(await silhouette.evaluate((el) => getComputedStyle(el).filter)).toContain("brightness(0)")
  await expect(card.locator('[data-vehicle="kleinwagen"] .vehicle-fx')).toHaveCount(0)
  await expect(card.locator('[data-vehicle="fahrrad"] .vehicle-fx')).toHaveCount(0)              // two pages away: not drawn
  await page.screenshot({ path: test.info().outputPath("silhouette.png") })
  await strip.evaluate((el) => el.scrollTo({ left: 0, behavior: "instant" }))
  await expect(label).toHaveText("Fahrrad · Stufe 1/13")
  await expect(card.locator('[data-vehicle="fahrrad"] .vehicle-fx')).toHaveCount(1)
  // someone else's profile swipes too
  const ben = await newUser(request, "ben")
  const prof = (await api(request, ann.token, "GET", `/users/${ben.name}`)).profile
  expect(prof.milestones.slice(0, 4)).toEqual([0, 1000, 2000, 4000])
  expect(prof.banners).toEqual({ land: null, air: null, space: null })
})

test("my whole history: older pages load, taps can be undone from there - also the older ones", async ({ page, request }) => {
  const ann = await newUser(request, "ann")
  const rules = (await api(request, ann.token, "GET", "/state")).state.rules
  const acc = rules.find((r: any) => r.kind === "acc")
  await api(request, ann.token, "PUT", "/settings", { daily_limit: false })       // no daily limit: every tap counts
  for (let i = 0; i < 34; i++) await api(request, ann.token, "POST", `/rules/${acc.id}/trigger`)
  await openApp(page, ann.token)
  await page.locator("button[aria-label=history]").click()
  // 34 taps and the two milestones on the way (1,000 and 2,000)
  const entries = page.locator(".my-history .history-entry")
  await expect(entries).toHaveCount(30)
  await entries.last().scrollIntoViewIfNeeded()
  await expect(entries).toHaveCount(36)                     // the next page came as the list was scrolled
  await expect(entries.filter({ hasText: "Meilenstein" })).toHaveCount(2)
  // the newest tap undone from the profile
  await entries.first().click()
  await page.getByRole("button", { name: "Rückgängig" }).click()
  await expect(entries).toHaveCount(35)
  // and the oldest one, from the page loaded later: it leaves the list too
  const momentum = (await api(request, ann.token, "GET", "/state")).state.momentum
  await entries.last().click()
  await page.getByRole("button", { name: "Rückgängig" }).click()
  await expect(entries).toHaveCount(34)
  expect((await api(request, ann.token, "GET", "/state")).state.momentum).toBeLessThan(momentum)
  const page2 = (await api(request, ann.token, "GET", "/events?limit=30")).events
  expect(page2).toHaveLength(30)
  const older = (await api(request, ann.token, "GET", `/events?before=${page2[29].id}&limit=30`)).events
  expect(older).toHaveLength(4)
})

test("the decay card carries the thunder cloud with the missed days", async ({ page, request }) => {
  const ann = await newUser(request, "ann")
  await openApp(page, ann.token)
  await tab(page, "rules").click()
  await page.locator("text=ENTSCHL.").first().click()            // the decelerators' half, with the decay card
  const chip = page.locator(".streak-chip")
  await expect(chip.first()).toContainText("0 Tage")
})
