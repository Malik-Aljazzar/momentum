// One of the Nine: the title icons in the profile open the title screen; the rankings' "The Nine" tab lists the
// holders; while the Larper election runs it has its own bottom tab (server/nine.py). The e2e server's database is
// shared by all specs, so the player here makes sure to be the Yapper (more comments than anyone else).
import { expect, test } from "@playwright/test"
import { api, newUser, openApp, tab } from "./helpers"

const COMMENTS = 25

test("titles in the profile, the Nine tab and the Larper election", async ({ page, request }) => {
  const ann = await newUser(request, "ann")
  const ben = await newUser(request, "ben")
  const post = (await api(request, ann.token, "POST", "/posts", { text: "Hallo Welt" })).post
  for (let i = 0; i < COMMENTS; i++) await api(request, ann.token, "POST", `/posts/${post.id}/comments`, { text: `Senf ${i}` })
  // the counted tables are kept a few seconds on the server
  await expect.poll(async () => (await api(request, ben.token, "GET", `/users/${ann.name}`)).profile.titles,
    { timeout: 20_000 }).toContain("yapper")

  // ann's profile: the Yapper's critter; tapping it opens the title screen with the tale, the holder and my place
  await openApp(page, ann.token)
  await tab(page, "profile").click()
  const icon = page.getByRole("button", { name: "Yapper" })
  await expect(icon).toBeVisible()
  expect((await icon.boundingBox())!.height).toBeGreaterThan(20)
  await icon.click()
  const screen = page.getByRole("dialog", { name: "Yapper" })
  await expect(screen).toBeVisible()
  await expect(screen).toContainText(/one of the nine/i)
  await expect(screen).toContainText("Sonnenuntergang kommentiert", { timeout: 8000 })
  await expect(screen).toContainText("So bekommst du den Titel")
  await expect(screen).toContainText(ann.name)
  await expect(screen).toContainText(`Du: ${COMMENTS} Kommentare · Platz 1 weltweit`)
  await page.locator("button[aria-label=close]").click()
  await expect(screen).toBeHidden()

  // the rankings' tab: all nine, the Yapper is ann
  await tab(page, "home").click()
  await page.locator("button[aria-label=leaderboard]").click()
  await page.getByRole("button", { name: "The Nine", exact: true }).click()
  const yapper = page.getByRole("button", { name: "Yapper" }).filter({ hasText: "Kommentare" })
  await expect(yapper).toContainText(ann.name)
  await expect(yapper).toContainText(`${COMMENTS} Kommentare`)
  await expect(page.getByRole("button", { name: "GOAT" }).last()).toBeVisible()

  // the election has its own tab while it runs (no Larper yet: open from the start) - the animated Larper
  await page.locator(".topbar button[aria-label=back]").click()
  const before = (await api(request, ann.token, "GET", "/nine/election")).election
  expect(before.open).toBe(true)
  const electionTab = tab(page, "election")
  await expect(electionTab).toBeVisible()
  expect((await electionTab.boundingBox())!.height).toBeGreaterThan(20)
  await electionTab.click()
  await expect(page.locator(".topbar").getByText("Larper-Wahl", { exact: true })).toBeVisible()
  await expect(page.getByText(`${before.voters} von 10 nötigen Stimmen`)).toBeVisible()
  await page.getByPlaceholder("Spieler suchen").fill(ben.name)
  const vote = page.getByRole("button", { name: "Wählen" }).first()
  await expect(vote).toBeVisible()
  await vote.click()
  await expect(page.getByText(`${before.voters + 1} von 10 nötigen Stimmen`)).toBeVisible()
  await expect(page.getByText(`Deine Stimme: @${ben.name}`)).toBeVisible()
  const e = (await api(request, ann.token, "GET", "/nine/election")).election
  expect(e.my_vote.username).toBe(ben.name)
  expect(e.top.find((c: { user: { username: string } }) => c.user.username === ben.name).votes).toBe(1)
  // the home card leads to the same tab
  await tab(page, "home").click()
  await page.getByRole("button", { name: /Larper-Wahl läuft/ }).click()
  await expect(page.getByText(`Deine Stimme: @${ben.name}`)).toBeVisible()
})
