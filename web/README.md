# Momentum PWA (iOS and any browser)

The web client mirrors the Android app in `../android` screen by screen (see the parity rule in
`../CLAUDE.md`). Preact + TypeScript + Vite, served by the Quart server under `/app/`.

```
npm install
npm run dev        # http://localhost:5173/app/ – API, media and avatars come from python -m server (:8000)
npm test           # Vitest: game logic, text parity
npm run build      # dist/ (the server serves it; deploy/deploy.sh builds it)
npm run e2e        # Playwright, WebKit with an iPhone profile, against python -m server.testserver
```

## How it maps to the Android app

| Android | Web |
|---|---|
| `AppViewModel.kt` | `src/store.ts` (signals, offline outbox, optimistic taps) + `src/logic.ts` (pure, unit-tested) |
| `data/Models.kt`, `Social.kt` | `src/model.ts` (the API's JSON as is, snake_case) |
| `ui/Strings.kt` | `src/strings/strings.gen.ts` – **generated** by `scripts/strings.mjs`; the few members it can't translate are in `src/strings/manual.ts` (tsc fails when one is missing) |
| `ui/Scenes.kt`, `Banners.kt` | `src/gfx/` (canvas, one shared animation loop, only visible canvases draw) |
| `ui/Components.kt`, `Nav.kt`, `PixelControls.kt` | `src/ui/components.tsx`, `src/nav.ts` |
| `ui/Video.kt`, `Voice.kt`, `Crop.kt` | `src/ui/video.tsx`, `voice.tsx`, `media.tsx` |
| one `ui/*.kt` per area | one `src/screens/*.tsx` per area (same names) |

Sprites and fonts are copied from `../pixelart/out` and `../android/.../res/font` by
`scripts/assets.mjs` (runs before dev/build) and precached by the service worker
(`scripts/sw-plugin.ts`), so the app starts offline. A deploy installs a new worker; the app
switches to it on start or the next time it comes back to the screen.

## Adding a feature

1. Build it in `android/` and here (same texts: add them to `Strings.kt`, run `npm run strings`).
2. Unit-test logic in `tests/unit`, the flow in `tests/e2e` (WebKit/iPhone).
3. `npm run build && npm run e2e`, then deploy both with `deploy/deploy.sh`.
