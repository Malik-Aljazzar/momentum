// Screen renderers. Each returns HTML and wires its events via mount().

import { openDecayEditor, openRuleEditor } from "./editor.js";
import { esc, fmt, fmtVal, icon, segBar, signed, sprite, timeAgo } from "./ui.js";

const EVENT_LABELS = { daily: "Täglicher Verfall", reset: "Momentum zurückgesetzt" };

const MODE_HINT = {
  constant: () => "konstant",
  linear: (r) => `linear · −${fmtVal(r.param)} pro Klick`,
  exponential: (r) => `exponentiell · ${Math.round(r.param * 100)}% bleiben`,
};

function avatar(user, size = 36) {
  return user.avatar
    ? `<img class="avatar px" src="${esc(user.avatar)}" width="${size}" height="${size}" alt="">`
    : `<span class="avatar avatar-empty" style="width:${size}px;height:${size}px">${esc((user.display_name || "?")[0].toUpperCase())}</span>`;
}

function sceneLayers(category) {
  if (category === "space") return `<div class="stars s1"></div><div class="stars s2"></div><div class="planet"></div>`;
  if (category === "air") return `<div class="clouds c1"></div><div class="clouds c2"></div>`;
  return `<div class="sun"></div><div class="mountains m1"></div><div class="mountains m2"></div><div class="road"></div>`;
}

// ---------------------------------------------------------------- home

export function home(ctx) {
  const st = ctx.state, vs = ctx.meta.vehicles;
  const idx = st.vehicle.index, v = vs[idx], next = vs[idx + 1];
  const step = st.settings.milestone_step;
  const frac = next ? (st.momentum - idx * step) / step : 1;
  const d = st.decay;

  const events = st.settings.show_history && st.events.length ? `
    <section class="card list-card">
      <h3 class="card-label">Verlauf</h3>
      ${st.events.slice(0, 8).map((e) => `
        <div class="event">
          ${e.icon ? icon(e.icon, 24) : `<span class="event-dot"></span>`}
          <div class="event-main"><b>${esc(e.label || EVENT_LABELS[e.kind] || "")}</b><small>${timeAgo(e.at)}</small></div>
          <span class="delta ${e.delta >= 0 ? "pos" : "neg"}">${signed(e.delta, fmt)}</span>
        </div>`).join("")}
    </section>` : "";

  return `
    <header class="topbar">
      <div class="hello">${avatar(st.user)}<div><small>Hallo</small><b>${esc(st.user.display_name)}</b></div></div>
      <div class="chip ${d.streak ? "warn" : ""}">${icon("storm", 16)}<span>${d.streak} ${d.streak === 1 ? "Tag" : "Tage"}</span></div>
    </header>

    <section class="card hero scene-${v.category}">
      <div class="scene">${sceneLayers(v.category)}</div>
      <div class="hero-head">
        <div class="big-num" id="momentum-num" style="--len:${Math.max(4, fmt(st.momentum).length)}">${fmt(st.momentum)}</div>
        <div class="hero-label">${icon("star", 16)} ${esc(v.label)} <span>· Stufe ${idx + 1}/${vs.length}</span></div>
      </div>
      <div class="hero-vehicle">${sprite(v.key, 4)}</div>
      <div class="hero-stats">
        <div><small>Heute</small><b>${signed(d.today_gain, fmt)}</b></div>
        <div><small>Ziel heute</small><b>${fmt(d.threshold)}</b></div>
        <div><small>Heute Nacht</small><b>−${fmt(d.tonight)}</b></div>
      </div>
    </section>

    <section class="card next-card">
      ${next ? `
        <div class="next-row">
          <div><h3 class="card-label">Nächster Meilenstein</h3><b class="next-name">${esc(next.label)}</b>
          <small>noch ${fmt(st.vehicle.next_at - st.momentum)} Momentum</small></div>
          <div class="next-sprite">${sprite(next.key, 2, true)}</div>
        </div>
        ${segBar(frac, 24)}` : `<h3 class="card-label">Alle Meilensteine geschafft</h3><b class="next-name">Du bist ganz oben.</b>`}
    </section>

    <section class="card garage-card">
      <h3 class="card-label">Garage</h3>
      <div class="garage">
        ${vs.map((g, i) => `
          <div class="garage-item ${i > idx ? "locked" : ""} ${i === idx ? "current" : ""}">
            ${sprite(g.key, 2, true)}
            <small>${i > idx ? fmt(i * step) : esc(g.label)}</small>
          </div>`).join("")}
      </div>
    </section>
    ${events}`;
}

// ---------------------------------------------------------------- accelerators / decelerators

