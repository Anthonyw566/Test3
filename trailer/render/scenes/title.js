// Title card. The survey map returns, dimmed, behind a ring emblem; the
// name lands on the braam and the credits settle under it.
import {
  W, H, clamp, lerp, smooth, ease, prog, envelope, rng, noise1, fbm1,
  FONTS, font, drawTracked, drawReveal, layoutGlyphs, rgba, hexToRgb,
} from '../lib.js';

const CX = W / 2, CY = 470;

function emblem(ctx, t, tl, k) {
  const radii = [54, 112, 176, 248, 330];
  ctx.save();
  ctx.translate(CX, CY);
  radii.forEach((r, i) => {
    const col = hexToRgb(tl.rings[i].color);
    const rk = ease.outCubic(clamp((k - i * 0.08) / 0.6));
    if (rk <= 0) return;
    ctx.strokeStyle = rgba(col, 0.32 * rk);
    ctx.lineWidth = 1.2;
    ctx.beginPath();
    ctx.arc(0, 0, r * lerp(0.85, 1, rk), -Math.PI / 2, -Math.PI / 2 + Math.PI * 2 * rk);
    ctx.stroke();
    // survey ticks that drift slowly, alternating direction per ring
    const n = 24 + i * 12;
    const rot = t * 0.04 * (i % 2 ? -1 : 1);
    ctx.strokeStyle = rgba(col, 0.18 * rk);
    for (let j = 0; j < n; j++) {
      const a = rot + (j / n) * Math.PI * 2;
      const len = j % 6 === 0 ? 9 : 4;
      ctx.beginPath();
      ctx.moveTo(Math.cos(a) * r, Math.sin(a) * r);
      ctx.lineTo(Math.cos(a) * (r + len), Math.sin(a) * (r + len));
      ctx.stroke();
    }
  });
  ctx.restore();
}

function embers(ctx, t) {
  const r = rng(808);
  ctx.save();
  ctx.globalCompositeOperation = 'lighter';
  for (let i = 0; i < 90; i++) {
    const x0 = r() * W, sp = 30 + r() * 80, sway = r() * 6.28, life = 4 + r() * 4;
    const ph = r() * life;
    const age = ((t + ph) % life) / life;
    const x = x0 + Math.sin(t * 0.8 + sway) * 30;
    const y = H + 20 - age * (H * 0.9) * (sp / 80);
    const a = Math.sin(age * Math.PI) * (0.4 + 0.6 * r());
    const s = 1 + r() * 2.2;
    const g = ctx.createRadialGradient(x, y, 0, x, y, s * 4);
    g.addColorStop(0, rgba([255, 170, 90], a));
    g.addColorStop(1, rgba([255, 90, 30], 0));
    ctx.fillStyle = g;
    ctx.fillRect(x - s * 4, y - s * 4, s * 8, s * 8);
  }
  ctx.restore();
}

export function drawTitle(t, tl, G, progs, target, ctx) {
  const c = tl.cues;
  const t0 = c.titleHit;
  const k = prog(t, t0, 2.2);
  G.draw(progs.hearth, {
    uTime: t,
    uCenter: [0, -70 + (t - t0) * 2],
    uZoom: lerp(0.95, 1.1, (t - t0) / 7.5),
    uMap: 1,
    uFire: 0.9,
    uFireScale: 11,
    uFirePx: [CX, CY + 4],
    uFlicker: 0.5 + 0.5 * fbm1(t * 7, 3),
    uInk: [1, 1, 1, 1],
    uInk5: 1,
    uRadii: [40, 120, 280, 560],
    uMapDim: 0.32,
    uTint: [1, 1, 1],
  }, target);

  embers(ctx, t);
  emblem(ctx, t, tl, k);

  // Title: tracking collapses from wide to set while glyphs sharpen.
  const tk = prog(t, t0 + 0.05, 1.8);
  font(ctx, FONTS.display, 132, 500);
  const tracking = lerp(0.62, 0.2, ease.outExpo(tk));
  const grad = ctx.createLinearGradient(0, CY - 80, 0, CY + 20);
  grad.addColorStop(0, '#fff7e6');
  grad.addColorStop(1, '#e2c99a');
  ctx.save();
  ctx.shadowColor = 'rgba(255,170,80,0.35)';
  ctx.shadowBlur = 50;
  drawReveal(ctx, 'DISTANT FRONTIERS', CX, CY + 40, tk, { align: 'center', tracking, stagger: 0.45, blur: 18, rise: 0, color: grad });
  ctx.restore();

  const sub = prog(t, c.tagline, 1.2);
  font(ctx, FONTS.journal, 46, 400, 'italic');
  drawReveal(ctx, 'Home stays safe. The frontier does not.', CX, CY + 132, sub, { align: 'center', stagger: 0.6, blur: 8, rise: 8, color: 'rgba(240,228,206,0.92)' });

  ctx.save();
  ctx.globalAlpha = prog(t, c.credits, 0.8);
  font(ctx, FONTS.mono, 19, 500);
  ctx.fillStyle = 'rgba(236,228,212,0.75)';
  drawTracked(ctx, 'A SERVER-SIDE MOD FOR ALL THE MODS 10  ·  NEOFORGE 1.21.1', CX, 800, 0.3, 'center');
  ctx.globalAlpha = prog(t, c.credits + 0.6, 0.8);
  font(ctx, FONTS.mono, 16, 500);
  ctx.fillStyle = 'rgba(236,228,212,0.5)';
  drawTracked(ctx, 'PLAYERS INSTALL NOTHING  ·  /rings board', CX, 840, 0.3, 'center');
  ctx.globalAlpha = prog(t, c.codeLine, 0.9);
  font(ctx, FONTS.journal, 28, 400, 'italic');
  ctx.fillStyle = 'rgba(236,228,212,0.55)';
  ctx.textAlign = 'center';
  ctx.fillText('Every frame and every sound in this film was generated from code.', CX, 975);
  ctx.restore();

  const hit = Math.exp(-(t - t0) * 6);
  return {
    exposure: 1,
    bloom: 0.85,
    bloomThreshold: 0.62,
    vignette: 0.6,
    grain: 0.05,
    flash: [1.0 * hit, 0.8 * hit, 0.55 * hit],
    shake: [noise1(t * 50, 1) * 14 * hit, noise1(t * 50, 2) * 14 * hit],
    ca: 0.0015 + 0.012 * hit,
    fade: 1 - smooth(c.fadeOut, tl.duration - 0.1, t),
    gain: [1.03, 1.0, 0.96],
  };
}
