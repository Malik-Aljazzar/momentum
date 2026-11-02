// "Lückentext" editors (android/.../ui/Editors.kt): a sentence explains the rule, the gaps are its
// settings. Picking another option rewrites the sentence.
import type { ComponentChildren } from "preact"
import { createContext } from "preact"
import { useContext, useState } from "preact/hooks"
import { applied, catalyst, fmtVal, nextValue, parseNum } from "../format"
import type { Decay, Rule } from "../model"
import { meta, save, showToast, state } from "../store"
import { S } from "../strings"
import type { ClozeBuilder, DecayGaps, RuleGaps } from "../strings/types"
import { Label, PixelIcon, PxButton, PxTextField, Switch } from "../ui/components"
import { EmojiPicker } from "../ui/emoji"
import { isEmojiIcon } from "../emoji"

const GapColor = createContext("var(--yellow)")
const CLOZE = { fontFamily: 'Pixelify, "Pixel Emoji"', fontSize: 20, lineHeight: "26px" } as const

interface Item { id: string; node: () => ComponentChildren; punct?: string }

class ClozeScope implements ClozeBuilder {
  items: Item[] = []
  private n = 0
  text(s: string) {
    for (const w of s.split(" ").filter(Boolean)) {
      const prev = this.items[this.items.length - 1]
      const punct = w.match(/^[.,:;!?%]+/)?.[0]
      if (punct && prev?.id.startsWith("gap:")) {
        // punctuation sticks to the gap before it, so a line never starts with ","
        prev.punct = (prev.punct ?? "") + punct
        const rest = w.slice(punct.length)
        if (rest) this.items.push({ id: `w${this.n++}`, node: () => rest })
        continue
      }
      this.items.push({ id: `w${this.n++}`, node: () => w })
    }
  }
  gap(id: string, content: () => ComponentChildren) { this.items.push({ id: `gap:${id}`, node: content }) }
}

function Cloze({ build }: { build: (c: ClozeBuilder) => void }) {
  const scope = new ClozeScope()
  build(scope)
  return <div style={{ display: "flex", flexWrap: "wrap", alignItems: "center", rowGap: 10, ...CLOZE }}>
    {scope.items.map((it) => <span key={it.id} class="row" style={{ marginRight: 6, color: "var(--text)" }}>
      {it.node()}{it.punct && <span>{it.punct}</span>}
    </span>)}
  </div>
}

function GapBox({ children, onClick }: { children: ComponentChildren; onClick?: () => void }) {
  const color = useContext(GapColor)
  return <span onClick={onClick} class="row" style={{
    borderRadius: "8px 8px 0 0", background: "rgba(255,255,255,0.08)", padding: "2px 6px", cursor: onClick ? "pointer" : undefined,
    backgroundImage: `linear-gradient(90deg, ${color} 60%, transparent 0)`, backgroundSize: "10px 2px", backgroundRepeat: "repeat-x",
    backgroundPosition: "0 100%", position: "relative",
  }}>{children}</span>
}

const gapText = (color: string) => ({ ...CLOZE, color, fontWeight: 600 })
const numText = (v: number) => (v % 1 === 0 ? String(Math.trunc(v)) : String(v).replace(".", ","))

/** An input exactly as wide as its content (a hidden twin sets the width). */
function AutoInput({ value, placeholder, onInput, numeric, center }: {
  value: string; placeholder?: string; onInput: (v: string) => void; numeric?: boolean; center?: boolean
}) {
  const color = useContext(GapColor)
  return <span style={{ position: "relative", display: "inline-block" }}>
    <span aria-hidden style={{ ...gapText(color), visibility: "hidden", whiteSpace: "pre", padding: "0 2px" }}>{value || placeholder || " "}</span>
    <input value={value} placeholder={placeholder} inputMode={numeric ? "decimal" : "text"} maxLength={40}
      onInput={(e) => onInput((e.target as HTMLInputElement).value)}
      style={{ ...gapText(color), position: "absolute", inset: 0, width: "100%", border: 0, outline: 0, background: "none", padding: "0 2px",
        textAlign: center ? "center" : "left", caretColor: color, minWidth: numeric ? 24 : 40 }} />
  </span>
}

function TextGap({ value, placeholder, onChange }: { value: string; placeholder: string; onChange: (v: string) => void }) {
  return <GapBox><AutoInput value={value} placeholder={placeholder} onInput={onChange} /></GapBox>
}

