// Cloze-text editors ("Lückentext") for accelerators, decelerators and the
// daily decay. The sentence explains the rule; the gaps are the settings.
// Changing a dropdown rewrites the whole sentence.

import { esc, fmtVal, icon } from "./ui.js";

// ---------------------------------------------------------------- bottom sheet

export function openSheet({ theme = "", render, mount }) {
  const root = document.createElement("div");
  root.className = "sheet-backdrop";
  root.innerHTML = `<div class="sheet ${theme}" role="dialog" aria-modal="true"><div class="sheet-grip"></div><div class="sheet-body"></div></div>`;
  document.body.append(root);
  const body = root.querySelector(".sheet-body");
  const close = () => {
    root.classList.add("closing");
    setTimeout(() => root.remove(), 180);
  };
  root.addEventListener("click", (e) => { if (e.target === root) close(); });
  const rerender = () => { body.innerHTML = render(); mount?.(body, { close, rerender }); };
  rerender();
  requestAnimationFrame(() => root.classList.add("open"));
  return { close, rerender, body };
}

// ---------------------------------------------------------------- gap fields

const width = (v) => `${Math.max(2, String(v).length + 1)}ch`;

const gap = {
  text: (f, v, ph = "") =>
    `<input class="gap gap-text" data-field="${f}" value="${esc(v)}" placeholder="${esc(ph)}" maxlength="40" style="width:${width(v || ph)}">`,
  num: (f, v) =>
    `<input class="gap" data-field="${f}" inputmode="decimal" value="${esc(v)}" style="width:${width(v)}">`,
  select: (f, v, options) =>
    `<span class="gap gap-select"><select data-field="${f}">${options
      .map(([val, label]) => `<option value="${val}"${String(val) === String(v) ? " selected" : ""}>${esc(label)}</option>`)
      .join("")}</select><b>${esc(options.find(([val]) => String(val) === String(v))?.[1] ?? v)}</b></span>`,
  icon: (v) => `<button type="button" class="gap gap-icon" data-action="icons">${icon(v, 24)}</button>`,
};

const num = (s) => {
  const v = parseFloat(String(s).replace(",", "."));
  return Number.isFinite(v) ? v : 0;
};

function bindGaps(body, draft, onChange) {
  body.querySelectorAll("[data-field]").forEach((el) => {
    const field = el.dataset.field;
    if (el.tagName === "SELECT") {
      el.addEventListener("change", () => onChange(field, el.value, true));
    } else {
      el.addEventListener("input", () => {
        el.style.width = width(el.value || el.placeholder);
        onChange(field, el.value, false);
      });
      el.addEventListener("focus", () => el.select());
    }
  });
}

// ---------------------------------------------------------------- rules

const RESET_OPTIONS = [[0, "nie"], [1, "1 Tag"], [2, "2 Tage"], [3, "3 Tage"], [5, "5 Tage"],
  [7, "7 Tage"], [10, "10 Tage"], [14, "14 Tage"], [30, "30 Tage"]];

function ruleSentence(d) {
  const acc = d.kind === "acc";
  const modes = [["constant", "konstant"], ["linear", "linear sinkend"],
    ["exponential", "exponentiell sinkend"]];
  const name = gap.text("name", d.name, acc ? "Sport machen" : "Doomscrolling");
  const mode = gap.select("mode", d.mode, modes);
  const resetOpts = RESET_OPTIONS.some(([v]) => v === d.reset_days)
    ? RESET_OPTIONS : [...RESET_OPTIONS, [d.reset_days, `${d.reset_days} Tage`]];
  const reset = gap.select("reset_days", d.reset_days, resetOpts);
  const what = acc ? "bekomme ich" : "verliere ich";

  let s = `${gap.icon(d.icon)} Für ${name} ${what} `;
  if (d.mode === "constant") {
    s += `jedes Mal ${gap.num("base", d.base)} Momentum. `;
    s += acc
      ? `Das Wachstum ist ${mode} — jeder Klick zählt gleich viel.`
      : `Die Strafe ist ${mode} — jedes Mal gleich hart.`;
    return s;
  }
  s += `beim ersten Mal ${gap.num("base", d.base)} Momentum. `;
  if (d.mode === "linear") {
    s += acc
      ? `Das Wachstum ist ${mode}: mit jedem weiteren Klick gibt es ${gap.num("param", d.param)} weniger, aber nie weniger als ${gap.num("min_value", d.min_value)}. `
      : `Die Strafe ist ${mode}: jedes weitere Mal kostet ${gap.num("param", d.param)} weniger, aber immer mindestens ${gap.num("min_value", d.min_value)}. `;
  } else {
    s += acc
      ? `Das Wachstum ist ${mode}: bei jedem Klick bleiben nur ${gap.num("pct", d.pct)}% davon übrig — erst geht es schnell runter, dann immer langsamer, aber nie unter ${gap.num("min_value", d.min_value)}. `
      : `Die Strafe ist ${mode}: jedes Mal bleiben nur ${gap.num("pct", d.pct)}% davon übrig — erst sinkt sie schnell, dann immer langsamer, aber nie unter ${gap.num("min_value", d.min_value)}. `;
  }
  s += d.reset_days === 0
    ? `Zurückgesetzt wird ${reset} — einmal verbraucht, bleibt es so.`
    : acc
      ? `Nach ${reset} ohne Klick starte ich wieder bei voller Stärke.`
      : `Nach ${reset} ohne Rückfall ist die Strafe wieder voll.`;
  return s;
}

