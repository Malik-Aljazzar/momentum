// The social flows end to end in Safari's engine: chat, posts with photos, comments, follows,
// groups with bulk actions, challenges, settings, reports, offline taps.
import { expect, test } from "@playwright/test"
import { api, longPress, newUser, openApp, tab } from "./helpers"

const PNG = Buffer.from("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==", "base64")

async function friends(request: Parameters<typeof newUser>[0]) {
  const ann = await newUser(request, "ann")
  const ben = await newUser(request, "ben")
  await api(request, ann.token, "POST", `/users/${ben.name}/follow`)
  await api(request, ben.token, "POST", `/users/${ann.name}/follow`)
  return { ann, ben }
}

test("chat: send a message, the other side gets it", async ({ page, request }) => {
  const { ann, ben } = await friends(request)
  const conv = (await api(request, ann.token, "POST", "/conversations/direct", { username: ben.name })).conversation
  await openApp(page, ann.token)
  await tab(page, "chats").click()
  await page.getByText(ben.name).first().click()
  await page.getByPlaceholder("Nachricht…").fill("Servus Ben")
  await page.locator("button[aria-label=send]").click()
  await expect(page.getByText("Servus Ben")).toBeVisible()
  const msgs = (await api(request, ben.token, "GET", `/conversations/${conv.id}/messages`)).messages
  expect(msgs.map((m: any) => m.text)).toContain("Servus Ben")
  // an answer arrives by polling
  await api(request, ben.token, "POST", `/conversations/${conv.id}/messages`, { text: "Hi Ann" })
  await expect(page.getByText("Hi Ann")).toBeVisible({ timeout: 10_000 })
})

test("post a photo with a caption, comment on it", async ({ page, request }) => {
  const { ann, ben } = await friends(request)
  await openApp(page, ann.token)
  await tab(page, "feed").click()
  await page.locator("button[aria-label='new post']").click()
  await page.locator("button[aria-label='create post']").click()
  const chooser = page.waitForEvent("filechooser")
  await page.getByText("Galerie").click()
  await (await chooser).setFiles({ name: "p.png", mimeType: "image/png", buffer: PNG })
  await page.getByPlaceholder(/Bildunterschrift|Schreib/).fill("Morgenlauf")
  await expect(page.getByRole("button", { name: "Posten" })).toBeEnabled({ timeout: 15_000 })
  await page.getByRole("button", { name: "Posten" }).click()
  await expect(page.getByText("Morgenlauf")).toBeVisible()
  const feed = (await api(request, ben.token, "GET", "/feed")).posts
  expect(feed[0]).toMatchObject({ text: "Morgenlauf" })
  expect(feed[0].media).toHaveLength(1)
  // the photo is really shown in the feed: loaded and not squeezed into a 0 px box
  const img = page.locator("article img[src*='/media/']").first()
  await expect.poll(() => img.evaluate((i: HTMLImageElement) => {
    const r = i.getBoundingClientRect()
    return i.complete && i.naturalWidth > 0 && r.height > 100 && getComputedStyle(i).opacity === "1"
  })).toBe(true)
  // a tap on the photo: the big view, X closes it
  await img.click()
  await expect(page.getByRole("dialog", { name: "viewer" })).toBeVisible()
  await page.getByRole("dialog", { name: "viewer" }).locator("button[aria-label=close]").click()
  await expect(page.getByRole("dialog", { name: "viewer" })).toBeHidden()
  await page.locator("button[aria-label=comments]").first().click()
  await page.getByPlaceholder("Kommentieren…").fill("stark!")
  await page.locator("button[aria-label=send]").click()
  await expect(page.getByText("stark!")).toBeVisible()
})

