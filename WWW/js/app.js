(() => {
'use strict';
const $ = (s, el = document) => el.querySelector(s);
const $$ = (s, el = document) => [...el.querySelectorAll(s)];
const uid = () => Math.random().toString(36).slice(2, 9);
const clamp = (v, a, b) => Math.min(b, Math.max(a, v));
const esc = s => String(s).replace(/[&<>"]/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]));
const FPS = 30;
const ASPECTS = { '9:16': [720, 1280], '16:9': [1280, 720], '1:1': [900, 900], '4:5': [864, 1080] };
const FONTS = { Sans: 'sans-serif', Serif: 'serif', Mono: 'monospace', Script: 'cursive' };
const EASE = {
  linear: t => t,
  easeIn: t => t * t * t,
  easeOut: t => 1 - Math.pow(1 - t, 3),
  easeInOut: t => t < .5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2,
  back: t => { const c1 = 1.70158, c3 = c1 + 1; return 1 + c3 * Math.pow(t - 1, 3) + c1 * Math.pow(t - 1, 2); },
  bounce: t => { const n = 7.5625, d = 2.75;
    if (t < 1 / d) return n * t * t;
    if (t < 2 / d) return n * (t -= 1.5 / d) * t + .75;
    if (t < 2.5 / d) return n * (t -= 2.25 / d) * t + .9375;
    return n * (t -= 2.625 / d) * t + .984375; },
  hold: t => t < 1 ? 0 : 1
};
const EASE_LABELS = { linear: 'Linear', easeIn: 'Ease in', easeOut: 'Ease out', easeInOut: 'Ease in-out', back: 'Overshoot', bounce: 'Bounce', hold: 'Hold' };

const S = { aspect: '9:16', W: 720, H: 1280, duration: 5, bg: '#0d0e14', layers: [], assets: {},
  sel: null, time: 0, zoom: 120, ease: 'easeOut', loop: true };
const cv = $('#cv'), ctx = cv.getContext('2d');
let exporting = false, playing = false, lastTs = 0;

/* ---------- keyframe engine ---------- */
function evalProp(L, k, t) {
  const keys = L.keys[k];
  if (!keys || !keys.length) return L.props[k];
  if (t <= keys[0].t) return keys[0].v;
  const last = keys[keys.length - 1];
  if (t >= last.t) return last.v;
  for (let i = 0; i < keys.length - 1; i++) {
    const a = keys[i], b = keys[i + 1];
    if (t >= a.t && t <= b.t) {
      const p = (t - a.t) / (b.t - a.t);
      return a.v + (b.v - a.v) * (EASE[a.e] || EASE.linear)(p);
    }
  }
  return last.v;
}
const keyAt = (L, k, t) => (L.keys[k] || []).find(x => Math.abs(x.t - t) < 0.017);
function upsertKey(L, k, t, v, e) {
  const arr = L.keys[k] || (L.keys[k] = []);
  const ex = arr.find(x => Math.abs(x.t - t) < 0.017);
  if (ex) { ex.v = v; if (e) ex.e = e; }
  else { arr.push({ t: +t.toFixed(3), v, e: e || S.ease }); arr.sort((a, b) => a.t - b.t); }
}
function setProp(L, k, v, t) {
  if (L.keys[k] && L.keys[k].length) upsertKey(L, k, t, v);
  else L.props[k] = v;
}
function toggleKey(L, k, t) {
  const ex = keyAt(L, k, t);
  if (ex) {
    const arr = L.keys[k]; arr.splice(arr.indexOf(ex), 1);
    if (!arr.length) { delete L.keys[k]; L.props[k] = ex.v; }
  } else upsertKey(L, k, t, evalProp(L, k, t));
}

/* ---------- layers ---------- */
const sel = () => S.layers.find(l => l.id === S.sel);
function newLayer(type, extra = {}) {
  const n = S.layers.filter(l => l.type === type).length + 1;
  const label = { text: 'Text', rect: 'Rectangle', circle: 'Circle', image: 'Image' }[type];
  const L = { id: uid(), type, name: `${label} ${n}`, start: 0, end: S.duration, color: '#ffffff',
    props: { x: S.W / 2, y: S.H / 2, scale: 1, rotation: 0, opacity: 1, blur: 0, reveal: 1 }, keys: {} };
  if (type === 'text') Object.assign(L, { text: 'Your text', size: 96, font: 'Sans', bold: true });
  if (type === 'rect') Object.assign(L, { w: 420, h: 260, radius: 28, color: '#ff5a7a' });
  if (type === 'circle') Object.assign(L, { w: 300, h: 300, color: '#ffc247' });
  return Object.assign(L, extra);
}
function addLayer(type, extra) {
  const L = newLayer(type, extra);
  S.layers.push(L); S.sel = L.id;
  commit(); buildAll(); render();
  return L;
}

/* ---------- presets ---------- */
const k2 = (L, k, t, v, e) => upsertKey(L, k, t, v, e);
const PRESETS = {
  'Fade in': (L, t) => { k2(L, 'opacity', t, 0, 'easeOut'); k2(L, 'opacity', t + .6, 1); },
  'Fade out': (L, t) => { k2(L, 'opacity', t, 1, 'easeIn'); k2(L, 'opacity', t + .6, 0); },
  'Slide up': (L, t) => { const y = evalProp(L, 'y', t);
    k2(L, 'y', t, y + 220, 'easeOut'); k2(L, 'y', t + .7, y);
    k2(L, 'opacity', t, 0, 'easeOut'); k2(L, 'opacity', t + .5, 1); },
  'Pop in': (L, t) => { k2(L, 'scale', t, 0, 'back'); k2(L, 'scale', t + .6, 1); },
  'Spin in': (L, t) => { k2(L, 'rotation', t, -180, 'easeOut'); k2(L, 'rotation', t + .8, 0);
    k2(L, 'scale', t, 0, 'easeOut'); k2(L, 'scale', t + .8, 1); },
  'Blur in': (L, t) => { k2(L, 'blur', t, 30, 'easeOut'); k2(L, 'blur', t + .8, 0);
    k2(L, 'opacity', t, 0, 'easeOut'); k2(L, 'opacity', t + .6, 1); },
  'Bounce drop': (L, t) => { const y = evalProp(L, 'y', t);
    k2(L, 'y', t, y - 500, 'bounce'); k2(L, 'y', t + 1, y); },
  'Typewriter': (L, t) => { if (L.type !== 'text') { toast('Typewriter works on text layers'); return false; }
    const d = Math.max(.5, L.text.length * .07);
    k2(L, 'reveal', t, 0, 'linear'); k2(L, 'reveal', t + d, 1); }
};

/* ---------- drawing ---------- */
const imgCache = {};
function getImg(id) {
  let im = imgCache[id];
  if (!im) { im = new Image(); im.onload = () => render(); im.src = S.assets[id]; imgCache[id] = im; }
  return im;
}
const fontStr = L => `${L.bold ? 700 : 400} ${L.size}px ${FONTS[L.font] || 'sans-serif'}`;
function rr(c, x, y, w, h, r) {
  r = Math.max(0, Math.min(r, w / 2, h / 2));
  c.beginPath(); c.moveTo(x + r, y);
  c.arcTo(x + w, y, x + w, y + h, r); c.arcTo(x + w, y + h, x, y + h, r);
  c.arcTo(x, y + h, x, y, r); c.arcTo(x, y, x + w, y, r); c.closePath();
}
function sizeOf(L) {
  if (L.type !== 'text') return { w: L.w, h: L.h };
  ctx.save(); ctx.font = fontStr(L);
  const lines = L.text.split('\n'); let w = 0;
  for (const ln of lines) w = Math.max(w, ctx.measureText(ln).width);
  ctx.restore();
  return { w: Math.max(w, 20), h: lines.length * L.size * 1.2 };
}
const visible = (L, t) => t >= L.start - 1e-6 && t <= L.end + 1e-6;
function drawLayer(L, t) {
  const p = {}; for (const k in L.props) p[k] = evalProp(L, k, t);
  ctx.save();
  ctx.globalAlpha = clamp(p.opacity, 0, 1);
  ctx.translate(p.x, p.y); ctx.rotate(p.rotation * Math.PI / 180); ctx.scale(p.scale, p.scale);
  if (p.blur > 0.1) ctx.filter = `blur(${p.blur}px)`;
  ctx.fillStyle = L.color;
  if (L.type === 'rect') { rr(ctx, -L.w / 2, -L.h / 2, L.w, L.h, L.radius); ctx.fill(); }
  else if (L.type === 'circle') { ctx.beginPath(); ctx.ellipse(0, 0, Math.abs(L.w / 2), Math.abs(L.h / 2), 0, 0, Math.PI * 2); ctx.fill(); }
  else if (L.type === 'image') { const im = getImg(L.asset); if (im.complete && im.naturalWidth) ctx.drawImage(im, -L.w / 2, -L.h / 2, L.w, L.h); }
  else if (L.type === 'text') {
    ctx.font = fontStr(L); ctx.textBaseline = 'middle'; ctx.textAlign = 'left';
    const lines = L.text.split('\n'), lh = L.size * 1.2;
    let remaining = Math.round(L.text.replace(/\n/g, '').length * clamp(p.reveal, 0, 1));
    lines.forEach((ln, i) => {
      const w = ctx.measureText(ln).width, y = (i - (lines.length - 1) / 2) * lh;
      ctx.fillText(ln.slice(0, Math.max(0, remaining)), -w / 2, y);
      remaining -= ln.length;
    });
  }
  ctx.restore();
}
function drawSel(L) {
  const p = {}; for (const k in L.props) p[k] = evalProp(L, k, S.time);
  const sz = sizeOf(L), w = sz.w * p.scale, h = sz.h * p.scale;
  const ds = (cv.clientWidth || S.W) / S.W;
  ctx.save();
  ctx.translate(p.x, p.y); ctx.rotate(p.rotation * Math.PI / 180);
  ctx.strokeStyle = '#ff5a7a'; ctx.lineWidth = 2.5 / ds; ctx.setLineDash([10 / ds, 6 / ds]);
  ctx.strokeRect(-w / 2, -h / 2, w, h); ctx.setLineDash([]);
  ctx.fillStyle = '#ff5a7a'; const c = 12 / ds;
  for (const [cx, cy] of [[-w / 2, -h / 2], [w / 2, -h / 2], [w / 2, h / 2], [-w / 2, h / 2]]) ctx.fillRect(cx - c / 2, cy - c / 2, c, c);
  ctx.restore();
}
function render() {
  ctx.setTransform(1, 0, 0, 1, 0, 0); ctx.globalAlpha = 1; ctx.filter = 'none';
  ctx.fillStyle = S.bg; ctx.fillRect(0, 0, S.W, S.H);
  for (const L of S.layers) if (visible(L, S.time)) drawLayer(L, S.time);
  if (!exporting) { const L = sel(); if (L && visible(L, S.time)) drawSel(L); }
}
function hitTest(pt) {
  for (let i = S.layers.length - 1; i >= 0; i--) {
    const L = S.layers[i]; if (!visible(L, S.time)) continue;
    const p = {}; for (const k in L.props) p[k] = evalProp(L, k, S.time);
    let dx = pt.x - p.x, dy = pt.y - p.y;
    const a = -p.rotation * Math.PI / 180;
    const rx = (dx * Math.cos(a) - dy * Math.sin(a)) / (p.scale || 1e-3);
    const ry = (dx * Math.sin(a) + dy * Math.cos(a)) / (p.scale || 1e-3);
    const sz = sizeOf(L);
    if (Math.abs(rx) <= Math.max(sz.w / 2, 40) && Math.abs(ry) <= Math.max(sz.h / 2, 40)) return L;
  }
  return null;
}
function fitCanvas() {
  const st = $('#stage'), cs = getComputedStyle(st);
  const aw = st.clientWidth - parseFloat(cs.paddingLeft) - parseFloat(cs.paddingRight);
  const ah = st.clientHeight - parseFloat(cs.paddingTop) - parseFloat(cs.paddingBottom);
  if (aw <= 0 || ah <= 0) return;
  const sc = Math.min(aw / S.W, ah / S.H);
  cv.style.width = S.W * sc + 'px'; cv.style.height = S.H * sc + 'px';
  render();
}

/* ---------- history / save ---------- */
let hist = [], hi = -1;
const snap = () => JSON.stringify({ aspect: S.aspect, duration: S.duration, bg: S.bg, layers: S.layers });
function commit() {
  const s = snap(); if (hist[hi] === s) return;
  hist = hist.slice(0, hi + 1); hist.push(s); if (hist.length > 60) hist.shift();
  hi = hist.length - 1; save(); updateUndoUI();
}
function restore(s) {
  const o = JSON.parse(s); Object.assign(S, o);
  if (!sel()) S.sel = null;
  applyAspect(false); S.time = clamp(S.time, 0, S.duration); buildAll(); render(); save();
}
function undo() { if (hi > 0) { hi--; restore(hist[hi]); updateUndoUI(); } }
function redo() { if (hi < hist.length - 1) { hi++; restore(hist[hi]); updateUndoUI(); } }
function updateUndoUI() { $('#btnUndo').disabled = hi <= 0; $('#btnRedo').disabled = hi >= hist.length - 1; }
function save() {
  try { localStorage.setItem('motionstudio.v1', JSON.stringify({ core: JSON.parse(snap()), assets: S.assets, ease: S.ease })); }
  catch (e) { toast('Autosave skipped: storage is full'); }
}
function load() {
  try {
    const raw = localStorage.getItem('motionstudio.v1'); if (!raw) return false;
    const o = JSON.parse(raw); Object.assign(S, o.core); S.assets = o.assets || {}; S.ease = o.ease || 'easeOut';
    return S.layers.length > 0;
  } catch (e) { return false; }
}

/* ---------- aspect ---------- */
function applyAspect(remap, newKey) {
  const oldW = S.W, oldH = S.H;
  if (newKey) S.aspect = newKey;
  [S.W, S.H] = ASPECTS[S.aspect];
  cv.width = S.W; cv.height = S.H;
  if (remap && (oldW !== S.W || oldH !== S.H)) {
    const rx = S.W / oldW, ry = S.H / oldH;
    for (const L of S.layers) {
      L.props.x *= rx; L.props.y *= ry;
      (L.keys.x || []).forEach(k => k.v *= rx); (L.keys.y || []).forEach(k => k.v *= ry);
    }
  }
  fitCanvas();
}

/* ---------- time / playback ---------- */
function fmt(t) { return t.toFixed(2) + 's'; }
function setTime(t) {
  S.time = clamp(t, 0, S.duration);
  $('#scrub').value = S.duration ? S.time / S.duration * 1000 : 0;
  $('#timeLabel').textContent = fmt(S.time);
  render(); syncProps(); updatePlayhead();
}
function play() {
  if (playing) return;
  if (S.time >= S.duration - 1e-3) S.time = 0;
  playing = true; lastTs = performance.now(); $('#btnPlay').innerHTML = '&#10073;&#10073;';
  requestAnimationFrame(tick);
}
function stop() { playing = false; $('#btnPlay').innerHTML = '&#9654;'; }
function tick(ts) {
  if (!playing) return;
  const dt = (ts - lastTs) / 1000; lastTs = ts;
  let t = S.time + dt;
  if (t >= S.duration) { if (S.loop) t = 0; else { setTime(S.duration); stop(); return; } }
  setTime(t); requestAnimationFrame(tick);
}

/* ---------- timeline ---------- */
function buildTimeline() {
  const tl = $('#tl'), sl = tl.scrollLeft, st = tl.scrollTop, z = S.zoom;
  const tw = Math.ceil(S.duration * z) + 60;
  const step = z >= 160 ? 0.5 : z >= 70 ? 1 : 2;
  let h = `<div class="tl-inner" style="width:calc(var(--nameW) + ${tw}px)">`;
  h += `<div class="tl-row ruler"><div class="tl-name"></div><div class="tl-track" id="ruler" style="width:${tw}px">`;
  for (let s = 0; s <= S.duration + 1e-6; s += step) h += `<div class="tick" style="left:${s * z}px">${+s.toFixed(1)}s</div>`;
  h += `<div id="rhead"></div></div></div>`;
  for (const L of [...S.layers].reverse()) {
    const times = new Set(); for (const k in L.keys) for (const kk of L.keys[k]) times.add(kk.t);
    h += `<div class="tl-row${L.id === S.sel ? ' sel' : ''}" data-id="${L.id}"><div class="tl-name">${esc(L.name)}</div>`
      + `<div class="tl-track" style="width:${tw}px"><div class="bar" style="left:${L.start * z}px;width:${Math.max(4, (L.end - L.start) * z)}px"></div>`;
    for (const t of times) h += `<div class="dia" data-t="${t}" style="left:${t * z}px"></div>`;
    h += `</div></div>`;
  }
  h += `<div id="playhead"></div></div>`;
  tl.innerHTML = h; tl.scrollLeft = sl; tl.scrollTop = st; updatePlayhead();
}
function updatePlayhead() {
  const ph = $('#playhead'), rh = $('#rhead'), tl = $('#tl'); if (!ph) return;
  ph.style.left = `calc(var(--nameW) + ${S.time * S.zoom}px)`;
  rh.style.left = `${S.time * S.zoom}px`;
  if (playing) {
    const x = S.time * S.zoom, vis = tl.clientWidth - 84;
    if (x < tl.scrollLeft + 10 || x > tl.scrollLeft + vis - 10) tl.scrollLeft = Math.max(0, x - 40);
  }
}
let scrubbing = false;
const rulerTime = e => { const r = $('#ruler').getBoundingClientRect(); return clamp((e.clientX - r.left) / S.zoom, 0, S.duration); };
$('#tl').addEventListener('pointerdown', e => {
  if (e.target.closest('#ruler')) { scrubbing = true; $('#tl').setPointerCapture(e.pointerId); stop(); setTime(rulerTime(e)); }
});
$('#tl').addEventListener('pointermove', e => { if (scrubbing) setTime(rulerTime(e)); });
const endScrub = () => { scrubbing = false; };
$('#tl').addEventListener('pointerup', endScrub);
$('#tl').addEventListener('pointercancel', endScrub);
$('#tl').addEventListener('click', e => {
  const row = e.target.closest('.tl-row[data-id]'); if (!row) return;
  if (S.sel !== row.dataset.id) select(row.dataset.id);
  const d = e.target.closest('.dia'); if (d) { stop(); setTime(parseFloat(d.dataset.t)); }
});
$('#zoomIn').onclick = () => { S.zoom = clamp(S.zoom * 1.5, 30, 400); buildTimeline(); };
$('#zoomOut').onclick = () => { S.zoom = clamp(S.zoom / 1.5, 30, 400); buildTimeline(); };

/* ---------- properties pane ---------- */
function propDefs() {
  return [
    { k: 'x', label: 'Position X', min: -S.W, max: S.W * 2, step: 1 },
    { k: 'y', label: 'Position Y', min: -S.H, max: S.H * 2, step: 1 },
    { k: 'scale', label: 'Scale', min: 0, max: 5, step: 0.01 },
    { k: 'rotation', label: 'Rotation', min: -720, max: 720, step: 1 },
    { k: 'opacity', label: 'Opacity', min: 0, max: 1, step: 0.01 },
    { k: 'blur', label: 'Blur', min: 0, max: 60, step: 0.5 },
    { k: 'reveal', label: 'Typewriter', min: 0, max: 1, step: 0.01, only: 'text' }
  ];
}
function buildProps() {
  const box = $('#paneProps'), L = sel();
  if (!L) { box.innerHTML = `<p class="empty">Nothing selected. Tap a layer on the canvas or in the timeline, or add one from the Add tab.</p>`; return; }
  let h = `<div class="field-grid"><label class="wide">Name<input data-f="name" value="${esc(L.name)}"></label>`;
  if (L.type === 'text') {
    h += `<label class="wide">Text<textarea data-f="text" rows="2">${esc(L.text)}</textarea></label>`
      + `<label>Size<input data-f="size" data-n="1" type="number" value="${L.size}"></label>`
      + `<label>Font<select data-f="font">${Object.keys(FONTS).map(f => `<option${f === L.font ? ' selected' : ''}>${f}</option>`).join('')}</select></label>`
      + `<label>Colour<input data-f="color" type="color" value="${L.color}"></label>`
      + `<label class="check"><input data-f="bold" data-b="1" type="checkbox"${L.bold ? ' checked' : ''}> Bold</label>`;
  } else if (L.type === 'rect') {
    h += `<label>Width<input data-f="w" data-n="1" type="number" value="${L.w}"></label>`
      + `<label>Height<input data-f="h" data-n="1" type="number" value="${L.h}"></label>`
      + `<label>Corner radius<input data-f="radius" data-n="1" type="number" value="${L.radius}"></label>`
      + `<label>Colour<input data-f="color" type="color" value="${L.color}"></label>`;
  } else if (L.type === 'circle') {
    h += `<label>Width<input data-f="w" data-n="1" type="number" value="${L.w}"></label>`
      + `<label>Height<input data-f="h" data-n="1" type="number" value="${L.h}"></label>`
      + `<label>Colour<input data-f="color" type="color" value="${L.color}"></label>`;
  } else {
    h += `<label>Width<input data-f="w" data-n="1" type="number" value="${L.w}"></label>`
      + `<label>Height<input data-f="h" data-n="1" type="number" value="${L.h}"></label>`;
  }
  h += `<label>Starts at (s)<input data-f="start" data-n="1" type="number" step="0.1" value="${L.start}"></label>`
    + `<label>Ends at (s)<input data-f="end" data-n="1" type="number" step="0.1" value="${L.end}"></label>`
    + `<label class="wide">Easing for new keyframes<select id="easeSel">${Object.keys(EASE_LABELS).map(k => `<option value="${k}"${k === S.ease ? ' selected' : ''}>${EASE_LABELS[k]}</option>`).join('')}</select></label></div>`;
  h += `<div class="row-actions"><button data-act="dup">Duplicate</button><button data-act="up">Move up</button><button data-act="down">Move down</button><button data-act="del">Delete</button></div>`;
  h += `<div class="sep">Tap the diamond to set a keyframe at the playhead. Once a property has keyframes, changing it at another time adds a new one.</div>`;
  for (const d of propDefs()) {
    if (d.only && d.only !== L.type) continue;
    h += `<div class="prow" data-k="${d.k}"><span>${d.label}</span>`
      + `<input type="range" data-k="${d.k}" min="${d.min}" max="${d.max}" step="${d.step}">`
      + `<input type="number" data-k="${d.k}" step="${d.step}"><button class="kf" data-kf="${d.k}" aria-label="Keyframe ${d.label}">&#9670;</button></div>`;
  }
  box.innerHTML = h; syncProps();
}
function syncProps() {
  const L = sel(); if (!L) return;
  const act = document.activeElement;
  $$('#paneProps .prow').forEach(row => {
    const k = row.dataset.k, v = evalProp(L, k, S.time);
    const r = $('input[type=range]', row), n = $('input[type=number]', row), b = $('.kf', row);
    if (r !== act) r.value = v;
    if (n !== act) n.value = +v.toFixed(2);
    b.classList.toggle('has', !!(L.keys[k] && L.keys[k].length));
    b.classList.toggle('on', !!keyAt(L, k, S.time));
  });
}
const pp = $('#paneProps');
pp.addEventListener('input', e => {
  const L = sel(), t = e.target; if (!L) return;
  if (t.dataset.k) {
    const v = parseFloat(t.value); if (isNaN(v)) return;
    setProp(L, t.dataset.k, v, S.time); render(); syncProps(); return;
  }
  const f = t.dataset.f; if (!f) return;
  let v = t.value;
  if (t.dataset.b) v = t.checked; else if (t.dataset.n) { v = parseFloat(v); if (isNaN(v)) return; }
  L[f] = v;
  if (f === 'name') { const el = $(`.tl-row[data-id="${L.id}"] .tl-name`); if (el) el.textContent = v; }
  render();
});
pp.addEventListener('change', e => {
  const L = sel(), t = e.target; if (!L) return;
  if (t.id === 'easeSel') {
    S.ease = t.value;
    for (const k in L.keys) { const ex = keyAt(L, k, S.time); if (ex) ex.e = S.ease; }
    commit(); render(); return;
  }
  if (t.dataset.f === 'start' || t.dataset.f === 'end') {
    L.start = clamp(L.start, 0, S.duration); L.end = clamp(L.end, L.start, S.duration);
    buildProps(); buildTimeline(); render();
  }
  commit(); if (t.dataset.k) buildTimeline();
});
pp.addEventListener('click', e => {
  const L = sel(); if (!L) return;
  const kf = e.target.closest('[data-kf]');
  if (kf) { toggleKey(L, kf.dataset.kf, S.time); commit(); syncProps(); buildTimeline(); return; }
  const a = e.target.closest('[data-act]'); if (!a) return;
  const i = S.layers.indexOf(L);
  if (a.dataset.act === 'del') { S.layers.splice(i, 1); S.sel = null; }
  if (a.dataset.act === 'dup') { const c = JSON.parse(JSON.stringify(L)); c.id = uid(); c.name = L.name + ' copy'; c.props.x += 30; c.props.y += 30; S.layers.splice(i + 1, 0, c); S.sel = c.id; }
  if (a.dataset.act === 'up' && i < S.layers.length - 1) { [S.layers[i], S.layers[i + 1]] = [S.layers[i + 1], S.layers[i]]; }
  if (a.dataset.act === 'down' && i > 0) { [S.layers[i], S.layers[i - 1]] = [S.layers[i - 1], S.layers[i]]; }
  commit(); buildAll(); render();
});

/* ---------- add pane / project pane ---------- */
$('#presets').innerHTML = Object.keys(PRESETS).map(n => `<button data-preset="${n}">${n}</button>`).join('');
$('#paneAdd').addEventListener('click', e => {
  const a = e.target.closest('[data-add]');
  if (a) { addLayer(a.dataset.add); switchTab('Props'); return; }
  const p = e.target.closest('[data-preset]');
  if (p) {
    const L = sel(); if (!L) { toast('Select a layer first'); return; }
    if (PRESETS[p.dataset.preset](L, S.time) === false) return;
    commit(); buildProps(); buildTimeline(); render(); toast(p.dataset.preset + ' added at ' + fmt(S.time));
  }
});
$('#btnImage').onclick = () => $('#fileImage').click();
$('#fileImage').onchange = e => {
  const f = e.target.files[0]; if (!f) return;
  const url = URL.createObjectURL(f), im = new Image();
  im.onload = () => {
    const m = 1280, sc = Math.min(1, m / Math.max(im.naturalWidth, im.naturalHeight));
    const c = document.createElement('canvas'); c.width = Math.round(im.naturalWidth * sc); c.height = Math.round(im.naturalHeight * sc);
    c.getContext('2d').drawImage(im, 0, 0, c.width, c.height);
    const png = f.type === 'image/png', id = uid();
    S.assets[id] = c.toDataURL(png ? 'image/png' : 'image/jpeg', 0.9);
    const fit = Math.min(1, (S.W * 0.7) / c.width, (S.H * 0.5) / c.height);
    addLayer('image', { asset: id, w: Math.round(c.width * fit), h: Math.round(c.height * fit) });
    URL.revokeObjectURL(url); switchTab('Props');
  };
  im.src = url; e.target.value = '';
};
function buildProject() {
  $('#aspects').innerHTML = Object.keys(ASPECTS).map(a => `<button data-aspect="${a}" class="${a === S.aspect ? 'on' : ''}">${a}</button>`).join('');
  $('#pDuration').value = S.duration; $('#pBg').value = S.bg; $('#pLoop').checked = S.loop;
}
$('#aspects').addEventListener('click', e => {
  const a = e.target.closest('[data-aspect]'); if (!a) return;
  applyAspect(true, a.dataset.aspect); commit(); buildProject(); buildProps();
});
$('#pDuration').onchange = e => {
  const d = clamp(parseFloat(e.target.value) || 5, 1, 120); S.duration = d;
  S.layers.forEach(L => { if (L.end > d) L.end = d; });
  S.time = Math.min(S.time, d); commit(); buildAll(); setTime(S.time);
};
$('#pBg').oninput = e => { S.bg = e.target.value; render(); };
$('#pBg').onchange = () => commit();
$('#pLoop').onchange = e => { S.loop = e.target.checked; };
$('#btnNew').onclick = () => {
  if (!confirm('Start a new project? The current one will be cleared.')) return;
  S.layers = []; S.assets = {}; S.sel = null; S.time = 0; S.duration = 5; S.bg = '#0d0e14';
  applyAspect(false, '9:16'); starter(); hist = []; hi = -1; commit(); buildAll(); setTime(0);
};

/* ---------- canvas touch: drag + pinch ---------- */
const ptrs = new Map(); let g = null;
const toCanvas = e => { const r = cv.getBoundingClientRect(); return { x: (e.clientX - r.left) * S.W / r.width, y: (e.clientY - r.top) * S.H / r.height }; };
cv.addEventListener('pointerdown', e => {
  cv.setPointerCapture(e.pointerId); ptrs.set(e.pointerId, { x: e.clientX, y: e.clientY }); stop();
  if (ptrs.size === 1) {
    const p = toCanvas(e), hit = hitTest(p);
    if (hit && hit.id !== S.sel) select(hit.id);
    const L = hit ? sel() : null;
    g = { mode: hit ? 'drag' : 'none', hit, sx: p.x, sy: p.y, moved: false, changed: false,
      ox: L ? evalProp(L, 'x', S.time) : 0, oy: L ? evalProp(L, 'y', S.time) : 0 };
  } else if (ptrs.size === 2 && sel()) {
    const L = sel(), [a, b] = [...ptrs.values()];
    g = { mode: 'pinch', d0: Math.hypot(b.x - a.x, b.y - a.y) || 1, a0: Math.atan2(b.y - a.y, b.x - a.x),
      s0: evalProp(L, 'scale', S.time), r0: evalProp(L, 'rotation', S.time), changed: false };
  }
});
cv.addEventListener('pointermove', e => {
  if (!ptrs.has(e.pointerId) || !g) return;
  ptrs.set(e.pointerId, { x: e.clientX, y: e.clientY });
  const L = sel(); if (!L) return;
  if (g.mode === 'drag' && ptrs.size === 1) {
    const p = toCanvas(e);
    if (Math.hypot(p.x - g.sx, p.y - g.sy) > 6) g.moved = true;
    if (g.moved) {
      setProp(L, 'x', g.ox + (p.x - g.sx), S.time); setProp(L, 'y', g.oy + (p.y - g.sy), S.time);
      g.changed = true; render(); syncProps();
    }
  } else if (g.mode === 'pinch' && ptrs.size === 2) {
    const [a, b] = [...ptrs.values()];
    const d = Math.hypot(b.x - a.x, b.y - a.y), ang = Math.atan2(b.y - a.y, b.x - a.x);
    setProp(L, 'scale', clamp(g.s0 * d / g.d0, 0, 5), S.time);
    setProp(L, 'rotation', g.r0 + (ang - g.a0) * 180 / Math.PI, S.time);
    g.changed = true; render(); syncProps();
  }
});
const endPtr = e => {
  ptrs.delete(e.pointerId);
  if (g && g.mode === 'none' && !ptrs.size && !g.moved) select(null);
  if (g && g.changed) { commit(); buildTimeline(); }
  if (!ptrs.size || (g && g.mode === 'pinch')) g = null;
};
cv.addEventListener('pointerup', endPtr);
cv.addEventListener('pointercancel', endPtr);

/* ---------- selection, tabs, toast ---------- */
function select(id) { S.sel = id; buildProps(); buildTimeline(); render(); }
function switchTab(n) {
  $$('#tabs button').forEach(b => b.classList.toggle('on', b.dataset.tab === n));
  $$('.pane').forEach(p => p.classList.toggle('on', p.id === 'pane' + n));
  if (n === 'Timeline') updatePlayhead();
}
$('#tabs').addEventListener('click', e => { const b = e.target.closest('[data-tab]'); if (b) switchTab(b.dataset.tab); });
let toastT;
function toast(msg) { const t = $('#toast'); t.textContent = msg; t.classList.add('on'); clearTimeout(toastT); toastT = setTimeout(() => t.classList.remove('on'), 2200); }

/* ---------- transport ---------- */
$('#btnPlay').onclick = () => playing ? stop() : play();
$('#btnStart').onclick = () => { stop(); setTime(0); };
$('#btnPrev').onclick = () => { stop(); setTime(S.time - 1 / FPS); };
$('#btnNext').onclick = () => { stop(); setTime(S.time + 1 / FPS); };
$('#scrub').addEventListener('input', e => { stop(); setTime(e.target.value / 1000 * S.duration); });
$('#btnUndo').onclick = undo; $('#btnRedo').onclick = redo;
document.addEventListener('visibilitychange', () => { if (document.hidden) stop(); });

/* ---------- export ---------- */
function blobToB64(blob) {
  return new Promise((res, rej) => { const r = new FileReader(); r.onload = () => res(String(r.result).split(',')[1]); r.onerror = rej; r.readAsDataURL(blob); });
}
async function saveBlob(blob, name) {
  const C = window.Capacitor;
  if (C && C.isNativePlatform && C.isNativePlatform() && C.Plugins && C.Plugins.Filesystem && C.Plugins.Share) {
    const data = await blobToB64(blob);
    const r = await C.Plugins.Filesystem.writeFile({ path: name, data, directory: 'CACHE' });
    await C.Plugins.Share.share({ title: name, url: r.uri, dialogTitle: 'Save or share your video' });
  } else {
    const a = document.createElement('a'); a.href = URL.createObjectURL(blob); a.download = name;
    document.body.appendChild(a); a.click(); a.remove(); setTimeout(() => URL.revokeObjectURL(a.href), 5000);
  }
}
async function doExport() {
  if (!window.MediaRecorder || !cv.captureStream) { toast('Video export is not supported on this device'); return; }
  stop();
  const modal = $('#modal'); let cancelled = false;
  modal.innerHTML = `<div class="card"><h3>Rendering video</h3><div class="pbar"><i id="pfill"></i></div><p id="ptxt" class="dim">0%</p><button id="pcancel">Cancel</button></div>`;
  modal.classList.add('on'); $('#pcancel').onclick = () => { cancelled = true; };
  const prevT = S.time; exporting = true;
  const types = ['video/mp4;codecs=avc1', 'video/mp4', 'video/webm;codecs=vp9', 'video/webm;codecs=vp8', 'video/webm'];
  const mime = types.find(t => MediaRecorder.isTypeSupported(t)) || '';
  const stream = cv.captureStream(FPS), chunks = [];
  let rec;
  try { rec = new MediaRecorder(stream, mime ? { mimeType: mime, videoBitsPerSecond: 10000000 } : {}); }
  catch (err) { exporting = false; modal.classList.remove('on'); toast('Could not start the video recorder'); return; }
  rec.ondataavailable = e => { if (e.data && e.data.size) chunks.push(e.data); };
  const stopped = new Promise(r => { rec.onstop = r; });
  S.time = 0; render(); rec.start(250);
  const t0 = performance.now();
  await new Promise(res => {
    const step = now => {
      if (cancelled) return res();
      const el = (now - t0) / 1000;
      S.time = Math.min(el, S.duration); render();
      const pct = Math.min(100, Math.round(el / S.duration * 100));
      $('#pfill').style.width = pct + '%'; $('#ptxt').textContent = pct + '%';
      if (el < S.duration + 0.2) requestAnimationFrame(step); else res();
    };
    requestAnimationFrame(step);
  });
  rec.stop(); await stopped; stream.getTracks().forEach(t => t.stop());
  exporting = false; setTime(prevT);
  modal.classList.remove('on');
  if (cancelled) return;
  const type = rec.mimeType || mime || 'video/webm', ext = type.includes('mp4') ? 'mp4' : 'webm';
  try { await saveBlob(new Blob(chunks, { type }), `motion-${Date.now()}.${ext}`); toast('Video ready'); }
  catch (err) { toast('Could not save the video'); }
}
$('#btnExport').onclick = doExport;

/* ---------- init ---------- */
function buildAll() { buildTimeline(); buildProps(); buildProject(); updateUndoUI(); }
function starter() {
  const bg = newLayer('rect', { name: 'Card', w: 560, h: 360, radius: 40, color: '#ff5a7a' });
  const tx = newLayer('text', { name: 'Title', text: 'Motion\nStudio', size: 120, color: '#ffffff' });
  S.layers.push(bg, tx);
  PRESETS['Pop in'](bg, 0.2); PRESETS['Slide up'](tx, 0.6);
  S.sel = tx.id;
}
(function init() {
  const had = load();
  applyAspect(false);
  if (!had) starter();
  hist = []; hi = -1; commit(); buildAll();
  new ResizeObserver(fitCanvas).observe($('#stage'));
  window.addEventListener('resize', fitCanvas);
  setTime(0);
  requestAnimationFrame(fitCanvas);
})();
})();
