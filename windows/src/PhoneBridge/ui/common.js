"use strict";
// Shared by every PhoneBridge page: talking to the app, small helpers, the icon set, and the Midnight drawings
// (MotifArt.arch, the hero scene, OrbView) ported from the Mac app.
const host = window.chrome && window.chrome.webview;
const $ = (s, root) => (root || document).querySelector(s);
const $$ = (s, root) => Array.from((root || document).querySelectorAll(s));
function send(cmd, extra) { if (host) host.postMessage(Object.assign({ cmd }, extra || {})); }
function esc(s) { return String(s == null ? "" : s).replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c])); }
function icon(name, cls) { return `<svg class="ico${cls ? " " + cls : ""}"><use href="#i-${name}"/></svg>`; }
/** Listens for the app's messages ({type, …}). */
function onHost(fn) { if (host) host.addEventListener("message", (e) => fn(typeof e.data === "string" ? JSON.parse(e.data) : e.data)); }

// Round avatar with initials on a colour picked from the name (PhoneDesign.swift Avatar).
const AVATAR = [["#6E5CFA", "#9E80FF"], ["#ED5CB3", "#FF8C80"], ["#1FB39E", "#4DD98C"], ["#FA9933", "#FFC74D"], ["#338CF2", "#59C7FF"], ["#8C66D9", "#D973D9"]];
function avatar(name, size) {
  size = size || 44;
  let seed = 0;
  for (const ch of String(name || "")) seed = (seed * 31 + ch.codePointAt(0)) & 0xFFFF;
  const [a, b] = AVATAR[seed % AVATAR.length];
  const words = String(name || "").split(/\s+/).filter(Boolean).slice(0, 2);
  let initials = words.map((w) => Array.from(w)[0]).join("").toUpperCase();
  if (!/^\p{L}/u.test(initials)) initials = "";
  const inner = initials ? `<span style="font:600 ${size * 0.38}px/1 var(--font-rounded)">${esc(initials)}</span>` : icon("person");
  return `<div class="avatar" style="width:${size}px;height:${size}px;background:linear-gradient(135deg,${a},${b});font-size:${size * 0.42}px">${inner}</div>`;
}