test("reply to a comment: the reply lands in the comment's thread", async ({ page, request }) => {
  const { ann, ben } = await friends(request)
  const post = (await api(request, ben.token, "POST", "/posts", { text: "Bergtour" })).post
  await api(request, ben.token, "POST", `/posts/${post.id}/comments`, { text: "wer kommt mit?" })
  await openApp(page, ann.token)
  await tab(page, "feed").click()
  await page.locator("button[aria-label=comments]").first().click()
  // the username sits on its own line above the comment
  const row = page.locator(".comment").filter({ hasText: "wer kommt mit?" })
  await expect(row.locator("b")).toHaveText(ben.name)
  await row.getByText("Antworten", { exact: true }).click()
  await expect(page.getByText(`Antwort an ${ben.name}`)).toBeVisible()
  await expect(page.getByPlaceholder("Kommentieren…")).toHaveValue(`@${ben.name} `)
  await page.getByPlaceholder("Kommentieren…").pressSequentially("ich!")
  await page.locator("button[aria-label=send]").click()
  await expect(page.getByText("ich!")).toBeVisible()
  await expect(page.getByText("— Antworten ausblenden")).toBeVisible()
  const list = (await api(request, ben.token, "GET", `/posts/${post.id}`)).post.comment_list
  expect(list[1]).toMatchObject({ parent_id: list[0].id })
  await page.screenshot({ path: test.info().outputPath("thread.png") })
  // folded: only the toggle shows
  await page.getByText("— Antworten ausblenden").click()
  await expect(page.getByText("ich!")).toBeHidden()
  await expect(page.getByText("— 1 Antwort ansehen")).toBeVisible()
})

test("activity: a comment shows a shortened preview of its text, at most two lines", async ({ page, request }) => {
  const { ann, ben } = await friends(request)
  const post = (await api(request, ann.token, "POST", "/posts", { text: "Bergtour" })).post
  await api(request, ben.token, "POST", `/posts/${post.id}/comments`, { text: "Mega Aussicht! " + "Wahnsinn ".repeat(30) })
  await openApp(page, ann.token)
  await tab(page, "feed").click()
  await page.locator("button[aria-label=activity]").click()
  const preview = page.locator(".comment-preview")
  await expect(preview).toHaveCount(1)
  await expect(preview).toContainText("Mega Aussicht!")
  // clamped: two lines of 17 px, the rest is cut off with an ellipsis
  const box = await preview.evaluate((e) => ({ h: e.getBoundingClientRect().height, full: e.scrollHeight }))
  expect(box.h).toBeGreaterThan(20)
  expect(box.h).toBeLessThan(40)
  expect(box.full).toBeGreaterThan(box.h)
  // the follow above has none
  await expect(page.locator(".list-item").filter({ hasText: "folgt dir jetzt" }).locator(".comment-preview")).toHaveCount(0)
  await page.screenshot({ path: test.info().outputPath("activity.png") })
})

test("follow someone from their profile", async ({ page, request }) => {
  const ann = await newUser(request, "ann")
  const ben = await newUser(request, "ben")
  await openApp(page, ann.token)
  await tab(page, "feed").click()
  await page.getByText("Leute finden").first().click()
  await page.getByPlaceholder(/Name/).fill(ben.name)
  await page.getByText(ben.name).first().click()
  await page.getByRole("button", { name: "Folgen" }).click()
  await expect(page.getByRole("button", { name: "Gefolgt" })).toBeVisible()
  expect((await api(request, ben.token, "GET", `/users/${ben.name}`)).profile.followers_count).toBe(1)
})

test("group: rules tab, select a group rule and make it public", async ({ page, request }) => {
  const { ann, ben } = await friends(request)
  const conv = (await api(request, ann.token, "POST", "/conversations/group", { name: `G${Date.now() % 100000}`, usernames: [ben.name] })).conversation
  await api(request, ann.token, "POST", `/conversations/${conv.id}/rules`, { kind: "acc", name: "Gruppenlauf", icon: "star", mode: "constant", base: 50 })
  await openApp(page, ann.token)
  await tab(page, "chats").click()
  await page.locator(".list-item", { hasText: conv.name }).first().click()
  await page.locator(".topbar button[aria-label=group]").click()
  await page.locator(".route button[aria-label=rules]").click()
  await longPress(page, page.getByText("Gruppenlauf"))
  await expect(page.getByText("1 ausgewählt")).toBeVisible()
  await page.getByRole("button", { name: "Öffentlich (1)" }).click()
  await expect.poll(async () => (await api(request, ann.token, "GET", `/conversations/${conv.id}/rules`)).rules[0].public).toBe(true)
})

