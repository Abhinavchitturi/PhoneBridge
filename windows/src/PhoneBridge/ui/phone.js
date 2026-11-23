"use strict";
// The phone window's logic: a port of PhoneStore (mac/Sources/PhoneWindow.swift) and the views around it.
// The phone's data (threads, photos, calls…) lives here; app-wide state (connection, history, clipboard, settings)
// comes from the app in "state" messages. Requests to the phone go out as {cmd:"tx", m:{t:…}}.

const TABS = [
  ["messages", "Messages", "message"], ["photos", "Photos", "photo"], ["calls", "Calls", "call"],
  ["apps", "Apps", "screen"], ["clipboard", "Clipboard", "clipboard"], ["device", "Device", "phone"],
];
const TITLES = {
  messages: "Messages", photos: "Photos & Videos", calls: "Calls", apps: "Phone Apps", clipboard: "Clipboard & Notes",
  device: "Device & Find My Phone", settings: "Settings", notifications: "Notification History",
};

// App state (pushed by the app) and phone data (from the phone).
let app = {
  connected: false, phoneName: "Phone", battery: null, charging: false, eta: null, ringing: false, dnd: false,
  history: [], clips: [], note: "", noteTs: 0, quickReplies: [], rules: { mutedApps: [], keywords: [], replyPopup: true, smartReplies: true },
  settings: {}, streaming: "", micStatus: "Off", presence: "", version: "",
};
const P = {
  tab: "messages", panel: true, threads: [], selectedThread: null, messages: [], drafts: {}, compose: null, readMarks: {},
  photos: [], photoTotal: 0, albums: [], album: null, kind: "all", loadingPhotos: false, selection: new Set(), selecting: false,
  downloaded: {}, progress: {}, viewing: null, extra: {}, slide: null,
  calls: [], contacts: [], device: null, biggest: [], location: null, locating: false, locationError: null,
  autoreply: { on: false, text: "", contactsOnly: true, declined: true }, smsOn: true, apps: [],
  search: { threads: "", contacts: "", apps: "", clips: "", history: "" }, palette: { open: false, q: "", index: 0 },
};
try { P.readMarks = JSON.parse(localStorage.getItem("smsReadMarks") || "{}"); } catch (e) { /* private window */ }

const tx = (m) => send("tx", { m });
const rel = new Intl.RelativeTimeFormat(undefined, { numeric: "auto", style: "short" });
function ago(ms) {
  const s = (ms - Date.now()) / 1000, a = Math.abs(s);
  if (a < 60) return rel.format(Math.round(s), "second");
  if (a < 3600) return rel.format(Math.round(s / 60), "minute");
  if (a < 86400) return rel.format(Math.round(s / 3600), "hour");
  if (a < 86400 * 30) return rel.format(Math.round(s / 86400), "day");
  return new Date(ms).toLocaleDateString();
}
const fmtTime = (ms) => new Date(ms).toLocaleTimeString([], { hour: "numeric", minute: "2-digit" });
const fmtStamp = (ms) => {
  const d = new Date(ms), today = new Date();
  const sameDay = d.toDateString() === today.toDateString();
  const y = new Date(today); y.setDate(today.getDate() - 1);
  const day = sameDay ? "Today" : d.toDateString() === y.toDateString() ? "Yesterday" : d.toLocaleDateString(undefined, { month: "short", day: "numeric", year: "numeric" });
  return `${day} at ${fmtTime(ms)}`;
};
const bytes = (n) => { const u = ["bytes", "KB", "MB", "GB", "TB"]; let i = 0; n = Number(n) || 0; while (n >= 1000 && i < u.length - 1) { n /= 1000; i++; } return (i ? n.toFixed(n < 10 ? 1 : 0) : n) + " " + u[i]; };
const mmss = (s) => `${Math.floor(s / 60)}:${String(Math.floor(s % 60)).padStart(2, "0")}`;
const digits = (s) => String(s || "").replace(/\D/g, "");
const sameNumber = (a, b) => { const x = digits(a).slice(-10), y = digits(b).slice(-10); return x.length > 0 && x === y; };
function toast(text) { const t = $("#toast"); t.textContent = text; t.classList.add("show"); clearTimeout(toast.t); toast.t = setTimeout(() => t.classList.remove("show"), 2600); }

function nameFor(number) {
  const d = digits(number).slice(-10);
  if (!d) return null;
  const same = (o) => digits(o).endsWith(d);
  return P.contacts.find((c) => same(c.number))?.name || P.calls.find((c) => same(c.number) && c.name)?.name || P.threads.find((t) => same(t.address))?.name || null;
}
function dial(number) {
  const n = String(number || "").trim();
  if (!n) return;
  send("dial", { number: n, name: nameFor(n) });
}

// ---------- Tabs and requests ----------
function setTab(tab) {
  if (P.tab === tab && document.body.dataset.tab === tab) return;
  P.tab = tab;
  P.viewing = null;
  document.body.dataset.tab = tab;
  refresh(tab);
  render();
}
function refresh(tab) {
  if (!app.connected) return;
  switch (tab) {
    case "messages": tx({ t: "sms_threads_request" }); break;
    case "photos": tx({ t: "albums_request" }); if (!P.photos.length) loadMorePhotos(); break;
    case "calls": tx({ t: "call_log_request" }); if (!P.contacts.length) tx({ t: "contacts_request" }); break;
    case "device": tx({ t: "device_request" }); tx({ t: "biggest_request" }); break;
    case "settings": tx({ t: "autoreply_request" }); break;
    case "apps": if (!P.apps.length) tx({ t: "apps_request" }); break;
  }
}

// ---------- Messages ----------
const isUnread = (t) => t.unread && (P.readMarks[t.thread] || 0) < t.date;
function markRead(t) {
  P.readMarks[t.thread] = t.date;
  try { localStorage.setItem("smsReadMarks", JSON.stringify(P.readMarks)); } catch (e) { /* ignore */ }
}
function selectThread(id) {
  P.compose = null;
  P.selectedThread = id;
  P.messages = [];
  tx({ t: "sms_thread_request", thread: id });
  const t = P.threads.find((x) => x.thread === id);
  if (t) markRead(t);
  render();
}
const currentThread = () => P.threads.find((t) => t.thread === P.selectedThread);

function sendDraft() {
  const box = $("#composer-text");
  const text = (box ? box.value : "").trim();
  if (!text) return;
  let to = null;
  if (P.compose) {
    to = P.compose.to.trim();
    if (!to) { toast("Add who it's to first"); return; }
  } else {
    const t = currentThread();
    if (!t) return;
    to = t.address;
  }
  tx({ t: "sms_send", to, text });
  P.drafts[P.selectedThread || "new"] = "";
  if (!P.compose) P.messages.unshift({ body: text, date: Date.now(), mine: true });
  else { toast("Sending…"); P.compose = null; }
  // The phone writes sent SMS to its database a moment later; refresh to show the real copy.
  setTimeout(() => { if (P.selectedThread) tx({ t: "sms_thread_request", thread: P.selectedThread }); tx({ t: "sms_threads_request" }); }, 2000);
  render();
}

function renderMessages() {
  if (!P.smsOn) {
    $("#t-messages .pane").innerHTML = `<div class="card empty" style="flex:1;border-radius:22px">${icon("message")}<b style="color:var(--text)">SMS is turned off on your phone</b><span>PhoneBridge isn't reading or sending your texts. To turn it on, open PhoneBridge on the phone → Settings → SMS on computer.</span></div>`;
    return;
  }
  if (!$("#threads")) location.reload();
  const q = P.search.threads.trim().toLowerCase();
  const list = q ? P.threads.filter((t) => (t.name || t.address).toLowerCase().includes(q) || t.snippet.toLowerCase().includes(q)) : P.threads;
  $("#threads").innerHTML = list.map((t) => {
    const title = t.name || t.address, unread = isUnread(t);
    return `<button class="thread${t.thread === P.selectedThread && !P.compose ? " sel" : ""}${unread ? " unread" : ""}" data-thread="${t.thread}">
      ${avatar(title, 46)}<div class="body"><div class="top"><b>${esc(title)}</b><span>${esc(ago(t.date))}</span></div>
      <div class="snip"><p>${esc(t.snippet)}</p>${unread ? '<i class="unread-dot"></i>' : ""}</div></div></button>`;
  }).join("") || `<div class="empty" style="padding:40px 10px">${icon("message")}<span>${P.threads.length ? "No matches" : "Loading conversations…"}</span></div>`;

  const chat = $("#chat");
  const keep = $("#composer-text") ? { v: $("#composer-text").value, f: document.activeElement === $("#composer-text") } : null;
  if (P.compose) {
    chat.innerHTML = `<div class="to-row"><b style="font:700 16px var(--font-rounded)">To:</b><input class="field" id="compose-to" placeholder="Name or number" value="${esc(P.compose.to)}"></div>
      <div class="msgs"><div class="empty" style="padding:40px">${icon("message")}<span>New message</span></div></div>${chipsHtml()}${composerHtml()}`;
    const to = $("#compose-to");
    to.addEventListener("input", () => { P.compose.to = to.value; suggest(to.value); });
    if (!keep) setTimeout(() => to.focus(), 30);
  } else {
    const t = currentThread();
    if (!t) {
      chat.innerHTML = `<div class="empty" style="flex:1">${icon("message")}<b style="color:var(--text)">Pick a conversation</b><span>Your phone's messages, with the same people and history.</span></div>`;
      return;
    }
    const title = t.name || t.address;
    const ordered = [...P.messages].reverse();
    let html = "", prev = 0;
    for (const m of ordered) {
      if (!prev || m.date - prev > 1800000) html += `<div class="stamp">${esc(fmtStamp(m.date))}</div>`;
      html += `<div class="bubble${m.mine ? " mine" : ""}" data-text="${esc(m.body)}">${esc(m.body)}</div>`;
      prev = m.date;
    }
    chat.innerHTML = `<div class="chat-head">${avatar(title, 40)}<div class="who"><b>${esc(title)}</b>${t.name ? `<small>${esc(t.address)}</small>` : ""}</div>
      <button class="roundbtn" data-cmd="dial-thread" title="Call">${icon("call")}</button></div>
      <div class="msgs" id="msgs">${html}</div>${chipsHtml()}${composerHtml()}`;
    const msgs = $("#msgs");
    msgs.scrollTop = msgs.scrollHeight;
  }
  const box = $("#composer-text");
  box.value = keep ? keep.v : P.drafts[P.selectedThread || "new"] || "";
  if (keep && keep.f) box.focus();
  autosize(box);
}
const chipsHtml = () => `<div class="chips">${app.quickReplies.map((r) => `<button data-quick="${esc(r)}">${esc(r)}</button>`).join("")}
  <button class="add" data-cmd="quick-save" title="Save what you've typed as a quick reply">${icon("check")}</button></div>`;
