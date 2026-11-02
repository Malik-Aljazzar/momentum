// App state + server access, the PWA's AppViewModel (android/.../AppViewModel.kt). Works offline:
// the last state and visited pages are cached, rule/decay/settings changes and taps are applied
// locally and queued, and the queue is sent in order when the network is back (the server stays
// the source of truth).
import { batch, signal } from "@preact/signals"
import { api, ApiError } from "./api"
import { pendingReleases, type Release } from "./changelog"
import { fmt } from "./format"
import { googleReturn } from "./googlelogin"
import { applyLocal, applyTap, currentDay, moveRule as moveLocal, offlineCapable, OFFLINE_SETTINGS, revertLocal } from "./logic"
import type { AppState, BulkResult, Event, MediaItem, Meta, Rule, StateResponse } from "./model"
import { cache, local } from "./storage"
import { lang, S, setLang as setLangOnly } from "./strings"
import { subscribe as pushSubscribe, unsubscribe as pushUnsubscribe } from "./push"
import { stopE2E } from "./e2e"

export type Screen = "loading" | "offline" | "login" | "main"

interface PendingOp { id: string; method: string; path: string; body?: unknown; eventId?: number }
export interface Pop { ruleId: number; text: string; nonce: number }
export interface Toast { text: string; error: boolean; nonce: number }

const EMAIL = /^[a-z0-9._%+\-]+@[a-z0-9.\-]+\.[a-z]{2,}$/
const TEMP_RULE = /\/rules\/(-\d+)/

export const screen = signal<Screen>("loading")
export const meta = signal<Meta | null>(local.get("meta", null))
export const state = signal<AppState | null>(null)
export const pop = signal<Pop | null>(null)
export const toast = signal<Toast | null>(null)
export const milestone = signal(false)
export const hyperspace = signal(false)
/** HYPERSPACE / ULTRA_HYPERSPACE while the lore screen (tap on the shining word) is open */
export const hyperLore = signal<string | null>(null)
export const online = signal(typeof navigator === "undefined" ? true : navigator.onLine)
export const updateRequired = signal(false)
/** after an update: the changelog's versions this device hasn't seen yet (null = nothing to show) */
export const whatsNew = signal<Release[] | null>(null)
export const systemFont = signal(local.get("system_font", false))
export const notifications = signal(local.get("notifications", true))
export const devMode = signal(local.get("dev", false))
/** bumped after posting: the feed loads again */
export const feedVersion = signal(0)
/** bumped after each successful sync: images that failed to load try again */
export const imageRetry = signal(0)
/** the app is visible (not in the background) */
export const foreground = signal(typeof document === "undefined" ? true : document.visibilityState !== "hidden")

const ops: PendingOp[] = local.get("outbox", [])
const tempRules: Record<string, string> = local.get("temp_rules", {})
export const pending = signal(ops.length)

api.token = local.get<string | null>("token", null)
api.language = lang.value
lang.subscribe((l) => { api.language = l })
api.onUpdateRequired = () => { updateRequired.value = true }

let tempCounter = 0
const tempId = () => -((Math.floor(Date.now() / 1000) % 100_000_000) * 10 + (tempCounter++ % 10)) - 1
const uuid = () => (crypto.randomUUID ? crypto.randomUUID() : Math.random().toString(36).slice(2) + Date.now().toString(36))

export function showToast(text: string, error = false) {
  toast.value = { text, error, nonce: performance.now() }
}

function putState(s: AppState) {
  state.value = s
  if (foreground.value) {   // on screen: what's in the state is seen
    local.set("last_message_seen", Math.max(local.get("last_message_seen", 0), s.unread.latest_message_id ?? 0))
    local.set("last_notification_seen", Math.max(local.get("last_notification_seen", 0), s.unread.latest_notification_id ?? 0))
  }
  local.set("state", s)
}

function saveOps() {
  local.set("outbox", ops)
  pending.value = ops.length
}

const message = (e: ApiError) => (e.status === 0 ? S.value.needsInternet : e.message || "Error")

function handle(e: ApiError) {
  if (e.status === 401) { showToast(S.value.sessionExpired, true); logoutLocal() }
  else if (e.status === 0) { online.value = false; showToast(S.value.needsInternet, true) }
  else showToast(message(e), true)
}
export const onError = handle

// ---------------------------------------------------------------- start, sync

