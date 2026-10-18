// Shomer Control – web dashboard for security companies (static page; data + rules live in Firestore).
import { initializeApp } from "https://www.gstatic.com/firebasejs/11.0.2/firebase-app.js";
import {
  getAuth, onAuthStateChanged, signInWithPopup, GoogleAuthProvider, signOut, connectAuthEmulator, signInWithCredential,
} from "https://www.gstatic.com/firebasejs/11.0.2/firebase-auth.js";
import {
  getFirestore, connectFirestoreEmulator, doc, getDoc, onSnapshot, collection, query, orderBy, limit, where,
  setDoc, updateDoc, addDoc, writeBatch, increment, serverTimestamp, getDocs, deleteDoc,
} from "https://www.gstatic.com/firebasejs/11.0.2/firebase-firestore.js";
import { getStorage, connectStorageEmulator, ref as sref, getDownloadURL, deleteObject } from "https://www.gstatic.com/firebasejs/11.0.2/firebase-storage.js";
import { firebaseConfig, ADMIN_EMAIL } from "./config.js";

const EMU = new URLSearchParams(location.search).has("emu");   // end-to-end tests only
const app = initializeApp(EMU ? { apiKey: "demo", projectId: "demo-shomer", authDomain: "localhost", storageBucket: "demo-shomer.appspot.com" } : firebaseConfig);
const auth = getAuth(app);
const db = getFirestore(app);
const storage = getStorage(app);
if (EMU) { connectAuthEmulator(auth, "http://127.0.0.1:9099", { disableWarnings: true }); connectFirestoreEmulator(db, "127.0.0.1", 8080); connectStorageEmulator(storage, "127.0.0.1", 9199); }

const $ = (id) => document.getElementById(id);
const esc = (s) => String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
const fmtTime = (t) => t ? new Date(t).toLocaleString("he-IL", { hour: "2-digit", minute: "2-digit", day: "2-digit", month: "2-digit" }) : "";
const ago = (ms) => { const m = Math.max(1, Math.round(ms / 60000)); return m < 60 ? `לפני ${m} דק׳` : `לפני ${Math.round(m / 60)} שע׳`; };
const STALE_MS = 120000;
const ALARMS = ["fall", "sos_call"];
const EVENT_LABEL = {
  fall: "⚠ נפילה", fall_ok: "\"אני בסדר\" אחרי נפילה", sos_call: "⚠ חיוג חירום", rec_start: "התחיל תיעוד", rec_saved: "תיעוד נשמר",
  shift_start: "תחילת משמרת", shift_end: "סוף משמרת", live_start: "צפייה חיה", live_end: "סוף צפייה",
};
const TRIGGER = { BUTTON: "כפתור", FLOATING: "כפתור צף", NOTIFICATION: "התראה", VOLUME: "כפתור שמע", VOICE: "קול", SHAKE: "ניעור", FALL: "נפילה", REMOTE: "חדר בקרה" };

let me = null, orgId = null, org = null;
let members = [], devices = {}, events = [];
let unsub = [];
let selected = null;
let map = null; const markers = {};
let firstEvents = true; const seenAlarm = new Set(); let alarmEvent = null;
let viewer = null;

// ------------------------------------------------------------------ auth
$("btn-google").onclick = async () => {
  $("login-msg").textContent = "";
  try { await signInWithPopup(auth, new GoogleAuthProvider()); }
  catch (e) { $("login-msg").textContent = e.code === "auth/unauthorized-domain" ? "הדומיין של האתר עוד לא אושר ב־Firebase (Authentication ← Settings ← Authorized domains)." : "ההתחברות נכשלה: " + (e.code || e.message); }
};
if (EMU) {
  $("emu-login").classList.remove("hidden");
  $("emu-login").onsubmit = async (e) => {
    e.preventDefault();
    const email = $("emu-email").value.trim();
    await signInWithCredential(auth, GoogleAuthProvider.credential(JSON.stringify({ sub: "u" + email.length + email.charCodeAt(0), email, email_verified: true })));
  };
}
document.querySelectorAll("[data-signout]").forEach((b) => (b.onclick = () => signOut(auth)));

