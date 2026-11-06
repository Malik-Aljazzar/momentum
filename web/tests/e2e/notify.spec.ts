// The home tab's card while notifications are off (src/push.ts notifyHint, screens/Home.tsx NotifyReminder): calls
// ring only while the app is open. In Safari (not the home-screen app) it says how to install; in the installed app
// it asks, or points to the phone's settings when they were denied; "not now" keeps it away for a week.
import { expect, test, type Page } from "@playwright/test"
import { newUser, openApp } from "./helpers"

const card = (page: Page) => page.getByRole("note").filter({ hasText: "Benachrichtigungen sind aus" })

/** The app as if opened from the home screen, the notifications' answer so far [permission]. */
async function homeScreenApp(page: Page, permission: "default" | "denied") {
  await page.addInitScript((p) => {
    Object.defineProperty(navigator, "standalone", { value: true, configurable: true })
    localStorage.setItem("notify_asked", "1")                // not the first start's question
    let now: string = p
    const N = function () {} as unknown as { permission: string; requestPermission(): Promise<string> }
    Object.defineProperty(N, "permission", { get: () => now })
    N.requestPermission = async () => { now = "granted"; return now }
    ;(window as unknown as { Notification: unknown }).Notification = N
  }, permission)
}

test("notifications off: install hint in Safari, 'not now' keeps it away", async ({ page, request }) => {
  const ann = await newUser(request, "na")
  await openApp(page, ann.token)
  await expect(card(page)).toBeVisible()
  await expect(card(page)).toContainText("Anrufe klingeln nur, solange die App offen ist")
  await expect(card(page)).toContainText("Zum Home-Bildschirm")
  await expect(card(page).getByRole("button", { name: "Erlauben" })).toHaveCount(0)
  await card(page).getByRole("button", { name: "Später" }).click()
  await expect(card(page)).toHaveCount(0)
  await page.reload()
  await expect(page.locator("nav.tabbar")).toBeVisible()
  await expect(card(page)).toHaveCount(0)
})

test("notifications off in the home-screen app: a tap allows them, the card goes", async ({ page, request }) => {
  const ben = await newUser(request, "nb")
  await homeScreenApp(page, "default")
  await openApp(page, ben.token)
  await expect(card(page)).toBeVisible()
  await expect(card(page)).not.toContainText("Zum Home-Bildschirm")
  await card(page).getByRole("button", { name: "Erlauben" }).click()
  await expect(card(page)).toHaveCount(0)
})

test("notifications denied in the home-screen app: the way to the phone's settings", async ({ page, request }) => {
  const cat = await newUser(request, "nc")
  await homeScreenApp(page, "denied")
  await openApp(page, cat.token)
  await expect(card(page)).toContainText("Einstellungen → Mitteilungen → Momentum")
  await expect(card(page).getByRole("button", { name: "Erlauben" })).toHaveCount(0)
})