export async function boot() {
  screen.value = "loading"
  // a new build: what changed (shown once the main screen is up)
  const news = pendingReleases(!!api.token)
  if (news.length) whatsNew.value = news
  // back from the Google sign-in (googlelogin.ts): take the token, clear the fragment
  const back = googleReturn(location.hash)
  if (back) {
    history.replaceState(null, "", location.pathname + location.search)
    if ("token" in back) { api.token = back.token; local.set("token", back.token) }
    else if (back.failed) showToast(S.value.googleFailed, true)
  }
  let m: Meta | null
  try {
    m = await api.call<Meta>("GET", "/meta")
    local.set("meta", m)
    online.value = true
  } catch (e) {
    if (e instanceof ApiError && e.status === 0) online.value = false
    m = local.get<Meta | null>("meta", null)
  }
  if (!m) { screen.value = "offline"; return }
  meta.value = m
  if (!api.token) { screen.value = online.value ? "login" : "offline"; return }
  const cached = local.get<AppState | null>("state", null)
  if (cached) { state.value = currentDay(cached); screen.value = "main" }
  if (online.value && await sync()) {
    screen.value = "main"
    if (notifications.value) void pushSubscribe(lang.value)
  } else if (!state.value) screen.value = "offline"
}

let syncing: Promise<boolean> | null = null

/** Send queued offline changes in order, then load the fresh state. True if online. */
export function sync(): Promise<boolean> {
  syncing ??= doSync().finally(() => { syncing = null })
  return syncing
}

async function doSync(): Promise<boolean> {
  while (ops.length) {
    const op = ops[0]
    const path = resolveTempIds(op.path)
    if (path == null) { ops.shift(); saveOps(); continue }      // e.g. the offline-created rule was rejected
    try {
      const text = await api.raw(op.method, path, op.body)
      try {
        const r = JSON.parse(text) as StateResponse
        if (r.state) {
          putState(r.state)
          if (r.capped) cappedToast(r.delta ?? 0)
        }
      } catch { /* not a state answer */ }
      ops.shift(); saveOps()
    } catch (e) {
      if (!(e instanceof ApiError)) throw e
      if (e.status === 0) { online.value = false; return false }
      if (e.status === 401) { logoutLocal(); return false }
      ops.shift(); saveOps()                     // rejected by the server (e.g. daily limit): drop it
      showToast(message(e), true)
    }
  }
  try {
    putState((await api.call<StateResponse>("GET", "/state")).state)
    online.value = true
    imageRetry.value++
    return true
  } catch (e) {
    if (e instanceof ApiError) {
      if (e.status === 0) online.value = false
      if (e.status === 401) logoutLocal()
    }
    return false
  }
}

/** Rules created offline have negative ids until the server knows them (matched via client_id). */
function resolveTempIds(path: string): string | null {
  const m = path.match(TEMP_RULE)
  if (!m) return path
  const clientId = tempRules[m[1]]
  if (!clientId) return null
  const real = state.value?.rules.find((r) => r.client_id === clientId && r.id > 0)?.id
  return real == null ? null : path.replace(m[0], `/rules/${real}`)
}

/** Re-fetch state (sends pending changes first). */
export async function refresh() {
  if (screen.value === "main" && navigator.onLine) await sync()
  else if (state.value) {
    const st = currentDay(state.value)
    if (st !== state.value) putState(st)
  }
}

export async function auth(login: string, password: string, register: boolean): Promise<boolean> {
  if (register && !EMAIL.test(login.trim().toLowerCase())) { showToast(S.value.invalidEmail, true); return false }
  try {
    meta.value ??= await api.call<Meta>("GET", "/meta")
    const body = { [register ? "email" : "login"]: login, password, language: lang.value }
    const { token } = await api.call<{ token: string }>("POST", register ? "/auth/register" : "/auth/login", body)
    api.token = token
    local.set("token", token)
    putState((await api.call<StateResponse>("GET", "/state")).state)
    online.value = true
    screen.value = "main"
    if (notifications.value) void pushSubscribe(lang.value)
    return true
  } catch (e) {
    if (e instanceof ApiError) showToast(message(e), true)
    return false
  }
}

// ---------------------------------------------------------------- changes (offline-capable)

/** Call returning a new state. Rule/decay/settings changes are queued when offline. */
export async function save(method: string, path: string, body?: Record<string, unknown>, quiet = false): Promise<boolean> {
  // new rules get a client id, so a retried offline create can't duplicate them
  const payload = method === "POST" && path === "/rules" && body && !("client_id" in body) ? { ...body, client_id: uuid() } : body
  const offlineOk = offlineCapable(method, path)
  if (!online.value) {
    if (offlineOk) return queue(method, path, payload, quiet)
    showToast(S.value.needsInternet, true)
    return false
  }
  try {
    putState((await api.call<StateResponse>(method, path, payload)).state)
    if (!quiet && method !== "DELETE") showToast(S.value.saved)
    return true
  } catch (e) {
    if (!(e instanceof ApiError)) throw e
    if (e.status === 0) {
      online.value = false
      if (offlineOk) return queue(method, path, payload, quiet)
      showToast(S.value.needsInternet, true)
      return false
    }
    handle(e)
    return false
  }
}

