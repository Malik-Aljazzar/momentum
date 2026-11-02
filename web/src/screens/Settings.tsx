// Settings (android/.../ui/Settings.kt): edited locally, one always-visible save button stores
// exactly what changed (profile, game settings, language, password, profile picture).
import type { ComponentChildren } from "preact"
import { useEffect, useState } from "preact/hooks"
import { fmt } from "../format"
import { defaultBanner } from "../gfx/banners"
import type { AppState } from "../model"
import { e2eState } from "../e2e"
import { nav } from "../nav"
import { installed, isIos, notifyPermission, requestNotifications, testNotification } from "../push"
import {
  call, changeNotifications, changeSystemFont, logout, meta, notifications, save, setLang, showToast, systemFont,
  toggleDevMode, uploadAvatar,
} from "../store"
import { lang as currentLang, S, stringsFor, type Lang } from "../strings"
import {
  Avatar, BannerBg, BannerPicker, FitText, HyperText, IconBtn, Label, MentionSuggestions, PxButton, PxCard, PxTextArea, PxTextField, SceneBg, Sheet, Switch,
} from "../ui/components"
import { cropImage, pickFiles } from "../ui/media"
import { RELEASES } from "../changelog"
import { WhatsNewOverlay } from "./Overlays"

declare const __BUILD__: string

/** The settings page opened from the profile: fixed banner, the settings below scroll. */
export function SettingsPage({ st }: { st: AppState }) {
  const s = S.value
  return <div class="fill col">
    <div style={{ position: "relative", margin: "calc(var(--safe-top) + 12px) 16px 8px", height: 120, borderRadius: "var(--card-radius)", overflow: "hidden", flex: "none" }}>
      <SceneBg scene="settings" />
      <div style={{ position: "absolute", top: 6, left: 6 }}><IconBtn icon="ui_back" onClick={nav.pop} label="back" /></div>
      <div class="col" style={{ position: "absolute", left: 20, right: 20, bottom: 20 }}>
        <Label text={s.settingsTag} />
        <FitText text={s.settingsTitle} max={28} />
      </div>
    </div>
    <div style={{ flex: 1, position: "relative", minHeight: 0 }}><SettingsScreen st={st} /></div>
  </div>
}