const composerHtml = () => `<div class="composer"><textarea id="composer-text" rows="1" placeholder="Message"></textarea>
  <button class="send" data-cmd="send-sms" title="Send">${icon("send")}</button></div>`;
function autosize(el) { el.style.height = "auto"; el.style.height = Math.min(110, el.scrollHeight) + "px"; const b = $(".composer .send"); if (b) b.disabled = !el.value.trim(); }
function suggest(q) {
  $(".suggest")?.remove();
  q = q.trim().toLowerCase();
  if (!q) return;
  const d = digits(q);
  const hits = P.contacts.filter((c) => c.name.toLowerCase().includes(q) || (d && digits(c.number).includes(d))).slice(0, 8);
  if (!hits.length) return;
  $("#chat").insertAdjacentHTML("beforeend", `<div class="suggest">${hits.map((c) => `<button data-to="${esc(c.number)}">${avatar(c.name, 30)}<div><b style="font-size:13px">${esc(c.name)}</b><br><small class="secondary">${esc(c.number)}</small></div></button>`).join("")}</div>`);
}

// ---------- Photos ----------
function loadMorePhotos(reset) {
  if (P.loadingPhotos || !(reset || !P.photos.length || P.photos.length < P.photoTotal)) return;
  P.loadingPhotos = true;
  tx({ t: "photos_request", offset: reset ? 0 : P.photos.length, limit: 60, album: P.album || "", kind: P.kind });
}
function resetPhotos() { P.photos = []; P.photoTotal = 0; P.selection.clear(); P.loadingPhotos = false; loadMorePhotos(true); render(); }
const photoById = (id) => P.photos.find((p) => p.id === id) || P.extra[id];
function fetchPhoto(id) {
  if (P.downloaded[id] || P.progress[id]) return;
  P.progress[id] = { got: 0, total: -1, started: Date.now() };
  tx({ t: "photo_get", id });
}
function view(id) {
  P.viewing = id;
  fetchPhoto(id);
  const i = P.photos.findIndex((p) => p.id === id);
  for (const j of [i - 1, i + 1]) if (i >= 0 && P.photos[j] && !P.photos[j].video) fetchPhoto(P.photos[j].id);
  renderViewer();
}
function step(d) {
  const i = P.photos.findIndex((p) => p.id === P.viewing);
  const j = i + d;
  if (i < 0 || !P.photos[j]) return;
  view(P.photos[j].id);
  if (j >= P.photos.length - 3) loadMorePhotos();
}
function askDelete(ids) {
  if (!ids.length) return;
  confirmSheet(`Delete ${ids.length} item${ids.length === 1 ? "" : "s"} from your phone?`,
    "They move to the phone's trash, where they stay for 30 days. Your phone may ask you to confirm.", "Delete", () => {
      tx({ t: "photos_delete", ids });
      toast(`Confirm deleting ${ids.length} item${ids.length === 1 ? "" : "s"} on the phone.`);
    });
}
const dayKey = (ms) => new Date(ms).toDateString();
function dayTitle(ms) {
  const d = new Date(ms), t = new Date();
  if (d.toDateString() === t.toDateString()) return "Today";
  const y = new Date(t); y.setDate(t.getDate() - 1);
  if (d.toDateString() === y.toDateString()) return "Yesterday";
  return d.toLocaleDateString(undefined, { weekday: "long", month: "long", day: "numeric", year: d.getFullYear() === t.getFullYear() ? undefined : "numeric" });
}
function renderPhotos() {
  document.body.classList.toggle("selecting", P.selecting);
  $("#albums").innerHTML = albumRow(null, "Library", null, null) + `<div class="overline">Albums</div>` + P.albums.map((a) => albumRow(a.id, a.name, a.count, a.cover)).join("");
  const name = P.album ? (P.albums.find((a) => a.id === P.album)?.name || "Album") : "Library";
  $("#album-title").textContent = P.selecting ? `${P.selection.size} selected` : name;
  $("#album-sub").textContent = P.selecting ? "Download or delete the selection" : P.photoTotal > 0 ? `${P.photoTotal.toLocaleString()} items` : "Loading…";
  const slides = P.photos.some((p) => !p.video);
  $("#photo-actions").innerHTML = `
    <button class="pill sm" data-cmd="slideshow"${slides ? "" : " disabled"}>${icon("play")}Slideshow</button>
    <button class="pill sm" data-cmd="photos-refresh" title="Refresh">${icon("refresh")}</button>
    ${P.selecting ? `<button class="pill sm prominent" data-cmd="photos-download"${P.selection.size ? "" : " disabled"}>${icon("download")}Download</button>
      <button class="pill sm destructive" data-cmd="photos-delete"${P.selection.size ? "" : " disabled"}>${icon("trash")}Delete</button>
      <button class="pill sm" data-cmd="photos-done">Done</button>` : `<button class="pill sm" data-cmd="photos-select">${icon("check")}Select</button>`}`;
  $("#kinds").style.display = P.selecting ? "none" : "";
  $$("#kinds button").forEach((b) => b.classList.toggle("sel", b.dataset.kind === P.kind));
  const groups = [];
  for (const p of P.photos) {
    const k = dayKey(p.date);
    if (!groups.length || groups[groups.length - 1].k !== k) groups.push({ k, date: p.date, items: [] });
    groups[groups.length - 1].items.push(p);
  }
  $("#days").innerHTML = groups.map((g) => `<div class="dayhead"><b>${esc(dayTitle(g.date))}</b><span>${g.items.length}</span></div>
    <div class="photogrid">${g.items.map(cellHtml).join("")}</div>`).join("") ||
    (P.loadingPhotos ? "" : `<div class="empty">${icon("photo")}<span>Nothing here yet</span></div>`);
  $("#loadmore").textContent = P.loadingPhotos ? "Loading…" : P.photos.length < P.photoTotal ? "Scroll for more" : "";
}
const albumRow = (id, name, count, cover) => `<button class="album${(P.album || null) === id ? " sel" : ""}" data-album="${esc(id || "")}">
  ${cover ? `<img src="data:image/jpeg;base64,${cover}" alt="">` : `<span class="cov">${icon("photo")}</span>`}
  <div style="min-width:0"><b>${esc(name)}</b>${count != null ? `<small>${Number(count).toLocaleString()}</small>` : ""}</div></button>`;
const cellHtml = (p) => `<button class="cell${P.selection.has(p.id) ? " sel" : ""}" data-photo="${p.id}" title="${esc(p.name)}">
  ${p.thumb ? `<img src="data:image/jpeg;base64,${p.thumb}" alt="" loading="lazy">` : ""}
  ${p.video ? `<span class="vid">${icon("play")}${p.duration > 0 ? mmss(p.duration) : ""}</span>` : ""}<span class="tick">${icon("check")}</span></button>`;

