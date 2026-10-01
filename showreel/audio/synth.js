#!/usr/bin/env node
/*
 * synth.js — the whole soundtrack AND sound design, synthesized from nothing.
 * No samples, no libraries: oscillators, noise, filters, a Freeverb and a ping-pong delay.
 *
 * It reads the same clock as the picture (src/shared.js), so cues are frame-accurate:
 *   - the ratchet clicks are the needle actually crossing tick marks in the spring simulation
 *   - the servo whine follows the needle's real velocity
 *   - every keystroke is a character in the code you see being typed
 *   - the impacts land on the cut frames, the lock-in hit lands on the frame the knot snaps shut
 *
 *   node audio/synth.js [out.wav]
 */
const fs = require('fs');
const path = require('path');
const S = require('../src/shared.js');
const { SR, BEAT, BAR, DURATION, SCENES, EV, CUTS, lerp, clamp } = S;

const N = Math.ceil(DURATION * SR);
const TAU = Math.PI * 2;
const rnd = S.mulberry32(20241001);
const mtof = (m) => 440 * Math.pow(2, (m - 69) / 12);
const db = (x) => Math.pow(10, x / 20);

// ------------------------------------------------------------------ mix buses
const mkBus = () => [new Float32Array(N), new Float32Array(N)];
const BUS = { pads: mkBus(), music: mkBus(), sfx: mkBus(), verb: mkBus(), dly: mkBus() };

// Place a mono buffer on a bus at time t. o: g gain, pan -1..1, rv reverb send, dl delay send
function put(bus, t, x, o = {}) {
  const g = o.g === undefined ? 1 : o.g, pan = o.pan || 0, rv = o.rv || 0, dl = o.dl || 0;
  const a = (pan + 1) * Math.PI / 4, gl = g * Math.cos(a), gr = g * Math.sin(a);
  const i0 = Math.round(t * SR), n = x.length, fi = Math.floor(0.002 * SR), fo = Math.floor(0.008 * SR);
  const B = BUS[bus], V = BUS.verb, D = BUS.dly;
  for (let i = 0; i < n; i++) {
    const k = i0 + i; if (k < 0 || k >= N) continue;
    const v = x[i] * Math.min(1, i / fi) * Math.min(1, (n - 1 - i) / fo);
    B[0][k] += v * gl; B[1][k] += v * gr;
    if (rv) { V[0][k] += v * gl * rv; V[1][k] += v * gr * rv; }
    if (dl) { D[0][k] += v * gl * dl; D[1][k] += v * gr * dl; }
  }
}
function putSt(bus, t, L, R, o = {}) {
  const g = o.g === undefined ? 1 : o.g, rv = o.rv || 0, dl = o.dl || 0;
  const i0 = Math.round(t * SR), n = L.length, fi = Math.floor(0.002 * SR), fo = Math.floor(0.008 * SR);
  const B = BUS[bus], V = BUS.verb, D = BUS.dly;
  for (let i = 0; i < n; i++) {
    const k = i0 + i; if (k < 0 || k >= N) continue;
    const e = Math.min(1, i / fi) * Math.min(1, (n - 1 - i) / fo), l = L[i] * e * g, r = R[i] * e * g;
    B[0][k] += l; B[1][k] += r;
    if (rv) { V[0][k] += l * rv; V[1][k] += r * rv; }
    if (dl) { D[0][k] += l * dl; D[1][k] += r * dl; }
  }
}

// ------------------------------------------------------------------ DSP primitives
const buf = (sec) => new Float32Array(Math.max(1, Math.floor(sec * SR)));
function noiseBuf(n) { const y = new Float32Array(n); for (let i = 0; i < n; i++) y[i] = rnd() * 2 - 1; return y; }
// Chamberlin state-variable filter. fc may be a number or fn(sampleIndex)->Hz. mode: lp | hp | bp
function svf(x, fc, q, mode) {
  const n = x.length, y = new Float32Array(n), q1 = 1 / q, isFn = typeof fc === 'function';
  let lo = 0, bd = 0, f = 2 * Math.sin(Math.PI * Math.min(isFn ? 100 : fc, 7000) / SR);
  for (let i = 0; i < n; i++) {
    if (isFn && (i & 7) === 0) f = 2 * Math.sin(Math.PI * Math.min(fc(i), 7000) / SR);
    const hi = x[i] - lo - q1 * bd; bd += f * hi; lo += f * bd;
    y[i] = mode === 'lp' ? lo : mode === 'hp' ? hi : bd * q1;
  }
  return y;
}
function lp1(x, fc) { const a = 1 - Math.exp(-TAU * fc / SR), y = new Float32Array(x.length); let s = 0; for (let i = 0; i < x.length; i++) { s += a * (x[i] - s); y[i] = s; } return y; }
const smoothstep = (u) => { u = clamp(u); return u * u * (3 - 2 * u); };

