// Screenshots of every main screen (test-results/tour/*.png) to compare the PWA with the Android
// app by eye; also fails when a screen throws.
import { expect, test } from "@playwright/test"
import { api, newUser, openApp, tab } from "./helpers"

const PNG = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="

test("tour through all screens", async ({ page, request }) => {
  const errors: string[] = []
  page.on("pageerror", (e) => errors.push(String(e)))
  const ann = await newUser(request, "ann")
  const ben = await newUser(request, "ben")
  await api(request, ann.token, "POST", `/users/${ben.name}/follow`)
  await api(request, ben.token, "POST", `/users/${ann.name}/follow`)
  const conv = (await api(request, ben.token, "POST", "/conversations/direct", { username: ann.name })).conversation
  await api(request, ben.token, "POST", `/conversations/${conv.id}/messages`, { text: `Hallo @${ann.name}, Hyperraum wartet!` })
  const img = Buffer.from(PNG.split(",")[1], "base64")
  const up = await request.post("/api/media", { headers: { Authorization: `Bearer ${ben.token}`, "Content-Type": "image/png" }, data: img })
  const media = (await up.json()).media
  await api(request, ben.token, "POST", "/posts", { text: "Erster Post", media_ids: [media.id] })

  await openApp(page, ann.token)
  const shot = async (name: string) => { await page.waitForTimeout(900); await page.screenshot({ path: `test-results/tour/${name}.png` }) }
  await shot("01-home")
  await tab(page, "rules").click(); await shot("02-rules")
  await page.locator("text=ENTSCHL.").first().click().catch(() => {}); await shot("03-rules-dec")
  await tab(page, "chats").click(); await expect(page.getByText(ben.name).first()).toBeVisible(); await shot("04-chats")
  await page.getByText(ben.name).first().click(); await expect(page.getByText("Erster").or(page.getByText("Hallo")).first()).toBeVisible(); await shot("05-chat")
  await page.locator(".topbar button[aria-label=back]").click()
  await tab(page, "feed").click(); await expect(page.getByText("Erster Post")).toBeVisible(); await shot("06-feed")
  await page.getByText("Erster Post").click().catch(() => {})
  await page.locator("button[aria-label=comments]").first().click(); await shot("07-post")
  await page.locator(".topbar button[aria-label=back]").click()
  await page.locator("button[aria-label=leaderboard]").click(); await shot("08-leaderboard")
  await page.locator(".topbar button[aria-label=back]").click()
  await tab(page, "profile").click(); await shot("09-profile")
  await page.locator("button[aria-label=settings]").click(); await shot("10-settings")
  expect(errors).toEqual([])
})