document.addEventListener("DOMContentLoaded", () => {
  document.body.insertAdjacentHTML("afterbegin", `<svg width="0" height="0" style="position:absolute" aria-hidden="true">
  <defs>
    <symbol id="i-share" viewBox="0 0 24 24"><path d="M12 3v12M8 7l4-4 4 4M7 10H6a2 2 0 0 0-2 2v7a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-7a2 2 0 0 0-2-2h-1"/></symbol>
    <symbol id="i-bell-waves" viewBox="0 0 24 24"><path d="M17 9.5a5 5 0 0 0-10 0c0 5.5-2 7-2 7h14s-2-1.5-2-7M10.3 19.5a2 2 0 0 0 3.4 0M2.8 8.5a9 9 0 0 1 2.3-4.2M21.2 8.5a9 9 0 0 0-2.3-4.2"/></symbol>
    <symbol id="i-bell" viewBox="0 0 24 24"><path d="M18 9.5a6 6 0 0 0-12 0c0 6-2.5 7.5-2.5 7.5h17S18 15.5 18 9.5M10.3 20.5a2 2 0 0 0 3.4 0"/></symbol>
    <symbol id="i-bell-off" viewBox="0 0 24 24"><path d="M8.5 4.6A6 6 0 0 1 18 9.5c0 2.6.5 4.4 1 5.5M6 9.5c0 6-2.5 7.5-2.5 7.5H17M10.3 20.5a2 2 0 0 0 3.4 0M3 3l18 18"/></symbol>
    <symbol id="i-clipboard" viewBox="0 0 24 24"><path d="M9 4.5H7a2 2 0 0 0-2 2V19a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V6.5a2 2 0 0 0-2-2h-2"/><rect x="9" y="3" width="6" height="3.2" rx="1.2"/><path d="M12 10v7M9 14l3 3 3-3"/></symbol>
    <symbol id="i-tray" viewBox="0 0 24 24"><path d="M12 3v11M8 10l4 4 4-4M4 15v3a3 3 0 0 0 3 3h10a3 3 0 0 0 3-3v-3"/></symbol>
    <symbol id="i-refresh" viewBox="0 0 24 24"><path d="M20 11.5A8 8 0 0 0 6 6.6L4 8.5M4 4v4.5h4.5M4 12.5A8 8 0 0 0 18 17.4l2-1.9M20 20v-4.5h-4.5"/></symbol>
    <symbol id="i-sliders" viewBox="0 0 24 24"><path d="M4 7h9M17 7h3M4 17h3M11 17h9"/><circle cx="15" cy="7" r="2"/><circle cx="9" cy="17" r="2"/></symbol>
    <symbol id="i-chevron" viewBox="0 0 24 24"><path d="M14.5 18l-6-6 6-6"/></symbol>
    <symbol id="i-phone" viewBox="0 0 24 24"><rect x="7" y="2.5" width="10" height="19" rx="2.6"/><path d="M11 18.5h2"/></symbol>
    <symbol id="i-phone-slash" viewBox="0 0 24 24"><path d="M17 13V5.1A2.6 2.6 0 0 0 14.4 2.5H9.6c-.9 0-1.6.4-2.1 1M7 7v11.9a2.6 2.6 0 0 0 2.6 2.6h4.8c1 0 1.8-.5 2.3-1.3M3 3l18 18"/></symbol>
    <symbol id="i-laptop" viewBox="0 0 24 24"><rect x="4.5" y="5" width="15" height="10" rx="1.5"/><path d="M2.5 19h19"/></symbol>
    <symbol id="i-info" viewBox="0 0 24 24"><circle cx="12" cy="12" r="9"/><path d="M12 11v5.5M12 7.6v.1"/></symbol>
    <symbol id="i-power" viewBox="0 0 24 24"><path d="M12 3v8M6.4 6.6a8 8 0 1 0 11.2 0"/></symbol>
    <symbol id="i-person" viewBox="0 0 24 24"><circle cx="12" cy="8.5" r="3.8"/><path d="M4.5 20.5a7.5 7.5 0 0 1 15 0"/></symbol>
    <symbol id="i-call" viewBox="0 0 24 24"><path fill="currentColor" stroke="none" d="M6.6 3.2c.6-.2 1.3 0 1.6.6l1.6 3.2c.3.6.2 1.3-.3 1.7l-1.4 1.2a11.5 11.5 0 0 0 6 6l1.2-1.4c.4-.5 1.1-.6 1.7-.3l3.2 1.6c.6.3.8 1 .6 1.6l-.7 2.1c-.3.9-1.2 1.5-2.2 1.4C10.5 20.4 3.6 13.5 3 6.1c-.1-1 .5-1.9 1.4-2.2z"/></symbol>
    <symbol id="i-call-end" viewBox="0 0 24 24"><path fill="currentColor" stroke="none" d="M12 9.2c-3.3 0-6.4.9-8.6 2.6-.7.5-.9 1.4-.6 2.2l.8 1.9c.3.7 1 1.1 1.8 1l2.7-.4c.7-.1 1.2-.6 1.3-1.3l.2-1.6c1.6-.5 3.2-.5 4.8 0l.2 1.6c.1.7.6 1.2 1.3 1.3l2.7.4c.8.1 1.5-.3 1.8-1l.8-1.9c.3-.8.1-1.7-.6-2.2-2.2-1.7-5.3-2.6-8.6-2.6z"/></symbol>
    <symbol id="i-call-in" viewBox="0 0 24 24"><path d="M19 5L13 11M13 6v5h5M7.6 4.2c.5-.2 1.1 0 1.3.5l1.3 2.6c.2.5.1 1-.3 1.4l-1.1 1a9.4 9.4 0 0 0 4.9 4.9l1-1.1c.4-.4.9-.5 1.4-.3l2.6 1.3c.5.2.7.8.5 1.3l-.6 1.7c-.2.7-1 1.2-1.8 1.1C10.7 19.7 5.3 14.3 4.8 8.5c-.1-.8.4-1.6 1.1-1.8z"/></symbol>
    <symbol id="i-call-out" viewBox="0 0 24 24"><path d="M13 11l6-6M14 5h5v5M7.6 4.2c.5-.2 1.1 0 1.3.5l1.3 2.6c.2.5.1 1-.3 1.4l-1.1 1a9.4 9.4 0 0 0 4.9 4.9l1-1.1c.4-.4.9-.5 1.4-.3l2.6 1.3c.5.2.7.8.5 1.3l-.6 1.7c-.2.7-1 1.2-1.8 1.1C10.7 19.7 5.3 14.3 4.8 8.5c-.1-.8.4-1.6 1.1-1.8z"/></symbol>
    <symbol id="i-mic-off" viewBox="0 0 24 24"><path d="M15 10V6a3 3 0 0 0-5.6-1.5M9 9v2a3 3 0 0 0 4.6 2.5M18.5 11a6.5 6.5 0 0 1-1 3.4M5.5 11a6.5 6.5 0 0 0 10.3 5.3M12 17.5V21M3 3l18 18"/></symbol>
    <symbol id="i-speaker" viewBox="0 0 24 24"><path d="M4 9.5h3.5L12 5.5v13l-4.5-4H4z"/><path d="M15.5 9a4 4 0 0 1 0 6M18 6.5a7.5 7.5 0 0 1 0 11"/></symbol>
    <symbol id="i-pause" viewBox="0 0 24 24"><path d="M9 6v12M15 6v12"/></symbol>
    <symbol id="i-keyboard" viewBox="0 0 24 24"><rect x="2.5" y="6" width="19" height="12" rx="2.5"/><path d="M6 10h.01M10 10h.01M14 10h.01M18 10h.01M7 14h10"/></symbol>
    <symbol id="i-compass" viewBox="0 0 24 24"><circle cx="12" cy="12" r="9"/><path d="M15.5 8.5l-2 5-5 2 2-5z"/></symbol>
    <symbol id="i-message" viewBox="0 0 24 24"><path d="M12 4c4.7 0 8.5 3.1 8.5 7s-3.8 7-8.5 7c-1 0-2-.1-2.9-.4L5 19.5l1.2-3.4C4.6 14.8 3.5 13 3.5 11c0-3.9 3.8-7 8.5-7z"/></symbol>
    <symbol id="i-photo" viewBox="0 0 24 24"><rect x="3" y="4.5" width="18" height="15" rx="3"/><circle cx="8.5" cy="9.5" r="1.6"/><path d="M21 15.5l-5-4.5-9 8.5"/></symbol>
    <symbol id="i-clock" viewBox="0 0 24 24"><circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/></symbol>
    <symbol id="i-contacts" viewBox="0 0 24 24"><rect x="4.5" y="3" width="15" height="18" rx="2.5"/><circle cx="12" cy="10" r="2.6"/><path d="M8 17a4 4 0 0 1 8 0M2.5 7h2M2.5 12h2M2.5 17h2"/></symbol>
    <symbol id="i-send" viewBox="0 0 24 24"><path d="M12 19V5M6 11l6-6 6 6"/></symbol>
    <symbol id="i-search" viewBox="0 0 24 24"><circle cx="11" cy="11" r="6.5"/><path d="M20 20l-4.4-4.4"/></symbol>
    <symbol id="i-trash" viewBox="0 0 24 24"><path d="M4 7h16M10 11v6M14 11v6M6 7l1 12a2 2 0 0 0 2 2h6a2 2 0 0 0 2-2l1-12M9 7V4.5h6V7"/></symbol>
    <symbol id="i-download" viewBox="0 0 24 24"><circle cx="12" cy="12" r="9"/><path d="M12 7.5v8M8.5 12l3.5 3.5 3.5-3.5"/></symbol>
    <symbol id="i-check" viewBox="0 0 24 24"><path d="M5 12.5l4.5 4.5L19 7.5"/></symbol>
    <symbol id="i-close" viewBox="0 0 24 24"><path d="M6 6l12 12M18 6L6 18"/></symbol>
    <symbol id="i-play" viewBox="0 0 24 24"><path fill="currentColor" stroke="none" d="M8 5.5v13a1 1 0 0 0 1.5.9l10-6.5a1 1 0 0 0 0-1.8l-10-6.5A1 1 0 0 0 8 5.5z"/></symbol>
    <symbol id="i-torch" viewBox="0 0 24 24"><path d="M8 3h8v4l-2 3v10a1 1 0 0 1-1 1h-2a1 1 0 0 1-1-1V10L8 7z"/><path d="M12 13v2"/></symbol>
    <symbol id="i-moon" viewBox="0 0 24 24"><path d="M19.5 14.5A8 8 0 0 1 9.5 4.5a8 8 0 1 0 10 10z"/></symbol>
    <symbol id="i-pin" viewBox="0 0 24 24"><path d="M12 21s-6.5-6-6.5-11a6.5 6.5 0 0 1 13 0c0 5-6.5 11-6.5 11z"/><circle cx="12" cy="10" r="2.3"/></symbol>
    <symbol id="i-folder" viewBox="0 0 24 24"><path d="M3.5 7.5a2 2 0 0 1 2-2h4l2 2.5h7a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2h-13a2 2 0 0 1-2-2z"/></symbol>
    <symbol id="i-reply" viewBox="0 0 24 24"><path d="M10 6L4 12l6 6M4.5 12H14a6 6 0 0 1 6 6"/></symbol>
    <symbol id="i-history" viewBox="0 0 24 24"><path d="M3.5 12a8.5 8.5 0 1 0 2.5-6L3.5 8.5M3.5 4v4.5H8M12 8v4.5l3 1.8"/></symbol>
    <symbol id="i-note" viewBox="0 0 24 24"><rect x="4.5" y="3.5" width="15" height="17" rx="2.5"/><path d="M8.5 8.5h7M8.5 12h7M8.5 15.5h4"/></symbol>
    <symbol id="i-palette" viewBox="0 0 24 24"><path d="M12 3.5a8.5 8.5 0 0 0 0 17c1.2 0 1.8-.8 1.8-1.7 0-1.1-.9-1.4-.9-2.4 0-.8.6-1.4 1.5-1.4h2.1a4 4 0 0 0 4-4c0-4.2-3.8-7.5-8.5-7.5z"/><circle cx="7.8" cy="11" r="1"/><circle cx="10.5" cy="7.5" r="1"/><circle cx="14.8" cy="7.8" r="1"/></symbol>
    <symbol id="i-mic" viewBox="0 0 24 24"><rect x="9" y="3" width="6" height="11" rx="3"/><path d="M5.5 11a6.5 6.5 0 0 0 13 0M12 17.5V21"/></symbol>
    <symbol id="i-screen" viewBox="0 0 24 24"><rect x="7" y="2.5" width="10" height="19" rx="2.6"/><path d="M10 6h4"/><path d="M2.5 9v6M21.5 9v6"/></symbol>
    <symbol id="i-bluetooth" viewBox="0 0 24 24"><path d="M7 7.5l10 9-5 4.5V3l5 4.5-10 9"/></symbol>
  </defs>
</svg>`);
});
document.addEventListener("contextmenu", (e) => e.preventDefault());