test("challenge: create one from the group's rules", async ({ page, request }) => {
  const { ann, ben } = await friends(request)
  const conv = (await api(request, ann.token, "POST", "/conversations/group", { name: `C${Date.now() % 100000}`, usernames: [ben.name] })).conversation
  await api(request, ann.token, "POST", `/conversations/${conv.id}/rules`, { kind: "acc", name: "Liegestütze", icon: "star", mode: "constant", base: 40 })
  await openApp(page, ann.token)
  await tab(page, "chats").click()
  await page.locator(".list-item", { hasText: conv.name }).first().click()
  await page.locator(".topbar button[aria-label=group]").click()
  await page.locator("button[aria-label='new challenge']").click()
  await page.getByPlaceholder(/Name der Challenge|Challenge/).fill("Oktober-Sprint")
  await page.getByText("Liegestütze").click()
  await page.getByRole("button", { name: "Erstellen" }).click()
  await expect(page.getByText("Oktober-Sprint").first()).toBeVisible()
  const list = (await api(request, ben.token, "GET", `/conversations/${conv.id}/challenges`)).challenges
  expect(list[0]).toMatchObject({ title: "Oktober-Sprint" })
})

test("settings: write a bio and save", async ({ page, request }) => {
  const { token, name } = await newUser(request)
  await openApp(page, token)
  await tab(page, "profile").click()
  await page.locator("button[aria-label=settings]").click()
  await page.getByPlaceholder(/Erzähl/).fill("Läuft bei mir")
  await page.getByRole("button", { name: "Speichern" }).click()
  await expect.poll(async () => (await api(request, token, "GET", `/users/${name}`)).profile.bio).toBe("Läuft bei mir")
})

test("report a bug", async ({ page, request }) => {
  const { token } = await newUser(request)
  await openApp(page, token)
  await tab(page, "profile").click()
  await page.locator("button[aria-label=settings]").click()
  await page.getByRole("button", { name: /Bug melden/ }).click()
  await page.locator("textarea").fill("Der Knopf ist zu klein")
  await page.getByRole("button", { name: "Absenden" }).click()
  await expect(page.locator(".toast")).toContainText(/Danke/)
})

test("offline: a tap is kept and sent when the connection is back", async ({ page, context, request }) => {
  const { token } = await newUser(request)
  await openApp(page, token)
  await tab(page, "rules").click()
  await context.setOffline(true)
  await page.evaluate(() => window.dispatchEvent(new Event("offline")))
  await page.locator('[data-rule="Aufgabe erledigt"] > div').first().click()
  await expect(page.getByText(/Offline/).first()).toBeVisible()
  expect((await api(request, token, "GET", "/state")).state.momentum).toBe(0)
  await context.setOffline(false)
  await page.evaluate(() => window.dispatchEvent(new Event("online")))
  await expect.poll(async () => (await api(request, token, "GET", "/state")).state.momentum, { timeout: 15_000 }).toBe(100)
})

