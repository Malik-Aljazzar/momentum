import { expect, test } from "@playwright/test"
import { api, longPress, newUser, openApp, tab } from "./helpers"

test("sign up, choose a username, see the start screen", async ({ page }) => {
  await page.addInitScript(() => localStorage.setItem("language", "de"))
  await page.goto("/app/")
  await page.getByText("Neu hier? Konto erstellen").click()
  const name = `neu${Date.now().toString(36)}`
  await page.getByPlaceholder("E-Mail").fill(`${name}@example.com`)
  await page.getByPlaceholder("Passwort").fill("geheim1")
  await page.getByRole("button", { name: "Konto erstellen", exact: true }).click()
  await page.getByPlaceholder("@name").fill(name)
  await page.getByRole("button", { name: "Weiter" }).click()
  await expect(page.locator("nav.tabbar")).toBeVisible()
  await expect(page.getByText(name).first()).toBeVisible()
  await expect(page.getByText("Nächster Meilenstein")).toBeVisible()
})

test("tapping an accelerator raises the momentum", async ({ page, request }) => {
  const { token } = await newUser(request)
  await openApp(page, token)
  await tab(page, "rules").click()
  const card = page.locator('[data-rule="Aufgabe erledigt"] > div').first()
  await card.click()
  await expect.poll(async () => (await api(request, token, "GET", "/state")).state.momentum).toBe(100)
  await tab(page, "home").click()
  await expect(page.getByText("+100").first()).toBeVisible()
})

test("create a rule with the cloze editor", async ({ page, request }) => {
  const { token } = await newUser(request)
  await openApp(page, token)
  await tab(page, "rules").click()
  await page.getByText("Neuer Beschleuniger").click()
  await page.getByPlaceholder("Sport machen").fill("Lesen")
  await page.getByRole("button", { name: "Speichern" }).click()
  await expect(page.locator('[data-rule="Lesen"]')).toBeVisible()
  const st = (await api(request, token, "GET", "/state")).state
  expect(st.rules.some((r: any) => r.name === "Lesen" && r.kind === "acc")).toBeTruthy()
})

test("one selected rule: the arrows sort it, bulk makes it public", async ({ page, request }) => {
  const { token } = await newUser(request)
  await openApp(page, token)
  await tab(page, "rules").click()
  const names = async () => (await api(request, token, "GET", "/state")).state.rules.filter((r: any) => r.kind === "acc").map((r: any) => r.name)
  const before = await names()
  await longPress(page, page.locator(`[data-rule="${before[1]}"] > div`).first())
  await expect(page.getByText("1 ausgewählt")).toBeVisible()
  await page.locator(`[data-rule="${before[1]}"] button[aria-label="▲"]`).click()
  await expect.poll(names, { timeout: 5000 }).toEqual([before[1], before[0], ...before.slice(2)])
  await page.getByRole("button", { name: "Öffentlich (1)" }).click()
  await expect.poll(async () => (await api(request, token, "GET", "/state")).state.rules.find((r: any) => r.name === before[1]).public).toBe(true)
})

test("a rule that can't be tapped shows the blocked finger and counts nothing", async ({ page, request }) => {
  const { token } = await newUser(request)
  await api(request, token, "POST", "/rules", { kind: "acc", name: "Nur automatisch", icon: "star", mode: "constant", base: 50, tappable: false })
  await openApp(page, token)
  await tab(page, "rules").click()
  await page.locator('[data-rule="Nur automatisch"] > div > div').first().click()
  await expect(page.locator('[data-rule="Nur automatisch"] img[src*="ui_notap"]')).toBeVisible()
  await page.waitForTimeout(800)
  expect((await api(request, token, "GET", "/state")).state.momentum).toBe(0)
})

test("custom time: any number 0..365 in hours or days (auto-tap after 12 hours)", async ({ page, request }) => {
  const { token } = await newUser(request)
  await openApp(page, token)
  await tab(page, "rules").click()
  await page.getByText("Neuer Beschleuniger").click()
  await page.getByPlaceholder("Sport machen").fill("Wasser")
  await page.locator("select").filter({ has: page.locator('option[value="custom"]') }).first().selectOption("custom")
  const dialog = page.getByRole("dialog", { name: "Zeit wählen (0–365)" })
  await dialog.getByPlaceholder("0–365").fill("12")
  await dialog.getByRole("button", { name: "Std." }).click()
  await dialog.getByRole("button", { name: "Fertig" }).click()
  await expect(page.getByText("12 Stunden")).toBeVisible()
  await page.getByRole("button", { name: "Speichern" }).click()
  await expect.poll(async () => (await api(request, token, "GET", "/state")).state.rules.find((r: any) => r.name === "Wasser"))
    .toMatchObject({ idle_days: 12, idle_unit: "h" })
})

test("coming back from the Google sign-in logs in and clears the address", async ({ page, request }) => {
  const { token } = await newUser(request)
  await page.addInitScript(() => localStorage.setItem("language", "de"))
  await page.goto(`/app/#token=${token}`)
  await expect(page.locator("nav.tabbar")).toBeVisible()
  expect(new URL(page.url()).hash).toBe("")
  // not set up on this server: no Google button on the login screen
  const fresh = await page.context().browser()!.newPage()
  await fresh.addInitScript(() => localStorage.setItem("language", "de"))
  await fresh.goto(page.url())
  await expect(fresh.getByText("Neu hier? Konto erstellen")).toBeVisible()
  await expect(fresh.getByText("Mit Google fortfahren")).toHaveCount(0)
  await fresh.close()
})