function renderViewer() {
  const v = $("#viewer");
  const p = P.viewing != null ? photoById(P.viewing) : null;
  if (!p) { v.classList.remove("show"); v.innerHTML = ""; return; }
  const url = P.downloaded[p.id], prog = P.progress[p.id];
  const thumb = p.thumb ? `data:image/jpeg;base64,${p.thumb}` : "";
  let stage;
  if (url && p.video) stage = `<video src="${esc(url)}" controls autoplay></video>`;
  else if (url) stage = `<img src="${esc(url)}" alt="">`;
  else {
    const frac = prog && prog.total > 0 ? Math.min(1, prog.got / prog.total) : 0;
    const speed = prog && prog.got > 0 ? prog.got / 1e6 / Math.max(0.2, (Date.now() - prog.started) / 1000) : 0;
    stage = `${thumb ? `<img class="loading" src="${thumb}" alt="">` : ""}<div class="progress glass" style="border-radius:20px">
      <svg class="ring" viewBox="0 0 62 62"><circle cx="31" cy="31" r="27" fill="none" stroke="rgba(255,255,255,.18)" stroke-width="5"/>
      <circle cx="31" cy="31" r="27" fill="none" stroke="var(--accent)" stroke-width="5" stroke-linecap="round" stroke-dasharray="${(frac * 169.6).toFixed(1)} 170" transform="rotate(-90 31 31)"/>
      <text x="31" y="36" text-anchor="middle" fill="#fff" font-size="14" font-weight="700">${prog && prog.total > 0 ? Math.round(frac * 100) + "%" : "…"}</text></svg>
      <span>${prog && prog.total > 0 ? `${(prog.got / 1e6).toFixed(1)} of ${(prog.total / 1e6).toFixed(1)} MB` : "Fetching from your phone…"}</span>
      ${speed > 0 ? `<span style="opacity:.6">${speed.toFixed(1)} MB/s</span>` : ""}</div>`;
  }
  const inGrid = P.photos.some((x) => x.id === p.id);
  const ready = !!url;
  v.innerHTML = `${thumb ? `<div class="blur" style="background-image:url('${thumb}')"></div>` : ""}
    <div class="bar">
      <button class="vbtn round glass" data-cmd="viewer-close" title="Close (Esc)">${icon("close")}</button>
      <div style="flex:1;min-width:0"><b style="font-size:14px">${esc(new Date(p.date).toLocaleString(undefined, { dateStyle: "long", timeStyle: "short" }))}</b><br>
        <small style="opacity:.6">${esc(p.name)}${p.video && p.duration > 0 ? " · " + mmss(p.duration) : ""}</small></div>
      <button class="vbtn glass" data-cmd="viewer-open" title="${p.video ? "Open in player" : "Open in Photos"}"${ready ? "" : " disabled"}>${icon("folder")}</button>
      ${p.video ? "" : `<button class="vbtn glass" data-cmd="viewer-ocr" title="Copy text from photo"${ready ? "" : " disabled"}>${icon("note")}</button>`}
      <button class="vbtn glass" data-cmd="viewer-download" title="Download">${icon("download")}</button>
      <button class="vbtn glass" data-cmd="viewer-copy" title="Copy (Ctrl+C)"${ready ? "" : " disabled"}>${icon("clipboard")}</button>
      <button class="vbtn glass red" data-cmd="viewer-delete" title="Delete">${icon("trash")}</button>
    </div>
    <div class="stage">${stage}</div>
    ${inGrid ? `<button class="arrow prev glass" data-cmd="viewer-prev">${icon("chevron")}</button><button class="arrow next glass" data-cmd="viewer-next" style="transform:translateY(-50%) scaleX(-1)">${icon("chevron")}</button>` : ""}`;
  v.classList.add("show");
}

const slidePhotos = () => P.photos.filter((p) => !p.video);
function startSlideshow() {
  const list = slidePhotos();
  if (!list.length) return;
  P.slide = { index: 0, timer: setInterval(() => stepSlide(1), 4000) };
  $("#slideshow").classList.add("show");
  stepSlide(0);
}
function stepSlide(d) {
  const list = slidePhotos();
  if (!P.slide || !list.length) return;
  P.slide.index = (P.slide.index + d + list.length) % list.length;
  for (let k = 0; k <= 2; k++) fetchPhoto(list[(P.slide.index + k) % list.length].id);
  const p = list[P.slide.index];
  const img = $("#slide");
  img.src = P.downloaded[p.id] || (p.thumb ? `data:image/jpeg;base64,${p.thumb}` : "");
  img.style.animation = "none"; void img.offsetWidth; img.style.animation = "";
}
function stopSlideshow() { if (P.slide) clearInterval(P.slide.timer); P.slide = null; $("#slideshow").classList.remove("show"); }

// ---------- Calls ----------
function renderCalls() {
  if (!$("#keys").children.length) {
    $("#keys").innerHTML = ["1", "2", "3", "4", "5", "6", "7", "8", "9", "*", "0", "#"].map((k) => `<button data-key="${k}">${k}</button>`).join("");
  }
  $("#dial-call").disabled = !$("#dial").value.trim();
  const q = P.search.contacts.trim().toLowerCase(), d = digits(q);
  const list = q ? P.contacts.filter((c) => c.name.toLowerCase().includes(q) || (d && digits(c.number).includes(d))) : P.contacts;
  $("#contacts").innerHTML = list.slice(0, 400).map((c) => `<button class="person" data-dial="${esc(c.number)}">${avatar(c.name, 34)}
    <div class="who"><b>${esc(c.name)}</b><small>${esc(c.number)}</small></div>${icon("call")}</button>`).join("") ||
    `<div class="empty" style="padding:30px">${icon("contacts")}<span>${P.contacts.length ? "No matches" : "Loading contacts…"}</span></div>`;
  $("#recents").innerHTML = P.calls.map((c) => {
    const bad = c.type === "missed" || c.type === "rejected";
    const color = bad ? "var(--red)" : c.type === "out" ? "var(--accent)" : "var(--green)";
    const ic = c.type === "in" ? "call-in" : c.type === "out" ? "call-out" : bad ? "call-end" : "call";
    const parts = [ago(c.date)];
    if (c.name) parts.unshift(c.number);
    if (c.duration > 0) parts.push(`${Math.floor(c.duration / 60)}m ${c.duration % 60}s`);
    return `<button class="person callrow${bad ? " bad" : ""}" data-dial="${esc(c.number)}" style="padding:7px 8px">
      <span class="kind" style="color:${color};background:color-mix(in srgb, ${color} 16%, transparent)">${icon(ic)}</span>
      <div class="who"><b>${esc(c.name || c.number)}</b><small>${esc(parts.join(" · "))}</small></div><span class="secondary">${icon("call")}</span></button>`;
  }).join("") || `<div class="empty">${icon("call")}<span>No calls yet</span></div>`;
}

// ---------- Apps ----------
function renderApps() {
  const q = P.search.apps.trim().toLowerCase();
  $("#app-search").placeholder = `Search ${P.apps.length} apps`;
  const list = q ? P.apps.filter((a) => a.name.toLowerCase().includes(q)) : P.apps;
  $("#apps-hint-text").textContent = app.streaming === "ready"
    ? "Session running: each app opens in its own window here while your phone stays free."
    : "Click an app to open it in its own window here. Your phone asks to start casting — tap Start (pick “One app” or “Entire screen”) and keep it unlocked. Control it with your mouse and keyboard.";
  $("#appgrid").innerHTML = list.map((a) => `<button class="apptile" data-app="${esc(a.pkg)}" title="Open ${esc(a.name)} on this PC">
    ${a.icon ? `<img src="data:image/png;base64,${a.icon}" alt="">` : avatar(a.name, 58)}<span>${esc(a.name)}</span></button>`).join("") ||
    `<div class="empty" style="grid-column:1/-1">${icon("screen")}<b style="color:var(--text)">Loading apps…</b><span>Fetching the app list from your phone.</span></div>`;
}

// ---------- Clipboard & notes ----------
function renderClipboard() {
  const q = P.search.clips.trim().toLowerCase();
  const list = q ? app.clips.filter((c) => c.text.toLowerCase().includes(q)) : app.clips;
  $("#clips").innerHTML = list.map((c) => `<div class="rowcard"><span class="src${c.fromPhone ? " phone" : ""}">${icon(c.fromPhone ? "phone" : "laptop")}</span>
    <div class="body"><p>${esc(c.text)}</p><small>${c.fromPhone ? "From phone" : "From PC"} · ${esc(fmtTime(c.date))}</small></div>
    <button class="iconbtn sm" data-clip-copy="${c.id}" title="Copy again">${icon("clipboard")}</button>
    <button class="iconbtn sm" data-clip-remove="${c.id}" title="Remove">${icon("close")}</button></div>`).join("") ||
    `<div class="empty">${icon("clipboard")}<b style="color:var(--text)">Nothing copied yet</b><span>Everything you copy on this PC, or send from the phone, shows up here. Click one to copy it again.</span></div>`;
  const note = $("#note");
  if (document.activeElement !== note && note.value !== app.note) note.value = app.note;
  $("#note-count").textContent = `${note.value.length} chars`;
}

