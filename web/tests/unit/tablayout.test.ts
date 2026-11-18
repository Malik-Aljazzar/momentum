// The tab layout's rules (src/tablayout.ts); the same cases as android TabLayoutTest.kt.
import { describe, expect, it } from "vitest"
import type { InstalledExtension } from "../../src/model"
import {
  addGroup, availableTabs, DEFAULT_LAYOUT, effectiveLayout, hiddenTabs, hide, indent, leafOf, locate, move, outdent,
  pagesOf, pagesWith, place, removeGroup, updateGroup, type Layout,
} from "../../src/tablayout"

const journal: InstalledExtension = {
  id: "journal", version: "1.1.0", name: { de: "Tagebuch", en: "Journal" }, icon: "openbook", enabled: true, tab: null,
  tabs: [{ key: "today", name: { de: "Heute", en: "Today" }, icon: "tab_pen" }, { key: "entries", name: { de: "Einträge", en: "Entries" }, icon: "tab_book" }],
}
const off: InstalledExtension = { ...journal, id: "notes", enabled: false }
const AVAILABLE = ["rules", "chats", "feed", "profile", "ext:journal:today", "ext:journal:entries"]
const social = (tabs: string[]) => ({ id: "g1", name: "Social", icon: "tab_people", show: "both" as const, tabs })
const L: Layout = [{ tab: "rules" }, social(["chats", "feed"]), { tab: "ext:journal:today" }, { tab: "profile" }]

describe("tab layout (= TabLayout.kt)", () => {
  it("the tabs to choose from: the standard ones and the switched-on extensions'", () => {
    expect(availableTabs([journal, off])).toEqual(AVAILABLE)
    expect(availableTabs(undefined)).toEqual(["rules", "chats", "feed", "profile"])
  })
  it("the layout as shown: the default, gone tabs left out, the profile always there", () => {
    expect(effectiveLayout(null, AVAILABLE)).toEqual(DEFAULT_LAYOUT)
    const gone = effectiveLayout([{ tab: "rules" }, social(["chats", "ext:notes:x"]), { tab: "ext:notes:y" }], AVAILABLE)
    expect(gone).toEqual([{ tab: "rules" }, social(["chats"]), { tab: "profile" }])
    expect(effectiveLayout([social([]), { tab: "profile" }], AVAILABLE)).toEqual([social([]), { tab: "profile" }])  // empty groups stay
    expect(hiddenTabs(L, AVAILABLE)).toEqual(["ext:journal:entries"])
    expect(locate(L, "feed")).toEqual({ index: 1, group: "g1" })
    expect(locate(L, "profile")).toEqual({ index: 3, group: null })
    expect(locate(L, "ext:journal:entries")).toBeNull()
    expect(pagesOf(L)).toEqual(["rules", "group:g1", "ext:journal:today", "profile"])
    // the election's page while it runs: right before the profile's (its group's, when it's in one)
    expect(pagesWith(L, true)).toEqual(["rules", "group:g1", "ext:journal:today", "election", "profile"])
    expect(pagesWith([social(["chats", "profile"]), { tab: "rules" }], true)).toEqual(["election", "group:g1", "rules"])
    expect(pagesWith(L, false)).toEqual(pagesOf(L))
  })
  it("moves within its own list", () => {
    expect(move(L, "g1", -1)).toEqual([social(["chats", "feed"]), { tab: "rules" }, L[2], L[3]])
    expect(move(L, "feed", -1)).toEqual([L[0], social(["feed", "chats"]), L[2], L[3]])
    expect(move(L, "rules", -1)).toBeNull()
    expect(move(L, "profile", 1)).toBeNull()
    expect(move(L, "feed", 1)).toBeNull()                            // the group's last
  })
  it("into the group above, out of it again", () => {
    const into = indent(L, "ext:journal:today")!
    expect(into).toEqual([L[0], social(["chats", "feed", "ext:journal:today"]), L[3]])
    expect(indent(L, "rules")).toBeNull()                            // nothing above
    expect(indent(L, "profile")).toBeNull()                          // above it is a tab, not a group
    expect(outdent(into, "chats")).toEqual([L[0], social(["feed", "ext:journal:today"]), { tab: "chats" }, L[3]])
    const full: Layout = [...L, { tab: "ext:journal:entries" }]
    expect(outdent(full, "chats")).toBeNull()                        // the bar has 5 already
  })
  it("hides, dissolves, places", () => {
    expect(hide(L, "feed")).toEqual([L[0], social(["chats"]), L[2], L[3]])
    expect(hide(L, "rules")).toEqual([L[1], L[2], L[3]])
    expect(hide(L, "profile")).toBeNull()
    expect(removeGroup(L, "g1")).toEqual([L[0], L[2], L[3]])
    expect(removeGroup([social(["chats", "profile"]), { tab: "rules" }], "g1")).toEqual([{ tab: "profile" }, { tab: "rules" }])
    expect(place(L, "ext:journal:entries", null)).toEqual([...L, { tab: "ext:journal:entries" }])
    expect(place(L, "ext:journal:entries", "g1")).toEqual([L[0], social(["chats", "feed", "ext:journal:entries"]), L[2], L[3]])
    expect(place(L, "feed", null)).toBeNull()                        // already placed
    expect(place([...L, { tab: "chats" }], "ext:journal:entries", null)).toBeNull()      // full
  })
  it("new groups get free ids; a group's settings change", () => {
    const g = addGroup(L, "Gruppe 2", "tab_star")!
    expect(g[4]).toEqual({ id: "g2", name: "Gruppe 2", icon: "tab_star", show: "icons", tabs: [] })
    expect(addGroup(g, "x", "tab_star")).toBeNull()                  // full
    expect(updateGroup(L, "g1", { show: "names", icon: "tab_heart" })[1]).toEqual({ ...social(["chats", "feed"]), show: "names", icon: "tab_heart" })
  })
  it("a group's page shows the chosen tab, else its first", () => {
    expect(leafOf(L, "group:g1", {})).toBe("chats")
    expect(leafOf(L, "group:g1", { g1: "feed" })).toBe("feed")
    expect(leafOf(L, "group:g1", { g1: "rules" })).toBe("chats")      // not in this group (any more)
    expect(leafOf([social([])], "group:g1", {})).toBeNull()
    expect(leafOf(L, "rules", {})).toBe("rules")
  })
})
