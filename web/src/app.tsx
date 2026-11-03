// The app root (android/.../ui/App.kt): login / main screen, tabs, opened screens, overlays.
import { useEffect, useRef, useState } from "preact/hooks"
import { keepsTabs, nav, stack, tab, TABS, type Route, type Tab } from "./nav"
import { foreground, hyperLore, hyperspace, milestone, online, pending, refresh, screen, state, updateRequired, whatsNew } from "./store"
import { startE2E } from "./e2e"
import { installed, isIos, notifyPermission, requestNotifications } from "./push"
import { lang as currentLang, S } from "./strings"
import { Avatar, PixelIcon, PxButton, Sheet, ToastHost } from "./ui/components"
import { ConfirmEmailScreen, LoginScreen, OfflineScreen, UsernameScreen } from "./screens/Login"
import { HomeScreen } from "./screens/Home"
import { RulesScreen } from "./screens/Rules"
import { RouteScreen, TabScreen } from "./screens/registry"
import { HyperLoreOverlay, HyperspaceOverlay, MilestoneOverlay, UpdateScreen, WhatsNewOverlay } from "./screens/Overlays"
import { OverlayHost } from "./ui/overlay"
import { MediaHost } from "./screens/media"
import { StoryHost } from "./screens/Stories"
import { OnTop } from "./ui/video"

export function App() {
  const sc = screen.value
  return <>
    {sc === "loading" && <div class="fill" />}
    {sc === "offline" && <OfflineScreen />}
    {sc === "login" && <LoginScreen />}
    {/* the e-mail address first (required: server/mail.py), then the username, then the app */}
    {sc === "main" && (state.value?.user.email_confirm?.required ? <ConfirmEmailScreen />
      : state.value?.user.needs_username ? <UsernameScreen /> : <MainScreen />)}
    <ToastHost bottom={sc === "main" ? "calc(var(--safe-bottom) + var(--tabbar) + 16px)" : "calc(var(--safe-bottom) + 32px)"} />
    {milestone.value && !hyperspace.value && <MilestoneOverlay onClose={() => { milestone.value = false }} />}
    {hyperspace.value && <HyperspaceOverlay onClose={() => { hyperspace.value = false; milestone.value = false }} />}
    {hyperLore.value && <HyperLoreOverlay bkey={hyperLore.value} onClose={() => { hyperLore.value = null }} />}
    {sc === "main" && whatsNew.value && <WhatsNewOverlay releases={whatsNew.value} onClose={() => { whatsNew.value = null }} />}
    {sc === "main" && <NotifyAsk />}
    <OverlayHost />
    <StoryHost />
    <MediaHost />
    {updateRequired.value && <UpdateScreen />}
  </>
}

/** First start of the PWA: ask once for notifications (permission not decided yet). On iOS only the
 *  installed app can get them, and the browser's own question needs a tap - hence this sheet. */
function NotifyAsk() {
  const [open, setOpen] = useState(() => {
    try {
      return notifyPermission() === "default" && "serviceWorker" in navigator && (installed() || !isIos())
        && localStorage.getItem("notify_asked") == null
    } catch { return false }
  })
  if (!open || !state.value) return null
  const done = () => { try { localStorage.setItem("notify_asked", "1") } catch { /* private mode */ } setOpen(false) }
  const s = S.value
  return <Sheet onClose={done} label={s.notifyAskTitle}>
    <div class="row" style={{ gap: 12 }}><PixelIcon name="ui_bell" size={32} /><div class="t-title" style={{ fontSize: 20 }}>{s.notifyAskTitle}</div></div>
    <span class="t-small">{s.notifyAskSub}</span>
    <PxButton text={s.allowNotifications} onClick={async () => { done(); await requestNotifications(currentLang.value) }} />
    <PxButton text={s.notNow} kind="ghost" onClick={done} />
  </Sheet>
}

const ICONS: Record<Tab, string> = { home: "ui_home", rules: "ui_updown", chats: "ui_chat", feed: "ui_feed", profile: "" }