onAuthStateChanged(auth, async (u) => {
  cleanup();
  me = u?.email?.toLowerCase() || null;
  show(me ? null : "login");
  if (!me) return;
  $("me-email").textContent = me;
  try {
    const idx = await getDoc(doc(db, "shomer_index", me));
    if (!idx.exists() || idx.data().role !== "manager") return noOrg();
    orgId = idx.data().orgId;
    start();
  } catch (e) { console.error(e); noOrg(); }
});

function noOrg() {
  show("noorg");
  $("noorg-email").textContent = me;
  $("admin-entry").classList.toggle("hidden", me !== ADMIN_EMAIL);
  $("btn-admin").onclick = () => { show("app"); orgId = null; $("tab-admin").classList.remove("hidden"); tab("admin"); loadAdmin(); };
}

function show(which) {
  ["login", "noorg", "app"].forEach((id) => $(id).classList.toggle("hidden", id !== which));
}

function cleanup() { unsub.forEach((f) => f()); unsub = []; stopLive(); }

// ------------------------------------------------------------------ live data
function start() {
  show("app");
  $("tab-admin").classList.toggle("hidden", me !== ADMIN_EMAIL);
  initMap();
  const o = doc(db, "shomer_orgs", orgId);
  unsub.push(onSnapshot(o, (s) => { org = s.data(); renderOrg(); }));
  unsub.push(onSnapshot(collection(o, "members"), (qs) => { members = qs.docs.map((d) => ({ email: d.id, ...d.data() })); renderAll(); }));
  unsub.push(onSnapshot(collection(o, "devices"), (qs) => { devices = {}; qs.docs.forEach((d) => (devices[d.id] = d.data())); renderAll(); }));
  unsub.push(onSnapshot(query(collection(o, "events"), orderBy("at", "desc"), limit(150)), (qs) => {
    events = qs.docs.map((d) => ({ id: d.id, ...d.data() }));
    // a NEW fall / emergency call → full-screen alarm with sound
    events.filter((e) => ALARMS.includes(e.type) && !e.ackBy).forEach((e) => {
      if (seenAlarm.has(e.id)) return;
      seenAlarm.add(e.id);
      if (!firstEvents || Date.now() - (e.at || 0) < 10 * 60000) raiseAlarm(e);
    });
    firstEvents = false;
    renderEvents(); renderDetail();
  }));
  unsub.push(onSnapshot(query(collection(o, "recordings"), orderBy("start", "desc"), limit(300)), (qs) => {
    recs = qs.docs.map((d) => ({ id: d.id, ...d.data() }));
    renderRecs();
  }, (e) => console.warn("recordings", e)));
  const t = setInterval(renderAll, 15000); unsub.push(() => clearInterval(t));
}

