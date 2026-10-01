// Expedition Heat, as a vintage panel instrument. The needle is a sum of
// damped-spring step responses (one per Heat jump), so every jump overshoots
// and settles like a real movement; at 100 the glass cracks.
import {
  W, H, clamp, lerp, smooth, ease, prog, spring, rng, fbm1, noise1,
  FONTS, font, drawTracked, drawReveal, rgba, typed,
} from '../lib.js';

const CX = 600, CY = 548, R = 330;
const STATES = [
  { name: 'Calm', from: 0, col: [120, 196, 120], ink: [76, 128, 72] },
  { name: 'Restless', from: 25, col: [236, 214, 108], ink: [170, 150, 52] },
  { name: 'Hunted', from: 50, col: [240, 162, 52], ink: [184, 112, 30] },
  { name: 'Marked', from: 75, col: [236, 84, 70], ink: [170, 40, 34] },
];
const stateOf = (v) => STATES[v >= 75 ? 3 : v >= 50 ? 2 : v >= 25 ? 1 : 0];
const ang = (v) => ((135 + 2.7 * v) * Math.PI) / 180;

let dialTex = null;
function dialTexture() {
  if (dialTex) return dialTex;
  const c = document.createElement('canvas');
  c.width = c.height = 2 * R;
  const g = c.getContext('2d');
  const img = g.createImageData(c.width, c.height);
  const r = rng(5);
  // Aged paper: low-frequency stains + fine fibre noise.
  for (let y = 0; y < c.height; y++) {
    for (let x = 0; x < c.width; x++) {
      const i = (y * c.width + x) * 4;
      const stain = fbm1(x * 0.012 + fbm1(y * 0.01, 2) * 2, 7) * 0.5 + 0.5;
      const fibre = r();
      const v = 255 - stain * 34 - fibre * 16;
      img.data[i] = v;
      img.data[i + 1] = v - 6 - stain * 6;
      img.data[i + 2] = v - 18 - stain * 14;
      img.data[i + 3] = 255;
    }
  }
  g.putImageData(img, 0, 0);
  dialTex = c;
  return c;
}

function needleValue(t, steps) {
  let v = 0, prev = 0;
  for (const [ts, val] of steps) {
    if (t < ts) break;
    v += (val - prev) * spring(t - ts, 12, 0.42);
    prev = val;
  }
  return v;
}

function drawBezel(ctx) {
  // Drop shadow on the panel.
  ctx.save();
  ctx.shadowColor = 'rgba(0,0,0,0.75)';
  ctx.shadowBlur = 60;
  ctx.shadowOffsetX = 18;
  ctx.shadowOffsetY = 26;
  ctx.fillStyle = '#111';
  ctx.beginPath(); ctx.arc(CX, CY, R + 44, 0, Math.PI * 2); ctx.fill();
  ctx.restore();
  // Turned-metal bezel: conic bands fake anisotropic reflections.
  const cg = ctx.createConicGradient(-0.6, CX, CY);
  const stops = [[0, '#2b2824'], [0.08, '#77706a'], [0.14, '#3a3631'], [0.3, '#1d1b19'], [0.42, '#5d5751'], [0.5, '#24211e'],
    [0.62, '#8a837b'], [0.68, '#34302c'], [0.85, '#191715'], [0.93, '#4c4741'], [1, '#2b2824']];
  for (const [o, c] of stops) cg.addColorStop(o, c);
  ctx.fillStyle = cg;
  ctx.beginPath(); ctx.arc(CX, CY, R + 44, 0, Math.PI * 2); ctx.fill();
  // inner lip
  const lip = ctx.createLinearGradient(CX - R, CY - R, CX + R, CY + R);
  lip.addColorStop(0, '#9a938a'); lip.addColorStop(0.5, '#2a2724'); lip.addColorStop(1, '#0c0b0a');
  ctx.fillStyle = lip;
  ctx.beginPath(); ctx.arc(CX, CY, R + 12, 0, Math.PI * 2); ctx.fill();
  ctx.fillStyle = '#0d0c0b';
  ctx.beginPath(); ctx.arc(CX, CY, R + 4, 0, Math.PI * 2); ctx.fill();
}