// ------------------------------------------------------------------ instruments
function kick(o = {}) {
  const f0 = o.f0 || 150, f1 = o.f1 || 52, tau = o.tau || 0.035, dec = o.dec || 0.22, len = o.len || 0.6, drive = o.drive || 1.8;
  const n = Math.floor(len * SR), y = new Float32Array(n); let ph = 0;
  for (let i = 0; i < n; i++) {
    const t = i / SR, f = f1 + (f0 - f1) * Math.exp(-t / tau); ph += TAU * f / SR;
    y[i] = Math.tanh(Math.sin(ph) * Math.exp(-t / dec) * (1 - Math.exp(-t / 0.0012)) * drive);
  }
  for (let i = 0; i < Math.floor(0.004 * SR); i++) y[i] += (rnd() * 2 - 1) * 0.22 * Math.exp(-i / (0.0012 * SR));
  return y;
}
function snare(len = 0.3) {
  const n = Math.floor(len * SR), bp = svf(noiseBuf(n), 2300, 1.1, 'bp'), y = new Float32Array(n);
  for (let i = 0; i < n; i++) { const t = i / SR; y[i] = bp[i] * Math.exp(-t / 0.075) * 2.4 + Math.sin(TAU * 188 * t) * Math.exp(-t / 0.045) * 0.55; }
  return y;
}
function clap(len = 0.35) {
  const n = Math.floor(len * SR), bp = svf(noiseBuf(n), 1500, 1.3, 'bp'), y = new Float32Array(n);
  for (let i = 0; i < n; i++) {
    const t = i / SR; let e = 0;
    for (const d of [0, 0.011, 0.022]) if (t >= d) e += Math.exp(-(t - d) / 0.0035) * 0.6;
    if (t > 0.03) e += Math.exp(-(t - 0.03) / 0.085) * 0.75;
    y[i] = bp[i] * e * 2.2;
  }
  return y;
}
function hat(open) {
  const dec = open ? 0.17 : 0.028, n = Math.floor((open ? 0.5 : 0.14) * SR), hp = svf(noiseBuf(n), 6500, 0.8, 'hp'), y = new Float32Array(n);
  for (let i = 0; i < n; i++) y[i] = hp[i] * Math.exp(-(i / SR) / dec);
  return y;
}
function bassNote(f, len) {
  const n = Math.floor((len + 0.06) * SR), y = new Float32Array(n); let ph = 0;
  for (let i = 0; i < n; i++) {
    const t = i / SR; ph += TAU * f / SR;
    const e = (1 - Math.exp(-t / 0.005)) * (t < len ? 1 : Math.exp(-(t - len) / 0.02)) * (0.72 + 0.28 * Math.exp(-t / 0.18));
    y[i] = Math.tanh(1.5 * (Math.sin(ph) + 0.32 * Math.sin(2 * ph) + 0.1 * Math.sin(3 * ph))) * e * 0.8;
  }
  return y;
}
// lush additive pad: 3 detuned voices per note, soft low-pass sweep, long attack
function pad(notes, len, o = {}) {
  const att = o.att || 0.7, rel = o.rel || 1.0, bright = o.bright || 1, n = Math.floor((len + rel) * SR);
  const L = new Float32Array(n), R = new Float32Array(n);
  const voices = [[-9, -0.65], [0, 0], [9, 0.65]];
  notes.forEach((m) => {
    voices.forEach(([cents, pan]) => {
      const fd = mtof(m) * Math.pow(2, cents / 1200), ph0 = rnd() * TAU, lfo = rnd() * TAU;
      const gl = Math.cos((pan + 1) * Math.PI / 4), gr = Math.sin((pan + 1) * Math.PI / 4), nh = Math.min(10, Math.floor(4200 / fd));
      for (let i = 0; i < n; i++) {
        const t = i / SR, e = smoothstep(t / att) * (t < len ? 1 : Math.exp(-(t - len) / (rel * 0.3)));
        const fc = (650 + 380 * Math.sin(0.31 * t + lfo)) * bright;
        let s = 0;
        for (let h = 1; h <= nh; h++) { const hf = h * fd, r = hf / fc; s += Math.sin(ph0 * h + TAU * hf * t) / (h * (1 + r * r)); }
        const v = s * e * 0.05;
        L[i] += v * gl; R[i] += v * gr;
      }
    });
  });
  return [L, R];
}
function pluck(f, len = 1.6) {
  const n = Math.floor(len * SR), y = new Float32Array(n);
  for (let h = 1; h <= 7; h++) {
    const amp = (1 / Math.pow(h, 1.15)) * (h % 2 ? 1 : 0.75), tau = (0.9 / (0.6 + 0.55 * h)) * (len / 1.6), fh = f * h * (1 + 0.0003 * h * h);
    if (fh > 9000) break;
    for (let i = 0; i < n; i++) { const t = i / SR; y[i] += amp * Math.sin(TAU * fh * t) * Math.exp(-t / tau); }
  }
  for (let i = 0; i < n; i++) y[i] *= (1 - Math.exp(-(i / SR) / 0.0015)) * 0.5;
  return y;
}
function bell(f, len = 3) {
  const n = Math.floor(len * SR), y = new Float32Array(n);
  [[1, 1, 1], [2.0, 0.55, 0.7], [2.76, 0.6, 0.45], [5.4, 0.3, 0.25], [8.93, 0.18, 0.12]].forEach(([r, a, tr]) => {
    const tau = len * tr / 2.2, fr = f * r; if (fr > 11000) return;
    for (let i = 0; i < n; i++) { const t = i / SR; y[i] += a * Math.sin(TAU * fr * t) * Math.exp(-t / tau); }
  });
  for (let i = 0; i < n; i++) y[i] *= (1 - Math.exp(-(i / SR) / 0.002)) * 0.38;
  return y;
}
function sparkle(f, len = 0.4) {
  const n = Math.floor(len * SR), y = new Float32Array(n);
  for (let i = 0; i < n; i++) { const t = i / SR; y[i] = (Math.sin(TAU * f * t) + 0.35 * Math.sin(TAU * f * 2.01 * t)) * Math.exp(-t / (len / 5)) * (1 - Math.exp(-t / 0.001)) * 0.4; }
  return y;
}
function blip(f, len = 0.09, glide = 0.06) {
  const n = Math.floor(len * SR), y = new Float32Array(n); let ph = 0;
  for (let i = 0; i < n; i++) {
    const t = i / SR; ph += TAU * f * (1 + glide * smoothstep(t / len)) / SR;
    y[i] = (Math.sin(ph) + 0.22 * Math.sin(2 * ph)) * Math.exp(-t / (len / 3)) * (1 - Math.exp(-t / 0.002)) * 0.5;
  }
  return y;
}
function tick(v) {                       // a ratchet click that rises in pitch as the needle climbs the dial
  const n = Math.floor(0.03 * SR), x = new Float32Array(n); x[0] = 1; x[1] = -0.6;
  const ring = svf(x, 1500 + v * 2.3, 7, 'bp'), y = new Float32Array(n);
  for (let i = 0; i < n; i++) { const t = i / SR; y[i] = ring[i] * 2.6 + Math.sin(TAU * 130 * t) * Math.exp(-t / 0.004) * 0.35; }
  return y;
}
function thunk(f0 = 100, len = 0.22) {
  const n = Math.floor(len * SR), y = new Float32Array(n); let ph = 0;
  const nz = svf(noiseBuf(n), 900, 1, 'lp');
  for (let i = 0; i < n; i++) { const t = i / SR; ph += TAU * (f0 * (0.55 + 0.45 * Math.exp(-t / 0.03))) / SR; y[i] = Math.sin(ph) * Math.exp(-t / 0.07) * 0.9 + nz[i] * Math.exp(-t / 0.012) * 0.6; }
  return y;
}
function key(kind) {
  const n = Math.floor(0.06 * SR), big = kind === 'enter' || kind === 'space';
  const bp = svf(noiseBuf(n), (big ? 1900 : 2900) * (0.85 + 0.3 * rnd()), 2.4, 'bp'), y = new Float32Array(n);
  for (let i = 0; i < n; i++) { const t = i / SR; y[i] = bp[i] * Math.exp(-t / 0.0024) * 1.5 + Math.sin(TAU * (kind === 'enter' ? 120 : big ? 170 : 270) * t) * Math.exp(-t / (kind === 'enter' ? 0.03 : 0.012)) * (kind === 'enter' ? 0.8 : 0.32); }
  return y;
}
function whoosh(dur, f0, f1, peak = 0.8, q = 1.3) {
  const n = Math.floor(dur * SR), out = [];
  for (let c = 0; c < 2; c++) {
    const bp = svf(noiseBuf(n), (i) => f0 * Math.pow(f1 / f0, i / n), q, 'bp'), y = new Float32Array(n);
    for (let i = 0; i < n; i++) { const u = i / n; y[i] = bp[i] * (u < peak ? Math.pow(u / peak, 2.2) : Math.pow(1 - (u - peak) / (1 - peak), 1.6)) * 1.6; }
    out.push(y);
  }
  return out;
}
function impact(len = 3.5, subHz = 48) {
  const n = Math.floor(len * SR), y = new Float32Array(n); let ph = 0;
  const nz = svf(noiseBuf(n), (i) => 120 + 3400 * Math.exp(-i / SR / 0.1), 0.9, 'lp');
  for (let i = 0; i < n; i++) {
    const t = i / SR; ph += TAU * subHz * (1 + 1.1 * Math.exp(-t / 0.22)) / SR;
    y[i] = Math.sin(ph) * Math.exp(-t / (len * 0.28)) * (1 - Math.exp(-t / 0.003)) * 0.95 + nz[i] * Math.exp(-t / 0.2) * 0.9;
  }
  [143, 216, 297, 392].forEach((fr, k) => { for (let i = 0; i < n; i++) { const t = i / SR; y[i] += Math.sin(TAU * fr * t) * Math.exp(-t / (len * 0.22)) * 0.035 / (1 + k * 0.4); } });
  return y;
}
function riser(dur, fLo, fHi, tonal = 1) {
  const n = Math.floor(dur * SR), bp = svf(noiseBuf(n), (i) => fLo * Math.pow(fHi / fLo, i / n), 2.2, 'bp'), y = new Float32Array(n);
  let ph1 = 0, ph2 = 0;
  for (let i = 0; i < n; i++) {
    const u = i / n, f = fLo * 0.5 * Math.pow(fHi / fLo, Math.pow(u, 1.4)); ph1 += TAU * f / SR; ph2 += TAU * f * 1.0075 / SR;
    const trem = 0.65 + 0.35 * Math.sin(TAU * (5 + 14 * u) * (i / SR));
    y[i] = (bp[i] * 1.5 + tonal * (Math.sin(ph1) + Math.sin(ph2) + 0.3 * Math.sin(2 * ph1)) * 0.28 * trem) * Math.pow(u, 2.2);
  }
  return y;
}
function revCrash(dur) {
  const n = Math.floor(dur * SR), a = svf(noiseBuf(n), 3600, 0.8, 'hp'), b = svf(noiseBuf(n), 5200, 3, 'bp'), y = new Float32Array(n);
  for (let i = 0; i < n; i++) y[i] = (a[i] * 0.8 + b[i] * 0.6) * Math.pow(i / n, 3);
  return y;
}
function crash(len = 2.6) {
  const n = Math.floor(len * SR), a = svf(noiseBuf(n), 5200, 0.8, 'hp'), y = new Float32Array(n);
  for (let i = 0; i < n; i++) { const t = i / SR; y[i] = a[i] * (Math.exp(-t / 0.7) * 0.7 + Math.exp(-t / 0.12) * 0.5); }
  return y;
}

