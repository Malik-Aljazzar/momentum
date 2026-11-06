import { expect, test } from "@playwright/test"
import { readFileSync } from "node:fs"
import { NEWEST_RELEASE, newUser, openApp } from "./helpers"

/** The version before the newest one: this device saw it, so what's new shows the newest release. */
const SECOND_RELEASE = [...readFileSync(new URL("../../../CHANGELOG.md", import.meta.url), "utf8").matchAll(/^## (.+)$/gm)][1][1].trim()

test("a day's first start: what's new first, then the limit's change since yesterday - once", async ({ page, request }) => {
  const { token } = await newUser(request)
  // yesterday's limit 50, today's stays as it is (1000 on the test server, the minimum): higher than yesterday
  const r = await request.post("/api/test/limit/yesterday", { data: { cap: 50 } })
  expect(r.ok(), await r.text()).toBeTruthy()
  await page.addInitScript((seen) => {
    // this device was here before: an older changelog and an earlier day
    if (localStorage.getItem("changelog_seen") == null) localStorage.setItem("changelog_seen", JSON.stringify(seen))
    if (localStorage.getItem("limit_seen_day") == null) localStorage.setItem("limit_seen_day", JSON.stringify("2000-01-01"))
  }, SECOND_RELEASE)
  await openApp(page, token)

  const news = page.getByRole("dialog", { name: "Was ist neu" })
  const limit = page.getByRole("dialog", { name: "Tageslimit gestiegen" })
  await expect(news).toBeVisible()
  await expect(news.locator("[data-release]").first()).toHaveAttribute("data-release", NEWEST_RELEASE)
  await page.waitForTimeout(300)
  await expect(limit).toBeHidden()                                     // one after the other, not on top
  await news.getByRole("button", { name: "Alles klar" }).click()
  await expect(news).toBeHidden()

  await expect(limit).toBeVisible()
  const numbers = limit.locator(".limit-change")
  await expect(numbers).toContainText("50")
  await expect(numbers).toContainText("1.000")
  expect((await numbers.boundingBox())!.height).toBeGreaterThan(16)
  await expect(limit.getByText("Heute kannst du bis zu 1.000 Momentum sammeln – gestern waren es 50.")).toBeVisible()
  await limit.getByRole("button", { name: "Alles klar" }).click()
  await expect(limit).toBeHidden()
  const day = await page.evaluate(() => JSON.parse(localStorage.getItem("limit_seen_day")!))
  expect(day).toMatch(/^\d{4}-\d{2}-\d{2}$/)
  expect(day).not.toBe("2000-01-01")

  // the same day again: quiet
  await page.reload()
  await expect(page.locator("nav.tabbar")).toBeVisible()
  await page.waitForTimeout(500)
  await expect(limit).toBeHidden()
  await expect(news).toBeHidden()
})