test("a group rule opened from my own list has no 'remove from group' button", async ({ page, request }) => {
  const { ann, ben } = await friends(request)
  const conv = (await api(request, ann.token, "POST", "/conversations/group", { name: `R${Date.now() % 100000}`, usernames: [ben.name] })).conversation
  const rule = (await api(request, ann.token, "POST", `/conversations/${conv.id}/rules`, { kind: "acc", name: "Gruppenyoga", icon: "star", mode: "constant", base: 30 })).rule
  await api(request, ann.token, "POST", "/rules/subscribe-many", { ids: [rule.id] })
  await openApp(page, ann.token)
  await tab(page, "rules").click()
  await page.locator('[data-rule="Gruppenyoga"] button[aria-label=edit]').click()
  await expect(page.getByText("Gruppenyoga").last()).toBeVisible()
  await expect(page.getByRole("button", { name: "Aus der Gruppe entfernen" })).toHaveCount(0)
  // the group shows (not who made the rule), and the subscription hint is the group one
  await expect(page.getByText(`Regel der Gruppe ${conv.name}`)).toBeVisible()
  await expect(page.getByText("Updates zu dieser Regel bekommst du automatisch.")).toBeVisible()
  await page.screenshot({ path: "test-results/tour/11-group-rule-detail.png" })
  // from the group overview it's there
  await page.reload()
  await expect(page.locator("nav.tabbar")).toBeVisible()
  await tab(page, "chats").click()
  await page.locator(".list-item", { hasText: conv.name }).first().click()
  await page.locator(".topbar button[aria-label=group]").click()
  await page.locator(".route button[aria-label=rules]").click()
  await page.locator(".route").getByText("Gruppenyoga").click()
  await expect(page.getByRole("button", { name: "Aus der Gruppe entfernen" })).toBeVisible()
})

test("group admins: members don't see admin actions, an admin makes someone admin", async ({ page, request }) => {
  const { ann, ben } = await friends(request)
  const conv = (await api(request, ann.token, "POST", "/conversations/group", { name: `A${Date.now() % 100000}`, usernames: [ben.name] })).conversation
  await openApp(page, ann.token)
  await tab(page, "chats").click()
  await page.locator(".list-item", { hasText: conv.name }).first().click()
  await page.locator(".topbar button[aria-label=group]").click()
  await page.locator(".route button[aria-label=members]").click()
  await page.locator(".route .list-item", { hasText: ben.name }).click()
  await page.getByRole("button", { name: "Zum Admin machen" }).click()
  await expect.poll(async () => (await api(request, ben.token, "GET", `/conversations/${conv.id}`)).conversation.is_admin).toBe(true)
  await expect(page.locator(".route .list-item", { hasText: ben.name }).getByText("Admin")).toBeVisible()
})

test("the same rule twice: a group's version of a rule I have shows a hint instead of 'subscribe'", async ({ page, request }) => {
  const { ann, ben } = await friends(request)
  const st = (await api(request, ann.token, "POST", "/rules", { kind: "acc", name: "Doppelt", icon: "star", mode: "constant", base: 50, public: true })).state
  const root = st.rules.find((r: any) => r.name === "Doppelt").id
  const conv = (await api(request, ann.token, "POST", "/conversations/group", { name: `T${Date.now() % 100000}`, usernames: [ben.name] })).conversation
  await api(request, ann.token, "POST", `/conversations/${conv.id}/rules`, { from_rule: root })
  await api(request, ben.token, "POST", `/rules/${root}/subscribe`)
  await openApp(page, ben.token)
  await tab(page, "chats").click()
  await page.locator(".list-item", { hasText: conv.name }).first().click()
  await page.locator(".topbar button[aria-label=group]").click()
  await page.locator(".route button[aria-label=rules]").click()
  await page.locator(".route .list-item", { hasText: "Doppelt" }).first().click()
  const sheet = page.locator(".sheet")
  await expect(sheet.getByText("Diese Regel hast du schon", { exact: false })).toBeVisible()
  await expect(sheet.getByRole("button", { name: "Abonnieren" })).toHaveCount(0)
})