// ------------------------------------------------------------------ effects
function freeverb(V) {
  const combT = [1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617], apT = [556, 441, 341, 225];
  const room = 0.935, damp = 0.34, pd = Math.floor(0.022 * SR), out = [new Float32Array(N), new Float32Array(N)];
  [0, 1].forEach((ch) => {
    const off = ch * 23, combs = combT.map((t) => ({ b: new Float32Array(t + off), i: 0, s: 0 })), aps = apT.map((t) => ({ b: new Float32Array(t + off), i: 0 }));
    for (let i = 0; i < N; i++) {
      const k = i - pd, inp = k >= 0 ? (V[ch][k] * 0.65 + V[1 - ch][k] * 0.35) * 0.03 : 0;
      let sum = 0;
      for (const c of combs) { const o = c.b[c.i]; c.s = o * (1 - damp) + c.s * damp; c.b[c.i] = inp + c.s * room; if (++c.i >= c.b.length) c.i = 0; sum += o; }
      for (const a of aps) { const b = a.b[a.i], o = -sum + b; a.b[a.i] = sum + b * 0.5; if (++a.i >= a.b.length) a.i = 0; sum = o; }
      out[ch][i] = sum;
    }
  });
  return out;
}
function pingpong(D, fb = 0.4) {
  const dl = Math.floor(0.75 * BEAT * SR), dr = Math.floor(0.5 * BEAT * SR), bl = new Float32Array(dl), br = new Float32Array(dr);
  const a = 1 - Math.exp(-TAU * 3200 / SR), out = [new Float32Array(N), new Float32Array(N)]; let pl = 0, pr = 0, lpL = 0, lpR = 0;
  for (let i = 0; i < N; i++) {
    const yl = bl[pl], yr = br[pr]; lpL += a * (yl - lpL); lpR += a * (yr - lpR);
    bl[pl] = (D[0][i] + D[1][i]) * 0.5 + lpR * fb; br[pr] = lpL * fb;
    out[0][i] = yl; out[1][i] = yr;
    if (++pl >= dl) pl = 0; if (++pr >= dr) pr = 0;
  }
  return out;
}

// ================================================================== the score
const T = (sceneIdx, local) => SCENES[sceneIdx].start + local;       // scene-local -> global time
const OPEN = 0, GAUGE = 1, KNOT = 2, CODE = 3, FINALE = 4;

