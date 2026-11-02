// Login / sign-up, username onboarding, offline start (android/.../ui/Login.kt).
import { useState } from "preact/hooks"
import { googleStartUrl } from "../googlelogin"
import { auth, boot, logout, meta, save, toggleDevMode } from "../store"
import { lang, S, setLang, type Lang } from "../strings"
import { FitText, Label, PixelIcon, PxButton, PxCard, PxTextField, SceneBg, VehicleSprite } from "../ui/components"

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
  const [user, setUser] = useState("")
  const [pass, setPass] = useState("")
  const [busy, setBusy] = useState(false)
  const [taps, setTaps] = useState(0)
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
          <form class="col" style={{ gap: 12 }} onSubmit={(e) => { e.preventDefault(); void submit() }}>
            <PxTextField value={user} onInput={setUser} placeholder={register ? s.email : s.emailOrUsername} type={register ? "email" : "text"} />
            <PxTextField value={pass} onInput={setPass} placeholder={s.password} password onDone={submit} />
            <PxButton text={register ? s.register : s.login} onClick={submit} disabled={busy} style={{ width: "100%" }} />
            {meta.value?.google_web && <PxButton text={s.googleSignIn} kind="ghost" style={{ width: "100%" }}
              onClick={() => { location.href = googleStartUrl(lang.value) }} />}
            <a class="t-small" href="/terms" target="_blank" style={{ color: "var(--muted)", fontSize: 12, textAlign: "center", textDecoration: "none" }}>
              {s.acceptTerms}
            </a>
            <button type="button" class="t-body" style={{ color: "var(--muted)", padding: 8 }} onClick={() => setRegister(!register)}>
              {register ? s.toLogin : s.toRegister}
            </button>
          </form>
        </PxCard>
      </div>
      <div style={{ height: 24 }} />
    </div>
  </div>
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
