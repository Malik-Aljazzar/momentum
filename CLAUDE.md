# Momentum – notes for agents

Read this first. It's the shared memory between agent sessions: what the product is, how the
repo works, the rules we keep, and how to ship. **Update it when you learn something future
agents need** (keep it short; facts that are in the code or in git don't belong here).

Talk to the user in **German**. Code, comments and commit-style texts are English.

## What it is

A habit-momentum app with a retro pixel-art look. Accelerators (rules that raise the momentum
when tapped) and decelerators (lower it), a daily decay, an adaptive daily limit, vehicles as
milestones, and an Instagram-like social layer (feed, posts with photos/videos, chats, groups,
group rules, challenges, leaderboards). Bilingual DE/EN, dark pixel style
(fonts Silkscreen + Pixelify Sans, sprites from `pixelart/`).

## Clients: Android app + iOS PWA, always in parity

| | Android | iOS (and any browser) |
|---|---|---|
| Where | `android/` (Kotlin, Jetpack Compose) | `web/` (PWA: Vite + TypeScript + Preact) |
| Served | `/app.apk` (download on the landing page) | `/app/` (install via Safari → "Zum Home-Bildschirm") |
| Tests | `server/tests` + emulator check | `web/` unit tests (Vitest) + e2e (Playwright, WebKit/iPhone) |

**Parity rule:** every user-facing feature or fix lands in **both** clients in the same
change, with tests on both sides, and both are deployed together by `deploy/deploy.sh`.
Platform-only things are listed here and nowhere else:

- Android only: home-screen widgets, CameraX in-app camera, Media3 on-device video
  compression, WorkManager background checks, volume-key sound toggle.
- The **admin app** (`android/admin`, own APK at `/admin.apk`, no PWA): reports, statistics,
  storage + its limit. Not part of the parity rule.
- PWA substitutes: camera/gallery via the system file picker; images shrunk in a canvas before
  upload, videos uploaded as they are (the server re-encodes them); voice via MediaRecorder;
  notifications only for the installed PWA (iOS ≥ 16.4) – real-time needs Web Push (open topic).
- Texts: `web/src/strings/strings.gen.ts` is generated from `Strings.kt` (`npm run strings`);
  never edit it by hand – change `Strings.kt`, hand-written exceptions live in `manual.ts`.

`client/` is an old web prototype – don't extend it, it's superseded by `web/`.

## Repo map

```
server/        Quart API (async SQLAlchemy, SQLite), MinIO blobs, ffmpeg via imageio-ffmpeg
  app.py         app factory, auth, rules, settings, version gate (426), state endpoints
  service.py     momentum state, triggers, undo, vehicles (VEHICLES), challenge credit
  momentum.py    pure rule math; milestone ladder (LADDER, milestone(), vehicle_index())
  limits.py      adaptive daily limit (rate doubles > .75, halves < .25; 25..12800; start 100)
  community.py   subscriptions/copies of rules (chain_copy, remove_copies…), group rules, bulk endpoints
  challenges.py  group challenges, rewards → credit
  chat.py        DMs/groups, mentions, mute, replies, group search/ranking, encryption switch + members' keys
  e2e.py         end-to-end encryption: keys/backup endpoints, envelope checks, encrypted media uploads
  social.py      users, follows, notifications, leaderboards
  posts.py       feed, posts, comments, sound, text overlays, pagination (page_args)
  media.py       images → 1080 px WebP + 480 px thumb, video ≤ 720p, audio clips
  overlay.py     text on photos/videos burned in at posting (font server/fonts/pixelify_bold.ttf)
  deezer.py      song search + 30 s previews for post sounds (proxied: no CORS, links expire)
  places.py      place search for a post's location (OpenStreetMap via Photon)
  stories.py     stories (24 h, seen marks), highlights, archive
  triggers.py    profile rules tapped with a post's slide / a story (≤5 per slide, ×1..10)
  db.py          models + migrations (_add_missing_columns, _migrate_data with config flags)
  admin.py       `python -m server.admin …` (reports, report-done, report-status, grant, step, rate, recompute)
  adminapi.py    /api/admin/* for the admin app (accounts with `User.admin`)
  storage.py     storage by category, the storage limit (MeteredStore wraps the blob store; 507 when full)
  security.py    login lockout (LoginFailure), client IP behind nginx (X-Real-IP), TOTP for admin logins
  tests/         pytest, in-memory blobs; must stay green
android/       app (ui/*.kt screens, AppViewModel, data/, widget/); strings in ui/Strings.kt (DE+EN)
               admin/ = the admin app (Compose, own module; `gradlew :admin:assembleDebug`)
web/           the PWA (see web/README.md)
pixelart/      generate.py → pixelart/out (sprites, icons, banners); both clients use these
deploy/        deploy.sh, Dockerfile, compose, nginx config copies
docs/SPEC.md   the original (German) product spec
```

## Product rules that are easy to break

- Momentum is whole numbers: accelerators round up, decelerators/decay round down.
- Milestones double: `milestone_step` (default 1000) × 0,1,2,4…512,1000,2000 → 13 vehicles,
  hyperspace at 4,000,000 (`momentum.LADDER`, sent to clients as `vehicle.milestones`,
  `reached_at`, `next_at`, `hyperspace_at` – clients must not compute thresholds themselves).
- Daily limit = adaptive rate; only "Wettbewerb" (daily limit on) players count. Limit off =
  free score, ranked value paused.
- Subscribed rules are read-only linked copies; they disappear when the root is deleted or
  made private (a group's own private rule keeps member subscriptions). Linked idle chains
  share visibility. A subscription's `public` in the state = "shown in my profile".
- Challenges only use group rules; their subscriptions are locked while participating.
- Nobody has the same rule twice: rules are the same when their links lead to the same origin
  (`community.origin_of`); a second way (person / group) is refused or reused. Real copies are own rules.
- "Hyperraum/Hyperspace" as a standalone word gets the animated gradient (never in usernames); tapping it
  opens the lore screen. "Ultra-Hyperspace" shines golden only for viewers with hyperspace; its banner
  (`ultrahyperspace`, 16M = `momentum.ultra_at`, `user.ultra`) is secret: not listed until unlocked.
- Every list is virtual / infinite-scrolled with server pagination.
- Blocks (`db.Block`): either direction hides everything - go through `social.is_blocked` / `block_ids`
  (used by `community.can_view`, `connected`, search, comments, DMs) when adding new social features.
- Rule times: `reset_days` / `idle_days` are 0..365 in `reset_unit` / `idle_unit` ("d" or "h").
- Videos keep at most 30 s (`media.VIDEO_MAX_SECONDS`, x264 preset medium); Android's camera and
  compression stop at 30 s too.
- Stories can be shared into chats (share kind `story`): every reader must be allowed to see the story
  (`stories.story_visible`: author's privacy, blocks, 24 h or a highlight). Answers / quick reactions are DMs
  with the story attached (`/api/stories/<id>/reply`, needs `connected`).
- Typing / recording indicator: `chat.set_typing` (in memory, ~6 s), clients ping
  `/api/conversations/<id>/typing` every 3 s while typing / recording; shown in the chat and the chat list.
- Photo stories run 5/10/15/30 s (`Story.duration`); a story's sound part is exactly that long, a post's sound
  part 5-30 s, both picked with a range bar (`clampRange` in `web/.../Feed.tsx` = `PostScreens.kt`).
- Rules in the feed: a personal rule that becomes public gets a post (kind "rule", `Post.rule_id`), a change
  moves it up again (`Post.bumped_at`, the feed's order), private / deleted removes it - call
  `posts.sync_rule_posts` (with the ids that were public before) after every change of a player's rules.
  Rule posts stay out of the profile grid and post count.
- Rankings `?period=today` ("24h" = old apps, same meaning): by the sum of `Event.delta` since 0:00 Berlin
  (`social.deltas_24h`), rows get `delta`; label "Heute"; Android also has ranking widgets for it
  (GroupDeltaWidget, FriendsDeltaWidget).
- Exactly one medium plays: the most visible one on the topmost layer. Full-screen layers over the screens
  (story, big view) register as covers (`CoverLayer` / `useCoverLayer` in ui/Video.kt, ui/video.tsx); a new
  full-screen viewer must do that too, or its media plays together with the feed's (reports #38, #42).
- Chat photos: after 14 days (`chat.CHAT_IMAGE_DAYS`) an hourly job keeps only the 480 px thumb (`Media.archived`); messages carry `expires_at` / `archived`, clients show the countdown. Messages show their time and day separators in the player's time zone (`web/src/chattime.ts` = `ui/ChatTime.kt`).
- iOS Safari once drew a card's animated banner canvas over everything on it (frame, badge, numbers): momentum cards use `.banner-card` (own compositing layers). The PWA blocks the browser's long-press menu (main.tsx).
- The limit info shows where tomorrow's rate is heading (`limits.status`: today_average, next_cap).
- The limit's average counts only competing players who tapped an acc/dec themselves that day (not automatic
  idle taps, not the decay, not undone taps) - `limits.average_growth`; `UserActivity` is admin statistics only.
- Limit used up: an accelerator with an active automatic (idle_days + idle_rule, not paused) can still be tapped by
  hand - +0, it only restarts the idle time (`service.guards_idle` = `logic.ts guardsIdle` = `AppViewModel.guardsIdle`).
- New kinds of chat lines (`Message.kind`) or activity (`Notification.kind`) would show wrongly in Android apps that
  don't know them yet (an Android release only comes on request): list them in `server/clients.py` with the
  versionCode from which Android shows them; older apps don't get them (the PWA always does).
- Group chats show member lines (`member_join/_add/_remove/_leave`, `Message.target_id`, `chat.member_line`);
  texts in `web/src/lines.ts` = `ui/Lines.kt`. Admins remove members (`DELETE /conversations/<id>/members/<username>`).
- Subscribing to a person's rule / following their decay (also a group doing it) notifies the owner (`sub_rule`,
  `sub_decay`, `Notification.rule_id` / `group_id`, `social.notify_once`); a group's own rules don't.
- Links in user text are tappable (`web/src/links.ts` = `ui/Links.kt`, used by MentionText).
- iOS PWA: photos from the front camera via the file input come unmirrored - `pickFiles` mirrors them like the preview
  (EXIF lens "front camera"), = Android's camera. Sounds are always uploaded as audio (a picked video otherwise
  becomes a video and the story / post refuses it).
- Filing a report gives 1 % of today's limit (`service.report_bonus`, event kind "report", no rule): counts
  against the limit (`limits.LIMITED`), shows in the own history, never in the profile.
- Android: avoid full-screen Compose `Dialog`s with controls at the bottom - on Android 15+ their window
  starts below the status bar but lays out full height, so the bottom gets cut off (reports #31/#34/#35).
  Use an overlay layer in the screen instead.
- Vehicle sheets have 8 frames of 64x32 (`generate.py FRAMES`, 80 ms each: `web/src/gfx/vehicles.ts` = `ui/VehicleFx.kt`
  FRAMES / FRAME_MS; widgets use frame 0). Effects around the sprite (dust, exhaust, headlights at night, contrails,
  afterburner, engine trails, glint) live in those two files (`VEHICLE_FX`, art-pixel coordinates); `vehicleSpeed`
  scrolls the banner / scene (`drawBanner(..., speed)` = `BannerBackground(speed)`), `bannerNight` switches the
  lights on. Banner extras (rays, fog, heat, water, bolt, lava, aurora, disc, galaxy, traffic) are the `EXTRAS`
  table in both; a new banner gets its line there too. The PWA draws the vehicle on its own canvas (`VehicleSprite`,
  `PAD` around it), Android draws past the sprite's bounds (the card clips).
- Pixel look everywhere: no rounded Material checkboxes/arrows – draw controls on a pixel grid
  (Android: `ui/PixelControls.kt`).
- Emojis are always pixel art: `pixelart/emoji.py` turns Twemoji into 18×18 sprites and builds two fonts
  (`res/font/pixel_emoji.ttf` = CBDT for Android, `pixelart/out/emoji/pixel_emoji.woff2` = COLRv0 for the PWA) plus
  `out/emoji/emoji.json` (picker data) and `sheet.png` (server: `server/emoji.py` burns emojis into overlays). Android:
  `Fonts` (Theme.kt) chains the app fonts with the emoji font (API 29+; `MomentumApplication`), EmojiCompat's auto-start
  is removed in the manifest. PWA: `"Pixel Emoji"` is the last family of every font stack in theme.css - keep it there in
  new `font-family` declarations. Exception: the "system font" setting shows the phone's own emojis (`.user-text.system`,
  `Fonts.system`, also in the picker). `generate.py` empties `pixelart/out/` - rerun `emoji.py` (or `git checkout
  pixelart/out/emoji`) after it; after changing the pixelizing delete `pixelart/cache/sprites.pickle`. A rule icon may be one emoji (`emoji.is_emoji`, clients: `isEmojiIcon` → PixelIcon draws
  text). Picker: `ui/Emoji.kt` = `web/src/ui/emoji.tsx`, `emoji = true` / `emoji` on PxTextField. Rerun the generator
  after an emoji version bump (downloads into `pixelart/cache/`, not deployed).
- Errors explain themselves: unexpected exceptions answer JSON 500 with `error` + `detail` (type and message,
  cleaned by `app.public_detail`: never SQL statements / data, paths, hosts, keys, tokens, addresses); MinIO full /
  disk reserve = 507, media store down = 503. Clients show the server's `error`; without JSON (nginx 502/503/504
  while the app restarts) they word the status (`serverDown` / `serverError`, `ApiError.fromServer` = `ApiException.fromServer`).