function rulePreview(d) {
  const sign = d.kind === "acc" ? "+" : "−";
  const vals = [];
  let v = d.base;
  for (let i = 0; i < 6; i++) {
    vals.push(v);
    if (d.mode === "linear") v = Math.max(d.min_value, v - d.param);
    else if (d.mode === "exponential") v = Math.max(d.min_value, v * d.pct / 100);
  }
  return vals.map((x) => `<span>${sign}${fmtVal(x)}</span>`).join("<em>›</em>");
}

function ruleDefaults(d, mode) {
  if (mode === "linear" && !d.param) d.param = Math.round(d.base / 4) || 1;
  if (mode !== "constant" && !d.min_value) d.min_value = Math.round(d.base / 5) || 1;
  if (mode !== "constant" && d.reset_days === 0 && d._fresh) d.reset_days = 3;
}

/**
 * @param ctx  { meta, save(method, path, body) -> Promise }
 * @param rule existing rule or null
 */
export function openRuleEditor(ctx, rule, kind) {
  const d = rule
    ? { ...rule, pct: rule.mode === "exponential" ? Math.round(rule.param * 100) : 70 }
    : { kind, name: "", icon: kind === "acc" ? "star" : "storm", mode: "constant", base: 50,
        param: 0, min_value: 0, reset_days: 0, pct: 70, _fresh: true };
  let showIcons = false;
  let confirmDelete = false;
  const acc = d.kind === "acc";

  openSheet({
    theme: acc ? "theme-acc" : "theme-dec",
    render: () => `
      <h2 class="sheet-title">${rule ? "Bearbeiten" : acc ? "Neuer Beschleuniger" : "Neuer Bremser"}</h2>
      <p class="cloze">${ruleSentence(d)}</p>
      ${showIcons ? `<div class="icon-grid">${ctx.meta.icons.map((n) =>
        `<button type="button" data-icon="${n}" class="${n === d.icon ? "sel" : ""}">${icon(n, 32)}</button>`).join("")}</div>` : ""}
      <div class="preview"><small>So entwickelt es sich pro Klick</small><div class="preview-row">${rulePreview(d)}</div></div>
      <div class="sheet-actions">
        ${rule ? `<button type="button" class="btn btn-ghost danger" data-action="delete">${confirmDelete ? "Wirklich löschen?" : "Löschen"}</button>` : ""}
        <button type="button" class="btn btn-primary" data-action="save">Speichern</button>
      </div>`,
    mount: (body, sheet) => {
      const refreshPreview = () => {
        body.querySelector(".preview-row").innerHTML = rulePreview(d);
      };
      bindGaps(body, d, (field, value, structural) => {
        if (field === "name") d.name = value;
        else if (field === "mode") { ruleDefaults(d, value); d.mode = value; }
        else d[field] = num(value);
        if (structural) sheet.rerender(); else refreshPreview();
      });
      body.querySelector("[data-action=icons]").addEventListener("click", () => {
        showIcons = !showIcons;
        sheet.rerender();
      });
      body.querySelectorAll("[data-icon]").forEach((b) => b.addEventListener("click", () => {
        d.icon = b.dataset.icon;
        showIcons = false;
        sheet.rerender();
      }));
      body.querySelector("[data-action=save]").addEventListener("click", async () => {
        const payload = {
          kind: d.kind, name: d.name.trim(), icon: d.icon, mode: d.mode, base: d.base,
          param: d.mode === "exponential" ? d.pct / 100 : d.param,
          min_value: d.min_value, reset_days: d.reset_days,
        };
        if (!payload.name) {
          body.querySelector("[data-field=name]").focus();
          return;
        }
        const ok = await ctx.save(rule ? "PUT" : "POST", rule ? `/rules/${rule.id}` : "/rules", payload);
        if (ok) sheet.close();
      });
      body.querySelector("[data-action=delete]")?.addEventListener("click", async () => {
        if (!confirmDelete) { confirmDelete = true; sheet.rerender(); return; }
        if (await ctx.save("DELETE", `/rules/${rule.id}`)) sheet.close();
      });
    },
  });
}