const CH = {
  Dm9:    { root: 38, notes: [50, 57, 60, 64, 65], arp: [62, 65, 69, 72, 76] },
  Bbmaj7: { root: 34, notes: [53, 57, 62, 65, 70], arp: [58, 62, 65, 69, 74] },
  Gm9:    { root: 43, notes: [55, 58, 62, 65, 69], arp: [58, 62, 65, 69, 74] },
  Gm7:    { root: 43, notes: [55, 58, 62, 65, 67], arp: [58, 62, 65, 67, 70] },
  A7sus:  { root: 45, notes: [57, 62, 64, 67, 69], arp: [57, 62, 64, 67, 69] },
  Fmaj7:  { root: 41, notes: [53, 57, 60, 64, 69], arp: [57, 60, 64, 69, 72] },
};
const PROG = ['Dm9', 'Dm9', 'Bbmaj7', 'Gm9', 'A7sus', 'Dm9', 'Bbmaj7', 'Gm9', 'Fmaj7', 'Gm7', 'A7sus', 'Bbmaj7', 'Dm9'].map((k) => CH[k]);
const barT = (b) => b * BAR;
const beatT = (b, beat) => b * BAR + beat * BEAT;
const kicks = [];                                                     // for sidechain ducking

// ---- pads: one long wash per chord
(function pads() {
  const padGain = [0.55, 0.6, 0.7, 0.75, 0.8, 0.85, 0.85, 0.9, 0.6, 0.62, 0.7, 0.95, 0.9];
  const bright = [0.6, 0.65, 0.8, 0.85, 1.0, 1.15, 1.15, 1.2, 0.75, 0.8, 1.0, 1.3, 1.1];
  for (let b = 0; b < S.BARS;) {
    let span = 1; while (b + span < S.BARS && PROG[b + span] === PROG[b] && b + span !== 5) span++;
    const last = b + span === S.BARS;
    const [L, R] = pad(PROG[b].notes, span * BAR + (last ? 0 : 0.15), { att: b === 0 ? 2.2 : 0.55, rel: last ? 2.4 : 1.1, bright: bright[b] });
    putSt('pads', barT(b), L, R, { g: padGain[b], rv: 0.38 });
    b += span;
  }
})();

// ---- ambience: room tone + projector dust in the open, electrical hum in the gauge scene
(function ambience() {
  const lo = lp1(noiseBuf(N), 380), hiss = svf(noiseBuf(N), 7000, 0.7, 'hp'), x = new Float32Array(N);
  for (let i = 0; i < N; i++) { const t = i / SR; x[i] = lo[i] * 0.55 * (0.7 + 0.3 * Math.sin(t * 0.4)) + hiss[i] * 0.012; }
  const fadeOut = (t) => clamp((DURATION - t) / 2);
  for (let i = 0; i < N; i++) x[i] *= smoothstep(i / SR / 1.2) * fadeOut(i / SR);
  put('sfx', 0, x, { g: 0.1 });
  // dust crackle through the open scene (matches the floating motes)
  for (let t = 0.2; t < SCENES[OPEN].end; t += -Math.log(rnd()) / 7) {
    const n = Math.floor((0.0015 + rnd() * 0.004) * SR), c = new Float32Array(n);
    for (let i = 0; i < n; i++) c[i] = (rnd() * 2 - 1) * Math.exp(-i / (n * 0.3));
    put('sfx', t, c, { g: 0.05 * smoothstep(t / 1.5) * (1 - smoothstep((t - 4.2) / 0.8)), pan: rnd() * 2 - 1 });
  }
  // mains hum while the gauge is powered
  const n = Math.floor((SCENES[GAUGE].dur + 0.6) * SR), h = new Float32Array(n);
  for (let i = 0; i < n; i++) { const t = i / SR; h[i] = (Math.sin(TAU * 50 * t) + 0.5 * Math.sin(TAU * 100 * t) + 0.25 * Math.sin(TAU * 150 * t)) * smoothstep((t - 0.3) / 0.9) * (1 - smoothstep((t - SCENES[GAUGE].dur + 0.2) / 0.7)); }
  put('sfx', T(GAUGE, 0.2), h, { g: 0.02, rv: 0.1 });
})();

// ---- open: heartbeat + sparse bells + the hairline sweep + word reveals
(function open() {
  for (let b = 0; b < 2; b++) for (const [bt, g] of [[0, 1], [0.8, 0.6]]) put('music', beatT(b, bt), kick({ f0: 90, f1: 40, dec: 0.2, len: 0.5, drive: 1.2 }), { g: 0.34 * g * (0.5 + 0.5 * smoothstep(b * 0.5)), rv: 0.1 });
  [[0, 74], [2.5, 69], [4, 77], [6.5, 76]].forEach(([bt, m], i) => put('music', beatT(0, bt) + 0.4, pluck(mtof(m), 2.0), { g: 0.34, dl: 0.55, rv: 0.55, pan: i % 2 ? 0.3 : -0.3 }));
  // the two hairlines draw outward: a rising sine sweep + air
  const t0 = EV.open.line[0], d = EV.open.line[1] - t0, n = Math.floor(d * SR), y = new Float32Array(n); let ph = 0;
  const air = svf(noiseBuf(n), (i) => 800 + 5200 * Math.pow(i / n, 1.5), 3, 'bp');
  for (let i = 0; i < n; i++) { const u = i / n, f = 180 * Math.pow(10, u); ph += TAU * f / SR; const e = Math.sin(Math.PI * u) ** 1.5; y[i] = (Math.sin(ph) * 0.5 + air[i] * 0.9) * e; }
  put('sfx', t0, y, { g: 0.32, rv: 0.4 });
  // phrase reveals: a soft blip-chime pair + a breath of air
  EV.open.phrases.forEach((lt, i) => {
    const f = [587.33, 698.46, 880][i];
    put('sfx', lt, blip(f, 0.14, 0.04), { g: 0.4, rv: 0.5, dl: 0.3, pan: -0.15 + i * 0.15 });
    put('sfx', lt + 0.02, bell(f * 2, 2.2), { g: 0.22, rv: 0.6 });
    const [wl, wr] = whoosh(0.7, 500, 3500, 0.5); putSt('sfx', lt - 0.12, wl, wr, { g: 0.1, rv: 0.4 });
  });
  put('sfx', EV.open.phrases[2] + 0.28, sparkle(1760, 0.6), { g: 0.4, rv: 0.6, dl: 0.4 });         // the coral "code."
  put('sfx', EV.open.phrases[2] + 0.28, bell(mtof(86), 3), { g: 0.18, rv: 0.7 });
  put('sfx', 0.9, blip(1320, 0.06), { g: 0.16, rv: 0.4 });
})();

