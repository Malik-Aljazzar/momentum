// Pixel emojis: the picker at text inputs, an emoji as a rule's icon (drawn by the pixel emoji font).
import { expect, test } from "@playwright/test"
import { api, newUser, openApp, tab } from "./helpers"

test("the emoji picker puts an emoji into a chat message", async ({ page, request }) => {
  const ann = await newUser(request, "ann")
  const ben = await newUser(request, "ben")
  await api(request, ann.token, "POST", `/users/${ben.name}/follow`)
  await api(request, ben.token, "POST", `/users/${ann.name}/follow`)
  const conv = (await api(request, ann.token, "POST", "/conversations/direct", { username: ben.name })).conversation
  await openApp(page, ann.token)
  await tab(page, "chats").click()
  await page.getByText(ben.name).first().click()
  const input = page.getByPlaceholder("Nachricht…")
  await input.fill("Los ")
  await page.locator("button[aria-label=emoji]").click()
  const grid = page.getByTestId("emoji-grid")
  await expect(grid.locator(".emoji-cell").first()).toBeVisible()
  // the groups come from emoji.json; a search finds by the English name, the pick lands at the caret
  await page.getByPlaceholder("Emoji suchen (englisch)…").fill("fire")
  await grid.locator('.emoji-cell[aria-label="fire"]').click()          // not "heart on fire"
  await expect(input).toHaveValue("Los 🔥")
  // the pixel emoji font is loaded and used for the text
  expect(await page.evaluate(() => document.fonts.check('16px "Pixel Emoji"'))).toBe(true)
  await page.locator("button[aria-label=send]").click()
  await expect(page.getByText("Los 🔥")).toBeVisible()
  const msgs = (await api(request, ben.token, "GET", `/conversations/${conv.id}/messages`)).messages
  expect(msgs.map((m: any) => m.text)).toContain("Los 🔥")
})

test("an emoji can be a rule's icon", async ({ page, request }) => {
  const ann = await newUser(request, "ann")
  await api(request, ann.token, "POST", "/rules", { kind: "acc", name: "Feuer", icon: "🔥", mode: "constant", base: 50 })
  await openApp(page, ann.token)
  await tab(page, "rules").click()
  const card = page.locator(".emoji-icon", { hasText: "🔥" }).first()
  await expect(card).toBeVisible()
  const box = (await card.boundingBox())!
  expect(box.width).toBeGreaterThan(20)                      // drawn in the icon's box, not a 0 px span
  // the editor's icon grid offers the picker (its cell shows the chosen emoji); the new rule's card draws it
  await page.getByText("Neuer Beschleuniger").click()
  await page.locator('.sheet img[src*="/icons/star.png"]').first().click()
  await page.locator(".sheet button[aria-label=emoji]").first().click()
  await page.getByPlaceholder("Emoji suchen (englisch)…").fill("rocket")
  await page.getByTestId("emoji-grid").locator(".emoji-cell", { hasText: "🚀" }).first().click()
  await expect(page.locator(".sheet .emoji-icon", { hasText: "🚀" }).first()).toBeVisible()
  await page.getByPlaceholder("Sport machen").fill("Rakete")
  await page.getByRole("button", { name: "Speichern" }).click()
  await expect(page.locator('[data-rule="Rakete"] .emoji-icon', { hasText: "🚀" })).toBeVisible()
  const st = (await api(request, ann.token, "GET", "/state")).state
  expect(st.rules.find((r: any) => r.name === "Rakete").icon).toBe("🚀")
})
