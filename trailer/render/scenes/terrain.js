// The expedition: one continuous flight outward from spawn through all five
// rings. The GPU raymarches the world; this module flies the camera, sets the
// sky for each ring, and draws the chapter cards and in-game HUD on top.
import {
  W, H, clamp, lerp, smooth, ease, prog, envelope, mix3, monotone, latticeHash, rng, fbm1,
  FONTS, font, drawTracked, drawReveal, layoutGlyphs, rgba, hexToRgb, rgb01, typed,
} from '../lib.js';

// ---- JS mirror of terrainH() in shaders.js (same hash, same constants).
function noised(x, y) {
  const fx = Math.floor(x), fy = Math.floor(y);
  const ix = fx + 50000, iy = fy + 50000;
  const u0 = x - fx, u1 = y - fy;
  const ux = u0 * u0 * u0 * (u0 * (u0 * 6 - 15) + 10);
  const uy = u1 * u1 * u1 * (u1 * (u1 * 6 - 15) + 10);
  const dux = 30 * u0 * u0 * (u0 * (u0 - 2) + 1);
  const duy = 30 * u1 * u1 * (u1 * (u1 - 2) + 1);
  const a = latticeHash(ix, iy), b = latticeHash(ix + 1, iy), c = latticeHash(ix, iy + 1), d = latticeHash(ix + 1, iy + 1);
  const k1 = b - a, k2 = c - a, k4 = a - b - c + d;
  return [-1 + 2 * (a + k1 * ux + k2 * uy + k4 * ux * uy), 2 * dux * (k1 + k4 * uy), 2 * duy * (k2 + k4 * ux)];
}
const sstep = (a, b, x) => { const k = clamp((x - a) / (b - a)); return k * k * (3 - 2 * k); };
export const pathX = (z) => 14 * Math.sin(z * 0.021) + 22 * Math.sin(z * 0.0073 + 1.3);

// ---- Flight plan. World-space distance along the valley at each ring
// crossing; the spline keeps velocity continuous while it climbs from a
// stroll at home to a sprint at the frontier.
const TIMES = [10, 15, 20, 25, 30, 35];
const ZS = [0, 30, 85, 155, 240, 322];
const zAt = monotone(TIMES, ZS);
const BLOCKS = [0, 400, 1200, 2800, 5600, 8400];
const ORIGIN = [pathX(-2), -2];
const radiusAt = (z) => Math.hypot(pathX(z) - ORIGIN[0], z - ORIGIN[1]);
const BOUNDS = [radiusAt(30), radiusAt(85), radiusAt(155), radiusAt(240)];

function ringIndex(x, z) {
  const r = Math.hypot(x - ORIGIN[0], z - ORIGIN[1]);
  const b = BOUNDS;
  if (r < b[0]) return r / b[0];
  if (r < b[1]) return 1 + (r - b[0]) / (b[1] - b[0]);
  if (r < b[2]) return 2 + (r - b[1]) / (b[2] - b[1]);
  if (r < b[3]) return 3 + (r - b[2]) / (b[3] - b[2]);
  return 4 + (r - b[3]) / (b[3] - b[2]);
}

export function terrainH(x, z, oct = 6) {
  const w = ringIndex(x, z);
  let px = x * 0.045, pz = z * 0.045;
  let a = 0, bb = 1, dx = 0, dz = 0;
  const ero = lerp(0.35, 1.3, sstep(0, 3.5, w));
  for (let i = 0; i < oct; i++) {
    const n = noised(px, pz);
    dx += n[1]; dz += n[2];
    a += (bb * n[0]) / (1 + ero * (dx * dx + dz * dz));
    bb *= 0.5;
    // p = R2 * p * 2 with GLSL mat2(0.8,-0.6,0.6,0.8) (column-major)
    const nx = (0.8 * px + 0.6 * pz) * 2;
    const nz = (-0.6 * px + 0.8 * pz) * 2;
    px = nx; pz = nz;
  }
  const amp = lerp(1.6, 9.5, sstep(0.2, 4.2, w));
  let h = a * amp + lerp(0, 3, sstep(1.5, 4.5, w));
  const ddx = x - pathX(z);
  const sig = lerp(9, 7.5, sstep(0, 4, w));
  h -= Math.exp((-ddx * ddx) / (2 * sig * sig)) * lerp(1.5, 8, sstep(0.5, 4, w));
  return h;
}

