// Login / sign-up, "forgot password", confirming the e-mail address, username onboarding, offline start
// (android/.../ui/Login.kt).
import { useEffect, useState } from "preact/hooks"
import { googleStartUrl } from "../googlelogin"
import { nav } from "../nav"
import {
  auth, boot, confirmEmail, confirmSentTo, forgotPassword, lockedLogin, logout, meta, resetPassword, save, sendConfirmCode, state, toggleDevMode,
} from "../store"
import { lang, S, setLang, type Lang } from "../strings"
import { FitText, Label, PixelIcon, PxButton, PxCard, PxTextField, SceneBg, TopBar, VehicleSprite } from "../ui/components"

/** DE | EN switch. */
export function LanguageSwitch() {
  return <div class="row" style={{ borderRadius: 12, background: "var(--faint)", padding: 3 }}>
    {(["de", "en"] as Lang[]).map((l) => <button key={l} class="t-button" onClick={() => setLang(l)} style={{
      borderRadius: 9, padding: "7px 12px", background: lang.value === l ? "var(--text)" : "transparent",
      color: lang.value === l ? "#000" : "var(--muted)",
    }}>{l.toUpperCase()}</button>)}
  </div>
}

const glass = "linear-gradient(180deg, rgba(27,30,43,0.9), rgba(19,21,29,0.9))"

export function LoginScreen() {
  const s = S.value
  const [register, setRegister] = useState(false)
  const [forgot, setForgot] = useState(false)
  const [user, setUser] = useState("")
  const [pass, setPass] = useState("")
  const [busy, setBusy] = useState(false)
  const [taps, setTaps] = useState(0)
  // locked after too many wrong passwords: straight to the new password (the code is on its way)
  const locked = lockedLogin.value
  useEffect(() => { if (locked) setForgot(true) }, [locked])
  const submit = async () => {
    if (busy || !user.trim() || !pass) return
    setBusy(true)
    await auth(user.trim(), pass, register)
    setBusy(false)
  }
  return <div class="fill">
    <SceneBg scene="space" sun={false} />
    <div class="fill scroll col" style={{ padding: "calc(var(--safe-top) + 12px) 16px calc(var(--safe-bottom) + 24px)", justifyContent: "space-between", alignItems: "center" }}>
      <div class="row" style={{ width: "100%", justifyContent: "flex-end" }}><LanguageSwitch /></div>
      <div class="col" style={{ alignItems: "center", width: "100%" }}>
        <Label text={s.tagline} />
        <div style={{ width: "90%" }} onClick={() => { if (taps + 1 >= 7) { setTaps(0); toggleDevMode() } else setTaps(taps + 1) }}>
          <FitText text="Momentum" max={48} class="t-block" style={{ textAlign: "center" }} />
        </div>
        <div style={{ height: 20 }} />
        <VehicleSprite vkey="gauge" dpPerPixel={3.5} />
        <div style={{ height: 24 }} />
        <PxCard bg={glass} style={{ width: "100%", maxWidth: 420 }}>
          {forgot ? <ForgotPassword key={locked?.login ?? ""} initial={locked?.login ?? user} note={locked?.message}
            onBack={() => { lockedLogin.value = null; setForgot(false) }} /> :
          <form class="col" style={{ gap: 12 }} onSubmit={(e) => { e.preventDefault(); void submit() }}>
            <PxTextField value={user} onInput={setUser} placeholder={register ? s.email : s.emailOrUsername} type={register ? "email" : "text"} />
            <PxTextField value={pass} onInput={setPass} placeholder={s.password} password onDone={submit} />
            <PxButton text={register ? s.register : s.login} onClick={submit} disabled={busy} style={{ width: "100%" }} />
            {!register && meta.value?.mail && <button type="button" class="t-small forgot-link" style={{ color: "var(--cyan)", padding: 4 }}
              onClick={() => setForgot(true)}>{s.forgotPassword}</button>}
            {meta.value?.google_web && <PxButton text={s.googleSignIn} kind="ghost" style={{ width: "100%" }}
              onClick={() => { location.href = googleStartUrl(lang.value) }} />}
            <a class="t-small" href="/terms" target="_blank" style={{ color: "var(--muted)", fontSize: 12, textAlign: "center", textDecoration: "none" }}>
              {s.acceptTerms}
            </a>
            <button type="button" class="t-body" style={{ color: "var(--muted)", padding: 8 }} onClick={() => setRegister(!register)}>
              {register ? s.toLogin : s.toRegister}
            </button>
          </form>}
        </PxCard>
      </div>
      <div style={{ height: 24 }} />
    </div>
  </div>
}