// ---- plucked arpeggio: the melodic thread through the piece
(function arp() {
  const pat = [0, 2, 4, 2, 1, 3, 4, 3], pat2 = [0, 3, 2, 4, 1, 2, 3, 1];
  const plan = [  // bar, gain, every-n-eighths (1 = every 8th), octave shift
    [2, 0.30, 1, 0], [3, 0.38, 1, 0], [4, 0.46, 1, 0],
    [5, 0.55, 1, 0], [6, 0.58, 1, 0], [7, 0.62, 1, 0],
    [8, 0.34, 1, 0], [9, 0.34, 1, 0], [10, 0.38, 1, 0],
    [11, 0.52, 1, 0],
  ];
  plan.forEach(([b, g, step, oct]) => {
    const ch = PROG[b];
    for (let i = 0; i < 8; i++) {
      if ((b >= 8 && b <= 10) && (i === 3 || i === 7)) continue;                       // let the code scene breathe
      if (b === 4 && beatT(b, i * 0.5) > T(GAUGE, SCENES[GAUGE].dur) - 0.2) continue;
      const m = ch.arp[(b === 6 || b === 9 ? pat2 : pat)[i]] + oct + (b === 7 && i % 4 === 3 ? 12 : 0);
      const acc = i % 2 === 0 ? 1 : 0.72;
      put('music', beatT(b, i * 0.5), pluck(mtof(m), 1.5), { g: g * acc, pan: ((i % 4) - 1.5) * 0.28, dl: 0.4, rv: 0.28 });
    }
  });
  // finale: slow ringing bells over the last chord
  [[0, 74], [1.5, 77], [3, 81], [5, 86]].forEach(([bt, m], i) => put('music', beatT(12, bt), bell(mtof(m), 3.2), { g: 0.34, rv: 0.6, dl: 0.3, pan: -0.3 + i * 0.2 }));
})();

// ---- bass
(function bass() {
  const sub = (t, f, len, g) => { const n = Math.floor(len * SR), y = new Float32Array(n); for (let i = 0; i < n; i++) { const tt = i / SR; y[i] = Math.sin(TAU * f * tt) * smoothstep(tt / 0.8) * (1 - smoothstep((tt - len + 0.8) / 0.8)); } put('music', t, y, { g }); };
  sub(0, mtof(38), 2 * BAR, 0.15);                                                            // D2 drone under the open
  for (let b = 2; b <= 4; b++) sub(barT(b), mtof(PROG[b].root), BAR, 0.14);                  // soft sub under the gauge
  for (let b = 8; b <= 10; b++) sub(barT(b), mtof(PROG[b].root), BAR, 0.15);                 // and under the code
  for (const b of [5, 6, 7, 11]) {                                                            // the drop groove
    const f = mtof(PROG[b].root);
    [[0, 1.25], [1.5, 0.75], [2.5, 0.75], [3.5, 0.45]].forEach(([bt, l], i) => put('music', beatT(b, bt), bassNote(i === 3 && b === 7 ? f * 2 : f, l * BEAT), { g: b === 5 ? 0.5 : 0.62 }));
  }
  sub(barT(12), mtof(PROG[12].root), BAR, 0.2);
})();

// ---- drums
(function drums() {
  const K = (t, g = 0.9) => { kicks.push(t); put('music', t, kick(), { g, rv: 0.04 }); };
  const C = (t, g = 0.5) => put('music', t, clap(), { g, rv: 0.28 });
  const H = (t, g = 0.2, open = false) => put('music', t, hat(open), { g, pan: 0.25 * (rnd() * 2 - 1) });
  // drop A: bars 5-6 (lighter, filtered feel) and bar 7 (full)
  for (const b of [5, 6]) {
    [0, 2, 2.75].forEach((bt) => K(beatT(b, bt), b === 5 ? 0.9 : 0.85));
    [1, 3].forEach((bt) => C(beatT(b, bt), 0.42));
    for (let i = 0; i < 8; i++) H(beatT(b, i * 0.5), i % 2 ? 0.2 : 0.11);
    H(beatT(b, 3.5), 0.15, true);
  }
  // bar 6 last beat: a snare roll + riser builds into the lock-in (the frame the knot snaps shut)
  for (let i = 0; i < 8; i++) { const t = beatT(6, 3 + i * 0.125); if (t > T(KNOT, EV.knot.lock) - 0.13) break; put('music', t, snare(0.2), { g: 0.12 + i * 0.05, rv: 0.2 }); }
  // bar 7: full groove — four on the floor
  for (let bt = 0; bt < 3.5; bt += 1) K(beatT(7, bt), 0.95);
  [1, 3].forEach((bt) => C(beatT(7, bt), 0.55));
  for (let i = 0; i < 12; i++) H(beatT(7, i * 0.25), i % 4 === 2 ? 0.22 : 0.1);
  put('music', beatT(7, 0), crash(3), { g: 0.4, rv: 0.4 });
  // bar 4: snare roll accelerating into the drop at 12.5s
  for (let t = beatT(4, 1), step = BEAT / 2, k = 0; t < barT(5) - 0.13; k++) {
    const u = (t - beatT(4, 1)) / (3 * BEAT);
    put('music', t, snare(0.2), { g: 0.1 + 0.5 * u, rv: 0.18, pan: ((k % 3) - 1) * 0.15 });
    step = BEAT / 2 / (1 + 3.2 * u * u * 2.2); t += Math.max(step, 0.03);
  }
  // finale bar 11: huge, then one last kick on 12
  for (let bt = 0; bt < 4; bt++) K(beatT(11, bt), 0.95);
  [1, 3].forEach((bt) => C(beatT(11, bt), 0.6));
  for (let i = 0; i < 16; i++) H(beatT(11, i * 0.25), i % 4 === 2 ? 0.24 : 0.1);
  put('music', barT(11), crash(3.4), { g: 0.5, rv: 0.45 });
  K(barT(12), 0.9);
})();

