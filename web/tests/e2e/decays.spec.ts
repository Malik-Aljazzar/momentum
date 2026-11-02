// Shared decays end to end: follow a person's public decay from the profile, group decays
// made by admins and followed by members, a challenge that brings its decay.
import { expect, test } from "@playwright/test"
import { api, newUser, openApp, tab } from "./helpers"

const DECAY = { base: 80, threshold: 300, catalyst_mode: "none", catalyst_a: 0, catalyst_b: 1.5 }

test("follow a person's public decay from the profile, then stop", async ({ page, request }) => {
  const ann = await newUser(request, "ann")
  const ben = await newUser(request, "ben")
  await api(request, ann.token, "PUT", "/decay", { ...DECAY, public: true })
  await openApp(page, ben.token)
  await tab(page, "feed").click()
  await page.getByText("Leute finden").first().click()
  await page.getByPlaceholder(/Name/).fill(ann.name)
  await page.getByText(ann.name).first().click()
  await page.locator(".route button[aria-label=rules]").click()
  await page.locator(".decay-row").first().click()
  const sheet = page.locator(".sheet")
  await expect(sheet.getByText("Du hast immer genau einen Decay", { exact: false })).toBeVisible()
  await sheet.getByRole("button", { name: "Folgen" }).click()
  await expect(sheet.getByText("Du folgst diesem Decay", { exact: false })).toBeVisible()
  let st = (await api(request, ben.token, "GET", "/state")).state
  expect(st.decay.follow).toMatchObject({ kind: "user", label: ann.name })
  expect(st.decay.base).toBe(80)
  // my decay card says where it comes from and opens the followed one
  await page.locator(".sheet-scrim").click({ position: { x: 20, y: 20 }, force: true })
  await tab(page, "rules").click()
  await page.locator("text=ENTSCHL.").first().click()
  await expect(page.locator(".decay-origin").first()).toContainText(ann.name)
  await page.locator(".decay-origin").first().click()
  await page.locator(".sheet").getByRole("button", { name: "Abo beenden" }).click()
  await expect.poll(async () => (await api(request, ben.token, "GET", "/state")).state.decay.follow).toBeNull()
  st = (await api(request, ben.token, "GET", "/state")).state
  expect(st.decay.base).not.toBe(80)
})

test("group: an admin creates a decay, a member follows it", async ({ page, request }) => {
  const ann = await newUser(request, "ann")
  const ben = await newUser(request, "ben")
  await api(request, ann.token, "POST", `/users/${ben.name}/follow`)
  await api(request, ben.token, "POST", `/users/${ann.name}/follow`)
  const conv = (await api(request, ann.token, "POST", "/conversations/group", { name: `D${Date.now() % 100000}`, usernames: [ben.name] })).conversation
  await openApp(page, ann.token)
  await tab(page, "chats").click()
  await page.locator(".list-item", { hasText: conv.name }).first().click()
  await page.locator(".topbar button[aria-label=group]").click()
  await page.locator(".route button[aria-label=rules]").click()
  await page.locator("button[aria-label='new decay']").click()
  await page.getByPlaceholder("Name des Decays").fill("Hart")
  await page.locator(".sheet").getByRole("button", { name: "Speichern" }).click()
  await expect(page.locator(".decay-row", { hasText: "Hart" })).toBeVisible()
  const decays = (await api(request, ben.token, "GET", `/conversations/${conv.id}/decays`)).decays
  expect(decays).toHaveLength(1)

  // the challenge picks it; whoever joins follows it while taking part
  await api(request, ann.token, "POST", `/conversations/${conv.id}/rules`, { kind: "acc", name: "Lauf", icon: "star", mode: "constant", base: 50 })
  const rule = (await api(request, ann.token, "GET", `/conversations/${conv.id}/rules`)).rules[0]
  const ch = (await api(request, ann.token, "POST", `/conversations/${conv.id}/challenges`, {
    title: "Woche", mode: "ranking", days: 7, start_in: 0, reward1: 10, rules: [{ rule_id: rule.id }], decay_id: decays[0].id,
  })).challenge
  await api(request, ben.token, "POST", `/challenges/${ch.id}/join`)
  const st = (await api(request, ben.token, "GET", "/state")).state
  expect(st.decay.follow).toMatchObject({ kind: "group", decay_id: decays[0].id })
  expect(st.decay.locked_by).toBe("Woche")
})

test("new challenge: pick one of the group's decays", async ({ page, request }) => {
  const ann = await newUser(request, "ann")
  const ben = await newUser(request, "ben")
  await api(request, ann.token, "POST", `/users/${ben.name}/follow`)
  await api(request, ben.token, "POST", `/users/${ann.name}/follow`)
  const conv = (await api(request, ann.token, "POST", "/conversations/group", { name: `C${Date.now() % 100000}`, usernames: [ben.name] })).conversation
  await api(request, ann.token, "POST", `/conversations/${conv.id}/rules`, { kind: "acc", name: "Lauf", icon: "star", mode: "constant", base: 50 })
  await api(request, ann.token, "POST", `/conversations/${conv.id}/decays`, { ...DECAY, name: "Sanft" })
  await openApp(page, ann.token)
  await tab(page, "chats").click()
  await page.locator(".list-item", { hasText: conv.name }).first().click()
  await page.locator(".topbar button[aria-label=group]").click()
  await page.locator("button[aria-label='new challenge']").click()
  await page.getByPlaceholder(/Name der Challenge|Challenge/).first().fill("Woche")
  await page.getByText("Lauf").click()
  await page.locator(".decay-pick", { hasText: "Sanft" }).click()
  await page.getByRole("button", { name: /Erstellen/i }).click()
  await expect(page.locator(".decay-row", { hasText: "Sanft" })).toBeVisible()      // the challenge's own page
  const list = (await api(request, ann.token, "GET", `/conversations/${conv.id}/challenges`)).challenges
  const full = (await api(request, ann.token, "GET", `/challenges/${list[0].id}`)).challenge
  expect(full.decay.name).toBe("Sanft")
})