// ---------- Device ----------
function renderDevice() {
  const level = app.battery ?? 0, d = P.device;
  const color = level <= 15 && !app.charging ? "var(--red)" : app.charging ? "var(--green)" : "var(--accent)";
  const circ = 2 * Math.PI * 45;
  const loc = P.location;
  const other = d ? Math.max(0, d.total - d.free - d.images - d.videos) : 0;
  const seg = (c, n) => d ? `<i style="background:${c};width:${(100 * n / Math.max(d.total, 1)).toFixed(2)}%"></i>` : "";
  const leg = (c, t, n) => `<div><i style="background:${c}"></i><div><b>${t}</b><small>${bytes(n)}</small></div></div>`;
  let map;
  if (loc) {
    const span = Math.max(0.004, loc.acc / 111000 * 3);
    map = `<iframe src="https://www.openstreetmap.org/export/embed.html?bbox=${loc.lon - span},${loc.lat - span},${loc.lon + span},${loc.lat + span}&layer=mapnik&marker=${loc.lat},${loc.lon}" title="Map"></iframe>`;
  } else {
    map = `<div>${icon(P.locating ? "search" : "pin")}<br>${esc(P.locating ? "Asking your phone…" : P.locationError || "Press Locate to see where your phone is.")}</div>`;
  }
  $("#device").innerHTML = `<div class="devgrid">
    <div class="dcard card" style="flex-direction:row;align-items:center;gap:20px;margin:0">
      <div class="gauge"><svg viewBox="0 0 100 100"><circle cx="50" cy="50" r="45" fill="none" stroke="rgba(255,255,255,.1)" stroke-width="10"/>
        <circle cx="50" cy="50" r="45" fill="none" stroke="${color}" stroke-width="10" stroke-linecap="round" stroke-dasharray="${(circ * level / 100).toFixed(1)} ${circ.toFixed(1)}"/></svg>
        <div style="text-align:center"><b>${app.battery != null ? app.battery + "%" : "–"}</b>${app.charging ? `<div style="color:var(--green);font-size:11px">⚡</div>` : ""}</div></div>
      <div style="display:flex;flex-direction:column;gap:6px;flex:1">
        <h3>Battery</h3><span class="secondary" style="font-size:13px">${app.charging ? "Charging" : "On battery"}</span>
        ${app.eta ? `<b style="color:var(--accent);font-size:13px">${icon("clock")} ${esc(app.eta)}</b>` : `<span class="caption">Time estimate appears after a few minutes</span>`}
        <div class="chkrow">Alerts<button class="switch" role="switch" aria-checked="${!!app.settings.batteryAlerts}" data-setting="batteryAlerts"></button></div>
      </div></div>
    <div class="dcard card" style="margin:0"><h3>Remote tools</h3>
      <div class="tools">
        <button class="tool${d && d.torch ? " on" : ""}" data-cmd="torch">${icon("torch")}Flashlight</button>
        <button class="tool${app.ringing ? " on" : ""}" data-cmd="ring">${icon("bell-waves")}${app.ringing ? "Stop ringing" : "Ring phone"}</button>
        <button class="tool${app.dnd ? " on" : ""}" data-cmd="dnd">${icon("moon")}Do Not Disturb</button>
      </div>
      <div class="segwide">${[["normal", "speaker", "Ring"], ["vibrate", "phone", "Vibrate"], ["silent", "mic-off", "Silent"]].map(([m, i, t]) =>
        `<button class="${d && d.ringer === m ? "sel" : ""}" data-ringer="${m}">${t}</button>`).join("")}</div>
    </div></div>
    <div class="dcard card"><div style="display:flex;align-items:center;gap:10px"><h3 style="flex:1">Find my phone</h3>
      ${loc ? `<span class="caption">Updated ${esc(ago(loc.time))} · ±${Math.round(loc.acc)} m</span>` : ""}
      <button class="pill sm prominent" data-cmd="locate"${P.locating ? " disabled" : ""}>${icon("pin")}Locate</button>
      ${loc ? `<button class="pill sm" data-cmd="open-map">${icon("compass")}Open in Maps</button>` : ""}</div>
      <div class="map">${map}</div>
      ${loc && P.locationError ? `<span class="caption" style="color:var(--orange)">${esc(P.locationError)}</span>` : ""}</div>
    <div class="dcard card"><div style="display:flex;align-items:center"><h3 style="flex:1">Storage</h3>
      ${d ? `<span class="caption">${bytes(d.free)} free of ${bytes(d.total)}</span>` : ""}</div>
      ${d ? `<div class="bar14">${seg("var(--accent)", d.images)}${seg("var(--accent2)", d.videos)}${seg("rgba(142,142,147,.7)", other)}</div>
        <div class="legend">${leg("var(--accent)", "Photos", d.images)}${leg("var(--accent2)", "Videos", d.videos)}${leg("rgba(142,142,147,.7)", "Apps & other", other)}${leg("rgba(255,255,255,.15)", "Free", d.free)}</div>` : `<span class="caption">Loading…</span>`}
      <hr><b style="font-size:13px">Biggest videos — clear space from here</b>
      ${P.biggest.length ? P.biggest.map((f) => `<div class="big">
        <button style="display:flex;gap:12px;align-items:center;flex:1;min-width:0;text-align:left" data-big="${f.id}" title="Play this video">
          <span class="thumb">${f.thumb ? `<img src="data:image/jpeg;base64,${f.thumb}" alt="">` : ""}${icon("play")}</span>
          <span style="min-width:0"><b style="font-size:13px;display:block;white-space:nowrap;overflow:hidden;text-overflow:ellipsis">${esc(f.name)}</b>
          <small class="secondary">${bytes(f.size)} · ${mmss(f.duration / 1000)} · ${esc(ago(f.date))}</small></span></button>
        <button class="iconbtn sm" data-save="${f.id}" title="Save to Downloads">${icon("download")}</button>
        <button class="iconbtn sm red" data-delete="${f.id}" title="Delete from phone">${icon("trash")}</button></div>`).join("") : `<span class="caption">Nothing to show.</span>`}
    </div>`;
}

// ---------- History and the live panel ----------
function renderHistory() {
  const q = P.search.history.trim().toLowerCase();
  const list = q ? app.history.filter((i) => [i.app, i.title, i.text].some((s) => (s || "").toLowerCase().includes(q))) : app.history;
  $("#history").innerHTML = list.slice(0, 300).map((i) => `<div class="hrow">${avatar(i.app, 38)}<div class="body">
    <div class="meta"><span>${esc(i.app)}</span><span>${esc(fmtStamp(i.date))}</span></div>
    ${i.title ? `<b>${esc(i.title)}</b>` : ""}<p>${esc(i.text)}</p></div></div>`).join("") ||
    `<div class="empty">${icon("history")}<b style="color:var(--text)">Nothing here yet</b><span>Every notification from your phone is kept here, encrypted on this PC.</span></div>`;
}
function renderPanel() {
  const feed = app.history.filter((i) => i.active);
  $("#feed-count").textContent = feed.length;
  $("#feed-count").style.display = feed.length ? "" : "none";
  const pb = $("#panel-badge");
  pb.textContent = Math.min(feed.length, 99);
  pb.style.display = !P.panel && feed.length ? "" : "none";
  const drafts = {};
  $$(".reply input").forEach((i) => { drafts[i.dataset.key] = { v: i.value, f: document.activeElement === i }; });
  $("#feed").innerHTML = feed.map((i) => `<div class="ncard">${avatar(i.app, 32)}<div class="body">
    <div class="meta">${esc(i.app)}<span>${esc(fmtTime(i.date))}</span><button data-dismiss="${esc(i.key || "")}" title="Dismiss">${icon("close")}</button></div>
    ${i.title ? `<b>${esc(i.title)}</b>` : ""}<p>${esc(i.text)}</p>
    ${i.canReply && i.key ? `<div class="reply"><input placeholder="Reply" data-key="${esc(i.key)}"><button class="send" data-reply="${esc(i.key)}">${icon("send")}</button></div>` : ""}
    </div></div>`).join("") || `<div class="empty" style="padding:60px 10px">${icon("bell-off")}<b style="color:var(--text)">All caught up</b><span>New phone notifications show up here.</span></div>`;
  for (const [k, d] of Object.entries(drafts)) {
    const i = $(`.reply input[data-key="${CSS.escape(k)}"]`);
    if (i) { i.value = d.v; if (d.f) i.focus(); }
  }
}
function sendReply(key) {
  const i = $(`.reply input[data-key="${CSS.escape(key)}"]`);
  const text = i ? i.value.trim() : "";
  if (!text) return;
  tx({ t: "reply", id: key, text });
  i.value = "";
  toast("Reply sent");
}

