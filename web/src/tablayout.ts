// The player's tabs (server/tablayout.py): up to MAX_TOP places in the bottom bar, each a tab or a group (any number of
// tabs; its page has a tab menu at the top showing their names, icons or both). Tabs that aren't placed are hidden; the
// profile always stays. Pure functions for the app and the editor (settings -> tabs). = android .../data/TabLayout.kt
import type { InstalledExtension, Texts } from "./model"

export type TabShow = "icons" | "names" | "both"
export interface TabGroup { id: string; name: string; icon: string; show: TabShow; tabs: string[] }
export interface TabEntry { tab: string }
export type TabItem = TabEntry | TabGroup
export type Layout = TabItem[]

export const MAX_TOP = 5
export const STANDARD = ["rules", "chats", "feed", "profile"]
export const DEFAULT_LAYOUT: Layout = STANDARD.map((tab) => ({ tab }))
export const SHOWS: TabShow[] = ["icons", "names", "both"]

export const isGroup = (i: TabItem): i is TabGroup => !("tab" in i)
/** a tab key or a group id */
export const keyOf = (i: TabItem) => isGroup(i) ? i.id : i.tab
export const extKey = (ext: string, key: string) => `ext:${ext}:${key}`

/** "ext:<extension>:<tab>" -> [extension, tab]; null for a standard tab. */
export function parseExtKey(k: string): [string, string] | null {
  const m = /^ext:([^:]+):(.+)$/.exec(k)
  return m ? [m[1], m[2]] : null
}

/** The tabs a player can place now: the standard ones, then those of the switched-on extensions. */
export function availableTabs(exts: InstalledExtension[] | null | undefined): string[] {
  return [...STANDARD, ...(exts ?? []).filter((e) => e.enabled).flatMap((e) => (e.tabs ?? []).map((t) => extKey(e.id, t.key)))]
}

/** An extension tab's manifest entry (name, icon); null when its extension isn't there. */
export function extTab(exts: InstalledExtension[] | null | undefined, k: string): { name: Texts; icon: string } | null {
  const p = parseExtKey(k)
  if (!p) return null
  return (exts ?? []).find((e) => e.id === p[0])?.tabs?.find((t) => t.key === p[1]) ?? null
}

/** The layout as shown: the stored one (or the default) without tabs that aren't available (an extension switched off
 *  or gone); the profile is always somewhere. */
export function effectiveLayout(stored: Layout | null | undefined, available: string[]): Layout {
  const ok = new Set(available)
  const out: Layout = []
  for (const i of stored ?? DEFAULT_LAYOUT) {
    if (isGroup(i)) out.push({ ...i, tabs: i.tabs.filter((k) => ok.has(k)) })
    else if (ok.has(i.tab)) out.push({ tab: i.tab })
  }
  if (!locate(out, "profile")) out.push({ tab: "profile" })
  return out
}

/** Tabs placed nowhere (hidden), in the order of `available`. */
export function hiddenTabs(layout: Layout, available: string[]): string[] {
  return available.filter((k) => !locate(layout, k))
}

/** Where a tab is: its place in the bar and its group (null = right in the bar); null when it's hidden. */
export function locate(layout: Layout, key: string): { index: number; group: string | null } | null {
  for (let i = 0; i < layout.length; i++) {
    const it = layout[i]
    if (isGroup(it) ? it.tabs.includes(key) : it.tab === key) return { index: i, group: isGroup(it) ? it.id : null }
  }
  return null
}

const groupOf = (layout: Layout, id: string) => layout.find((i): i is TabGroup => isGroup(i) && i.id === id)
const swap = <T>(list: T[], i: number, j: number): T[] | null => {
  if (j < 0 || j >= list.length) return null
  const out = [...list]
  ;[out[i], out[j]] = [out[j], out[i]]
  return out
}

// ---- editing (settings -> tabs): each returns the new layout, or null when that move isn't possible

/** One step up (-1) / down (+1) within its own list (the bar, or its group); `key` = a tab or a group id. */
export function move(layout: Layout, key: string, dir: -1 | 1): Layout | null {
  const top = layout.findIndex((i) => keyOf(i) === key)
  if (top >= 0) return swap(layout, top, top + dir)
  const at = locate(layout, key)
  if (!at?.group) return null
  const g = layout[at.index] as TabGroup
  const tabs = swap(g.tabs, g.tabs.indexOf(key), g.tabs.indexOf(key) + dir)
  return tabs && layout.map((i, n) => n === at.index ? { ...g, tabs } : i)
}