function queue(method: string, path: string, body: Record<string, unknown> | undefined, quiet: boolean): boolean {
  let payload = body
  if (path === "/settings" && body) {
    const allowed = Object.fromEntries(Object.entries(body).filter(([k]) => OFFLINE_SETTINGS.has(k)))
    if (Object.keys(allowed).length < Object.keys(body).length) showToast(S.value.needsInternet, true)
    if (Object.keys(allowed).length === 0) return false
    payload = allowed
  }
  const st = state.value
  if (!st) return false
  putState(applyLocal(st, method, path, payload, () => {
    const id = tempId()
    if (method === "POST" && path === "/rules") {
      tempRules[String(id)] = String(payload?.client_id ?? uuid())
      local.set("temp_rules", tempRules)
    }
    return id
  }))
  ops.push({ id: uuid(), method, path, body: payload })
  saveOps()
  if (!quiet && method !== "DELETE" && path !== "/settings") showToast(S.value.saved)
  return true
}

// ---------------------------------------------------------------- taps, undo

/** Capped by the daily limit: what was credited (+0: only the automatic rule started over). */
const cappedToast = (amount: number) => showToast(amount < 1 ? S.value.limitZeroTap : S.value.limitPartial(fmt(amount)), true)

let tapSeq = 0

export async function trigger(rule: Rule) {
  const opId = uuid()
  const at = new Date().toISOString()
  const body = { at, op_id: opId }
  const before = state.value
  if (!before || rule.tappable === false) return       // the card shows the "no tap" sign
  // show the result right away (same rules as the server), the server confirms in the background
  const tap = applyTap(before, rule, at, tempId, (i) => meta.value?.vehicles[i]?.key ?? "")
  if (!tap) { showToast(S.value.limitReached, true); return }
  batch(() => {
    putState(tap.state)
    if (tap.milestone) milestone.value = true
    if (tap.hyperspace) hyperspace.value = true
    pop.value = { ruleId: rule.id, text: (tap.actual >= 0 ? "+" : "−") + fmt(Math.abs(tap.actual)), nonce: performance.now() }
  })
  const seq = ++tapSeq
  if (online.value && rule.id > 0) {
    try {
      const res = await api.call<StateResponse>("POST", `/rules/${rule.id}/trigger`, body)
      if (seq === tapSeq) putState(res.state)     // a newer tap's response brings the newer state
      if (res.milestone) milestone.value = true
      if (res.hyperspace) hyperspace.value = true
      if (res.capped && res.state.settings.daily_limit !== false) cappedToast(res.delta ?? 0)
      return
    } catch (e) {
      if (!(e instanceof ApiError)) throw e
      if (e.status !== 0) {               // rejected (e.g. daily limit): take the optimistic tap back
        batch(() => { putState(before); milestone.value = false; hyperspace.value = false })
        handle(e)
        return
      }
      online.value = false
    }
  }
  // offline (or a rule only known locally): keep the local result and queue the tap
  if (tap.capped) cappedToast(tap.amount)
  ops.push({ id: opId, method: "POST", path: `/rules/${rule.id}/trigger`, body, eventId: tap.eventId })
  saveOps()
  if (online.value) await sync()            // the rule was only known locally: sync creates it first
}

export async function undo(event: Event) {
  const keys = meta.value?.vehicles.map((v) => v.key) ?? []
  if (event.id < 0) {                       // still in the queue: just drop it
    const left = ops.filter((o) => o.eventId !== event.id)
    ops.splice(0, ops.length, ...left)
    saveOps()
    if (state.value) putState(revertLocal(state.value, event, keys))
    return
  }
  if (online.value) {
    try {
      putState((await api.call<StateResponse>("POST", `/events/${event.id}/undo`)).state)
      return
    } catch (e) {
      if (!(e instanceof ApiError)) throw e
      if (e.status !== 0) { handle(e); return }
      online.value = false
    }
  }
  if (state.value) putState(revertLocal(state.value, event, keys))
  ops.push({ id: uuid(), method: "POST", path: `/events/${event.id}/undo` })
  saveOps()
}

let orderTimer: ReturnType<typeof setTimeout> | undefined

/** Moves a rule one place up (-1) or down (+1) among its kind; shown at once, saved shortly after. */
export function moveRule(id: number, step: number) {
  const st = state.value
  if (!st) return
  const rules = moveLocal(st.rules, id, step)
  if (!rules) return
  state.value = { ...st, rules }
  clearTimeout(orderTimer)
  orderTimer = setTimeout(() => {           // several taps in a row are saved together
    void save("PUT", "/rules/order", { ids: rules.filter((r) => r.id > 0).map((r) => r.id) }, true)
  }, 700)
}

