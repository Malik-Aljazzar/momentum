// Extensions (server/extensions.py): optional tabs a player downloads in the settings. The page runs in a sandboxed
// iframe (scripts only = an opaque origin: no token, no app storage; its CSP allows no network) and talks to the app
// through the bridge (server/shipped_extensions/sdk.js) - this file answers it with the player's data on the server.
// = android .../data/Extensions.kt
import { api, ApiError } from "./api"
import type { InstalledExtension, Texts } from "./model"
import { cache } from "./storage"
import { extensionTap, extensionUndo, showToast, state } from "./store"
import { lang } from "./strings"

export interface BridgeRequest { momentum: 1; id: number; method: string; params: Record<string, unknown> }
export interface BridgeAnswer { momentum: 1; id: number; result?: unknown; error?: { message: string; status: number } }
/** What the app gives the bridge (the server calls and the rest), swapped out in tests. */
export interface BridgeDeps {
  call: (method: "GET" | "PUT" | "DELETE", path: string, body?: unknown) => Promise<unknown>
  toast: (text: string) => void
  context: () => unknown
  /** the manifest's permissions ("rules": rules.list / tap / undo) */
  permissions: string[]
  /** the player's tappable rules */
  rules: () => unknown[]
  /** one tap of a rule, like the player's (online): {event, delta, capped} */
  tap: (rule: number) => Promise<unknown>
  /** a tap taken back */
  undo: (event: number) => Promise<unknown>
}

/** A bridge message from the page is at most this long (a document is at most 16 KB on the server). */
export const MAX_MESSAGE = 64 * 1024

/** The page's message (JSON text) as a request; null when it's something else. */
export function parseRequest(data: unknown): BridgeRequest | null {
  if (typeof data !== "string" || data.length > MAX_MESSAGE) return null
  try {
    const m = JSON.parse(data)
    if (m?.momentum !== 1 || typeof m.id !== "number" || typeof m.method !== "string") return null
    return { momentum: 1, id: m.id, method: m.method, params: m.params && typeof m.params === "object" ? m.params : {} }
  } catch {
    return null
  }
}

const seg = (v: unknown) => encodeURIComponent(String(v ?? ""))
const ORDERS = ["key", "-key", "updated", "-updated"]

/** Answers one request of extension [id] (the server checks everything again: its own data only). */
export async function answer(id: string, r: BridgeRequest, deps: BridgeDeps): Promise<BridgeAnswer> {
  const p = r.params
  const base = `/extensions/${seg(id)}/data/${seg(p.collection)}`
  try {
    let result: unknown
    switch (r.method) {
      case "context": result = deps.context(); break
      case "toast": deps.toast(String(p.text ?? "").slice(0, 140)); result = true; break
      case "data.get":
        try {
          result = ((await deps.call("GET", `${base}/${seg(p.key)}`)) as { doc: { value: unknown } }).doc.value
        } catch (e) {
          if (e instanceof ApiError && e.status === 404) result = null
          else throw e
        }
        break
      case "data.put": result = ((await deps.call("PUT", `${base}/${seg(p.key)}`, { value: p.value })) as { doc: unknown }).doc; break
      case "data.delete": await deps.call("DELETE", `${base}/${seg(p.key)}`); result = true; break
      case "rules.list": case "rules.tap": case "rules.undo": {
        if (!deps.permissions.includes("rules")) return { momentum: 1, id: r.id, error: { message: "permission rules missing", status: 403 } }
        const n = Math.floor(Number(r.method === "rules.undo" ? p.event : p.rule))
        if (r.method === "rules.list") result = deps.rules()
        else if (!Number.isFinite(n) || n <= 0) return { momentum: 1, id: r.id, error: { message: "a rule / event id expected", status: 400 } }
        else if (r.method === "rules.tap") result = await deps.tap(n)
        else { await deps.undo(n); result = true }
        break
      }
      case "data.list": {
        const offset = Math.max(0, Math.floor(Number(p.offset) || 0))
        const limit = Math.min(100, Math.max(1, Math.floor(Number(p.limit) || 30)))
        const order = ORDERS.includes(String(p.order)) ? String(p.order) : "key"
        result = await deps.call("GET", `${base}?offset=${offset}&limit=${limit}&order=${encodeURIComponent(order)}`)
        break
      }
      default: return { momentum: 1, id: r.id, error: { message: `unknown method ${r.method}`, status: 400 } }
    }
    return { momentum: 1, id: r.id, result }
  } catch (e) {
    const status = e instanceof ApiError ? e.status : 0
    return { momentum: 1, id: r.id, error: { message: e instanceof Error ? e.message : String(e), status } }
  }
}

/** The app's bridge: the real server, the toast, the player; `tab` = the manifest key of the tab the page is shown as. */
export function appDeps(ext: InstalledExtension, tab: string | null = null): BridgeDeps {
  return {
    call: (method, path, body) => api.call(method, path, body),
    toast: (text) => showToast(text),
    context: () => {
      const u = state.value?.user
      return { api: 1, lang: lang.value, user: { username: u?.username ?? "", display_name: u?.display_name ?? "" },
        extension: { id: ext.id, version: ext.version }, tab }
    },
    permissions: ext.permissions ?? [],
    rules: () => (state.value?.rules ?? []).filter((x) => x.tappable !== false && x.id > 0)
      .map((x) => ({ id: x.id, name: x.name, icon: x.icon, kind: x.kind, value: x.value })),
    tap: (rule) => extensionTap(rule),
    undo: (event) => extensionUndo(event),
  }
}

/** The name in the app's language. */
export const textOf = (t: Texts, l: "de" | "en") => t[l] || t.en || t.de

/** The page with the server's address filled in (its <base> and CSP, server/extensions.py compose). */
export const withOrigin = (page: string, origin: string) => page.split("__ORIGIN__").join(origin)

const pageKey = (id: string) => "ext-page:" + id

/** Keeps a downloaded page on the phone (it opens offline, too). */
export async function storePage(id: string, version: string, page: string) {
  await cache.set(pageKey(id), JSON.stringify({ version, page }))
}

/** The extension's page: the stored copy of this version, else downloaded again (null: offline and none stored). */
export async function pageOf(ext: InstalledExtension): Promise<string | null> {
  const stored = await cache.get(pageKey(ext.id))
  let old: { version: string; page: string } | null = null
  try { old = stored ? JSON.parse(stored) : null } catch { old = null }
  if (old?.version === ext.version) return old.page
  try {
    const r = await api.call<{ version: string; page: string }>("GET", `/extensions/${seg(ext.id)}/page`)
    await storePage(ext.id, r.version, r.page)
    return r.page
  } catch {
    return old?.page ?? null          // offline: the older copy is better than nothing
  }
}

/** Gone with the extension (removed in the settings). */
export async function forgetPage(id: string) {
  await cache.set(pageKey(id), "")
}
