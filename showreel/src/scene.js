/*
 * scene.js — every pixel of the showreel, drawn with Canvas 2D.
 *
 * renderFrame(n) is a PURE function of the frame number: no accumulated state,
 * so frames can be rendered by any worker in any order. Motion that looks like
 * simulation (particles, the needle spring) is analytic or pre-tabulated.
 */
(() => {
  'use strict';
  const S = SHARED;
  const { W, H, FPS, DURATION, FRAMES, SCENES, EV, SR, clamp, lerp, smooth, easeOutCubic, easeOutExpo, easeInOutCubic, mulberry32 } = S;
  const DATA = window.DATA || { snippet: '// (no snippet)', stats: { lines: 0 } };

  const canvas = document.getElementById('c');
  const ctx = canvas.getContext('2d');
  const mk = (w, h) => { const c = document.createElement('canvas'); c.width = w; c.height = h; return c; };
  const layer = mk(W, H), lctx = layer.getContext('2d');
  const TAU = Math.PI * 2;

  const C = { coral: '#ff5a47', teal: '#22d3c5', amber: '#ffb454', cream: '#fff4dc', pink: '#ff3d81', ink: '#07070a' };
  const SERIF = '"DM Serif Display", Georgia, serif';
  const SANS = '"DM Sans", "Helvetica Neue", sans-serif';
  const MONO = '"JetBrains Mono", ui-monospace, monospace';

  // ----------------------------------------------------------- helpers
  function txt(g, s, x, y, o) {
    g.save();
    g.font = o.font;
    g.fillStyle = o.fill || '#fff';
    g.textAlign = o.align || 'left';
    g.textBaseline = o.base || 'alphabetic';
    if (o.ls !== undefined) g.letterSpacing = o.ls + 'px';
    g.globalAlpha *= o.alpha === undefined ? 1 : o.alpha;
    if (o.blur > 0.2) g.filter = 'blur(' + o.blur + 'px)';
    g.fillText(s, x, y);
    g.restore();
  }
  // blur-in / rise-up reveal progress for text starting at t0
  const rev = (tl, t0, dur = 0.8) => easeOutCubic((tl - t0) / dur);
  function revTxt(g, s, x, y, tl, t0, o, dur = 0.8) {
    const p = rev(tl, t0, dur);
    if (p <= 0) return;
    txt(g, s, x, y + (1 - p) * 26, Object.assign({}, o, { alpha: (o.alpha === undefined ? 1 : o.alpha) * p, blur: (1 - p) * 16 }));
  }
  // fixed-width digits so a counting number never jitters
  function tabular(g, str, x, y, font, fill, cell) {
    g.save(); g.font = font; g.fillStyle = fill; g.textBaseline = 'alphabetic'; g.textAlign = 'center';
    let cx = x;
    for (const ch of str) {
      const isDigit = ch >= '0' && ch <= '9';
      const w = isDigit ? cell : g.measureText(ch).width;
      g.fillText(ch, cx + w / 2, y);
      cx += w;
    }
    g.restore();
    return cx - x;
  }
  const rr = (g, x, y, w, h, r) => { g.beginPath(); g.roundRect(x, y, w, h, r); };

  // ------------------------------------------------------ static caches
  const caches = {};
  function noiseTile(size, seed, amp) {
    const c = mk(size, size), g = c.getContext('2d'), im = g.createImageData(size, size), r = mulberry32(seed);
    for (let i = 0; i < size * size; i++) {
      const v = 128 + (r() + r() + r() - 1.5) * amp;
      im.data[i * 4] = im.data[i * 4 + 1] = im.data[i * 4 + 2] = v; im.data[i * 4 + 3] = 255;
    }
    g.putImageData(im, 0, 0);
    return c;
  }

  function buildPanel() {
    // dark brushed-metal panel with scratches and a screw, bigger than the frame so it can drift (parallax)
    const PW = W + 160, PH = H + 120, c = mk(PW, PH), g = c.getContext('2d'), r = mulberry32(11);
    g.fillStyle = '#0c0c0b'; g.fillRect(0, 0, PW, PH);
    for (let i = 0; i < 260; i++) {                         // low-frequency mottling
      const x = r() * PW, y = r() * PH, rad = 60 + r() * 380, light = r() < 0.5;
      const gr = g.createRadialGradient(x, y, 0, x, y, rad);
      gr.addColorStop(0, light ? 'rgba(150,135,105,.045)' : 'rgba(0,0,0,.10)'); gr.addColorStop(1, 'rgba(0,0,0,0)');
      g.fillStyle = gr; g.fillRect(x - rad, y - rad, rad * 2, rad * 2);
    }
    const key = g.createRadialGradient(PW * 0.78, PH * 0.15, 40, PW * 0.78, PH * 0.15, PW * 0.9);   // key light from the upper right
    key.addColorStop(0, 'rgba(210,190,150,.20)'); key.addColorStop(0.5, 'rgba(120,105,80,.06)'); key.addColorStop(1, 'rgba(0,0,0,0)');
    g.fillStyle = key; g.fillRect(0, 0, PW, PH);
    const im = g.getImageData(0, 0, PW, PH), d = im.data;  // fine grain
    for (let i = 0; i < PW * PH; i++) {
      const n = (r() + r() - 1) * 15;
      d[i * 4] += n; d[i * 4 + 1] += n; d[i * 4 + 2] += n * 0.9;
    }
    g.putImageData(im, 0, 0);
    g.lineCap = 'round';
    for (let i = 0; i < 900; i++) {                         // brushed streaks
      const x = r() * PW, y = r() * PH, l = 40 + r() * 420;
      g.strokeStyle = 'rgba(255,245,225,' + (0.012 + r() * 0.03) + ')'; g.lineWidth = 1;
      g.beginPath(); g.moveTo(x, y); g.lineTo(x + l, y + (r() - 0.5) * 3); g.stroke();
    }
    for (let i = 0; i < 160; i++) {                         // scratches
      const x = r() * PW, y = r() * PH, l = 30 + r() * 260, a = r() * TAU;
      g.strokeStyle = 'rgba(255,240,215,' + (0.03 + r() * 0.08) + ')'; g.lineWidth = 0.6 + r() * 0.8;
      g.beginPath(); g.moveTo(x, y); g.lineTo(x + Math.cos(a) * l, y + Math.sin(a) * l); g.stroke();
    }
    for (const [sx, sy] of [[PW - 62, 150], [PW - 62, PH - 150]]) {   // screws on the right edge, like a real panel
      const gr = g.createRadialGradient(sx - 8, sy - 10, 2, sx, sy, 38);
      gr.addColorStop(0, '#6c675e'); gr.addColorStop(0.6, '#2a2825'); gr.addColorStop(1, '#0d0d0c');
      g.fillStyle = gr; g.beginPath(); g.arc(sx, sy, 38, 0, TAU); g.fill();
      g.strokeStyle = 'rgba(0,0,0,.8)'; g.lineWidth = 6; g.beginPath(); g.moveTo(sx - 22, sy - 12); g.lineTo(sx + 22, sy + 12); g.stroke();
      g.strokeStyle = 'rgba(255,255,255,.14)'; g.lineWidth = 1.5; g.beginPath(); g.moveTo(sx - 22, sy - 9); g.lineTo(sx + 22, sy + 15); g.stroke();
    }
    return c;
  }

  const GR = { R: 372, RF: 288, M: 90 };
  function buildGauge() {
    const { R, RF, M } = GR, size = 2 * (R + M), c = mk(size, size), g = c.getContext('2d'), cx = size / 2, cy = size / 2, r = mulberry32(5);
    const arc = (rad) => { g.beginPath(); g.arc(cx, cy, rad, 0, TAU); };
    g.save(); g.shadowColor = 'rgba(0,0,0,.8)'; g.shadowBlur = 80; g.shadowOffsetX = 26; g.shadowOffsetY = 34; g.fillStyle = '#000'; arc(R); g.fill(); g.restore();
    // black bakelite bezel
    let cg = g.createConicGradient(-2.2, cx, cy);
    [[0, '#33312e'], [0.1, '#5e5a54'], [0.2, '#1c1b19'], [0.35, '#0b0b0a'], [0.5, '#2a2825'], [0.62, '#4f4b45'], [0.75, '#161513'], [0.9, '#25231f'], [1, '#33312e']].forEach(([p, col]) => cg.addColorStop(p, col));
    g.fillStyle = cg; arc(R); g.fill();
    let rg = g.createRadialGradient(cx, cy, RF + 10, cx, cy, R);
    rg.addColorStop(0, 'rgba(0,0,0,.65)'); rg.addColorStop(0.5, 'rgba(0,0,0,.05)'); rg.addColorStop(1, 'rgba(255,255,255,.07)');
    g.fillStyle = rg; arc(R); g.fill();
    let lg = g.createLinearGradient(cx - R, cy - R, cx + R, cy + R);
    lg.addColorStop(0, 'rgba(255,255,255,.6)'); lg.addColorStop(0.42, 'rgba(255,255,255,0)'); lg.addColorStop(0.6, 'rgba(0,0,0,0)'); lg.addColorStop(1, 'rgba(255,255,255,.22)');
    g.strokeStyle = lg; g.lineWidth = 3; arc(R - 2); g.stroke();
    for (let i = 0; i < 288; i++) {                          // knurled edge
      const a = (i / 288) * TAU;
      g.strokeStyle = i % 2 ? 'rgba(255,255,255,.075)' : 'rgba(0,0,0,.4)'; g.lineWidth = 2.2;
      g.beginPath(); g.moveTo(cx + Math.cos(a) * (R - 6), cy + Math.sin(a) * (R - 6)); g.lineTo(cx + Math.cos(a) * (R - 22), cy + Math.sin(a) * (R - 22)); g.stroke();
    }
    // brass retaining ring
    const RB = RF + 16;
    cg = g.createConicGradient(0.7, cx, cy);
    [[0, '#7b5c20'], [0.18, '#f0d28a'], [0.3, '#a07f36'], [0.5, '#4d3813'], [0.68, '#d9b45a'], [0.82, '#8a6a27'], [1, '#7b5c20']].forEach(([p, col]) => cg.addColorStop(p, col));
    g.fillStyle = cg; arc(RB); g.fill();
    g.fillStyle = '#0b0a08'; arc(RF + 1); g.fill();
    // paper face, aged
    rg = g.createRadialGradient(cx - 50, cy - 70, 20, cx, cy, RF);
    rg.addColorStop(0, '#fffdf1'); rg.addColorStop(0.55, '#fbf0cb'); rg.addColorStop(0.9, '#e5cf8d'); rg.addColorStop(1, '#bf9f58');
    g.fillStyle = rg; arc(RF); g.fill();
    g.save(); arc(RF); g.clip();
    for (let i = 0; i < 70; i++) {                           // foxing
      const x = cx + (r() - 0.5) * RF * 2, y = cy + (r() - 0.5) * RF * 2, rad = 10 + r() * 55;
      const gr = g.createRadialGradient(x, y, 0, x, y, rad);
      gr.addColorStop(0, 'rgba(140,100,40,' + (0.02 + r() * 0.05) + ')'); gr.addColorStop(1, 'rgba(140,100,40,0)');
      g.fillStyle = gr; g.fillRect(x - rad, y - rad, rad * 2, rad * 2);
    }
    for (let i = 0; i < 2600; i++) { g.fillStyle = 'rgba(70,50,20,' + (r() * 0.1) + ')'; g.fillRect(cx + (r() - 0.5) * RF * 2, cy + (r() - 0.5) * RF * 2, 1.4, 1.4); }
    const sh = g.createRadialGradient(cx, cy, RF * 0.78, cx, cy, RF);   // inner shadow under the bezel lip
    sh.addColorStop(0, 'rgba(60,35,5,0)'); sh.addColorStop(0.8, 'rgba(60,35,5,.22)'); sh.addColorStop(1, 'rgba(25,12,0,.7)');
    g.fillStyle = sh; g.fillRect(0, 0, size, size);
    // scale
    const ang = (v) => (S.GAUGE.a0 + S.GAUGE.sweep * v / S.GAUGE.max) * Math.PI / 180;
    g.strokeStyle = '#2a1d0e'; g.lineCap = 'butt';
    for (let v = 0; v <= 1000; v += 10) {
      const major = v % 100 === 0, mid = v % 50 === 0, a = ang(v), len = major ? 38 : mid ? 27 : 17;
      g.lineWidth = major ? 5.5 : mid ? 3.2 : 1.7;
      const ro = RF - 16, ri = ro - len;
      g.beginPath(); g.moveTo(cx + Math.cos(a) * ro, cy + Math.sin(a) * ro); g.lineTo(cx + Math.cos(a) * ri, cy + Math.sin(a) * ri); g.stroke();
    }
    g.fillStyle = '#2a1d0e'; g.textAlign = 'center'; g.textBaseline = 'middle'; g.font = '500 37px ' + SANS;
    for (let v = 0; v <= 1000; v += 100) { const a = ang(v), rad = RF - 86; g.fillText(String(v), cx + Math.cos(a) * rad, cy + Math.sin(a) * rad); }
    g.letterSpacing = '8px'; g.font = '500 23px ' + SANS; g.fillText('FRAMES', cx + 4, cy + 104);
    g.letterSpacing = '5px'; g.font = '400 17px ' + SANS; g.fillStyle = '#5b4527'; g.fillText('NOT FILMED', cx + 2, cy + 140);
    g.restore();
    return c;
  }

  function buildGrain() { return noiseTile(512, 77, 120); }
  function buildVignette() {
    const c = mk(W, H), g = c.getContext('2d');
    const gr = g.createRadialGradient(W / 2, H / 2, H * 0.58, W / 2, H / 2, H * 1.18);
    gr.addColorStop(0, 'rgba(0,0,0,0)'); gr.addColorStop(1, 'rgba(0,0,0,.52)');
    g.fillStyle = gr; g.fillRect(0, 0, W, H);
    return c;
  }
  const sm1 = mk(480, 270), sm2 = mk(120, 68), s1 = sm1.getContext('2d'), s2 = sm2.getContext('2d');
  function bloom(src, dst, a1, a2) {                         // cheap, wide bloom: 2 downsamples blended back additively
    s1.clearRect(0, 0, 480, 270); s1.drawImage(src, 0, 0, 480, 270);
    s2.clearRect(0, 0, 120, 68); s2.drawImage(sm1, 0, 0, 120, 68);
    dst.save(); dst.globalCompositeOperation = 'lighter'; dst.imageSmoothingQuality = 'high';
    dst.globalAlpha = a1; dst.drawImage(sm1, 0, 0, W, H);
    dst.globalAlpha = a2; dst.drawImage(sm2, 0, 0, W, H);
    dst.restore();
  }

  // ======================================================= SCENE 1 — open
  const dust = (() => {
    const r = mulberry32(31), a = [];
    for (let i = 0; i < 260; i++) a.push({ x: r() * W, y: r() * H, z: 0.3 + r() * 1.1, ph: r() * TAU, sp: 4 + r() * 12, rad: 0.7 + r() * 2.0 });
    return a;
  })();

  function drawOpen(g, tl) {
    g.fillStyle = '#050507'; g.fillRect(0, 0, W, H);
    const bg = g.createRadialGradient(W / 2, H / 2, 0, W / 2, H / 2, 900);
    bg.addColorStop(0, 'rgba(255,90,71,' + (0.10 * smooth(tl / 2)) + ')'); bg.addColorStop(0.5, 'rgba(34,211,197,.035)'); bg.addColorStop(1, 'rgba(0,0,0,0)');
    g.fillStyle = bg; g.fillRect(0, 0, W, H);
    // dust in the projector beam
    g.save(); g.globalCompositeOperation = 'lighter';
    for (const d of dust) {
      const x = (d.x + tl * d.sp * d.z + Math.sin(tl * 0.6 + d.ph) * 18) % W, y = (d.y - tl * d.sp * 0.35 * d.z + H) % H;
      const tw = 0.5 + 0.5 * Math.sin(tl * 2.1 + d.ph * 3);
      g.globalAlpha = 0.28 * tw * d.z * smooth(tl / 1.2); g.fillStyle = d.z > 0.9 ? C.cream : '#9fe9e2';
      g.beginPath(); g.arc(x, y, d.rad * d.z, 0, TAU); g.fill();
    }
    g.restore();
    // the two hairlines framing the title
    const p = easeOutExpo((tl - EV.open.line[0]) / (EV.open.line[1] - EV.open.line[0]));
    for (const y of [262, 818]) {
      const half = 700 * p;
      const lg = g.createLinearGradient(W / 2 - half, 0, W / 2 + half, 0);
      lg.addColorStop(0, 'rgba(255,244,220,0)'); lg.addColorStop(0.5, 'rgba(255,244,220,.9)'); lg.addColorStop(1, 'rgba(255,244,220,0)');
      g.fillStyle = lg; g.fillRect(W / 2 - half, y, half * 2, 1.5);
    }
    revTxt(g, 'A SHOWREEL  ·  ' + FRAMES + ' FRAMES  ·  ZERO CAMERAS', W / 2, 232, tl, 0.9, { font: '400 20px ' + MONO, fill: 'rgba(255,244,220,.7)', align: 'center', ls: 7 }, 1.2);
    const lines = [['No camera.', 440], ['No footage.', 610], ['Just ', 780]];
    lines.forEach(([s, y], i) => {
      const t0 = EV.open.phrases[i], dim = i < 2 ? 1 - 0.62 * smooth((tl - EV.open.phrases[i + 1]) / 0.6) : 1;
      if (i < 2) revTxt(g, s, W / 2, y, tl, t0, { font: '400 156px ' + SERIF, fill: C.cream, align: 'center', alpha: dim }, 1.0);
      else {
        g.save(); g.font = '400 156px ' + SERIF; const w1 = g.measureText('Just ').width; g.font = 'italic 400 156px ' + SERIF; const w2 = g.measureText('code.').width; g.restore();
        const x0 = W / 2 - (w1 + w2) / 2;
        revTxt(g, 'Just ', x0, y, tl, t0, { font: '400 156px ' + SERIF, fill: C.cream }, 1.0);
        revTxt(g, 'code.', x0 + w1, y, tl, t0 + 0.28, { font: 'italic 400 156px ' + SERIF, fill: C.coral }, 1.1);
      }
    });
  }

  // ===================================================== SCENE 2 — gauge
  function drawGauge(g, tl) {
    const panel = caches.panel, face = caches.gauge, { R, RF, M } = GR;
    const push = 1 + 0.055 * smooth(tl / SCENES[1].dur);
    g.save();
    g.translate(900, 560); g.scale(push, push); g.translate(-900, -560);
    const drift = tl * 5;
    g.drawImage(panel, -80 - drift * 0.5, -60 - drift * 0.3);
    const gx = 568, gy = 556, lamp = smooth((tl - EV.gauge.lamp) / 1.0);
    g.drawImage(face, gx - face.width / 2, gy - face.height / 2);
    // needle
    const v = S.needle(tl - 0), a = (S.GAUGE.a0 + S.GAUGE.sweep * v / S.GAUGE.max) * Math.PI / 180;
    const wob = Math.sin(tl * 61) * 0.0012 * clamp(S.needleVel(tl) / 400);
    g.save(); g.translate(gx, gy);
    // red marker for this video's frame count
    const ma = (S.GAUGE.a0 + S.GAUGE.sweep * S.GAUGE.target / S.GAUGE.max) * Math.PI / 180, mp = 0.75 + 0.25 * Math.sin(tl * 5);
    g.save(); g.rotate(ma); g.fillStyle = C.coral; g.globalAlpha = smooth((tl - EV.gauge.redline) / 0.4) * (0.7 + 0.3 * mp);
    g.beginPath(); g.moveTo(RF - 8, 0); g.lineTo(RF - 30, -11); g.lineTo(RF - 30, 11); g.closePath(); g.fill(); g.restore();
    // shadow then needle
    for (const pass of [0, 1]) {
      g.save(); g.rotate(a + wob);
      if (pass === 0) { g.translate(9, 13); g.filter = 'blur(5px)'; g.fillStyle = 'rgba(40,25,5,.42)'; } else {
        const ng = g.createLinearGradient(0, -8, 0, 8); ng.addColorStop(0, '#4a3220'); ng.addColorStop(0.5, '#1b1008'); ng.addColorStop(1, '#0c0703'); g.fillStyle = ng;
      }
      g.beginPath(); g.moveTo(-74, -8); g.lineTo(-74, 8); g.lineTo(-8, 7); g.lineTo(RF - 18, 1.2); g.lineTo(RF - 18, -1.2); g.lineTo(-8, -7); g.closePath(); g.fill();
      if (pass === 1) { g.fillStyle = 'rgba(255,230,190,.35)'; g.fillRect(0, -3, RF - 40, 1.2); }
      g.restore();
    }
    const hub = g.createRadialGradient(-7, -9, 2, 0, 0, 34);
    hub.addColorStop(0, '#fff0b8'); hub.addColorStop(0.35, '#c8a24c'); hub.addColorStop(0.8, '#5a4012'); hub.addColorStop(1, '#2a1c06');
    g.fillStyle = hub; g.beginPath(); g.arc(0, 0, 33, 0, TAU); g.fill();
    g.strokeStyle = 'rgba(0,0,0,.55)'; g.lineWidth = 2; g.stroke();
    g.fillStyle = '#17100a'; g.beginPath(); g.arc(0, 0, 9, 0, TAU); g.fill();
    // glass: diagonal sheen + a soft moving streak
    g.save(); g.beginPath(); g.arc(0, 0, RF, 0, TAU); g.clip();
    const gl = g.createLinearGradient(-RF, -RF, RF * 0.3, RF * 0.6);
    gl.addColorStop(0, 'rgba(255,255,255,.20)'); gl.addColorStop(0.45, 'rgba(255,255,255,.03)'); gl.addColorStop(0.5, 'rgba(255,255,255,0)'); gl.addColorStop(1, 'rgba(255,255,255,0)');
    g.fillStyle = gl; g.fillRect(-RF, -RF, RF * 2, RF * 2);
    const sx = lerp(-RF * 1.4, RF * 1.4, smooth((tl - 3.3) / 2.2));
    g.rotate(-0.6); const sg = g.createLinearGradient(sx - 90, 0, sx + 90, 0);
    sg.addColorStop(0, 'rgba(255,248,230,0)'); sg.addColorStop(0.5, 'rgba(255,248,230,.16)'); sg.addColorStop(1, 'rgba(255,248,230,0)');
    g.fillStyle = sg; g.fillRect(sx - 90, -RF * 1.5, 180, RF * 3);
    g.restore();
    g.restore();
    // power-on: the dial lights up from darkness
    g.fillStyle = 'rgba(0,0,0,' + (0.82 * (1 - lamp)) + ')'; g.beginPath(); g.arc(gx, gy, RF + 40, 0, TAU); g.fill();
    g.fillStyle = 'rgba(0,0,0,' + (0.55 * (1 - lamp)) + ')'; g.fillRect(-80, -60, W + 160, H + 120);
    g.restore();

    // the big number
    const num = Math.round(clamp(v, 0, FRAMES)), x0 = 1010;
    revTxt(g, 'Frames rendered  ·  this video', x0, 336, tl, 0.55, { font: '500 35px ' + SANS, fill: 'rgba(255,244,220,.92)' }, 0.9);
    const nr = rev(tl, 0.7, 0.9);
    if (nr > 0) {
      g.save(); g.globalAlpha = nr;
      g.filter = 'blur(26px)'; g.globalCompositeOperation = 'lighter'; g.globalAlpha = nr * 0.28;
      tabular(g, String(num), x0 - 6, 650, '400 352px ' + SERIF, '#ffd9a6', 182);
      g.filter = 'none'; g.globalCompositeOperation = 'source-over'; g.globalAlpha = nr;
      tabular(g, String(num), x0 - 6, 650, '400 352px ' + SERIF, '#fffaf0', 182);
      g.restore();
    }
    const rows = [[EV.gauge.sub1, 'Frames filmed', '0', C.cream], [EV.gauge.sub2, 'Lines of code written', String(DATA.stats.lines || 0), C.cream]];
    rows.forEach(([t0, label, val], i) => {
      const y = 738 + i * 62;
      revTxt(g, label, x0, y, tl, t0, { font: '500 36px ' + SANS, fill: 'rgba(255,244,220,.78)' }, 0.8);
      g.save(); g.font = '500 36px ' + SANS; const lw = g.measureText(label + '  ').width; g.restore();
      revTxt(g, val, x0 + lw, y, tl, t0 + 0.12, { font: '400 44px ' + SERIF, fill: i === 0 ? C.coral : C.teal }, 0.8);
    });
  }

  // ======================================================= SCENE 3 — knot
  const NP = 5200;
  const KX = 1290, KY = 520, KS = 104, TRAIL = 7;
  const kp = (() => {
    const r = mulberry32(2024), o = { bx: new Float32Array(NP), by: new Float32Array(NP), ph: new Float32Array(NP), u: new Float32Array(NP), ox: new Float32Array(NP), oy: new Float32Array(NP), oz: new Float32Array(NP), col: new Uint8Array(NP), dl: new Float32Array(NP), big: new Uint8Array(NP), sw: new Float32Array(NP) };
    for (let i = 0; i < NP; i++) {
      o.bx[i] = (r() - 0.5) * 2500; o.by[i] = (r() - 0.5) * 1500; o.ph[i] = r() * TAU; o.u[i] = r() * TAU;
      o.ox[i] = (r() + r() + r() - 1.5) * 0.40; o.oy[i] = (r() + r() + r() - 1.5) * 0.40; o.oz[i] = (r() + r() + r() - 1.5) * 0.40;
      const q = r(); o.col[i] = q < 0.34 ? 0 : q < 0.64 ? 1 : q < 0.86 ? 2 : q < 0.93 ? 3 : 4;
      o.dl[i] = r() * 1.1; o.big[i] = r() < 0.2 ? 1 : 0; o.sw[i] = (r() - 0.5) * 2;
    }
    return o;
  })();
  const KCOL = ['#22d3c5', '#ff5a47', '#fff4dc', '#ff3d81', '#ffb454'];
  const XS = [], YS = [];
  for (let k = 0; k < TRAIL; k++) { XS.push(new Float32Array(NP)); YS.push(new Float32Array(NP)); }
  const pl = mk(W, H), plc = pl.getContext('2d');

  function knotRot(tt) { return [0.55 * tt + 0.3, 0.55 + 0.16 * Math.sin(0.5 * tt)]; }
  function project(x, y, z, ry, rx) {
    let x1 = x * Math.cos(ry) + z * Math.sin(ry), z1 = -x * Math.sin(ry) + z * Math.cos(ry);
    let y1 = y * Math.cos(rx) - z1 * Math.sin(rx), z2 = y * Math.sin(rx) + z1 * Math.cos(rx);
    const f = 1500 / (1500 - z2 * KS);
    return [x1 * f * KS, y1 * f * KS, f];
  }
  function particlePos(i, tt, pop, i2) {
    const bx = kp.bx[i], by = kp.by[i], ph = kp.ph[i];
    // chaos phase: a slow vortex around where the knot will form
    const r0 = Math.hypot(bx, by) * 0.82 + 40, th0 = Math.atan2(by, bx);
    const om = 0.30 * (1.2 - Math.min(r0, 1300) / 1500);
    const th = th0 + om * tt + 0.12 * Math.sin(0.9 * tt + ph), rad = r0 * (1 + 0.04 * Math.sin(0.7 * tt + ph));
    const ax = KX - 140 + rad * Math.cos(th), ay = KY + rad * Math.sin(th) * 0.64;
    const u = kp.u[i] + tt * 0.35, [ry, rx] = knotRot(tt);
    const rr_ = 2 + Math.cos(3 * u);
    const [px, py, f] = project(rr_ * Math.cos(2 * u) + kp.ox[i], rr_ * Math.sin(2 * u) + kp.oy[i], Math.sin(3 * u) + kp.oz[i], ry, rx);
    const bxk = KX + px * pop, byk = KY + py * pop;
    const dur = (EV.knot.converge[1] - EV.knot.converge[0]) - 1.15;
    const e = easeInOutCubic((tt - EV.knot.converge[0] - kp.dl[i]) / dur);
    const dx = bxk - ax, dy = byk - ay, sw = Math.sin(Math.PI * e) * 0.28 * kp.sw[i];
    XS[i2][i] = ax + dx * e - dy * sw; YS[i2][i] = ay + dy * e + dx * sw;
    return f;
  }

  function drawKnot(g, tl) {
    const lock = EV.knot.lock, since = tl - lock;
    g.fillStyle = '#05060a'; g.fillRect(0, 0, W, H);
    const pulse = 0.5 + 0.5 * Math.sin(tl * 0.8);
    let bg = g.createRadialGradient(KX, KY, 0, KX, KY, 900);
    bg.addColorStop(0, 'rgba(34,211,197,' + (0.10 + 0.05 * pulse) + ')'); bg.addColorStop(0.5, 'rgba(255,90,71,.04)'); bg.addColorStop(1, 'rgba(0,0,0,0)');
    g.fillStyle = bg; g.fillRect(0, 0, W, H);
    bg = g.createRadialGradient(240, 960, 0, 240, 960, 900);
    bg.addColorStop(0, 'rgba(255,90,71,.10)'); bg.addColorStop(1, 'rgba(0,0,0,0)'); g.fillStyle = bg; g.fillRect(0, 0, W, H);

    // faint perspective floor grid
    g.save(); g.globalAlpha = 0.5 * smooth(tl / 1.5); g.strokeStyle = 'rgba(160,220,215,.07)'; g.lineWidth = 1;
    for (let i = -14; i <= 14; i++) { g.beginPath(); g.moveTo(W / 2 + i * 60, 760); g.lineTo(W / 2 + i * 260, H + 20); g.stroke(); }
    for (let k = 0; k < 7; k++) { const y = 760 + Math.pow(k / 6, 1.8) * 320 + ((tl * 14) % 1); g.beginPath(); g.moveTo(0, y); g.lineTo(W, y); g.stroke(); }
    g.restore();

    const pop = since >= 0 ? 1 + 0.07 * Math.exp(-since * 6) * Math.cos(since * 20) : 1;
    plc.clearRect(0, 0, W, H);
    for (let k = 0; k < TRAIL; k++) { const tt = tl - k * 0.026; for (let i = 0; i < NP; i++) particlePos(i, tt, pop, k); }
    const fade = smooth(tl / 0.9);
    plc.save(); plc.globalCompositeOperation = 'lighter'; plc.lineCap = 'round';
    for (let c = 0; c < 5; c++) {
      for (let k = TRAIL - 2; k >= 0; k--) {
        for (let big = 0; big < 2; big++) {
          plc.beginPath(); let any = false;
          for (let i = 0; i < NP; i++) {
            if (kp.col[i] !== c || kp.big[i] !== big) continue;
            plc.moveTo(XS[k][i], YS[k][i]); plc.lineTo(XS[k + 1][i], YS[k + 1][i]); any = true;
          }
          if (!any) continue;
          plc.strokeStyle = KCOL[c]; plc.globalAlpha = fade * (0.42 * Math.pow(1 - k / (TRAIL - 1), 1.6) + 0.02); plc.lineWidth = (big ? 2.8 : 1.5) * (1 - k * 0.09);
          plc.stroke();
        }
      }
      plc.beginPath();
      for (let i = 0; i < NP; i++) if (kp.col[i] === c) { const s = kp.big[i] ? 3.0 : 1.9; plc.rect(XS[0][i] - s / 2, YS[0][i] - s / 2, s, s); }
      plc.fillStyle = KCOL[c]; plc.globalAlpha = fade * 0.62; plc.fill();
    }
    plc.restore();
    g.drawImage(pl, 0, 0);
    bloom(pl, g, 0.5 * fade, 0.6 * fade);

    // lock-in: shockwaves + flash
    if (since >= 0) {
      g.save(); g.globalCompositeOperation = 'lighter';
      const fl = g.createRadialGradient(KX, KY, 0, KX, KY, 520);
      fl.addColorStop(0, 'rgba(255,244,220,' + (0.55 * Math.exp(-since * 5)) + ')'); fl.addColorStop(1, 'rgba(255,244,220,0)');
      g.fillStyle = fl; g.fillRect(0, 0, W, H);
      [0, 0.14, 0.3].forEach((d, i) => {
        const p = (since - d) / 1.6; if (p <= 0 || p >= 1) return;
        g.strokeStyle = i === 1 ? C.coral : C.cream; g.globalAlpha = 0.55 * (1 - p); g.lineWidth = 2.6 * (1 - p) + 0.6;
        g.beginPath(); g.arc(KX, KY, 1300 * easeOutExpo(p), 0, TAU); g.stroke();
      });
      g.restore();
    }
    // orbit rings
    const ro = smooth((tl - lock - 0.3) / 0.8);
    if (ro > 0) {
      g.save(); g.lineWidth = 1.4;
      [[470, 1.15, 0.2, 0.30, 0], [545, -0.6, 0.9, -0.18, 1]].forEach(([rad, tilt, spin, speed, k]) => {
        g.strokeStyle = k ? 'rgba(255,90,71,.5)' : 'rgba(34,211,197,.55)'; g.globalAlpha = ro; g.setLineDash(k ? [3, 14] : [40, 12]); g.lineDashOffset = -tl * 40 * (k ? -1 : 1);
        g.beginPath();
        for (let j = 0; j <= 160; j++) {
          const a = (j / 160) * TAU + tl * speed, x = Math.cos(a) * rad / KS, z0 = Math.sin(a) * rad / KS;
          const y = z0 * Math.sin(tilt), z = z0 * Math.cos(tilt);
          const [px, py] = project(x, y, z, spin + tl * 0.12, 0.45);
          if (j) g.lineTo(KX + px, KY + py); else g.moveTo(KX + px, KY + py);
        }
        g.stroke();
      });
      g.restore();
    }
    // headline + equation
    const [h0, h1] = EV.knot.head;
    revTxt(g, '5,200 particles.', 130, 430, tl, h0, { font: '400 98px ' + SERIF, fill: C.cream }, 0.9);
    revTxt(g, 'One equation.', 130, 548, tl, h1, { font: 'italic 400 98px ' + SERIF, fill: C.teal }, 0.9);
    const eq = ['x = (2 + cos 3u) · cos 2u', 'y = (2 + cos 3u) · sin 2u', 'z = sin 3u'];
    eq.forEach((s, i) => {
      const n = Math.floor(clamp((tl - (h1 + 0.45 + i * 0.28)) * 55, 0, s.length));
      if (n > 0) txt(g, s.slice(0, n) + (n < s.length ? '▌' : ''), 134, 628 + i * 44, { font: '400 29px ' + MONO, fill: 'rgba(255,244,220,.78)' });
    });
    // callouts
    const tags = [['T(2,3) torus knot', KX + 190, KY - 275, KX + 100, KY - 160], ['7-sample trails', KX + 240, KY + 40, KX + 215, KY + 10], ['0 bytes of footage', KX + 120, KY + 350, KX + 60, KY + 220]];
    tags.forEach(([s, x, y, tx, ty], i) => {
      const p = easeOutCubic((tl - EV.knot.callouts[i]) / 0.6); if (p <= 0) return;
      g.save(); g.strokeStyle = 'rgba(255,244,220,.65)'; g.lineWidth = 1.5; g.globalAlpha = p;
      g.beginPath(); g.moveTo(tx, ty); g.lineTo(lerp(tx, x, p), lerp(ty, y, p)); g.lineTo(lerp(tx, x, p) + 54 * p, lerp(ty, y, p)); g.stroke();
      g.fillStyle = C.coral; g.beginPath(); g.arc(tx, ty, 5, 0, TAU); g.fill(); g.restore();
      revTxt(g, s, x + 66, y + 7, tl, EV.knot.callouts[i] + 0.25, { font: '500 22px ' + MONO, fill: C.cream, ls: 1 }, 0.5);
    });
    // convergence readout
    const conv = clamp((tl - EV.knot.converge[0]) / (EV.knot.converge[1] - EV.knot.converge[0]));
    txt(g, 'converged  ' + String(Math.round(conv * 100)).padStart(3, ' ') + '%', 130, H - 120, { font: '400 22px ' + MONO, fill: 'rgba(255,244,220,.55)', ls: 3, alpha: smooth(tl / 0.8) });
    g.fillStyle = 'rgba(255,244,220,.2)'; g.fillRect(130, H - 100, 360, 2); g.fillStyle = C.teal; g.fillRect(130, H - 100, 360 * conv, 2);
  }

  // ======================================================= SCENE 4 — code
  const KW = new Set(['function', 'const', 'let', 'return', 'for', 'if', 'else', 'new']);
  function tokenize(line) {
    const out = []; let i = 0;
    while (i < line.length) {
      const rest = line.slice(i);
      if (rest.startsWith('//')) { out.push([rest, '#6f7686']); break; }
      let m;
      if ((m = /^[A-Za-z_$][\w$]*/.exec(rest))) {
        const w = m[0], nxt = rest[w.length];
        out.push([w, KW.has(w) ? '#ff7a66' : nxt === '(' || w === 'FRAMES' ? '#ffd28a' : '#ece8de']); i += w.length;
      } else if ((m = /^\d+(\.\d+)?/.exec(rest))) { out.push([m[0], '#5ee6d6']); i += m[0].length; }
      else { out.push([rest[0], '#9aa1b2']); i++; }
    }
    return out;
  }
  const CODE_LINES = String(DATA.snippet).split('\n');
  const TYPE_T = S.makeTyping(String(DATA.snippet), EV.code.type[0], EV.code.type[1], 7);

  function blobs(g, tl, strength) {
    g.save(); g.globalCompositeOperation = 'lighter';
    [[0.18, 0.3, C.coral, 700, 0.0], [0.82, 0.25, C.teal, 640, 1.7], [0.6, 0.95, C.pink, 560, 3.1], [0.05, 0.9, C.amber, 480, 4.4]].forEach(([fx, fy, col, rad, ph]) => {
      const x = W * fx + Math.sin(tl * 0.35 + ph) * 90, y = H * fy + Math.cos(tl * 0.3 + ph) * 70;
      const gr = g.createRadialGradient(x, y, 0, x, y, rad);
      gr.addColorStop(0, col + 'ff'); gr.addColorStop(1, col + '00');
      g.globalAlpha = strength; g.fillStyle = gr; g.fillRect(x - rad, y - rad, rad * 2, rad * 2);
    });
    g.restore();
  }

  function drawCode(g, tl) {
    g.fillStyle = '#08080c'; g.fillRect(0, 0, W, H);
    blobs(g, tl, 0.22);
    const push = 1 + 0.05 * smooth(tl / SCENES[3].dur), rot = (tl / SCENES[3].dur - 0.5) * 0.004;
    g.save(); g.translate(W / 2, 470); g.rotate(rot); g.scale(push, push); g.translate(-W / 2, -470);
    const wp = easeOutCubic((tl - EV.code.win) / 0.9), wx = 190, wy = 104, ww = 1540, wh = 696;
    g.save(); g.globalAlpha = wp; g.translate(0, (1 - wp) * 40);
    g.shadowColor = 'rgba(0,0,0,.65)'; g.shadowBlur = 90; g.shadowOffsetY = 40;
    rr(g, wx, wy, ww, wh, 22); g.fillStyle = 'rgba(14,14,20,.90)'; g.fill(); g.shadowColor = 'transparent';
    g.strokeStyle = 'rgba(255,255,255,.11)'; g.lineWidth = 1.5; g.stroke();
    g.fillStyle = 'rgba(255,255,255,.035)'; g.beginPath(); g.roundRect(wx, wy, ww, 62, [22, 22, 0, 0]); g.fill();
    [C.coral, C.amber, C.teal].forEach((c, i) => { g.fillStyle = c; g.globalAlpha = wp * 0.85; g.beginPath(); g.arc(wx + 38 + i * 30, wy + 31, 8.5, 0, TAU); g.fill(); });
    g.globalAlpha = wp;
    txt(g, 'showreel/src/shared.js', wx + ww / 2, wy + 39, { font: '400 22px ' + MONO, fill: 'rgba(255,244,220,.55)', align: 'center' });
    // code
    const fs = 28, lh = 37, cw = 16.8, cx0 = wx + 118, cy0 = wy + 62 + 46, n = S.typedCount(TYPE_T, tl);
    g.font = '400 ' + fs + 'px ' + MONO;
    let idx = 0, caretX = cx0, caretY = cy0, curLine = 0;
    CODE_LINES.forEach((line, li) => {
      const y = cy0 + li * lh, shown = clamp(n - idx, 0, line.length), started = n > idx || (n === idx && li === 0 && n > 0);
      if (n >= idx) {
        txt(g, String(li + 1).padStart(2, ' '), cx0 - 52, y, { font: '400 22px ' + MONO, fill: 'rgba(255,255,255,.22)' });
      }
      if (shown > 0 || n >= idx) {
        let col = 0;
        for (const [s, color] of tokenize(line)) {
          if (col >= shown) break;
          const part = s.slice(0, shown - col);
          txt(g, part, cx0 + col * cw, y, { font: '400 ' + fs + 'px ' + MONO, fill: color });
          col += s.length;
        }
      }
      if (n >= idx && n <= idx + line.length) { caretX = cx0 + shown * cw; caretY = y; curLine = li; }
      idx += line.length + 1;
    });
    if (n < String(DATA.snippet).length || Math.floor(tl * 2.4) % 2 === 0) {
      g.fillStyle = C.cream; g.globalAlpha = wp * 0.9; g.fillRect(caretX + 1, caretY - fs + 4, 11, fs + 6);
      g.globalAlpha = wp * 0.045; g.fillStyle = '#fff'; g.fillRect(wx + 8, caretY - fs + 2, ww - 16, lh);
    }
    g.restore();
    // terminal
    const tp = easeOutCubic((tl - EV.code.enter + 0.1) / 0.6);
    if (tp > 0) {
      const ty = 826, th = 178;
      g.save(); g.globalAlpha = tp; g.translate(0, (1 - tp) * 30);
      g.shadowColor = 'rgba(0,0,0,.6)'; g.shadowBlur = 60; g.shadowOffsetY = 24;
      rr(g, wx, ty, ww, th, 18); g.fillStyle = 'rgba(8,8,12,.94)'; g.fill(); g.shadowColor = 'transparent';
      g.strokeStyle = 'rgba(255,255,255,.1)'; g.lineWidth = 1.5; g.stroke();
      const cmd = '$ node showreel/render.js', ncmd = Math.floor(clamp((tl - EV.code.enter) * 40, 0, cmd.length));
      txt(g, cmd.slice(0, ncmd), wx + 36, ty + 46, { font: '400 25px ' + MONO, fill: C.cream });
      const run = clamp((tl - EV.code.run[0]) / (EV.code.run[1] - EV.code.run[0]));
      if (tl > EV.code.run[0] - 0.05) {
        const fr = Math.round(run * FRAMES), bars = 26, filled = Math.round(run * bars);
        txt(g, 'frames  ' + '█'.repeat(filled) + '░'.repeat(bars - filled) + '  ' + String(fr).padStart(3, ' ') + ' / ' + FRAMES, wx + 36, ty + 84, { font: '400 25px ' + MONO, fill: C.teal });
      }
      if (run >= 1) {
        revTxt(g, 'audio   ' + (DURATION).toFixed(1) + ' s · 44.1 kHz stereo · synthesized  ✓', wx + 36, ty + 120, tl, EV.code.run[1] + 0.02, { font: '400 25px ' + MONO, fill: C.amber }, 0.3);
        revTxt(g, 'mux     showreel.mp4  ✓', wx + 36, ty + 154, tl, EV.code.done, { font: '400 25px ' + MONO, fill: C.coral }, 0.3);
      }
      g.restore();
    }
    g.restore();
  }

  // ===================================================== SCENE 5 — finale
  const STATS = [
    { label: 'FRAMES', v: FRAMES, fmt: (n) => Math.round(n).toLocaleString('en-US'), col: C.cream },
    { label: 'PIXELS COMPOSED', v: FRAMES * W * H / 1e9, fmt: (n) => n.toFixed(2) + 'B', col: C.cream },
    { label: 'AUDIO SAMPLES SYNTHESIZED', v: Math.round(DURATION * SR * 2) / 1e6, fmt: (n) => n.toFixed(2) + 'M', col: C.cream },
    { label: 'CAMERAS  ·  MICROPHONES', v: 0, fmt: () => '0', col: C.coral },
  ];
  function drawFinale(g, tl) {
    g.fillStyle = '#07070b'; g.fillRect(0, 0, W, H);
    blobs(g, tl + 3, 0.26 * smooth(tl / 1.5));
    g.fillStyle = 'rgba(5,5,9,.45)'; g.fillRect(0, 0, W, H);
    txt(g, 'THIS FILM, BY THE NUMBERS', W / 2, 205, { font: '400 22px ' + MONO, fill: 'rgba(255,244,220,.6)', align: 'center', ls: 8, alpha: smooth(tl / 0.6) });
    const centers = [-690, -230, 230, 690];
    STATS.forEach((s, i) => {
      const x = W / 2 + centers[i];
      let p, t0;
      if (i < 3) { const [a, b] = EV.finale.counts[i]; t0 = a; p = easeOutExpo((tl - a) / (b - a)); }
      else { t0 = EV.finale.zero; p = tl >= t0 ? 1 : 0; }
      if (tl < t0 - 0.05) return;
      const val = s.fmt(s.v * (i < 3 ? p : 1)), pop = i === 3 ? 1 + 0.18 * Math.exp(-(tl - t0) * 7) * Math.cos((tl - t0) * 18) : 1;
      g.save(); g.translate(x, 400); g.scale(pop, pop);
      g.globalAlpha = smooth((tl - t0 + 0.05) / 0.25);
      g.font = '400 128px ' + SERIF; g.textAlign = 'center'; g.fillStyle = s.col; g.textBaseline = 'alphabetic';
      if (i === 3) { g.shadowColor = 'rgba(255,90,71,.6)'; g.shadowBlur = 40; }
      g.fillText(val, 0, 0); g.restore();
      txt(g, s.label, x, 462, { font: '400 19px ' + MONO, fill: 'rgba(255,244,220,.62)', align: 'center', ls: 3, alpha: smooth((tl - t0) / 0.4) });
    });
    // title
    const tp = EV.finale.title, k = rev(tl, tp, 1.4);
    if (k > 0) {
      g.save(); g.font = '400 176px ' + SERIF; const w1 = g.measureText('Code in. ').width; g.font = 'italic 400 176px ' + SERIF; const w2 = g.measureText('Cinema out.').width; g.restore();
      const x0 = W / 2 - (w1 + w2) / 2, y = 720;
      revTxt(g, 'Code in. ', x0, y, tl, tp, { font: '400 176px ' + SERIF, fill: C.cream }, 1.2);
      g.save(); const gr = g.createLinearGradient(x0 + w1, 0, x0 + w1 + w2, 0); gr.addColorStop(0, C.coral); gr.addColorStop(0.55, C.pink); gr.addColorStop(1, C.amber);
      revTxt(g, 'Cinema out.', x0 + w1, y, tl, tp + 0.4, { font: 'italic 400 176px ' + SERIF, fill: gr }, 1.3); g.restore();
    }
    revTxt(g, 'Every pixel and every sound, generated from code.', W / 2, 828, tl, EV.finale.credit - 0.15, { font: '400 27px ' + MONO, fill: 'rgba(255,244,220,.72)', align: 'center', ls: 2 }, 0.9);
    revTxt(g, 'MADE WITH CLAUDE CODE', W / 2, 900, tl, EV.finale.credit + 0.25, { font: '500 20px ' + MONO, fill: C.teal, align: 'center', ls: 9 }, 0.9);
    const fo = smooth((tl - EV.finale.fade[0]) / (EV.finale.fade[1] - EV.finale.fade[0]));
    g.fillStyle = 'rgba(0,0,0,' + fo + ')'; g.fillRect(0, 0, W, H);
  }

  // ========================================================= compositor
  const drawers = { open: drawOpen, gauge: drawGauge, knot: drawKnot, code: drawCode, finale: drawFinale };

  function renderFrame(f) {
    const t = f / FPS, HALF = 0.25;
    ctx.setTransform(1, 0, 0, 1, 0, 0); ctx.globalCompositeOperation = 'source-over'; ctx.globalAlpha = 1;
    ctx.fillStyle = '#000'; ctx.fillRect(0, 0, W, H);
    SCENES.forEach((sc, i) => {
      if (t < sc.start - HALF || t > sc.end + HALF) return;
      const a = i === 0 ? smooth(t / 0.7) : smooth((t - (sc.start - HALF)) / (2 * HALF));
      if (a <= 0.002) return;
      lctx.save(); lctx.setTransform(1, 0, 0, 1, 0, 0); lctx.globalAlpha = 1; lctx.globalCompositeOperation = 'source-over'; lctx.clearRect(0, 0, W, H);
      drawers[sc.id](lctx, Math.max(0, t - sc.start));
      lctx.restore();
      let z = 1;
      if (i > 0 && t < sc.start + HALF) z = lerp(0.94, 1, smooth((t - (sc.start - HALF)) / (2 * HALF)));
      if (i < SCENES.length - 1 && t > sc.end - HALF) z = lerp(1, 1.07, smooth((t - (sc.end - HALF)) / (2 * HALF)));
      ctx.save(); ctx.globalAlpha = a; ctx.translate(W / 2, H / 2); ctx.scale(z, z); ctx.translate(-W / 2, -H / 2); ctx.drawImage(layer, 0, 0); ctx.restore();
    });
    // flash on every cut: the sound design hits at the same instant
    let fl = 0; for (const b of S.CUTS) { const d = t - b; if (d >= 0) fl += 0.42 * Math.exp(-d * 17); }
    if (fl > 0.004) {
      const a = Math.min(fl, 0.75), gr = ctx.createRadialGradient(W / 2, H / 2, 0, W / 2, H / 2, W * 0.72);
      gr.addColorStop(0, 'rgba(255,236,200,' + a + ')'); gr.addColorStop(1, 'rgba(255,236,200,' + a * 0.2 + ')');
      ctx.save(); ctx.globalCompositeOperation = 'lighter'; ctx.fillStyle = gr; ctx.fillRect(0, 0, W, H); ctx.restore();
    }
    ctx.drawImage(caches.vignette, 0, 0);
    // film grain
    const r = mulberry32(f * 977 + 13);
    ctx.save(); ctx.globalCompositeOperation = 'overlay'; ctx.globalAlpha = 0.20;
    const ox = -Math.floor(r() * 512), oy = -Math.floor(r() * 512);
    for (let x = ox; x < W; x += 512) for (let y = oy; y < H; y += 512) ctx.drawImage(caches.grain, x, y);
    ctx.restore();
    // HUD: frame-accurate timecode, because every frame is a function of its number
    const endFade = 1 - smooth((t - (DURATION - 1.2)) / 0.8);
    ctx.save(); ctx.globalAlpha = 0.55 * endFade * smooth(t / 1.2);
    txt(ctx, S.timecode(t), W - 64, 62, { font: '400 20px ' + MONO, fill: C.cream, align: 'right', ls: 2 });
    txt(ctx, 'CODE  →  CINEMA  ·  1080p  30 FPS', 64, 62, { font: '400 16px ' + MONO, fill: C.cream, ls: 6 });
    ctx.fillStyle = 'rgba(255,244,220,.18)'; ctx.fillRect(64, H - 40, W - 128, 2);
    ctx.fillStyle = C.coral; ctx.fillRect(64, H - 40, (W - 128) * clamp(t / DURATION), 2);
    SCENES.forEach((sc) => { ctx.fillStyle = C.cream; ctx.fillRect(64 + (W - 128) * sc.start / DURATION - 1, H - 46, 2, 14); });
    ctx.restore();
  }

  async function init() {
    await Promise.all([
      '400 20px "DM Serif Display"', 'italic 400 20px "DM Serif Display"', '400 20px "DM Sans"', '500 20px "DM Sans"', '700 20px "DM Sans"', '400 20px "JetBrains Mono"', '700 20px "JetBrains Mono"',
    ].map((f) => document.fonts.load(f, 'Aa0')));
    caches.panel = buildPanel(); caches.gauge = buildGauge(); caches.grain = buildGrain(); caches.vignette = buildVignette();
    return true;
  }

  window.renderFrame = renderFrame;
  window.ready = init();
  window.TOTAL_FRAMES = FRAMES;

  if (/[?&]live/.test(location.search)) {
    document.body.classList.add('live');
    window.ready.then(() => { const t0 = performance.now(); (function loop() { renderFrame(Math.floor(((performance.now() - t0) / 1000 * FPS)) % FRAMES); requestAnimationFrame(loop); })(); });
  }
})();
