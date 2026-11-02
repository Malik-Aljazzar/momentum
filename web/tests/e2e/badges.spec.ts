// The King of Reporters: the profile of whoever had the most reports implemented shows the badge; tapping it
// says why (count, bugs / requests, the implemented reports).
import { expect, test } from "@playwright/test"
import { api, newUser, openApp, tab } from "./helpers"

test("the King of Reporters badge and its explanation", async ({ page, request }) => {
  const ann = await newUser(request, "ann")
  const ben = await newUser(request, "ben")
  await api(request, ann.token, "POST", "/reports", { kind: "bug", text: "Chat stürzt beim Foto ab" })
  await api(request, ann.token, "POST", "/reports", { kind: "feature", text: "Dunkler Modus bitte" })
  await api(request, ben.token, "POST", "/reports", { kind: "bug", text: "Nur einer" })
  // nothing implemented yet: no crown anywhere
  expect((await api(request, ben.token, "GET", `/users/${ann.name}`)).profile.badges).toEqual([])
  for (const u of [ann, ben]) {                                        // the admin app's job (test server only)
    const done = await request.post("/api/test/reports/done", { data: { username: u.name } })
    expect(done.ok()).toBeTruthy()
  }

  await openApp(page, ann.token)
  await tab(page, "profile").click()
  const badge = page.getByRole("button", { name: "King of Reporters" })
  await expect(badge).toBeVisible()
  const box = (await badge.boundingBox())!
  expect(box.height).toBeGreaterThan(8)                                 // drawn, not a 0 px box
  await badge.click()
  const sheet = page.getByRole("dialog", { name: "King of Reporters" })
  await expect(sheet).toBeVisible()
  await expect(sheet).toContainText("2 Meldungen von hier wurden umgesetzt – 1 Bugs, 1 Wünsche")
  await expect(sheet).toContainText("Die Krone trägt")
  await expect(sheet).not.toContainText("Chat stürzt beim Foto ab")       // what was reported stays private

  // ben's profile (one implemented report) has no crown
  expect((await api(request, ann.token, "GET", `/users/${ben.name}`)).profile.badges).toEqual([])
})
