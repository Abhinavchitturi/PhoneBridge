"use strict";
// The six themes (mac/Sources/Themes.swift AppTheme.all), their small glyph, corner artwork, the animated hero banner
// (MotifScenes.swift) and the extras around the searching orb (MotifAnimation.swift orbExtras). Vector drawings are
// ported from MotifArt; the Mac's cut-out pictures (mac/Assets/themeart_*.png) are used when the app serves them.

const THEMES = [
  { id: "midnight", name: "Midnight", bg: "#0B0B0D", accent: "#F5F5F7", accent2: "#A1A1A6", onAccent: "#0B0B0D", motif: "arch" },
  { id: "batman", name: "Batman", bg: "#09090A", accent: "#F5C518", accent2: "#B8921A", onAccent: "#111111", motif: "bat" },
  { id: "spiderman", name: "Spider-Man", bg: "#0A0A0B", accent: "#E23636", accent2: "#8FA3FF", onAccent: "#FFFFFF", motif: "spider" },
  { id: "ironman", name: "Iron Man", bg: "#0F0A0A", accent: "#FFC21A", accent2: "#D0112B", onAccent: "#111111", motif: "reactor" },
  { id: "thor", name: "Thor", bg: "#080A10", accent: "#5AB8FF", accent2: "#A9B7C6", onAccent: "#06121F", motif: "thor" },
  { id: "matrix", name: "Matrix", bg: "#020A05", accent: "#19F06B", accent2: "#0E9B45", onAccent: "#021108", motif: "matrix" },
];
const themeById = (id) => THEMES.find((t) => t.id === id) || THEMES[0];
let currentTheme = THEMES[0];

function rgba(hex, a) {
  const n = parseInt(hex.slice(1), 16);
  return `rgba(${(n >> 16) & 255},${(n >> 8) & 255},${n & 255},${a})`;
}

/** Switches the page's colours to a theme (the CSS variables every component uses). */
function applyTheme(id) {
  const t = themeById(id);
  if (t === currentTheme && document.documentElement.dataset.theme === t.id) return;
  currentTheme = t;
  const s = document.documentElement.style;
  s.setProperty("--bg", t.bg);
  s.setProperty("--accent", t.accent);
  s.setProperty("--accent2", t.accent2);
  s.setProperty("--on-accent", t.onAccent);
  document.documentElement.dataset.theme = t.id;
}

// ---------- Pictures (served by the app at https://art.phonebridge/) ----------
const ART = {};
function art(name) {
  if (!(name in ART)) {
    const img = new Image();
    ART[name] = null;
    img.onload = () => { ART[name] = img; };
    img.src = `https://art.phonebridge/themeart_${name}.png`;
  }
  return ART[name];
}
function picture(ctx, name, x, y, fit, opacity, rotation, scaleY) {
  const img = art(name);
  if (!img) return false;
  const k = fit / Math.max(img.width, img.height);
  ctx.save();
  ctx.globalAlpha *= opacity == null ? 1 : opacity;
  ctx.translate(x, y);
  if (rotation) ctx.rotate(rotation);
  if (scaleY) ctx.scale(1, scaleY);
  ctx.drawImage(img, -img.width * k / 2, -img.height * k / 2, img.width * k, img.height * k);
  ctx.restore();
  return true;
}