/** "Forgot password": a code to the account's confirmed address, then the code and a new password (logs in).
 *  [note]: the account is locked and the code already on its way - the server's words. */
function ForgotPassword({ initial, note, onBack }: { initial: string; note?: string; onBack: () => void }) {
  const s = S.value
  const [login, setLogin] = useState(initial)
  const [sent, setSent] = useState(!!note)
  const [code, setCode] = useState("")
  const [pass, setPass] = useState("")
  const [busy, setBusy] = useState(false)
  const send = async () => {
    if (busy || !login.trim()) return
    setBusy(true)
    if (await forgotPassword(login.trim())) setSent(true)
    setBusy(false)
  }
  const store = async () => {
    if (busy || code.replace(/\D/g, "").length !== 6 || pass.length < 6) return
    setBusy(true)
    await resetPassword(login.trim(), code, pass)
    setBusy(false)
  }
  return <form class="col forgot" style={{ gap: 12 }} onSubmit={(e) => { e.preventDefault(); void (sent ? store() : send()) }}>
    <div class="t-strong">{s.newPassword}</div>
    <div class="t-small">{sent ? note ?? s.forgotSent : s.forgotHint}</div>
    <PxTextField value={login} onInput={setLogin} placeholder={s.emailOrUsername} />
    {sent && <>
      <PxTextField value={code} onInput={(v) => setCode(v.replace(/[^\d ]/g, "").slice(0, 7))} placeholder={s.confirmCode} inputMode="numeric" />
      <PxTextField value={pass} onInput={setPass} placeholder={s.newPassword} password onDone={store} />
    </>}
    <PxButton text={sent ? s.savePassword : s.sendCode} onClick={sent ? store : send} disabled={busy} style={{ width: "100%" }} />
    {sent && <button type="button" class="t-small" style={{ color: "var(--cyan)", padding: 4 }} onClick={send}>{s.resendCode}</button>}
    <button type="button" class="t-body" style={{ color: "var(--muted)", padding: 8 }} onClick={onBack}>{s.backToLogin}</button>
  </form>
}

/** Confirming the e-mail address (required, server/mail.py): the mailed code; another one after a minute, another
 *  address (typed wrong), log out. Opened before the deadline (reminder on the home tab) it has a back arrow. */
export function ConfirmEmailScreen({ opened = false }: { opened?: boolean }) {
  const s = S.value
  const email = state.value?.user.email ?? ""
  const sent = confirmSentTo.value === email
  const [code, setCode] = useState("")
  const [busy, setBusy] = useState(false)
  const [wait, setWait] = useState(sent ? 60 : 0)
  const [other, setOther] = useState(false)
  const [newMail, setNewMail] = useState("")
  useEffect(() => {
    if (wait <= 0) return
    const h = setTimeout(() => setWait(wait - 1), 1000)
    return () => clearTimeout(h)
  }, [wait])
  const send = async (to?: string) => {
    if (busy) return
    setBusy(true)
    const next = await sendConfirmCode(to)
    setBusy(false)
    if (next != null) { setWait(next); setOther(false); setCode("") }
  }
  const submit = async () => {
    if (busy || code.replace(/\D/g, "").length !== 6) return
    setBusy(true)
    const ok = await confirmEmail(code)
    setBusy(false)
    if (ok && opened) nav.pop()
  }
  const card = <PxCard bg={glass} style={{ width: "100%", maxWidth: 420 }}>
    <div class="col confirm-email" style={{ gap: 12 }}>
      <FitText text={s.confirmEmailTitle} max={22} />
      <div class="t-small">{sent ? s.confirmEmailSent(email) : s.confirmEmailAsk(email)}</div>
      {/* another address (typed wrong): only that, until it's sent or cancelled */}
      {other ? <>
        <PxTextField value={newMail} onInput={setNewMail} placeholder={s.newEmail} type="email" onDone={() => send(newMail.trim())} />
        <PxButton text={s.sendCode} onClick={() => send(newMail.trim())} disabled={busy || !newMail.includes("@")} style={{ width: "100%" }} />
        <button class="t-small" style={{ color: "var(--muted)", padding: 4 }} onClick={() => setOther(false)}>{s.cancel}</button>
      </> : <>
        {sent ? <>
          <PxTextField value={code} placeholder={s.confirmCode} inputMode="numeric" onDone={submit}
            onInput={(v) => setCode(v.replace(/[^\d ]/g, "").slice(0, 7))} />
          <PxButton text={s.confirmButton} onClick={submit} disabled={busy} style={{ width: "100%", opacity: code.replace(/\D/g, "").length === 6 ? 1 : .4 }} />
          <button class="t-small" disabled={wait > 0} style={{ color: wait > 0 ? "var(--muted)" : "var(--cyan)", padding: 4 }} onClick={() => send()}>
            {wait > 0 ? s.resendIn(wait) : s.resendCode}
          </button>
          <div class="t-small" style={{ fontSize: 12 }}>{s.checkSpam}</div>
        </> : <PxButton text={s.sendCode} onClick={() => send()} disabled={busy} style={{ width: "100%" }} />}
        <button class="t-small" style={{ color: "var(--muted)", padding: 4 }} onClick={() => setOther(true)}>{s.otherAddress}</button>
      </>}
      {!opened && <button class="t-body" style={{ color: "var(--muted)", padding: 8 }} onClick={() => logout()}>{s.logout}</button>}
    </div>
  </PxCard>
  if (opened) return <div class="fill col">
    <TopBar title={s.confirmEmailTitle} />
    <div class="scroll col" style={{ flex: 1, padding: "8px 16px 24px", alignItems: "center" }}>{card}</div>
  </div>
  return <div class="fill">
    <SceneBg scene="space" />
    <div class="fill scroll col" style={{ padding: "calc(var(--safe-top) + 16px) 16px calc(var(--safe-bottom) + 16px)", justifyContent: "center", alignItems: "center" }}>
      {card}
    </div>
  </div>
}

