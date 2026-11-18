// Extensions (server/extensions.py; = android .../ui/Extensions.kt): the settings page to download, switch on / off and
// remove them, and the tab an extension adds - its page in a sandboxed iframe, answered by the bridge (extensions.ts).
import { useEffect, useRef, useState } from "preact/hooks"
import { answer, appDeps, forgetPage, pageOf, parseRequest, storePage, textOf, withOrigin } from "../extensions"
import type { CatalogExtension, InstalledExtension } from "../model"
import { layoutNow, nav } from "../nav"
import { online, refresh, req, state } from "../store"
import { extKey, locate } from "../tablayout"
import { lang, S } from "../strings"
import { EmptyHint, LoadMore, OfflineState, PixelIcon, PxButton, PxCard, Switch } from "../ui/components"
import { IconPickSheet } from "./Editors"
import { ConfirmButton } from "./Settings"

/** Settings -> Extensions: the catalog, each with download / on-off / remove. */
export function ExtensionsPage() {
  const s = S.value
  const [list, setList] = useState<CatalogExtension[] | null>(null)
  const [more, setMore] = useState(false)
  const [busy, setBusy] = useState<string | null>(null)
  const load = async (offset = 0) => {
    const r = await req<{ extensions: CatalogExtension[] }>("GET", `/extensions?offset=${offset}&limit=50`)
    if (!r) return
    setList((old) => (offset ? [...(old ?? []), ...r.extensions] : r.extensions))
    setMore(r.extensions.length >= 50)
  }
  useEffect(() => { void load() }, [])
  const update = (e: CatalogExtension) => setList((l) => l?.map((x) => (x.id === e.id ? e : x)) ?? null)
  const act = async (id: string, f: () => Promise<void>) => {
    setBusy(id)
    try { await f() } finally { setBusy(null) }
  }
  const install = (e: CatalogExtension) => act(e.id, async () => {
    const r = await req<{ extension: CatalogExtension; page: string }>("POST", `/extensions/${e.id}/install`)
    if (!r) return
    await storePage(e.id, r.extension.version, r.page)          // downloaded: it opens offline too
    update(r.extension)
    await refresh()                                             // its tabs come with the state (to be placed)
  })
  const toggle = (e: CatalogExtension, on: boolean) => act(e.id, async () => {
    const r = await req<{ extension: CatalogExtension }>("PUT", `/extensions/${e.id}`, { enabled: on })
    if (r) { update(r.extension); await refresh() }
  })
  const remove = (e: CatalogExtension) => act(e.id, async () => {
    if (!(await req("DELETE", `/extensions/${e.id}`))) return
    await forgetPage(e.id)
    update({ ...e, installed: false, enabled: false })
    await refresh()
  })
  const l = lang.value
  return <div class="fill scroll" style={{ padding: "4px 16px calc(var(--safe-bottom) + 32px)" }}>
    <div class="col" style={{ gap: 14 }}>
      <span class="t-small">{s.extensionsIntro}</span>
      {!list ? (!online.value && <OfflineState />) : list.length === 0 ? <EmptyHint title={s.extensionsNone} icon="sparkles" />
        : list.map((e) => <PxCard key={e.id}><div class="col extension-card" data-ext={e.id} style={{ gap: 12 }}>
          <div class="row" style={{ gap: 14, alignItems: "flex-start" }}>
            <div class="center" style={{ width: 52, height: 52, borderRadius: 16, background: "var(--faint)", flex: "none" }}>
              <PixelIcon name={e.icon} size={32} />
            </div>
            <div class="col grow" style={{ minWidth: 0 }}>
              <span class="t-strong">{textOf(e.name, l)}</span>
              <span class="t-small" style={{ fontSize: 12, color: e.official ? "var(--yellow)" : undefined }}>
                {(e.official ? s.extensionOfficial : s.extensionBy(e.author)) + (e.tabs?.length ? " · " + s.extensionTabsCount(e.tabs.length) : "") + ` · ${Math.max(1, Math.round(e.size / 1024))} KB`}
              </span>
            </div>
          </div>
          <span class="t-body" style={{ color: "var(--muted)" }}>{textOf(e.description, l)}</span>
          {e.permissions?.includes("rules") && <div class="row ext-permission" style={{ gap: 8 }}>
            <PixelIcon name="ui_lock" size={14} />
            <span class="t-small" style={{ color: "var(--yellow)" }}>{s.extensionPermRules}</span>
          </div>}
          {!e.installed
            ? <PxButton text={busy === e.id ? "…" : s.extensionDownload} onClick={() => { if (!busy) void install(e) }} />
            : <>
              <div class="row" style={{ cursor: "pointer" }} onClick={() => { if (!busy) void toggle(e, !e.enabled) }}>
                <span class="t-body grow">{s.extensionActive}</span>
                <Switch on={e.enabled} onChange={(v) => { if (!busy) void toggle(e, v) }} label={s.extensionActive} />
              </div>
              {/* switched on, but a tab of it isn't placed yet: where to place it */}
              {e.enabled && (e.tabs ?? []).some((t) => !locate(layoutNow(), extKey(e.id, t.key))) && <div class="col tabs-waiting" style={{ gap: 10 }}>
                <span class="t-small">{s.extensionTabsWaiting}</span>
                <PxButton text={s.extensionArrangeTabs} kind="ghost" onClick={() => nav.push({ r: "settings", page: "tabs" })} />
              </div>}
              <ConfirmButton label={s.extensionRemove} confirm={s.extensionRemoveConfirm} onConfirm={() => { void remove(e) }} />
            </>}
        </div></PxCard>)}
      {list && <LoadMore hasMore={more} load={() => load(list.length)} />}
      <span class="t-small" style={{ textAlign: "center", padding: "6px 0" }}>{s.extensionsCommunity}</span>
    </div>
  </div>
}