export function rules(ctx, kind) {
  const st = ctx.state, acc = kind === "acc";
  const list = st.rules.filter((r) => r.kind === kind);
  const d = st.decay;
  const dayFrac = d.threshold > 0 ? d.today_gain / d.threshold : 1;

  const decayCard = acc ? "" : `
    <button type="button" class="card decay-card" data-action="decay">
      <div class="decay-top">
        ${icon("calendar", 48)}
        <div class="decay-main">
          <h3 class="card-label">Täglicher Verfall</h3>
          <b class="decay-num">−${fmt(d.tonight)}</b>
          <small>heute Nacht · Grundwert ${fmt(d.base)}${d.streak ? ` · Strähne ${d.streak}` : ""}</small>
        </div>
        <span class="edit-dot">${icon("ui_edit", 16)}</span>
      </div>
      ${d.catalyst_mode !== "none" ? `
        <div class="decay-progress">
          <small>Heute gesammelt ${fmt(d.today_gain)} / ${fmt(d.threshold)}</small>
          ${segBar(dayFrac, 20, dayFrac >= 1 ? "good" : "")}
        </div>` : ""}
    </button>`;

  return `
    <section class="card rules-hero scene-${kind}">
      <div class="scene">${acc ? `<div class="arrows up"></div>` : `<div class="rain"></div>`}</div>
      <small class="card-label">${acc ? "Gib Gas" : "Bremsklötze"}</small>
      <h1 class="screen-title">${acc ? "Beschleuniger" : "Bremser"}</h1>
      <p class="hero-sub">${acc ? "Tippe auf etwas, das du geschafft hast." : "Ehrlich sein: tippe, wenn es passiert ist."}</p>
    </section>
    ${decayCard}
    <div class="rule-list">
      ${list.map((r) => `
        <div class="rule-card ${kind}" data-id="${r.id}" role="button" tabindex="0">
          <span class="rule-icon">${icon(r.icon, 32)}</span>
          <div class="rule-main"><b>${esc(r.name)}</b><small>${MODE_HINT[r.mode](r)}</small></div>
          <span class="rule-value">${acc ? "+" : "−"}${fmtVal(r.value)}</span>
          <button type="button" class="rule-edit" data-edit="${r.id}" aria-label="Bearbeiten">${icon("ui_edit", 16)}</button>
        </div>`).join("")}
      <button type="button" class="rule-add" data-action="add">${icon("ui_plus", 16)} ${acc ? "Neuer Beschleuniger" : "Neuer Bremser"}</button>
    </div>`;
}

export function mountRules(el, ctx, kind) {
  const byId = (id) => ctx.state.rules.find((r) => r.id === Number(id));
  el.querySelectorAll(".rule-card").forEach((card) => {
    const fire = () => ctx.trigger(byId(card.dataset.id), card);
    card.addEventListener("click", (e) => { if (!e.target.closest(".rule-edit")) fire(); });
    card.addEventListener("keydown", (e) => { if (e.key === "Enter") fire(); });
  });
  el.querySelectorAll("[data-edit]").forEach((b) =>
    b.addEventListener("click", () => openRuleEditor(ctx, byId(b.dataset.edit), kind)));
  el.querySelector("[data-action=add]").addEventListener("click", () => openRuleEditor(ctx, null, kind));
  el.querySelector("[data-action=decay]")?.addEventListener("click", () => openDecayEditor(ctx, ctx.state.decay));
}

// ---------------------------------------------------------------- settings

export function settings(ctx) {
  const st = ctx.state, s = st.settings;
  const tzOptions = ctx.meta.timezones.map((tz) =>
    `<option${tz === s.timezone ? " selected" : ""}>${esc(tz)}</option>`).join("");
  return `
    <section class="card rules-hero scene-settings">
      <div class="scene"><div class="stars s1"></div></div>
      <small class="card-label">Boxenstopp</small>
      <h1 class="screen-title">Einstellungen</h1>
    </section>

    <section class="card form-card">
      <h3 class="card-label">Profil</h3>
      <div class="profile-row">
        <label class="avatar-pick">${avatar(st.user, 64)}<input type="file" accept="image/*" id="avatar-input" hidden><span>ändern</span></label>
        <div class="field grow"><small>Anzeigename</small><input class="input" id="display-name" value="${esc(st.user.display_name)}" maxlength="40"></div>
      </div>
      <small class="muted">Angemeldet als @${esc(st.user.username)}</small>
    </section>

    <section class="card form-card">
      <h3 class="card-label">Spiel</h3>
      <label class="field"><small>Zeitzone (Tageswechsel für den Verfall)</small><select class="input" id="timezone">${tzOptions}</select></label>
      <label class="field"><small>Momentum pro Meilenstein</small><input class="input" id="milestone" inputmode="numeric" value="${s.milestone_step}"></label>
      <label class="toggle"><span>Vibration beim Tippen</span><input type="checkbox" id="haptics"${s.haptics ? " checked" : ""}><i></i></label>
      <label class="toggle"><span>Verlauf auf dem Startscreen</span><input type="checkbox" id="history"${s.show_history ? " checked" : ""}><i></i></label>
    </section>

    <section class="card form-card">
      <h3 class="card-label">Passwort ändern</h3>
      <input class="input" type="password" id="pw-old" placeholder="Altes Passwort" autocomplete="current-password">
      <input class="input" type="password" id="pw-new" placeholder="Neues Passwort" autocomplete="new-password">
      <button type="button" class="btn btn-ghost" data-action="password">Passwort speichern</button>
    </section>

    <section class="card form-card">
      <h3 class="card-label">Gefahrenzone</h3>
      <button type="button" class="btn btn-ghost" data-action="logout">Abmelden</button>
      <button type="button" class="btn btn-ghost danger" data-action="reset">Momentum auf 0 setzen</button>
      <button type="button" class="btn btn-ghost danger" data-action="delete">Konto löschen</button>
    </section>
    <p class="footnote">Momentum · Prototyp</p>`;
}