function MainScreen() {
  const st = state.value
  const pager = useRef<HTMLDivElement>(null)
  const current = tab.value
  const routes = stack.value
  // opened from a notification (?open=chat:12, post:3, challenge:5, notifications)
  useEffect(() => {
    const open = new URLSearchParams(location.search).get("open")
    if (!open) return
    history.replaceState(null, "", "/app/")
    const [kind, id] = open.split(":")
    const n = Number(id)
    if (kind === "chat" && n) { tab.value = "chats"; nav.push({ r: "chat", id: n }) }
    else if (kind === "post" && n) nav.push({ r: "post", id: n })
    else if (kind === "challenge" && n) nav.push({ r: "challenge", id: n })
    else if (kind === "notifications") { tab.value = "feed"; nav.push({ r: "notifications" }) }
  }, [])
  // encrypted chats: my key on this device (made on the first device, else unlocked with the recovery key)
  const myId = st?.user.id
  useEffect(() => { if (myId != null && online.value) void startE2E(myId) }, [myId, online.value])
  // keep unread badges fresh while the app is open
  useEffect(() => {
    const h = setInterval(() => { if (foreground.value) void refresh() }, 20_000)
    return () => clearInterval(h)
  }, [])
  // the tabs sit side by side: swipe sideways or tap the bar
  // a tap on the bar scrolls there smoothly: the tabs passed on the way don't count
  const target = useRef<number | null>(null)
  useEffect(() => {
    const el = pager.current
    if (!el) return
    const i = TABS.indexOf(current)
    if (Math.round(el.scrollLeft / el.clientWidth) !== i) {
      target.current = i
      el.scrollTo({ left: i * el.clientWidth, behavior: "smooth" })
    }
  }, [current])
  const onScroll = () => {
    const el = pager.current
    if (!el) return
    if (target.current != null) {
      if (Math.abs(el.scrollLeft - target.current * el.clientWidth) < 2) target.current = null
      return
    }
    // swiping: the highlight moves as soon as the next tab is more than half in view
    const i = Math.round(el.scrollLeft / el.clientWidth)
    if (TABS[i] && TABS[i] !== tab.value) tab.value = TABS[i]
  }
  if (!st) return null
  const top = routes[routes.length - 1]
  return <div class="fill">
    <div ref={pager} class="fill" onScroll={onScroll} onTouchStart={() => { target.current = null }} style={{
      display: "flex", overflowX: "auto", overflowY: "hidden", scrollSnapType: "x mandatory", scrollbarWidth: "none",
    }}>
      {TABS.map((t) => <section key={t} style={{ position: "relative", flex: "0 0 100%", height: "100%", scrollSnapAlign: "start", scrollSnapStop: "always" }}>
        {/* a tab covered by an opened screen (or not the current one) doesn't play videos */}
        <OnTop.Provider value={routes.length === 0 && current === t}>
          {t === "home" ? <HomeScreen st={st} /> : t === "rules" ? <RulesScreen st={st} /> : <TabScreen tab={t} />}
        </OnTop.Provider>
      </section>)}
    </div>
    {(!online.value || pending.value > 0) && <div class="row" style={{
      position: "absolute", left: "50%", transform: "translateX(-50%)", bottom: "calc(var(--safe-bottom) + var(--tabbar) + 10px)", zIndex: 25,
      borderRadius: 999, background: "rgba(43,24,48,0.94)", padding: "7px 14px", gap: 8, whiteSpace: "nowrap",
    }}>
      <PixelIcon name="storm" size={16} />
      <span class="t-small" style={{ color: "var(--text)" }}>{pending.value > 0 ? S.value.offlinePending(pending.value) : S.value.offlineNow}</span>
    </div>}
    <nav class="tabbar"><div>
      {TABS.map((t) => {
        const sel = t === current && !routes.some((r) => !keepsTabs(r))
        const badge = t === "chats" ? st.unread.messages : t === "feed" ? st.unread.notifications : 0
        return <button key={t} aria-label={t} onClick={() => nav.selectTab(t)}>
          {t === "profile"
            ? <div style={{ opacity: t === current ? 1 : .55 }}><Avatar name={st.user.display_name} url={st.user.avatar} size={24} /></div>
            : <div style={{ position: "relative" }}>
              <PixelIcon name={ICONS[t]} size={24} alpha={t === current ? 1 : .4} />
              {badge > 0 && <i style={{ position: "absolute", top: -2, right: -4, width: 8, height: 8, background: "var(--orange)" }} />}
            </div>}
          <i class="dot" style={{ background: sel ? "var(--text)" : "transparent" }} />
        </button>
      })}
    </div></nav>
    {top && <RouteLayer key={routes.length + ":" + JSON.stringify(top)} route={top} />}
  </div>
}

/** An opened screen; swiping from the left edge goes back (iOS has no back button). */
function RouteLayer({ route }: { route: Route }) {
  const ref = useRef<HTMLDivElement>(null)
  useEffect(() => {
    const el = ref.current
    if (!el) return
    let x0 = -1, y0 = 0, dx = 0
    const down = (e: TouchEvent) => { const t = e.touches[0]; if (t.clientX < 24) { x0 = t.clientX; y0 = t.clientY; dx = 0 } }
    const move = (e: TouchEvent) => {
      if (x0 < 0) return
      const t = e.touches[0]
      dx = t.clientX - x0
      if (Math.abs(t.clientY - y0) > Math.abs(dx) && dx < 30) { x0 = -1; el.style.transform = ""; return }
      if (dx > 0) el.style.transform = `translateX(${dx}px)`
    }
    const up = () => {
      if (x0 < 0) return
      x0 = -1
      if (dx > el.clientWidth * .3) nav.pop()
      else el.style.transform = ""
    }
    el.addEventListener("touchstart", down, { passive: true })
    el.addEventListener("touchmove", move, { passive: true })
    el.addEventListener("touchend", up)
    return () => { el.removeEventListener("touchstart", down); el.removeEventListener("touchmove", move); el.removeEventListener("touchend", up) }
  }, [])
  return <div ref={ref} class={"route" + (keepsTabs(route) ? " keep-tabs" : "")}><RouteScreen route={route} /></div>
}