// ---- Atmosphere per ring. Sun sinks a little further at every border.
function sunDir(elevDeg, az = 0.38) {
  const e = (elevDeg * Math.PI) / 180;
  return [Math.sin(az) * Math.cos(e), Math.sin(e), Math.cos(az) * Math.cos(e)];
}
const ATMOS = [
  { sun: sunDir(20), sunCol: [1.22, 1.0, 0.76], top: [0.20, 0.36, 0.64], hor: [0.74, 0.74, 0.70], fog: [0.62, 0.64, 0.62], den: 0.0055, amb: 0.95, stars: 0, moon: 0, clouds: 0.55, ctint: [1.0, 0.96, 0.9], exp: 1.0 },
  { sun: sunDir(11), sunCol: [1.40, 0.98, 0.58], top: [0.24, 0.35, 0.60], hor: [0.96, 0.74, 0.50], fog: [0.78, 0.62, 0.46], den: 0.0068, amb: 0.78, stars: 0, moon: 0, clouds: 0.65, ctint: [1.1, 0.8, 0.6], exp: 1.0 },
  { sun: sunDir(4), sunCol: [1.50, 0.78, 0.32], top: [0.15, 0.19, 0.36], hor: [0.98, 0.56, 0.27], fog: [0.64, 0.42, 0.25], den: 0.0085, amb: 0.55, stars: 0, moon: 0, clouds: 0.75, ctint: [1.3, 0.6, 0.3], exp: 1.05 },
  { sun: sunDir(1.0), sunCol: [1.05, 0.36, 0.2], top: [0.05, 0.06, 0.14], hor: [0.62, 0.21, 0.13], fog: [0.33, 0.13, 0.11], den: 0.010, amb: 0.75, stars: 0.75, moon: 1, clouds: 0.6, ctint: [0.9, 0.3, 0.2], exp: 1.15 },
  { sun: sunDir(2.5, 0.2), sunCol: [0.85, 0.16, 0.06], top: [0.02, 0.012, 0.018], hor: [0.50, 0.09, 0.04], fog: [0.24, 0.055, 0.03], den: 0.0145, amb: 0.3, stars: 0.18, moon: 0, clouds: 0.85, ctint: [1.0, 0.22, 0.08], exp: 1.25 },
];
function atmosAt(t) {
  // Snap to the ring we are in, cross-fading 0.5s either side of a border
  // (hidden by the curtain flash).
  let i = 0;
  for (let k = 1; k < 5; k++) if (t >= TIMES[k] - 0.5) i = k;
  const k = i > 0 ? smooth(TIMES[i] - 0.5, TIMES[i] + 0.5, t) : 1;
  const A = ATMOS[Math.max(0, i - 1)], B = ATMOS[i];
  const m = (a, b) => (Array.isArray(a) ? mix3(a, b, k) : lerp(a, b, k));
  const out = {};
  for (const key of Object.keys(B)) out[key] = m(A[key], B[key]);
  return out;
}

const norm = (v) => { const l = Math.hypot(v[0], v[1], v[2]); return [v[0] / l, v[1] / l, v[2] / l]; };
const cross = (a, b) => [a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]];

function rawAltitude(z, t) {
  // Clear the highest ground over the next stretch of valley.
  let hmax = -1e9;
  for (const s of [0, 1.5, 3.5, 6, 9]) hmax = Math.max(hmax, terrainH(pathX(z + s), z + s, 6) - s * 0.06);
  // Final move: crane up over the ridge to reveal the burning plain.
  const clearance = lerp(1.9, 4.2, smooth(10, 32, t)) + 7.0 * ease.inOutSine(clamp((t - 32.3) / 2.7));
  return hmax + clearance;
}

