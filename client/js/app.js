// App shell: auth, Instagram-style bottom tabs, state store, milestones.

import { api, getToken, setToken } from "./api.js";
import * as screens from "./screens.js";
import { confetti, esc, fmt, floatText, haptic, icon, sprite, toast } from "./ui.js";

const TABS = [
  { id: "home", icon: "ui_home", label: "Start" },
  { id: "acc", icon: "ui_up", label: "Beschleuniger" },
  { id: "dec", icon: "ui_down", label: "Bremser" },
  { id: "settings", icon: "ui_gear", label: "Einstellungen" },
];

const root = document.getElementById("app");
const app = {
  meta: null,
  state: null,
  tab: "home",
  scroll: {},          // remembered scroll position per tab
  stale: new Set(),    // tabs whose content must be re-rendered when shown
  shownMomentum: null, // for the count-up animation on the home screen
};

// ---------------------------------------------------------------- context for screens

const ctx = {
  get state() { return app.state; },
  get meta() { return app.meta; },
  toast,
  /** API call without state update. Returns data or null (error toasted). */
  async call(method, path, body) {
    try { return await api(method, path, body); } catch (e) { handleError(e); return null; }
  },
  /** API call that returns a new state. Returns true on success. */
  async save(method, path, body, { quiet = false } = {}) {
    const data = await ctx.call(method, path, body);
    if (!data) return false;
    setState(data.state);
    if (!quiet && method !== "DELETE") toast("Gespeichert");
    return true;
  },
  async trigger(rule, el) {
    if (!rule) return;
    haptic(app.state);
    el.classList.remove("pop");
    void el.offsetWidth;
    el.classList.add("pop");
    const data = await ctx.call("POST", `/rules/${rule.id}/trigger`);
    if (!data) return;
    floatText(el.querySelector(".rule-value") || el, (data.delta >= 0 ? "+" : "−") + fmt(Math.abs(data.delta)), rule.kind);
    setState(data.state);
    if (data.milestone) showMilestone();
  },
  logout: async (skipApi = false) => {
    if (!skipApi) await api("POST", "/auth/logout").catch(() => {});
    setToken(null);
    app.state = null;
    showLogin();
  },
};

function handleError(e) {
  if (e.status === 401 && app.state) {
    toast("Sitzung abgelaufen");
    ctx.logout(true);
  } else {
    toast(e.message, "error");
  }
}

function setState(state) {
  app.state = state;
  TABS.forEach((t) => app.stale.add(t.id));
  renderTab(app.tab);
}

// ---------------------------------------------------------------- tabs

function shell() {
  root.innerHTML = `
    <main class="screens">${TABS.map((t) => `<section class="screen" data-tab="${t.id}" hidden></section>`).join("")}</main>
    <nav class="tabbar" role="tablist">
      ${TABS.map((t) => `<button type="button" role="tab" data-tab="${t.id}" aria-label="${t.label}">${icon(t.icon, 24)}</button>`).join("")}
    </nav>`;
  root.querySelectorAll(".tabbar button").forEach((b) =>
    b.addEventListener("click", () => switchTab(b.dataset.tab)));
}

function switchTab(id) {
  if (id === app.tab) {   // tapping the active tab scrolls to top, like Instagram
    window.scrollTo({ top: 0, behavior: "smooth" });
    return;
  }
  app.scroll[app.tab] = window.scrollY;
  app.tab = id;
  try { history.replaceState(null, "", `#${id}`); } catch { /* sandboxed */ }
  renderTab(id);
  window.scrollTo(0, app.scroll[id] || 0);
}

function renderTab(id) {
  root.querySelectorAll(".screen").forEach((s) => { s.hidden = s.dataset.tab !== id; });
  root.querySelectorAll(".tabbar button").forEach((b) =>
    b.setAttribute("aria-selected", String(b.dataset.tab === id)));
  const el = root.querySelector(`.screen[data-tab="${id}"]`);
  if (!el || (!app.stale.has(id) && el.childElementCount)) {
    if (id === "home") countUp();
    return;
  }
  app.stale.delete(id);
  if (id === "home") {
    el.innerHTML = screens.home(ctx);
    countUp();
  } else if (id === "acc" || id === "dec") {
    el.innerHTML = screens.rules(ctx, id);
    screens.mountRules(el, ctx, id);
  } else {
    el.innerHTML = screens.settings(ctx);
    screens.mountSettings(el, ctx);
  }
}

function countUp() {
  const el = document.getElementById("momentum-num");
  const target = app.state.momentum;
  const from = app.shownMomentum ?? target;
  app.shownMomentum = target;
  if (!el || from === target) return;
  const t0 = performance.now(), dur = 700;
  const step = (t) => {
    const k = Math.min(1, (t - t0) / dur);
    const eased = 1 - (1 - k) ** 3;
    el.textContent = fmt(from + (target - from) * eased);
    if (k < 1) requestAnimationFrame(step);
  };
  requestAnimationFrame(step);
}

// ---------------------------------------------------------------- milestone

function showMilestone() {
  const v = app.meta.vehicles[app.state.vehicle.index];
  const el = document.createElement("div");
  el.className = `milestone scene-${v.category}`;
  el.innerHTML = `
    <div class="scene"></div>
    <small class="card-label">Meilenstein erreicht</small>
    <h1 class="logo">Level up!</h1>
    <div class="milestone-vehicle">${sprite(v.key, 5)}</div>
    <p>Du fährst jetzt: <b>${esc(v.label)}</b></p>
    <button type="button" class="btn btn-primary">Weiter</button>`;
  document.body.append(el);
  haptic(app.state, 200);
  confetti();
  el.querySelector("button").addEventListener("click", () => el.remove());
}

// ---------------------------------------------------------------- login

function showLogin(mode = "login") {
  root.innerHTML = screens.login(app.meta, mode);
  const form = root.querySelector("#login-form");
  root.querySelector("[data-action=switch]").addEventListener("click", () =>
    showLogin(mode === "login" ? "register" : "login"));
  form.addEventListener("submit", async (e) => {
    e.preventDefault();
    const body = { ...Object.fromEntries(new FormData(form)), language: "de" };
    try {
      const { token } = await api("POST", mode === "login" ? "/auth/login" : "/auth/register", body);
      setToken(token);
      await startMain();
    } catch (err) {
      toast(err.message, "error");
    }
  });
}

async function startMain() {
  const { state } = await api("GET", "/state");
  app.state = state;
  app.shownMomentum = null;
  shell();
  TABS.forEach((t) => app.stale.add(t.id));
  const fromHash = location.hash.slice(1);
  app.tab = TABS.some((t) => t.id === fromHash) ? fromHash : "home";
  renderTab(app.tab);
}

async function boot() {
  try {
    app.meta = await api("GET", "/meta");
  } catch (e) {
    root.innerHTML = `<p class="offline">Server nicht erreichbar.<br>Läuft <code>python -m server</code>?</p>`;
    return;
  }
  if (!getToken()) return showLogin();
  try {
    await startMain();
  } catch (e) {
    if (e.status === 401) { setToken(null); showLogin(); } else handleError(e);
  }
}

// Coming back to the app (e.g. next morning): fetch state so daily decay is applied.
document.addEventListener("visibilitychange", async () => {
  if (document.visibilityState !== "visible" || !app.state) return;
  try { setState((await api("GET", "/state")).state); } catch { /* keep old state */ }
});

boot();