// ---------- Drawing (ported from MotifArt.arch, the Midnight hero scene, and OrbView) ----------
const hash = (x) => { const v = Math.sin(x * 12.9898) * 43758.5453; return v - Math.floor(v); };

function arch(ctx, cx, cy, w, color, line) {
  ctx.strokeStyle = color; ctx.fillStyle = color; ctx.lineWidth = line; ctx.lineCap = "round";
  ctx.beginPath(); ctx.moveTo(cx - w / 2, cy + w * 0.3); ctx.quadraticCurveTo(cx, cy - w * 0.7, cx + w / 2, cy + w * 0.3); ctx.stroke();
  for (const sx of [-w / 2, w / 2]) { ctx.beginPath(); ctx.arc(cx + sx, cy + w * 0.3, line * 1.25, 0, Math.PI * 2); ctx.fill(); }
}
function glyph(ctx, size, color) {
  const k = size / 28, c = size / 2;
  arch(ctx, c, c - 1.8 * k, 18 * k, color, 2.4 * k);
}
function fitCanvas(cv) {
  const r = cv.getBoundingClientRect(), d = window.devicePixelRatio || 1;
  const w = Math.max(1, Math.round(r.width * d)), h = Math.max(1, Math.round(r.height * d));
  if (cv.width !== w || cv.height !== h) { cv.width = w; cv.height = h; }
  const ctx = cv.getContext("2d"); ctx.setTransform(d, 0, 0, d, 0, 0);
  return [ctx, r.width, r.height];
}
function circle(ctx, x, y, r) { ctx.beginPath(); ctx.arc(x, y, r, 0, Math.PI * 2); }