/** A tab in the bar joins the group right above it (at its end). */
export function indent(layout: Layout, key: string): Layout | null {
  const i = layout.findIndex((x) => !isGroup(x) && x.tab === key)
  const above = layout[i - 1]
  if (i < 1 || !isGroup(above)) return null
  return [...layout.slice(0, i - 1), { ...above, tabs: [...above.tabs, key] }, ...layout.slice(i + 1)]
}

/** A tab in a group goes into the bar, right after its group (the bar needs room). */
export function outdent(layout: Layout, key: string): Layout | null {
  const at = locate(layout, key)
  if (!at?.group || layout.length >= MAX_TOP) return null
  const g = layout[at.index] as TabGroup
  return [...layout.slice(0, at.index), { ...g, tabs: g.tabs.filter((k) => k !== key) }, { tab: key },
    ...layout.slice(at.index + 1)]
}

/** A tab leaves the layout (hidden); never the profile. */
export function hide(layout: Layout, key: string): Layout | null {
  if (key === "profile" || !locate(layout, key)) return null
  return layout.flatMap((i): TabItem[] => isGroup(i) ? [{ ...i, tabs: i.tabs.filter((k) => k !== key) }]
    : i.tab === key ? [] : [i])
}

/** A group dissolved: its tabs are hidden, except the profile, which takes the group's place. */
export function removeGroup(layout: Layout, id: string): Layout {
  return layout.flatMap((i): TabItem[] => !isGroup(i) || i.id !== id ? [i]
    : i.tabs.includes("profile") ? [{ tab: "profile" }] : [])
}

/** A hidden tab placed: into the bar (at its end; needs room) or at the end of a group. */
export function place(layout: Layout, key: string, group: string | null): Layout | null {
  if (locate(layout, key)) return null
  if (group == null) return layout.length < MAX_TOP ? [...layout, { tab: key }] : null
  const g = groupOf(layout, group)
  return g ? layout.map((i) => i === g ? { ...g, tabs: [...g.tabs, key] } : i) : null
}

/** A new, empty group at the end of the bar (needs room). */
export function addGroup(layout: Layout, name: string, icon: string): Layout | null {
  if (layout.length >= MAX_TOP) return null
  let n = 1
  while (layout.some((i) => isGroup(i) && i.id === `g${n}`)) n++
  return [...layout, { id: `g${n}`, name, icon, show: "icons", tabs: [] }]
}

/** A group's name / icon / what its tab menu shows. */
export function updateGroup(layout: Layout, id: string, patch: Partial<Pick<TabGroup, "name" | "icon" | "show">>): Layout {
  return layout.map((i) => isGroup(i) && i.id === id ? { ...i, ...patch } : i)
}

/** A group's next free default number ("Gruppe 2"). */
export function nextGroupNumber(layout: Layout): number {
  return layout.filter(isGroup).length + 1
}

// ---- the app's pages: one per place in the bar (+ the election's while it runs)

/** A page of the main pager: a tab key, or "group:<id>". */
export const groupPage = (id: string) => `group:${id}`
export const pageGroupId = (page: string) => page.startsWith("group:") ? page.slice(6) : null
export const pagesOf = (layout: Layout) => layout.map((i) => isGroup(i) ? groupPage(i.id) : i.tab)
/** The pager's pages; while the Larper election runs (server/nine.py) its page comes right before the profile's. */
export function pagesWith(layout: Layout, election: boolean): string[] {
  const pages = pagesOf(layout)
  if (!election) return pages
  const at = locate(layout, "profile")?.index ?? pages.length
  return [...pages.slice(0, at), "election", ...pages.slice(at)]
}

/** The tab a page shows: itself, or the chosen tab of its group (its first one at the start). */
export function leafOf(layout: Layout, page: string, chosen: Record<string, string>): string | null {
  const id = pageGroupId(page)
  if (id == null) return page
  const g = groupOf(layout, id)
  if (!g) return null
  return g.tabs.includes(chosen[id]) ? chosen[id] : g.tabs[0] ?? null
}