export function camera(t) {
  const z = zAt(t);
  // Temporal smoothing of altitude (stateless: average a few nearby times).
  let y = 0, n = 0;
  for (let k = -5; k <= 5; k++) {
    const tt = t + k * 0.09;
    const w = 1 - Math.abs(k) / 6;
    y += rawAltitude(zAt(tt), tt) * w; n += w;
  }
  y /= n;
  const x = pathX(z) + fbm1(t * 0.35, 3) * 0.6;
  const look = 14;
  const lz = z + look;
  const pitch = -0.12 * look + fbm1(t * 0.5, 5) * 0.25 - 1.5 * ease.inOutSine(clamp((t - 32.3) / 2.7));
  const target = [pathX(lz) + fbm1(t * 0.4, 9) * 0.5, y + pitch, lz];
  const f = norm([target[0] - x, target[1] - y, target[2] - z]);
  const curv = -14 * 0.021 * 0.021 * Math.sin(z * 0.021) - 22 * 0.0073 * 0.0073 * Math.sin(z * 0.0073 + 1.3);
  const roll = clamp(-curv * 22, -0.2, 0.2) + fbm1(t * 0.6, 11) * 0.02;
  let r = norm(cross([0, 1, 0], f));
  let u = cross(f, r);
  const cr = Math.cos(roll), sr = Math.sin(roll);
  const r2 = [r[0] * cr + u[0] * sr, r[1] * cr + u[1] * sr, r[2] * cr + u[2] * sr];
  const u2 = [u[0] * cr - r[0] * sr, u[1] * cr - r[1] * sr, u[2] * cr - r[2] * sr];
  return { pos: [x, y, z], rot: [...r2, ...u2, ...f], right: r2, up: u2, fwd: f, z };
}

function blocksAt(z) {
  for (let i = 0; i < ZS.length - 1; i++) {
    if (z <= ZS[i + 1]) return lerp(BLOCKS[i], BLOCKS[i + 1], (z - ZS[i]) / (ZS[i + 1] - ZS[i]));
  }
  return BLOCKS[BLOCKS.length - 1];
}

// ---- Watchers in the Wildmarch: pairs of eyes placed on real terrain so
// hills correctly hide them (depth-tested in the shader).
function eyes(t, tl) {
  const t0 = tl.cues.eyes;
  const cam0 = camera(t0);
  const r = rng(77);
  const out = new Float32Array(24);
  const spots = [[30, -7], [36, 9], [42, -12], [48, 6], [34, 13], [55, -5]];
  spots.forEach(([dz, dx], i) => {
    const z = cam0.z + dz;
    const x = pathX(z) + dx;
    const y = terrainH(x, z, 9) + 0.55;
    const start = t0 + i * 0.22 + r() * 0.1;
    const life = 1.7 + r() * 0.5;
    const k = (t - start) / life;
    let open = 0;
    if (k > 0 && k < 1) {
      open = sstep(0, 0.12, k) * (1 - sstep(0.82, 1, k));
      const blink = (k * life * 1.3 + i * 0.37) % 1.1;
      if (blink > 0.95 && blink < 1.05) open *= 0.1;
    }
    out.set([x, y, z, open], i * 4);
  });
  return out;
}