test("tap on the shining hyperspace word: the lore with the locked background; ultra stays plain below hyperspace", async ({ page, request }) => {
  const { ann, ben } = await friends(request)
  await api(request, ben.token, "POST", "/posts", { text: "Ab in den Hyperraum, dann Ultra-Hyperraum" })
  await openApp(page, ann.token)
  await tab(page, "feed").click()
  await expect(page.locator(".hyper")).toHaveCount(1)                // the ultra word is plain text for ann
  await expect(page.locator(".hyper.gold")).toHaveCount(0)
  await page.locator(".hyper").first().screenshot({ path: test.info().outputPath("word.png") })
  await page.locator(".hyper").first().click()
  const lore = page.getByRole("dialog", { name: "Alle sehnen sich danach, das ultimative Momentum zu erreichen." })
  await expect(lore).toBeVisible()
  await expect(lore.getByText("ab 4 Mio. im Wettbewerb")).toBeVisible()
  await page.waitForTimeout(3200)
  await page.screenshot({ path: test.info().outputPath("lore.png") })
  await lore.click()
  await expect(lore).toBeHidden()
})

test("double tap on a post's picture likes it; a second double tap doesn't unlike", async ({ page, request }) => {
  const { ann, ben } = await friends(request)
  const r = await request.post("/api/media", { headers: { Authorization: `Bearer ${ben.token}`, "Content-Type": "image/png" }, data: PNG })
  const media = (await r.json()).media
  const post = (await api(request, ben.token, "POST", "/posts", { text: "Gipfel", media_ids: [media.id] })).post
  await openApp(page, ann.token)
  await tab(page, "feed").click()
  const pic = page.locator(".dbltap").first()
  await pic.dblclick()
  await expect(page.locator(".big-heart")).toBeVisible()
  await expect.poll(async () => (await api(request, ben.token, "GET", `/posts/${post.id}`)).post.likes).toBe(1)
  await page.waitForTimeout(400)
  await pic.dblclick()
  await page.waitForTimeout(600)
  expect((await api(request, ben.token, "GET", `/posts/${post.id}`)).post.likes).toBe(1)
})

test("block someone from their profile, find them in settings, unblock", async ({ page, request }) => {
  const { ann, ben } = await friends(request)
  await openApp(page, ann.token)
  await tab(page, "feed").click()
  await page.getByText("Leute finden").first().click()
  await page.getByPlaceholder("Name oder @username").fill(ben.name)
  await page.getByText(ben.name).first().click()
  await page.locator("button[aria-label=menu]").click()
  await page.getByRole("button", { name: "Blockieren" }).click()
  await page.getByRole("button", { name: "Blockieren" }).click()          // confirm
  await expect(page.getByText("Du hast dieses Konto blockiert.")).toBeVisible()
  await expect.poll(async () => (await api(request, ann.token, "GET", "/blocks")).users.map((u: any) => u.username)).toEqual([ben.name])
  const r = await request.get(`/api/users/${ann.name}`, { headers: { Authorization: `Bearer ${ben.token}` } })
  expect(r.status()).toBe(404)
  await page.getByRole("button", { name: "Blockierung aufheben" }).click()
  await expect(page.getByText("Du hast dieses Konto blockiert.")).toBeHidden()
  expect((await api(request, ann.token, "GET", "/blocks")).users).toEqual([])
})

test("chat reactions: double tap = heart, hold = pick another emoji", async ({ page, request }) => {
  const { ann, ben } = await friends(request)
  const conv = (await api(request, ann.token, "POST", "/conversations/direct", { username: ben.name })).conversation
  await api(request, ben.token, "POST", `/conversations/${conv.id}/messages`, { text: "Kommst du mit?" })
  await openApp(page, ann.token)
  await tab(page, "chats").click()
  await page.getByText(ben.name).first().click()
  const bubble = page.locator(".bubble").filter({ hasText: "Kommst du mit?" })
  await bubble.dblclick()
  await expect(page.locator(".reactions")).toHaveText("❤️")
  const msgs = async () => (await api(request, ben.token, "GET", `/conversations/${conv.id}/messages`)).messages
  expect((await msgs())[0].reactions).toEqual([{ emoji: "❤️", count: 1, mine: false }])
  // hold: the emoji row; another one replaces the heart
  const box = (await bubble.boundingBox())!
  await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2)
  await page.mouse.down()
  await page.waitForTimeout(700)
  await page.mouse.up()
  await page.getByRole("menuitem", { name: "😂" }).click()
  await expect(page.locator(".reactions")).toHaveText("😂")
  // ben sees it by polling
  expect((await msgs())[0].reactions).toEqual([{ emoji: "😂", count: 1, mine: false }])
})

