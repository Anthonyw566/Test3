// "Make it home." Back at the hearth, the expedition banks: field Marks
// times the Heat bonus, with the mod's own ledger message.
import {
  W, H, clamp, lerp, smooth, ease, prog, envelope, rng, fbm1,
  FONTS, font, drawTracked, drawReveal, rgba,
} from '../lib.js';

const CX = W / 2;

function bokeh(ctx, t) {
  const r = rng(2024);
  ctx.save();
  ctx.globalCompositeOperation = 'lighter';
  for (let i = 0; i < 34; i++) {
    const x = r() * W + Math.sin(t * 0.3 + i) * 20;
    const y = ((r() * H - t * (10 + r() * 30)) % H + H) % H;
    const rad = 20 + r() * 70;
    const a = 0.03 + r() * 0.07;
    const g = ctx.createRadialGradient(x, y, rad * 0.75, x, y, rad);
    g.addColorStop(0, rgba([255, 170, 90], a));
    g.addColorStop(1, rgba([255, 140, 60], 0));
    ctx.fillStyle = g;
    ctx.beginPath(); ctx.arc(x, y, rad, 0, Math.PI * 2); ctx.fill();
  }
  ctx.restore();
}

function ledgerRow(ctx, t, t0, y, num, label, color = '#f4ecdc') {
  const k = prog(t, t0, 0.5, ease.outCubic);
  if (k <= 0) return;
  ctx.save();
  ctx.globalAlpha *= k;
  font(ctx, FONTS.figures, 66, 500);
  ctx.fillStyle = color;
  ctx.textAlign = 'right';
  ctx.fillText(num, 1010 - 0, y + 14 * (1 - k));
  font(ctx, FONTS.mono, 22, 500);
  ctx.fillStyle = 'rgba(236,228,212,0.7)';
  ctx.textAlign = 'left';
  drawTracked(ctx, label, 1050, y - 6 + 14 * (1 - k), 0.22);
  ctx.restore();
}

export function drawBank(t, tl, G, progs, target, ctx) {
  const c = tl.cues;
  const b = tl.bank;
  const warm = smooth(c.bankHome - 0.05, c.bankHome + 0.8, t);
  G.draw(progs.hearth, {
    uTime: t,
    uCenter: [0, 0],
    uZoom: 1,
    uMap: 0,
    uFire: warm * 1.1,
    uFireScale: 420,
    uFirePx: [CX, 1210],
    uFlicker: 0.5 + 0.5 * fbm1(t * 7, 3),
    uInk: [0, 0, 0, 0],
    uInk5: 0,
    uRadii: [1, 2, 3, 4],
    uMapDim: 0,
    uTint: [1, 0.95, 0.9],
  }, target);

  bokeh(ctx, t);
  ctx.save();
  ctx.globalAlpha = 1 - smooth(57.2, 57.5, t);

  font(ctx, FONTS.display, 104, 600, 'italic');
  ctx.shadowColor = 'rgba(0,0,0,0.6)';
  ctx.shadowBlur = 30;
  drawReveal(ctx, 'Make it home.', CX, 330, prog(t, c.bankHome, 1.0), { align: 'center', stagger: 0.55, blur: 12, rise: 14, color: '#f8eedc' });
  ctx.shadowBlur = 0;

  const [l1, l2, l3] = c.bankLines;
  ledgerRow(ctx, t, l1, 500, `◈ ${b.field}`, 'FIELD MARKS');
  ledgerRow(ctx, t, l2, 590, `× ${b.multiplier.toFixed(2)}`, `HEAT BONUS  ·  HEAT ${b.heat}`, '#f0a531');
  // Rule draws, then the total counts up with a coin tick per step.
  const rk = prog(t, l3 - 0.1, 0.4, ease.outExpo);
  ctx.fillStyle = 'rgba(236,228,212,0.45)';
  ctx.fillRect(CX - 360, 632, 720 * rk, 1.5);
  if (t >= l3) {
    const ck = ease.outCubic(clamp((t - l3) / (c.bankCountEnd - l3)));
    const val = Math.round(lerp(b.field, b.banked, ck));
    ledgerRow(ctx, t, l3, 712, `◈ ${val}`, 'BANKED AT THE HEARTH', ck >= 1 ? '#8be0da' : '#f4ecdc');
  }
  // The mod's own chat line once the count lands.
  const mk = prog(t, c.bankCountEnd, 0.4);
  if (mk > 0) {
    ctx.globalAlpha *= mk;
    font(ctx, FONTS.mono, 24, 500);
    ctx.textAlign = 'left';
    const parts = [['Expedition banked: ', 'rgba(236,228,212,0.85)'], [`◈ ${b.banked}`, '#8be0da'], [` (+${b.banked - b.field} heat bonus)`, '#f0a531']];
    let total = 0;
    const ws = parts.map(([s]) => { const w = ctx.measureText(s).width; total += w; return w; });
    let x = CX - total / 2;
    parts.forEach(([s, col], i) => { ctx.fillStyle = col; ctx.fillText(s, x, 820); x += ws[i]; });
    font(ctx, FONTS.mono, 16, 500);
    ctx.fillStyle = 'rgba(236,228,212,0.4)';
    drawTracked(ctx, 'FIELD MARKS BANK ONLY AT HOME  ·  DIE OUT THERE AND HALF ARE LOST', CX, 868, 0.22, 'center');
  }
  ctx.restore();

  const stamp = t >= c.bankCountEnd ? Math.exp(-(t - c.bankCountEnd) * 8) : 0;
  return {
    exposure: 1,
    bloom: 0.8,
    bloomThreshold: 0.6,
    vignette: 0.55,
    grain: 0.05,
    flash: [0.18 * stamp, 0.14 * stamp, 0.1 * stamp],
    fade: smooth(c.bankHome - 0.02, c.bankHome + 0.4, t),
    gain: [1.04, 1.0, 0.95],
  };
}
