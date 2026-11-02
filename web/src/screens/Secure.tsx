// End-to-end encryption on screen (android/.../ui/Secure.kt): a direct chat's overview, the encryption
// switch with the safety codes (also in a group's overview), the recovery key, unlocking a new device.
import { useEffect, useState } from "preact/hooks"
import {
  chatKeys, keyWarnings, markRecoveryNoted, myPublicKey, recoveryCode, resetKey, safetyCode, trustKey, unlock, type MemberKey,
} from "../e2e"
import type { Conversation } from "../model"
import { nav } from "../nav"
import { online, refresh, req, showToast, state } from "../store"
import { S } from "../strings"
import { Avatar, OfflineState, OfflineStrip, PixelIcon, PxButton, PxTextField, Sheet, TopBar } from "../ui/components"
import { SectionTitle, SwitchRow } from "./shared"

/** A direct chat's overview: the other person, mute, encryption + safety codes. */
export function ChatInfoScreen({ id }: { id: number }) {
  const s = S.value
  const [conv, setConv] = useState<Conversation | null>(null)
  useEffect(() => { void req<{ conversation: Conversation }>("GET", `/conversations/${id}`).then((r) => setConv(r?.conversation ?? null)) }, [id])
  const other = conv?.members[0]
  return <div class="fill col">
    <TopBar title={s.chatInfo} />
    <OfflineStrip />
    {!conv ? (!online.value && <OfflineState />) : <div class="scroll" style={{ flex: 1, paddingBottom: 40 }}>
      {other && <div class="col" style={{ alignItems: "center", padding: "20px 16px 8px", gap: 10 }}>
        <Avatar name={other.display_name} url={other.avatar} size={84} />
        <span class="t-title" style={{ fontSize: 22 }}>{other.username}</span>
        <PxButton text={s.viewProfile} kind="ghost" onClick={() => nav.push({ r: "user", username: other.username })} />
      </div>}
      <div style={{ padding: "12px 16px" }}>
        <SwitchRow title={s.muteChat} hint={s.muteChatHint} on={!!conv.muted} onChange={async (v) => {
          const r = await req<{ conversation: Conversation }>("PUT", `/conversations/${id}/mute`, { muted: v })
          if (r) { setConv(r.conversation); void refresh() }
        }} />
      </div>
      <EncryptionSection conv={conv} onConv={setConv} />
    </div>}
  </div>
}

/** The encryption switch (direct: both, group: admins) and everyone's safety code to compare in person. */
export function EncryptionSection({ conv, onConv }: { conv: Conversation; onConv: (c: Conversation) => void }) {
  const s = S.value
  const [keys, setKeys] = useState<MemberKey[]>([])
  const [codes, setCodes] = useState<Record<number, string>>({})
  const [mine, setMine] = useState<string | null>(null)
  useEffect(() => {
    void chatKeys(conv.id, true).then(async (ks) => {
      setKeys(ks)
      const out: Record<number, string> = {}
      for (const k of ks) if (k.public) out[k.user_id] = await safetyCode(k.public)
      setCodes(out)
    }).catch(() => {})
    const pub = myPublicKey()
    if (pub) void safetyCode(pub).then(setMine)
  }, [conv.id])
  const me = state.value?.user.id
  return <div class="col encryption" style={{ padding: "4px 0 12px" }}>
    <SectionTitle text={s.e2eTitle} />
    <div class="col" style={{ padding: "0 16px", gap: 12 }}>
      <div style={{ opacity: conv.can_switch_e2e ? 1 : .55, pointerEvents: conv.can_switch_e2e ? undefined : "none" }}>
        <SwitchRow title={s.e2eTitle} hint={s.e2eHint + (conv.can_switch_e2e ? "" : " " + s.e2eOnlyAdmins)} on={!!conv.e2e} onChange={async (v) => {
          const r = await req<{ conversation: Conversation }>("PUT", `/conversations/${conv.id}/e2e`, { on: v })
          if (r) onConv(r.conversation)
        }} />
      </div>
      <span class="t-strong" style={{ marginTop: 6 }}>{s.safetyCodes}</span>
      <span class="t-small" style={{ fontSize: 12 }}>{s.safetyHint}</span>
      {mine && <CodeRow name={s.yourCode} code={mine} />}
      {keys.filter((k) => k.user_id !== me).map((k) => <CodeRow key={k.user_id} name={k.username} code={codes[k.user_id] ?? "—"}
        changed={!!keyWarnings.value[k.user_id]} onOk={() => void trustKey(k.user_id)} />)}
    </div>
  </div>
}

