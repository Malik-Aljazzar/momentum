import { expect, type Page, test } from "@playwright/test"
import { newUser, openApp } from "./helpers"

/** A finger pulling from (x, y) down by dy in a few steps (synthetic touch events, like the phone's). */
async function swipeDown(page: Page, x: number, y: number, dy: number, steps = 6, stepMs = 30) {
  await page.evaluate(async ([x, y, dy, steps, stepMs]) => {
    const target = document.elementFromPoint(x, y)!
    // WebKit has no Touch constructor: plain events carrying what the handlers read
    const fire = (type: string, cy: number) => {
      const e = new Event(type, { bubbles: true, cancelable: true })
      const touch = { identifier: 1, target, clientX: x, clientY: cy }
      Object.defineProperty(e, "touches", { value: type === "touchend" ? [] : [touch] })
      Object.defineProperty(e, "changedTouches", { value: [touch] })
      target.dispatchEvent(e)
    }
    fire("touchstart", y)
    for (let i = 1; i <= steps; i++) {
      await new Promise((r) => setTimeout(r, stepMs))
      fire("touchmove", y + (dy * i) / steps)
    }
    fire("touchend", y + dy)
  }, [x, y, dy, steps, stepMs] as const)
}

test("a sheet closes with a swipe down and springs back from a short pull (report #71)", async ({ page, request }) => {
  const me = await newUser(request, "sheet")
  await openApp(page, me.token)
  const sheet = page.locator(".sheet")
  const open = async () => {
    await page.locator("nav.tabbar button[aria-label=profile]").click()
    await page.getByRole("button", { name: "share" }).click()        // the profile's share sheet
    await expect(sheet).toBeVisible()
    await page.waitForTimeout(300)                                    // its slide-in is over
  }
  await open()
  const handle = (await sheet.locator(".handle").boundingBox())!
  const height = (await sheet.boundingBox())!.height
  // a short, slow pull: it comes back
  await swipeDown(page, handle.x + handle.width / 2, handle.y + 2, Math.round(height * .1), 6, 60)
  await page.waitForTimeout(400)
  await expect(sheet).toBeVisible()
  expect((await sheet.boundingBox())!.height).toBeCloseTo(height, 0)
  // pulled down far: it slides away
  await swipeDown(page, handle.x + handle.width / 2, handle.y + 2, Math.round(height * .6))
  await expect(sheet).toHaveCount(0)
  // the scrim slides it away too
  await open()
  await page.locator(".sheet-scrim").click({ position: { x: 20, y: 20 } })
  await expect(sheet).toHaveCount(0)
})