// ---------------------------------------------------------------- online-only calls

/** Call without state in the response. */
export async function call(method: string, path: string, body?: unknown): Promise<boolean> {
  if (!online.value) { showToast(S.value.needsInternet, true); return false }
  try {
    await api.raw(method, path, body)
    return true
  } catch (e) {
    if (e instanceof ApiError) handle(e)
    return false
  }
}

/**
 * Typed request for the social parts. GET responses are cached and shown when offline
 * (chats, messages, feed, profiles); other requests need the connection.
 */
export async function req<T>(method: string, path: string, body?: unknown): Promise<T | null> {
  if (method === "GET") {
    try {
      const text = await api.raw(method, path, body)
      void cache.set("get:" + path, text)
      online.value = true
      return JSON.parse(text) as T
    } catch (e) {
      if (!(e instanceof ApiError)) throw e
      if (e.status === 0) {
        online.value = false
        const text = await cache.get("get:" + path)
        return text ? (JSON.parse(text) as T) : null
      }
      handle(e)
      return null
    }
  }
  if (!online.value) { showToast(S.value.needsInternet, true); return null }
  try {
    return await api.call<T>(method, path, body)
  } catch (e) {
    if (!(e instanceof ApiError)) throw e
    if (e.status === 0) online.value = false
    handle(e)
    return null
  }
}

/** A bulk action; the new state is applied, the result tells what was skipped. */
export async function bulk(path: string, body: unknown): Promise<BulkResult | null> {
  if (!online.value) { showToast(S.value.needsInternet, true); return null }
  try {
    const r = await api.call<BulkResult>("POST", path, body)
    putState(r.state)
    return r
  } catch (e) {
    if (e instanceof ApiError) handle(e)
    return null
  }
}

/** Upload a photo / video / audio (already prepared: images shrunk). */
export async function uploadMedia(data: Blob, type: string, query = ""): Promise<MediaItem | null> {
  if (!online.value) { showToast(S.value.needsInternet, true); return null }
  try {
    return (await api.upload<{ media: MediaItem }>("/media" + query, data, type)).media
  } catch (e) {
    if (!(e instanceof ApiError)) throw e
    // a failed upload isn't a lost connection: don't switch the app to offline
    if (e.status === 0) showToast(S.value.uploadFailed, true)
    else handle(e)
    return null
  }
}

export async function uploadAvatar(jpeg: Blob): Promise<boolean> {
  if (!online.value) { showToast(S.value.needsInternet, true); return false }
  try {
    putState((await api.upload<StateResponse>("/settings/avatar", jpeg, "image/jpeg")).state)
    return true
  } catch (e) {
    if (e instanceof ApiError) handle(e)
    return false
  }
}

export async function logout(callApi = true) {
  await pushUnsubscribe()                 // while the session is still valid
  if (callApi && online.value) { try { await api.raw("POST", "/auth/logout") } catch { /* gone anyway */ } }
  logoutLocal()
}

function logoutLocal() {
  stopE2E()
  api.token = null
  local.set("token", null)
  local.set("state", null)
  state.value = null
  ops.length = 0
  for (const k of Object.keys(tempRules)) delete tempRules[k]
  local.set("temp_rules", tempRules)
  saveOps()
  void cache.clear()          // cached chats/feed belong to this account
  screen.value = "login"
}

export function changeSystemFont(on: boolean) { systemFont.value = on; local.set("system_font", on) }
export function changeNotifications(on: boolean) {
  notifications.value = on
  local.set("notifications", on)
  if (on) void pushSubscribe(lang.value); else void pushUnsubscribe()
}

/** Language change: the server sends the notifications in the new language. */
export function setLang(l: "de" | "en") {
  setLangOnly(l)
  if (notifications.value) void pushSubscribe(l)
}
export function toggleDevMode() {
  devMode.value = !devMode.value
  local.set("dev", devMode.value)
  showToast(devMode.value ? S.value.devOn : S.value.devOff)
}

/** Server-relative media paths ("/media/...", "/avatars/...") stay as they are: same origin. */
export const abs = (url?: string | null) => url ?? null

// connection changes and coming back to the app
if (typeof window !== "undefined") {
  window.addEventListener("online", () => {
    online.value = true
    if (screen.value === "main") void sync()
    else if (screen.value === "offline") void boot()
  })
  window.addEventListener("offline", () => { online.value = false })
  document.addEventListener("visibilitychange", () => {
    foreground.value = document.visibilityState !== "hidden"
    if (foreground.value) void refresh()
  })
}