// ---- Particles: motes at home, ash and embers at the frontier.
function particles(ctx, t, cam, ring) {
  const N = 220;
  const r = rng(4242);
  const f = H / 0.93;
  ctx.save();
  for (let i = 0; i < N; i++) {
    const kind = r();
    const px = (r() - 0.5) * 40, py = (r() - 0.5) * 18, pz0 = r() * 40 + 0.8;
    const sway = r() * 6.28, sz = 0.5 + r();
    // World-locked box that wraps around the camera as it flies.
    const zrel = ((pz0 - cam.z * 1.0) % 40 + 40) % 40 + 0.8;
    let X = px + Math.sin(t * 0.7 + sway) * 0.4;
    let Y = py;
    let col, alpha, size, add = false;
    if (ring >= 3.6) {
      if (kind < 0.7) { // ash: falls, grey
        Y = ((py - t * 1.1 + 900) % 18) - 9;
        col = [150, 140, 135]; alpha = 0.55; size = 0.05 * sz;
      } else { // embers: rise, glow
        Y = ((py + t * 2.2 + 900) % 18) - 9;
        X += Math.sin(t * 2.3 + sway) * 0.5;
        col = [255, 120, 40]; alpha = 0.9; size = 0.028 * sz; add = true;
      }
    } else if (ring < 0.9) {
      if (kind > 0.35) continue;
      Y = py * 0.3 - 1.0 + Math.sin(t * 0.9 + sway) * 0.3;
      col = [255, 225, 160]; alpha = 0.5; size = 0.02 * sz; add = true;
    } else continue;
    const amt = ring >= 3.6 ? smooth(3.6, 4.0, ring) : 1 - smooth(0.6, 0.9, ring);
    const sx = W / 2 + (X / zrel) * f;
    const sy = H / 2 - (Y / zrel) * f;
    const rad = Math.max(0.6, (size / zrel) * f);
    if (sx < -50 || sx > W + 50 || sy < -50 || sy > H + 50) continue;
    const near = clamp(1 - zrel / 40);
    const blur = rad * (zrel < 4 ? 2.5 : 1);
    ctx.globalCompositeOperation = add ? 'lighter' : 'source-over';
    const g = ctx.createRadialGradient(sx, sy, 0, sx, sy, blur * 1.6);
    g.addColorStop(0, rgba(col, alpha * amt * (0.3 + 0.7 * near)));
    g.addColorStop(1, rgba(col, 0));
    ctx.fillStyle = g;
    ctx.beginPath();
    ctx.arc(sx, sy, blur * 1.6, 0, Math.PI * 2);
    ctx.fill();
  }
  ctx.restore();
}

// ---- Overlay: chapter cards + HUD.
const SKULL = '☠';
const HEAT_COL = { Calm: '#7fe08f', Restless: '#f2e27a', Hunted: '#f0a531', Marked: '#ef5a4c' };
const heatLabel = (h) => (h >= 75 ? 'Marked' : h >= 50 ? 'Hunted' : h >= 25 ? 'Restless' : 'Calm');

function heatAt(t) {
  return monotone([10, 20, 25, 30, 35], [0, 0, 18, 46, 72])(t);
}
function fieldMarksAt(t) {
  return Math.round(monotone([10, 15, 20, 25, 30, 35], [0, 0, 20, 80, 160, 240])(t));
}