// ---------- Vector emblems (MotifArt) ----------
function batPath(ctx, px, py, s) {
  const pt = (x, y) => [px + x * s, py + (y - 3) * s];
  ctx.beginPath();
  ctx.moveTo(...pt(0, -9)); ctx.lineTo(...pt(3, -13)); ctx.lineTo(...pt(4, -8));
  const q = (cx, cy, x, y) => ctx.quadraticCurveTo(...pt(cx, cy), ...pt(x, y));
  q(9, -4, 14, -8); q(30, -16, 48, 2); q(38, 0, 30, 7); q(24, 2, 16, 12); q(10, 8, 0, 20);
  q(-10, 8, -16, 12); q(-24, 2, -30, 7); q(-38, 0, -48, 2); q(-30, -16, -14, -8); q(-9, -4, -4, -8);
  ctx.lineTo(...pt(-3, -13)); ctx.closePath();
}
function bat(ctx, x, y, s, color) { batPath(ctx, x, y, s); ctx.fillStyle = color; ctx.fill(); }
function spider(ctx, px, py, s, color) {
  const pt = (x, y) => [px + x * s, py + (y - 4) * s];
  const legs = [[[3, -6], [20, -10], [16, -36]], [[3, -3], [28, -4], [27, -30]], [[3, 1], [26, 8], [32, 34]], [[3, 5], [16, 14], [17, 40]]];
  ctx.strokeStyle = color; ctx.lineWidth = Math.max(1, 2.1 * s); ctx.lineCap = "round"; ctx.lineJoin = "round";
  ctx.beginPath();
  for (const side of [-1, 1]) for (const l of legs) { ctx.moveTo(...pt(side * l[0][0], l[0][1])); ctx.lineTo(...pt(side * l[1][0], l[1][1])); ctx.lineTo(...pt(side * l[2][0], l[2][1])); }
  ctx.stroke();
  ctx.fillStyle = color;
  ctx.beginPath(); ctx.moveTo(...pt(0, -2)); ctx.quadraticCurveTo(...pt(9, 8), ...pt(3, 24)); ctx.lineTo(...pt(0, 32)); ctx.lineTo(...pt(-3, 24)); ctx.quadraticCurveTo(...pt(-9, 8), ...pt(0, -2)); ctx.fill();
  ctx.beginPath(); ctx.ellipse(px, py - 7.2 * s, 3.4 * s, 3.8 * s, 0, 0, Math.PI * 2); ctx.fill();
}
function mask(ctx, x, y, r, fill, eye, line) {
  ctx.fillStyle = fill; ctx.beginPath(); ctx.arc(x, y, r, 0, Math.PI * 2); ctx.fill();
  ctx.strokeStyle = line; ctx.lineWidth = Math.max(0.6, r * 0.07); ctx.beginPath();
  for (let i = 0; i < 8; i++) { const a = i * Math.PI / 4; ctx.moveTo(x, y); ctx.lineTo(x + Math.cos(a) * r * 0.98, y + Math.sin(a) * r * 0.98); }
  ctx.moveTo(x + r * 0.62, y); ctx.arc(x, y, r * 0.62, 0, Math.PI * 2); ctx.stroke();
  for (const side of [-1, 1]) {
    ctx.save(); ctx.translate(x + side * r * 0.44, y - r * 0.04); ctx.rotate(-side * 0.5);
    ctx.fillStyle = line; ctx.beginPath(); ctx.ellipse(0, 0, r * 0.46, r * 0.29, 0, 0, Math.PI * 2); ctx.fill();
    ctx.fillStyle = eye; ctx.beginPath(); ctx.ellipse(0, 0, r * 0.3, r * 0.17, 0, 0, Math.PI * 2); ctx.fill();
    ctx.restore();
  }
}
function web(ctx, cx, cy, k, color) {
  const ends = [[-95, 28], [-78, 72], [-48, 108], [-4, 124]].map(([x, y]) => [cx + x * k, cy + y * k]);
  ctx.strokeStyle = color; ctx.lineWidth = Math.max(0.8, k * 0.55); ctx.beginPath();
  for (const e of ends) { ctx.moveTo(cx, cy); ctx.lineTo(...e); }
  for (const t of [0.35, 0.65]) ends.forEach((e, i) => { const q = [cx + (e[0] - cx) * t, cy + (e[1] - cy) * t]; i === 0 ? ctx.moveTo(...q) : ctx.lineTo(...q); });
  ctx.stroke();
}
function reactor(ctx, x, y, r, color) {
  ctx.strokeStyle = color;
  for (const [f, w] of [[1, 1], [0.72, 2], [0.38, 2]]) { ctx.lineWidth = w; ctx.beginPath(); ctx.arc(x, y, r * f, 0, Math.PI * 2); ctx.stroke(); }
}
function ironFace(ctx, cx, cy, s, helmet, plate, eye) {
  const poly = (pts, color) => { ctx.fillStyle = color; ctx.beginPath(); pts.forEach(([x, y], i) => (i ? ctx.lineTo : ctx.moveTo).call(ctx, cx + x * s, cy + y * s)); ctx.closePath(); ctx.fill(); };
  poly([[0, -53], [18, -50], [29, -38], [32, -20], [34, -6], [38, 0], [34, 10], [26, 36], [22, 48], [12, 54], [-12, 54], [-22, 48], [-26, 36], [-34, 10], [-38, 0], [-34, -6], [-32, -20], [-29, -38], [-18, -50]], helmet);
  poly([[-27, -40], [-20, -47], [-9, -47], [-6, -20], [6, -20], [9, -47], [20, -47], [27, -40], [29, -8], [25, 14], [15, 38], [9, 46], [-9, 46], [-15, 38], [-25, 14], [-29, -8]], plate);
  for (const sd of [-1, 1]) { poly([[sd * 27, -7], [sd * 5, 1], [sd * 5, 10], [sd * 24, 7]], helmet); poly([[sd * 24, -4], [sd * 8, 3], [sd * 8, 6.5], [sd * 21, 5]], eye); }
}
function hammer(ctx, cx, cy, s, color, cut, rotation) {
  ctx.save(); ctx.translate(cx, cy); ctx.rotate(rotation == null ? 25 * Math.PI / 180 : rotation);
  ctx.fillStyle = color;
  const box = (x, y, w, h) => { ctx.beginPath(); ctx.roundRect(x * s, (y + 6) * s, w * s, h * s, 2 * s); ctx.fill(); };
  box(-24, -26, 48, 34); box(-6, -31, 12, 6); box(-5, 8, 10, 34); box(-7, 40, 14, 4);
  ctx.strokeStyle = cut; ctx.lineWidth = Math.max(0.7, 2.1 * s); ctx.beginPath();
  for (const sx of [-19, 19]) { ctx.moveTo(sx * s, -14 * s); ctx.lineTo(sx * s, 8 * s); }
  for (const y of [17, 24, 31]) { ctx.moveTo(-5 * s, (y + 9) * s); ctx.lineTo(5 * s, (y + 3) * s); }
  ctx.stroke(); ctx.restore();
}
function bolt(ctx, ox, oy, s, color) {
  const pts = [[0, 0], [-14, 34], [-2, 34], [-18, 78], [8, 28], [-4, 28], [10, 0]];
  ctx.fillStyle = color; ctx.beginPath(); pts.forEach(([x, y], i) => (i ? ctx.lineTo : ctx.moveTo).call(ctx, ox + x * s, oy + y * s)); ctx.closePath(); ctx.fill();
}
function lightning(ctx, x0, y0, x1, y1, seed, jitter) {
  ctx.beginPath(); ctx.moveTo(x0, y0);
  const n = 7;
  for (let i = 1; i < n; i++) {
    const t = i / n;
    ctx.lineTo(x0 + (x1 - x0) * t + (hash(seed + i) - 0.5) * jitter * 2, y0 + (y1 - y0) * t + (hash(seed + i + 50) - 0.5) * jitter * 2);
  }
  ctx.lineTo(x1, y1);
}
function glowDisc(ctx, x, y, r, color, alpha) {
  const g = ctx.createRadialGradient(x, y, 0, x, y, r);
  g.addColorStop(0, rgba(color, alpha)); g.addColorStop(1, rgba(color, 0));
  ctx.fillStyle = g; ctx.beginPath(); ctx.arc(x, y, r, 0, Math.PI * 2); ctx.fill();
}