// ---- scene transitions: whoosh in, impact on the cut frame
(function transitions() {
  // cut 1 (5.0 s): open -> gauge
  const [w1l, w1r] = whoosh(1.5, 300, 6000, 0.85); putSt('sfx', CUTS[0] - 1.5, w1l, w1r, { g: 0.5, rv: 0.35 });
  put('sfx', CUTS[0], impact(3.2, 55), { g: 0.5, rv: 0.45 });
  put('sfx', CUTS[0], bell(mtof(62), 4), { g: 0.4, rv: 0.6 });
  // cut 2 (12.5 s): gauge -> knot (the first drop)
  put('sfx', CUTS[1] - 2.4, riser(2.4 - 0.125, 250, 6500), { g: 0.5, rv: 0.3 });
  put('sfx', CUTS[1] - 2.0, revCrash(2.0 - 0.125), { g: 0.5, rv: 0.2 });
  put('sfx', CUTS[1], impact(4, 46), { g: 0.9, rv: 0.55 });
  put('sfx', CUTS[1], crash(3.5), { g: 0.35, rv: 0.45 });
  // cut 3 (20.0 s): knot -> code (drums fall away, space opens up)
  put('sfx', CUTS[2] - 1.5, revCrash(1.5), { g: 0.2, rv: 0.4 });
  const [w3l, w3r] = whoosh(1.2, 4000, 400, 0.15); putSt('sfx', CUTS[2], w3l, w3r, { g: 0.32, rv: 0.4 });
  put('sfx', CUTS[2], impact(3, 58), { g: 1.0, rv: 0.5 });
  put('sfx', CUTS[2], bell(mtof(50), 4.5), { g: 0.35, rv: 0.65 });
  // cut 4 (27.5 s): code -> finale
  put('sfx', CUTS[3] - 2.45, riser(2.45 - 0.06, 300, 7000), { g: 0.5, rv: 0.3 });
  const [w4l, w4r] = whoosh(1.4, 400, 6500, 0.9); putSt('sfx', CUTS[3] - 1.4, w4l, w4r, { g: 0.5, rv: 0.3 });
  put('sfx', CUTS[3], impact(4.2, 44), { g: 0.9, rv: 0.55 });
})();

// ---- gauge scene: lamp-on, ratchet ticks, servo whine, the settle
(function gauge() {
  const g0 = SCENES[GAUGE].start;
  // lamp on: relay clunk + filament whine
  put('sfx', T(GAUGE, EV.gauge.lamp), thunk(80, 0.25), { g: 0.7, rv: 0.2 });
  { const n = Math.floor(1.2 * SR), y = new Float32Array(n); let ph = 0; for (let i = 0; i < n; i++) { const u = i / n; ph += TAU * (300 + 1400 * u * u) / SR; y[i] = Math.sin(ph) * Math.sin(Math.PI * u) ** 2 * 0.5; } put('sfx', T(GAUGE, EV.gauge.lamp + 0.05), y, { g: 0.2, rv: 0.5 }); }
  // each tick mark the needle crosses is a click, panned across the dial as it sweeps
  const ticks = S.needleTicks();
  ticks.forEach(({ t, v, dir }) => {
    const vel = Math.abs(S.needleVel(t)), g = 0.2 + 0.3 * clamp(vel / 900);
    put('sfx', g0 + t, tick(v), { g: g * (dir > 0 ? 1 : 0.8), pan: clamp((v / 1000 - 0.5) * 0.9, -0.5, 0.5), rv: 0.1 });
  });
  // servo whine follows |velocity|
  { const t0 = 0.85, t1 = 4.5, n = Math.floor((t1 - t0) * SR), y = new Float32Array(n); let ph = 0, s = 0;
    for (let i = 0; i < n; i++) {
      const vel = Math.abs(S.needleVel(t0 + i / SR)), a = clamp(vel / 500), f = 95 + 0.42 * vel; ph += TAU * f / SR;
      s += 0.08 * ((Math.sin(ph) + 0.45 * Math.sin(2 * ph) + 0.2 * Math.sin(3 * ph)) - s);
      y[i] = s * Math.pow(a, 1.3);
    }
    put('sfx', g0 + t0, y, { g: 0.55, rv: 0.12 }); }
  // settle: a mechanical thunk and a tiny spring wobble
  const st = S.needleSettle();
  put('sfx', g0 + st, thunk(110, 0.3), { g: 0.5, rv: 0.25 });
  { const n = Math.floor(0.7 * SR), y = new Float32Array(n); for (let i = 0; i < n; i++) { const t = i / SR; y[i] = Math.sin(TAU * 210 * t * (1 - 0.03 * t)) * Math.exp(-t / 0.18) * 0.25; } put('sfx', g0 + st, y, { g: 0.4, rv: 0.4 }); }
  // the redline marker lights up, then the two sub-lines pop in
  put('sfx', T(GAUGE, EV.gauge.redline), blip(988, 0.12, 0.1), { g: 0.4, rv: 0.5, dl: 0.3 });
  [[EV.gauge.sub1, 660], [EV.gauge.sub2, 880]].forEach(([lt, f]) => { put('sfx', T(GAUGE, lt), blip(f, 0.11, 0.08), { g: 0.5, rv: 0.45, dl: 0.35 }); put('sfx', T(GAUGE, lt + 0.12), sparkle(f * 4, 0.4), { g: 0.3, rv: 0.6 }); });
  // glass glint
  { const [l, r] = whoosh(1.1, 2500, 6500, 0.5, 2.5); putSt('sfx', T(GAUGE, 3.3), l, r, { g: 0.08, rv: 0.5 }); }
})();