function SettingsScreen({ st }: { st: AppState }) {
  const s = S.value
  const set = st.settings
  const [bio, setBio] = useState(st.user.bio ?? "")
  const [bioEdited, setBioEdited] = useState(false)
  const [username, setUsername] = useState(st.user.username)
  const [timezone, setTimezone] = useState(set.timezone)
  const [haptics, setHaptics] = useState(set.haptics)
  const [history, setHistory] = useState(set.show_history)
  const [priv, setPriv] = useState(!!st.user.private)
  const [dailyLimit, setDailyLimit] = useState(set.daily_limit !== false)
  const [banners, setBanners] = useState({ land: st.user.banners?.land ?? null, air: st.user.banners?.air ?? null, space: st.user.banners?.space ?? null })
  const [pickBanner, setPickBanner] = useState<"land" | "air" | "space" | null>(null)
  const [lang, setLangLocal] = useState<Lang>(currentLang.value)
  const [sysFont, setSysFont] = useState(systemFont.value)
  const [notif, setNotif] = useState(notifications.value)
  const [oldPw, setOldPw] = useState("")
  const [newPw, setNewPw] = useState("")
  const [avatar, setAvatar] = useState<Blob | null>(null)
  const [avatarUrl, setAvatarUrl] = useState<string | null>(null)
  const [pickTz, setPickTz] = useState(false)
  const [lowering, setLowering] = useState(false)
  const [saving, setSaving] = useState(false)
  const [taps, setTaps] = useState(0)
  const [showLog, setShowLog] = useState(false)
  const [permission, setPermission] = useState(notifyPermission())
  useEffect(() => () => { if (avatarUrl) URL.revokeObjectURL(avatarUrl) }, [avatarUrl])

  const body: Record<string, unknown> = {}
  if (bio.trim() !== (st.user.bio ?? "")) body.bio = bio.trim()
  if (username.trim().toLowerCase() !== st.user.username) body.username = username.trim().toLowerCase()
  if (timezone !== set.timezone) body.timezone = timezone
  if (haptics !== set.haptics) body.haptics = haptics
  if (history !== set.show_history) body.show_history = history
  if (dailyLimit !== (set.daily_limit !== false)) body.daily_limit = dailyLimit
  if (priv !== !!st.user.private) body.private = priv
  if (banners.land !== (st.user.banners?.land ?? null)) body.banner = banners.land
  if (banners.air !== (st.user.banners?.air ?? null)) body.banner_air = banners.air
  if (banners.space !== (st.user.banners?.space ?? null)) body.banner_space = banners.space
  const passwordChange = oldPw !== "" && newPw !== ""
  const dirty = Object.keys(body).length > 0 || avatar != null || passwordChange || lang !== currentLang.value || sysFont !== systemFont.value || notif !== notifications.value

  const doSave = async () => {
    setSaving(true)
    let ok = true
    if (avatar) { if (await uploadAvatar(avatar)) setAvatar(null); else ok = false }
    if (ok && Object.keys(body).length) ok = await save("PUT", "/settings", body, true)
    if (ok && passwordChange) {
      ok = await call("POST", "/settings/password", { old: oldPw, new: newPw })
      if (ok) { setOldPw(""); setNewPw("") }
    }
    if (ok && lang !== currentLang.value) setLang(lang)
    if (ok && sysFont !== systemFont.value) changeSystemFont(sysFont)
    if (ok && notif !== notifications.value) changeNotifications(notif)
    setSaving(false)
    if (ok) showToast(stringsFor(lang).saved)
  }

  const pickAvatar = async () => {
    const [file] = await pickFiles("image/*")
    if (!file) return
    const jpeg = await cropImage(file)
    if (jpeg) { setAvatar(jpeg); setAvatarUrl(URL.createObjectURL(jpeg)) }
  }

  return <>
    <div class="fill scroll" style={{ padding: "0 16px calc(var(--safe-bottom) + 120px)" }}>
      <div class="col" style={{ gap: 14 }}>
        <Section title={s.profile}>
          <div class="row" style={{ gap: 14 }}>
            <button class="col" style={{ alignItems: "center" }} onClick={pickAvatar}>
              {avatarUrl ? <img src={avatarUrl} style={{ width: 64, height: 64, borderRadius: 18 }} /> : <Avatar name={st.user.display_name} url={st.user.avatar} size={64} />}
              <span class="t-small" style={{ fontSize: 12 }}>{s.change}</span>
            </button>
            <div class="col grow" style={{ gap: 6 }}>
              <span class="t-small">{s.username}</span>
              <PxTextField value={username} placeholder="name" onInput={(v) => setUsername(v.toLowerCase().replace(/[^\p{L}\p{N}_.]/gu, ""))} />
            </div>
          </div>
          <span class="t-small" style={{ fontSize: 12 }}>{s.usernameHint}</span>
          <div class="row">
            <span class="t-small grow">{s.bio}</span>
            <span class="t-small" style={{ fontSize: 12, color: bio.length > 150 ? "var(--orange)" : undefined }}>{bio.length}/150</span>
          </div>
          <PxTextArea rows={3} value={bio} placeholder={s.bioHint} user maxLength={150} onInput={(v) => { setBio(v); setBioEdited(true) }} />
          {/* suggestions only while typing (a saved bio may well end with "@name") */}
          {bioEdited && <MentionSuggestions text={bio} onPick={(t) => { if (t.length <= 150) setBio(t) }} />}
          {st.user.email && <span class="t-small">{s.email}: {st.user.email}</span>}
          {st.user.google_email && <span class="t-small">{s.googleAccount}: {st.user.google_email}</span>}
          {/* one background per vehicle type; the current vehicle's is shown on profile + start screen */}
          <span class="t-small">{s.banner}</span>
          <div class="row" style={{ gap: 8, alignItems: "flex-start" }}>
            {(["land", "air", "space"] as const).map((k) => <div key={k} class="col grow">
              <button onClick={() => setPickBanner(k)} style={{ position: "relative", height: 64, borderRadius: 12, overflow: "hidden", width: "100%" }}>
                <BannerBg bkey={banners[k] ?? defaultBanner(k)} still />
              </button>
              <span class="t-small" style={{ fontSize: 12, color: "var(--text)", paddingTop: 4 }}>{s.bannerKind(k)}</span>
              <HyperText text={banners[k] ? s.bannerName(banners[k]!) : s.bannerAuto} class="t-small ellipsis" style={{ fontSize: 11 }} />
            </div>)}
          </div>
          <Toggle label={s.privateAccount} on={priv} onChange={setPriv} />
          <span class="t-small" style={{ fontSize: 12 }}>{s.privateAccountHint}</span>
          <button class="t-body" style={{ color: "var(--cyan)", textAlign: "left", padding: "6px 0" }} onClick={() => nav.push({ r: "blocked" })}>{s.blockedAccounts}  ›</button>
          {/* encrypted chats: the recovery key (or unlock this device) */}
          {e2eState.value !== "off" && <button class="t-body" style={{ color: "var(--cyan)", textAlign: "left", padding: "6px 0" }}
            onClick={() => nav.push({ r: e2eState.value === "locked" ? "e2eUnlock" : "e2eRecovery" })}>{e2eState.value === "locked" ? s.unlockTitle : s.recoveryTitle}  ›</button>}
          {st.user.avatar && !avatar && <PxButton text={s.removePhoto} kind="ghost" onClick={() => save("DELETE", "/settings/avatar", undefined, true)} />}
        </Section>

        <Section title={s.game}>
          <div class="row">
            <span class="t-body grow">{s.language}</span>
            <div class="row" style={{ borderRadius: 12, background: "var(--faint)", padding: 3 }}>
              {(["de", "en"] as Lang[]).map((l) => <button key={l} class="t-button" onClick={() => setLangLocal(l)} style={{
                borderRadius: 9, padding: "7px 12px", background: lang === l ? "var(--text)" : "transparent", color: lang === l ? "#000" : "var(--muted)",
              }}>{l.toUpperCase()}</button>)}
            </div>
          </div>
          <span class="t-small">{s.timezone}</span>
          <button class="row field" onClick={() => setPickTz(true)} style={{ textAlign: "left" }}>
            <span class="t-body grow">{timezone}</span><span style={{ color: "var(--muted)" }}>▾</span>
          </button>
          <Toggle label={s.haptics} on={haptics} onChange={setHaptics} />
          <Toggle label={s.notificationsSetting} on={notif} onChange={setNotif} />
          {notif && (isIos() && !installed()
            // iOS: only the installed PWA (home screen) may show notifications
            ? <span class="t-small" style={{ color: "var(--yellow)" }}>{s.notificationsInstallHint}</span>
            : <>
              <span class="t-small" style={{ color: permission === "granted" ? "var(--lime)" : "var(--orange)" }}>
                {permission === "granted" ? s.notificationsAllowed : s.notificationsBlockedWeb}
              </span>
              <div class="row" style={{ gap: 8 }}>
                {permission !== "granted" && <PxButton text={s.allowNotifications} class="grow"
                  onClick={async () => setPermission(await requestNotifications(currentLang.value))} />}
                <PxButton text={s.testNotification} kind="ghost" class="grow"
                  onClick={() => testNotification(s.testNotification, s.testNotificationText, currentLang.value)} />
              </div>
            </>)}
          <Toggle label={s.systemFontSetting} on={sysFont} onChange={setSysFont} />
          <span class="t-small" style={{ fontSize: 12 }}>{s.systemFontHint}</span>
          <Toggle label={s.showHistory} on={history} onChange={setHistory} />
          <Toggle label={s.dailyLimitSetting} on={dailyLimit} onChange={setDailyLimit} />
          <span class="t-small" style={{ fontSize: 12 }}>{s.dailyLimitHint}</span>
        </Section>

        {st.user.has_password !== false && <Section title={s.changePassword}>
          <PxTextField value={oldPw} onInput={setOldPw} placeholder={s.oldPassword} password />
          <PxTextField value={newPw} onInput={setNewPw} placeholder={s.newPassword} password />
        </Section>}

        {/* privacy policy + terms (server/legal.html), also the Play Store listing's links */}
        <Section title={s.legalSection}>
          <PxButton text={s.privacyPolicy} kind="ghost" style={{ width: "100%" }} onClick={() => { window.open("/privacy", "_blank") }} />
          <PxButton text={s.termsOfUse} kind="ghost" style={{ width: "100%" }} onClick={() => { window.open("/terms", "_blank") }} />
        </Section>

        {/* bug reports and feature requests (with screenshots) */}
        <Section title={s.reportTitle}>
          <span class="t-small">{s.reportSettingsHint}</span>
          <PxButton text={s.reportOpen} kind="ghost" onClick={() => nav.push({ r: "report" })} />
        </Section>
        {/* the changelog (CHANGELOG.md): shown after an update, the whole list here */}
        <Section title={s.whatsNewTitle}>
          <span class="t-small">{s.whatsNewHint}</span>
          <PxButton text={s.whatsNewOpen} kind="ghost" onClick={() => setShowLog(true)} />
        </Section>
        <Section title={s.dangerZone}>
          <PxButton text={s.logout} kind="ghost" onClick={() => logout()} />
          <PxButton text={s.lowerMomentum} kind="ghost" onClick={() => setLowering(true)} />
          <ConfirmButton label={s.deleteAccount} confirm={s.tapAgain} onConfirm={async () => { if (await call("DELETE", "/account")) void logout(false) }} />
        </Section>

        {/* tap 7x to toggle developer mode */}
        <div class="t-small" style={{ color: "rgba(244,244,244,0.25)", padding: 8 }}
          onClick={() => { if (taps + 1 >= 7) { setTaps(0); toggleDevMode() } else setTaps(taps + 1) }}>Momentum · Web {__BUILD__}</div>
      </div>
    </div>

    {/* always visible; enabled when something changed */}
    <div style={{ position: "absolute", left: 0, right: 0, bottom: 0, padding: "24px 16px calc(var(--safe-bottom) + 12px)", background: "linear-gradient(180deg, transparent, var(--bg) 40%)" }}>
      <PxButton text={saving ? "…" : s.save} style={{ width: "100%", opacity: dirty ? 1 : .4 }}
        onClick={() => { if (!dirty) showToast(s.nothingToSave); else if (!saving) void doSave() }} />
    </div>

    {lowering && <LowerMomentumSheet current={st.momentum} onDone={() => setLowering(false)} />}
    {showLog && <WhatsNewOverlay releases={RELEASES} onClose={() => setShowLog(false)} />}
    {pickBanner && <BannerPicker selected={banners[pickBanner]} allowAuto autoKey={defaultBanner(pickBanner)} kind={pickBanner}
      hyperspace={pickBanner === "space" ? !!st.user.hyperspace : undefined} ultra={pickBanner === "space" && !!st.user.ultra} onClose={() => setPickBanner(null)}
      onPick={(key) => { setBanners({ ...banners, [pickBanner]: key }); setPickBanner(null) }} />}
    {pickTz && <TimezonePicker zones={meta.value?.timezones ?? []} current={timezone} onPick={(tz) => { setPickTz(false); setTimezone(tz) }} onClose={() => setPickTz(false)} />}
  </>
}

