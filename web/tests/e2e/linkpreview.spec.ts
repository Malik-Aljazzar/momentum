// Link previews in chats, like WhatsApp (server/linkpreview.py; the test server makes them up for
// example.com without the internet): shown while typing, sent along, a card in the bubble - in a plain chat
// and inside the envelope of an encrypted one; x sends without.
import { devices, expect, test, type Browser } from "@playwright/test"
import { api, newUser, openApp, tab } from "./helpers"

async function phone(browser: Browser) {
  const ctx = await browser.newContext({ ...devices["iPhone 13"], baseURL: test.info().project.use.baseURL })
  return ctx.newPage()
}

test("a link's preview goes with the message, plain and encrypted", async ({ browser, request }) => {
  const ann = await newUser(request, "ann")
  const ben = await newUser(request, "ben")
  await api(request, ann.token, "POST", `/users/${ben.name}/follow`)
  await api(request, ben.token, "POST", `/users/${ann.name}/follow`)
  const conv = (await api(request, ann.token, "POST", "/conversations/direct", { username: ben.name })).conversation
  const a = await phone(browser), b = await phone(browser)
  await openApp(a, ann.token)
  await openApp(b, ben.token)

  // plain chat: typing a link shows its preview above the input field, it goes with the message
  await tab(a, "chats").click()
  await a.getByText(ben.name).first().click()
  const field = a.getByPlaceholder("Nachricht…")
  await field.fill("schau mal www.example.com/seite")
  await expect(a.locator(".draft-link")).toContainText("Example page")
  await expect(a.locator(".draft-link")).toContainText("example.com")
  await a.locator("button[aria-label=send]").click()
  await expect(a.locator(".draft-link")).toBeHidden()
  const card = a.locator(".bubble .link-card").first()
  await expect(card).toContainText("Example page")
  await expect(card).toHaveAttribute("href", "https://www.example.com/seite")
  // a wide picture sits over the text, at the card's full width (not a 0 px box)
  const pic = card.locator("img")
  await expect.poll(() => pic.evaluate((i: HTMLImageElement) => (i.complete ? i.naturalWidth : 0))).toBe(480)
  expect((await pic.boundingBox())!.width).toBeGreaterThan(200)
  const stored = (await api(request, ben.token, "GET", `/conversations/${conv.id}/messages`)).messages.at(-1)
  expect(stored.link.title).toBe("Example page")

  // x: this message goes without one; a page without a preview shows none
  await field.fill("ohne https://square.example.com/x")
  await expect(a.locator(".draft-link")).toContainText("Example page")
  await a.locator("button[aria-label='without preview']").click()
  await expect(a.locator(".draft-link")).toBeHidden()
  await a.locator("button[aria-label=send]").click()
  await expect(a.locator(".bubble").getByText("ohne")).toBeVisible()
  expect((await api(request, ben.token, "GET", `/conversations/${conv.id}/messages`)).messages.at(-1).link).toBeNull()
  await field.fill("nichts da https://nothing.test/")
  await a.waitForTimeout(1500)
  await expect(a.locator(".draft-link")).toBeHidden()
  await field.fill("")

  // ben sees the card
  await tab(b, "chats").click()
  await b.getByText(ann.name).first().click()
  await expect(b.locator(".bubble .link-card").first()).toContainText("Example page")

  // encrypted: the preview travels inside the envelope - the server can't read it, ben's phone opens it
  await api(request, ann.token, "PUT", `/conversations/${conv.id}/e2e`, { on: true })
  for (const u of [ann, ben]) await expect.poll(async () => (await api(request, u.token, "GET", "/e2e/me")).public).toBeTruthy()
  await a.reload()
  await tab(a, "chats").click()
  await a.getByText(ben.name).first().click()
  await expect(a.locator(".system-line")).toContainText("eingeschaltet")
  await field.fill("geheimer Tipp https://square.example.com/tipp")
  await expect(a.locator(".draft-link")).toContainText("Example page")
  await a.locator("button[aria-label=send]").click()
  await expect(a.locator(".bubble .link-card.small")).toHaveCount(1)          // a square picture: beside the text
  const sealed = (await api(request, ben.token, "GET", `/conversations/${conv.id}/messages`)).messages.at(-1)
  expect(sealed.link).toBeNull()
  expect(JSON.stringify(sealed)).not.toContain("Example page")
  await expect(b.locator(".bubble .link-card.small")).toContainText("square.example.com", { timeout: 15_000 })
})
