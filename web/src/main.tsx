import { render } from "preact"
import "./ui/theme.css"
import { App } from "./app"
import { boot } from "./store"
import { fontsLoaded } from "./ui/components"
import { fitIosScreen, refitSoon } from "./viewport"
import { tones } from "./calltones"
import { call } from "./calls"

// iOS home-screen app: the screen's real height (reports #28, #36, see viewport.ts)
fitIosScreen()
addEventListener("resize", fitIosScreen)
addEventListener("orientationchange", () => setTimeout(fitIosScreen, 300))
// iOS allows sounds only after a tap: the call tones' audio is unlocked with every tap (calltones.ts)
addEventListener("pointerdown", () => tones.unlock(), { passive: true })
visualViewport?.addEventListener("resize", fitIosScreen)
visualViewport?.addEventListener("scroll", fitIosScreen)      // iOS pans the view while the keyboard is up
addEventListener("scroll", fitIosScreen)                      // ... and iOS 26 scrolls the page too (report #59)
addEventListener("focusin", refitSoon)
addEventListener("focusout", refitSoon)
// no browser menu on a long press (save image, open video ...): it should feel like an app (report #50);
// text fields keep theirs (paste)
addEventListener("contextmenu", (e) => { if (!editable(e.target)) e.preventDefault() })
function editable(t: EventTarget | null): boolean {
  return t instanceof HTMLElement && (t.isContentEditable || t.closest("input, textarea") != null)
}

// the pixel fonts first (a short wait, they're precached): no text flashes in a fallback font
void Promise.race([fontsLoaded, new Promise((r) => setTimeout(r, 1500))]).then(() => {
  render(<App />, document.getElementById("app")!)
  void boot()
})

// offline start + instant loading. A deploy installs a new worker: the app switches to it right
// away while starting, otherwise the next time it comes back to the screen (nothing typed is lost) -
// never while a call rings or runs (a reload hangs up; opened from the ringing, a tap on "answer" would be lost)
if ("serviceWorker" in navigator && import.meta.env.PROD) {
  const hadWorker = !!navigator.serviceWorker.controller        // false on the very first visit
  const started = performance.now()
  const forCall = new URLSearchParams(location.search).get("open")?.startsWith("call:") ?? false   // (app.tsx)
  let pendingReload = false
  navigator.serviceWorker.addEventListener("controllerchange", () => {
    if (!hadWorker) return
    if (performance.now() - started < 10_000 && !call.value && !forCall) location.reload()
    else pendingReload = true
  })
  void navigator.serviceWorker.register("/app/sw.js", { scope: "/app/" }).then((reg) => {
    document.addEventListener("visibilitychange", () => {
      if (document.visibilityState !== "visible") return
      if (pendingReload && !call.value) location.reload()
      else void reg.update()
    })
  })
}