function drawFace(ctx, v) {
  ctx.save();
  ctx.beginPath(); ctx.arc(CX, CY, R, 0, Math.PI * 2); ctx.clip();
  ctx.drawImage(dialTexture(), CX - R, CY - R);
  // Warm lamp from upper left, rim shadow from the bezel.
  const lg = ctx.createRadialGradient(CX - 120, CY - 140, 40, CX, CY, R * 1.1);
  lg.addColorStop(0, 'rgba(255,236,190,0.18)');
  lg.addColorStop(0.6, 'rgba(120,90,50,0.0)');
  lg.addColorStop(1, 'rgba(40,25,10,0.55)');
  ctx.fillStyle = lg;
  ctx.fillRect(CX - R, CY - R, 2 * R, 2 * R);
  const rim = ctx.createRadialGradient(CX, CY, R * 0.86, CX, CY, R);
  rim.addColorStop(0, 'rgba(0,0,0,0)');
  rim.addColorStop(1, 'rgba(0,0,0,0.5)');
  ctx.fillStyle = rim;
  ctx.fillRect(CX - R, CY - R, 2 * R, 2 * R);

  // State bands, printed in ink.
  STATES.forEach((s, i) => {
    const a0 = ang(s.from), a1 = ang(i < 3 ? STATES[i + 1].from : 100);
    ctx.strokeStyle = rgba(s.ink, 0.85);
    ctx.lineWidth = 11;
    ctx.beginPath(); ctx.arc(CX, CY, R * 0.6, a0 + 0.012, a1 - 0.012); ctx.stroke();
    // label along the arc
    const label = s.name.toUpperCase();
    font(ctx, FONTS.mono, 13, 600);
    const mid = (a0 + a1) / 2;
    const rr = R * 0.5;
    const total = ctx.measureText(label).width + label.length * 3;
    let a = mid - (total / 2) / rr;
    ctx.fillStyle = rgba(s.ink, 0.95);
    for (const ch of label) {
      const w = ctx.measureText(ch).width + 3;
      const aa = a + (w / 2) / rr;
      ctx.save();
      ctx.translate(CX + Math.cos(aa) * rr, CY + Math.sin(aa) * rr);
      ctx.rotate(aa + Math.PI / 2);
      ctx.textAlign = 'center';
      ctx.fillText(ch, 0, 4);
      ctx.restore();
      a += w / rr;
    }
  });

  // Ticks and numerals.
  ctx.strokeStyle = '#2a241d';
  for (let k = 0; k <= 100; k += 2) {
    const a = ang(k);
    const major = k % 10 === 0;
    const r0 = R * 0.93, r1 = major ? R * 0.83 : k % 10 === 5 ? R * 0.87 : R * 0.89;
    ctx.lineWidth = major ? 3.2 : 1.4;
    ctx.beginPath();
    ctx.moveTo(CX + Math.cos(a) * r0, CY + Math.sin(a) * r0);
    ctx.lineTo(CX + Math.cos(a) * r1, CY + Math.sin(a) * r1);
    ctx.stroke();
    if (major) {
      font(ctx, FONTS.display, 36, 600);
      ctx.fillStyle = '#2a241d';
      ctx.textAlign = 'center';
      ctx.fillText(String(k), CX + Math.cos(a) * R * 0.72, CY + Math.sin(a) * R * 0.72 + 12);
    }
  }
  ctx.lineWidth = 1.5;
  ctx.beginPath(); ctx.arc(CX, CY, R * 0.93, ang(0), ang(100)); ctx.stroke();

  font(ctx, FONTS.mono, 14, 600);
  ctx.fillStyle = 'rgba(42,36,29,0.85)';
  drawTracked(ctx, 'EXPEDITION HEAT', CX, CY + 96, 0.32, 'center');
  font(ctx, FONTS.mono, 11, 500);
  ctx.fillStyle = 'rgba(42,36,29,0.6)';
  drawTracked(ctx, 'DISTANT FRONTIERS  ·  MK I', CX, CY + 118, 0.3, 'center');
  ctx.restore();
}