function chapterCard(ctx, t, ring, idx) {
  const t0 = ring.start + 0.35, t1 = ring.end - 0.25;
  if (t < t0 - 0.1 || t > t1 + 0.1) return;
  const out = 1 - prog(t, t1 - 0.45, 0.45, ease.inCubic);
  const col = hexToRgb(ring.color);
  const x = 150;
  ctx.save();
  ctx.globalAlpha = out;
  ctx.translate(0, -12 * (1 - out));

  // Legibility pool behind the card.
  const g = ctx.createRadialGradient(x + 380, 770, 40, x + 380, 770, 720);
  g.addColorStop(0, 'rgba(0,0,0,0.42)');
  g.addColorStop(1, 'rgba(0,0,0,0)');
  ctx.fillStyle = g;
  ctx.fillRect(0, 0, W, H);

  // Index line, typed on.
  font(ctx, FONTS.mono, 19, 500);
  const range = ring.outer < 0 ? `${ring.inner.toLocaleString('en-US')}+ BLOCKS` : `${ring.inner.toLocaleString('en-US')} – ${ring.outer.toLocaleString('en-US')} BLOCKS`;
  const label = `RING ${String(idx).padStart(2, '0')}   ·   ${range}`;
  ctx.fillStyle = rgba(col, 0.95);
  drawTracked(ctx, typed(label, t, t0, 55), x, 640, 0.28);
  // rule
  const rk = prog(t, t0 + 0.1, 0.6, ease.outExpo);
  ctx.fillStyle = rgba(col, 0.6);
  ctx.fillRect(x, 662, 560 * rk, 1.5);

  // Ring name: resolves from blur while tracking tightens.
  const nk = prog(t, t0 + 0.15, 0.9, ease.outCubic);
  font(ctx, FONTS.display, 132, 600);
  ctx.save();
  ctx.shadowColor = 'rgba(0,0,0,0.55)';
  ctx.shadowBlur = 30;
  drawReveal(ctx, ring.name, x - 4, 772, nk, { tracking: lerp(0.09, 0.005, ease.outExpo(nk)), stagger: 0.55, rise: 22, blur: 14, color: '#f4ecdc' });
  ctx.restore();

  // Danger: skulls stamp in on the beat.
  font(ctx, FONTS.glyph, 46, 400);
  if (ring.danger === 0) {
    font(ctx, FONTS.mono, 18, 500);
    ctx.fillStyle = rgba(col, 0.9 * prog(t, t0 + 0.7, 0.4));
    drawTracked(ctx, 'SAFE GROUND  ·  NO HOSTILE SPAWNS', x, 828, 0.24);
  } else {
    for (let i = 0; i < ring.danger; i++) {
      const ts = t0 + 0.625 + i * 0.156;
      const k = clamp((t - ts) / 0.35);
      if (k <= 0) continue;
      const s = 1 + 0.6 * (1 - ease.outBack(k, 2.2));
      ctx.save();
      ctx.translate(x + 22 + i * 60, 822);
      ctx.scale(s, s);
      ctx.globalAlpha *= clamp(k * 3);
      ctx.fillStyle = rgba(col, 1);
      ctx.shadowColor = rgba(col, 0.8);
      ctx.shadowBlur = 18;
      ctx.textAlign = 'center';
      ctx.fillText(SKULL, 0, 12);
      ctx.restore();
    }
  }

  // The mod's own entry message, in an expedition-journal hand.
  font(ctx, FONTS.journal, 40, 400, 'italic');
  ctx.fillStyle = '#efe5d2';
  ctx.save();
  ctx.shadowColor = 'rgba(0,0,0,0.6)';
  ctx.shadowBlur = 16;
  drawReveal(ctx, `“${ring.message}”`, x, 892, prog(t, t0 + 1.1, 1.2), { stagger: 0.7, rise: 6, blur: 6, color: 'rgba(239,229,210,0.92)' });
  ctx.restore();
  ctx.restore();
}

function hud(ctx, t, tl, cam, lb) {
  if (lb <= 0.01) return;
  const barH = 138 * lb;
  ctx.save();
  ctx.globalAlpha = smooth(0, 1, lb);
  // Top bar: expedition log + distance from spawn.
  font(ctx, FONTS.mono, 16, 500);
  ctx.fillStyle = 'rgba(231,222,204,0.55)';
  drawTracked(ctx, 'EXPEDITION LOG  ·  DAY 47', 150, barH / 2 + 6, 0.3);
  const blocks = Math.round(blocksAt(cam.z));
  ctx.fillStyle = 'rgba(231,222,204,0.8)';
  drawTracked(ctx, `${blocks.toLocaleString('en-US').padStart(5, ' ')} BLOCKS FROM SPAWN`, W - 150, barH / 2 + 6, 0.3, 'right');

  // Bottom bar: a faithful re-set of the mod's action bar.
  const ringIdx = tl.rings.findIndex((r) => t >= r.start && t < r.end);
  const ring = tl.rings[Math.max(0, ringIdx)];
  const y = H - barH / 2 + 7;
  const parts = [];
  if (ring.danger === 0) {
    parts.push([ring.name, ring.color], ['   ·   home: no Heat, no hostiles', 'rgba(200,200,200,0.55)']);
  } else {
    const heat = heatAt(t);
    const lbl = heatLabel(heat);
    parts.push([`${ring.name} `, ring.color], [SKULL.repeat(ring.danger), ring.color, FONTS.glyph]);
    parts.push(['   Heat: ', 'rgba(170,170,170,0.75)'], [`${lbl} (${Math.floor(heat)})`, HEAT_COL[lbl]]);
    parts.push([`   ◈ ${fieldMarksAt(t)} field`, '#86d9d4']);
  }
  font(ctx, FONTS.mono, 22, 500);
  let total = 0;
  const widths = parts.map(([s, , fam]) => {
    font(ctx, fam ?? FONTS.mono, 22, 500);
    const w = ctx.measureText(s).width;
    total += w;
    return w;
  });
  let x = W / 2 - total / 2;
  ctx.shadowColor = 'rgba(0,0,0,0.9)';
  ctx.shadowBlur = 0;
  ctx.shadowOffsetX = 2;
  ctx.shadowOffsetY = 2;
  parts.forEach(([s, c, fam], i) => {
    font(ctx, fam ?? FONTS.mono, 22, 500);
    ctx.fillStyle = c;
    ctx.textAlign = 'left';
    ctx.fillText(s, x, y);
    x += widths[i];
  });
  ctx.restore();
}

