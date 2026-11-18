// A group of tabs (settings -> tabs, src/tablayout.ts): one place in the bar, its page has a tab menu at the top like
// the profile's sections - the tabs' names, icons or both (the group's `show`), never cut off: the menu slides
// sideways when they don't fit (like the ranking's). = android .../ui/TabGroups.kt
import { useEffect, useRef, useState } from "preact/hooks"
import type { AppState } from "../model"
import { groupTabs, nav, stack } from "../nav"
import { S } from "../strings"
import { groupPage, leafOf, parseExtKey, type TabGroup } from "../tablayout"
import { EmptyHint } from "../ui/components"
import { OnTop } from "../ui/video"
import { ExtensionTab } from "./Extensions"
import { TabScreen } from "./registry"
import { RulesScreen } from "./Rules"
import { tabBadge, TabIcon, tabName } from "./tabinfo"

/** One tab's screen. */
export function LeafScreen({ k, st, visible }: { k: string; st: AppState; visible: boolean }) {
  if (k === "rules") return <RulesScreen st={st} />
  const ext = parseExtKey(k)
  if (ext) return <ExtensionTab id={ext[0]} tabKey={ext[1]} visible={visible} />
  return <TabScreen tab={k} />
}

/** A group's page: its menu at the top, the chosen tab below (the ones opened before stay as they were). */
export function TabGroupPage({ group, st, active }: { group: TabGroup; st: AppState; active: boolean }) {
  const s = S.value
  const chosen = leafOf([group], groupPage(group.id), groupTabs.value)
  const [opened, setOpened] = useState<string[]>([])
  useEffect(() => { if (chosen && !opened.includes(chosen)) setOpened([...opened, chosen]) }, [chosen])
  const onTop = active && stack.value.length === 0
  return <div class="fill col tab-group" data-group={group.id}>
    <GroupMenu group={group} chosen={chosen} st={st} />
    <div class="tab-group-body">
      {!chosen ? <EmptyHint title={group.name || s.tabsGroupDefault(1)} sub={s.tabsGroupEmpty} icon={group.icon} />
        : group.tabs.filter((k) => k === chosen || opened.includes(k)).map((k) =>
          <div key={k} class="fill" style={{ display: k === chosen ? undefined : "none" }}>
            <OnTop.Provider value={onTop && k === chosen}>
              <LeafScreen k={k} st={st} visible={onTop && k === chosen} />
            </OnTop.Provider>
          </div>)}
    </div>
  </div>
}

function GroupMenu({ group, chosen, st }: { group: TabGroup; chosen: string | null; st: AppState }) {
  const ref = useRef<HTMLDivElement>(null)
  // the chosen one in view (only this menu scrolls: scrollIntoView would move the pager too)
  useEffect(() => {
    const box = ref.current
    const item = box?.querySelector<HTMLElement>("[aria-selected=true]")
    if (!box || !item) return
    if (item.offsetLeft < box.scrollLeft) box.scrollTo({ left: item.offsetLeft - 16, behavior: "smooth" })
    else if (item.offsetLeft + item.offsetWidth > box.scrollLeft + box.clientWidth)
      box.scrollTo({ left: item.offsetLeft + item.offsetWidth - box.clientWidth + 16, behavior: "smooth" })
  }, [chosen, group.tabs.join()])
  return <div ref={ref} class="row group-menu" role="tablist" aria-label={group.name}>
    {group.tabs.map((k) => {
      const sel = k === chosen
      const badge = tabBadge(k, st) > 0
      return <button key={k} role="tab" aria-selected={sel} data-tab={k} class="group-menu-item" onClick={() => nav.selectInGroup(group.id, k)}>
        <span class="row" style={{ gap: 8, opacity: sel ? 1 : .4, position: "relative" }}>
          {group.show !== "names" && <TabIcon k={k} st={st} size={20} />}
          {group.show !== "icons" && <span class="t-button">{tabName(k, st).name}</span>}
          {badge && <i class="group-menu-dot" />}
        </span>
        <i class="group-menu-line" style={{ background: sel ? "var(--text)" : "transparent" }} />
      </button>
    })}
  </div>
}
