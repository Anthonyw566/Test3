// Champions: generated names (NameGen + the first modifier's epithet, exactly
// as the mod builds them), each revealed with its modifier's own effect.
// Then the Hunter catches your scent.
import {
  W, H, clamp, lerp, smooth, ease, prog, envelope, rng, noise1, fbm1,
  FONTS, font, drawTracked, drawReveal, drawDecoded, rgba,
} from '../lib.js';

const FX = {
  vile: { tint: [0.05, 0.10, 0.03], under: [0.14, 0.32, 0.07], col: [150, 214, 104] },
  unseen: { tint: [0.05, 0.04, 0.10], under: [0.24, 0.15, 0.46], col: [182, 160, 255] },
  sunder: { tint: [0.09, 0.06, 0.04], under: [0.40, 0.22, 0.09], col: [228, 168, 98] },
  warp: { tint: [0.08, 0.03, 0.10], under: [0.38, 0.12, 0.52], col: [206, 128, 255] },
  hunter: { tint: [0.11, 0.015, 0.015], under: [0.45, 0.04, 0.03], col: [232, 64, 52] },
};
const CX = W / 2;

function particlesBurst(ctx, t, t0, x, y, col, n = 60, seed = 1, spread = 220, inward = false) {
  const k = (t - t0) / 0.7;
  if (k < 0 || k > 1) return;
  const r = rng(seed);
  ctx.save();
  ctx.globalCompositeOperation = 'lighter';
  for (let i = 0; i < n; i++) {
    const a = r() * Math.PI * 2;
    const d = (0.2 + r() * 0.8) * spread;
    const e = ease.outCubic(k);
    const dist = inward ? d * (1 - e) : d * e;
    const px = x + Math.cos(a) * dist * 1.6, py = y + Math.sin(a) * dist * 0.7 - (inward ? 0 : 40 * e);
    const s = (1.5 + r() * 2.5) * (1 - k * 0.6);
    const al = (1 - k) * (0.6 + 0.4 * r());
    const g = ctx.createRadialGradient(px, py, 0, px, py, s * 4);
    g.addColorStop(0, rgba(col, al));
    g.addColorStop(1, rgba(col, 0));
    ctx.fillStyle = g;
    ctx.fillRect(px - s * 4, py - s * 4, s * 8, s * 8);
  }
  ctx.restore();
}

function drips(ctx, t, t0, col) {
  const r = rng(77);
  ctx.save();
  ctx.globalCompositeOperation = 'lighter';
  for (let i = 0; i < 26; i++) {
    const x = CX - 330 + r() * 660;
    const start = t0 + 0.25 + r() * 0.6;
    const k = t - start;
    if (k < 0) continue;
    const y = 640 + 0.5 * 900 * k * k;
    const a = clamp(1 - k * 1.1) * 0.8;
    const len = 6 + 30 * clamp(k * 3);
    const g = ctx.createLinearGradient(x, y - len, x, y);
    g.addColorStop(0, rgba(col, 0));
    g.addColorStop(1, rgba(col, a));
    ctx.fillStyle = g;
    ctx.fillRect(x - 1.5, y - len, 3, len);
  }
  ctx.restore();
}

function cracks(ctx, t, t0) {
  const k = ease.outExpo(clamp((t - t0) / 0.3));
  if (k <= 0) return;
  const r = rng(9);
  ctx.save();
  for (let i = 0; i < 9; i++) {
    let x = CX + (r() - 0.5) * 500, y = 650;
    let a = Math.PI / 2 + (r() - 0.5) * 2.2;
    const len = (250 + r() * 500) * k;
    ctx.beginPath();
    ctx.moveTo(x, y);
    let l = 0;
    while (l < len) {
      const s = 10 + r() * 30;
      a += (r() - 0.5) * 0.7;
      x += Math.cos(a) * s; y += Math.sin(a) * s; l += s;
      ctx.lineTo(x, y);
    }
    ctx.strokeStyle = 'rgba(0,0,0,0.85)';
    ctx.lineWidth = 3.5;
    ctx.stroke();
    ctx.strokeStyle = 'rgba(255,190,120,0.55)';
    ctx.lineWidth = 1;
    ctx.stroke();
  }
  // Falling debris.
  for (let i = 0; i < 40; i++) {
    const x0 = CX + (r() - 0.5) * 700, vx = (r() - 0.5) * 200;
    const tt = t - t0 - r() * 0.1;
    if (tt < 0) continue;
    const x = x0 + vx * tt, y = 640 - 200 * tt * r() + 0.5 * 1600 * tt * tt;
    const s = 2 + r() * 7;
    ctx.save();
    ctx.translate(x, y);
    ctx.rotate(tt * 8 * (r() - 0.5));
    ctx.fillStyle = `rgba(${120 + r() * 60},${90 + r() * 40},${70 + r() * 30},${clamp(1.2 - tt)})`;
    ctx.fillRect(-s / 2, -s / 2, s, s * 0.7);
    ctx.restore();
  }
  ctx.restore();
}

