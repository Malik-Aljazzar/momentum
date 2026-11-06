// System notifications for the PWA via Web Push (server/push.py sends them in real time, also
// while the app is closed). iOS: only for the installed PWA (home screen, iOS 16.4+) and only
// after the user allowed them from a tap.
import { signal } from "@preact/signals"
import { api } from "./api"

export type Permission = "granted" | "denied" | "default" | "unsupported"

/** Read in the app: close the shown notifications whose tag matches (chat-<id>, note-<id>). */
export async function clearNotifications(match: (tag: string) => boolean) {
  try {
    const reg = await registration()
    for (const n of (await reg?.getNotifications()) ?? []) if (match(n.tag)) n.close()
  } catch { /* not supported: nothing to clear */ }
}

export function notifyPermission(): Permission {
  return typeof Notification === "undefined" ? "unsupported" : Notification.permission
}

/** Running from the home screen (iOS shows notifications only then). */
export const installed = () =>
  matchMedia("(display-mode: standalone)").matches || (navigator as Navigator & { standalone?: boolean }).standalone === true

export const isIos = () => /iPhone|iPad|iPod/.test(navigator.userAgent) || (navigator.platform === "MacIntel" && navigator.maxTouchPoints > 1)

/** What the home tab's card says while notifications are off (calls ring only while the app is open; = Android
 *  `notifyHint` in data/Notifier.kt): "ask" - a tap may still allow them, "denied" - only the phone's settings can,
 *  "install" - on an iPhone only the home-screen app gets them. Nothing when the player switched them off in the app
 *  (wanted false) or tapped "not now" ([hiddenUntil], NOTIFY_HINT_DAYS). */
export type NotifyHint = "ask" | "denied" | "install" | null
export const NOTIFY_HINT_DAYS = 7
const HINT_KEY = "notify_hint_until"

/** "Not now" on the card or the first start's question: until then the card stays away. */
export const notifyHintUntil = signal((() => { try { return Number(localStorage.getItem(HINT_KEY) ?? 0) || 0 } catch { return 0 } })())

export function postponeNotifyHint(now = Date.now()) {
  const until = now + NOTIFY_HINT_DAYS * 86_400_000
  try { localStorage.setItem(HINT_KEY, String(until)) } catch { /* private mode: until the next start */ }
  notifyHintUntil.value = until
}

export function notifyHint(o: { wanted: boolean; permission: Permission; ios: boolean; installed: boolean; hiddenUntil: number; now: number }): NotifyHint {
  if (!o.wanted || o.now < o.hiddenUntil) return null
  if (o.ios && !o.installed) return "install"
  if (o.permission === "default") return "ask"
  if (o.permission === "denied") return "denied"
  return null                                      // allowed, or a browser without notifications
}

/** Asks for permission (must run from a tap) and subscribes this device. */
export async function requestNotifications(lang: string): Promise<Permission> {
  if (typeof Notification === "undefined") return "unsupported"
  let p: Permission
  try { p = await Notification.requestPermission() } catch { p = Notification.permission }
  if (p === "granted") await subscribe(lang)
  return p
}

const b64ToBytes = (b64: string) => {
  const s = atob(b64.replace(/-/g, "+").replace(/_/g, "/") + "=".repeat((4 - (b64.length % 4)) % 4))
  return Uint8Array.from(s, (c) => c.charCodeAt(0))
}

async function registration() {
  if (!("serviceWorker" in navigator) || !("PushManager" in window)) return null
  return (await navigator.serviceWorker.getRegistration("/app/")) ?? null
}

/** Subscribe this device for pushes (allowed already) and tell the server; safe to call often. */
export async function subscribe(lang: string): Promise<boolean> {
  try {
    if (notifyPermission() !== "granted" || !api.token) return false
    const reg = await registration()
    if (!reg) return false
    let sub = await reg.pushManager.getSubscription()
    if (!sub) {
      const { key } = await api.call<{ key: string }>("GET", "/push/vapid")
      sub = await reg.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: b64ToBytes(key) })
    }
    await api.call("POST", "/push/register", { platform: "web", subscription: sub.toJSON(), lang })
    return true
  } catch {
    return false
  }
}

/** Logout / notifications off: no more pushes to this device. */
export async function unsubscribe() {
  try {
    const sub = await (await registration())?.pushManager.getSubscription()
    if (!sub) return
    if (api.token) await api.raw("DELETE", "/push/register", { endpoint: sub.endpoint }).catch(() => {})
    await sub.unsubscribe()
  } catch { /* nothing to undo */ }
}

/** A notification right now, through the service worker (to check that they arrive). */
export async function testNotification(title: string, body: string, lang: string) {
  if (notifyPermission() !== "granted" && (await requestNotifications(lang)) !== "granted") return
  const reg = await registration()
  if (reg) await reg.showNotification(title, { body, icon: "/app/icon-192.png", data: { url: "/app/" } })
  else new Notification(title, { body, icon: "/app/icon-192.png" })
}
