// The emoji picker (android/.../ui/Emoji.kt): a sheet with the recent row, the groups as tabs (their first
// emoji), a search by the English name, and the skin-tone variants on a long press. Emojis are plain text
// in the pixel emoji font. [EmojiButton] opens it next to a text input and hands the pick to the input.
import { useEffect, useState } from "preact/hooks"
import { type EmojiEntry, type EmojiGroup, GROUP_ICONS, loadEmoji, recentEmoji, rememberEmoji, searchEmoji, tail } from "../emoji"
import { systemFont } from "../store"
import { S } from "../strings"
import { IconBtn, PxTextField, Sheet, usePress } from "./components"

/** With [text] (typing in a field) the picker stays open after a pick and shows what's written, with a delete key
 *  (report #64); without (a rule icon: one emoji) a pick closes it. */
export function EmojiPicker({ onPick, onClose, text, onDelete }: {
  onPick: (e: string) => void; onClose: () => void; text?: string; onDelete?: () => void
}) {
  const s = S.value
  const [groups, setGroups] = useState<EmojiGroup[]>([])
  const [tab, setTab] = useState(recentEmoji().length ? -1 : 0)
  const [query, setQuery] = useState("")
  const [variants, setVariants] = useState<EmojiEntry | null>(null)
  useEffect(() => { void loadEmoji().then(setGroups) }, [])
  const pick = (e: string) => { rememberEmoji(e); onPick(e); if (text == null) onClose() }
  const recent = recentEmoji()
  const list: EmojiEntry[] = query.trim() ? searchEmoji(groups, query)
    : tab < 0 ? recent.map((e) => ({ e, n: "" })) : groups[tab]?.emoji ?? []
  // the "system font" setting: the phone's own emojis here too (report #61)
  return <Sheet onClose={onClose} label="emoji">
    <div class={systemFont.value ? "col emoji-picker system" : "col emoji-picker"} style={{ gap: 10 }}>
      {text != null && <div class="row emoji-preview">
        <span class={systemFont.value ? "grow t-body user-text system" : "grow t-body user-text"} data-testid="emoji-preview">{tail(text) || " "}</span>
        <IconBtn icon="ui_backspace" alpha={text ? .8 : .3} onClick={() => onDelete?.()} label="delete" />
      </div>}
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
    </div>
  </Sheet>
}

function EmojiCell({ entry, onPick, onVariants }: { entry: EmojiEntry; onPick: (e: string) => void; onVariants: () => void }) {
  const press = usePress(() => onPick(entry.e), entry.v ? onVariants : undefined)
  return <button class="emoji-cell" title={entry.n} aria-label={entry.n || entry.e} {...press}>{entry.e}</button>
}

/** The smiley next to a text input: opens the picker, which stays open while picking; [text] is the field's text,
 *  [onPick] puts an emoji in, [onDelete] takes the last character back, [onOpen] / [onClose] around it. */
export function EmojiButton({ text, onPick, onDelete, onOpen, onClose, alpha = .8 }: {
  text: string; onPick: (e: string) => void; onDelete: () => void; onOpen?: () => void; onClose?: () => void; alpha?: number
}) {
  const [open, setOpen] = useState(false)
  return <>
    <IconBtn icon="smile" alpha={alpha} onClick={() => { onOpen?.(); setOpen(true) }} label="emoji" />
    {open && <EmojiPicker text={text} onPick={onPick} onDelete={onDelete} onClose={() => { setOpen(false); onClose?.() }} />}
  </>
}