function NumGap({ value, onChange }: { value: number; onChange: (v: number) => void }) {
  const [text, setText] = useState(numText(value))
  return <GapBox><AutoInput value={text} numeric center onInput={(t) => { setText(t); const v = parseNum(t); if (v != null) onChange(v) }} /></GapBox>
}

/** A gap with options: the phone's own picker (iOS wheel), styled as the gap. */
function SelectGap<T extends string | number>({ selected, options, onSelect }: { selected: T; options: [T, string][]; onSelect: (v: T) => void }) {
  const color = useContext(GapColor)
  const label = options.find(([v]) => v === selected)?.[1] ?? String(selected)
  return <GapBox>
    <span style={gapText(color)}>{label}</span>
    <span style={{ ...gapText(color), opacity: .7, fontSize: 13 }}>&nbsp;▾</span>
    <select value={String(selected)} onChange={(e) => {
      const raw = (e.target as HTMLSelectElement).value
      const hit = options.find(([v]) => String(v) === raw)
      if (hit) onSelect(hit[0])
    }} style={{ position: "absolute", inset: 0, opacity: 0, width: "100%", fontSize: 16 }}>
      {options.map(([v, l]) => <option key={String(v)} value={String(v)}>{l}</option>)}
    </select>
  </GapBox>
}

/** A duration: the usual days as presets, plus "custom": any number 0..365 in hours or days (report #18). */
function TimeGap({ n, unit, presets, label, onChange }: {
  n: number; unit: string; presets: number[]; label: (n: number, unit: string) => string; onChange: (n: number, unit: string) => void
}) {
  const s = S.value
  const color = useContext(GapColor)
  const [custom, setCustom] = useState(false)
  const [text, setText] = useState(n > 0 ? String(n) : "")
  const [u, setU] = useState(unit)
  const current = unit === "h" && n > 0 ? "custom" : String(n)
  return <GapBox>
    <span style={gapText(color)}>{label(n, unit)}</span>
    <span style={{ ...gapText(color), opacity: .7, fontSize: 13 }}>&nbsp;▾</span>
    <select value={presets.includes(n) || current === "custom" ? current : "custom"} onChange={(e) => {
      const raw = (e.target as HTMLSelectElement).value
      if (raw === "custom") { setText(n > 0 ? String(n) : ""); setU(unit); setCustom(true) } else onChange(Number(raw), "d")
    }} style={{ position: "absolute", inset: 0, opacity: 0, width: "100%", fontSize: 16 }}>
      {presets.map((v) => <option key={v} value={String(v)}>{label(v, "d")}</option>)}
      <option value="custom">{s.customTime}</option>
    </select>
    {custom && <div class="fill center" role="dialog" aria-label={s.customTimeTitle} style={{ position: "fixed", zIndex: 70, background: "rgba(0,0,0,.6)" }}
      onClick={() => setCustom(false)}>
      <div class="col" style={{ background: "var(--card2)", borderRadius: 20, padding: 20, gap: 14, width: "min(340px, 86vw)" }} onClick={(e) => e.stopPropagation()}>
        <div class="t-title" style={{ fontSize: 18 }}>{s.customTimeTitle}</div>
        <div class="row" style={{ gap: 8 }}>
          <div class="grow"><PxTextField value={text} onInput={(t) => setText(t.replace(/\D/g, "").slice(0, 3))} placeholder="0–365" inputMode="numeric" /></div>
          {([["h", s.unitHours], ["d", s.unitDays]] as const).map(([k, l]) => <button key={k} class="t-button" onClick={() => setU(k)} style={{
            borderRadius: 10, padding: "10px 12px", background: u === k ? "var(--text)" : "var(--faint)", color: u === k ? "#000" : "var(--text)",
          }}>{l}</button>)}
        </div>
        <PxButton text={s.done} onClick={() => { setCustom(false); onChange(Math.max(0, Math.min(365, Number(text) || 0)), u) }} />
      </div>
    </div>}
  </GapBox>
}

const IconGap = ({ icon, onClick }: { icon: string; onClick?: () => void }) => <GapBox onClick={onClick}><PixelIcon name={icon} size={24} /></GapBox>