function champion(ctx, t, c, i, next) {
  const t0 = c.t, t1 = next;
  if (t < t0 || t >= t1) return null;
  const fx = FX[c.fx];
  const local = t - t0;
  let ox = 0, oy = 0, scale = 1;
  let visible = true;
  if (c.fx === 'unseen') {
    // Blinkstep: appears left, vanishes, reappears centred.
    const blinkAt = 0.55;
    ox = local < blinkAt ? -300 : 0;
    visible = !(local > blinkAt - 0.04 && local < blinkAt + 0.07);
  }
  if (c.fx === 'sunder') {
    scale = 1 + 0.25 * (1 - ease.outExpo(clamp(local / 0.18)));
  }
  const out = 1 - smooth(t1 - 0.12, t1, t);
  ctx.save();
  ctx.globalAlpha = out;

  font(ctx, FONTS.mono, 20, 500);
  ctx.fillStyle = rgba(fx.col, 0.85 * prog(t, t0, 0.2));
  drawTracked(ctx, `CHAMPION  ·  ${c.mods.map((m) => m.toUpperCase()).join('  +  ')}`, CX, 420, 0.32, 'center');

  if (visible) {
    ctx.save();
    ctx.translate(CX + ox, 600 + oy);
    ctx.scale(scale, scale);
    font(ctx, FONTS.display, 190, 600);
    ctx.shadowColor = rgba(fx.col, 0.35);
    ctx.shadowBlur = 40;
    ctx.fillStyle = '#f5ecdc';
    drawDecoded(ctx, c.name, 0, 0, prog(t, t0, 0.38), 11 + i, t, { tracking: 0.02, align: 'center' });
    ctx.shadowBlur = 0;
    font(ctx, FONTS.journal, 84, 400, 'italic');
    drawReveal(ctx, c.epithet, 0, 100, prog(t, t0 + 0.18, 0.45), { align: 'center', stagger: 0.5, blur: 8, rise: 10, color: rgba(fx.col, 1) });
    ctx.restore();
  }
  ctx.restore();

  if (c.fx === 'vile') drips(ctx, t, t0, fx.col);
  if (c.fx === 'unseen') {
    particlesBurst(ctx, t, t0 + 0.51, CX - 300, 560, fx.col, 70, 3, 160);
    particlesBurst(ctx, t, t0 + 0.55, CX, 560, fx.col, 70, 4, 160, true);
  }
  if (c.fx === 'sunder') cracks(ctx, t, t0);
  if (c.fx === 'warp') particlesBurst(ctx, t, t0, CX, 560, fx.col, 120, 8, 420, true);
  return { fx, local };
}

export function drawChampions(t, tl, G, progs, target, ctx) {
  const ch = tl.champions;
  const hunterT = tl.hunter.t;
  let active = null;
  ch.forEach((c, i) => {
    const r = champion(ctx, t, c, i, i + 1 < ch.length ? ch[i + 1].t : hunterT);
    if (r) active = { ...r, c };
  });

  let fx = active ? active.fx : FX.hunter;
  let swirl = 0, warpAmt = 0, shake = [0, 0], ca = 0.002, flash = [0, 0, 0];
  if (active) {
    const l = active.local;
    const hit = Math.exp(-l * 10);
    flash = fx.under.map((v) => v * 0.5 * hit);
    if (active.c.fx === 'warp') {
      const w = Math.sin(clamp(l / 1.25) * Math.PI);
      swirl = 2.2 * w;
      warpAmt = w;
    }
    if (active.c.fx === 'sunder') {
      const s = Math.exp(-l * 5) * 22;
      shake = [noise1(t * 60, 1) * s, noise1(t * 60, 2) * s];
    }
    if (active.c.fx === 'unseen') {
      const b = Math.exp(-Math.pow((l - 0.55) / 0.05, 2));
      ca = 0.002 + 0.03 * b;
    }
  } else if (t >= hunterT) {
    // The Hunter.
    const l = t - hunterT;
    const beat = (k) => Math.exp(-Math.pow(((l - k) % 0.625) / 0.05, 2));
    const pulse = l > 0.2 ? beat(0) : 0;
    font(ctx, FONTS.mono, 20, 500);
    ctx.save();
    ctx.globalAlpha = prog(t, hunterT, 0.3) * (1 - smooth(52.2, 52.5, t));
    ctx.fillStyle = 'rgba(232,90,72,0.8)';
    drawTracked(ctx, 'HEAT 100  ·  MARKED  ·  A HUNTER HAS BEEN SENT', CX, 440, 0.3, 'center');
    const s = 1 + 0.012 * pulse;
    ctx.translate(CX, 590);
    ctx.scale(s, s);
    font(ctx, FONTS.display, 150, 700);
    const name = tl.hunter.name;
    const nk = prog(t, hunterT + 0.1, 0.5, ease.outCubic);
    ctx.fillStyle = '#e8402f';
    ctx.shadowColor = 'rgba(255,40,20,0.55)';
    ctx.shadowBlur = 50;
    drawReveal(ctx, name, 0, 0, nk, { align: 'center', stagger: 0.4, blur: 16, rise: 0, color: '#e8402f' });
    ctx.shadowBlur = 0;
    font(ctx, FONTS.journal, 70, 400, 'italic');
    drawReveal(ctx, 'has caught your scent.', 0, 100, prog(t, hunterT + 0.55, 0.8), { align: 'center', stagger: 0.6, blur: 8, rise: 8, color: '#f0e2d0' });
    ctx.setTransform(1, 0, 0, 1, 0, 0);
    font(ctx, FONTS.mono, 16, 500);
    ctx.fillStyle = 'rgba(240,226,208,0.45)';
    drawTracked(ctx, 'HUNTERS ALWAYS CARRY SIEGER  —  WALLS ARE A DELAY, NOT A DEFENSE', CX, 820, 0.22, 'center');
    ctx.restore();
    flash = [0.25 * pulse, 0.02 * pulse, 0.0];
  }

  G.draw(progs.smoke, {
    uTime: t,
    uTint: fx.tint,
    uUnder: fx.under,
    uDensity: 1.0,
    uSwirl: swirl,
  }, target);

  return {
    exposure: 1.05,
    bloom: 0.75,
    bloomThreshold: 0.7,
    vignette: 0.7,
    grain: 0.06,
    ca,
    shake,
    flash,
    warp: [0.5, 0.48, swirl * 0.35, -0.25 * warpAmt],
    fade: 1 - smooth(52.25, 52.5, t) * 0.9,
  };
}
