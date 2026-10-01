// Shared math, easing, randomness and typography helpers.
// Everything here is pure: a frame is a function of `t` alone, so any frame
// can be rendered in isolation (which is what lets render.mjs split the film
// across parallel browser workers).

export const W = 1920;
export const H = 1080;

export const clamp = (x, a = 0, b = 1) => Math.min(b, Math.max(a, x));
export const lerp = (a, b, k) => a + (b - a) * k;
export const invLerp = (a, b, x) => clamp((x - a) / (b - a));
export const smooth = (a, b, x) => { const k = invLerp(a, b, x); return k * k * (3 - 2 * k); };
export const mix3 = (a, b, k) => [lerp(a[0], b[0], k), lerp(a[1], b[1], k), lerp(a[2], b[2], k)];

// Easing. Each element in the film gets a curve chosen for how it should
// feel: type settles with expo-out, stamps overshoot, camera moves use
// sine-in-out so they never start or stop with a jerk.
export const ease = {
  linear: (k) => k,
  inQuad: (k) => k * k,
  outQuad: (k) => 1 - (1 - k) * (1 - k),
  inOutSine: (k) => 0.5 - 0.5 * Math.cos(Math.PI * k),
  outCubic: (k) => 1 - Math.pow(1 - k, 3),
  inCubic: (k) => k * k * k,
  inOutCubic: (k) => (k < 0.5 ? 4 * k * k * k : 1 - Math.pow(-2 * k + 2, 3) / 2),
  outExpo: (k) => (k >= 1 ? 1 : 1 - Math.pow(2, -10 * k)),
  inExpo: (k) => (k <= 0 ? 0 : Math.pow(2, 10 * k - 10)),
  inOutExpo: (k) => (k <= 0 ? 0 : k >= 1 ? 1 : k < 0.5 ? Math.pow(2, 20 * k - 10) / 2 : (2 - Math.pow(2, -20 * k + 10)) / 2),
  outBack: (k, s = 1.70158) => 1 + (s + 1) * Math.pow(k - 1, 3) + s * Math.pow(k - 1, 2),
  outQuint: (k) => 1 - Math.pow(1 - k, 5),
};

// Progress of `t` through [t0, t0+dur], eased.
export const prog = (t, t0, dur, fn = ease.linear) => fn(clamp((t - t0) / dur));

// Envelope that fades in over `a`, holds, then fades out over `r` before t1.
export const envelope = (t, t0, t1, a = 0.3, r = 0.3) =>
  Math.min(smooth(t0, t0 + a, t), 1 - smooth(t1 - r, t1, t));

// Damped spring step response (unit step at t=0). Used for gauge needles and
// stamps: superpose several of these and you get physically plausible motion
// without integrating state frame-to-frame.
export function spring(t, freq = 9, damping = 0.32) {
  if (t <= 0) return 0;
  const w = 2 * Math.PI * freq * 0.25;
  const z = damping;
  const wd = w * Math.sqrt(1 - z * z);
  return 1 - Math.exp(-z * w * t) * (Math.cos(wd * t) + (z * w / wd) * Math.sin(wd * t));
}

// Integer hash identical to the GLSL one in shaders.js, so JS can query the
// same terrain the GPU draws (for camera clearance).
export function hashU(x) {
  x >>>= 0;
  x ^= x >>> 16; x = Math.imul(x, 0x7feb352d);
  x ^= x >>> 15; x = Math.imul(x, 0x846ca68b);
  x ^= x >>> 16;
  return x >>> 0;
}
export const hash2 = (x, y) => hashU((x + hashU(y)) >>> 0) / 4294967295;

// Lattice table shared with the terrain shader (uploaded as an R32F texture).
export const HASH_TABLE = (() => {
  const r = rng(1312);
  const a = new Float32Array(256 * 256);
  for (let i = 0; i < a.length; i++) a[i] = r();
  return a;
})();
export const latticeHash = (x, y) => HASH_TABLE[((y & 255) << 8) | (x & 255)];

