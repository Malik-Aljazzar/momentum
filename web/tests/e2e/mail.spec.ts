// E-mails with codes (server/mail.py; the test server keeps them, /api/test/mail): a new password by code, confirming
// an address typed wrong at sign-up.
import { expect, test, type APIRequestContext } from "@playwright/test"
import { mailedCode, NEWEST_RELEASE, newUser } from "./helpers"

/** A code to [email] other than [before] (the test server keeps only the last one per address). */
async function nextCode(request: APIRequestContext, email: string, before: string) {
  let code = before
  await expect.poll(async () => (code = await mailedCode(request, email))).not.toBe(before)
  return code
}

test("forgot password: a code to the confirmed address, a new password, logged in", async ({ page, request }) => {
  const ann = await newUser(request, "ann")
  const email = `${ann.name}@example.com`
  const before = await mailedCode(request, email)                 // the sign-up's confirm code
  await page.addInitScript(() => localStorage.setItem("language", "de"))
  await page.goto("/app/")
  await page.getByRole("button", { name: "Passwort vergessen?" }).click()
  await page.locator(".forgot").getByPlaceholder("E-Mail oder Benutzername").fill(ann.name)
  await page.getByRole("button", { name: "Code senden" }).click()
  await expect(page.locator(".forgot")).toContainText("ist jetzt ein Code unterwegs")
  await page.getByPlaceholder("6-stelliger Code").fill(await nextCode(request, email, before))
  await page.getByPlaceholder("Neues Passwort").fill("neuespw1")
  await page.screenshot({ path: test.info().outputPath("forgot.png") })
  await page.getByRole("button", { name: "Passwort speichern" }).click()
  await expect(page.locator("nav.tabbar")).toBeVisible()
  // the old password is gone, the new one works; the old session ended
  expect((await request.post("/api/auth/login", { data: { login: ann.name, password: "geheim1" } })).status()).toBe(401)
  expect((await request.post("/api/auth/login", { data: { login: ann.name, password: "neuespw1" } })).ok()).toBe(true)
  expect((await request.get("/api/state", { headers: { Authorization: `Bearer ${ann.token}` } })).status()).toBe(401)
})

test("an address typed wrong at sign-up: another one gets the code, confirmed it opens the app", async ({ page, request }) => {
  const name = `typo${Date.now().toString(36)}`
  const r = await request.post("/api/auth/register", { data: { email: `${name}@exmaple.com`, password: "geheim1", language: "de" } })
  const { token } = await r.json()
  await page.addInitScript(([t, seen]) => {
    localStorage.setItem("token", JSON.stringify(t)); localStorage.setItem("language", "de"); localStorage.setItem("changelog_seen", JSON.stringify(seen))
  }, [token, NEWEST_RELEASE])
  await page.goto("/app/")
  const confirm = page.locator(".confirm-email")
  await expect(confirm).toContainText(`${name}@exmaple.com`)
  // locked meanwhile
  expect((await request.get("/api/feed", { headers: { Authorization: `Bearer ${token}` } })).status()).toBe(403)
  await confirm.getByRole("button", { name: "Andere Adresse verwenden" }).click()
  await confirm.getByPlaceholder("Neue E-Mail-Adresse").fill(`${name}@example.com`)
  await confirm.getByRole("button", { name: "Code senden" }).click()
  await expect(confirm).toContainText(`Code an ${name}@example.com geschickt`)
  await confirm.getByPlaceholder("6-stelliger Code").fill(await mailedCode(request, `${name}@example.com`))
  await confirm.getByRole("button", { name: "Bestätigen" }).click()
  await expect(page.getByPlaceholder("@name")).toBeVisible()                  // next: the username
  const st = (await (await request.get("/api/state", { headers: { Authorization: `Bearer ${token}` } })).json()).state
  expect(st.user.email).toBe(`${name}@example.com`)
  expect(st.user.email_confirm).toBeNull()
})

test("five wrong passwords lock the account: the login screen opens the new password, the code is mailed", async ({ page, request }) => {
  const ann = await newUser(request, "ann")
  const email = `${ann.name}@example.com`
  const before = await mailedCode(request, email)
  await page.addInitScript(() => localStorage.setItem("language", "de"))
  await page.goto("/app/")
  await page.getByPlaceholder("E-Mail oder Benutzername").fill(ann.name)
  const attempt = async (password: string) => {
    await page.getByPlaceholder("Passwort", { exact: true }).fill(password)
    const answered = page.waitForResponse("**/api/auth/login")
    await page.getByPlaceholder("Passwort", { exact: true }).press("Enter")
    return (await answered).status()
  }
  for (let i = 1; i <= 4; i++) expect(await attempt(`falsch${i}`)).toBe(401)
  await expect(page.locator(".toast")).toContainText("noch 1 Versuch, dann wird dein Konto gesperrt")    // warned
  expect(await attempt("falsch5")).toBe(423)
  // locked: straight to the new password, with the server's words and the code field
  const forgot = page.locator(".forgot")
  await expect(forgot).toContainText("gesperrt")
  await expect(forgot.getByPlaceholder("E-Mail oder Benutzername")).toHaveValue(ann.name)
  await page.screenshot({ path: test.info().outputPath("locked.png") })
  expect((await request.post("/api/auth/login", { data: { login: ann.name, password: "geheim1" } })).status()).toBe(423)
  await forgot.getByPlaceholder("6-stelliger Code").fill(await nextCode(request, email, before))
  await forgot.getByPlaceholder("Neues Passwort").fill("wiederda1")
  await forgot.getByRole("button", { name: "Passwort speichern" }).click()
  await expect(page.locator("nav.tabbar")).toBeVisible()
  const again = await request.post("/api/auth/login", { data: { login: ann.name, password: "wiederda1" } })
  expect(again.status(), await again.text()).toBe(200)
})
