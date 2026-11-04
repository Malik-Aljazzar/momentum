// End-to-end encrypted chats in the PWA: keys made on the first start, messages and photos only readable in
// the apps (the server keeps ciphertext), the recovery key unlocking a new device, safety codes.
import { devices, expect, test, type Browser } from "@playwright/test"
import { api, newUser, openApp, tab } from "./helpers"

async function phone(browser: Browser) {
  const ctx = await browser.newContext({ ...devices["iPhone 13"], baseURL: test.info().project.use.baseURL })
  return ctx.newPage()
}

test("encrypted chat: text and photo readable only in the apps, a new device unlocks with the recovery key", async ({ browser, request }) => {
  const ann = await newUser(request, "ann")
  const ben = await newUser(request, "ben")
  await api(request, ann.token, "POST", `/users/${ben.name}/follow`)
  await api(request, ben.token, "POST", `/users/${ann.name}/follow`)
  const conv = (await api(request, ann.token, "POST", "/conversations/direct", { username: ben.name })).conversation
  await api(request, ann.token, "PUT", `/conversations/${conv.id}/e2e`, { on: true })      // the test server's chats start plain

  // both phones make their key on the first start
  const a = await phone(browser), b = await phone(browser)
  await openApp(a, ann.token)
  await openApp(b, ben.token)
  for (const u of [ann, ben]) await expect.poll(async () => (await api(request, u.token, "GET", "/e2e/me")).public).toBeTruthy()

  // ann writes; the server only has ciphertext, ben's phone opens it
  await tab(a, "chats").click()
  await a.getByText(ben.name).first().click()
  await expect(a.locator(".system-line")).toContainText("eingeschaltet")
  await expect(a.locator(".e2e-note")).toBeVisible()
  await a.getByPlaceholder("Nachricht…").fill("Streng geheim")
  await a.locator("button[aria-label=send]").click()
  await expect(a.getByText("Streng geheim")).toBeVisible()
  const stored = (await api(request, ben.token, "GET", `/conversations/${conv.id}/messages`)).messages.at(-1)
  expect(stored.text).toBe("")
  expect(JSON.stringify(stored)).not.toContain("Streng geheim")
  await tab(b, "chats").click()
  await expect(b.getByText(/Streng geheim/).first()).toBeVisible({ timeout: 15_000 })     // the chat list's preview
  await b.getByText(ann.name).first().click()
  await expect(b.locator(".bubble").getByText("Streng geheim")).toBeVisible()

  // a photo: encrypted on the phone, shown decrypted on the other one - in the bubble the full picture, not
  // the 480 px thumb (blurry on a 240 px bubble)
  const photo = Buffer.from((await a.evaluate(() => {
    const c = document.createElement("canvas")
    c.width = 1000; c.height = 750
    const g = c.getContext("2d")!
    g.fillStyle = "#38b764"; g.fillRect(0, 0, 1000, 750)
    return c.toDataURL("image/png")
  })).split(",")[1], "base64")
  const chooser = a.waitForEvent("filechooser")
  await a.locator("button[aria-label=photo]").click()
  await (await chooser).setFiles({ name: "p.png", mimeType: "image/png", buffer: photo })
  await a.locator("button[aria-label='send photos']").click()
  const img = b.locator(".chat-photo img").first()
  await expect.poll(() => img.evaluate((i: HTMLImageElement) => i.src.startsWith("blob:") && i.complete ? i.naturalWidth : 0), { timeout: 20_000 }).toBe(1000)

  // safety codes: what ben sees as ann's code is what ann sees as her own
  await b.locator("button[aria-label='chat info']").click()
  await expect(b.getByText("Sicherheitscodes")).toBeVisible()
  const annCode = b.locator(".safety-code", { hasText: ann.name }).locator("span").nth(1)
  await expect(annCode).not.toHaveText("—")                 // "—" until the code is computed
  const annOnBen = await annCode.innerText()
  await a.locator("button[aria-label='chat info']").click()
  await expect(a.locator(".safety-code", { hasText: "Dein Code" }).locator("span").nth(1)).toHaveText(annOnBen)

  // the recovery key, from the reminder in the chat list
  await a.goto("/app/")
  await tab(a, "chats").click()
  await a.locator(".e2e-card").click()
  const code = (await a.locator(".recovery-code span").allInnerTexts()).join(" ")
  expect(code.split(" ")).toHaveLength(13)
  await a.getByRole("button", { name: "Ich habe ihn notiert" }).click()
  await expect(a.locator(".e2e-card")).toBeHidden()

  // a new phone for ann: locked until the recovery key is typed in, then the old message opens
  const c = await phone(browser)
  await openApp(c, ann.token)
  await tab(c, "chats").click()
  await expect(c.locator(".e2e-card")).toContainText("entsperren")
  await c.getByText(ben.name).first().click()
  await expect(c.locator(".sealed").first()).toBeVisible()
  await c.getByRole("button", { name: "Chats entsperren", exact: true }).click()
  await c.getByPlaceholder("ABCD EFGH …").fill(code.toLowerCase())
  await c.getByRole("button", { name: "Entsperren", exact: true }).click()
  await expect(c.locator(".bubble").getByText("Streng geheim")).toBeVisible({ timeout: 15_000 })
  await b.locator("button[aria-label=back]").last().click()            // ben: from the chat info back to the chat
  await c.getByPlaceholder("Nachricht…").fill("vom neuen Handy")
  await c.locator("button[aria-label=send]").click()
  await expect(b.locator(".bubble").getByText("vom neuen Handy")).toBeVisible({ timeout: 15_000 })
})

test("encryption switched off in the chat info: plain again, the chat shows who did it", async ({ browser, request }) => {
  const ann = await newUser(request, "ann")
  const ben = await newUser(request, "ben")
  await api(request, ben.token, "POST", `/users/${ann.name}/follow`)
  const conv = (await api(request, ann.token, "POST", "/conversations/direct", { username: ben.name })).conversation
  await api(request, ann.token, "PUT", `/conversations/${conv.id}/e2e`, { on: true })
  const a = await phone(browser)
  await openApp(a, ann.token)
  await tab(a, "chats").click()
  await a.getByText(ben.name).first().click()
  await a.locator("button[aria-label='chat info']").click()
  await a.locator(".encryption button[role=switch]").first().click()
  await expect.poll(async () => (await api(request, ann.token, "GET", `/conversations/${conv.id}`)).conversation.e2e).toBe(false)
  await a.locator("button[aria-label=back]").last().click()
  await expect(a.locator(".system-line").first()).toContainText("Du hast die Ende-zu-Ende-Verschlüsselung ausgeschaltet")   // newest first (reversed list)
  await a.getByPlaceholder("Nachricht…").fill("offen")
  await a.locator("button[aria-label=send]").click()
  await expect.poll(async () => (await api(request, ben.token, "GET", `/conversations/${conv.id}/messages`)).messages.at(-1).text).toBe("offen")
})