function CodeRow({ name, code, changed, onOk }: { name: string; code: string; changed?: boolean; onOk?: () => void }) {
  const s = S.value
  return <div class="col safety-code" style={{ gap: 4, borderRadius: 12, background: "var(--card)", padding: "10px 12px", border: changed ? "1px solid var(--yellow)" : undefined }}>
    <span class="t-small" style={{ color: changed ? "var(--yellow)" : "var(--text)" }}>{name}</span>
    <span style={{ font: "500 15px ui-monospace, Menlo, monospace", letterSpacing: .5, color: "var(--text)" }}>{code}</span>
    {changed && <div class="row" style={{ gap: 8, marginTop: 4 }}>
      <span class="t-small grow" style={{ fontSize: 12 }}>{s.keyChanged(name)}</span>
      <PxButton text={s.keyChangedOk} kind="ghost" onClick={() => onOk?.()} />
    </div>}
  </div>
}

/** The recovery key, to write down (shown once after the key was made, later from the settings). */
export function RecoveryScreen() {
  const s = S.value
  const code = recoveryCode()
  return <div class="fill col">
    <TopBar title={s.recoveryTitle} />
    <div class="scroll col" style={{ flex: 1, padding: 16, gap: 16 }}>
      <div class="center" style={{ padding: 8 }}><PixelIcon name="ui_lock" size={40} color="var(--yellow)" /></div>
      <span class="t-body">{s.recoveryIntro}</span>
      {code ? <div class="recovery-code" style={{ display: "grid", gridTemplateColumns: "repeat(4, 1fr)", gap: 8, borderRadius: 14, background: "var(--card)", padding: 14 }}>
        {code.split(" ").map((g, i) => <span key={i} style={{ font: "600 17px ui-monospace, Menlo, monospace", letterSpacing: 1, textAlign: "center", color: "var(--text)" }}>{g}</span>)}
      </div> : <span class="t-small">{s.unlockCardHint}</span>}
      {code && <PxButton text={s.recoveryCopy} kind="ghost" onClick={async () => {
        try { await navigator.clipboard.writeText(code); showToast(s.copied) } catch { /* no clipboard: write it down */ }
      }} />}
      {code && <PxButton text={s.recoveryNoted} onClick={async () => { await markRecoveryNoted(); nav.pop() }} />}
    </div>
  </div>
}

/** A new device: the recovery key opens the backup of my key; lost -> a new key. */
export function UnlockScreen() {
  const s = S.value
  const [code, setCode] = useState("")
  const [busy, setBusy] = useState(false)
  const [confirmReset, setConfirmReset] = useState(false)
  const go = async () => {
    if (busy) return
    setBusy(true)
    const ok = await unlock(code).catch(() => false)
    setBusy(false)
    if (ok) nav.pop()
    else showToast(s.unlockWrong, true)
  }
  return <div class="fill col">
    <TopBar title={s.unlockTitle} />
    <div class="scroll col" style={{ flex: 1, padding: 16, gap: 16 }}>
      <div class="center" style={{ padding: 8 }}><PixelIcon name="ui_lock" size={40} color="var(--yellow)" /></div>
      <span class="t-body">{s.unlockIntro}</span>
      <PxTextField value={code} onInput={setCode} placeholder={s.unlockPlaceholder} onDone={go} />
      <PxButton text={busy ? "…" : s.unlockButton} onClick={go} disabled={!code.trim()} />
      <button class="t-small" style={{ color: "var(--cyan)", padding: 8 }} onClick={() => setConfirmReset(true)}>{s.resetKey}</button>
    </div>
    {confirmReset && <Sheet onClose={() => setConfirmReset(false)}>
      <div class="t-title" style={{ fontSize: 20 }}>{s.resetKeyConfirm}</div>
      <span class="t-small">{s.resetKeyWarning}</span>
      <div class="row" style={{ gap: 10 }}>
        <PxButton text={s.cancel} kind="ghost" class="grow" onClick={() => setConfirmReset(false)} />
        <PxButton text={s.resetKeyConfirm} kind="danger" class="grow" onClick={async () => {
          setConfirmReset(false)
          if (await resetKey().catch(() => false)) nav.replace({ r: "e2eRecovery" })
        }} />
      </div>
    </Sheet>}
  </div>
}
