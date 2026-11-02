// Small rendering helpers shared by all screens.

export const esc = (s) => String(s ?? "").replace(/[&<>"']/g, (c) => (
  { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));

const intFmt = new Intl.NumberFormat("de-DE", { maximumFractionDigits: 0 });
const decFmt = new Intl.NumberFormat("de-DE", { maximumFractionDigits: 1 });

/** Momentum totals: whole numbers. */
export const fmt = (n) => intFmt.format(Math.round(n));
/** Rule values: up to one decimal (exponential rules produce fractions). */
export const fmtVal = (n) => decFmt.format(n);
export const signed = (n, f = fmtVal) => (n >= 0 ? "+" : "−") + f(Math.abs(n));

export function sprite(key, scale = 4, still = false) {
  return `<div class="sprite${still ? " still" : ""}" style="--s:${scale};background-image:url(/sprites/${key}/sheet.png)"></div>`;
}

export function icon(name, size = 32, cls = "") {
  return `<img class="px ${cls}" src="/sprites/icons/${esc(name)}.png" width="${size}" height="${size}" alt="">`;
}

export function haptic(state, ms = 12) {
  if (state?.settings?.haptics && navigator.vibrate) navigator.vibrate(ms);
}

let toastTimer;
export function toast(msg, kind = "") {
  let el = document.getElementById("toast");
  if (!el) {
    el = document.createElement("div");
    el.id = "toast";
    document.body.append(el);
  }
  el.className = `toast show ${kind}`;
  el.textContent = msg;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => el.classList.remove("show"), 2600);
}

/** "+400" that floats up from an element. */
export function floatText(fromEl, text, kind) {
  const r = fromEl.getBoundingClientRect();
  const el = document.createElement("div");
  el.className = `float-text ${kind}`;
  el.textContent = text;
  el.style.left = `${r.left + r.width / 2}px`;
  el.style.top = `${r.top}px`;
  document.body.append(el);
  el.addEventListener("animationend", () => el.remove());
}

/** Blocky segmented progress bar. */
export function segBar(fraction, segments = 20, cls = "") {
  const on = Math.max(0, Math.min(segments, Math.floor(fraction * segments)));
  let s = "";
  for (let i = 0; i < segments; i++) s += `<i class="${i < on ? "on" : ""}"></i>`;
  return `<div class="segbar ${cls}">${s}</div>`;
}

export function timeAgo(iso) {
  const sec = (Date.now() - new Date(iso).getTime()) / 1000;
  if (sec < 60) return "gerade eben";
  if (sec < 3600) return `vor ${Math.floor(sec / 60)} min`;
  if (sec < 86400) return `vor ${Math.floor(sec / 3600)} h`;
  const d = Math.floor(sec / 86400);
  return d === 1 ? "gestern" : `vor ${d} Tagen`;
}

/** Pixel confetti burst, used for milestones. */
export function confetti(n = 60) {
  const colors = ["#ffcd75", "#ef7d57", "#a7f070", "#73eff7", "#f4f4f4", "#b13e53", "#41a6f6"];
  for (let i = 0; i < n; i++) {
    const p = document.createElement("i");
    p.className = "confetti";
    p.style.left = `${Math.random() * 100}vw`;
    p.style.background = colors[i % colors.length];
    p.style.animationDelay = `${Math.random() * 0.6}s`;
    p.style.animationDuration = `${1.6 + Math.random() * 1.4}s`;
    p.style.setProperty("--dx", `${(Math.random() - 0.5) * 120}px`);
    document.body.append(p);
    p.addEventListener("animationend", () => p.remove());
  }
}