/** The theme's small mark (dock, tiles, the orb's centre). Drawn into a square of `size`. */
function themeGlyph(ctx, size, t, color) {
  t = typeof t === "string" ? themeById(t) : t || currentTheme;
  color = color || t.accent;
  const c = size / 2, k = size / 28;
  switch (t.motif) {
    case "arch": return arch(ctx, c, c - 1.8 * k, 18 * k, color, 2.4 * k);
    case "bat": if (!picture(ctx, "bat", c, c, 26 * k)) bat(ctx, c, c, 0.27 * k, color); return;
    case "spider": if (!picture(ctx, "mask", c, c, 24 * k)) mask(ctx, c, c, 10 * k, color, "#fff", t.bg); return;
    case "reactor": if (!picture(ctx, "helmet", c, c, 27 * k)) ironFace(ctx, c, c, 0.25 * k, t.accent2, color, "#fff"); return;
    case "thor": if (!picture(ctx, "thor", c, c, 25 * k)) hammer(ctx, c, c - 3 * k, 0.3 * k, color, t.bg); return;
    case "matrix":
      ctx.fillStyle = color; ctx.font = `700 ${12 * k}px ui-monospace, Consolas, monospace`; ctx.textAlign = "center"; ctx.textBaseline = "middle";
      ctx.fillText("01", c, c);
  }
}