- King of Reporters: the player whose most reports were set to done wears a badge in the profile
  (`profile.badges`, `social.badges_of`; tie = who got there first). Tapping it explains why with numbers only
  (count, bugs, features) - never the reports' texts.
- Server tests: `server/tests/conftest.py` sets `MOMENTUM_START_RATE` high (the daily limit doesn't bite; limit
  tests write their own DailyCap) and turns the APK version gate off (no dependence on a local release build).
- All texts in DE and EN.
- **Changelog:** every user-facing change gets a line in `CHANGELOG.md` (root; DE + EN, same points, newest
  first, `## <date>` headings - a second release on the same day gets a suffix like `2026-11-02 b`). Both
  apps bundle the file (PWA: `web/src/changelog.ts` imports it; Android: `syncChangelog` in build.gradle.kts
  -> assets, `data/Changelog.kt`) and show the versions added since the one they ran before as a sheet on the
  main screen (`whatsNew` signal / `vm.whatsNew`; stored key `changelog_seen`); the settings open the whole list.
  Don't rename old headings: an unknown stored version shows nothing. Tests on both sides check that every
  version has as many DE as EN lines.

## Public repo and issues

The project is a **public** git repo: https://github.com/Malik-Aljazzar/momentum (`main`). Never commit
secrets (`.gitignore` lists them: `secrets/`, keystore / local.properties, `.env`); generated files
(`web/public/sprites|fonts`, `strings.gen.ts`, `precache.gen.json`, pixel-art previews, builds) stay out.
Commit messages in English. Push after every deployed change.

