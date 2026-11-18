// Tabs and the stack of screens opened on top of them (android/.../ui/Nav.kt, App.kt MainScreen).
import { signal } from "@preact/signals"
import { extensionTabs, tabKey } from "./extensions"
import { state } from "./store"

/** The own profile is the main page (the first tab shown); an extension's tab is "ext:<id>". */
export type Tab = "rules" | "chats" | "feed" | "election" | "profile" | `ext:${string}`
export const TABS: Tab[] = ["rules", "chats", "feed", "profile"]
/** The tabs right now: extensions that are switched on add theirs (server/extensions.py), while the Larper
 *  election runs it gets its own tab (server/nine.py) - both before the profile. */
export const tabsNow = (): Tab[] => {
  const st = state.value
  return ["rules", "chats", "feed", ...extensionTabs(st?.extensions).map((e) => tabKey(e.id)),
    ...(st?.user.election ? ["election" as const] : []), "profile"]
}
export const isExtensionTab = (t: Tab): t is `ext:${string}` => t.startsWith("ext:")

/** The settings' pages (one level below the menu). */
export type SettingsPageKey = "profile" | "game" | "app" | "security" | "extensions" | "legal" | "account"

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
export const stack = signal<Route[]>([])
/** bumped when the current tab is tapped again: the tab scrolls to the top */
export const scrollTop = signal<{ tab: Tab; n: number }>({ tab: "profile", n: 0 })

/** My own profile opened from anywhere: the profile tab, like tapping it. */
function ownProfile(r: Route): boolean {
  if (r.r === "user" && r.username === state.value?.user.username) {
    stack.value = []
    tab.value = "profile"
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
  selectTab(t: Tab) {
    stack.value = []
    if (t === tab.value) scrollTop.value = { tab: t, n: scrollTop.value.n + 1 }
    else tab.value = t
  },
}

/** Screens that keep the tab bar visible (like Instagram). */
export const keepsTabs = (r: Route) => r.r === "chats" || r.r === "user" || r.r === "leaderboard"
