import { expect, test } from "@playwright/test"
import { NEWEST_RELEASE, newUser, openApp, tab } from "./helpers"

test("after an update the app shows what's new once; the settings show the whole list", async ({ page, request }) => {
  const { token } = await newUser(request)
  // this device ran the app before the changelog existed: everything, once
  await page.addInitScript(() => {
    if (localStorage.getItem("changelog_seen") == null) localStorage.setItem("changelog_seen", JSON.stringify(""))
  })
  await openApp(page, token)
  const sheet = page.getByRole("dialog", { name: "Was ist neu" })
  await expect(sheet).toBeVisible()
  const newest = sheet.locator("[data-release]").first()
  await expect(newest).toHaveAttribute("data-release", NEWEST_RELEASE)
  const bullet = newest.locator(".row").first()
  await expect(bullet).toBeVisible()
  expect((await bullet.boundingBox())!.height).toBeGreaterThan(10)
  await sheet.getByRole("button", { name: "Alles klar" }).click()
  await expect(sheet).toBeHidden()
  expect(await page.evaluate(() => localStorage.getItem("changelog_seen"))).toBe(JSON.stringify(NEWEST_RELEASE))

  // seen: the next start stays quiet
  await page.reload()
  await expect(page.locator("nav.tabbar")).toBeVisible()
  await page.waitForTimeout(500)
  await expect(sheet).toBeHidden()

  // the settings open the whole list any time
  await tab(page, "profile").click()
  await page.locator("button[aria-label=settings]").click()
  await page.getByRole("button", { name: "Alle Änderungen" }).click()
  await expect(sheet).toBeVisible()
  await expect(sheet.locator("[data-release]").first()).toHaveAttribute("data-release", NEWEST_RELEASE)
  await sheet.getByRole("button", { name: "Alles klar" }).click()
  await expect(sheet).toBeHidden()
})

test("a fresh install starts at the newest version and sees no changelog", async ({ page, request }) => {
  const { token } = await newUser(request)
  await page.addInitScript(() => localStorage.setItem("language", "de"))
  await page.goto("/app/")                                             // first start: the login screen
  await expect(page.getByText("Neu hier? Konto erstellen")).toBeVisible()
  expect(await page.evaluate(() => localStorage.getItem("changelog_seen"))).toBe(JSON.stringify(NEWEST_RELEASE))
  await page.evaluate((t) => localStorage.setItem("token", JSON.stringify(t)), token)
  await page.reload()
  await expect(page.locator("nav.tabbar")).toBeVisible()
  await page.waitForTimeout(500)
  await expect(page.getByRole("dialog", { name: "Was ist neu" })).toBeHidden()
})
