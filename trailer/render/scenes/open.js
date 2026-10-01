// Cold open: a hearth fire in the dark ("Home stays safe."), then the camera
// pulls away until the fire is a single point at the centre of a survey map
// and the five rings ink themselves in, one per beat. Then we dive in.
import {
  W, H, clamp, lerp, smooth, ease, prog, envelope, rng, fbm1, noise1,
  FONTS, font, drawTracked, drawReveal, rgba, hexToRgb,
} from '../lib.js';

const RADII = [40, 120, 280, 560]; // map units = blocks / 10
const CX = W / 2, CY = H / 2;

function logLerp(a, b, k) { return Math.exp(lerp(Math.log(a), Math.log(b), k)); }

function mapCamera(t, tl) {
  const c = tl.cues;
  // Zoom out in two moves (pull-up, then settle while rings ink), then dive.
  let zoom;
  if (t < c.frontierNot) zoom = 40;
  else if (t < c.mapRings[0]) zoom = logLerp(40, 2.4, ease.outCubic(prog(t, c.frontierNot, c.mapRings[0] - c.frontierNot)));
  else if (t < c.mapDive) zoom = logLerp(2.4, 0.86, ease.inOutSine(prog(t, c.mapRings[0], c.mapDive - c.mapRings[0])));
  else zoom = logLerp(0.86, 60, ease.inExpo(prog(t, c.mapDive, 10 - c.mapDive)));
  return { zoom, center: [0, 0] };
}

function sparks(ctx, t, fx, fy, scale, amount) {
  if (amount <= 0.01) return;
  const r = rng(99);
  ctx.save();
  ctx.globalCompositeOperation = 'lighter';
  for (let i = 0; i < 70; i++) {
    const life = 1.2 + r() * 1.6;
    const phase = r() * life;
    const age = ((t + phase) % life) / life;
    const x0 = (r() - 0.5) * 0.5;
    const vx = (r() - 0.5) * 0.5;
    const sway = r() * 6.28;
    const h = 1.2 + r() * 1.4;
    const x = fx + (x0 + vx * age + Math.sin(age * 6 + sway) * 0.08) * scale;
    const y = fy - (0.2 + age * h) * scale;
    const a = (1 - age) * (0.5 + 0.5 * Math.sin(t * 20 + i)) * amount;
    const rad = (1.2 + r() * 1.8) * (scale / 300);
    const g = ctx.createRadialGradient(x, y, 0, x, y, rad * 4);
    g.addColorStop(0, rgba([255, 200, 120], a));
    g.addColorStop(0.3, rgba([255, 120, 40], a * 0.6));
    g.addColorStop(1, rgba([255, 80, 20], 0));
    ctx.fillStyle = g;
    ctx.fillRect(x - rad * 4, y - rad * 4, rad * 8, rad * 8);
  }
  ctx.restore();
}