// ---- knot scene: shimmer swell, the lock-in, callouts, equation chatter
(function knot() {
  const k0 = SCENES[KNOT].start, lock = T(KNOT, EV.knot.lock), c0 = T(KNOT, EV.knot.converge[0]);
  // scattered chaos shimmer that thickens as the particles converge
  const notes = [74, 76, 77, 81, 84, 86, 88, 89, 93];
  for (let t = k0 + 0.2; t < lock - 0.12;) {
    const u = clamp((t - k0) / (lock - k0));
    const m = notes[Math.min(notes.length - 1, Math.floor((rnd() * 0.55 + 0.45 * u) * notes.length))];
    put('sfx', t, sparkle(mtof(m), 0.45), { g: 0.12 + 0.18 * u, pan: rnd() * 1.6 - 0.8, rv: 0.55, dl: 0.3 });
    t += -Math.log(rnd()) / (2 + 22 * u * u);
  }
  // a reverse-style swell during the convergence
  put('sfx', c0, riser(lock - 0.125 - c0, 400, 7000, 1.4), { g: 0.5, rv: 0.4 });
  { const [l, r] = whoosh(lock - c0, 600, 5000, 0.97, 1.0); putSt('sfx', c0, l, r, { g: 0.25, rv: 0.3 }); }
  // LOCK: sub boom, downward whomp, chime cluster, three sonar pings (they match the three expanding rings)
  put('sfx', lock, impact(4.5, 40), { g: 1.0, rv: 0.55 });
  { const [l, r] = whoosh(1.4, 6000, 200, 0.12); putSt('sfx', lock, l, r, { g: 0.4, rv: 0.45 }); }
  [mtof(74), mtof(77), mtof(81), mtof(86)].forEach((f, i) => put('sfx', lock + i * 0.02, bell(f, 4.5), { g: 0.4, rv: 0.65, pan: -0.4 + i * 0.27 }));
  [0, 0.14, 0.3].forEach((d, i) => put('sfx', lock + d, blip([1760, 1318.5, 880][i], 0.5, -0.12), { g: 0.34, rv: 0.7, dl: 0.4, pan: [-0.3, 0.3, 0][i] }));
  put('music', lock, crash(3.4), { g: 0.45, rv: 0.45 });
  // headline lines
  EV.knot.head.forEach((lt, i) => { put('sfx', T(KNOT, lt), blip([1046.5, 1318.5][i], 0.12, 0.05), { g: 0.42, rv: 0.5, dl: 0.3 }); const [l, r] = whoosh(0.5, 900, 4000, 0.4); putSt('sfx', T(KNOT, lt) - 0.05, l, r, { g: 0.12, rv: 0.4 }); });
  // orbit rings fade in: two soft rising sweeps
  [0.3, 0.55].forEach((d, i) => { const n = Math.floor(0.9 * SR), y = new Float32Array(n); let ph = 0; for (let j = 0; j < n; j++) { const u = j / n; ph += TAU * (300 * (i + 1) + 700 * u) / SR; y[j] = Math.sin(ph) * Math.sin(Math.PI * u) * 0.4; } put('sfx', lock + d, y, { g: 0.2, rv: 0.55, dl: 0.3, pan: i ? 0.5 : -0.5 }); });
  // the equation types itself out: quiet keys
  [[EV.knot.head[1] + 0.45, 25], [EV.knot.head[1] + 0.73, 25], [EV.knot.head[1] + 1.01, 10]].forEach(([lt, nch]) => { for (let j = 0; j < nch; j++) put('sfx', T(KNOT, lt + j / 55), key('k'), { g: 0.16, pan: -0.35 + rnd() * 0.2 }); });
  // callouts
  EV.knot.callouts.forEach((lt, i) => { put('sfx', T(KNOT, lt), tick(500 + i * 200), { g: 0.4, rv: 0.2, pan: 0.3 }); put('sfx', T(KNOT, lt + 0.3), blip([1318.5, 1568, 1760][i], 0.09, 0.04), { g: 0.3, rv: 0.5, dl: 0.3, pan: 0.35 }); });
})();

// ---- code scene: every keystroke is a real character you see being typed
(function code() {
  const src = fs.readFileSync(path.join(__dirname, '../src/shared.js'), 'utf8');
  const m = /\/\/ >>> snippet\n([\s\S]*?)\n\s*\/\/ <<</.exec(src), ls = m[1].split('\n');
  const ind = Math.min(...ls.filter((l) => l.trim()).map((l) => l.match(/^ */)[0].length));
  const text = ls.map((l) => l.slice(ind)).join('\n');
  const times = S.makeTyping(text, EV.code.type[0], EV.code.type[1], 7), c0 = SCENES[CODE].start;
  // window slides in
  { const [l, r] = whoosh(0.8, 500, 3500, 0.55); putSt('sfx', T(CODE, EV.code.win - 0.2), l, r, { g: 0.22, rv: 0.45 }); }
  put('sfx', T(CODE, EV.code.win + 0.3), thunk(150, 0.18), { g: 0.2, rv: 0.4 });
  let count = 0;
  for (let i = 0; i < text.length; i++) {
    const ch = text[i];
    if (ch === ' ' && rnd() < 0.5) continue;
    if (ch !== '\n' && ch !== ' ' && (count++ % 2)) continue;                           // at ~100 cps, every other key keeps it a patter, not a buzz
    const kind = ch === '\n' ? 'enter' : ch === ' ' ? 'space' : 'k';
    put('sfx', c0 + times[i], key(kind), { g: kind === 'enter' ? 0.42 : 0.3 + rnd() * 0.12, pan: (rnd() - 0.5) * 0.4, rv: 0.1 });
  }
  // run the command
  put('sfx', T(CODE, EV.code.enter), key('enter'), { g: 0.6, rv: 0.2 });
  put('sfx', T(CODE, EV.code.enter), thunk(120, 0.2), { g: 0.35, rv: 0.3 });
  { const [l, r] = whoosh(0.7, 800, 4500, 0.5); putSt('sfx', T(CODE, EV.code.enter - 0.1), l, r, { g: 0.12, rv: 0.4 }); }
  // progress bar: 26 blocks fill, each a rising blip, over a slow rising hum
  const bars = 26;
  for (let i = 0; i < bars; i++) { const u = i / (bars - 1); put('sfx', T(CODE, EV.code.run[0] + u * (EV.code.run[1] - EV.code.run[0])), blip(520 * Math.pow(2, 1.6 * u), 0.05, 0.03), { g: 0.2, rv: 0.3, pan: -0.5 + u }); }
  // success: audio ✓ then mux ✓ with a little arpeggio
  put('sfx', T(CODE, EV.code.run[1] + 0.02), blip(988, 0.1, 0.05), { g: 0.4, rv: 0.5, dl: 0.3 });
  [74, 77, 81, 86].forEach((mm, i) => put('sfx', T(CODE, EV.code.done + i * 0.06), bell(mtof(mm), 2.2), { g: 0.34, rv: 0.6, pan: -0.3 + i * 0.2 }));
  put('sfx', T(CODE, EV.code.done), thunk(130, 0.3), { g: 0.4, rv: 0.3 });
})();