/** The explanation sentence with fixed values: subscribed, group and challenge rules. */
export function RuleSentence({ rule }: { rule: Pick<Rule, "kind" | "name" | "icon" | "mode" | "base" | "param" | "min_value" | "reset_days" | "reset_unit"> }) {
  const s = S.value
  const acc = rule.kind === "acc"
  const color = acc ? "var(--lime)" : "var(--orange)"
  const fixed = (t: string) => <GapBox><span style={gapText(color)}>{t}</span></GapBox>
  const pct = rule.mode === "exponential" ? Math.round(rule.param * 100) : 70
  const gaps: RuleGaps = {
    icon: () => <IconGap icon={rule.icon} />, name: () => fixed(rule.name), base: () => fixed(numText(rule.base)),
    mode: () => fixed(s.modes.find(([m]) => m === rule.mode)?.[1] ?? rule.mode), param: () => fixed(numText(rule.param)),
    pct: () => fixed(numText(pct)), min: () => fixed(numText(rule.min_value)), reset: () => fixed(s.resetSpan(rule.reset_days, rule.reset_unit ?? "d")),
  }
  return <GapColor.Provider value={color}><Cloze build={(c) => s.ruleSentence(c, acc, rule.mode, rule.reset_days, gaps)} /></GapColor.Provider>
}

function Preview({ caption, values }: { caption: string; values: [string | null, string][] }) {
  return <div class="col" style={{ borderRadius: 16, background: "rgba(0,0,0,0.3)", padding: "12px 14px" }}>
    <span class="t-small">{caption}</span>
    <div class="row" style={{ flexWrap: "wrap", gap: "4px 6px", marginTop: 6, alignItems: "flex-end" }}>
      {values.map(([top, v], i) => <span key={i} class="row" style={{ gap: 6, alignItems: "flex-end" }}>
        {i > 0 && <span class="t-block" style={{ color: "rgba(255,255,255,0.25)", fontSize: 14 }}>›</span>}
        <span class="col" style={{ alignItems: "center" }}>
          {top != null && <span class="t-small" style={{ fontSize: 10, lineHeight: "12px" }}>{top}</span>}
          <span class="t-block" style={{ fontSize: 14, fontWeight: 400 }}>{v}</span>
        </span>
      </span>)}
    </div>
  </div>
}

function SheetBody({ gapColor, top, children }: { gapColor: string; top: string; children: ComponentChildren }) {
  return <GapColor.Provider value={gapColor}>
    <div class="col" style={{ gap: 16, margin: "-14px -20px -24px", padding: "14px 20px 24px", background: `linear-gradient(180deg, ${top}, var(--sheet) 40%)` }}>
      {children}
    </div>
  </GapColor.Provider>
}

// ---------------------------------------------------------------- rules

const RESETS = [0, 1, 2, 3, 5, 7, 10, 14, 30]
const IDLE_DAYS = [0, 1, 2, 3, 5, 7, 14, 30]

interface Draft {
  kind: string; name: string; icon: string; mode: string; base: number; param: number; min_value: number
  reset_days: number; pct: number; public: boolean; idle_days: number; idle_rule_id: number | null; tappable: boolean
  reset_unit: string; idle_unit: string          // "d" days or "h" hours
}