/** Big faint artwork for the top-right corner of a window or a theme tile (MotifArt.draw, designed at 380 wide). */
function drawThemeArt(cv, id) {
  const t = themeById(id);
  const [ctx, w, h] = fitCanvas(cv);
  ctx.clearRect(0, 0, w, h);
  const u = w / 380;
  const draw = () => {
    ctx.clearRect(0, 0, w, h);
    switch (t.motif) {
      case "arch": arch(ctx, w - 120 * u, 96 * u, 150 * u, rgba(t.accent, 0.09), 9 * u); break;
      case "bat":
        if (picture(ctx, "batoval", w - 135 * u, 96 * u, 250 * u, 0.6)) { picture(ctx, "bat", w - 285 * u, 45 * u, 62 * u, 0.5); picture(ctx, "bat", w - 60 * u, 215 * u, 50 * u, 0.4); break; }
        ctx.fillStyle = rgba(t.accent, 0.15); ctx.strokeStyle = rgba(t.accent, 0.35); ctx.lineWidth = 3 * u;
        ctx.beginPath(); ctx.ellipse(w - 130 * u, 91 * u, 120 * u, 75 * u, 0, 0, Math.PI * 2); ctx.fill(); ctx.stroke();
        bat(ctx, w - 130 * u, 92 * u, 1.9 * u, t.bg); bat(ctx, w - 285 * u, 40 * u, 0.35 * u, rgba(t.accent, 0.25)); bat(ctx, w - 60 * u, 210 * u, 0.3 * u, rgba(t.accent, 0.22));
        break;
      case "spider":
        if (picture(ctx, "mask", w - 120 * u, 112 * u, 200 * u, 0.55)) { picture(ctx, "spider", w - 270 * u, 150 * u, 110 * u, 0.5); break; }
        web(ctx, w, 0, 2.1 * u, rgba(t.accent2, 0.26)); spider(ctx, w - 110 * u, 150 * u, 2.4 * u, rgba(t.accent, 0.6));
        break;
      case "reactor":
        if (picture(ctx, "helmet", w - 120 * u, 112 * u, 210 * u, 0.8)) { picture(ctx, "reactor", w - 270 * u, 160 * u, 100 * u, 0.4); break; }
        reactor(ctx, w - 120 * u, 112 * u, 112 * u, rgba(t.accent, 0.22));
        ctx.globalAlpha = 0.75; ironFace(ctx, w - 120 * u, 112 * u, 1.75 * u, t.accent2, t.accent, "#F4F4F4"); ctx.globalAlpha = 1;
        break;
      case "thor":
        if (picture(ctx, "thor", w - 120 * u, 113 * u, 215 * u, 0.5)) break;
        ctx.strokeStyle = rgba(t.accent, 0.3); ctx.lineWidth = 6 * u; ctx.beginPath(); ctx.arc(w - 120 * u, 113 * u, 105 * u, 0, Math.PI * 2); ctx.stroke();
        hammer(ctx, w - 120 * u, 108 * u, 2 * u, rgba(t.accent, 0.5), t.bg); bolt(ctx, w - 285 * u, 6 * u, 0.8 * u, rgba(t.accent2, 0.28));
        break;
      case "matrix": {
        const chars = "01アウ7Z3";
        ctx.font = `${Math.max(5, 12 * u)}px ui-monospace, Consolas, monospace`; ctx.textAlign = "center"; ctx.textBaseline = "middle";
        for (let c = 0; c < 9; c++) for (let r = 0; r < 9; r++) {
          ctx.fillStyle = rgba(t.accent, 0.05 + r * 0.03);
          ctx.fillText(chars[(c * 3 + r) % chars.length], w - 24 * u - c * 22 * u, 20 * u + r * 22 * u);
        }
      }
    }
  };
  draw();
  setTimeout(draw, 400); // again once the pictures have loaded
}