/** Before the deadline: a reminder card (home tab) that opens the confirm screen. */
export function ConfirmReminder() {
  const s = S.value
  const due = state.value?.user.email_confirm
  if (!due || due.required) return null
  const date = new Date(due.due).toLocaleDateString(lang.value === "de" ? "de-DE" : "en-GB", { day: "numeric", month: "long" })
  return <button class="col confirm-reminder" onClick={() => nav.push({ r: "confirmEmail" })} style={{
    gap: 8, padding: 14, borderRadius: 16, background: "var(--card)", border: "1px solid var(--yellow)", textAlign: "left", width: "100%",
  }}>
    <span class="t-small" style={{ color: "var(--text)" }}>{s.confirmDue(date)}</span>
    <span class="t-button" style={{ color: "var(--yellow)" }}>{s.confirmNow}</span>
  </button>
}

/** Right after signing up (and for old accounts): pick the public @username. */
export function UsernameScreen() {
  const s = S.value
  const [name, setName] = useState("")
  const [busy, setBusy] = useState(false)
  const submit = async () => {
    if (busy || name.length < 3) return
    setBusy(true)
    await save("PUT", "/settings", { username: name }, true)
    setBusy(false)
  }
  return <div class="fill">
    <SceneBg scene="space" />
    <div class="fill scroll col" style={{ padding: "calc(var(--safe-top) + 16px) 16px calc(var(--safe-bottom) + 16px)", justifyContent: "center", alignItems: "center" }}>
      <VehicleSprite vkey="friends" dpPerPixel={3} />
      <div style={{ height: 20 }} />
      <PxCard bg={glass} style={{ width: "100%", maxWidth: 420 }}>
        <div class="col" style={{ gap: 12 }}>
          <FitText text={s.chooseUsername} max={22} />
          <div class="t-small">{s.chooseUsernameSub}</div>
          <PxTextField value={name} placeholder="@name" onDone={submit}
            onInput={(v) => setName(v.toLowerCase().replace(/[^\p{L}\p{N}_.]/gu, "").slice(0, 30))} />
          <div class="t-small" style={{ fontSize: 12 }}>{s.usernameHint}</div>
          <PxButton text={s.next} onClick={submit} style={{ width: "100%", opacity: name.length >= 3 ? 1 : .4 }} />
          <button class="t-body" style={{ color: "var(--muted)", padding: 8 }} onClick={() => logout()}>{s.logout}</button>
        </div>
      </PxCard>
    </div>
  </div>
}

export function OfflineScreen() {
  const s = S.value
  return <div class="fill">
    <SceneBg scene="settings" />
    <div class="fill col center" style={{ padding: 24, gap: 14, flexDirection: "column" }}>
      <PixelIcon name="storm" size={64} />
      <div class="t-body" style={{ color: "var(--muted)", textAlign: "center", maxWidth: 420 }}>{s.offline}</div>
      <PxButton text={s.retry} onClick={() => boot()} style={{ width: "100%", maxWidth: 420 }} />
    </div>
    <div style={{ position: "absolute", top: "calc(var(--safe-top) + 16px)", right: 16 }}><LanguageSwitch /></div>
  </div>
}