// ---- finale: counters tick up, the zero lands, the title blooms
(function finale() {
  EV.finale.counts.forEach(([a, b], ci) => {
    const nt = 22; // ticks while the number climbs; the climb is ease-out, so they thin out
    for (let i = 0; i < nt; i++) {
      const u = 1 - Math.pow(1 - i / (nt - 1), 1 / 2.2), t = T(FINALE, a + u * (b - a));
      put('sfx', t, blip(700 * Math.pow(2, 1.3 * (i / nt)), 0.035, 0.02), { g: 0.2, pan: [-0.45, 0, 0.45][ci], rv: 0.25 });
    }
    put('sfx', T(FINALE, b), bell(mtof(81 + ci * 3), 1.6), { g: 0.22, rv: 0.55, pan: [-0.45, 0, 0.45][ci] });
  });
  // the zero: a hollow low hit with a metallic ping
  const z = T(FINALE, EV.finale.zero);
  put('sfx', z, impact(3, 52), { g: 0.7, rv: 0.55 });
  put('sfx', z, bell(mtof(69), 4), { g: 0.3, rv: 0.7 });
  // title: swell, then two bells — "Code in." / "Cinema out."
  const tt = T(FINALE, EV.finale.title);
  put('sfx', tt - 0.5, riser(0.5, 800, 5000, 0.6), { g: 0.3, rv: 0.4 });
  [mtof(62), mtof(69), mtof(77), mtof(86)].forEach((f, i) => put('sfx', tt + i * 0.03, bell(f, 5), { g: 0.4, rv: 0.7, pan: -0.4 + i * 0.27 }));
  [mtof(74), mtof(81), mtof(89), mtof(93)].forEach((f, i) => put('sfx', tt + 0.4 + i * 0.05, bell(f, 5), { g: 0.3, rv: 0.75, pan: 0.4 - i * 0.25 }));
  for (let i = 0; i < 12; i++) put('sfx', tt + 0.4 + i * 0.07 + rnd() * 0.03, sparkle(mtof([86, 89, 93, 98][i % 4]), 0.6), { g: 0.15, rv: 0.7, pan: rnd() * 1.6 - 0.8, dl: 0.3 });
  // credit lines
  [EV.finale.credit - 0.15, EV.finale.credit + 0.25].forEach((lt, i) => put('sfx', T(FINALE, lt) , blip([1318.5, 1760][i], 0.1, 0.05), { g: 0.3, rv: 0.55, dl: 0.3 }));
})();

// ================================================================== render + master
function duckEnv() {
  const e = new Float32Array(N).fill(1);
  for (const t of kicks) { const i0 = Math.round(t * SR), len = Math.floor(0.45 * SR); for (let i = 0; i < len && i0 + i < N; i++) { const v = 1 - 0.5 * Math.exp(-(i / SR) / 0.13) * (1 - Math.exp(-(i / SR) / 0.004)); if (v < e[i0 + i]) e[i0 + i] = v; } }
  return e;
}
function rmsDb(arr) { let s = 0; for (let i = 0; i < arr[0].length; i++) s += arr[0][i] * arr[0][i] + arr[1][i] * arr[1][i]; return (10 * Math.log10(s / (2 * arr[0].length) + 1e-12)).toFixed(1); }

const out = process.argv[2] || path.join(__dirname, '../out/soundtrack.wav');
const duck = duckEnv();
const wet = pingpong(BUS.dly);
for (let i = 0; i < N; i++) { BUS.verb[0][i] += wet[0][i] * 0.35; BUS.verb[1][i] += wet[1][i] * 0.35; }
const rev = freeverb(BUS.verb);

const L = new Float32Array(N), R = new Float32Array(N);
const MASTER = Number(process.env.MASTER_GAIN || 1.0);
for (let i = 0; i < N; i++) {
  const t = i / SR;
  const l = BUS.pads[0][i] * duck[i] + BUS.music[0][i] + BUS.sfx[0][i] + wet[0][i] * 0.6 + rev[0][i] * 0.9;
  const r = BUS.pads[1][i] * duck[i] + BUS.music[1][i] + BUS.sfx[1][i] + wet[1][i] * 0.6 + rev[1][i] * 0.9;
  const fade = smoothstep(t / 0.06) * (1 - smoothstep((t - (DURATION - 1.6)) / 1.6));
  L[i] = l * fade * MASTER; R[i] = r * fade * MASTER;
}
// DC block, gentle saturation as a soft limiter
let dcl = 0, dcr = 0, pkL = 0;
for (let i = 0; i < N; i++) {
  dcl += 0.0006 * (L[i] - dcl); dcr += 0.0006 * (R[i] - dcr);
  L[i] = Math.tanh((L[i] - dcl) * 1.15) / Math.tanh(1.15); R[i] = Math.tanh((R[i] - dcr) * 1.15) / Math.tanh(1.15);
  pkL = Math.max(pkL, Math.abs(L[i]), Math.abs(R[i]));
}
const TARGET_DB = process.env.TARGET_PEAK_DB === undefined ? -6 : Number(process.env.TARGET_PEAK_DB);
const norm = db(TARGET_DB) / pkL;
for (let i = 0; i < N; i++) { L[i] *= norm; R[i] *= norm; }

// 16-bit WAV with TPDF dither
function writeWav(file, l, r) {
  const data = Buffer.alloc(N * 4), dr = S.mulberry32(5);
  for (let i = 0; i < N; i++) {
    const dl = (dr() - dr()) / 32768, dd = (dr() - dr()) / 32768;
    data.writeInt16LE(Math.max(-32768, Math.min(32767, Math.round((l[i] + dl) * 32767))), i * 4);
    data.writeInt16LE(Math.max(-32768, Math.min(32767, Math.round((r[i] + dd) * 32767))), i * 4 + 2);
  }
  const h = Buffer.alloc(44);
  h.write('RIFF', 0); h.writeUInt32LE(36 + data.length, 4); h.write('WAVEfmt ', 8); h.writeUInt32LE(16, 16); h.writeUInt16LE(1, 20); h.writeUInt16LE(2, 22);
  h.writeUInt32LE(SR, 24); h.writeUInt32LE(SR * 4, 28); h.writeUInt16LE(4, 32); h.writeUInt16LE(16, 34); h.write('data', 36); h.writeUInt32LE(data.length, 40);
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, Buffer.concat([h, data]));
}
writeWav(out, L, R);
console.log('  stems (dBFS rms, pre-master):', Object.entries({ pads: BUS.pads, music: BUS.music, sfx: BUS.sfx, verb: rev }).map(([k, v]) => k + ' ' + rmsDb(v)).join('  '));
console.log('  wrote', out, `${DURATION.toFixed(1)} s · ${SR} Hz stereo · peak ${TARGET_DB.toFixed(1)} dBFS`);