function drawNeedle(ctx, v, t) {
  const a = ang(v);
  const draw = (dx, dy, col) => {
    ctx.save();
    ctx.translate(CX + dx, CY + dy);
    ctx.rotate(a);
    ctx.fillStyle = col;
    ctx.beginPath();
    ctx.moveTo(-R * 0.24, -9);
    ctx.lineTo(-R * 0.24, 9);
    ctx.lineTo(0, 5.5);
    ctx.lineTo(R * 0.88, 1.2);
    ctx.lineTo(R * 0.9, 0);
    ctx.lineTo(R * 0.88, -1.2);
    ctx.lineTo(0, -5.5);
    ctx.closePath();
    ctx.fill();
    ctx.restore();
  };
  ctx.save();
  ctx.filter = 'blur(5px)';
  draw(10, 14, 'rgba(30,18,8,0.35)');
  ctx.restore();
  draw(0, 0, '#1a1513');
  // red tip
  ctx.save();
  ctx.translate(CX, CY);
  ctx.rotate(a);
  ctx.fillStyle = '#8c1f18';
  ctx.beginPath();
  ctx.moveTo(R * 0.7, 2.4); ctx.lineTo(R * 0.9, 0); ctx.lineTo(R * 0.7, -2.4); ctx.closePath(); ctx.fill();
  ctx.restore();
  // cap
  const cap = ctx.createRadialGradient(CX - 8, CY - 10, 2, CX, CY, 30);
  cap.addColorStop(0, '#cfc7bc'); cap.addColorStop(0.35, '#6c655e'); cap.addColorStop(1, '#151311');
  ctx.fillStyle = cap;
  ctx.beginPath(); ctx.arc(CX, CY, 28, 0, Math.PI * 2); ctx.fill();
  ctx.strokeStyle = 'rgba(0,0,0,0.6)';
  ctx.lineWidth = 2.5;
  ctx.beginPath(); ctx.moveTo(CX - 10, CY + 6); ctx.lineTo(CX + 10, CY - 6); ctx.stroke();
}