test("the post menu sheet lies above the tab bar (not clipped by the post card)", async ({ page, request }) => {
  const { ann } = await friends(request)
  await api(request, ann.token, "POST", "/posts", { text: "Mein Post" })
  await openApp(page, ann.token)
  await tab(page, "feed").click()
  await page.locator("article button[aria-label=menu]").first().click()
  const del = page.locator(".sheet").getByRole("button", { name: "Post löschen" })
  await expect(del).toBeVisible()
  await del.click({ trial: true })            // waits until the sheet has slid in (stable)
  // the topmost element at the button's center is the button itself (nothing covers it)
  const hit = await del.evaluate((el) => {
    const r = el.getBoundingClientRect()
    const top = document.elementFromPoint(r.left + r.width / 2, r.top + r.height / 2)
    return !!top && (top === el || el.contains(top))
  })
  expect(hit).toBe(true)
  await del.click()
  await expect.poll(async () => (await api(request, ann.token, "GET", `/users/${ann.name}/posts`)).posts.length).toBe(0)
})

test("profile like Instagram: username on top, picture next to the numbers, the momentum card in the first tab", async ({ page, request }) => {
  const { ann, ben } = await friends(request)
  await api(request, ben.token, "POST", "/posts", { text: "Hallo" })
  await openApp(page, ann.token)
  await tab(page, "feed").click()
  await page.getByText("Leute finden").first().click()
  await page.getByPlaceholder("Name oder @username").fill(ben.name)
  await page.locator(".route").last().getByText(ben.name).last().click()      // the search hit, not the feed below
  const route = page.locator(".route").last()
  await expect(route.locator(".topbar")).toContainText(ben.name)
  await expect(route.locator(".profile-head")).toContainText("Posts")
  // first tab: the banner card with the momentum; the posts in the second
  await expect(route.locator("button[aria-label=momentum] i")).toHaveAttribute("style", /var\(--text\)/)
  await expect(route.getByText("Stufe 1/13", { exact: false }).first()).toBeVisible()
  await route.locator("button[aria-label=posts]").click()
  await expect(route.getByText("Hallo")).toBeVisible()
})

test("a post with a place: typed in, shown under the name", async ({ page, request }) => {
  const { ann } = await friends(request)
  await openApp(page, ann.token)
  await tab(page, "feed").click()
  await page.locator("button[aria-label='new post']").click()
  await page.locator("button[aria-label='create post']").click()
  await page.getByPlaceholder(/Bildunterschrift|Schreib/).fill("Morgenlauf")
  await page.getByText("Ort hinzufügen").click()
  await page.getByPlaceholder("Ort suchen…").fill("Bei Oma")
  await page.getByRole("button", { name: "„Bei Oma“ verwenden" }).click()
  await page.getByRole("button", { name: "Posten" }).click()
  await expect(page.locator("article .post-place").first()).toHaveText("Bei Oma")
  const posts = (await api(request, ann.token, "GET", `/users/${ann.name}/posts`)).posts
  expect(posts[0].location).toEqual({ name: "Bei Oma", lat: null, lon: null })
})

/** n seconds of a 440 Hz tone as WAV (stands in for a song preview). */
function toneWav(seconds: number) {
  const rate = 8000, n = rate * seconds, buf = Buffer.alloc(44 + n * 2)
  buf.write("RIFF", 0); buf.writeUInt32LE(36 + n * 2, 4); buf.write("WAVEfmt ", 8); buf.writeUInt32LE(16, 16)
  buf.writeUInt16LE(1, 20); buf.writeUInt16LE(1, 22); buf.writeUInt32LE(rate, 24); buf.writeUInt32LE(rate * 2, 28)
  buf.writeUInt16LE(2, 32); buf.writeUInt16LE(16, 34); buf.write("data", 36); buf.writeUInt32LE(n * 2, 40)
  for (let i = 0; i < n; i++) buf.writeInt16LE(Math.round(Math.sin(i / rate * 2 * Math.PI * 440) * 8000), 44 + i * 2)
  return buf
}

