// Settings -> Tabs (server/tablayout.py, src/tablayout.ts): the bar's places (up to 5) in order, groups indented with
// their tabs (name, icon from the tab library, what their menu shows), and the hidden tabs to place. Edits a working
// copy; the page's save button stores it. = android .../ui/TabsEditor.kt
import { useState } from "preact/hooks"
import type { AppState } from "../model"
import { meta, showToast } from "../store"
import { S } from "../strings"
import {
  addGroup, availableTabs, DEFAULT_LAYOUT, effectiveLayout, hiddenTabs, hide, indent, isGroup, MAX_TOP, move, nextGroupNumber,
  outdent, place, removeGroup, SHOWS, updateGroup, type Layout, type TabGroup, type TabShow,
} from "../tablayout"
import { Label, PixelIcon, PxButton, PxCard, PxTextField, Sheet } from "../ui/components"
import { PageBody, SaveBar, useSaver } from "./Settings"
import { TabIcon, tabName } from "./tabinfo"

/** A small square pixel button of the editor (dimmed when the move isn't possible). */
function EdBtn({ icon, label, on, onClick, flip }: { icon: string; label: string; on: boolean; onClick: () => void; flip?: boolean }) {
  return <button class="ed-btn" aria-label={label} title={label} disabled={!on} onClick={(e) => { e.stopPropagation(); onClick() }}>
    <PixelIcon name={icon} size={16} alpha={on ? 1 : .22} style={flip ? { transform: "scaleX(-1)" } : undefined} />
  </button>
}