function drawGlass(ctx, crackK, t) {
  ctx.save();
  ctx.beginPath(); ctx.arc(CX, CY, R, 0, Math.PI * 2); ctx.clip();
  // Curved highlight from the lamp.
  const hg = ctx.createLinearGradient(CX - R, CY - R, CX, CY);
  hg.addColorStop(0, 'rgba(255,250,235,0.22)');
  hg.addColorStop(1, 'rgba(255,250,235,0)');
  ctx.fillStyle = hg;
  ctx.beginPath();
  ctx.arc(CX, CY, R * 0.97, Math.PI * 1.02, Math.PI * 1.62);
  ctx.arc(CX + 40, CY + 50, R * 0.97, Math.PI * 1.55, Math.PI * 1.08, true);
  ctx.closePath();
  ctx.fill();
  // Dust.
  const r = rng(31);
  for (let i = 0; i < 90; i++) {
    const a = r() * Math.PI * 2, d = Math.sqrt(r()) * R;
    ctx.fillStyle = `rgba(255,248,230,${0.05 + r() * 0.1})`;
    ctx.beginPath(); ctx.arc(CX + Math.cos(a) * d, CY + Math.sin(a) * d, 0.6 + r() * 1.4, 0, Math.PI * 2); ctx.fill();
  }
  // Crack: radial fractures from the impact plus concentric links.
  if (crackK > 0) {
    const ix = CX + R * 0.42, iy = CY - R * 0.38;
    const rc = rng(404);
    ctx.strokeStyle = 'rgba(245,240,230,0.75)';
    ctx.shadowColor = 'rgba(255,255,255,0.5)';
    ctx.shadowBlur = 4;
    const rays = [];
    for (let k = 0; k < 13; k++) {
      const a0 = (k / 13) * Math.PI * 2 + rc() * 0.4;
      const len = (120 + rc() * 380) * ease.outExpo(crackK);
      const pts = [[ix, iy]];
      let x = ix, y = iy, a = a0, l = 0;
      while (l < len) {
        const seg = 14 + rc() * 26;
        a += (rc() - 0.5) * 0.5;
        x += Math.cos(a) * seg; y += Math.sin(a) * seg; l += seg;
        pts.push([x, y]);
      }
      rays.push(pts);
      ctx.lineWidth = 1.1 + rc() * 0.8;
      ctx.beginPath();
      pts.forEach(([px, py], j) => (j ? ctx.lineTo(px, py) : ctx.moveTo(px, py)));
      ctx.stroke();
    }
    ctx.lineWidth = 0.8;
    for (let ring = 1; ring <= 3; ring++) {
      ctx.beginPath();
      rays.forEach((pts, k) => {
        const p = pts[Math.min(pts.length - 1, ring * 2)];
        if (k === 0) ctx.moveTo(p[0], p[1]); else ctx.lineTo(p[0] + (rc() - 0.5) * 6, p[1] + (rc() - 0.5) * 6);
      });
      ctx.closePath();
      ctx.globalAlpha = 0.5 * ease.outCubic(clamp(crackK * 2 - ring * 0.3));
      ctx.stroke();
      ctx.globalAlpha = 1;
    }
  }
  ctx.restore();
}