function drawRings(ctx, t, tl, zoom) {
  const times = tl.cues.mapRings;
  const base = ctx.globalAlpha;
  ctx.save();
  // Graticule: faint survey grid, scaled with the map.
  const fadeIn = smooth(tl.cues.frontierNot + 0.4, times[0] + 0.5, t);
  ctx.strokeStyle = `rgba(200,190,170,${0.07 * fadeIn})`;
  ctx.lineWidth = 1;
  const step = 100 * zoom;
  if (step > 12) {
    for (let k = -30; k <= 30; k++) {
      const x = CX + k * step, y = CY + k * step;
      if (x > -2 && x < W + 2) { ctx.beginPath(); ctx.moveTo(x, 0); ctx.lineTo(x, H); ctx.stroke(); }
      if (y > -2 && y < H + 2) { ctx.beginPath(); ctx.moveTo(0, y); ctx.lineTo(W, y); ctx.stroke(); }
    }
  }
  // Ring boundaries draw themselves clockwise from north.
  RADII.forEach((r, i) => {
    const k = ease.inOutCubic(prog(t, times[i], 0.55));
    if (k <= 0) return;
    const col = hexToRgb(tl.rings[i + 1].color);
    ctx.strokeStyle = rgba(col, 0.9);
    ctx.lineWidth = 1.6;
    ctx.shadowColor = rgba(col, 0.9);
    ctx.shadowBlur = 12;
    ctx.beginPath();
    ctx.arc(CX, CY, r * zoom, -Math.PI / 2, -Math.PI / 2 + Math.PI * 2 * k);
    ctx.stroke();
    // a bright pen-tip where the line is being drawn
    if (k < 1) {
      const a = -Math.PI / 2 + Math.PI * 2 * k;
      const px = CX + Math.cos(a) * r * zoom, py = CY + Math.sin(a) * r * zoom;
      const g = ctx.createRadialGradient(px, py, 0, px, py, 16);
      g.addColorStop(0, 'rgba(255,245,220,0.95)');
      g.addColorStop(1, 'rgba(255,245,220,0)');
      ctx.fillStyle = g;
      ctx.fillRect(px - 16, py - 16, 32, 32);
    }
  });
  ctx.shadowBlur = 0;

  // Callouts: leader lines from each ring to a legend column on the right.
  tl.rings.forEach((ring, i) => {
    const ti = times[i];
    const k = prog(t, ti + 0.15, 0.6, ease.outCubic);
    if (k <= 0) return;
    const col = hexToRgb(ring.color);
    const ang = (-62 + i * 21) * Math.PI / 180;
    const rr = i === 0 ? RADII[0] * 0.55 * zoom : i < 4 ? ((RADII[i - 1] + RADII[i]) / 2) * zoom : RADII[3] * 1.12 * zoom;
    const px = CX + Math.cos(ang) * rr, py = CY + Math.sin(ang) * rr;
    const lx = 1430, ly = 250 + i * 128;
    ctx.globalAlpha = base * k;
    ctx.strokeStyle = rgba(col, 0.55);
    ctx.lineWidth = 1;
    ctx.beginPath();
    ctx.moveTo(px, py);
    const ex = lerp(px, lx - 24, k);
    ctx.lineTo(ex, lerp(py, ly - 9, k));
    ctx.stroke();
    ctx.fillStyle = rgba(col, 1);
    ctx.beginPath(); ctx.arc(px, py, 3, 0, Math.PI * 2); ctx.fill();
    font(ctx, FONTS.display, 40, 600);
    drawReveal(ctx, ring.name, lx, ly, prog(t, ti + 0.2, 0.7), { stagger: 0.5, rise: 8, blur: 8, color: '#efe6d4' });
    font(ctx, FONTS.mono, 15, 500);
    ctx.fillStyle = rgba(col, 0.9 * k);
    const range = ring.outer < 0 ? `${ring.inner.toLocaleString('en-US')}+ BLOCKS` : `${ring.inner.toLocaleString('en-US')}–${ring.outer.toLocaleString('en-US')} BLOCKS`;
    drawTracked(ctx, `${'☠'.repeat(ring.danger) || 'SAFE'}  ·  ${range}`, lx, ly + 30, 0.18);
    ctx.globalAlpha = base;
  });

  // Survey title, compass and scale bar.
  const ta = smooth(times[0], times[0] + 0.8, t);
  ctx.globalAlpha = base * ta;
  font(ctx, FONTS.mono, 15, 500);
  ctx.fillStyle = 'rgba(220,210,190,0.6)';
  drawTracked(ctx, 'SURVEY  ·  OVERWORLD  ·  ORIGIN 0, 0', 150, 170, 0.3);
  font(ctx, FONTS.display, 44, 500, 'italic');
  ctx.fillStyle = '#efe6d4';
  ctx.fillText('The Rings of the Frontier', 148, 222);
  // compass
  ctx.save();
  ctx.translate(190, 880);
  ctx.strokeStyle = 'rgba(220,210,190,0.5)';
  ctx.beginPath(); ctx.arc(0, 0, 34, 0, Math.PI * 2); ctx.stroke();
  ctx.fillStyle = 'rgba(239,230,212,0.9)';
  ctx.beginPath(); ctx.moveTo(0, -46); ctx.lineTo(7, 0); ctx.lineTo(0, 8); ctx.lineTo(-7, 0); ctx.closePath(); ctx.fill();
  ctx.fillStyle = 'rgba(239,230,212,0.35)';
  ctx.beginPath(); ctx.moveTo(0, 46); ctx.lineTo(7, 0); ctx.lineTo(-7, 0); ctx.closePath(); ctx.fill();
  font(ctx, FONTS.mono, 13, 600);
  ctx.fillStyle = 'rgba(239,230,212,0.8)';
  ctx.textAlign = 'center';
  ctx.fillText('N', 0, -54);
  ctx.restore();
  // scale bar (1,000 blocks)
  const bar = 100 * zoom;
  if (bar < 600) {
    ctx.fillStyle = 'rgba(220,210,190,0.7)';
    ctx.fillRect(1430, 905, bar, 2);
    ctx.fillRect(1430, 899, 1.5, 14);
    ctx.fillRect(1430 + bar, 899, 1.5, 14);
    font(ctx, FONTS.mono, 13, 500);
    drawTracked(ctx, '1,000 BLOCKS', 1430, 935, 0.25);
  }
  ctx.restore();
}