export function RuleEditor({ rule, kind, onClose, submit, onDelete, title, publicToggle }: {
  rule: (Pick<Rule, "kind" | "name" | "icon" | "mode" | "base" | "param" | "min_value" | "reset_days" | "reset_unit" | "idle_unit"> & { id: number; public?: boolean; idle_days?: number; idle_rule_id?: number | null; tappable?: boolean }) | null
  kind: string; onClose: () => void
  /** custom target (group/challenge rule) instead of the personal rules */
  submit?: (body: Record<string, unknown>) => Promise<boolean>
  onDelete?: () => Promise<boolean>
  title?: string
  /** group rules: may be public (others subscribe to it) */
  publicToggle?: [string, string]
}) {
  const personal = !submit
  const s = S.value
  const acc = kind === "acc"
  const [d, setD] = useState<Draft>(() => rule ? {
    kind: rule.kind, name: rule.name, icon: rule.icon, mode: rule.mode, base: rule.base, param: rule.param, min_value: rule.min_value,
    reset_days: rule.reset_days, pct: rule.mode === "exponential" ? Math.round(rule.param * 100) : 70, public: !!rule.public,
    idle_days: rule.idle_days ?? 0, idle_rule_id: rule.idle_rule_id ?? null, tappable: rule.tappable !== false,
    reset_unit: rule.reset_unit ?? "d", idle_unit: rule.idle_unit ?? "d",
  } : { kind, name: "", icon: acc ? "star" : "storm", mode: "constant", base: 50, param: 0, min_value: 0, reset_days: 0, pct: 70, public: false,
    idle_days: 0, idle_rule_id: null, tappable: true, reset_unit: "d", idle_unit: "d" })
  const [showIcons, setShowIcons] = useState(false)
  const [pickEmoji, setPickEmoji] = useState(false)
  const [confirmDelete, setConfirmDelete] = useState(false)
  const set = (p: Partial<Draft>) => setD((x) => ({ ...x, ...p }))
  const icons = meta.value?.icons ?? []

  const setMode = (mode: string) => setD((x) => {
    let n = { ...x, mode }
    if (mode === "linear" && n.param === 0) n = { ...n, param: Math.max(1, Math.round(n.base / 4)) }
    if (mode !== "constant" && n.min_value === 0) n = { ...n, min_value: Math.max(1, Math.round(n.base / 5)) }
    if (mode !== "constant" && n.reset_days === 0 && !rule) n = { ...n, reset_days: 3 }
    return n
  })

  const gaps: RuleGaps = {
    icon: () => <IconGap icon={d.icon} onClick={() => setShowIcons(!showIcons)} />,
    name: () => <TextGap value={d.name} placeholder={s.namePlaceholder(acc)} onChange={(name) => set({ name })} />,
    base: () => <NumGap value={d.base} onChange={(base) => set({ base })} />,
    mode: () => <SelectGap selected={d.mode} options={s.modes as [string, string][]} onSelect={setMode} />,
    param: () => <NumGap value={d.param} onChange={(param) => set({ param })} />,
    pct: () => <NumGap value={d.pct} onChange={(pct) => set({ pct })} />,
    min: () => <NumGap value={d.min_value} onChange={(min_value) => set({ min_value })} />,
    reset: () => <TimeGap n={d.reset_days} unit={d.reset_unit} presets={RESETS} label={s.resetSpan}
      onChange={(reset_days, reset_unit) => set({ reset_days, reset_unit })} />,
  }
  const counterparts = (state.value?.rules ?? []).filter((r) => r.kind !== kind && r.challenge_id == null && r.id > 0)
  const target = counterparts.find((r) => r.id === d.idle_rule_id) ?? counterparts[0]
  const savedRule = rule ? state.value?.rules.find((r) => r.id === rule.id) : undefined
  const sign = acc ? "+" : "−"
  const steps: [null, string][] = []
  for (let v = d.base, i = 0; i < 6; i++, v = nextValue(d.mode, v, d.mode === "exponential" ? d.pct / 100 : d.param, d.min_value)) {
    steps.push([null, sign + fmtVal(applied(kind, v))])
  }
  const publicLabels = personal ? [s.showOnProfile, s.showOnProfileHint] as [string, string] : publicToggle

  const doSave = async () => {
    if (!d.name.trim()) { showToast(s.nameMissing, true); return }
    const body: Record<string, unknown> = {
      kind: d.kind, name: d.name.trim(), icon: d.icon, mode: d.mode, base: d.base,
      param: d.mode === "exponential" ? d.pct / 100 : d.param, min_value: d.min_value, reset_days: d.reset_days, reset_unit: d.reset_unit, public: d.public,
      tappable: d.tappable,
    }
    if (personal) {
      body.idle_days = target ? d.idle_days : 0
      body.idle_unit = d.idle_unit
      body.idle_rule_id = d.idle_days > 0 ? (target?.id ?? null) : null
    }
    const ok = submit ? await submit(body) : await save(rule ? "PUT" : "POST", rule ? `/rules/${rule.id}` : "/rules", body)
    if (ok) onClose()
  }

  return <SheetBody gapColor={acc ? "var(--lime)" : "var(--orange)"} top={acc ? "#17302C" : "#33172A"}>
    <div class="t-title" style={{ fontSize: 20 }}>{title ?? (rule ? s.edit : acc ? s.newAcc : s.newDec)}</div>
    <Cloze build={(c) => s.ruleSentence(c, acc, d.mode, d.reset_days, gaps)} />

    {personal && <>
      {/* optional: left untapped for a while -> a rule of the other kind is tapped automatically */}
      <Label text={s.idleTitle(acc)} />
      {counterparts.length === 0 ? <span class="t-small">{s.idleNoCounterpart(acc)}</span> : <>
        <Cloze build={(c) => s.idleSentence(c, d.idle_days > 0,
          () => <TimeGap n={d.idle_days} unit={d.idle_unit} presets={IDLE_DAYS} label={s.idleSpan}
            onChange={(n, u) => set({ idle_days: n, idle_unit: u, idle_rule_id: d.idle_rule_id ?? target!.id })} />,
          () => <GapColor.Provider value={acc ? "var(--orange)" : "var(--lime)"}>
            <SelectGap selected={target!.id} options={counterparts.map((r) => [r.id, r.name] as [number, string])} onSelect={(id) => set({ idle_rule_id: id })} />
          </GapColor.Provider>)} />
        {/* pausing doesn't lose the setting (also possible for subscribed rules, in their details) */}
        {savedRule && (savedRule.idle_days ?? 0) > 0 && <div class="row" style={{ gap: 10 }}>
          <div class="col grow"><span class="t-body">{s.idlePause}</span><span class="t-small" style={{ fontSize: 12 }}>{s.idlePauseHint}</span></div>
          <Switch on={!!savedRule.idle_paused} onChange={(on) => save("PUT", `/rules/${savedRule.id}/idle-pause`, { paused: on }, true)} />
        </div>}
      </>}
    </>}

    {showIcons && <div class="row" style={{ flexWrap: "wrap", gap: 8 }}>
      {/* any emoji as the icon (pixel art like the sprites) */}
      <button class="center" aria-label="emoji" onClick={() => setPickEmoji(true)} style={{ width: 52, height: 52, borderRadius: 14,
        background: isEmojiIcon(d.icon) ? (acc ? "rgba(167,240,112,0.25)" : "rgba(239,125,87,0.25)") : "var(--faint)" }}>
        <PixelIcon name={isEmojiIcon(d.icon) ? d.icon : "😀"} size={32} /></button>
      {pickEmoji && <EmojiPicker onPick={(e) => { set({ icon: e }); setShowIcons(false) }} onClose={() => setPickEmoji(false)} />}
      {icons.map((name) => <button key={name} class="center" onClick={() => { set({ icon: name }); setShowIcons(false) }} style={{
        width: 52, height: 52, borderRadius: 14, background: name === d.icon ? (acc ? "rgba(167,240,112,0.25)" : "rgba(239,125,87,0.25)") : "var(--faint)",
      }}><PixelIcon name={name} size={32} /></button>)}
    </div>}

    <Preview caption={s.previewRule} values={steps} />

    {/* a rule that only counts through the automatic tap (subscribers keep that too) */}
    <div class="row" style={{ gap: 10 }}>
      <div class="col grow"><span class="t-body">{s.tappableSetting}</span><span class="t-small">{s.tappableHint}</span></div>
      <Switch on={d.tappable} onChange={(on) => set({ tappable: on })} label={s.tappableSetting} />
    </div>

    {publicLabels && <div class="row" style={{ gap: 10 }}>
      <div class="col grow"><span class="t-body">{publicLabels[0]}</span><span class="t-small">{publicLabels[1]}</span></div>
      <Switch on={d.public} onChange={(on) => set({ public: on })} />
    </div>}

    <div class="row" style={{ gap: 10 }}>
      {rule && (personal || onDelete) && <PxButton text={confirmDelete ? s.reallyDelete : s.delete} kind="danger" onClick={async () => {
        if (!confirmDelete) { setConfirmDelete(true); return }
        if (onDelete ? await onDelete() : await save("DELETE", `/rules/${rule.id}`)) onClose()
      }} />}
      <PxButton text={s.save} onClick={doSave} class="grow" />
    </div>
  </SheetBody>
}