Reports -> issues (`server/github.py`): one issue per report, labels `bug` / `feature`, `android` / `pwa`,
`status: new|todo|done` (done = closed, back to todo = reopened), the body carries the text, the owner's note
("Task") and platform / app version / device - never the reporter's name or the screenshots. A loop every
minute sends what changed (`Report.issue_number`, `Report.issue_sync` = hash of the last sent content), one
way: the server is the truth, changes on GitHub are overwritten. Config `GITHUB_REPO` + `GITHUB_TOKEN` in the
server's `.env` (empty = off); `python -m server.admin issues` runs a round by hand; the admin API's reports
carry `issue` (the URL).

## Develop

```
docker compose up -d                       # MinIO (start Docker Desktop first)
.venv/Scripts/python -m server             # API on :8000 (prints LAN address)
.venv/Scripts/python -m pytest server/tests -q
cd android && ./gradlew.bat :app:assembleDebug
cd web && npm install && npm run dev       # PWA dev server, proxies /api to :8000
cd web && npm test && npm run e2e          # Vitest + Playwright (starts its own test server)
```

- e2e runs against the built `web/dist` – `npm run build` first. Two sessions at once: give the
  second one `E2E_PORT=8775` and `--output <scratchpad dir>` (else they share port 8765 and wipe
  each other's `test-results/`).

- Android SDK: `%LOCALAPPDATA%\Android\Sdk`, AVD `momentum` (headless:
  `emulator -avd momentum -no-window -no-audio -gpu swiftshader_indirect`). Verify UI changes in
  the emulator with `adb exec-out screencap -p` screenshots (1080×2400).
- **Before every deploy with Android UI changes:** install the debug APK in the emulator and tap
  through all five tabs (`adb shell input tap <x> 2238` for x = 108, 324, 540, 756, 972), then
  `adb logcat -d -b crash`. Compiling isn't enough: e.g. `BoxWithConstraints`/`LazyColumn`
  (SubcomposeLayout) inside a `height(IntrinsicSize.Min)` row crashes at runtime (happened once).
- PWA visual check: `npx playwright test tour` writes screenshots of every main screen to
  `web/test-results/tour/` (WebKit, iPhone 13) – compare them with the emulator.
- PWA in real Android Chrome: `python -m server.testserver 8785`, `adb reverse tcp:8785 tcp:8785`, open
  `http://localhost:8785/app/` in the emulator's Chrome; inspect it with Playwright `connectOverCDP` after
  `adb forward tcp:9222 localabstract:chrome_devtools_remote`. UI tests should check sizes, not only "loaded"
  (feed photos once loaded fine but sat in a 0 px box).
- Large edits: small Python patch scripts in the session scratchpad that assert each
  `old` snippet exists, then replace (avoid bash heredocs containing quotes – write the script
  with the Write tool).
- The project is **not** a git repo: there's no undo. Look before deleting or overwriting.

## Ship

**Android releases only when the user explicitly says so.** `bash deploy/deploy.sh` deploys server + PWA
(the APK on the server stays); `bash deploy/deploy.sh --apk` also builds and publishes the release APK for
https://momentum.mal-zeta.de, builds the PWA, uploads to `sz-dev-environments:/opt/momentum`,
`docker compose up -d --build`. Run all tests first. Old APKs get HTTP 426 (background GETs exempt).

- Host details: system nginx with TLS (certbot webroot), app on 127.0.0.1:8095, SQLite in
  volume `momentum_app-data` (/data/momentum.db), MinIO internal. Ports registry: /opt/USED_PORTS.md.
- Data migrations: add a config-flag step to `db._migrate_data`; test it on a copy of the
  production DB when it rewrites data.
- SQLite runs in WAL mode (`db._sqlite_wal`): a copy of the DB needs `momentum.db-wal` too (or
  `sqlite3 momentum.db ".backup copy.db"`). Requests run to their end when the client hangs up
  (`app.App.handle_request`): cancelled mid-query, a connection once kept SQLite locked for everyone.

## Play Store and Google sign-in

- Play build: `cd android && ./gradlew.bat :app:bundleRelease -Pplay -PmomentumServer=https://momentum.mal-zeta.de`
  -> `app/build/outputs/bundle/release/app-release.aab`, signed with the upload key (`secrets/momentum-upload.jks`,
  passwords in `android/keystore.properties` - both never in a repo / never uploaded; losing them = Play support
  ticket). Same package `com.momentum.app` as the website APK, which stays on the debug key (both can't update over
  each other). `BuildConfig.PLAY`: sends `X-App-Channel: play` (the server's APK version gate skips it), the update
  screen opens the Play page. targetSdk 36 (Play's minimum since 08/2026), AGP 8.10.
- Legal pages `/legal` (`/privacy`, `/terms`, `/delete-account` redirect to its anchors), `server/legal.html`; the
  operator block comes from `MOMENTUM_OPERATOR` in the server's `.env` (`
` = new line). Linked in both apps' settings
  and under the login button (accepting the terms).
- Sign in with Google (`server/google.py`): a Google account is its own identity (`User.google_sub`); its address is
  `User.google_email`, shown only, never a login - `User.email` stays the password accounts' login, so the same
  address can be two separate accounts. Google-only accounts have `password_hash == ""` (no password login, no
  password section). Android: Credential Manager -> `POST /api/auth/google`; PWA: redirect flow
  `/api/auth/google/start` -> callback -> `/app/#token=…` (no popup: breaks in the iOS home-screen app). Config:
  `GOOGLE_CLIENT_ID` (web client; also Android's serverClientId) and `GOOGLE_CLIENT_SECRET` in `.env`; empty = no buttons.

## User reports

Users file bug reports / feature requests in the app. Status: **new → todo → done**, set by the owner
in the admin app.

- **Only implement `todo` reports**, then mark them done. Their `note` holds the owner's (reworded) task.
- **`new` reports are off limits** unless the user explicitly says so for that report in the chat; then
  reword it into a clear task (`note`), set it to `todo` and implement it.

```
ssh sz-dev-environments "cd /opt/momentum && docker compose exec -T app python -m server.admin reports"        # todo (new|done|all)
ssh sz-dev-environments "cd /opt/momentum && docker compose exec -T app python -m server.admin report-done <id>"
ssh sz-dev-environments "cd /opt/momentum && docker compose exec -T app python -m server.admin report-status <id> todo"
```

Screenshots are linked as `https://momentum.mal-zeta.de/media/…` – download and look at them.
Fix in both clients, deploy, then mark done.

## Admin app and storage limit

`android/admin` talks to `/api/admin/*`; access needs `User.admin` (`python -m server.admin grant <username>`,
which prints the authenticator key once; `totp-reset` makes a new one, `revoke` ends admin access).

Sessions (`AuthToken.scope`): "app" = game, ends after 180 days unused; "admin" = only `/api/admin/*`,
from `/api/admin/login` (password + TOTP code, each code works once), ends after 30 days. Neither works
for the other - don't let admin routes accept game tokens. Logins lock after failures (5 per account+IP,
20 per IP, 50 per account in 15 min -> 429). Report screenshots live under `reports/` (not served by
`/media`, only via `/api/admin/blob/<key>`); everything else in `/media` is public with unguessable keys.
Ship it with `bash deploy/deploy.sh --admin` (served at `/admin.apk`, not linked). The storage limit
(config `storage_limit`, default = whole disk) caps DB file + all blobs: puts past it and any POST/PUT/PATCH
while full get HTTP 507 (auth and admin routes exempt, DELETE always works). New blob uploads must go
through `app.blobs` (the MeteredStore) so they're counted; new media kinds need a category in
`storage._media_categories` and a label in `admin/Ui.kt`.

## Push notifications

`server/push.py`: a loop sends new messages / activity every ~3 s. PWA: Web Push (VAPID keys made
by the server, stored in the config table as PEM – pywebpush needs it as a `Vapid` object, a
PEM string makes every round crash) with the text in the user's language (`TEXTS`, keep in
sync with Strings.kt). Android: FCM data message = wake-up, the app runs `checkUpdates` itself
(`data/Push.kt`); WorkManager polling stays as fallback. Firebase project `momentum-df519`,
`android/app/google-services.json` is in the repo. The service account key is on the server at
`/data/firebase-admin.json` (in the data volume, survives deploys; locally `secrets/firebase-admin.json`) –
without it the log says "Android keeps polling". Devices are bound to their login session (logout = no pushes).
Read notifications disappear: tags `chat:<id>` / `activity` (Android) and `chat-<id>` / `note-<id>`
(PWA) are cleared when the chat / activity list is opened; Android also clears what
`/api/updates` no longer lists in `unread_chats` / `unread_notifications` (read on another device).

## E-mail: confirming the address, new password, lock (`server/mail.py`)

Sent with Microsoft Graph as the app (client credentials) from `MAIL_FROM` (momentum@scriptum-zeta.com, a shared
mailbox); the Entra app may only send as that mailbox (Exchange "RBAC for Applications": role `Application Mail.Send`
with a management scope - no Mail.Send permission in Entra). Config `MAIL_TENANT_ID`, `MAIL_CLIENT_ID`,
`MAIL_CLIENT_SECRET`, `MAIL_FROM` in the server's `.env` (empty = everything below is off);
`python -m server.admin mail-test <address>` sends a test mail. Codes: 6 digits, only their hash stored, 30 min,
5 tries, a new one per address after 60 s, 6 an hour.

- Confirming is required: new accounts at once (the confirm screen comes before the username), accounts from before
  mails were on get 14 days once (`start_deadlines`, config flag `email_confirm_since`), the apps remind them on the
  home tab. Locked meanwhile: only `app.CONFIRM_FREE`; Android apps older than `clients.CONFIRM_APP` aren't locked
  (they can't show the screen). Google-only accounts are exempt. State: `user.email_confirm` ({due, required} or null).
- New password only to a confirmed address; reset ends every session. 5 wrong passwords in a row (`LOCK_AFTER`) lock a
  confirmed account: logins answer 423 + mail the reset code, the apps open "new password". Unconfirmed / mails off:
  only security.py's 15-minute lockout. `security.clear_failures` clears only the first key of its list.
- Tests: `create_app(mail=...)` (default from env = off in tests); the e2e server keeps codes (`/api/test/mail`) and
  `newUser` confirms like a player. The test server runs with Quart's reloader: editing server code restarts it with
  an empty database.

## End-to-end encrypted chats

Scheme in `server/e2e.py` (docstring); the same code three times: `web/src/e2ecrypto.ts`, `android/.../data/E2E.kt`,
`server/tests/e2e_crypto.py` (reference + test vector). Change one -> change all, regenerate the vector
(`python -m server.tests.e2e_crypto > web/tests/unit/e2e-vector.json`, copy to `android/app/src/test/resources/`);
`test_e2e.py`, `web/tests/unit/e2e.test.ts` and `E2ETest.kt` (`gradlew :app:testDebugUnitTest`) all open it.

- P-256 key per user; the private key never reaches the server, only its backup encrypted with the recovery key
  (52 chars, shown once / in the settings). Made automatically on the first start (`startE2E` / `E2EStore.start`);
  another device is "locked" until the recovery key is typed in. Keys stay on the device on logout.
- `Conversation.e2e` (default on; direct: both switch it, groups: admins) -> plain text / plain media are refused (409);
  the switch writes a chat line (`Message.kind` e2e_on/e2e_off, not unread, no push).
- The server can't read: text (`Message.e2e` envelope, `text` empty), mentions come as `mentions` (usernames) from the
  sender, photos / voice are encrypted by the apps (`/api/media/encrypted`, `/api/media/<id>/thumb`; the server doesn't
  shrink them). Shares, reactions, who/when stay metadata.
- Pushes carry the encrypted preview (`pv`); the PWA's service worker opens it (`src/swpush.ts`, bundled into sw.js by
  `scripts/sw-plugin.ts`), Android opens it in `Notifier` (`E2EStore.previewText`).
- Tests: `create_app(e2e_default=False)` is the default for tests and the e2e server (plain chats); encrypted tests
  switch it on (`PUT /conversations/<id>/e2e`). Safety codes + key-change warnings = trust on first use per device.
- A message must fit the chat as it is now: every member with a key gets a wrapped key, and `GET /conversations/<id>/keys`
  gives `hash`, which the apps send back as `e2e.kh` - stale, a member missing or a reader gone -> 409 (`e2e.StaleKeys`),
  the apps reload the keys and send again (also photos / voice, if the chat's encryption still matches). `eph` must be a
  point on the curve (server `valid_public_key`, Android `E2E.publicKey`). The offline chat caches keep encrypted messages
  as they came (`closeMessage`), never the opened text / media keys.

## Groups

Admins (`ConversationMember.admin`, `chat.is_admin`) change everything (rules, challenges,
members, settings); there's always one (last one leaves -> longest member). `Conversation.anarchy`
= everyone is admin. Group rules show the group (name, picture), not their creator.

## Shared decays (`server/decays.py`, clients: `ui/Decays.kt`, `web/src/screens/Decays.tsx`)

A player has exactly one decay: their own or a followed one (a person's public decay or a group
decay); the followed settings replace theirs, the streak stays their own (`decays.effective`).
Groups have several decays (own or linked to a public one). A challenge has at most one decay;
joining forces it (`force_follow`), leaving restores the previous follow (`release`).