function drawHero(t) {
  const [ctx, w, h] = fitCanvas($("#hero"));
  if (typeof heroScene === "function") heroScene(ctx, w, h, t); else drawHeroArch(ctx, w, h, t);
}

/** Midnight's banner: night sky with slow light, a bridge between a phone and a computer, packets travelling over it. */
function drawHeroArch(ctx, w, h, t) {
  const bg = typeof currentTheme !== "undefined" ? currentTheme.bg : "#0B0B0D";
  ctx.fillStyle = bg; ctx.fillRect(0, 0, w, h);
  const glow = (x, y, r, a, white) => {
    const g = ctx.createRadialGradient(x, y, 0, x, y, r);
    g.addColorStop(0, `rgba(${white},${white},${white},${a})`); g.addColorStop(1, "rgba(0,0,0,0)");
    ctx.fillStyle = g; ctx.fillRect(0, 0, w, h);
  };
  glow(w * (0.3 + 0.12 * Math.sin(t * 0.2)), h * 0.35, w * 0.45, 0.08, 255);
  glow(w * (0.72 + 0.1 * Math.cos(t * 0.17)), h * 0.6, w * 0.4, 0.06, 179);
  for (let i = 0; i < 46; i++) {
    const tw = 0.3 + 0.7 * Math.abs(Math.sin(t * 0.8 + hash(i + 200) * 6));
    const r = 0.5 + hash(i + 300) * 0.9;
    ctx.fillStyle = `rgba(255,255,255,${0.18 * tw})`; circle(ctx, hash(i) * w, hash(i + 100) * h * 0.75, r); ctx.fill();
  }
  const L = [w * 0.16, h * 0.5], R = [w * 0.84, h * 0.5], C = [w * 0.5, -h * 0.4];
  ctx.strokeStyle = "rgba(255,255,255,0.26)"; ctx.lineWidth = 1.4; ctx.lineCap = "round";
  ctx.beginPath(); ctx.moveTo(...L); ctx.quadraticCurveTo(...C, ...R); ctx.stroke();
  const q = (s) => { const a = 1 - s, b = s; return [a * a * L[0] + 2 * a * b * C[0] + b * b * R[0], a * a * L[1] + 2 * a * b * C[1] + b * b * R[1]]; };
  for (let k = 0; k < 6; k++) {
    const dir = k % 2 === 0 ? 1 : -1;
    const ph = (t * 0.2 + k / 6) % 1;
    const edge = Math.sin(Math.PI * ph);
    for (let tr = 0; tr < 9; tr++) {
      const s = Math.min(1, Math.max(0, (dir > 0 ? ph : 1 - ph) - dir * tr * 0.013));
      const [x, y] = q(s);
      ctx.fillStyle = `rgba(255,255,255,${(1 - tr / 9) * 0.85 * edge})`; circle(ctx, x, y, Math.max(0.6, 2.8 - 0.28 * tr)); ctx.fill();
    }
  }
  [L, R].forEach(([x, y], i) => {
    const frac = (t * 0.5 + i * 0.5) % 1, rr = 13 + 12 * frac;
    ctx.lineWidth = 1; ctx.strokeStyle = `rgba(255,255,255,${(1 - frac) * 0.3})`; circle(ctx, x, y, rr); ctx.stroke();
    ctx.fillStyle = bg; circle(ctx, x, y, 13); ctx.fill();
    ctx.strokeStyle = "rgba(255,255,255,0.55)"; circle(ctx, x, y, 13); ctx.stroke();
    ctx.strokeStyle = "rgba(255,255,255,0.9)"; ctx.lineWidth = 1.2; ctx.lineJoin = "round";
    ctx.beginPath();
    if (i === 0) ctx.roundRect(x - 3.5, y - 6.5, 7, 13, 1.8);
    else { ctx.rect(x - 6, y - 5, 12, 7.5); ctx.moveTo(x - 8, y + 5); ctx.lineTo(x + 8, y + 5); }
    ctx.stroke();
  });
}

