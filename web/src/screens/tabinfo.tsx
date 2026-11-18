// What a tab is called and how it looks (the bar, a group's menu, the editor in settings -> tabs). = TabInfo in
// android .../ui/TabGroups.kt
import { textOf } from "../extensions"
import type { AppState } from "../model"
import { lang, S } from "../strings"
import { extTab, parseExtKey } from "../tablayout"
import { Avatar, PixelIcon } from "../ui/components"

/** the standard tabs' glyphs (the profile shows the avatar) */
export const TAB_ICONS: Record<string, string> = { rules: "ui_updown", chats: "ui_chat", feed: "ui_feed", election: "ui_star" }

/** A tab's name and, for an extension's tab, its extension's name (to tell them apart in the editor). */
export function tabName(key: string, st: AppState): { name: string; from?: string } {
  const s = S.value
  switch (key) {
    case "rules": return { name: s.rulesTitle }
    case "chats": return { name: s.chats }
    case "feed": return { name: s.feed }
    case "profile": return { name: s.profile }
  }
  const t = extTab(st.extensions, key)
  const ext = st.extensions?.find((e) => e.id === parseExtKey(key)?.[0])
  return { name: t ? textOf(t.name, lang.value) : key, from: ext ? textOf(ext.name, lang.value) : undefined }
}

/** Unread things on a tab (the dot): chats -> messages, feed -> activity. */
export function tabBadge(key: string, st: AppState): number {
  return key === "chats" ? st.unread.messages : key === "feed" ? st.unread.notifications : 0
}

export function TabIcon({ k, st, size = 24, alpha = 1 }: { k: string; st: AppState; size?: number; alpha?: number }) {
  if (k === "profile") return <div style={{ opacity: alpha }}><Avatar name={st.user.display_name} url={st.user.avatar} size={size} /></div>
  return <PixelIcon name={TAB_ICONS[k] ?? extTab(st.extensions, k)?.icon ?? "ui_dots"} size={size} alpha={alpha} />
}