// ---------- Settings ----------
function sw(key, on, label) { return `<div class="chkrow"><span>${label}</span><button class="switch" role="switch" aria-checked="${!!on}" data-setting="${key}" aria-label="${esc(label)}"></button></div>`; }
function section(title, ic, note, body) {
  return `<div class="section card"><div class="section-head"><div class="badge">${icon(ic)}</div><h3>${title}</h3></div>${note ? `<p class="note">${note}</p>` : ""}${body}</div>`;
}
function renderSettings() {
  const s = app.settings, r = app.rules;
  const seen = [...new Set(app.history.map((i) => i.app).filter(Boolean).concat(r.mutedApps))].sort();
  const html = [
    section("Appearance", "palette", "Pick a theme. It changes the colours and the small drawings in the corner, here and on the phone.",
      `<div class="themes">${THEMES.map((t) => `<button class="theme${t.id === app.theme ? " sel" : ""}" data-theme="${t.id}">
        <span class="sw" style="background:${t.bg}"><canvas data-art="${t.id}"></canvas><span class="lines"><span style="display:flex;gap:6px;align-items:center"><i style="width:10px;height:10px;border-radius:50%;background:${t.accent}"></i><i style="width:44px;background:rgba(255,255,255,.5)"></i></span><i style="width:70px;background:rgba(255,255,255,.2)"></i></span></span>${esc(t.name)}</button>`).join("")}</div>`),
    section("Notifications", "bell", "Muted apps and words still land in History — they just don't pop up or make a sound on this PC.",
      (seen.length ? `<div style="display:grid;grid-template-columns:repeat(auto-fill,minmax(220px,1fr));gap:2px 16px">${seen.map((a) => sw("mute:" + a, r.mutedApps.includes(a), "Mute " + esc(a))).join("")}</div>`
        : `<span class="caption">Apps appear here once they've sent you a notification.</span>`) +
      sw("replyPopup", r.replyPopup, "Show a reply box in the corner for chat messages") +
      `<p class="note">WhatsApp, Instagram and other chat apps pop up in the top-right with a text box ready to type in. It replaces the Windows notification for those apps and goes away by itself after a few seconds unless you're using it.</p>` +
      sw("smartReplies", r.smartReplies, "Offer 3 suggested replies on chat notifications") +
      `<p class="note">${esc(app.smartReplyStatus || "Written on this PC by a local model. Nothing is sent anywhere.")}</p><hr>
      <b style="font-size:12.5px">Mute anything containing</b>
      <div class="tagrow">${r.keywords.map((k) => `<button class="tag" data-unkeyword="${esc(k)}">${esc(k)}${icon("close")}</button>`).join("")}
        <input class="field" id="keyword" placeholder="Add a word, e.g. offer, sale" style="max-width:260px"></div>`),
    section("Messages", "message", "",
      sw("readAloud", s.readAloud, "Read new messages aloud") +
      `<p class="note">Messages from chat apps are spoken as they arrive. You can also right-click any message and choose Read aloud.</p><hr>
      <b style="font-size:12.5px">Quick replies</b>${app.quickReplies.map((q) => `<div class="chkrow"><span>${esc(q)}</span><button class="iconbtn sm" style="margin-left:auto" data-unquick="${esc(q)}">${icon("close")}</button></div>`).join("")}
      <div style="display:flex;gap:8px"><input class="field" id="new-quick" placeholder="New quick reply"><button class="pill sm" data-cmd="quick-add">Add</button></div>`),
    section("Missed calls", "call-in", "When you miss or decline a call, the phone texts the caller back by itself — even with this PC off. Each number is texted at most once every 4 hours.",
      sw("ar:on", P.autoreply.on, "Text back when I miss a call") +
      `<textarea class="field" id="ar-text" rows="3">${esc(P.autoreply.text)}</textarea>
      <div style="display:flex;align-items:center;gap:8px"><span class="caption" style="flex:1">{name} becomes the contact's first name.</span><button class="pill sm" data-cmd="ar-save">Save message</button></div>` +
      sw("ar:contactsOnly", P.autoreply.contactsOnly, "Only saved contacts") + sw("ar:declined", P.autoreply.declined, "Also when I decline")),
    section("Phone", "phone", "",
      sw("batteryAlerts", s.batteryAlerts, "Battery alerts (fully charged, running low)") +
      sw("focusDnd", s.focusDnd, "When Do Not Disturb is on for this PC, turn on Do Not Disturb on the phone")),
    section("Lock when my phone walks away", "bluetooth", "",
      sw("presence", s.presence, esc(app.presence || "Lock this PC when the phone goes out of range")) +
      `<div class="chkrow"><span>Lock when phone is</span><select class="field" id="presence-range" style="margin-left:auto">
        ${[["-65", "Close (a few steps)"], ["-75", "Medium (other side of the room)"], ["-85", "Far (another room)"]].map(([v, t]) => `<option value="${v}"${String(s.presenceRange || -75) === v ? " selected" : ""}>${t}</option>`).join("")}</select></div>
      <p class="note">Locks after 20 s without keyboard/mouse use while the phone is away; wakes the screen when it's back. Needs Bluetooth on this PC and “Nearby devices” allowed on the phone.</p>`),
    section("Phone apps & screen", "screen", "",
      `<div class="row-btns"><button class="pill sm prominent" data-cmd="tab" data-tab="apps">Open apps…</button><button class="pill sm" data-cmd="mirror">Show phone screen</button>${app.streaming === "missing" ? "" : `<button class="pill sm" data-cmd="stream-setup">One-time setup…</button>`}</div>
      <p class="note">${esc(app.streamingNote || "")}</p>`),
    section("Phone as microphone", "mic", "",
      `<div class="chkrow"><i class="dot${app.micActive ? "" : ""}" style="background:${app.micActive ? "var(--red)" : "rgba(235,235,245,.3)"}"></i><span>${esc(app.micStatus)}</span></div>
      <p class="note">Start it from the phone (🎙 Mic to computer). It plays into the free VB-CABLE virtual audio device — install it once from vb-audio.com/Cable, then pick “CABLE Output” as the microphone in Zoom, Meet or Teams.</p>`),
    section("Screenshots", "photo", "", sw("screenshots", s.screenshots, "Send new PC screenshots to the phone") +
      `<p class="note">Phone screenshots always arrive here automatically (copied, and saved in Downloads).</p>`),
    section("Away from home", "compass", "Install Tailscale (free) on this PC and the phone and sign in with the same account. PhoneBridge then reconnects over it whenever you're not on the same Wi-Fi — college, office, anywhere.",
      `<div class="row-btns"><button class="pill sm" data-url="https://tailscale.com/download">Get Tailscale</button></div>`),
    section("Right-click menu", "share", "", `<p class="note">In File Explorer, right-click files → Send to → PhoneBridge. PhoneBridge adds itself to the Send to menu when it starts.</p>`),
    section("Updates", "download", "PhoneBridge keeps itself up to date from GitHub, like the phone app. It checks when it opens, when the phone connects and every few hours.",
      `<div class="chkrow"><span>${app.update ? `Version ${esc(app.update)} is available (you have ${esc(app.version)})` : `You have version ${esc(app.version)}`}</span></div>
      <div class="row-btns">${app.update ? `<button class="pill sm prominent" data-cmd="update-install">${icon("download")}Update now</button>` : ""}<button class="pill sm" data-cmd="update-check">Check for updates</button></div>`),
    section("This PC", "laptop", "", sw("autostart", s.autostart, "Start with Windows") +
      `<div class="row-btns"><button class="pill sm destructive" data-cmd="forget-ask">${icon("phone-slash")}Forget phone</button><button class="pill sm" data-cmd="open-log">Open log</button><button class="pill sm" data-cmd="quit">Quit PhoneBridge</button></div>
      <p class="note">PhoneBridge for Windows ${esc(app.version)}</p>`),
  ].join("");
  const scroll = $("#t-settings .setscroll").scrollTop;
  $("#settings").innerHTML = html;
  $("#t-settings .setscroll").scrollTop = scroll;
  $$("canvas[data-art]").forEach((c) => drawThemeArt(c, c.dataset.art));
}

// ---------- Palette ----------
function paletteItems() {
  const q = P.palette.q.trim(), ql = q.toLowerCase();
  const out = [];
  const m = (s) => !ql || String(s).toLowerCase().includes(ql);
  const action = (ic, title, sub, run) => { if (m(title) || m(sub)) out.push({ ic, title, sub, group: "Actions", run }); };
  const d = q.replace(/[\s-]/g, "");
  if (/^\+?\d{3,}$/.test(d)) out.push({ ic: "call", title: `Call ${q}`, sub: "From your phone", group: "Actions", run: () => dial(q) });
  action("bell-waves", app.ringing ? "Stop ringing my phone" : "Ring my phone", "Find it nearby", () => send("ring"));
  action("moon", app.dnd ? "Turn Do Not Disturb off" : "Turn Do Not Disturb on", "On your phone", () => tx({ t: "phone_dnd", on: !app.dnd }));
  action("share", "Send a file to phone…", "Pick files on this PC", () => send("send-files"));
  action("screen", "Show phone screen", "Mirror it in a window", () => send("mirror"));
  action("refresh", "Reconnect to phone", "Search for it again", () => send("reconnect"));
  action("pin", "Find my phone", "Show it on a map", () => { setTab("device"); locate(); });
  action("torch", P.device && P.device.torch ? "Turn phone flashlight off" : "Turn phone flashlight on", "Remote flashlight", () => tx({ t: "torch", on: !(P.device && P.device.torch) }));
  action("play", "Start photo slideshow", "Full screen, every few seconds", () => { setTab("photos"); setTimeout(startSlideshow, 400); });
  for (const [t, title, ic] of [["messages", "Messages", "message"], ["photos", "Photos & Videos", "photo"], ["calls", "Calls", "call"], ["apps", "Phone Apps", "screen"],
    ["clipboard", "Clipboard history", "clipboard"], ["device", "Device & storage", "phone"], ["settings", "Settings", "sliders"]]) action(ic, `Go to ${title}`, "", () => setTab(t));
  if (!ql) return out;
  const cap = (g, n) => out.filter((x) => x.group === g).length >= n;
  for (const t of P.threads) { if (cap("Messages", 4)) break; if (m(t.name || t.address) || m(t.snippet)) out.push({ ic: "message", title: t.name || t.address, sub: t.snippet, group: "Messages", run: () => { setTab("messages"); selectThread(t.thread); } }); }
  const dq = digits(q);
  for (const c of P.contacts) { if (cap("Contacts", 5)) break; if (m(c.name) || (dq && digits(c.number).includes(dq))) out.push({ ic: "person", title: c.name, sub: `Call ${c.number}`, group: "Contacts", run: () => dial(c.number) }); }
  for (const a of P.apps) { if (cap("Apps", 5)) break; if (m(a.name)) out.push({ ic: "screen", title: a.name, sub: "Open on this PC", group: "Apps", run: () => send("open-app", { pkg: a.pkg, name: a.name }) }); }
  for (const p of P.photos) { if (cap("Photos", 3)) break; if (m(p.name)) out.push({ ic: "photo", title: p.name, sub: p.video ? "Video" : "Photo", group: "Photos", run: () => { setTab("photos"); view(p.id); } }); }
  for (const c of app.clips) { if (cap("Clipboard", 3)) break; if (m(c.text)) out.push({ ic: "clipboard", title: c.text.slice(0, 80).replace(/\n/g, " "), sub: "Copy again", group: "Clipboard", run: () => send("clip-copy", { id: c.id }) }); }
  return out;
}
function renderPalette() {
  const bg = $("#palette-bg");
  bg.classList.toggle("show", P.palette.open);
  if (!P.palette.open) return;
  const list = paletteItems();
  P.palette.index = Math.min(P.palette.index, Math.max(0, list.length - 1));
  P.palette.list = list;
  $("#palette-items").innerHTML = list.length ? list.map((it, i) => `${i === 0 || list[i - 1].group !== it.group ? `<div class="grp">${it.group}</div>` : ""}
    <button class="it${i === P.palette.index ? " sel" : ""}" data-pi="${i}"><span class="ic">${icon(it.ic)}</span><span class="who"><b>${esc(it.title)}</b>${it.sub ? `<small>${esc(it.sub)}</small>` : ""}</span>${i === P.palette.index ? '<span class="kbd">↵</span>' : ""}</button>`).join("")
    : `<div class="empty" style="padding:30px">No matches</div>`;
  $(".palette .it.sel")?.scrollIntoView({ block: "nearest" });
}
function openPalette(on) { P.palette = { open: on, q: "", index: 0 }; $("#palette-q").value = ""; renderPalette(); if (on) setTimeout(() => $("#palette-q").focus(), 20); }
function runPalette(i) { const it = (P.palette.list || [])[i]; openPalette(false); if (it) it.run(); }