function drawOrb(t) {
  const [ctx, w, h] = fitCanvas($("#orb"));
  ctx.clearRect(0, 0, w, h);
  const cx = w / 2, cy = h / 2, R = Math.min(w, h) * 0.25;
  const blob = (scale, phase, amp) => {
    ctx.beginPath();
    for (let i = 0; i <= 96; i++) {
      const a = (i / 96) * Math.PI * 2;
      const wob = Math.sin(3 * a + phase) * 0.6 + Math.sin(5 * a - phase * 0.7) * 0.4 + Math.sin(2 * a + phase * 0.5) * 0.5;
      const r = R * scale * (1 + amp * wob);
      const x = cx + Math.cos(a) * r, y = cy + Math.sin(a) * r;
      i === 0 ? ctx.moveTo(x, y) : ctx.lineTo(x, y);
    }
    ctx.closePath();
  };
  ctx.lineWidth = 1;
  blob(1.62, t * 0.3, 0.05); ctx.strokeStyle = "rgba(255,255,255,0.13)"; ctx.stroke();
  blob(1.42, -t * 0.26 + 1.3, 0.06); ctx.strokeStyle = "rgba(255,255,255,0.2)"; ctx.stroke();
  const pulse = (t / 2.6) % 1;
  const accent = typeof currentTheme !== "undefined" ? currentTheme.accent : "#F5F5F7";
  blob(1 + 0.55 * pulse, t * 0.7, 0.07); ctx.lineWidth = 1.2;
  ctx.strokeStyle = typeof rgba === "function" ? rgba(accent, (1 - pulse) * 0.32) : `rgba(245,245,247,${(1 - pulse) * 0.32})`; ctx.stroke();
  blob(1.0, t * 0.7, 0.07);
  const g = ctx.createRadialGradient(cx + R * 0.38, cy - R * 0.3, 0, cx + R * 0.38, cy - R * 0.3, R * 1.35);
  g.addColorStop(0, "rgb(87,87,87)"); g.addColorStop(0.5, "rgb(31,31,31)"); g.addColorStop(1, "rgb(18,18,18)");
  ctx.fillStyle = g; ctx.fill(); ctx.lineWidth = 1; ctx.strokeStyle = "rgba(255,255,255,0.09)"; ctx.stroke();
  ctx.beginPath(); ctx.arc(cx, cy, R * 0.72, (120 * Math.PI) / 180, (210 * Math.PI) / 180); ctx.lineWidth = 1.2; ctx.strokeStyle = "rgba(255,255,255,0.1)"; ctx.stroke();
  const themed = typeof orbExtras === "function" ? orbExtras(ctx, cx, cy, R, t) : false;
  for (let i = 0; i < (themed ? 0 : 16); i++) {
    const ring = i % 2 === 0 ? 1.62 : 1.42, dir = i % 3 === 0 ? -1 : 1;
    const a = i * 0.83 + t * (0.1 + 0.03 * (i % 4)) * dir;
    const r = R * ring * (1 + 0.05 * Math.sin(3 * a));
    const x = cx + Math.cos(a) * r, y = cy + Math.sin(a) * r;
    const alpha = Math.max(0.08, 0.3 + 0.3 * Math.sin(t * 0.9 + i)), s = 1.6 + (i % 3);
    ctx.fillStyle = `rgba(255,255,255,${alpha})`;
    ctx.save(); ctx.translate(x, y);
    if (i % 3 === 0) { circle(ctx, 0, 0, s); ctx.fill(); }
    else if (i % 3 === 1) { ctx.rotate(a + t * 0.4); ctx.fillRect(-s, -s, s * 2, s * 2); }
    else { ctx.rotate(a * 2); ctx.beginPath(); ctx.moveTo(0, -s * 1.3); ctx.lineTo(s * 1.2, s); ctx.lineTo(-s * 1.2, s); ctx.closePath(); ctx.fill(); }
    ctx.restore();
  }
  const breathe = 1 + 0.06 * Math.sin(t * 2);
  ctx.save(); ctx.translate(cx, cy); ctx.scale(breathe, breathe); ctx.translate(-22, -22);
  if (typeof themeGlyph === "function") themeGlyph(ctx, 44); else glyph(ctx, 44, "#F5F5F7");
  ctx.restore();
}