// ---------------------------------------------------------------- daily decay

export function DecayEditor({ decay, onClose, submit, title, name, onDelete }: {
  decay: Decay; onClose: () => void
  /** a group decay instead of my own (then with a name) */
  submit?: (body: Record<string, unknown>) => Promise<boolean>
  title?: string; name?: string; onDelete?: () => Promise<boolean>
}) {
  const s = S.value
  const [named, setNamed] = useState(name ?? "")
  const [pub, setPub] = useState(!!decay.public)
  const [confirmDelete, setConfirmDelete] = useState(false)
  const [base, setBase] = useState(decay.base)
  const [threshold, setThreshold] = useState(decay.threshold)
  const [mode, setMode] = useState(decay.catalyst_mode)
  const [a, setA] = useState(decay.catalyst_a)
  const [b, setB] = useState(decay.catalyst_mode === "exponential" ? decay.catalyst_b : 1.5)
  const [pct, setPct] = useState(decay.catalyst_mode === "inverse" ? Math.round(decay.catalyst_b * 100) : 70)
  const gaps: DecayGaps = {
    icon: () => <PixelIcon name="calendar" size={24} />,
    base: () => <NumGap value={base} onChange={setBase} />,
    threshold: () => <NumGap value={threshold} onChange={setThreshold} />,
    mode: () => <SelectGap selected={mode} options={s.catalysts as [string, string][]} onSelect={(m) => { if (m !== "none" && a === 0) setA(25); setMode(m) }} />,
    a: () => <NumGap value={a} onChange={setA} />,
    b: () => <NumGap value={b} onChange={setB} />,
    pct: () => <NumGap value={pct} onChange={setPct} />,
  }
  const bb = mode === "inverse" ? pct / 100 : b
  // first column: a good day (only the base), then 1..4 missed days in a row
  const preview: [string, string][] = [["✓", "−" + fmtVal(applied("dec", base))],
    ...[1, 2, 3, 4].map((n) => [`${n}×`, "−" + fmtVal(applied("dec", base + (mode === "none" ? 0 : catalyst(mode, a, bb, n))))] as [string, string])]
  return <SheetBody gapColor="var(--orange)" top="#33172A">
    <div class="t-title" style={{ fontSize: 20 }}>{title ?? s.dailyDecay}</div>
    {name != null && <PxTextField value={named} onInput={(v) => setNamed(v.slice(0, 40))} placeholder={s.decayName} />}
    <Cloze build={(c) => s.decaySentence(c, mode, gaps)} />
    <Preview caption={s.previewDecay} values={preview} />
    {/* my own decay: public = in my profile, others may follow; a group's: others outside may */}
    <div class="row" style={{ gap: 10 }}>
      <div class="col grow">
        <span class="t-body">{submit ? s.groupPublic : s.decayPublic}</span>
        <span class="t-small">{submit ? s.groupRulePublicHint : s.decayPublicHint}</span>
      </div>
      <Switch on={pub} onChange={setPub} label={s.decayPublic} />
    </div>
    <div class="row" style={{ gap: 10 }}>
      {onDelete && <PxButton text={confirmDelete ? s.reallyDelete : s.delete} kind="danger" onClick={async () => {
        if (!confirmDelete) { setConfirmDelete(true); return }
        if (await onDelete()) onClose()
      }} />}
      <PxButton text={s.save} class="grow" onClick={async () => {
        if (name != null && !named.trim()) { showToast(s.nameMissing, true); return }
        const body: Record<string, unknown> = { base, threshold, catalyst_mode: mode, catalyst_a: a, catalyst_b: bb, public: pub }
        if (name != null) body.name = named.trim()
        if (await (submit ? submit(body) : save("PUT", "/decay", body))) onClose()
      }} />
    </div>
  </SheetBody>
}