export function drawGauge(t, tl, G, progs, target, ctx) {
  const t0 = 35.0;
  const steps = tl.heatSteps;
  const crashT = steps[steps.length - 1][0];
  const alarm = t >= tl.heatMessages[2].t ? 0.5 + 0.5 * Math.sin((t - tl.heatMessages[2].t) * Math.PI * 2 * 1.6) : 0;
  const alarmAmt = alarm * smooth(tl.heatMessages[2].t, tl.heatMessages[2].t + 0.5, t) * 0.35 + (t >= crashT ? 0.3 : 0);
  const lightsUp = smooth(t0, t0 + 0.9, t);

  G.draw(progs.panel, { uTime: t, uLight: [430, 220], uAlarm: alarmAmt, uBright: lerp(0.15, 1, lightsUp) }, target);

  let v = needleValue(t, steps);
  // Pinned at the stop: the movement buzzes against the peg.
  if (t >= crashT) v = Math.min(v, 101.5) + noise1(t * 70, 9) * 0.9;
  v += fbm1(t * 13, 4) * 0.25 * (v / 100);
  const crackK = clamp((t - crashT) / 0.35);

  ctx.save();
  ctx.globalAlpha = lightsUp;
  drawBezel(ctx);
  drawFace(ctx, v);
  drawNeedle(ctx, Math.min(v, 102), t);
  drawGlass(ctx, crackK, t);
  // Screws in the panel.
  for (const [sx, sy] of [[1830, 120], [1830, 960]]) {
    const sg = ctx.createRadialGradient(sx - 4, sy - 5, 1, sx, sy, 18);
    sg.addColorStop(0, '#8d867d'); sg.addColorStop(1, '#151311');
    ctx.fillStyle = sg;
    ctx.beginPath(); ctx.arc(sx, sy, 17, 0, Math.PI * 2); ctx.fill();
    ctx.strokeStyle = 'rgba(0,0,0,0.7)'; ctx.lineWidth = 3;
    ctx.beginPath(); ctx.moveTo(sx - 9, sy - 3); ctx.lineTo(sx + 9, sy + 3); ctx.stroke();
  }
  ctx.restore();

  // ---- type column
  const shown = clamp(Math.round(v), 0, 100);
  const st = stateOf(clamp(v, 0, 100) >= 99.5 ? 100 : shown);
  const x = 1060;
  const colIn = prog(t, t0 + 0.35, 0.8, ease.outCubic);
  ctx.save();
  ctx.globalAlpha = colIn;
  font(ctx, FONTS.mono, 22, 500);
  ctx.fillStyle = 'rgba(236,228,212,0.75)';
  drawTracked(ctx, 'EXPEDITION HEAT', x, 330, 0.3);
  ctx.fillStyle = 'rgba(236,228,212,0.35)';
  ctx.fillRect(x, 352, 520, 1.5);

  font(ctx, FONTS.figures, 250, 500);
  const isMax = t >= crashT;
  ctx.fillStyle = isMax ? '#ff6a58' : '#f4ecdc';
  ctx.shadowColor = isMax ? 'rgba(255,60,40,0.6)' : 'rgba(0,0,0,0.4)';
  ctx.shadowBlur = isMax ? 40 : 20;
  ctx.textAlign = 'left';
  ctx.fillText(String(shown), x - 12, 610);
  ctx.shadowBlur = 0;

  // State word rolls like a counter when a threshold is crossed.
  let li = 0;
  tl.heatMessages.forEach((m, i) => { if (t >= m.t) li = i + 1; });
  const rollT = li > 0 ? tl.heatMessages[li - 1].t : -10;
  const rk = ease.outCubic(clamp((t - rollT) / 0.35));
  font(ctx, FONTS.display, 84, 600, 'italic');
  ctx.save();
  ctx.beginPath(); ctx.rect(x - 10, 630, 800, 105); ctx.clip();
  const cur = STATES[li], prev = STATES[Math.max(0, li - 1)];
  if (rk < 1 && li > 0) {
    ctx.fillStyle = rgba(prev.col, 1 - rk);
    ctx.fillText(prev.name, x, 712 - 90 * rk);
  }
  ctx.fillStyle = rgba(cur.col, li > 0 ? rk : 1);
  ctx.fillText(cur.name, x, 712 + (li > 0 ? 90 * (1 - rk) : 0));
  ctx.restore();

  // The mod's own warnings, typed as they arrive.
  if (li > 0) {
    const m = tl.heatMessages[li - 1];
    font(ctx, FONTS.journal, 40, 400, 'italic');
    ctx.fillStyle = rgba(cur.col, 0.95);
    ctx.fillText(typed(m.text, t, m.t + 0.05, 48), x, 800);
  }
  font(ctx, FONTS.mono, 16, 500);
  ctx.fillStyle = 'rgba(236,228,212,0.45)';
  drawTracked(ctx, 'BUILDS IN RING 2+  ·  JUMPS ON ELITE KILLS  ·  BANKS AT HOME', x, 880, 0.18);
  ctx.restore();

  const crashHit = t >= crashT ? Math.exp(-(t - crashT) * 9) : 0;
  const stepHit = steps.reduce((acc, [ts]) => acc + (t >= ts ? Math.exp(-(t - ts) * 30) : 0), 0);
  const out = smooth(44.7, 45.0, t);
  return {
    exposure: 1,
    bloom: 0.5,
    bloomThreshold: 0.78,
    vignette: 0.6,
    grain: 0.05,
    ca: 0.0015 + 0.01 * crashHit,
    shake: [noise1(t * 50, 1) * (2.5 * stepHit + 16 * crashHit), noise1(t * 50, 2) * (2.5 * stepHit + 16 * crashHit)],
    flash: [0.5 * crashHit, 0.08 * crashHit, 0.05 * crashHit],
    fade: 1 - out * 0.85,
    gain: [1.03, 1.0, 0.96],
  };
}