async function pixelateImage(file, size = 48) {
  const img = await createImageBitmap(file);
  const c = document.createElement("canvas");
  c.width = c.height = size;
  const g = c.getContext("2d");
  const side = Math.min(img.width, img.height);
  g.imageSmoothingEnabled = true;
  g.drawImage(img, (img.width - side) / 2, (img.height - side) / 2, side, side, 0, 0, size, size);
  return c.toDataURL("image/png");
}

export function mountSettings(el, ctx) {
  const put = (body) => ctx.save("PUT", "/settings", body, { quiet: true });
  const $ = (id) => el.querySelector(`#${id}`);

  $("avatar-input").addEventListener("change", async (e) => {
    const f = e.target.files[0];
    if (f) await put({ avatar: await pixelateImage(f) });
  });
  $("display-name").addEventListener("change", (e) => put({ display_name: e.target.value }));
  $("timezone").addEventListener("change", (e) => put({ timezone: e.target.value }));
  $("milestone").addEventListener("change", (e) => put({ milestone_step: Number(e.target.value) }));
  $("haptics").addEventListener("change", (e) => put({ haptics: e.target.checked }));
  $("history").addEventListener("change", (e) => put({ show_history: e.target.checked }));

  el.querySelector("[data-action=password]").addEventListener("click", async () => {
    const ok = await ctx.call("POST", "/settings/password", { old: $("pw-old").value, new: $("pw-new").value });
    if (ok) { $("pw-old").value = $("pw-new").value = ""; ctx.toast("Passwort geändert"); }
  });
  el.querySelector("[data-action=logout]").addEventListener("click", () => ctx.logout());

  const confirmButton = (action, label, run) => {
    const b = el.querySelector(`[data-action=${action}]`);
    b.addEventListener("click", async () => {
      if (b.dataset.armed) return run();
      b.dataset.armed = "1";
      b.textContent = label;
      setTimeout(() => { delete b.dataset.armed; b.textContent = b.dataset.orig; }, 3000);
    });
    b.dataset.orig = b.textContent;
  };
  confirmButton("reset", "Sicher? Nochmal tippen", () => ctx.save("POST", "/momentum/reset"));
  confirmButton("delete", "Alles weg! Nochmal tippen", async () => {
    if (await ctx.call("DELETE", "/account")) ctx.logout(true);
  });
}

// ---------------------------------------------------------------- login

export function login(meta, mode) {
  return `
    <div class="login scene-space">
      <div class="scene"><div class="stars s1"></div><div class="stars s2"></div><div class="planet"></div></div>
      <div class="login-inner">
        <small class="card-label">Miss dein seelisches</small>
        <h1 class="logo">Momentum</h1>
        <div class="login-vehicle">${sprite(meta.vehicles[meta.vehicles.length - 1].key, 4)}</div>
        <form class="card form-card login-card" id="login-form">
          <input class="input" name="username" placeholder="Benutzername" autocomplete="username" autocapitalize="none" required>
          <input class="input" name="password" type="password" placeholder="Passwort" autocomplete="${mode === "login" ? "current-password" : "new-password"}" required>
          <button class="btn btn-primary" type="submit">${mode === "login" ? "Los geht's" : "Konto erstellen"}</button>
          <button class="btn btn-link" type="button" data-action="switch">${mode === "login" ? "Neu hier? Konto erstellen" : "Schon dabei? Anmelden"}</button>
        </form>
      </div>
    </div>`;
}