/** The decay's explanation with fixed values (a decay seen from outside). */
export function DecaySentence({ decay: d }: { decay: Decay }) {
  const s = S.value
  const color = "var(--orange)"
  const fixed = (t: string) => <GapBox><span style={gapText(color)}>{t}</span></GapBox>
  const gaps: DecayGaps = {
    icon: () => <PixelIcon name="calendar" size={24} />, base: () => fixed(numText(d.base)), threshold: () => fixed(numText(d.threshold)),
    mode: () => fixed((s.catalysts as [string, string][]).find(([m]) => m === d.catalyst_mode)?.[1] ?? d.catalyst_mode),
    a: () => fixed(numText(d.catalyst_a)), b: () => fixed(numText(d.catalyst_b)), pct: () => fixed(numText(Math.round(d.catalyst_b * 100))),
  }
  return <GapColor.Provider value={color}><Cloze build={(c) => s.decaySentence(c, d.catalyst_mode, gaps)} /></GapColor.Provider>
}

/** "−50 › −75 › ..." for a good day and 1..4 missed days in a row. */
export const decaySteps = (d: Decay) => [d.base, ...[1, 2, 3, 4].map((n) => d.base + (d.catalyst_mode === "none" ? 0 : catalyst(d.catalyst_mode, d.catalyst_a, d.catalyst_b, n)))]
  .map((v) => "−" + fmtVal(applied("dec", v))).join("  ›  ")