export function drawTerrain(t, tl, G, progs, target, ctx) {
  const cam = camera(t);
  const A = atmosAt(t);
  const ringT = clamp(Math.floor((t - 10) / 5), 0, 4);
  // Curtain: the next border ahead, glowing in the colour of what's beyond.
  const next = Math.min(4, ringT + 1);
  const crossT = TIMES[ringT + 1];
  const curtainAmt = ringT < 4 ? 1.4 * ease.inCubic(smooth(crossT - 2.8, crossT - 0.1, t)) : 0;
  const ringColor = rgb01(tl.rings[next].color);
  const crossK = (k) => Math.exp(-Math.pow((t - TIMES[k]) / 0.14, 2));
  let flash = [0, 0, 0];
  let kick = 0;
  for (let k = 1; k < 5; k++) {
    const c = crossK(k);
    const rc = rgb01(tl.rings[k].color);
    flash = [flash[0] + rc[0] * c * 0.55, flash[1] + rc[1] * c * 0.55, flash[2] + rc[2] * c * 0.55];
    kick += Math.exp(-Math.pow((t - TIMES[k]) / 0.35, 2));
  }
  // Entry flash from the map dive.
  const inFlash = Math.exp(-Math.pow((t - 10) / 0.18, 2)) * (t >= 10 ? 1 : 0);
  flash = flash.map((v, i) => v + [1.0, 0.85, 0.6][i] * inFlash * 0.9);

  G.draw(progs.terrain, {
    uRes: [target.w, target.h],
    uTime: t,
    uCamPos: cam.pos,
    uCamRot: cam.rot,
    uFov: 0.93 + 0.12 * kick,
    uOrigin: ORIGIN,
    uBounds: BOUNDS,
    uSunDir: norm(A.sun),
    uSunCol: A.sunCol,
    uSkyTop: A.top,
    uSkyHor: A.hor,
    uFogCol: A.fog,
    uFogDen: A.den,
    uStars: A.stars,
    uMoonDir: norm([-0.5, 0.42, 0.75]),
    uMoon: A.moon,
    uCurtainR: ringT < 4 ? BOUNDS[ringT] : 1e6,
    uCurtainCol: ringColor,
    uCurtainAmt: curtainAmt,
    uAmbient: A.amb,
    uCloudAmt: A.clouds,
    uCloudTint: A.ctint,
    uEyes: { v4: eyes(t, tl) },
    uHashTex: progs.hashTex,
    uStepK: lerp(0.85, 0.6, smooth(17, 23, t)),
  }, target);

  // Overlay.
  const ri = ringIndex(cam.pos[0], cam.pos[2]);
  particles(ctx, t, cam, ri);
  const lb = ease.outCubic(clamp((t - 10.05) / 0.7));
  tl.rings.forEach((ring, i) => chapterCard(ctx, t, ring, i));
  hud(ctx, t, tl, cam, lb);

  const shake = kick > 0.05 ? [fbm1(t * 30, 1) * 7 * kick, fbm1(t * 30, 2) * 7 * kick] : [0, 0];
  return {
    flash,
    shake,
    letterbox: lb,
    exposure: A.exp,
    bloom: 0.6,
    bloomThreshold: 0.8,
    ca: 0.0018 + 0.004 * kick,
    vignette: 0.45,
    grain: 0.05,
    lift: [0.01, 0.008, 0.012],
    gamma: [1.0, 1.0, 1.02],
    gain: [1.02, 1.0, 0.97],
    sat: 1.05,
    fade: 1,
  };
}

export const terrainInfo = { ORIGIN, BOUNDS, TIMES, ZS };