// Seeded PRNG (mulberry32).
export function rng(seed) {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

// Smooth 1D value noise for wobble/flicker.
export function noise1(x, seed = 0) {
  const i = Math.floor(x);
  const f = x - i;
  const u = f * f * (3 - 2 * f);
  const a = hash2(i + 100000, seed + 7) * 2 - 1;
  const b = hash2(i + 100001, seed + 7) * 2 - 1;
  return a + (b - a) * u;
}
export const fbm1 = (x, seed = 0) =>
  noise1(x, seed) * 0.6 + noise1(x * 2.13, seed + 1) * 0.28 + noise1(x * 4.37, seed + 2) * 0.12;

export function hexToRgb(hex) {
  const v = parseInt(hex.slice(1), 16);
  return [(v >> 16) & 255, (v >> 8) & 255, v & 255];
}
export const rgba = (rgb, a = 1) => `rgba(${rgb[0] | 0},${rgb[1] | 0},${rgb[2] | 0},${a})`;
export const rgb01 = (hex) => hexToRgb(hex).map((c) => c / 255);

// ---------------------------------------------------------------- typography

export const FONTS = {
  display: '"Cormorant Garamond"',
  journal: '"IM Fell English"',
  mono: '"IBM Plex Mono"',
  figures: '"Playfair Display"', // lining numerals for big counters
  glyph: '"DejaVu Sans"',
};

export function font(ctx, family, size, weight = 400, style = 'normal') {
  ctx.font = `${style} ${weight} ${size}px ${family}`;
}

// Draw text with manual letter-spacing (em units). Returns total width.
// Canvas `letterSpacing` exists but we also want per-glyph control (decode
// effects, staggered reveals), so glyphs are laid out by hand.
export function layoutGlyphs(ctx, text, trackingEm = 0) {
  const size = parseFloat(/(\d+(?:\.\d+)?)px/.exec(ctx.font)[1]);
  const track = trackingEm * size;
  const glyphs = [];
  let x = 0;
  const chars = [...text];
  for (let i = 0; i < chars.length; i++) {
    const ch = chars[i];
    // Kerning-aware advance: measure pair width minus the next glyph.
    const pair = i + 1 < chars.length ? ctx.measureText(ch + chars[i + 1]).width - ctx.measureText(chars[i + 1]).width : ctx.measureText(ch).width;
    glyphs.push({ ch, x, w: pair });
    x += pair + (i + 1 < chars.length ? track : 0);
  }
  return { glyphs, width: x };
}

export function drawTracked(ctx, text, x, y, trackingEm = 0, align = 'left') {
  const { glyphs, width } = layoutGlyphs(ctx, text, trackingEm);
  const ox = align === 'center' ? x - width / 2 : align === 'right' ? x - width : x;
  ctx.textAlign = 'left';
  for (const g of glyphs) ctx.fillText(g.ch, ox + g.x, y);
  return width;
}

// Text that resolves out of blur: each glyph fades in with a stagger,
// rising slightly and sharpening. `k` is overall progress 0..1.
export function drawReveal(ctx, text, x, y, k, opts = {}) {
  const { tracking = 0, align = 'left', stagger = 0.5, rise = 14, blur = 10, color = '#fff' } = opts;
  const { glyphs, width } = layoutGlyphs(ctx, text, tracking);
  const ox = align === 'center' ? x - width / 2 : align === 'right' ? x - width : x;
  const n = glyphs.length;
  ctx.textAlign = 'left';
  for (let i = 0; i < n; i++) {
    const g = glyphs[i];
    const start = (i / Math.max(1, n - 1)) * stagger;
    const gk = ease.outCubic(clamp((k - start) / (1 - stagger)));
    if (gk <= 0) continue;
    ctx.save();
    ctx.globalAlpha *= gk;
    const b = (1 - gk) * blur;
    if (b > 0.3) ctx.filter = `blur(${b.toFixed(2)}px)`;
    ctx.fillStyle = color;
    ctx.fillText(g.ch, ox + g.x, y + (1 - gk) * rise);
    ctx.restore();
  }
  return width;
}

// Characters scramble through a glyph set before locking, left to right.
const SCRAMBLE = 'ABCDEFGHJKLMNPRSTUVWXYZ#%&*+=?<>/\\|';
export function decodeText(text, k, seed = 1, tFrame = 0) {
  const chars = [...text];
  const r = rng(seed + Math.floor(tFrame * 24));
  return chars
    .map((c, i) => {
      if (c === ' ') return ' ';
      const lock = (i + 1) / chars.length;
      if (k >= lock) return c;
      if (k < lock - 0.45) return ' ';
      return SCRAMBLE[Math.floor(r() * SCRAMBLE.length)];
    })
    .join('');
}

// Decode drawn on the final word's glyph grid, so the layout never jumps
// while characters are still scrambling.
export function drawDecoded(ctx, text, x, y, k, seed, t, opts = {}) {
  const { tracking = 0, align = 'left' } = opts;
  const { glyphs, width } = layoutGlyphs(ctx, text, tracking);
  const ox = align === 'center' ? x - width / 2 : align === 'right' ? x - width : x;
  const shown = [...decodeText(text, k, seed, t).padEnd(text.length, ' ')];
  ctx.textAlign = 'left';
  glyphs.forEach((g, i) => {
    const ch = shown[i] ?? '';
    if (!ch.trim()) return;
    ctx.fillText(ch, ox + g.x + (ch !== g.ch ? (g.w - ctx.measureText(ch).width) / 2 : 0), y);
  });
  return width;
}

// Typewriter: reveals characters over [t0, t0+dur]; returns substring.
export function typed(text, t, t0, cps = 40) {
  const n = Math.floor(clamp((t - t0) * cps, 0, text.length));
  return text.slice(0, n);
}

export function sceneAt(timeline, t) {
  for (const s of timeline.scenes) if (t >= s.start && t < s.end) return s;
  return timeline.scenes[timeline.scenes.length - 1];
}

// Monotone cubic interpolation through (xs, ys): used for the camera's
// distance-from-spawn curve so velocity changes smoothly.
export function monotone(xs, ys) {
  const n = xs.length;
  const d = [];
  const m = new Array(n).fill(0);
  for (let i = 0; i < n - 1; i++) d.push((ys[i + 1] - ys[i]) / (xs[i + 1] - xs[i]));
  m[0] = d[0];
  m[n - 1] = d[n - 2];
  for (let i = 1; i < n - 1; i++) m[i] = d[i - 1] * d[i] <= 0 ? 0 : (2 * d[i - 1] * d[i]) / (d[i - 1] + d[i]);
  return (x) => {
    if (x <= xs[0]) return ys[0] + m[0] * (x - xs[0]);
    if (x >= xs[n - 1]) return ys[n - 1] + m[n - 1] * (x - xs[n - 1]);
    let i = 0;
    while (x > xs[i + 1]) i++;
    const h = xs[i + 1] - xs[i];
    const s = (x - xs[i]) / h;
    const h00 = 2 * s ** 3 - 3 * s ** 2 + 1, h10 = s ** 3 - 2 * s ** 2 + s;
    const h01 = -2 * s ** 3 + 3 * s ** 2, h11 = s ** 3 - s ** 2;
    return h00 * ys[i] + h10 * h * m[i] + h01 * ys[i + 1] + h11 * h * m[i + 1];
  };
}