/** The tab an extension adds: its page in an iframe that may only run scripts (an opaque origin: no token, no app
 *  storage, no network by its CSP). [visible]: on screen now - the page hears "show" / "hide". */
/** An extension's tab "ext:<id>:<tabKey>": its page, told which tab it is (the bridge's context `tab`). */
export function ExtensionTab({ id, tabKey, visible }: { id: string; tabKey: string; visible: boolean }) {
  const s = S.value
  const ext = state.value?.extensions?.find((e) => e.id === id)
  const [html, setHtml] = useState<string | null>(null)
  const [failed, setFailed] = useState(false)
  const frame = useRef<HTMLIFrameElement>(null)
  const current = useRef<InstalledExtension | undefined>(ext)
  current.current = ext
  // the page asked for an icon (bridge pickIcon): the sheet is open until a pick or closing it (= null)
  const [picking, setPicking] = useState<{ current: string | null } | null>(null)
  const pending = useRef<((icon: string | null) => void) | null>(null)
  const finish = (icon: string | null) => {
    pending.current?.(icon)
    pending.current = null
    setPicking(null)
  }
  const pickIcon = (cur: string | null) => new Promise<string | null>((done) => {
    pending.current?.(null)                       // a second ask: the first one picked nothing
    pending.current = done
    setPicking({ current: cur })
  })
  useEffect(() => () => pending.current?.(null), [])
  useEffect(() => {
    if (!ext) return
    let alive = true
    setFailed(false)
    void pageOf(ext).then((page) => {
      if (!alive) return
      if (page) setHtml(withOrigin(page, location.origin)); else setFailed(true)
    })
    return () => { alive = false }
  }, [id, ext?.version])
  // the bridge: only messages from this frame, answered to it alone
  useEffect(() => {
    const onMessage = (e: MessageEvent) => {
      const win = frame.current?.contentWindow
      const ex = current.current
      if (!win || e.source !== win || !ex) return
      const r = parseRequest(e.data)
      if (!r) return
      void answer(ex.id, r, appDeps(ex, tabKey, pickIcon)).then((a) => frame.current?.contentWindow?.postMessage(JSON.stringify(a), "*"))
    }
    addEventListener("message", onMessage)
    return () => removeEventListener("message", onMessage)
  }, [])
  useEffect(() => {
    frame.current?.contentWindow?.postMessage(JSON.stringify({ momentum: 1, event: visible ? "show" : "hide" }), "*")
  }, [visible])
  if (!ext) return null
  return <div class="fill col" data-ext-tab={`${id}:${tabKey}`} style={{ paddingTop: "var(--safe-top)", paddingBottom: "calc(var(--tabbar) + var(--safe-bottom))" }}>
    {html ? <iframe ref={frame} class="extension-frame" title={textOf(ext.name, lang.value)} sandbox="allow-scripts" srcDoc={html}
      style={{ flex: 1, width: "100%", border: 0, background: "var(--bg)", display: "block" }} />
      : failed && <EmptyHint title={textOf(ext.name, lang.value)} sub={s.extensionLoadFailed} icon={ext.icon} />}
    {picking && <IconPickSheet current={picking.current} onPick={finish} onClose={() => finish(null)} />}
  </div>
}
