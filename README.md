# Momentum

A habit-momentum app in a retro pixel-art look. Accelerators raise your momentum when you tap them,
decelerators lower it, every day a decay pulls it down, and an adaptive daily limit keeps the competition
fair. Milestones unlock vehicles, up to the hyperspace jump. Around the game sits an Instagram-like social
layer: a feed with photos and videos, stories, end-to-end encrypted chats and groups, group rules,
challenges and leaderboards. German and English.

Live at https://momentum.mal-zeta.de - the Android app is on the landing page, the iOS app is the PWA
at `/app/` ("Add to Home Screen" in Safari).

## Two clients, one feature set

| | Android | iOS and any browser |
|---|---|---|
| Code | `android/` (Kotlin, Jetpack Compose) | `web/` (PWA: Vite + TypeScript + Preact) |
| Served | `/app.apk` | `/app/` |
| Tests | `server/tests` + emulator | Vitest + Playwright (WebKit, iPhone) |

Every feature lands in both clients in the same change. The texts live once, in
`android/.../ui/Strings.kt`, and are generated for the web (`npm run strings`). Sprites, icons and
banners come from `pixelart/generate.py`; the emoji font from `pixelart/emoji.py`. `CHANGELOG.md` is
bundled into both apps: after an update they show what's new.

```
server/     Quart API (async SQLAlchemy + SQLite), MinIO for media, ffmpeg via imageio-ffmpeg
android/    the app (app/) and the admin app (admin/: reports, statistics, storage)
web/        the PWA
pixelart/   the sprite generator -> pixelart/out (both clients and the server use it)
deploy/     Dockerfile, compose, nginx config, deploy.sh
docs/       the original (German) product spec
client/     an early web prototype, superseded by web/
```

`CLAUDE.md` is the engineering handbook: product rules, module map, how to test and ship.

## Run it

```
docker compose up -d                        # MinIO (Docker Desktop)
python -m venv .venv
.venv/Scripts/pip install -r server/requirements.txt
.venv/Scripts/python -m server              # API on :8000, prints the LAN address
cd web && npm install && npm run dev        # PWA on :5173, proxies /api to :8000
cd android && ./gradlew.bat :app:assembleDebug   # needs the Android SDK; talks to the PC it was built on
```

Tests:

```
.venv/Scripts/python -m pytest server/tests -q
cd web && npm test && npm run build && npm run e2e
cd android && ./gradlew.bat :app:testDebugUnitTest
```

## Bug reports and feature requests

Players file them in the app (settings → report). They go to the admin app and, as issues with labels
(`bug` / `feature`, `android` / `pwa`, `status: new` → `todo` → `done`), to this repo's issue tracker -
one way, from the server, without the reporter's name or the screenshots. Set `GITHUB_REPO` and
`GITHUB_TOKEN` (issues: write) in the server's `.env` to turn it on.

## Configuration

Everything comes from the environment (the server's `.env` in production, see `deploy/`): `MOMENTUM_DB`,
`MOMENTUM_TZ`, `MINIO_*`, `GOOGLE_CLIENT_ID` / `GOOGLE_CLIENT_SECRET` (sign in with Google),
`MOMENTUM_OPERATOR` (the legal page), `GITHUB_REPO` / `GITHUB_TOKEN`. Web Push keys are generated on
first start; Android pushes need the Firebase service account at `/data/firebase-admin.json`.
