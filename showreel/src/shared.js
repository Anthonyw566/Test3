/*
 * shared.js — the single clock for pixels AND sound.
 *
 * The page (scene.js) and the audio synth (audio/synth.js) both load this file,
 * so a needle tick you SEE and the click you HEAR come from the same function.
 * Everything here is a pure function of time: no state, no randomness that
 * isn't seeded. That is what lets frames render in parallel, in any order.
 */
(function (root) {
  'use strict';

  const FPS = 30, W = 1920, H = 1080, SR = 44100;
  const BPM = 96, BEAT = 60 / BPM, BAR = BEAT * 4;

  // Scenes are cut on bar lines so the music can breathe with the picture.
  const SCENE_LIST = [['open', 2], ['gauge', 3], ['knot', 3], ['code', 3], ['finale', 2]];
  let bar = 0;
  const SCENES = SCENE_LIST.map(([id, n]) => {
    const s = { id, start: bar * BAR, end: (bar + n) * BAR, dur: n * BAR, bar0: bar, bars: n };
    bar += n;
    return s;
  });
  const BARS = bar;
  const DURATION = BARS * BAR;
  const FRAMES = Math.round(DURATION * FPS);
  const CUTS = SCENES.slice(1).map((s) => s.start);

  // ---------------------------------------------------------------- math
  const clamp = (x, a = 0, b = 1) => Math.min(b, Math.max(a, x));
  const lerp = (a, b, t) => a + (b - a) * t;
  const smooth = (t) => { t = clamp(t); return t * t * (3 - 2 * t); };
  function easeOutCubic(t) { t = clamp(t); return 1 - Math.pow(1 - t, 3); }
  function easeInQuad(t) { t = clamp(t); return t * t; }
  function easeInOutCubic(t) { t = clamp(t); return t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2; }
  function easeOutExpo(t) { t = clamp(t); return t === 1 ? 1 : 1 - Math.pow(2, -10 * t); }
  function easeOutBack(t, s = 1.70158) { t = clamp(t); return 1 + (s + 1) * Math.pow(t - 1, 3) + s * Math.pow(t - 1, 2); }
  function mulberry32(a) {
    return function () {
      a |= 0; a = (a + 0x6d2b79f5) | 0;
      let t = Math.imul(a ^ (a >>> 15), 1 | a);
      t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
      return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
    };
  }

  // ------------------------------------------------- the needle (a spring)
  const GAUGE = { max: 1000, a0: 135, sweep: 270, target: FRAMES, dt: 0.0005 };

  // >>> snippet
  // The needle is a spring chasing a ramp.
  function simulate(ramp, zeta, omega, dt = 0.0005) {
    const out = [];
    let x = 0, v = 0;
    for (let t = 0; t < 8; t += dt) {
      const pull = omega * omega * (ramp(t) - x);
      v += (pull - 2 * zeta * omega * v) * dt;
      x += v * dt;
      out.push(x);
    }
    return out;
  }
  const ramp = (t) => FRAMES * (
    0.55 * easeOutCubic((t - 0.9) / 1.1) +
    0.45 * easeInQuad((t - 2.6) / 0.7));
  const angle = (v) => 135 + 270 * v / 1000;
  // <<<

  const needleTable = simulate(ramp, 0.5, 10, GAUGE.dt);

  function needle(tl) {
    if (tl <= 0) return 0;
    const f = tl / GAUGE.dt, i = Math.floor(f);
    if (i >= needleTable.length - 1) return needleTable[needleTable.length - 1];
    return lerp(needleTable[i], needleTable[i + 1], f - i);
  }
  function needleVel(tl) {
    const i = clamp(Math.floor(tl / GAUGE.dt), 0, needleTable.length - 2);
    return (needleTable[i + 1] - needleTable[i]) / GAUGE.dt;
  }
  // every time the needle crosses a minor tick mark (every 10 units), both directions
  function needleTicks() {
    const out = [];
    let last = Math.floor(needleTable[0] / 10);
    for (let i = 1; i < needleTable.length; i++) {
      const c = Math.floor(needleTable[i] / 10);
      if (c !== last) { out.push({ t: i * GAUGE.dt, v: needleTable[i], dir: c > last ? 1 : -1 }); last = c; }
    }
    return out;
  }
  function needleSettle() {
    for (let i = needleTable.length - 1; i >= 0; i--) if (Math.abs(needleTable[i] - GAUGE.target) > 1.2) return i * GAUGE.dt;
    return 0;
  }
  const needlePeak = Math.max.apply(null, needleTable.filter((_, i) => i % 20 === 0));

  // ------------------------------------------------------------- typing
  // Deterministic keystroke times: char i appears at times[i] (scene-local seconds).
  function makeTyping(text, t0, t1, seed) {
    const r = mulberry32(seed || 99);
    const n = text.length, gaps = new Array(n);
    let sum = 0;
    for (let i = 0; i < n; i++) {
      const ch = text[i];
      let g = 0.55 + r() * 0.9;
      if (ch === '\n') g = 2.4 + r() * 1.6;
      else if (ch === ' ') g *= 0.65;
      if (r() < 0.035) g += 2.5 + r() * 2.5;      // a thinking pause
      gaps[i] = g; sum += g;
    }
    const k = (t1 - t0) / sum, times = new Float64Array(n);
    let t = t0;
    for (let i = 0; i < n; i++) { times[i] = t; t += gaps[i] * k; }
    return times;
  }
  function typedCount(times, t) {
    let lo = 0, hi = times.length;
    while (lo < hi) { const m = (lo + hi) >> 1; if (times[m] <= t) lo = m + 1; else hi = m; }
    return lo;
  }

  // ------------------------------------------- scene-local event times (s)
  // Both the visuals and the sound design hang off these.
  const EV = {
    open:   { line: [0.35, 1.55], phrases: [1.15, 2.15, 3.15] },
    gauge:  { lamp: 0.25, sub1: 4.0, sub2: 4.55, redline: 3.5 },
    knot:   { converge: [2.6, 5.0], lock: 5.0, head: [5.25, 5.8], callouts: [6.05, 6.45, 6.85] },
    code:   { win: 0.25, type: [0.95, 5.2], enter: 5.45, run: [5.7, 6.95], done: 7.05 },
    finale: { counts: [[0.45, 1.25], [0.75, 1.55], [1.05, 1.85]], zero: 2.05, title: 2.55, credit: 3.75, fade: [4.0, 5.0] },
  };

  const timecode = (t) => {
    const f = Math.floor(t * FPS + 1e-6), s = Math.floor(f / FPS);
    const p = (n) => String(n).padStart(2, '0');
    return p(Math.floor(s / 60)) + ':' + p(s % 60) + ':' + p(f % FPS);
  };

  const api = {
    FPS, W, H, SR, BPM, BEAT, BAR, BARS, DURATION, FRAMES, SCENES, CUTS, EV, GAUGE,
    clamp, lerp, smooth, easeOutCubic, easeInQuad, easeInOutCubic, easeOutExpo, easeOutBack, mulberry32,
    needle, needleVel, needleTicks, needleSettle, needlePeak, makeTyping, typedCount, timecode,
  };
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  else root.SHARED = api;
})(typeof globalThis !== 'undefined' ? globalThis : this);
