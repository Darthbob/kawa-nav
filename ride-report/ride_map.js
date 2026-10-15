/* KawaNav ride report: the ride on an interactive map.

   Plain JavaScript and no library, like the rest of the report: the track, the moments, the cursor and
   every control are drawn by this file. Only the background under the track comes from outside: the
   tile layers listed in the ride's data, each asked only for the squares in view. With none of them
   reachable the track is drawn on a plain background and everything else still works.

   ride_report.py writes the ride as JSON into the element with the id "ride-data" and puts this file
   after it, at the end of the page, so the charts and the table of moments are already there to be
   tied to the map. */
(() => {
  "use strict";

  const root = document.getElementById("ride-map");
  const dataEl = document.getElementById("ride-data");
  if (!root || !dataEl) return;
  const D = JSON.parse(dataEl.textContent);
  const N = D.t.length;
  // a track with no value to colour it by (a ride with the position alone) is drawn in one colour
  if (!D.colours.length) D.colours.push({ key: "", label: "One colour", unit: "", decimals: 0, scale: "plain" });
  const T = Math.max(1, D.t[N - 1]);          // ms from the first second to the last, as the charts use

  // ------------------------------------------------------------------ colours

  // The report's palette: one blue ramp for magnitude, its steps far enough apart to tell neighbours.
  const BLUE = {
    100: "#cde2fb", 150: "#b7d3f6", 200: "#9ec5f4", 250: "#86b6ef", 300: "#6da7ec", 350: "#5598e7",
    400: "#3987e5", 450: "#2a78d6", 500: "#256abf", 550: "#1c5cab", 600: "#184f95", 650: "#104281",
    700: "#0d366b",
  };
  const steps = (list) => list.map((s) => BLUE[s]);
  const RAMPS = {
    // how much: one hue, the low end receding toward the background under the track
    seq: {
      light: steps([250, 300, 350, 400, 450, 500, 550, 600, 650, 700]),
      dark: steps([600, 550, 500, 450, 400, 350, 300, 250, 200, 150]),
    },
    // gears 1 to 6 (on a light background the 5th and the 6th are the closest pair: the legend and
    // the readout name the gear)
    gear: { light: steps([250, 350, 450, 550, 650, 700]), dark: steps([600, 500, 400, 300, 200, 100]) },
    // forward acceleration: braking red, accelerating blue, grey for neither (steps taken in OKLab)
    div: {
      light: ["#e34948", "#ea7069", "#f09289", "#f2b1aa", "#f3d0cb", "#f0efec",
              "#c9d8ea", "#a3c1e7", "#7da9e2", "#5691dd", "#2a78d6"],
      dark: ["#e66767", "#c15f5d", "#9e5653", "#7b4d49", "#5a433f", "#383835",
             "#3b4856", "#3d5878", "#3e689b", "#3c77bf", "#3987e5"],
    },
  };
  const NEUTRAL = "#898781";

  // the report's own colours (ink, surface, one per kind of moment), from its stylesheet
  const prefersDark = matchMedia("(prefers-color-scheme: dark)");
  let theme = {};
  function readTheme() {
    const cs = getComputedStyle(root);
    const v = (name, fallback) => cs.getPropertyValue(name).trim() || fallback;
    theme = {
      ink: v("--ink", "#0b0b0b"), ink2: v("--ink-2", "#52514e"), line: v("--line", "#e1e0d9"),
      surface: v("--surface", "#fcfcfb"), kinds: {},
    };
    for (const kd of D.kinds) theme.kinds[kd.id] = v("--k-" + kd.id, NEUTRAL);
  }
  const kindColour = (kind) => theme.kinds[kind] || NEUTRAL;

  function luminance(hex) {
    const c = [1, 3, 5].map((i) => parseInt(hex.slice(i, i + 2), 16) / 255)
      .map((v) => (v <= 0.04045 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4));
    return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2];
  }
  // the letter on a marker: white or ink, whichever stands out more from the marker's colour
  const inks = {};
  function inkOn(hex) {
    if (!(hex in inks)) {
      const l = /^#[0-9a-f]{6}$/i.test(hex) ? luminance(hex) : 0;
      inks[hex] = 1.05 / (l + 0.05) >= (l + 0.05) / (luminance("#0b0b0b") + 0.05) ? "#fff" : "#0b0b0b";
    }
    return inks[hex];
  }

  // ------------------------------------------------------------------ numbers

  const clamp = (v, lo, hi) => Math.max(lo, Math.min(hi, v));
  const num = (v, d) => v.toLocaleString("en-US", { minimumFractionDigits: d, maximumFractionDigits: d });
  // minutes and seconds from the start, the way the table of moments writes them
  const clock = (ms) => {
    const s = Math.round(ms / 1000);
    return Math.floor(s / 60) + ":" + String(s % 60).padStart(2, "0");
  };
  const withUnit = (text, unit) => (!unit ? text : /^[°%]/.test(unit) ? text + unit : text + " " + unit);

  function valueText(def, v) {
    if (def.key === "gear") return v === 0 ? "N" : String(Math.round(v));
    const text = withUnit(num(def.abs ? Math.abs(v) : v, def.decimals), def.unit);
    return def.signed && v > 0 ? "+" + text : text;
  }

  // the values of one second, for the tooltip and the readout: value first, what it is after
  function readings(i) {
    const out = [];
    for (const def of D.readout) {
      const v = (D.series[def.key] || [])[i];
      if (v != null) out.push({ value: valueText(def, v), label: def.label });
    }
    return out;
  }

  // the row whose time is nearest to ms
  function indexAt(ms) {
    let lo = 0, hi = N - 1;
    while (hi - lo > 1) {
      const mid = (lo + hi) >> 1;
      if (D.t[mid] < ms) lo = mid; else hi = mid;
    }
    return ms - D.t[lo] <= D.t[hi] - ms ? lo : hi;
  }

  // ------------------------------------------------------------------ the track, in Web Mercator

  const mercX = (lon) => (lon + 180) / 360;
  const mercY = (lat) => {
    const s = Math.sin((lat * Math.PI) / 180);
    return 0.5 - Math.log((1 + s) / (1 - s)) / (4 * Math.PI);
  };

  const MX = new Float64Array(N), MY = new Float64Array(N);   // the track, 0..1 across the world
  const PX = new Float64Array(N), PY = new Float64Array(N);   // and where it was drawn last
  const fixes = [];                                             // the rows that have a position
  for (let i = 0; i < N; i++) {
    if (D.lat[i] == null || D.lon[i] == null) continue;
    MX[i] = mercX(D.lon[i]);
    MY[i] = mercY(D.lat[i]);
    fixes.push(i);
  }
  if (fixes.length < 2) return;

  // the fix nearest in time to each row, so a second without one still has a place on the map
  const FIX = new Int32Array(N);
  for (let i = 0, j = 0; i < N; i++) {
    while (j + 1 < fixes.length && Math.abs(fixes[j + 1] - i) <= Math.abs(fixes[j] - i)) j++;
    FIX[i] = Math.abs(D.t[fixes[j]] - D.t[i]) <= 10000 ? fixes[j] : -1;
  }

  // a segment joins two fixes at most 5 s apart: a longer gap (a tunnel, a lost fix) stays open
  const SA = [], SB = [];
  for (let k = 1; k < fixes.length; k++) {
    const a = fixes[k - 1], b = fixes[k];
    if (D.t[b] - D.t[a] <= 5000) { SA.push(a); SB.push(b); }
  }
  const ALL = SA.map((_, k) => k);

  let bx0 = 1, bx1 = 0, by0 = 1, by1 = 0;
  for (const i of fixes) {
    bx0 = Math.min(bx0, MX[i]); bx1 = Math.max(bx1, MX[i]);
    by0 = Math.min(by0, MY[i]); by1 = Math.max(by1, MY[i]);
  }

  const events = D.events.map((e, k) => ({
    ...e, k, x: e.lat == null ? null : mercX(e.lon), y: e.lat == null ? null : mercY(e.lat),
  }));
  const kinds = {};
  for (const kd of D.kinds) kinds[kd.id] = kd;

  // ------------------------------------------------------------------ the page around the map

  function h(tag, attrs, ...kids) {
    const el = document.createElement(tag);
    for (const [k, v] of Object.entries(attrs || {})) {
      if (v == null || v === false) continue;
      if (k === "class") el.className = v;
      else if (k === "text") el.textContent = v;
      else el.setAttribute(k, v === true ? "" : v);
    }
    for (const kid of kids) if (kid != null) el.append(kid);
    return el;
  }
  const glyph = (kind) => h("span", { class: "glyph k-" + kind, text: (kinds[kind] || {}).glyph || "•" });

  // what the reader chose last time, in this browser: a convenience, never needed
  const PREFS = "kawanav-ride-map";
  let prefs = {};
  try { prefs = JSON.parse(localStorage.getItem(PREFS) || "{}") || {}; } catch (_) { prefs = {}; }

  const st = {
    cx: 0.5, cy: 0.5, z: 3, W: 0, H: 0,
    layer: D.layers.length ? Math.max(0, D.layers.findIndex((l) => l.id === prefs.layer)) : -1,
    colour: Math.max(0, D.colours.findIndex((c) => c.key === prefs.colour)),
    cursor: -1, pinned: -1,
    shown: new Set(D.kinds.filter((kd) => !kd.hidden).map((kd) => kd.id)),
  };
  if (prefs.layer === "none") st.layer = -1;

  function savePrefs() {
    try {
      localStorage.setItem(PREFS, JSON.stringify({
        layer: st.layer < 0 ? "none" : D.layers[st.layer].id, colour: D.colours[st.colour].key,
        speed: speedSel.value,
      }));
    } catch (_) { /* a private window: nothing to remember */ }
  }

  // above the map: what colours the track, the background, which moments are shown
  const colourSel = h("select", { "aria-label": "Colour the track by" });
  D.colours.forEach((c, k) => colourSel.append(h("option", { value: k, text: c.label })));
  colourSel.value = st.colour;
  const legend = h("span", { class: "legend", "aria-live": "polite" });
  const layerSel = h("select", { "aria-label": "Background" });
  D.layers.forEach((l, k) => layerSel.append(h("option", { value: k, text: l.name })));
  layerSel.append(h("option", { value: -1, text: "None" }));
  layerSel.value = st.layer;
  const tools = h("div", { class: "map-tools" },
    h("label", {}, "Colour ", colourSel), legend,
    D.layers.length ? h("label", { class: "push" }, "Background ", layerSel) : null);

  const chips = h("div", { class: "map-chips", role: "group", "aria-label": "Moments on the map" });
  for (const kd of D.kinds) {
    const count = events.filter((e) => e.kind === kd.id).length;
    const chip = h("button", { type: "button", class: "chip", "aria-pressed": String(st.shown.has(kd.id)),
                               title: "Show or hide these moments on the map" },
                   glyph(kd.id), h("span", { text: kd.label }), h("span", { class: "count", text: String(count) }));
    chip.addEventListener("click", () => {
      if (st.shown.has(kd.id)) st.shown.delete(kd.id); else st.shown.add(kd.id);
      if (st.pinned >= 0 && !st.shown.has(events[st.pinned].kind)) unpin();
      syncChips();
      redraw(); drawStrip();
    });
    chip.dataset.kind = kd.id;
    chips.append(chip);
  }
  function syncChips() {
    for (const chip of chips.children) chip.setAttribute("aria-pressed", String(st.shown.has(chip.dataset.kind)));
  }

  // the map
  const pane = h("div", { class: "map-tiles" });
  const cv = h("canvas", { class: "map-canvas" });
  const tip = h("div", { class: "map-tip", hidden: true });
  const button = (act, label, title) =>
    h("button", { type: "button", "data-act": act, title, "aria-label": title, text: label });
  const fullBtn = button("full", "⛶", "Full screen");
  const zoomCtl = h("div", { class: "map-ctl" },
    button("in", "+", "Zoom in"), button("out", "−", "Zoom out"),
    button("fit", "⤢", "Show the whole ride"), fullBtn);
  const scaleBar = h("div", { class: "map-scale" });
  const credit = h("div", { class: "map-credit" });
  const note = h("div", { class: "map-note", hidden: true, role: "status" });
  const hint = h("div", { class: "map-note", hidden: true });
  const view = h("div", {
    class: "map-view", tabindex: "0", role: "application", "aria-roledescription": "map",
    "aria-label": "The ride's track. Drag or use the arrow keys to move it, + and − to zoom.",
  }, pane, cv, zoomCtl, scaleBar, credit, note, hint, tip);

  // under the map: the replay, the ride over time, the values at the cursor
  const play = h("button", { type: "button", class: "play", title: "Replay the ride", "aria-label": "Replay the ride", text: "▶" });
  const speedSel = h("select", { "aria-label": "Replay speed" });
  for (const x of [10, 30, 60, 120]) speedSel.append(h("option", { value: x, text: x + "×" }));
  speedSel.value = [10, 30, 60, 120].includes(+prefs.speed) ? prefs.speed : 30;
  const strip = h("canvas", { class: "map-strip", "aria-label": "The ride over time: point at it or drag along it to move the cursor" });
  const readout = h("div", { class: "map-readout" },
    "Point at the track, at the strip under the map or at a chart to read that second.");
  const caption = h("p", { class: "dim map-caption" });

  root.append(...[tools, chips.children.length ? chips : null, view,
                  h("div", { class: "map-bar" }, play, speedSel, strip), readout, caption].filter(Boolean));

  const ctx = cv.getContext("2d");
  const sctx = strip.getContext("2d");
  let dpr = window.devicePixelRatio || 1;

  // ------------------------------------------------------------------ colouring the track

  let palette = [], buckets = [], spare = [], domain = null;

  const baseDark = () => (st.layer >= 0 ? !!D.layers[st.layer].dark : prefersDark.matches);

  function valuesOf(def) {
    const raw = D.series[def.key] || [];
    return def.abs ? raw.map((v) => (v == null ? null : Math.abs(v))) : raw;
  }

  function recolour() {
    const def = D.colours[st.colour];
    const mode = baseDark() ? "dark" : "light";
    const vals = valuesOf(def);
    let lo = Infinity, hi = -Infinity;
    const mags = [];
    for (const i of fixes) {
      const v = vals[i];
      if (v == null) continue;
      lo = Math.min(lo, v); hi = Math.max(hi, v);
      mags.push(Math.abs(v));
    }
    let bucketOf;
    if (def.scale === "plain") {
      domain = { lo: 0, hi: 1 };
      palette = [BLUE[mode === "dark" ? 300 : 500]];
      bucketOf = () => 0;
    } else if (def.scale === "div") {
      // symmetric around zero, the scale set by all but the strongest 2 % so one jolt does not wash it out
      mags.sort((a, b) => a - b);
      const m = Math.max(mags.length ? mags[Math.floor((mags.length - 1) * 0.98)] : 0, 0.05);
      domain = { lo: -m, hi: m };
      palette = RAMPS.div[mode];
      bucketOf = (v) => clamp(Math.round((v / m) * 5), -5, 5) + 5;
    } else if (def.scale === "gear") {
      domain = { lo: 0, hi: 6 };
      palette = RAMPS.gear[mode];
      bucketOf = (v) => (v >= 1 ? Math.min(palette.length, Math.round(v)) - 1 : -1);
    } else {
      if (def.lo != null) lo = Math.min(lo, def.lo);
      if (!isFinite(lo)) { lo = 0; hi = 1; }
      if (!(hi > lo)) hi = lo + 1;
      domain = { lo, hi };
      palette = RAMPS.seq[mode];
      bucketOf = (v) => clamp(Math.floor(((v - lo) / (hi - lo)) * palette.length), 0, palette.length - 1);
    }
    buckets = palette.map(() => []);
    spare = [];
    for (let k = 0; k < SA.length; k++) {
      let v = vals[SB[k]];
      if (v == null) v = vals[SA[k]];
      const b = def.scale === "plain" ? 0 : v == null ? -1 : bucketOf(v);
      (b < 0 ? spare : buckets[b]).push(k);
    }
    showLegend(def);
  }

  function showLegend(def) {
    const swatch = (colour) => h("i", { style: "background:" + colour });
    legend.replaceChildren();
    if (def.scale === "plain") return;
    if (def.scale === "gear") {
      const used = new Set((D.series.gear || []).filter((v) => v != null).map((v) => Math.round(v)));
      if (used.has(0)) legend.append(h("span", { class: "lg" }, swatch(NEUTRAL), "N"));
      palette.forEach((c, k) => { if (used.has(k + 1)) legend.append(h("span", { class: "lg" }, swatch(c), String(k + 1))); });
      return;
    }
    const ramp = h("span", { class: "ramp", "aria-hidden": "true" }, ...palette.map(swatch));
    const fmt = (v) => valueText(def, v);
    if (def.scale === "div") {
      legend.append(h("span", { text: "braking " + fmt(domain.lo) }), ramp, h("span", { text: fmt(domain.hi) + " accelerating" }));
    } else {
      legend.append(h("span", { text: fmt(domain.lo) }), ramp, h("span", { text: fmt(domain.hi) }));
    }
  }

  // ------------------------------------------------------------------ the view

  const ZMIN = 3, ZMAX = 19;
  const scale = () => 256 * 2 ** st.z;                        // CSS pixels across the world
  const clampZ = (z) => clamp(z, ZMIN, ZMAX);
  const toScreen = (x, y) => [(x - st.cx) * scale() + st.W / 2, (y - st.cy) * scale() + st.H / 2];
  const toWorld = (px, py) => [st.cx + (px - st.W / 2) / scale(), st.cy + (py - st.H / 2) / scale()];

  function keepNearTrack() {
    // the track may be pushed to the edge, never out of sight
    const mx = st.W / 2 / scale(), my = st.H / 2 / scale();
    st.cx = clamp(st.cx, bx0 - mx * 0.9, bx1 + mx * 0.9);
    st.cy = clamp(st.cy, by0 - my * 0.9, by1 + my * 0.9);
  }

  function fitZoom() {
    const pad = 36;
    const dx = Math.max((bx1 - bx0) * 256, 1e-9), dy = Math.max((by1 - by0) * 256, 1e-9);
    const z = Math.log2(Math.min((st.W - 2 * pad) / dx, (st.H - 2 * pad) / dy));
    return clamp(Math.floor(z * 4) / 4, ZMIN, 17);
  }

  function fit(animated) {
    const cx = (bx0 + bx1) / 2, cy = (by0 + by1) / 2, z = fitZoom();
    if (animated) flyTo(cx, cy, z);
    else { st.cx = cx; st.cy = cy; st.z = z; redraw(); }
  }

  let frame = 0;
  const redraw = () => { if (!frame) frame = requestAnimationFrame(draw); };

  // while the zoom is moving, the levels it passes through are not fetched
  let zoomingUntil = 0, settleTimer = 0;
  function zoomMoving() {
    zoomingUntil = performance.now() + 160;
    clearTimeout(settleTimer);
    settleTimer = setTimeout(redraw, 180);
  }

  let anim = 0;
  function stopAnimation() { if (anim) cancelAnimationFrame(anim); anim = 0; }
  function animate(ms, zooms, apply) {
    stopAnimation();
    const t0 = performance.now();
    const step = (now) => {
      const p = clamp((now - t0) / ms, 0, 1);                 // a frame's time can precede the start
      apply(1 - (1 - p) ** 3);
      if (zooms) zoomingUntil = p < 1 ? now + 160 : 0;
      draw();
      anim = p < 1 ? requestAnimationFrame(step) : 0;
    };
    anim = requestAnimationFrame(step);
  }

  function flyTo(cx, cy, z, ms = 450) {
    const a = { cx: st.cx, cy: st.cy, z: st.z };
    z = clampZ(z);
    animate(ms, Math.abs(z - a.z) > 1e-6, (e) => {
      st.cx = a.cx + (cx - a.cx) * e; st.cy = a.cy + (cy - a.cy) * e; st.z = a.z + (z - a.z) * e;
    });
  }

  // zoom keeping the place under (px, py) where it is
  function zoomAround(px, py, z) {
    const [wx, wy] = toWorld(px, py);
    st.z = clampZ(z);
    st.cx = wx - (px - st.W / 2) / scale();
    st.cy = wy - (py - st.H / 2) / scale();
    keepNearTrack();
  }
  function zoomTo(px, py, z) {
    const z0 = st.z, z1 = clampZ(z), [wx, wy] = toWorld(px, py);
    animate(260, true, (e) => {
      st.z = z0 + (z1 - z0) * e;
      st.cx = wx - (px - st.W / 2) / scale();
      st.cy = wy - (py - st.H / 2) / scale();
    });
  }

  // ------------------------------------------------------------------ the tiles under the track

  const tiles = new Map();
  let tilesOk = 0, tilesBad = 0;

  function tileUrl(L, z, x, y) {
    const sub = L.sub ? L.sub[(x + y) % L.sub.length] : "";
    return L.url.replace("{s}", sub).replace("{z}", z).replace("{x}", x).replace("{y}", y);
  }

  function addTile(L, z, x, y, key) {
    const img = new Image();
    const t = { img, z, x, y, done: false, layer: L.id };
    img.alt = "";
    img.draggable = false;
    img.decoding = "async";
    img.onload = () => {
      t.done = true;
      img.classList.add("on");
      if (st.layer >= 0 && D.layers[st.layer].id === t.layer) { tilesOk++; note.hidden = true; }
      redraw();
    };
    img.onerror = () => {
      t.done = true;
      if (st.layer >= 0 && D.layers[st.layer].id === t.layer) {
        tilesBad++;
        if (!tilesOk && tilesBad >= 3) {
          note.textContent = "The background could not be loaded (no internet?). The track does not need it.";
          note.hidden = false;
        }
      }
      redraw();
    };
    img.src = tileUrl(L, z, x, y);
    tiles.set(key, t);
    pane.append(img);
    return t;
  }

  function dropTiles() {
    for (const t of tiles.values()) t.img.remove();
    tiles.clear();
  }

  function tileBox(t) {
    const k = 256 * 2 ** (st.z - t.z), n = 2 ** t.z;
    const at = (v, c, size) => Math.round((v - c * n) * k + size / 2);
    return [at(t.x, st.cx, st.W), at(t.y, st.cy, st.H), at(t.x + 1, st.cx, st.W), at(t.y + 1, st.cy, st.H)];
  }

  function placeTiles() {
    const L = D.layers[st.layer];
    if (!L) { if (tiles.size) dropTiles(); return; }
    // between two levels, the tiles are drawn from 0.62 to 1.23 times their size: enlarged a little at
    // most, so their lettering stays sharp
    const z = clamp(Math.floor(st.z + 0.7), 0, L.max);
    const n = 2 ** z, k = 256 * 2 ** (st.z - z);
    const fx = st.cx * n, fy = st.cy * n;
    const x0 = Math.max(0, Math.floor(fx - st.W / 2 / k)), x1 = Math.min(n - 1, Math.floor(fx + st.W / 2 / k));
    const y0 = Math.max(0, Math.floor(fy - st.H / 2 / k)), y1 = Math.min(n - 1, Math.floor(fy + st.H / 2 / k));
    const load = performance.now() >= zoomingUntil;
    const want = new Set();
    let complete = true;
    for (let y = y0; y <= y1; y++) {
      for (let x = x0; x <= x1; x++) {
        const key = L.id + "/" + z + "/" + x + "/" + y;
        want.add(key);
        let t = tiles.get(key);
        if (!t && load) t = addTile(L, z, x, y, key);
        if (!t || !t.done) complete = false;
      }
    }
    for (const [key, t] of tiles) {
      const box = tileBox(t);
      const onScreen = box[2] > 0 && box[0] < st.W && box[3] > 0 && box[1] < st.H;
      // a level the zoom has left stays as a stand-in until this one has arrived
      const keep = t.z === z ? want.has(key) : t.done && !complete && onScreen;
      if (!keep || t.layer !== L.id) { t.img.remove(); tiles.delete(key); continue; }
      const s = t.img.style;
      s.transform = "translate(" + box[0] + "px," + box[1] + "px)";
      s.width = box[2] - box[0] + "px";
      s.height = box[3] - box[1] + "px";
      s.zIndex = t.z === z ? 2 : 1;
    }
  }

  function setLayer(k) {
    st.layer = k;
    dropTiles();
    tilesOk = tilesBad = 0;
    note.hidden = true;
    const L = D.layers[k];
    credit.innerHTML = L ? L.credit : "";                     // our own constant, with its links
    credit.hidden = !L;
    view.dataset.layer = L ? L.id : "none";
    view.dataset.base = baseDark() ? "dark" : "light";
    caption.textContent = L
      ? "The background comes from " + L.source + ", asked for the squares in view when the report opens; " +
        "with “None” the report asks no server for anything."
      : "No background: the track is drawn on its own, and the report asks no server for anything.";
    recolour();
    redraw(); drawStrip();
  }

  // ------------------------------------------------------------------ drawing

  let hits = [];                                              // the markers drawn, for pointing at them

  function strokeSegments(list) {
    ctx.beginPath();
    let last = -1;
    for (const k of list) {
      const a = SA[k], b = SB[k];
      if (a !== last) ctx.moveTo(PX[a], PY[a]);
      ctx.lineTo(PX[b], PY[b]);
      last = b;
    }
    ctx.stroke();
  }

  function marker(x, y, fill, letter, r) {
    ctx.beginPath();
    ctx.arc(x, y, r + 2, 0, 2 * Math.PI);
    ctx.fillStyle = theme.surface;
    ctx.fill();
    ctx.beginPath();
    ctx.arc(x, y, r, 0, 2 * Math.PI);
    ctx.fillStyle = fill;
    ctx.fill();
    if (letter) {
      ctx.fillStyle = inkOn(fill);
      ctx.font = "700 " + Math.round(r * 1.35) + "px system-ui, sans-serif";
      ctx.textAlign = "center";
      ctx.textBaseline = "middle";
      ctx.fillText(letter, x, y + 0.5);
    }
  }

  function draw() {
    frame = 0;
    if (!st.W || !st.H) return;
    placeTiles();
    const s = scale(), hw = st.W / 2, hh = st.H / 2;
    for (const i of fixes) {
      PX[i] = (MX[i] - st.cx) * s + hw;
      PY[i] = (MY[i] - st.cy) * s + hh;
    }
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    ctx.clearRect(0, 0, st.W, st.H);
    ctx.lineCap = "round";
    ctx.lineJoin = "round";

    // the track: a casing for contrast with whatever is under it, then the colour of each second
    const width = clamp(st.z - 9, 3, 6);
    const dark = baseDark();
    ctx.strokeStyle = dark ? "rgba(252,252,251,0.8)" : "rgba(11,11,11,0.55)";
    ctx.lineWidth = width + 3;
    strokeSegments(ALL);
    ctx.lineWidth = width;
    ctx.strokeStyle = NEUTRAL;
    strokeSegments(spare);
    buckets.forEach((list, b) => {
      if (!list.length) return;
      ctx.strokeStyle = palette[b];
      strokeSegments(list);
    });

    // start and finish
    const ink = theme.ink;
    const first = fixes[0], last = fixes[fixes.length - 1];
    marker(PX[last], PY[last], ink, "■", 7);
    marker(PX[first], PY[first], ink, "▶", 7);

    // the moments: gear changes underneath, the rarer ones on top, the pinned one above all
    hits = [];
    const shown = events.filter((e) => e.x != null && st.shown.has(e.kind));
    const order = shown.filter((e) => e.kind === "shift").concat(shown.filter((e) => e.kind !== "shift"));
    for (const e of order) {
      if (e.k === st.pinned) continue;
      const [x, y] = toScreen(e.x, e.y);
      if (x < -20 || y < -20 || x > st.W + 20 || y > st.H + 20) continue;
      marker(x, y, kindColour(e.kind), kinds[e.kind] ? kinds[e.kind].glyph : "", e.kind === "shift" ? 6 : 7.5);
      hits.push({ x, y, k: e.k });
    }

    // the cursor: the second being read
    const at = st.cursor >= 0 ? FIX[st.cursor] : -1;
    if (at >= 0) {
      ctx.beginPath();
      ctx.arc(PX[at], PY[at], 7, 0, 2 * Math.PI);
      ctx.fillStyle = theme.surface;
      ctx.fill();
      ctx.lineWidth = 3;
      ctx.strokeStyle = ink;
      ctx.stroke();
    }

    if (st.pinned >= 0) {
      const e = events[st.pinned];
      const [x, y] = toScreen(e.x, e.y);
      marker(x, y, kindColour(e.kind), kinds[e.kind] ? kinds[e.kind].glyph : "", 10);
      hits.push({ x, y, k: e.k });
      if (x < 0 || y < 0 || x > st.W || y > st.H) tip.hidden = true;
      else placeTip(x, y, 14);
    }

    drawScale();
  }

  function drawScale() {
    const lat = (Math.atan(Math.sinh(Math.PI * (1 - 2 * st.cy))) * 180) / Math.PI;
    const metresPerPx = (40075016.686 * Math.cos((lat * Math.PI) / 180)) / scale();
    const max = 110 * metresPerPx;
    const p = 10 ** Math.floor(Math.log10(max));
    const d = [5, 2, 1].map((f) => f * p).find((v) => v <= max) || p;
    scaleBar.style.width = Math.round(d / metresPerPx) + "px";
    scaleBar.textContent = d >= 1000 ? d / 1000 + " km" : d + " m";
  }

  // the ride over time, under the map: the colouring channel, the moments, the cursor
  function drawStrip() {
    const w = strip.width / dpr, hh = strip.height / dpr;
    if (!w || !hh) return;
    const g = sctx;
    g.setTransform(dpr, 0, 0, dpr, 0, 0);
    g.clearRect(0, 0, w, hh);
    const def = D.colours[st.colour], vals = valuesOf(def);
    const top = 4, bottom = hh - 9;
    const X = (i) => (D.t[i] / T) * w;
    const Y = (v) => bottom - ((clamp(v, domain.lo, domain.hi) - domain.lo) / (domain.hi - domain.lo)) * (bottom - top);
    g.strokeStyle = theme.line;
    g.lineWidth = 1;
    g.beginPath();
    const base = def.scale === "div" ? Y(0) : bottom;
    g.moveTo(0, Math.round(base) + 0.5);
    g.lineTo(w, Math.round(base) + 0.5);
    g.stroke();
    g.beginPath();
    let open = false;
    for (let i = 0; i < N; i++) {
      const v = vals[i];
      if (v == null) { open = false; continue; }
      if (!open) { g.moveTo(X(i), Y(v)); open = true; } else g.lineTo(X(i), Y(v));
    }
    g.strokeStyle = theme.ink2;
    g.lineWidth = 1.5;
    g.lineJoin = "round";
    g.stroke();
    for (const e of events) {
      if (!st.shown.has(e.kind)) continue;
      g.fillStyle = kindColour(e.kind);
      g.fillRect(Math.round(X(e.i)) - 1, hh - 6, 3, 6);
    }
    if (st.cursor >= 0) {
      g.fillStyle = theme.ink;
      g.fillRect(Math.round(X(st.cursor)) - 1, 0, 2, hh);
    }
  }

  // ------------------------------------------------------------------ tooltips

  function placeTip(x, y, gap) {
    tip.hidden = false;
    const w = tip.offsetWidth, hgt = tip.offsetHeight;
    let left = x + gap, top = y + gap;
    if (left + w > st.W - 6) left = x - gap - w;
    if (top + hgt > st.H - 6) top = y - gap - hgt;
    tip.style.left = Math.max(6, left) + "px";
    tip.style.top = Math.max(6, top) + "px";
  }

  function pointTip(i) {
    const grid = h("div", { class: "tip-grid" });
    for (const r of readings(i)) grid.append(h("b", { text: r.value }), h("span", { text: r.label }));
    tip.replaceChildren(h("div", { class: "tip-head", text: clock(D.t[i]) }), grid);
  }

  function eventTip(k) {
    const e = events[k];
    tip.replaceChildren(
      h("div", { class: "tip-head" }, glyph(e.kind), " " + e.time + " · " + e.title),
      h("div", { class: "tip-detail", text: e.detail }));
  }

  function hideTip() { if (st.pinned < 0) tip.hidden = true; }

  // ------------------------------------------------------------------ one second, everywhere

  const charts = [...document.querySelectorAll("svg[data-key]")].map((svg) => ({
    svg, key: svg.dataset.key, left: +svg.dataset.left, w: +svg.dataset.w, vw: +svg.dataset.vw,
    decimals: +svg.dataset.decimals || 0, unit: svg.dataset.unit || "",
    line: svg.querySelector(".cursor"), at: svg.closest("figure").querySelector(".at"),
  }));

  const rows = [...document.querySelectorAll("tr[data-ev]")];

  function setCursor(i, from) {
    if (i < 0 || i >= N) return;
    if (i === st.cursor) return;
    st.cursor = i;
    if (from === "strip" || from === "play") keepInView(i);
    redraw();
    drawStrip();
    const parts = [h("b", { text: clock(D.t[i]) })];
    for (const r of readings(i)) parts.push(h("span", {}, r.label + " ", h("b", { text: r.value })));
    if (FIX[i] < 0) parts.push(h("span", { class: "dim", text: "no position this second" }));
    readout.replaceChildren(...parts);
    for (const c of charts) {
      const x = c.left + (c.w * D.t[i]) / T;
      c.line.setAttribute("x1", x.toFixed(1));
      c.line.setAttribute("x2", x.toFixed(1));
      c.line.removeAttribute("visibility");
      const v = (D.series[c.key] || [])[i];
      if (c.at) {
        c.at.textContent = " · at " + clock(D.t[i]) + ": " +
          (v == null ? "no value" : withUnit(num(v, c.decimals), c.unit));
      }
    }
  }

  function keepInView(i) {
    const f = FIX[i];
    if (f < 0 || drag || pinch) return;
    const [x, y] = toScreen(MX[f], MY[f]);
    const m = Math.min(60, st.W / 5, st.H / 5);
    if (x < m || y < m || x > st.W - m || y > st.H - m) {
      stopAnimation();
      st.cx = MX[f];
      st.cy = MY[f];
    }
  }

  function pin(k) {
    st.pinned = k;
    eventTip(k);
    rows.forEach((tr) => tr.classList.toggle("is-on", +tr.dataset.ev === k));
    redraw();
  }

  function unpin() {
    if (st.pinned < 0) return;
    st.pinned = -1;
    tip.hidden = true;
    rows.forEach((tr) => tr.classList.remove("is-on"));
    redraw();
  }

  function showEvent(k) {
    const e = events[k];
    if (!e) return;
    if (!st.shown.has(e.kind)) { st.shown.add(e.kind); syncChips(); drawStrip(); }
    setCursor(e.i, "event");
    if (e.x == null) return;
    pin(k);
    const box = root.getBoundingClientRect();
    if (box.top < 0 || box.bottom > window.innerHeight) root.scrollIntoView({ behavior: "smooth", block: "nearest" });
    flyTo(e.x, e.y, Math.max(Math.round(st.z), 15));
  }

  // ------------------------------------------------------------------ pointing, dragging, zooming

  const pointers = new Map();
  let drag = null, pinch = null, lastTap = { t: 0, x: 0, y: 0 };

  function local(e) {
    const r = view.getBoundingClientRect();
    return { x: e.clientX - r.left, y: e.clientY - r.top };
  }

  // a pointer already gone (released before this ran) cannot be captured: dragging works without it
  function capture(el, e) {
    try { el.setPointerCapture(e.pointerId); } catch (_) { /* nothing to hold on to */ }
  }

  function markerAt(x, y) {
    let best = -1, bd = 14 * 14;                               // a hit area wider than the marker
    for (const m of hits) {
      const d = (m.x - x) ** 2 + (m.y - y) ** 2;
      if (d <= bd) { bd = d; best = m.k; }
    }
    return best;
  }

  function pointAt(x, y) {
    let best = -1, bd = 18 * 18;
    for (const i of fixes) {
      const d = (PX[i] - x) ** 2 + (PY[i] - y) ** 2;
      if (d < bd) { bd = d; best = i; }
    }
    return best;
  }

  function hover(x, y) {
    const m = markerAt(x, y);
    if (m >= 0) {
      view.style.cursor = "pointer";
      if (st.pinned < 0) { eventTip(m); placeTip(x, y, 14); }
      return;
    }
    view.style.cursor = "";
    const i = pointAt(x, y);
    if (i >= 0) {
      setCursor(i, "map");
      if (st.pinned < 0) { pointTip(i); placeTip(x, y, 14); }
    } else hideTip();
  }

  function tap(x, y) {
    const now = performance.now();
    if (now - lastTap.t < 320 && Math.hypot(x - lastTap.x, y - lastTap.y) < 24) {
      lastTap.t = 0;
      zoomTo(x, y, Math.round(st.z) + 1);                     // a double click or a double tap
      return;
    }
    lastTap = { t: now, x, y };
    const m = markerAt(x, y);
    if (m >= 0) { setCursor(events[m].i, "map"); pin(m); return; }
    unpin();
    const i = pointAt(x, y);
    if (i >= 0) { setCursor(i, "map"); pointTip(i); placeTip(x, y, 14); } else tip.hidden = true;
  }

  function pinchState() {
    const [a, b] = [...pointers.values()];
    return { x: (a.x + b.x) / 2, y: (a.y + b.y) / 2, d: Math.hypot(a.x - b.x, a.y - b.y) || 1 };
  }

  view.addEventListener("pointerdown", (e) => {
    if (e.pointerType === "mouse" && e.button !== 0) return;
    if (e.target.closest("button, a")) return;               // the zoom buttons and the credit's links
    view.focus({ preventScroll: true });
    capture(view, e);
    const p = local(e);
    pointers.set(e.pointerId, p);
    stopAnimation();
    if (pointers.size === 1) drag = { x: p.x, y: p.y, cx: st.cx, cy: st.cy, moved: false };
    else if (pointers.size === 2) {
      const s = pinchState();
      const [wx, wy] = toWorld(s.x, s.y);
      drag = null;
      pinch = { d: s.d, z: st.z, wx, wy };
      tip.hidden = st.pinned < 0 ? true : tip.hidden;
    }
  });

  view.addEventListener("pointermove", (e) => {
    const p = local(e);
    if (!pointers.has(e.pointerId)) {
      if (e.pointerType === "mouse" && !e.target.closest(".map-ctl, .map-credit")) hover(p.x, p.y);
      return;
    }
    pointers.set(e.pointerId, p);
    if (pinch && pointers.size >= 2) {
      const s = pinchState();
      st.z = clampZ(pinch.z + Math.log2(s.d / pinch.d));
      st.cx = pinch.wx - (s.x - st.W / 2) / scale();
      st.cy = pinch.wy - (s.y - st.H / 2) / scale();
      keepNearTrack();
      zoomMoving();
      redraw();
      return;
    }
    if (!drag) return;
    const dx = p.x - drag.x, dy = p.y - drag.y;
    if (!drag.moved && dx * dx + dy * dy < 16) return;
    if (!drag.moved) {
      drag.moved = true;
      view.classList.add("dragging");
      if (st.pinned < 0) tip.hidden = true;
    }
    st.cx = drag.cx - dx / scale();
    st.cy = drag.cy - dy / scale();
    keepNearTrack();
    redraw();
  });

  function release(e) {
    if (!pointers.has(e.pointerId)) return;
    const p = pointers.get(e.pointerId);
    pointers.delete(e.pointerId);
    if (pinch) {
      if (pointers.size < 2) {
        pinch = null;
        const rest = [...pointers.values()][0];
        drag = rest ? { x: rest.x, y: rest.y, cx: st.cx, cy: st.cy, moved: true } : null;
      }
      return;
    }
    if (drag && !drag.moved && e.type === "pointerup") tap(p.x, p.y);
    drag = null;
    view.classList.remove("dragging");
  }
  view.addEventListener("pointerup", release);
  view.addEventListener("pointercancel", release);
  view.addEventListener("pointerleave", (e) => {
    if (e.pointerType === "mouse" && !pointers.size) { view.style.cursor = ""; hideTip(); }
  });

  const isFull = () => document.fullscreenElement === root || root.classList.contains("is-max");

  // the wheel scrolls the page: the map zooms with Ctrl (or ⌘, or a touchpad pinch) or in full screen
  let hintTimer = 0;
  view.addEventListener("wheel", (e) => {
    if (!e.ctrlKey && !e.metaKey && !isFull()) {
      hint.textContent = "Hold Ctrl and scroll to zoom the map, or use + and −";
      hint.hidden = false;
      clearTimeout(hintTimer);
      hintTimer = setTimeout(() => { hint.hidden = true; }, 1400);
      return;
    }
    e.preventDefault();
    const p = local(e);
    const dy = e.deltaY * (e.deltaMode === 1 ? 33 : e.deltaMode === 2 ? 400 : 1);
    stopAnimation();
    zoomAround(p.x, p.y, st.z + clamp(-dy * 0.01, -0.5, 0.5));
    zoomMoving();
    if (st.pinned < 0) tip.hidden = true;
    redraw();
  }, { passive: false });

  view.addEventListener("keydown", (e) => {
    const step = 80 / scale();
    switch (e.key) {
      case "ArrowLeft": st.cx -= step; break;
      case "ArrowRight": st.cx += step; break;
      case "ArrowUp": st.cy -= step; break;
      case "ArrowDown": st.cy += step; break;
      case "+": case "=": zoomTo(st.W / 2, st.H / 2, Math.round(st.z) + 1); break;
      case "-": case "_": zoomTo(st.W / 2, st.H / 2, Math.round(st.z) - 1); break;
      case "Escape":
        if (st.pinned >= 0) unpin();
        else if (isFull()) toggleFull();
        else return;
        break;
      default: return;
    }
    e.preventDefault();
    keepNearTrack();
    redraw();
  });

  zoomCtl.addEventListener("click", (e) => {
    const act = (e.target.closest("button") || {}).dataset?.act;
    if (act === "in") zoomTo(st.W / 2, st.H / 2, Math.round(st.z) + 1);
    else if (act === "out") zoomTo(st.W / 2, st.H / 2, Math.round(st.z) - 1);
    else if (act === "fit") fit(true);
    else if (act === "full") toggleFull();
  });

  function toggleFull() {
    if (document.fullscreenElement === root) { document.exitFullscreen(); return; }
    if (root.classList.contains("is-max")) { root.classList.remove("is-max"); syncFull(); return; }
    const fallback = () => { root.classList.add("is-max"); syncFull(); };
    if (root.requestFullscreen) root.requestFullscreen().then(syncFull, fallback);
    else fallback();
  }
  function syncFull() {
    const on = isFull();
    fullBtn.textContent = on ? "✕" : "⛶";
    fullBtn.title = on ? "Leave full screen" : "Full screen";
    fullBtn.setAttribute("aria-label", fullBtn.title);
  }
  document.addEventListener("fullscreenchange", syncFull);

  // ------------------------------------------------------------------ the strip, the replay

  let scrubbing = false;
  function scrub(e, from) {
    const r = strip.getBoundingClientRect();
    setCursor(indexAt(clamp((e.clientX - r.left) / r.width, 0, 1) * T), from);
  }
  strip.addEventListener("pointerdown", (e) => {
    scrubbing = true;
    capture(strip, e);
    scrub(e, "strip");
  });
  strip.addEventListener("pointermove", (e) => {
    if (scrubbing) scrub(e, "strip");
    else if (e.pointerType === "mouse") scrub(e, "hover");
  });
  const endScrub = () => { scrubbing = false; };
  strip.addEventListener("pointerup", endScrub);
  strip.addEventListener("pointercancel", endScrub);

  let playing = 0;
  function startPlay() {
    if (st.cursor < 0 || st.cursor >= N - 1) setCursor(0, "play");
    let at = D.t[st.cursor], seen = st.cursor, last = performance.now();
    play.textContent = "❚❚";
    play.title = "Pause";
    play.setAttribute("aria-label", "Pause");
    const step = (now) => {
      if (st.cursor !== seen) at = D.t[st.cursor];              // moved by hand while playing
      at += Math.max(0, now - last) * +speedSel.value;
      last = Math.max(last, now);
      if (at >= T) { setCursor(N - 1, "play"); stopPlay(); return; }
      setCursor(indexAt(at), "play");
      seen = st.cursor;
      playing = requestAnimationFrame(step);
    };
    playing = requestAnimationFrame(step);
  }
  function stopPlay() {
    cancelAnimationFrame(playing);
    playing = 0;
    play.textContent = "▶";
    play.title = "Replay the ride";
    play.setAttribute("aria-label", play.title);
  }
  play.addEventListener("click", () => (playing ? stopPlay() : startPlay()));
  speedSel.addEventListener("change", savePrefs);

  // ------------------------------------------------------------------ the rest of the report

  for (const c of charts) {
    const at = (e) => {
      const r = c.svg.getBoundingClientRect();
      const f = (((e.clientX - r.left) / r.width) * c.vw - c.left) / c.w;
      if (f >= 0 && f <= 1) setCursor(indexAt(f * T), "chart");
    };
    c.svg.addEventListener("pointermove", at);
    c.svg.addEventListener("pointerdown", at);
  }

  for (const tr of rows) {
    const go = () => showEvent(+tr.dataset.ev);
    tr.addEventListener("click", go);
    tr.addEventListener("keydown", (e) => {
      if (e.key === "Enter" || e.key === " ") { e.preventDefault(); go(); }
    });
  }

  colourSel.addEventListener("change", () => {
    st.colour = +colourSel.value;
    recolour(); redraw(); drawStrip(); savePrefs();
  });
  layerSel.addEventListener("change", () => { setLayer(+layerSel.value); savePrefs(); });
  prefersDark.addEventListener("change", () => {
    readTheme();
    view.dataset.base = baseDark() ? "dark" : "light";
    recolour(); redraw(); drawStrip();
  });

  function resize() {
    dpr = window.devicePixelRatio || 1;
    const r = view.getBoundingClientRect();
    const W = Math.round(r.width), H = Math.round(r.height);
    if (W && H && (W !== st.W || H !== st.H)) {
      const first = !st.W;
      st.W = W;
      st.H = H;
      cv.width = Math.round(W * dpr);
      cv.height = Math.round(H * dpr);
      cv.style.width = W + "px";
      cv.style.height = H + "px";
      if (first) fit(false);
    }
    strip.width = Math.round(strip.clientWidth * dpr);
    strip.height = Math.round(strip.clientHeight * dpr);
    redraw();
    drawStrip();
  }

  readTheme();
  setLayer(st.layer);
  syncFull();
  new ResizeObserver(resize).observe(view);
  new ResizeObserver(resize).observe(strip);
})();
