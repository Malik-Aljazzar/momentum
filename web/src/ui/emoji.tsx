// The emoji picker (android/.../ui/Emoji.kt): a sheet with the recent row, the groups as tabs (their first
// emoji), a search by the English name, and the skin-tone variants on a long press. Emojis are plain text
// in the pixel emoji font. [EmojiButton] opens it next to a text input and hands the pick to the input.
import { useEffect, useState } from "preact/hooks"
import { type EmojiEntry, type EmojiGroup, GROUP_ICONS, loadEmoji, recentEmoji, rememberEmoji, searchEmoji } from "../emoji"
import { S } from "../strings"
import { IconBtn, PxTextField, Sheet, usePress } from "./components"

export function EmojiPicker({ onPick, onClose }: { onPick: (e: string) => void; onClose: () => void }) {
  const s = S.value
  const [groups, setGroups] = useState<EmojiGroup[]>([])
  const [tab, setTab] = useState(recentEmoji().length ? -1 : 0)
  const [query, setQuery] = useState("")
  const [variants, setVariants] = useState<EmojiEntry | null>(null)
  useEffect(() => { void loadEmoji().then(setGroups) }, [])
  const pick = (e: string) => { rememberEmoji(e); onPick(e); onClose() }
  const recent = recentEmoji()
  const list: EmojiEntry[] = query.trim() ? searchEmoji(groups, query)
    : tab < 0 ? recent.map((e) => ({ e, n: "" })) : groups[tab]?.emoji ?? []
  return <Sheet onClose={onClose} label="emoji">
    <div class="row" style={{ gap: 8 }}>
      <div class="grow"><PxTextField value={query} onInput={setQuery} placeholder={s.emojiSearch} /></div>
      <IconBtn icon="ui_close" alpha={.6} onClick={onClose} label="close" />
    </div>
    {!query.trim() && <div class="row emoji-tabs">
      {recent.length > 0 && <button class={tab < 0 ? "on" : ""} aria-label={s.emojiRecent} onClick={() => setTab(-1)}>🕐</button>}
      {groups.map((g, i) => <button key={g.name} class={i === tab ? "on" : ""} aria-label={s.emojiGroup(g.name)} onClick={() => setTab(i)}>{GROUP_ICONS[g.name]}</button>)}
    </div>}
    <span class="t-label">{query.trim() ? s.emojiTitle : tab < 0 ? s.emojiRecent : s.emojiGroup(groups[tab]?.name ?? "")}</span>
    {variants && <div class="row emoji-grid" style={{ background: "var(--faint)", borderRadius: 14, padding: 4 }}>
      {[variants.e, ...variants.v!].map((e) => <button key={e} class="emoji-cell" onClick={() => pick(e)}>{e}</button>)}
    </div>}
    <div class="emoji-grid" data-testid="emoji-grid">
      {list.length === 0 && groups.length > 0 && <span class="t-small">{s.emojiNone}</span>}
      {list.map((x) => <EmojiCell key={x.e} entry={x} onPick={pick} onVariants={() => setVariants(x)} />)}
    </div>
  </Sheet>
}

function EmojiCell({ entry, onPick, onVariants }: { entry: EmojiEntry; onPick: (e: string) => void; onVariants: () => void }) {
  const press = usePress(() => onPick(entry.e), entry.v ? onVariants : undefined)
  return <button class="emoji-cell" title={entry.n} aria-label={entry.n || entry.e} {...press}>{entry.e}</button>
}

/** The smiley next to a text input: opens the picker, [onPick] puts the emoji in. */
export function EmojiButton({ onPick, alpha = .8 }: { onPick: (e: string) => void; alpha?: number }) {
  const [open, setOpen] = useState(false)
  return <>
    <IconBtn icon="smile" alpha={alpha} onClick={() => setOpen(true)} label="emoji" />
    {open && <EmojiPicker onPick={onPick} onClose={() => setOpen(false)} />}
  </>
}