export function TabsEditor({ st }: { st: AppState }) {
  const s = S.value
  const available = availableTabs(st.extensions)
  const start = effectiveLayout(st.settings.tabs, available)
  const [layout, setLayout] = useState<Layout>(start)
  const [editing, setEditing] = useState<string | null>(null)
  const [placing, setPlacing] = useState<string | null>(null)
  const dirty = JSON.stringify(layout) !== JSON.stringify(start)
  const [saving, run] = useSaver(dirty ? { tabs: layout } : {})
  const apply = (next: Layout | null) => { if (next) setLayout(next) }
  const hidden = hiddenTabs(layout, available)
  const groups = layout.filter(isGroup)
  const full = layout.length >= MAX_TOP
  const groupName = (g: TabGroup) => g.name || s.tabsGroupDefault(groups.indexOf(g) + 1)

  const tabRow = (k: string, inGroup: boolean, i: number) => {
    const { name, from } = tabName(k, st)
    return <div key={k} class="row tabs-row" data-tab={k} style={{ gap: 10, padding: inGroup ? "8px 10px 8px 0" : "8px 10px 8px 16px" }}>
      {inGroup && <i class="tabs-guide" />}
      <div class="center" style={{ width: 28, flex: "none" }}><TabIcon k={k} st={st} size={20} /></div>
      <div class="col grow" style={{ minWidth: 0 }}>
        <span class="t-body ellipsis">{name}</span>
        {from && <span class="t-small ellipsis" style={{ fontSize: 11 }}>{from}</span>}
      </div>
      <div class="row" style={{ gap: 4, flex: "none" }}>
        <EdBtn icon="ui_up" label={s.tabsMoveUp} on={!!move(layout, k, -1)} onClick={() => apply(move(layout, k, -1))} />
        <EdBtn icon="ui_down" label={s.tabsMoveDown} on={!!move(layout, k, 1)} onClick={() => apply(move(layout, k, 1))} />
        {inGroup
          ? <EdBtn icon="ui_back" label={s.tabsOutdent} on={!!outdent(layout, k)} onClick={() => apply(outdent(layout, k))} />
          : <EdBtn icon="ui_back" flip label={s.tabsIndent} on={i > 0 && !!indent(layout, k)} onClick={() => apply(indent(layout, k))} />}
        <EdBtn icon="ui_close" label={s.tabsHide} on={k !== "profile"} onClick={() => apply(hide(layout, k))} />
      </div>
    </div>
  }

  return <PageBody saveBar={<SaveBar dirty={dirty} saving={saving} onSave={() => void run()} />}>
    <span class="t-small">{s.tabsIntro}</span>
    <div class="row" style={{ padding: "4px 4px 0" }}>
      <Label text={s.tabsInBar} />
      <span class="grow" />
      <span class="t-small tabs-count">{s.tabsBarCount(layout.length, MAX_TOP)}</span>
    </div>
    <PxCard padding={0}><div class="col tabs-editor" style={{ padding: "6px 0" }}>
      {layout.map((item, i) => {
        if (!isGroup(item)) return tabRow(item.tab, false, i)
        const g = item
        return <div key={g.id} class="col tabs-group" data-group={g.id}>
          <div class="row" style={{ gap: 10, padding: "8px 10px 8px 16px" }}>
            <div class="center" style={{ width: 28, flex: "none" }}><PixelIcon name={g.icon} size={20} /></div>
            <span class="t-strong ellipsis grow">{groupName(g)}</span>
            <div class="row" style={{ gap: 4, flex: "none" }}>
              <EdBtn icon="ui_up" label={s.tabsMoveUp} on={!!move(layout, g.id, -1)} onClick={() => apply(move(layout, g.id, -1))} />
              <EdBtn icon="ui_down" label={s.tabsMoveDown} on={!!move(layout, g.id, 1)} onClick={() => apply(move(layout, g.id, 1))} />
              <EdBtn icon="ui_edit" label={s.tabsEdit} on onClick={() => setEditing(editing === g.id ? null : g.id)} />
              <EdBtn icon="ui_close" label={s.tabsRemoveGroup} on onClick={() => apply(removeGroup(layout, g.id))} />
            </div>
          </div>
          {editing === g.id && <GroupEditor g={g} onChange={(p) => setLayout(updateGroup(layout, g.id, p))} />}
          <div class="col" style={{ paddingLeft: 24 }}>{g.tabs.map((k) => tabRow(k, true, i))}</div>
        </div>
      })}
    </div></PxCard>
    <PxButton text={s.tabsNewGroup} kind="ghost" disabled={full} onClick={() => {
      const next = addGroup(layout, s.tabsGroupDefault(nextGroupNumber(layout)), "tab_star")
      if (next) { setLayout(next); setEditing(next[next.length - 1] && isGroup(next[next.length - 1]) ? (next[next.length - 1] as TabGroup).id : null) }
    }} />
    <div style={{ padding: "4px 4px 0" }}><Label text={s.tabsHidden} /></div>
    <PxCard padding={0}><div class="col tabs-hidden" style={{ padding: "6px 0" }}>
      {hidden.length === 0 ? <span class="t-small" style={{ padding: "10px 16px" }}>{s.tabsHiddenNone}</span>
        : hidden.map((k) => {
          const { name, from } = tabName(k, st)
          return <div key={k} class="row tabs-row" data-tab={k} style={{ gap: 10, padding: "8px 10px 8px 16px" }}>
            <div class="center" style={{ width: 28, flex: "none" }}><TabIcon k={k} st={st} size={20} alpha={.6} /></div>
            <div class="col grow" style={{ minWidth: 0 }}>
              <span class="t-body ellipsis">{name}</span>
              {from && <span class="t-small ellipsis" style={{ fontSize: 11 }}>{from}</span>}
            </div>
            <EdBtn icon="ui_plus" label={s.tabsAdd} on onClick={() => {
              if (groups.length) setPlacing(k)
              else if (full) showToast(s.tabsBarFull)
              else apply(place(layout, k, null))
            }} />
          </div>
        })}
    </div></PxCard>
    <span class="t-small">{s.tabsProfileStays}</span>
    <PxButton text={s.tabsReset} kind="ghost" onClick={() => { setLayout(effectiveLayout(DEFAULT_LAYOUT, available)); setEditing(null) }} />
    {placing && <Sheet onClose={() => setPlacing(null)} label={s.tabsWhere}>
      <div class="t-title" style={{ fontSize: 20 }}>{s.tabsWhere}</div>
      <div class="col tabs-where" style={{ gap: 10 }}>
        <PxButton text={s.tabsIntoBar} disabled={full} onClick={() => { apply(place(layout, placing, null)); setPlacing(null) }} />
        {groups.map((g) => <button key={g.id} class="row chip" data-group={g.id} style={{ gap: 10 }}
          onClick={() => { apply(place(layout, placing, g.id)); setPlacing(null) }}>
          <PixelIcon name={g.icon} size={20} /><span class="t-body">{groupName(g)}</span>
        </button>)}
      </div>
    </Sheet>}
  </PageBody>
}

/** A group's name, what its menu shows and its icon (the tab library). */
function GroupEditor({ g, onChange }: { g: TabGroup; onChange: (p: Partial<Pick<TabGroup, "name" | "icon" | "show">>) => void }) {
  const s = S.value
  const label: Record<TabShow, string> = { icons: s.tabsShowIcons, names: s.tabsShowNames, both: s.tabsShowBoth }
  const icons = meta.value?.tab_icons ?? []
  return <div class="col group-editor" style={{ gap: 12, padding: "4px 16px 14px 54px" }}>
    <span class="t-small">{s.tabsGroupName}</span>
    <PxTextField value={g.name} onInput={(v) => onChange({ name: v })} maxLength={24} user />
    <span class="t-small">{s.tabsGroupShow}</span>
    <div class="row" style={{ gap: 8, flexWrap: "wrap" }}>
      {SHOWS.map((v) => <button key={v} class={"chip t-button" + (g.show === v ? " on" : "")} data-show={v}
        onClick={() => onChange({ show: v })}>{label[v]}</button>)}
    </div>
    <span class="t-small">{s.tabsGroupIcon}</span>
    <div class="icon-grid">
      {icons.map((name) => <button key={name} class={"icon-cell" + (g.icon === name ? " on" : "")} aria-label={name}
        aria-pressed={g.icon === name} onClick={() => onChange({ icon: name })}>
        <PixelIcon name={name} size={20} />
      </button>)}
    </div>
  </div>
}