// ------------------------------------------------------------------ company cloud recordings
let recs = [];
const dur = (ms) => { const s = Math.round((ms || 0) / 1000); return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, "0")}`; };
function renderRecs() {
  const sel = $("rec-guard"), cur = sel.value;
  const guards = [...new Map(recs.map((r) => [r.email, r.name || r.email])).entries()];
  sel.innerHTML = `<option value="">כל המאבטחים</option>` + guards.map(([e, n]) => `<option value="${esc(e)}">${esc(n)}</option>`).join("");
  sel.value = guards.some(([e]) => e === cur) ? cur : "";
  $("rec-note").textContent = `נמחקות אוטומטית אחרי ${org?.policy?.retentionDays || 30} ימים, אלא אם סומנו "שמור"`;
  const shown = recs.filter((r) => !sel.value || r.email === sel.value);
  $("rec-list").innerHTML = shown.length ? shown.map((r) => `
    <div class="item" data-rec="${esc(r.id)}">
      <div class="grow"><div class="t">${esc(r.name || r.email)} · ${esc(TRIGGER[r.trigger] || "")}</div>
        <div class="s">${fmtTime(r.start)} · ${dur(r.durationMs)} · ${Math.round((r.size || 0) / 1048576)} MB${r.keep ? " · <b class='keep'>שמור</b>" : ""}</div></div>
      <button class="btn primary" data-play="${esc(r.id)}">▶ צפייה</button>
      <button class="btn" data-keep="${esc(r.id)}">${r.keep ? "בטל שמירה" : "שמור"}</button>
      <button class="btn" data-del="${esc(r.id)}">מחק</button>
    </div>`).join("") : `<p class="muted">אין עדיין הקלטות בענן. תיעודים של המאבטחים יופיעו כאן אחרי שיעלו.</p>`;
  document.querySelectorAll("[data-play]").forEach((b) => (b.onclick = () => playRec(recs.find((r) => r.id === b.dataset.play))));
  document.querySelectorAll("[data-keep]").forEach((b) => (b.onclick = () => {
    const r = recs.find((x) => x.id === b.dataset.keep);
    updateDoc(doc(db, "shomer_orgs", orgId, "recordings", r.id), { keep: !r.keep }).catch(() => alert("הפעולה נכשלה"));
  }));
  document.querySelectorAll("[data-del]").forEach((b) => (b.onclick = () => delRec(recs.find((r) => r.id === b.dataset.del))));
}
$("rec-guard").onchange = renderRecs;
async function playRec(r) {
  if (!r) return;
  try {
    const url = await getDownloadURL(sref(storage, r.path));
    $("rec-player").classList.remove("hidden");
    $("rec-video").src = url;
    $("rec-dl").href = url;
    $("rec-title").textContent = `${r.name || r.email} · ${fmtTime(r.start)}`;
    $("rec-sha").textContent = r.sha256 ? "SHA-256: " + r.sha256 : "";
    $("rec-video").play().catch(() => {});
    window.scrollTo({ top: 0, behavior: "smooth" });
  } catch (e) { console.warn("recording url", e); alert("לא הצלחנו לפתוח את ההקלטה"); }
}
$("rec-close").onclick = () => { $("rec-video").pause(); $("rec-video").removeAttribute("src"); $("rec-player").classList.add("hidden"); };
async function delRec(r) {
  if (!r || !confirm(`למחוק לצמיתות את ההקלטה של ${r.name || r.email} מ־${fmtTime(r.start)}?\nבטלפון של המאבטח היא נשארת.`)) return;
  try { await deleteObject(sref(storage, r.path)); } catch (e) { /* already gone */ }
  try { await deleteDoc(doc(db, "shomer_orgs", orgId, "recordings", r.id)); } catch (e) { alert("המחיקה נכשלה"); }
}

function status(email) {
  const d = devices[email];
  if (!d || !d.onShift) return { cls: "", text: "לא במשמרת", online: false };
  if (Date.now() - (d.updatedAt || 0) > STALE_MS) return { cls: "stale", text: "אין קליטה " + ago(Date.now() - d.updatedAt), online: false };
  if (d.recording) return { cls: "rec", text: "● מתעד" + (d.trigger ? " (" + (TRIGGER[d.trigger] || d.trigger) + ")" : ""), online: true };
  return { cls: "on", text: d.live ? "במשמרת • צופים עכשיו" : "במשמרת", online: true };
}

function renderOrg() {
  if (!org) return;
  $("org-name").textContent = org.name;
  $("org-seats").textContent = `${org.memberCount || 0}/${org.seats || 0} מכשירים`;
  $("plan-big").textContent = org.seats ? `${org.seats} מכשירים` : "אין חבילה פעילה";
  $("plan-sub").textContent = `${org.memberCount || 0} מאבטחים מחוברים` + (org.plan?.source === "admin" ? " • הוקצה ע״י מנהל המערכת" : org.plan?.source === "play" ? (org.plan.verified ? ` • התשלום אומת מול Google Play, מתחדש ${fmtTime(org.plan.expiry)}` : " • Google Play") : "");
  const p = org.policy || {};
  $("r-pre").value = p.preRecordSeconds ?? "";
  $("r-audio").checked = p.recordAudio === true; $("r-fall").checked = p.fallDetection === true;
  $("r-lock").checked = p.autoLock !== false; $("r-discard").checked = !!p.allowDiscard; $("r-delete").checked = !!p.allowDelete;
  $("r-cloud").value = p.cloudBackup || "all"; $("r-keep").value = String(p.retentionDays || 30);
}

function renderAll() { renderGuards(); renderMarkers(); renderMembers(); renderDetail(); }

function renderGuards() {
  const rows = members.map((m) => ({ m, s: status(m.email), d: devices[m.email] }))
    .sort((a, b) => (b.s.online - a.s.online) || (a.m.name || a.m.email).localeCompare(b.m.name || b.m.email));
  $("online-count").textContent = `${rows.filter((r) => r.s.online).length} במשמרת`;
  $("guard-list").innerHTML = rows.length ? rows.map(({ m, s, d }) => `
    <div class="guard ${selected === m.email ? "sel" : ""}" data-email="${esc(m.email)}">
      <span class="st ${s.cls}"></span>
      <div><div class="nm">${esc(m.name || m.email)}${m.badge ? ` <span class="muted">• ${esc(m.badge)}</span>` : ""}</div><div class="sub">${esc(s.text)}</div></div>
      ${d?.battery != null && d.onShift ? `<span class="bat ${d.battery <= 20 ? "low" : ""}">${d.battery}%</span>` : ""}
    </div>`).join("") : `<p class="muted" style="padding:14px">עדיין אין מאבטחים. הוסף אותם בלשונית "צוות".</p>`;
  document.querySelectorAll(".guard").forEach((el) => (el.onclick = () => select(el.dataset.email, true)));
}

function initMap() {
  if (map) return;
  map = L.map("map", { zoomControl: true, attributionControl: true }).setView([31.9, 34.9], 9);
  L.tileLayer("https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png", { maxZoom: 19, attribution: "© OpenStreetMap" }).addTo(map);
}

let fitted = false;
function renderMarkers() {
  if (!map) return;
  const pts = [];
  members.forEach((m) => {
    const d = devices[m.email]; const s = status(m.email);
    const has = d && d.onShift && d.lat != null && d.lon != null;
    if (!has) { if (markers[m.email]) { map.removeLayer(markers[m.email]); delete markers[m.email]; } return; }
    const icon = L.divIcon({ className: "", iconSize: [22, 22], iconAnchor: [11, 22], html: `<div class="pin ${s.cls}"></div><div class="pin-label">${esc(m.name || m.email)}</div>` });
    if (!markers[m.email]) { markers[m.email] = L.marker([d.lat, d.lon], { icon }).addTo(map); markers[m.email].on("click", () => select(m.email, false)); }
    else { markers[m.email].setLatLng([d.lat, d.lon]); markers[m.email].setIcon(icon); }
    pts.push([d.lat, d.lon]);
  });
  if (!fitted && pts.length) { map.fitBounds(pts, { padding: [60, 60], maxZoom: 16 }); fitted = true; }
}

function select(email, pan) {
  if (selected !== email) stopLive();
  selected = email;
  $("detail").classList.remove("hidden");
  const d = devices[email];
  if (pan && d?.lat != null) map.setView([d.lat, d.lon], Math.max(map.getZoom(), 16));
  renderGuards(); renderDetail();
}
$("detail-close").onclick = () => { stopLive(); selected = null; $("detail").classList.add("hidden"); renderGuards(); };

function renderDetail() {
  if (!selected) return;
  const m = members.find((x) => x.email === selected) || { email: selected };
  const d = devices[selected] || {}; const s = status(selected);
  $("d-name").textContent = (m.name || m.email) + (m.badge ? " • " + m.badge : "");
  const loc = d.lat != null ? ` • ${d.lat.toFixed(5)}, ${d.lon.toFixed(5)}` + (d.acc ? ` ±${Math.round(d.acc)}m` : "") : "";
  $("d-sub").textContent = s.text + (d.battery != null && d.onShift ? ` • סוללה ${d.battery}%` : "") + loc + (d.updatedAt ? " • עודכן " + fmtTime(d.updatedAt) : "");
  $("d-watch").disabled = !s.online; $("d-rec").disabled = !s.online;
  $("d-watch").textContent = viewer ? "סגור שידור" : "צפייה חיה";
  $("d-rec").textContent = d.recording ? "עצור ושמור תיעוד" : "התחל תיעוד";
  $("d-rec").classList.toggle("on", !!d.recording);
  const mine = events.filter((e) => e.email === selected).slice(0, 8);
  $("d-events").innerHTML = mine.map((e) => `<div><b>${esc(EVENT_LABEL[e.type] || e.type)}</b> ${fmtTime(e.at)}${e.detail ? " • " + esc(TRIGGER[e.detail] || e.detail) : ""}</div>`).join("");
}

$("d-rec").onclick = () => {
  if (!selected) return;
  const d = devices[selected] || {};
  sendCommand(selected, d.recording ? "stop" : "start");
};
$("d-watch").onclick = () => (viewer ? stopLive() : watch(selected));

function sendCommand(email, type) {
  return setDoc(doc(db, "shomer_orgs", orgId, "cmd", email), { id: crypto.randomUUID(), type, by: me, at: Date.now() });
}

// ------------------------------------------------------------------ live video (WebRTC, video only)
async function watch(email) {
  stopLive();
  const box = $("video-box"), state = $("v-state");
  box.classList.remove("hidden", "live"); state.textContent = "מתחבר לטלפון…";
  const session = crypto.randomUUID();
  const live = doc(db, "shomer_orgs", orgId, "live", email);
  const pc = new RTCPeerConnection({ iceServers: [{ urls: ["stun:stun.l.google.com:19302", "stun:stun1.l.google.com:19302"] }] });
  const v = { pc, live, session, subs: [], timers: [] };
  viewer = v; renderDetail();
  pc.addTransceiver("video", { direction: "recvonly" });
  pc.ontrack = (e) => { $("video").srcObject = e.streams[0] || new MediaStream([e.track]); };
  pc.onicecandidate = (e) => { if (e.candidate) addDoc(collection(live, "vc"), { session, candidate: e.candidate.candidate, sdpMid: e.candidate.sdpMid, sdpMLineIndex: e.candidate.sdpMLineIndex }); };
  pc.oniceconnectionstatechange = () => {
    const st = pc.iceConnectionState;
    if (st === "connected" || st === "completed") box.classList.add("live");
    if (st === "failed") { box.classList.remove("live"); state.textContent = "לא ניתן להתחבר לטלפון. ייתכן שאין לו קליטה או שהרשת חוסמת שידור ישיר."; }
  };
  $("video").onloadeddata = () => box.classList.add("live");
  const offer = await pc.createOffer();
  await pc.setLocalDescription(offer);
  await setDoc(live, { state: "request", session, offer: { type: offer.type, sdp: offer.sdp }, viewer: me, beat: Date.now() });
  let answered = false;
  v.subs.push(onSnapshot(live, (s) => {
    const d = s.data(); if (!d || d.session !== session) return;
    if (d.state === "answer" && !answered) { answered = true; pc.setRemoteDescription(d.answer).catch(console.error); }
    if (d.state === "end" && viewer === v) { state.textContent = "השידור הסתיים"; box.classList.remove("live"); stopLive(false); }
  }));
  v.subs.push(onSnapshot(query(collection(live, "dc"), where("session", "==", session)), (qs) => {
    qs.docChanges().forEach((ch) => { if (ch.type === "added") { const c = ch.doc.data(); pc.addIceCandidate({ candidate: c.candidate, sdpMid: c.sdpMid, sdpMLineIndex: c.sdpMLineIndex }).catch(() => {}); } });
  }));
  v.timers.push(setInterval(() => updateDoc(live, { beat: Date.now() }).catch(() => {}), 5000));
  v.timers.push(setTimeout(() => { if (!answered && viewer === v) state.textContent = "הטלפון לא עונה. ודא שהמאבטח במשמרת ושיש לו אינטרנט."; }, 20000));
}

function stopLive(write = true) {
  const v = viewer; if (!v) return;
  viewer = null;
  v.subs.forEach((f) => f()); v.timers.forEach((t) => { clearInterval(t); clearTimeout(t); });
  try { v.pc.close(); } catch {}
  if (write) updateDoc(v.live, { state: "end", endedBy: "viewer" }).catch(() => {});
  if (write) { $("video-box").classList.add("hidden"); $("video").srcObject = null; }
  renderDetail();
}
window.addEventListener("beforeunload", () => stopLive());

// ------------------------------------------------------------------ alerts
function renderEvents() {
  const open = events.filter((e) => ALARMS.includes(e.type) && !e.ackBy).length;
  $("alert-count").textContent = open; $("alert-count").classList.toggle("hidden", !open);
  $("event-list").innerHTML = events.length ? events.map((e) => {
    const alarm = ALARMS.includes(e.type);
    return `<div class="item ${alarm && !e.ackBy ? "alarm" : ""}">
      <div class="grow"><div class="t">${esc(EVENT_LABEL[e.type] || e.type)} • ${esc(e.name || e.email)}</div>
      <div class="s">${fmtTime(e.at)}${e.detail ? " • " + esc(TRIGGER[e.detail] || e.detail) : ""}${e.lat != null ? ` • ${e.lat.toFixed(5)}, ${e.lon.toFixed(5)}` : ""}${e.ackBy ? " • טופל ע״י " + esc(e.ackBy) : ""}</div></div>
      ${e.lat != null ? `<button class="btn" data-map="${esc(e.email)}" data-lat="${e.lat}" data-lon="${e.lon}">מפה</button>` : ""}
      ${alarm && !e.ackBy ? `<button class="btn primary" data-ack="${e.id}">טופל</button>` : ""}
    </div>`;
  }).join("") : `<p class="muted">עוד אין אירועים.</p>`;
  document.querySelectorAll("[data-ack]").forEach((b) => (b.onclick = () => ack(b.dataset.ack)));
  document.querySelectorAll("[data-map]").forEach((b) => (b.onclick = () => { tab("board"); map.setView([+b.dataset.lat, +b.dataset.lon], 17); select(b.dataset.map, false); }));
}
const ack = (id) => updateDoc(doc(db, "shomer_orgs", orgId, "events", id), { ackBy: me, ackAt: Date.now() });

let beepTimer = null;
function raiseAlarm(e) {
  alarmEvent = e;
  $("alarm-text").textContent = `${e.name || e.email} • ${fmtTime(e.at)}` + (e.type === "sos_call" ? " • המכשיר מחייג לאיש קשר לחירום" : "");
  $("alarm").classList.remove("hidden");
  beep(); clearInterval(beepTimer); beepTimer = setInterval(beep, 2500);
  if ("Notification" in window && Notification.permission === "granted") new Notification("Shomer – זוהתה נפילה", { body: e.name || e.email });
}
function closeAlarm() { $("alarm").classList.add("hidden"); clearInterval(beepTimer); }
$("alarm-ack").onclick = () => { if (alarmEvent) ack(alarmEvent.id); closeAlarm(); };
$("alarm-map").onclick = () => { closeAlarm(); tab("board"); if (alarmEvent?.lat != null) map.setView([alarmEvent.lat, alarmEvent.lon], 17); select(alarmEvent.email, false); };
$("alarm-watch").onclick = () => { closeAlarm(); tab("board"); select(alarmEvent.email, true); watch(alarmEvent.email); };
let audio;
function beep() {
  try {
    audio = audio || new (window.AudioContext || window.webkitAudioContext)();
    [0, 0.35].forEach((t) => {
      const o = audio.createOscillator(), g = audio.createGain();
      o.type = "square"; o.frequency.value = 880; g.gain.value = 0.08;
      o.connect(g); g.connect(audio.destination); o.start(audio.currentTime + t); o.stop(audio.currentTime + t + 0.22);
    });
  } catch {}
}
document.addEventListener("click", () => { if ("Notification" in window && Notification.permission === "default") Notification.requestPermission(); }, { once: true });

// ------------------------------------------------------------------ team
function renderMembers() {
  $("member-list").innerHTML = members.map((m) => {
    const s = status(m.email);
    return `<div class="item"><span class="guard"><span class="st ${s.cls}"></span></span>
      <div class="grow"><div class="t">${esc(m.name || "—")}${m.badge ? " • " + esc(m.badge) : ""}</div><div class="s">${esc(m.email)} • ${esc(s.text)}</div></div>
      <button class="btn" data-edit="${esc(m.email)}">ערוך</button><button class="btn" data-del="${esc(m.email)}">הסר</button></div>`;
  }).join("");
  document.querySelectorAll("[data-del]").forEach((b) => (b.onclick = () => removeMember(b.dataset.del)));
  document.querySelectorAll("[data-edit]").forEach((b) => (b.onclick = () => editMember(b.dataset.edit)));
}

$("add-form").onsubmit = async (e) => {
  e.preventDefault();
  const email = $("add-email").value.trim().toLowerCase(), name = $("add-name").value.trim(), badge = $("add-badge").value.trim();
  const msg = $("add-msg");
  if ((org.memberCount || 0) >= (org.seats || 0)) { msg.textContent = "כל המכשירים בחבילה בשימוש. שדרג את החבילה באפליקציה."; return; }
  try {
    const b = writeBatch(db);
    b.set(doc(db, "shomer_orgs", orgId, "members", email), { name: name.slice(0, 60), badge: badge.slice(0, 32), addedBy: me, addedAt: serverTimestamp() });
    b.update(doc(db, "shomer_orgs", orgId), { memberCount: increment(1) });
    b.set(doc(db, "shomer_index", email), { orgId, role: "member" });
    await b.commit();
    msg.textContent = `${email} נוסף. כשיתחבר באפליקציה Shomer הוא יחובר לחברה.`;
    e.target.reset();
  } catch (err) {
    console.error(err);
    msg.textContent = "ההוספה נכשלה – ייתכן שהמייל כבר שייך לחברה אחרת.";
  }
};

async function removeMember(email) {
  if (!confirm(`להסיר את ${email} מהחברה?`)) return;
  const b = writeBatch(db);
  b.delete(doc(db, "shomer_orgs", orgId, "members", email));
  b.update(doc(db, "shomer_orgs", orgId), { memberCount: increment(-1) });
  b.delete(doc(db, "shomer_index", email));
  await b.commit().catch((e) => alert("ההסרה נכשלה: " + e.code));
}

async function editMember(email) {
  const m = members.find((x) => x.email === email); if (!m) return;
  const name = prompt("שם", m.name || ""); if (name === null) return;
  const badge = prompt("מספר תג", m.badge || ""); if (badge === null) return;
  await updateDoc(doc(db, "shomer_orgs", orgId, "members", email), { name: name.slice(0, 60), badge: badge.slice(0, 32) });
}

// ------------------------------------------------------------------ rules
$("r-save").onclick = async () => {
  const pre = $("r-pre").value;
  const policy = {
    preRecordSeconds: pre ? +pre : null, recordAudio: $("r-audio").checked, fallDetection: $("r-fall").checked,
    autoLock: $("r-lock").checked, allowDiscard: $("r-discard").checked, allowDelete: $("r-delete").checked,
    cloudBackup: $("r-cloud").value, retentionDays: +$("r-keep").value,
  };
  try { await setDoc(doc(db, "shomer_orgs", orgId), { policy }, { merge: true }); $("r-msg").textContent = "נשמר ✓"; }
  catch (e) { $("r-msg").textContent = "השמירה נכשלה"; }
  setTimeout(() => ($("r-msg").textContent = ""), 3000);
};

// ------------------------------------------------------------------ system admin (free seats for pilots)
async function loadAdmin() {
  const qs = await getDocs(collection(db, "shomer_orgs"));
  $("org-list").innerHTML = qs.docs.map((d) => {
    const o = d.data();
    return `<div class="item"><div class="grow"><div class="t">${esc(o.name)}</div><div class="s">${esc(o.ownerEmail)} • ${o.memberCount || 0}/${o.seats || 0} • ${esc(o.plan?.source || "—")}</div></div>
      <input type="number" min="0" max="500" value="${o.seats || 0}" style="width:90px" id="seat-${d.id}"><button class="btn" data-seat="${d.id}">הקצה</button></div>`;
  }).join("") || `<p class="muted">אין חברות.</p>`;
  document.querySelectorAll("[data-seat]").forEach((b) => (b.onclick = async () => {
    const n = +$("seat-" + b.dataset.seat).value;
    await updateDoc(doc(db, "shomer_orgs", b.dataset.seat), { seats: n, plan: { source: "admin", by: me, at: Date.now() } });
    loadAdmin();
  }));
}

// ------------------------------------------------------------------ tabs
function tab(name) {
  document.querySelectorAll("#tabs button").forEach((b) => b.classList.toggle("on", b.dataset.tab === name));
  document.querySelectorAll(".pane").forEach((p) => p.classList.toggle("on", p.id === "pane-" + name));
  if (name === "board" && map) setTimeout(() => map.invalidateSize(), 50);
  if (name === "admin") loadAdmin();
}
document.querySelectorAll("#tabs button").forEach((b) => (b.onclick = () => tab(b.dataset.tab)));

// test hook
window.__shomer = { select, watch, sendCommand, state: () => ({ me, orgId, members, devices, events, viewer: !!viewer }) };