// ---------- Device helpers ----------
function locate() {
  P.locating = true; P.locationError = null;
  tx({ t: "loc_request" });
  clearTimeout(locate.t);
  locate.t = setTimeout(() => { if (P.locating) { P.locating = false; P.locationError = "The phone didn't answer. Check it's awake and connected."; render(); } }, 25000);
  render();
}

// ---------- One-time setup for phone apps in their own windows (StreamingSetupView) ----------
function showStreamSetup() {
  $("#stream-sheet")?.remove();
  document.body.insertAdjacentHTML("beforeend", `<div class="sheet-bg show" id="stream-sheet"><div class="sheet" style="width:420px">
    <h4>Phone apps in their own windows</h4>
    <p>One time only, with the phone on the same Wi-Fi:<br>1. On the phone: Settings → Developer options → Wireless debugging → <b>Pair device with pairing code</b>.<br>
    2. Type the IP address &amp; port and the 6-digit code it shows here.<br>Afterwards PhoneBridge switches Developer options on only while you use a phone app here.</p>
    <input class="field" id="sp-host" placeholder="IP address & port, e.g. 192.168.1.20:37099">
    <input class="field" id="sp-code" placeholder="Pairing code" inputmode="numeric">
    <p id="sp-msg" style="white-space:pre-wrap"></p>
    <div class="row-btns"><button class="pill" data-cmd="stream-close">Close</button><button class="pill prominent" data-cmd="stream-pair">Pair</button></div>
  </div></div>`);
  setTimeout(() => $("#sp-host").focus(), 30);
}

// ---------- Sheet ----------
let sheetOk = null;
function confirmSheet(title, text, ok, run) {
  $("#sheet-title").textContent = title; $("#sheet-text").textContent = text; $("#sheet-ok").textContent = ok;
  sheetOk = run; $("#sheet").classList.add("show");
}

// ---------- Rendering ----------
function render() {
  const tab = P.tab;
  document.body.dataset.tab = tab;
  document.body.classList.toggle("no-panel", !P.panel);
  $("#title").textContent = TITLES[tab];
  $("#chip-name").textContent = app.phoneName || "Phone";
  $("#chip-dot").classList.toggle("on", app.connected);
  $("#chip-status").textContent = app.connected ? "Connected" : "Offline";
  $("#chip-batt").textContent = app.battery != null ? ` · ${app.charging ? "⚡" : ""}${app.battery}%${app.eta ? " · " + app.eta : ""}` : "";
  $$(".quick.needs").forEach((b) => (b.disabled = !app.connected));
  $("#q-dnd").classList.toggle("on", !!app.dnd);
  $("#q-ring").classList.toggle("on", !!app.ringing);
  $("#q-panel").classList.toggle("on", P.panel);
  const unread = P.threads.filter(isUnread).length;
  $("#dock").innerHTML = `<button class="item glyph" data-cmd="tab" data-tab="settings" title="Themes (Settings)"><svg width="26" height="26" viewBox="0 0 28 28"><path d="M5 17.6Q14 -0.4 23 17.6" fill="none" stroke="var(--accent)" stroke-width="2.4" stroke-linecap="round"/><circle cx="5" cy="17.6" r="3" fill="var(--accent)"/><circle cx="23" cy="17.6" r="3" fill="var(--accent)"/></svg></button><i class="sep"></i>` +
    TABS.map(([t, title, ic]) => `<button class="item${tab === t ? " sel" : ""}" data-cmd="tab" data-tab="${t}" title="${title}">${icon(ic)}<span>${title}</span>${t === "messages" && unread ? `<i class="badge">${Math.min(unread, 99)}</i>` : ""}</button>`).join("") +
    `<i class="sep"></i><button class="item${tab === "notifications" ? " sel" : ""}" data-cmd="tab" data-tab="notifications" title="History">${icon("history")}<span>History</span></button>
     <button class="item${tab === "settings" ? " sel" : ""}" data-cmd="tab" data-tab="settings" title="Settings">${icon("sliders")}<span>Settings</span></button>`;
  const online = app.connected || tab === "settings" || tab === "notifications" || tab === "clipboard";
  $$(".tab").forEach((t) => t.classList.toggle("show", online ? t.id === "t-" + tab : t.id === "t-offline"));
  if (!online) return renderPanel();
  ({ messages: renderMessages, photos: renderPhotos, calls: renderCalls, apps: renderApps, clipboard: renderClipboard,
     device: renderDevice, notifications: renderHistory, settings: renderSettings })[tab]();
  renderPanel();
  renderViewer();
}

// ---------- Phone messages ----------
function onPhone(m) {
  switch (m.t) {
    case "sms_threads":
      P.threads = (m.threads || []).map((t) => ({ thread: t.thread, address: t.address || "", name: t.name || null, snippet: t.snippet || "", date: t.date || 0, unread: !!t.unread }));
      if (P.selectedThread == null && P.threads.length && !P.compose) selectThread(P.threads[0].thread);
      else if (P.tab === "messages" && currentThread()) markRead(currentThread());
      break;
    case "sms_thread":
      if (m.thread !== P.selectedThread) return;
      P.messages = (m.messages || []).map((x) => ({ body: x.body || "", date: x.date || 0, mine: !!x.mine }));
      break;
    case "sms_new": {
      tx({ t: "sms_threads_request" });
      const cur = currentThread();
      if (cur && sameNumber(m.address, cur.address)) tx({ t: "sms_thread_request", thread: cur.thread });
      return;
    }
    case "sms_state":
      if (m.on === false) { P.threads = []; P.messages = []; P.selectedThread = null; }
      else if (!P.smsOn && P.tab === "messages") tx({ t: "sms_threads_request" });
      P.smsOn = m.on !== false;
      break;
    case "albums": P.albums = (m.albums || []).map((a) => ({ id: a.id, name: a.name || "Album", count: a.count || 0, cover: a.cover })); break;
    case "photos": {
      if ((m.album || "") !== (P.album || "") || (m.kind || "all") !== P.kind) return; // a late page from another album
      P.loadingPhotos = false;
      P.photoTotal = m.total ?? P.photoTotal;
      const page = (m.items || []).map((p) => ({ id: p.id, date: p.date || 0, name: p.name || "", thumb: p.thumb, video: !!p.video, duration: Math.floor((p.duration || 0) / 1000) }));
      if (!m.offset) P.photos = page;
      else { const known = new Set(P.photos.map((p) => p.id)); P.photos.push(...page.filter((p) => !known.has(p.id))); }
      break;
    }
    case "photos_deleted": {
      const ids = new Set(m.ids || []);
      if (!m.ok) { toast("The phone didn't confirm the deletion."); return; }
      if (P.viewing != null && ids.has(P.viewing)) {
        const i = P.photos.findIndex((p) => p.id === P.viewing);
        const rest = P.photos.filter((p) => !ids.has(p.id));
        P.viewing = i >= 0 && rest.length ? rest[Math.min(i, rest.length - 1)].id : null;
      }
      P.photos = P.photos.filter((p) => !ids.has(p.id));
      P.photoTotal = Math.max(0, P.photoTotal - ids.size);
      ids.forEach((id) => P.selection.delete(id));
      P.biggest = P.biggest.filter((f) => !ids.has(f.id));
      tx({ t: "device_request" });
      toast(`${ids.size} item${ids.size === 1 ? "" : "s"} moved to the phone's trash.`);
      break;
    }
    case "call_log": P.calls = (m.calls || []).map((c) => ({ number: c.number || "", name: c.name || null, type: c.type || "", date: c.date || 0, duration: c.duration || 0 })); break;
    case "contacts": P.contacts = (m.contacts || []).filter((c) => c.name && c.number); break;
    case "autoreply": P.autoreply = { on: !!m.on, text: m.text || "", contactsOnly: m.contactsOnly !== false, declined: m.declined !== false }; break;
    case "device": P.device = { total: m.total || 0, free: m.free || 0, images: m.images || 0, videos: m.videos || 0, torch: !!m.torch, ringer: m.ringer || "normal" }; break;
    case "torch_state": if (P.device) P.device.torch = !!m.on; break;
    case "biggest": P.biggest = (m.items || []).map((f) => ({ id: f.id, name: f.name || "Video", size: f.size || 0, date: f.date || 0, duration: f.duration || 0, thumb: f.thumb })); break;
    case "loc":
      P.locating = false;
      if (typeof m.lat === "number" && typeof m.lon === "number") { P.location = { lat: m.lat, lon: m.lon, acc: m.acc || 50, time: m.time || Date.now() }; P.locationError = null; }
      else P.locationError = m.error === "permission" ? "Allow location on the phone: PhoneBridge → Find my phone, then choose “All the time”."
        : m.error === "off" ? "Location is switched off on the phone." : "No position yet. Make sure location is allowed “all the time” for PhoneBridge, then try again.";
      break;
    case "apps": P.apps = (m.apps || []).filter((a) => a.pkg && a.name); break;
    default: return;
  }
  render();
}

