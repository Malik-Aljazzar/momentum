// Tabs and the stack of screens opened on top of them (android/.../ui/Nav.kt, App.kt MainScreen).
import { signal } from "@preact/signals"
import { state } from "./store"
import { availableTabs, effectiveLayout, groupPage, leafOf, locate, pagesWith, type Layout } from "./tablayout"

/** A page of the main pager (one per place in the bar): a tab ("rules", "chats", "feed", "profile", an extension's
 *  "ext:<extension>:<tab>"), a group of tabs ("group:<id>", src/tablayout.ts) or "election" while the vote runs.
 *  The own profile is the main page (the app starts there, wherever it's placed). */
export type Tab = string
/** My tabs as shown (settings -> tabs). */
export const layoutNow = (): Layout => {
  const st = state.value
  return effectiveLayout(st?.settings.tabs, availableTabs(st?.extensions))
}
/** The pages right now: one per place in the bar; while the Larper election runs (server/nine.py) it gets a page of
 *  its own before the profile's. */
export const tabsNow = (): Tab[] => pagesWith(layoutNow(), !!state.value?.user.election)
export const isExtensionTab = (t: Tab) => t.startsWith("ext:")

/** The settings' pages (one level below the menu). */
export type SettingsPageKey = "profile" | "game" | "app" | "tabs" | "security" | "extensions" | "legal" | "account"

export type Route =
  | { r: "user"; username: string }
  | { r: "post"; id: number }
  | { r: "follows"; username: string; followers: boolean }
  | { r: "search" }
  | { r: "blocked" }
  | { r: "notifications" }
  | { r: "chats" }
  | { r: "chat"; id: number }
  | { r: "newPost" }
  | { r: "newStory" }
  | { r: "newChat" }
  | { r: "newGroup" }
  | { r: "settings"; page?: SettingsPageKey }
  | { r: "group"; id: number }
  | { r: "challenge"; id: number }
  | { r: "newChallenge"; conversationId: number }
  | { r: "requests" }
  | { r: "report" }
  | { r: "leaderboard"; scope?: "global" | "friends" | "groups" | "nine" }
  | { r: "chatInfo"; id: number }
  | { r: "e2eRecovery" }
  | { r: "e2eUnlock" }
  | { r: "confirmEmail" }

export const tab = signal<Tab>("profile")
/** the tab each group shows (its first one until another is picked) */
export const groupTabs = signal<Record<string, string>>({})
/** The tab on screen: the current page, or the chosen tab of its group (null: an empty group). */
export const currentLeaf = (): string | null => leafOf(layoutNow(), tab.value, groupTabs.value)
export const stack = signal<Route[]>([])
/** bumped when the current tab is tapped again: the tab scrolls to the top */
export const scrollTop = signal<{ tab: Tab; n: number }>({ tab: "profile", n: 0 })

/** My own profile opened from anywhere: the profile tab, like tapping it. */
function ownProfile(r: Route): boolean {
  if (r.r === "user" && r.username === state.value?.user.username) {
    stack.value = []
    nav.showTab("profile")
    return true
  }
  return false
}

export const nav = {
  push(r: Route) {
    if (ownProfile(r)) return
    stack.value = [...stack.value, r]
  },
  pop() {
    if (stack.value.length) stack.value = stack.value.slice(0, -1)
  },
  replace(r: Route) {
    const rest = stack.value.slice(0, -1)
    if (ownProfile(r)) return
    stack.value = [...rest, r]
  },
  /** A page picked in the bar (again: its tab scrolls to the top). */
  selectTab(t: Tab) {
    stack.value = []
    if (t === tab.value) scrollTop.value = { tab: currentLeaf() ?? t, n: scrollTop.value.n + 1 }
    else tab.value = t
  },
  /** A tab picked in its group's menu (again: to the top). */
  selectInGroup(group: string, key: string) {
    if (leafOf(layoutNow(), groupPage(group), groupTabs.value) === key) scrollTop.value = { tab: key, n: scrollTop.value.n + 1 }
    else groupTabs.value = { ...groupTabs.value, [group]: key }
  },
  /** Goes to wherever a tab is placed (its page, or its group with it chosen); false when it's hidden - the caller
   *  then just opens what it wanted on top. */
  showTab(key: string): boolean {
    if (key === "election") { tab.value = key; return true }
    const at = locate(layoutNow(), key)
    if (!at) return false
    if (at.group) {
      groupTabs.value = { ...groupTabs.value, [at.group]: key }
      tab.value = groupPage(at.group)
    } else tab.value = key
    return true
  },
}

/** Screens that keep the tab bar visible (like Instagram). */
export const keepsTabs = (r: Route) => r.r === "chats" || r.r === "user" || r.r === "leaderboard"