// ---------------------------------------------------------------- daily decay

export function catalystValue(mode, a, b, streak) {
  if (streak <= 0 || mode === "none") return 0;
  if (mode === "linear") return a * streak;
  if (mode === "exponential") return a * b ** (streak - 1);
  return a * (1 - b ** streak);
}

function decaySentence(d) {
  const mode = gap.select("catalyst_mode", d.catalyst_mode, [
    ["none", "gar nicht"], ["linear", "linear"], ["exponential", "exponentiell"],
    ["inverse", "umgekehrt exponentiell"]]);
  let s = `${icon("calendar", 24, "inline")} Jeden Tag verliere ich ${gap.num("base", d.base)} Momentum. `;
  if (d.catalyst_mode === "none") {
    return s + `Lasse ich es schleifen, wächst der Verfall ${mode} — es bleibt beim Grundwert.`;
  }
  s += `Sammle ich an einem Tag weniger als ${gap.num("threshold", d.threshold)} Momentum, wächst meine Durststrecke — und mit ihr ein Katalysator, der ${mode} zunimmt: `;
  if (d.catalyst_mode === "linear") {
    s += `pro Tag in Folge kommen ${gap.num("catalyst_a", d.catalyst_a)} Verfall dazu.`;
  } else if (d.catalyst_mode === "exponential") {
    s += `am ersten Tag ${gap.num("catalyst_a", d.catalyst_a)} extra, danach jeden Tag ×${gap.num("catalyst_b", d.catalyst_b)}. Das eskaliert schnell!`;
  } else {
    s += `erst schnell, dann immer langsamer, bis höchstens ${gap.num("catalyst_a", d.catalyst_a)} extra. Jeden Tag schrumpft der Abstand zum Maximum auf ${gap.num("pct", d.pct)}%.`;
  }
  return s + ` Ein guter Tag setzt die Strähne zurück.`;
}

function decayPreview(d) {
  const b = d.catalyst_mode === "inverse" ? d.pct / 100 : d.catalyst_b;
  const out = [];
  for (let n = 1; n <= 5; n++) {
    out.push(`<span><small>Tag ${n}</small>−${fmtVal(d.base + catalystValue(d.catalyst_mode, d.catalyst_a, b, n))}</span>`);
  }
  return out.join("<em>›</em>");
}

export function openDecayEditor(ctx, decay) {
  const d = { ...decay, pct: decay.catalyst_mode === "inverse" ? Math.round(decay.catalyst_b * 100) : 70 };
  openSheet({
    theme: "theme-dec",
    render: () => `
      <h2 class="sheet-title">Täglicher Verfall</h2>
      <p class="cloze">${decaySentence(d)}</p>
      <div class="preview"><small>Verfall pro Nacht bei einer Durststrecke</small><div class="preview-row">${decayPreview(d)}</div></div>
      <div class="sheet-actions"><button type="button" class="btn btn-primary" data-action="save">Speichern</button></div>`,
    mount: (body, sheet) => {
      bindGaps(body, d, (field, value, structural) => {
        if (field === "catalyst_mode") {
          if (value === "exponential" && !(d.catalyst_b >= 1)) d.catalyst_b = 1.5;
          if (value !== "none" && !d.catalyst_a) d.catalyst_a = 25;
          d.catalyst_mode = value;
        } else d[field] = num(value);
        if (structural) sheet.rerender();
        else body.querySelector(".preview-row").innerHTML = decayPreview(d);
      });
      body.querySelector("[data-action=save]").addEventListener("click", async () => {
        const payload = {
          base: d.base, threshold: d.threshold, catalyst_mode: d.catalyst_mode,
          catalyst_a: d.catalyst_a,
          catalyst_b: d.catalyst_mode === "inverse" ? d.pct / 100 : d.catalyst_b,
        };
        if (await ctx.save("PUT", "/decay", payload)) sheet.close();
      });
    },
  });
}