export function drawOpen(t, tl, G, progs, target, ctx) {
  const c = tl.cues;
  const cam = mapCamera(t, tl);

  // Fire: big and close, then a beacon at the map's centre.
  const fireIn = smooth(c.emberIn, c.emberIn + 1.6, t);
  const pull = ease.inOutCubic(prog(t, c.frontierNot, 1.5));
  const fireScale = logLerp(300, 9, pull);
  const firePx = [CX, lerp(720, CY + 4, pull)];
  const flicker = 0.5 + 0.5 * fbm1(t * 7, 3);
  const mapVis = smooth(c.frontierNot + 0.2, c.mapRings[0] + 0.3, t);
  const ink = c.mapRings.map((ti) => smooth(ti, ti + 0.7, t));

  G.draw(progs.hearth, {
    uTime: t,
    uCenter: cam.center,
    uZoom: cam.zoom,
    uMap: mapVis,
    uFire: fireIn * lerp(1, 1.8, pull),
    uFireScale: fireScale,
    uFirePx: firePx,
    uFlicker: flicker,
    uInk: ink.slice(0, 4),
    uInk5: ink[4],
    uRadii: RADII,
    uMapDim: 1,
    uTint: [1, 1, 1],
  }, target);

  // ---- overlay
  sparks(ctx, t, firePx[0], firePx[1], fireScale, fireIn * (1 - pull));

  // "Home stays safe."
  const homeA = envelope(t, c.homeSafe, c.frontierNot - 0.3, 0.01, 0.6);
  if (homeA > 0) {
    ctx.save();
    ctx.globalAlpha = homeA;
    font(ctx, FONTS.journal, 66, 400, 'italic');
    ctx.shadowColor = 'rgba(0,0,0,0.8)';
    ctx.shadowBlur = 20;
    drawReveal(ctx, 'Home stays safe.', CX, 900, prog(t, c.homeSafe, 1.6), { align: 'center', stagger: 0.6, rise: 10, blur: 10, color: '#f3e7cf' });
    ctx.restore();
  }
  // "The frontier does not." lands on the downbeat, hard.
  const notA = envelope(t, c.frontierNot, c.mapRings[1] + 0.2, 0.01, 0.6);
  if (notA > 0) {
    const k = prog(t, c.frontierNot, 0.5, ease.outExpo);
    ctx.save();
    ctx.globalAlpha = notA;
    font(ctx, FONTS.display, 84, 600);
    ctx.shadowColor = 'rgba(0,0,0,0.85)';
    ctx.shadowBlur = 24;
    ctx.fillStyle = '#f6eddc';
    drawTracked(ctx, 'The frontier does not.', CX, 900 - 10 * (1 - k), lerp(0.12, 0.01, k), 'center');
    ctx.restore();
  }
  if (t >= c.mapRings[0] - 0.2) {
    ctx.save();
    ctx.globalAlpha = 1 - smooth(c.mapDive, c.mapDive + 0.35, t);
    drawRings(ctx, t, tl, cam.zoom);
    ctx.restore();
  }

  const hit = Math.exp(-Math.pow((t - c.frontierNot) / 0.08, 2)) * (t >= c.frontierNot - 0.02 ? 1 : 0);
  const dive = ease.inExpo(prog(t, c.mapDive + 0.25, 10 - c.mapDive - 0.25));
  const fade = smooth(0, c.emberIn + 0.5, t);
  return {
    exposure: 1,
    bloom: 0.9,
    bloomThreshold: 0.55,
    flash: [1.0 * dive * 1.3 + hit * 0.25, 0.85 * dive * 1.3 + hit * 0.2, 0.6 * dive * 1.3 + hit * 0.15],
    shake: hit > 0.01 ? [noise1(t * 60, 3) * 10 * hit, noise1(t * 60, 4) * 10 * hit] : [0, 0],
    ca: 0.002 + 0.01 * dive,
    vignette: 0.55,
    grain: 0.05,
    fade,
    warp: [0.5, 0.5, 0.35 * dive, 0.6 * dive],
  };
}
