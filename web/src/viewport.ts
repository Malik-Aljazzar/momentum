// The app's frame on iOS home-screen apps (black-translucent status bar).
//
// iOS sometimes lays the page out a status bar shorter than the screen (CSS 100% = screen minus ~47-62 px)
// while innerHeight already reports the full height, so the tab bar floated above the bottom with an empty
// strip under it (reports #28, #36). A home-screen app always covers the whole screen, so it takes the
// screen's real height.
//
// While the keyboard is up, iOS shrinks only the visual viewport and pans it; the keyboard's bar (arrows,
// "done") then covered part of the text field (report #43). Then the app takes exactly the visible part:
// its height and its offset. iOS 26 also scrolls the page itself and reports the keyboard's size while it
// still moves in: the whole app sat ~55 px too high (title under the status bar, a gap above the keyboard,
// the caret and the typing line out of place: report #59). So the page is kept at the top and the frame is
// measured again a little later.

export interface Frame { height: string; top: string }

export function appHeight(standalone: boolean, innerH: number, innerW: number, screenH: number, screenW: number): string {
  if (!standalone) return "100%"
  const portrait = innerH >= innerW
  const full = portrait ? Math.max(screenH, screenW) : Math.min(screenH, screenW)
  return full - innerH > 100 ? "100%" : `${full}px`
}

/** Height and top of #app; [vvHeight] / [vvTop]: the visual viewport (what is visible above the keyboard). */
export function appFrame(standalone: boolean, innerH: number, innerW: number, screenH: number, screenW: number,
  vvHeight?: number, vvTop = 0): Frame {
  if (!standalone) return { height: "100%", top: "0px" }
  const portrait = innerH >= innerW
  const full = portrait ? Math.max(screenH, screenW) : Math.min(screenH, screenW)
  if (vvHeight != null && full - vvHeight > 120) return { height: `${Math.round(vvHeight)}px`, top: `${Math.round(vvTop)}px` }
  return { height: appHeight(standalone, innerH, innerW, screenH, screenW), top: "0px" }
}

export function isIosHomeScreenApp(): boolean {
  const nav = navigator as Navigator & { standalone?: boolean }
  const ios = /iPhone|iPad|iPod/.test(navigator.userAgent) || (navigator.platform === "MacIntel" && navigator.maxTouchPoints > 1)
  return ios && (nav.standalone === true || matchMedia("(display-mode: standalone)").matches)
}

export function fitIosScreen() {
  const vv = window.visualViewport
  const f = appFrame(isIosHomeScreenApp(), innerHeight, innerWidth, screen.height, screen.width, vv?.height, vv?.offsetTop ?? 0)
  const root = document.documentElement.style
  root.setProperty("--app-height", f.height)
  root.setProperty("--app-top", f.top)
  // the keyboard covers the home indicator: no safe-area gap above it
  const keyboard = f.top !== "0px" || (f.height.endsWith("px") && vv != null && parseFloat(f.height) < screen.height - 120)
  if (keyboard) root.setProperty("--safe-bottom", "0px")
  else root.removeProperty("--safe-bottom")
  if (isIosHomeScreenApp() && (scrollX !== 0 || scrollY !== 0)) scrollTo(0, 0)
}

/** iOS settles the keyboard after its events: fit again a few times after a field gets or loses the focus. */
export function refitSoon() {
  for (const ms of [80, 250, 500, 900]) setTimeout(fitIosScreen, ms)
}