// ---------- Events ----------
document.addEventListener("click", (e) => {
  const t = e.target.closest("button, [data-thread], [data-photo]");
  if (!t) return;
  const ds = t.dataset;
  if (ds.thread) return selectThread(Number(ds.thread));
  if (ds.album !== undefined) { P.album = ds.album || null; return resetPhotos(); }
  if (ds.kind) { P.kind = ds.kind; return resetPhotos(); }
  if (ds.photo) {
    const id = Number(ds.photo);
    if (P.selecting || e.ctrlKey) { P.selecting = true; P.selection.has(id) ? P.selection.delete(id) : P.selection.add(id); return renderPhotos(); }
    return view(id);
  }
  if (ds.key) { $("#dial").value += ds.key; return renderCalls(); }
  if (ds.dial) return dial(ds.dial);
  if (ds.app) return send("open-app", { pkg: ds.app, name: P.apps.find((a) => a.pkg === ds.app)?.name || ds.app });
  if (ds.quick) { const b = $("#composer-text"); b.value = ds.quick; autosize(b); b.focus(); return; }
  if (ds.to) { P.compose.to = ds.to; $(".suggest")?.remove(); $("#compose-to").value = ds.to; $("#composer-text").focus(); return; }
  if (ds.clipCopy) { send("clip-copy", { id: ds.clipCopy }); return toast("Copied — also on your phone's clipboard"); }
  if (ds.clipRemove) return send("clip-remove", { id: ds.clipRemove });
  if (ds.dismiss) return send("history-dismiss", { key: ds.dismiss });
  if (ds.reply) return sendReply(ds.reply);
  if (ds.ringer) return tx({ t: "ringer", mode: ds.ringer });
  if (ds.big) { const f = P.biggest.find((x) => x.id === Number(ds.big)); if (f) { P.extra[f.id] = { id: f.id, date: f.date, name: f.name, thumb: f.thumb, video: true, duration: Math.floor(f.duration / 1000) }; view(f.id); } return; }
  if (ds.save) return send("photos-save", { ids: [Number(ds.save)] });
  if (ds.delete) return askDelete([Number(ds.delete)]);
  if (ds.theme) return send("theme", { id: ds.theme });
  if (ds.unkeyword) return send("rules", { op: "unkeyword", value: ds.unkeyword });
  if (ds.unquick) return send("quick", { op: "remove", value: ds.unquick });
  if (ds.url) return window.open(ds.url, "_blank");
  if (ds.pi) return runPalette(Number(ds.pi));
  if (ds.setting) {
    const key = ds.setting, on = t.getAttribute("aria-checked") !== "true";
    t.setAttribute("aria-checked", String(on));
    if (key.startsWith("mute:")) return send("rules", { op: "mute", value: key.slice(5) });
    if (key.startsWith("ar:")) { P.autoreply[key.slice(3)] = on; return saveAutoReply(); }
    if (key === "replyPopup" || key === "smartReplies") return send("rules", { op: key, value: on });
    return send("set", { key, value: on });
  }
  switch (ds.cmd) {
    case "tab": return setTab(ds.tab);
    case "panel": P.panel = !P.panel; return render();
    case "palette": return openPalette(!P.palette.open);
    case "dnd": return tx({ t: "phone_dnd", on: !app.dnd });
    case "ring": case "send-files": case "mirror": case "reconnect": case "open-log": case "quit": case "stream-setup":
    case "update-install": case "update-check": return send(ds.cmd);
    case "stream-pair": return send("stream-pair", { hostPort: $("#sp-host").value, code: $("#sp-code").value });
    case "stream-close": $("#stream-sheet").remove(); return;
    case "new-message": P.compose = { to: "" }; P.selectedThread = null; return render();
    case "dial-thread": { const th = currentThread(); if (th) dial(th.address); return; }
    case "send-sms": return sendDraft();
    case "quick-save": { const v = ($("#composer-text")?.value || "").trim(); if (v) send("quick", { op: "add", value: v }); return; }
    case "quick-add": { const v = $("#new-quick").value.trim(); if (v) send("quick", { op: "add", value: v }); return; }
    case "photos-refresh": tx({ t: "albums_request" }); return resetPhotos();
    case "photos-select": P.selecting = true; return renderPhotos();
    case "photos-done": P.selecting = false; P.selection.clear(); return renderPhotos();
    case "photos-download": return send("photos-save", { ids: [...P.selection] });
    case "photos-delete": return askDelete([...P.selection]);
    case "slideshow": return startSlideshow();
    case "slide-prev": return stepSlide(-1);
    case "slide-next": return stepSlide(1);
    case "slide-stop": return stopSlideshow();
    case "viewer-close": P.viewing = null; return renderViewer();
    case "viewer-prev": return step(-1);
    case "viewer-next": return step(1);
    case "viewer-open": return send("photo-open", { id: P.viewing });
    case "viewer-ocr": return send("photo-ocr", { id: P.viewing });
    case "viewer-download": return send("photos-save", { ids: [P.viewing] });
    case "viewer-copy": return send("photo-copy", { id: P.viewing });
    case "viewer-delete": return askDelete([P.viewing]);
    case "apps-refresh": P.apps = []; tx({ t: "apps_request" }); return renderApps();
    case "clip-clear": return send("clip-clear");
    case "note-todo": { const n = $("#note"); n.value += (n.value === "" || n.value.endsWith("\n") ? "" : "\n") + "☐ "; return noteEdited(); }
    case "note-copy": send("copy-text", { text: $("#note").value }); return toast("Note copied");
    case "history-clear": return confirmSheet("Clear notification history?", "Everything in History is deleted from this PC.", "Clear", () => send("history-clear"));
    case "feed-clear": return send("feed-clear");
    case "torch": return tx({ t: "torch", on: !(P.device && P.device.torch) });
    case "locate": return locate();
    case "open-map": if (P.location) window.open(`https://www.google.com/maps/search/?api=1&query=${P.location.lat},${P.location.lon}`, "_blank"); return;
    case "ar-save": P.autoreply.text = $("#ar-text").value; return saveAutoReply();
    case "forget-ask": return confirmSheet(`Forget ${app.phoneName || "this phone"}?`, "You'll need to pair again: on the phone, open Computers → Pair a new computer.", "Forget", () => send("forget"));
    case "sheet-cancel": $("#sheet").classList.remove("show"); return;
    case "sheet-ok": $("#sheet").classList.remove("show"); if (sheetOk) sheetOk(); return;
  }
});
function saveAutoReply() { const a = P.autoreply; tx({ t: "autoreply_set", on: a.on, text: a.text, contactsOnly: a.contactsOnly, declined: a.declined }); toast("Saved on the phone"); }

let noteTimer = null;
function noteEdited() { $("#note-count").textContent = `${$("#note").value.length} chars`; clearTimeout(noteTimer); noteTimer = setTimeout(() => send("note", { text: $("#note").value }), 400); }