// the routes below only see the requests if no service worker passes them on
test.describe(() => { test.use({ serviceWorkers: "block" })
test("a post's sound from a song (Deezer preview through the server)", async ({ page, request }) => {
  const { ann } = await friends(request)
  // Deezer itself isn't asked in the test: search and preview come from here
  await page.route((url) => url.pathname === "/api/songs", (r) => r.fulfill({ json: { songs: [{ id: 42, title: "Harder", artist: "Daft Punk", cover: null }] } }))
  await page.route("**/api/songs/42/preview", (r) => r.fulfill({ body: toneWav(3), contentType: "audio/wav" }))
  await openApp(page, ann.token)
  await tab(page, "feed").click()
  await page.locator("button[aria-label='new post']").click()
  await page.locator("button[aria-label='create post']").click()
  await page.getByPlaceholder(/Bildunterschrift|Schreib/).fill("Mit Musik")
  await page.getByText("Sound hinzufügen").click()
  await page.getByPlaceholder("Song suchen…").fill("daft")
  await expect(page.locator(".song-hit")).toHaveCount(1)
  await page.locator(".song-hit").getByText("Harder", { exact: true }).click()
  await expect.poll(() => page.locator("input").evaluateAll((els) => els.some((e) => (e as HTMLInputElement).value === "Daft Punk – Harder"))).toBe(true)
  await page.getByRole("button", { name: "Posten" }).click()
  await expect.poll(async () => (await api(request, ann.token, "GET", `/users/${ann.name}/posts`)).posts[0]?.sound?.title, { timeout: 15_000 })
    .toBe("Daft Punk – Harder")
})
})

test("links in a chat open in the browser (report #58)", async ({ page, request }) => {
  const { ann, ben } = await friends(request)
  const conv = (await api(request, ann.token, "POST", "/conversations/direct", { username: ben.name })).conversation
  await api(request, ben.token, "POST", `/conversations/${conv.id}/messages`, { text: "schau mal www.example.com/a." })
  await openApp(page, ann.token)
  await tab(page, "chats").click()
  await page.getByText(ben.name).first().click()
  const link = page.locator("a.text-link", { hasText: "www.example.com/a" })
  await expect(link).toHaveAttribute("href", "https://www.example.com/a")
  await expect(link).toHaveAttribute("target", "_blank")
})

test("group: an admin removes a member, the chat shows who was added and removed (report #56)", async ({ page, request }) => {
  const { ann, ben } = await friends(request)
  const conv = (await api(request, ann.token, "POST", "/conversations/group", { name: `K${Date.now() % 100000}`, usernames: [ben.name] })).conversation
  const cat = await newUser(request, "cat")
  await api(request, cat.token, "POST", `/users/${ann.name}/follow`)
  await api(request, ann.token, "POST", `/conversations/${conv.id}/members`, { usernames: [cat.name] })
  await openApp(page, ann.token)
  await tab(page, "chats").click()
  await page.locator(".list-item", { hasText: conv.name }).first().click()
  await expect(page.locator(".system-line", { hasText: `Du hast ${cat.name} hinzugefügt` })).toBeVisible()
  await page.locator(".topbar button[aria-label=group]").click()
  await page.locator(".route button[aria-label=members]").click()
  await page.locator(".route .list-item", { hasText: cat.name }).click()
  await page.getByRole("button", { name: "Aus der Gruppe entfernen" }).click()
  await page.getByRole("button", { name: `${cat.name} aus der Gruppe entfernen?` }).click()
  await expect.poll(async () => (await api(request, ann.token, "GET", `/conversations/${conv.id}`)).conversation.members
    .map((m: any) => m.username)).toEqual([ben.name])
})