/** The animated banner at the top of the main window: one scene per theme (MotifArt.scene). */
function heroScene(ctx, w, h, time, t) {
  t = t || currentTheme;
  ctx.fillStyle = t.bg; ctx.fillRect(0, 0, w, h);
  const lin = (c0, c1) => { const g = ctx.createLinearGradient(0, 0, 0, h); g.addColorStop(0, c0); g.addColorStop(1, c1); ctx.fillStyle = g; ctx.fillRect(0, 0, w, h); };
  switch (t.motif) {
    case "arch": return drawHeroArch(ctx, w, h, time);
    case "bat": {
      lin(t.bg, "#16130A");
      for (let i = 0; i < 3; i++) {
        const cx = ((time * (6 + 3 * i) + i * w * 0.4) % (w * 1.4)) - w * 0.2, cy = h * (0.12 + 0.2 * i);
        const g = ctx.createRadialGradient(cx, cy, 0, cx, cy, 90); g.addColorStop(0, "rgba(255,255,255,.05)"); g.addColorStop(1, "rgba(255,255,255,0)");
        ctx.fillStyle = g; ctx.beginPath(); ctx.ellipse(cx, cy, 90, 22, 0, 0, Math.PI * 2); ctx.fill();
      }
      const sx = w * 0.72, sy = h * 0.38, sr = h * 0.36, sway = Math.sin(time * 0.6) * 8, bx = w * 0.22, by = h + 4;
      const g = ctx.createLinearGradient(bx, by, sx, sy); g.addColorStop(0, rgba(t.accent, 0)); g.addColorStop(1, rgba(t.accent, 0.24));
      ctx.fillStyle = g; ctx.beginPath(); ctx.moveTo(bx - 12, by); ctx.lineTo(bx + 12, by); ctx.lineTo(sx + sr * 0.85 + sway, sy); ctx.lineTo(sx - sr * 0.85 + sway, sy); ctx.closePath(); ctx.fill();
      const pulse = 0.5 + 0.5 * Math.sin(time * 1.4);
      const rg = ctx.createRadialGradient(sx + sway, sy, 0, sx + sway, sy, sr); rg.addColorStop(0, rgba(t.accent, 0.5 + 0.2 * pulse)); rg.addColorStop(1, rgba(t.accent, 0.1));
      ctx.fillStyle = rg; ctx.beginPath(); ctx.arc(sx + sway, sy, sr, 0, Math.PI * 2); ctx.fill();
      if (!picture(ctx, "batoval", sx + sway, sy, sr * 1.6, 0.95)) bat(ctx, sx + sway, sy, sr / 34, t.bg);
      for (let i = 0; i < 5; i++) {
        const travel = (time * (34 + 9 * i) + i * 150) % (w + 100);
        const px = w + 30 - travel, py = h * (0.16 + 0.13 * i) + 8 * Math.sin(time * 2 + i), flap = 0.62 + 0.38 * Math.abs(Math.sin(time * 9 + i * 1.7));
        if (!picture(ctx, "bat", px, py, 26 + (i % 2) * 6, 0.8, 0, flap)) { ctx.save(); ctx.translate(px, py); ctx.scale(1, flap); bat(ctx, 0, 0, 0.3 + (i % 2) * 0.06, rgba(t.accent, 0.8)); ctx.restore(); }
      }
      ctx.fillStyle = "#050505"; ctx.fillRect(0, h - 8, w, 8);
      return;
    }
    case "spider": {
      lin("#2C0508", t.bg);
      glowDisc(ctx, w * 0.8, h * 0.2, w * 0.5, "#7A1018", 0.35);
      const city = (speed, color, seed, lo, hi, windows) => {
        const off = (time * speed) % 60;
        for (let i = -1; i < w / 60 + 2; i++) {
          const k = Math.floor(i + (time * speed) / 60);
          const bh = lo + hash(seed + k) * (hi - lo), bw = 40 + hash(seed + k + 9) * 26, x = i * 60 - off;
          ctx.fillStyle = color; ctx.fillRect(x, h - bh, bw, bh);
          if (windows) for (let wy = h - bh + 8; wy < h - 6; wy += 11) for (let wx = x + 6; wx < x + bw - 6; wx += 9)
            if (hash(seed + k * 7 + wy * 3 + wx) > 0.72) { ctx.fillStyle = rgba(windows, 0.35); ctx.fillRect(wx, wy, 3, 4); }
        }
      };
      city(7, "#1A0508", 3, 60, 125);
      city(16, "#0A0204", 21, 28, 78, t.accent2);
      if (!picture(ctx, "web", w - 62, 52, 112, 0.35)) web(ctx, w, 0, 1, rgba(t.accent2, 0.3));
      // a swing: pendulum from an anchor that moves across, the spider on the end of the line
      const cyc = (time % 3.6) / 3.6, ax = w * (0.2 + 0.6 * cyc), ay = -4, len = h * 0.62;
      const ang = Math.sin(cyc * Math.PI * 2) * 0.9;
      const px = ax + Math.sin(ang) * len, py = ay + Math.cos(ang) * len;
      ctx.strokeStyle = "rgba(255,255,255,.55)"; ctx.lineWidth = 1; ctx.beginPath(); ctx.moveTo(ax, ay); ctx.lineTo(px, py); ctx.stroke();
      if (!picture(ctx, "spider", px, py + 8, 30, 1, ang)) spider(ctx, px, py + 8, 0.45, t.accent);
      return;
    }
    case "reactor": {
      lin("#1D0D0A", t.bg);
      const pulse = 0.5 + 0.5 * Math.sin(time * 2.2);
      glowDisc(ctx, w * 0.74, h * 0.5, h * 0.9, t.accent, 0.12 + 0.1 * pulse);
      ctx.strokeStyle = rgba(t.accent, 0.05); ctx.lineWidth = 0.6; ctx.beginPath();
      for (let i = 1; i < 8; i++) { const y = h * i / 8; ctx.moveTo(0, y); ctx.lineTo(w, y); }
      ctx.stroke();
      const rx = w * 0.14, ry = h * 0.66, cyc = time % 3.2, fire = cyc < 0.55 ? 1 - cyc / 0.55 : 0;
      glowDisc(ctx, rx, ry, 62, t.accent, 0.25 + 0.4 * fire);
      if (!picture(ctx, "reactor", rx, ry, 58, 0.95, time * 0.8)) { reactor(ctx, rx, ry, 26, rgba(t.accent, 0.9)); }
      if (!picture(ctx, "helmet", w * 0.76, h * 0.52, h * 0.95, 0.95)) ironFace(ctx, w * 0.76, h * 0.52, h / 120, t.accent2, t.accent, "#F4F4F4");
      if (fire > 0) {
        ctx.lineCap = "round";
        ctx.strokeStyle = rgba(t.accent, 0.5 * fire); ctx.lineWidth = 12 * fire; ctx.beginPath(); ctx.moveTo(rx, ry); ctx.lineTo(w * 0.4, -h * 0.1); ctx.stroke();
        ctx.strokeStyle = `rgba(255,255,255,${0.95 * fire})`; ctx.lineWidth = 3 * fire; ctx.stroke();
        const rr = 18 + 56 * (1 - fire);
        ctx.strokeStyle = rgba(t.accent, 0.7 * fire); ctx.lineWidth = 2; ctx.beginPath(); ctx.arc(rx, ry, rr, 0, Math.PI * 2); ctx.stroke();
      }
      return;
    }
    case "thor": {
      for (let i = 0; i < 3; i++) glowDisc(ctx, w * (0.2 + 0.3 * i) + 20 * Math.sin(time * 0.3 + i), h * 0.25, w * 0.35, t.accent2, 0.1);
      const period = 4.4, p = (time % period) / period, cx = w * 0.74, cy = h * 0.44, sx = -30, sy = h * 0.92;
      ctx.strokeStyle = rgba(t.accent, 0.25 + 0.15 * Math.sin(time * 3)); ctx.lineWidth = 1.2; ctx.beginPath(); ctx.arc(cx, cy, 24, 0, Math.PI * 2); ctx.stroke();
      const at = (q) => { const e = 1 - Math.pow(1 - q, 2); return [sx + (cx - sx) * e, sy + (cy - sy) * e - Math.sin(Math.PI * q) * h * 0.3]; };
      if (p < 0.55) {
        const q = p / 0.55;
        for (let k = 1; k <= 5; k++) { const [x, y] = at(Math.max(0, q - k * 0.03)); ctx.fillStyle = rgba(t.accent, 0.3 * (1 - k / 6)); circle(ctx, x, y, 4); ctx.fill(); }
        const [x, y] = at(q);
        if (!picture(ctx, "hammer", x, y, 56, 1, q * 18)) hammer(ctx, x, y, 0.6, t.accent2, t.bg, q * 18);
      } else {
        const q = (p - 0.55) / 0.45, flash = Math.max(0, 1 - q * 3);
        glowDisc(ctx, cx, cy, 90, t.accent, 0.55 * flash);
        if (flash > 0) {
          const rr = 20 + 90 * (1 - flash);
          ctx.strokeStyle = `rgba(255,255,255,${0.8 * flash})`; ctx.lineWidth = 2; ctx.beginPath(); ctx.arc(cx, cy, rr, 0, Math.PI * 2); ctx.stroke();
          for (let k = 0; k < 4; k++) {
            const a = k * Math.PI / 2 + 0.5;
            lightning(ctx, cx, cy, cx + Math.cos(a) * 120, cy + Math.sin(a) * 80, k * 3 + Math.floor(time / period), 10);
            ctx.strokeStyle = rgba(t.accent, 0.6 * flash); ctx.lineWidth = 5; ctx.stroke();
            ctx.strokeStyle = `rgba(255,255,255,${0.9 * flash})`; ctx.lineWidth = 1.5; ctx.stroke();
          }
        }
        ctx.save(); ctx.globalAlpha = Math.max(0, 1 - Math.max(0, q - 0.55) * 2.2);
        if (!picture(ctx, "hammer", cx, cy, 56, 1, 0.45)) hammer(ctx, cx, cy, 0.6, t.accent2, t.bg, 0.45);
        ctx.restore();
      }
      return;
    }
    case "matrix": {
      const chars = "01アウ7Z3", cols = Math.max(8, Math.floor(w / 18));
      ctx.font = "12px ui-monospace, Consolas, monospace"; ctx.textAlign = "center"; ctx.textBaseline = "middle";
      for (let col = 0; col < cols; col++) {
        const speed = 40 + 50 * hash(col), head = ((time * speed + hash(col + 3) * 400) % (h + 160)) - 20;
        for (let r = 0; r < 12; r++) {
          const y = head - r * 15;
          if (y < -8 || y > h + 8) continue;
          const a = r === 0 ? 0.9 : 0.55 * (1 - r / 12);
          ctx.fillStyle = r === 0 ? `rgba(255,255,255,${a})` : rgba(t.accent, a);
          ctx.fillText(chars[Math.floor(hash(col * 31 + r + Math.floor(time * 5)) * chars.length) % chars.length], 10 + col * 18, y);
        }
      }
    }
  }
}