function Section({ title, children }: { title: string; children: ComponentChildren }) {
  return <PxCard><div class="col" style={{ gap: 12 }}><Label text={title} />{children}</div></PxCard>
}

function Toggle({ label, on, onChange }: { label: string; on: boolean; onChange: (v: boolean) => void }) {
  return <div class="row" style={{ cursor: "pointer" }} onClick={() => onChange(!on)}>
    <span class="t-body grow">{label}</span>
    <Switch on={on} onChange={onChange} label={label} />
  </div>
}

/** Destructive action: first tap arms it, second tap within 3 s runs it. */
function ConfirmButton({ label, confirm, onConfirm }: { label: string; confirm: string; onConfirm: () => void }) {
  const [armed, setArmed] = useState(false)
  useEffect(() => { if (!armed) return; const h = setTimeout(() => setArmed(false), 3000); return () => clearTimeout(h) }, [armed])
  return <PxButton text={armed ? confirm : label} kind="danger" onClick={() => { if (armed) { setArmed(false); onConfirm() } else setArmed(true) }} />
}

function TimezonePicker({ zones, current, onPick, onClose }: { zones: string[]; current: string; onPick: (tz: string) => void; onClose: () => void }) {
  const [q, setQ] = useState("")
  const list = zones.filter((z) => z.toLowerCase().includes(q.trim().toLowerCase()))
  return <Sheet onClose={onClose}>
    <PxTextField value={q} onInput={setQ} placeholder={S.value.searchTimezone} />
    <div class="col">{list.map((tz) => <button key={tz} class="t-body" onClick={() => onPick(tz)}
      style={{ textAlign: "left", padding: "12px 0", color: tz === current ? "var(--yellow)" : undefined }}>{tz}</button>)}</div>
  </Sheet>
}

/** Set the own momentum down to a chosen value (0 = start over). */
function LowerMomentumSheet({ current, onDone }: { current: number; onDone: () => void }) {
  const s = S.value
  const [text, setText] = useState("0")
  const [confirm, setConfirm] = useState(false)
  const value = text === "" ? null : Number(text)
  const valid = value != null && value < current
  return <Sheet onClose={onDone}>
    <div class="t-title" style={{ fontSize: 20 }}>{s.lowerMomentum}</div>
    <span class="t-small">{s.lowerMomentumHint(fmt(current))}</span>
    <PxTextField value={text} inputMode="numeric" onInput={(v) => { setText(v.replace(/\D/g, "").slice(0, 12)); setConfirm(false) }} />
    {!valid && text !== "" && <span class="t-small" style={{ color: "var(--orange)" }}>{s.lowerMomentumInvalid}</span>}
    <PxButton text={confirm ? s.tapAgain : s.lowerMomentumTo(fmt(value ?? 0))} kind="danger" style={{ opacity: valid ? 1 : .4 }} onClick={async () => {
      if (!valid) return
      if (!confirm) { setConfirm(true); return }
      if (await save("POST", "/momentum/lower", { value })) onDone()
    }} />
  </Sheet>
}