document.addEventListener("input", (e) => {
  const id = e.target.id;
  if (id === "thread-search") { P.search.threads = e.target.value; renderMessages(); }
  else if (id === "contact-search") { P.search.contacts = e.target.value; renderCalls(); }
  else if (id === "app-search") { P.search.apps = e.target.value; renderApps(); }
  else if (id === "clip-search") { P.search.clips = e.target.value; renderClipboard(); }
  else if (id === "hist-search") { P.search.history = e.target.value; renderHistory(); }
  else if (id === "composer-text") { P.drafts[P.selectedThread || "new"] = e.target.value; autosize(e.target); }
  else if (id === "dial") renderCalls();
  else if (id === "note") noteEdited();
  else if (id === "palette-q") { P.palette.q = e.target.value; P.palette.index = 0; renderPalette(); }
});
document.addEventListener("change", (e) => { if (e.target.id === "presence-range") send("set", { key: "presenceRange", value: Number(e.target.value) }); });
document.addEventListener("keydown", (e) => {
  const id = e.target.id;
  if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === "k") { e.preventDefault(); return openPalette(!P.palette.open); }
  if (P.palette.open) {
    if (e.key === "Escape") return openPalette(false);
    if (e.key === "ArrowDown" || e.key === "ArrowUp") { e.preventDefault(); P.palette.index = Math.max(0, P.palette.index + (e.key === "ArrowDown" ? 1 : -1)); return renderPalette(); }
    if (e.key === "Enter") { e.preventDefault(); return runPalette(P.palette.index); }
    return;
  }
  if (id === "composer-text" && e.key === "Enter" && !e.shiftKey) { e.preventDefault(); return sendDraft(); }
  if (e.target.closest(".reply") && e.key === "Enter") return sendReply(e.target.dataset.key);
  if (id === "dial" && e.key === "Enter") return dial(e.target.value);
  if (id === "keyword" && e.key === "Enter") { send("rules", { op: "keyword", value: e.target.value }); e.target.value = ""; return; }
  if (id === "new-quick" && e.key === "Enter") { const v = e.target.value.trim(); if (v) send("quick", { op: "add", value: v }); return; }
  if (P.slide) { if (e.key === "Escape") stopSlideshow(); if (e.key === "ArrowRight") stepSlide(1); if (e.key === "ArrowLeft") stepSlide(-1); return; }
  if (P.viewing != null && !/INPUT|TEXTAREA/.test(e.target.tagName)) {
    if (e.key === "Escape") { P.viewing = null; return renderViewer(); }
    if (e.key === "ArrowRight") return step(1);
    if (e.key === "ArrowLeft") return step(-1);
    if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === "c") return send("photo-copy", { id: P.viewing });
  }
  if (e.key === "Escape" && $("#sheet").classList.contains("show")) $("#sheet").classList.remove("show");
});
document.addEventListener("contextmenu", (e) => {
  const b = e.target.closest(".bubble");
  if (b) { e.preventDefault(); send("speak", { text: b.dataset.text }); toast("Reading aloud"); }
});
$("#photo-scroller").addEventListener("scroll", (e) => { const s = e.target; if (s.scrollTop + s.clientHeight > s.scrollHeight - 600) loadMorePhotos(); });
$("#dial-back").addEventListener("click", () => { const d = $("#dial"); d.value = d.value.slice(0, -1); renderCalls(); });
$("#dial-call").addEventListener("click", () => dial($("#dial").value));
$("#palette-bg").addEventListener("click", (e) => { if (e.target.id === "palette-bg") openPalette(false); });

// Files dropped anywhere on the window go to the phone.
let dragDepth = 0;
const hasFiles = (e) => e.dataTransfer && Array.from(e.dataTransfer.types || []).includes("Files");
document.addEventListener("dragenter", (e) => { if (hasFiles(e)) { e.preventDefault(); dragDepth++; document.body.classList.add("dragging"); } });
document.addEventListener("dragleave", () => { if (--dragDepth <= 0) { dragDepth = 0; document.body.classList.remove("dragging"); } });
document.addEventListener("dragover", (e) => { if (hasFiles(e)) e.preventDefault(); });
document.addEventListener("drop", (e) => {
  e.preventDefault(); dragDepth = 0; document.body.classList.remove("dragging");
  if (e.dataTransfer.files.length && host && host.postMessageWithAdditionalObjects) host.postMessageWithAdditionalObjects({ cmd: "drop" }, e.dataTransfer.files);
});

// ---------- From the app ----------
onHost((m) => {
  switch (m.type) {
    case "state": {
      const was = app.connected;
      app = Object.assign(app, m.state);
      applyTheme(app.theme);
      if (app.connected && !was) refresh(P.tab);
      render();
      break;
    }
    case "phone": onPhone(m.m); break;
    case "photo": // a full-size photo or video is in the cache
      P.downloaded[m.id] = m.url; delete P.progress[m.id];
      if (P.viewing === m.id) renderViewer();
      if (P.slide) stepSlide(0);
      break;
    case "progress":
      if (P.progress[m.id]) { P.progress[m.id].got = m.got; P.progress[m.id].total = m.total; }
      if (P.viewing === m.id) renderViewer();
      break;
    case "open":
      setTab(m.tab || P.tab);
      if (m.tab === "messages" && m.number === "") { P.compose = { to: "" }; P.selectedThread = null; render(); }
      if (m.tab === "messages" && m.number) {
        const th = P.threads.find((t) => sameNumber(t.address, m.number));
        if (th) selectThread(th.thread); else { P.compose = { to: m.number }; render(); }
      }
      if (m.tab === "photos" && m.slideshow) setTimeout(startSlideshow, 500);
      break;
    case "toast": toast(m.text); break;
    case "stream-setup": showStreamSetup(); break;
    case "stream-message": { const el = $("#sp-msg"); if (el) el.textContent = m.text; else toast(m.text); break; }
  }
});

// Backdrop artwork for the current theme (top-right), drawn once per theme change.
let artTheme = null;
function drawBackdrop() {
  if (artTheme === app.theme) return;
  artTheme = app.theme;
  drawThemeArt($("#art"), app.theme, true);
}
setInterval(drawBackdrop, 500);

render();
send("ready");

// ---------- Demo (tools/inline-ui.py phone.html <tab>) ----------
if (!host && window.__demo) {
  const now = Date.now(), min = 60000, day = 86400000;
  app = Object.assign(app, { connected: true, phoneName: "Pixel 9", battery: 76, charging: true, eta: "Full in 35 min", version: "0.23.0",
    quickReplies: ["Can't talk now — I'll call you back", "On my way", "OK 👍", "Thanks!"], settings: { batteryAlerts: true, autostart: true, screenshots: true, presence: false },
    streamingNote: "Apps open by showing the phone's screen. For apps in their own windows, install scrcpy (winget install Genymobile.scrcpy) and do the one-time setup.",
    history: [
      { key: "a", active: true, canReply: true, app: "WhatsApp", title: "Maya", text: "Are we still on for dinner tonight? 🍝", date: now - 2 * min },
      { key: "b", active: true, app: "Gmail", title: "Your order has shipped", text: "Track your package: arriving Thursday.", date: now - 40 * min },
      { key: "c", active: false, app: "Calendar", title: "Standup", text: "In 10 minutes", date: now - 3 * 3600000 }],
    clips: [{ id: "1", text: "https://example.com/trip-itinerary", date: now - 5 * min, fromPhone: true }, { id: "2", text: "Meeting notes: ship v2 on Friday", date: now - 50 * min, fromPhone: false }],
    note: "☐ Groceries\n☐ Call the bank\nIdeas for the weekend…", theme: "midnight" });
  P.threads = [
    { thread: 1, address: "+14155550134", name: "Maya Patel", snippet: "See you at 8! I booked the table by the window.", date: now - 3 * min, unread: true },
    { thread: 2, address: "+14155550199", name: "Dad", snippet: "Call me when you're free", date: now - 2 * 3600000, unread: false },
    { thread: 3, address: "56789", name: null, snippet: "Your verification code is 482193", date: now - day, unread: false }];
  P.selectedThread = 1;
  P.messages = [{ body: "See you at 8! I booked the table by the window.", date: now - 3 * min, mine: false }, { body: "Perfect, thank you 🙌", date: now - 6 * min, mine: true },
    { body: "Dinner tonight?", date: now - 50 * min, mine: false }];
  P.contacts = [{ name: "Maya Patel", number: "+1 415 555 0134" }, { name: "Dad", number: "+1 415 555 0199" }, { name: "Alex Kim", number: "+1 415 555 0101" }];
  P.calls = [{ number: "+14155550134", name: "Maya Patel", type: "in", date: now - 3600000, duration: 312 }, { number: "+14155550111", name: null, type: "missed", date: now - day, duration: 0 },
    { number: "+14155550199", name: "Dad", type: "out", date: now - 2 * day, duration: 64 }];
  P.device = { total: 256e9, free: 92e9, images: 38e9, videos: 61e9, torch: false, ringer: "vibrate" };
  P.apps = ["WhatsApp", "Instagram", "Spotify", "Maps", "Chrome", "Camera", "YouTube", "Gmail"].map((n) => ({ pkg: n.toLowerCase(), name: n }));
  P.albums = [{ id: "cam", name: "Camera", count: 2412 }, { id: "ss", name: "Screenshots", count: 318 }];
  P.photoTotal = 2412;
  const tile = (h) => { const c = document.createElement("canvas"); c.width = c.height = 120; const x = c.getContext("2d"); const g = x.createLinearGradient(0, 0, 120, 120); g.addColorStop(0, `hsl(${h},60%,55%)`); g.addColorStop(1, `hsl(${h + 40},60%,30%)`); x.fillStyle = g; x.fillRect(0, 0, 120, 120); return c.toDataURL("image/jpeg").split(",")[1]; };
  P.photos = Array.from({ length: 14 }, (_, i) => ({ id: i + 1, date: now - (i < 5 ? 0 : i < 10 ? day : 3 * day), name: `IMG_${2400 + i}.jpg`, thumb: tile(i * 25), video: i % 5 === 2, duration: 73 }));
  P.tab = window.__demo === "default" ? "messages" : window.__demo;
  render();
}