/** Little extras around the searching orb, per theme (MotifArt.orbExtras). */
function orbExtras(ctx, cx, cy, R, time, t) {
  t = t || currentTheme;
  switch (t.motif) {
    case "bat":
      for (let i = 0; i < 7; i++) {
        const ring = i % 2 === 0 ? 1.62 : 1.42, a = i * 0.9 + time * (i % 2 === 0 ? 0.32 : -0.26);
        const x = cx + Math.cos(a) * R * ring, y = cy + Math.sin(a) * R * ring, flap = 0.6 + 0.4 * Math.abs(Math.sin(time * 8 + i * 1.3));
        if (!picture(ctx, "bat", x, y, 22, 0.8, a + (i % 2 === 0 ? Math.PI / 2 : -Math.PI / 2), flap)) {
          ctx.save(); ctx.translate(x, y); ctx.rotate(a + Math.PI / 2); ctx.scale(1, flap); bat(ctx, 0, 0, 0.22, rgba(t.accent, 0.8)); ctx.restore();
        }
      }
      return true;
    case "spider": {
      ctx.strokeStyle = rgba(t.accent2, 0.22); ctx.lineWidth = 0.8; ctx.beginPath();
      for (let k = 0; k < 8; k++) { const a = k * Math.PI / 4 + time * 0.06; ctx.moveTo(cx + Math.cos(a) * R * 1.05, cy + Math.sin(a) * R * 1.05); ctx.lineTo(cx + Math.cos(a) * R * 1.62, cy + Math.sin(a) * R * 1.62); }
      ctx.stroke();
      const a = time * 0.3, x = cx + Math.cos(a) * R * 1.62, y = cy + Math.sin(a) * R * 1.62;
      if (!picture(ctx, "spider", x, y, 30, 0.9, a + Math.PI / 2 + 0.15 * Math.sin(time * 6))) { ctx.save(); ctx.translate(x, y); ctx.rotate(a + Math.PI / 2); spider(ctx, 0, 0, 0.4, t.accent); ctx.restore(); }
      return false;
    }
    case "reactor":
      for (const [scale, span, speed, lw, color] of [[1.74, 80, 0.9, 2.4, t.accent], [1.86, 44, -1.3, 1.8, t.accent2], [1.96, 120, 0.5, 1.4, t.accent]]) {
        const start = time * speed;
        ctx.strokeStyle = rgba(color, 0.7); ctx.lineWidth = lw; ctx.lineCap = "round";
        ctx.beginPath(); ctx.arc(cx, cy, R * scale, start, start + span * Math.PI / 180); ctx.stroke();
      }
      return false;
    case "thor": {
      const cyc = time % 2.2, flash = cyc < 0.3 ? 1 - cyc / 0.3 : 0;
      if (flash > 0) {
        const seed = Math.floor(time / 2.2);
        for (let k = 0; k < 3; k++) {
          const a = hash(seed + k * 3.1) * 2 * Math.PI;
          lightning(ctx, cx + Math.cos(a) * R * 0.95, cy + Math.sin(a) * R * 0.95, cx + Math.cos(a + 0.2) * R * 1.95, cy + Math.sin(a + 0.2) * R * 1.95, seed * 5 + k, 9);
          ctx.strokeStyle = `rgba(255,255,255,${0.9 * flash})`; ctx.lineWidth = 1.4; ctx.stroke();
        }
      }
      return false;
    }
    case "matrix": return true; // its own rain replaces the floating shapes
  }
  return false;
}

// Previews (tools/inline-ui.py page demo theme) pick a theme.
if (window.__theme) applyTheme(window.__theme);
