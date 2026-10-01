#!/usr/bin/env node
/*
 * render.js — turns src/scene.html into frames, then muxes them with the synthesized soundtrack.
 *
 *   node render.js --list=0,150,400 --png --dir=out/test      # a few stills for quick iteration
 *   node render.js --frames=0-974 --dir=$WORK/frames          # every frame, N parallel workers
 *   node render.js --encode --dir=$WORK/frames --audio=$WORK/soundtrack.wav --out=out/showreel.mp4
 *
 * Needs: node, playwright (+ its chromium), ffmpeg.
 */
const fs = require('fs');
const path = require('path');
const { execFileSync, spawnSync } = require('child_process');

const args = Object.fromEntries(process.argv.slice(2).map((a) => { const m = /^--([^=]+)(?:=(.*))?$/.exec(a); return [m[1], m[2] === undefined ? true : m[2]]; }));
const ROOT = __dirname;
const S = require('./src/shared.js');

function loadPlaywright() {
  try { return require('playwright'); } catch (e) { /* fall through to the global install */ }
  const g = execFileSync('npm', ['root', '-g']).toString().trim();
  return require(path.join(g, 'playwright'));
}

function countLines() {
  const files = [];
  const walk = (d) => fs.readdirSync(d, { withFileTypes: true }).forEach((e) => {
    const p = path.join(d, e.name);
    if (e.isDirectory()) { if (['fonts', 'out', 'stills', '.work', 'node_modules'].includes(e.name)) return; walk(p); }
    else if (/\.(js|html)$/.test(e.name)) files.push(p);
  });
  walk(ROOT);
  return files.reduce((n, f) => n + fs.readFileSync(f, 'utf8').split('\n').length, 0);
}

function snippet() {
  const src = fs.readFileSync(path.join(ROOT, 'src/shared.js'), 'utf8');
  const m = /\/\/ >>> snippet\n([\s\S]*?)\n\s*\/\/ <<</.exec(src);
  const lines = m[1].split('\n'), indent = Math.min(...lines.filter((l) => l.trim()).map((l) => l.match(/^ */)[0].length));
  return lines.map((l) => l.slice(indent)).join('\n');
}

function parseFrames() {
  if (args.list) return String(args.list).split(',').map(Number);
  const [a, b] = String(args.frames || `0-${S.FRAMES - 1}`).split('-').map(Number);
  const out = []; for (let f = a; f <= (b === undefined ? a : b); f++) out.push(f);
  return out;
}

async function renderFrames() {
  const { chromium } = loadPlaywright();
  const frames = parseFrames();
  const dir = path.resolve(args.dir || path.join(ROOT, '.work/frames'));
  fs.mkdirSync(dir, { recursive: true });
  const png = !!args.png, workers = Number(args.workers || 4);
  const data = { snippet: snippet(), stats: { lines: countLines() } };
  const browser = await chromium.launch({ args: ['--disable-gpu', '--force-color-profile=srgb', '--hide-scrollbars'] });
  const queue = frames.slice(), t0 = Date.now(); let done = 0;
  const url = 'file://' + path.join(ROOT, 'src/scene.html');
  async function worker(id) {
    const ctx = await browser.newContext({ viewport: { width: S.W, height: S.H }, deviceScaleFactor: 1 });
    const page = await ctx.newPage();
    page.on('pageerror', (e) => { console.error('[page error]', e.message); });
    await page.addInitScript((d) => { window.DATA = d; }, data);
    await page.goto(url);
    await page.evaluate(() => window.ready);
    while (queue.length) {
      const f = queue.shift();
      await page.evaluate((n) => window.renderFrame(n), f);
      const file = path.join(dir, `${String(f).padStart(5, '0')}.${png ? 'png' : 'jpg'}`);
      await page.screenshot(png ? { path: file, type: 'png' } : { path: file, type: 'jpeg', quality: 93 });
      done++;
      if (done % 25 === 0 || done === frames.length) {
        const el = (Date.now() - t0) / 1000, eta = el / done * (frames.length - done);
        process.stdout.write(`\r  ${done}/${frames.length} frames  ${(done / el).toFixed(2)} fps  eta ${eta.toFixed(0)}s   `);
      }
    }
    await ctx.close();
  }
  await Promise.all(Array.from({ length: workers }, (_, i) => worker(i)));
  await browser.close();
  console.log(`\n  rendered ${frames.length} frames in ${((Date.now() - t0) / 1000).toFixed(1)} s -> ${dir}`);
}

function encode() {
  const dir = path.resolve(args.dir || path.join(ROOT, '.work/frames'));
  const out = path.resolve(args.out || path.join(ROOT, 'out/showreel.mp4'));
  fs.mkdirSync(path.dirname(out), { recursive: true });
  const png = fs.existsSync(path.join(dir, '00000.png'));
  const ff = ['-y', '-hide_banner', '-loglevel', 'error', '-stats', '-framerate', String(S.FPS), '-i', path.join(dir, png ? '%05d.png' : '%05d.jpg')];
  if (args.audio) ff.push('-i', path.resolve(args.audio));
  ff.push('-vf', png ? 'scale=out_range=tv:out_color_matrix=bt709:flags=accurate_rnd+full_chroma_int,format=yuv420p' : 'scale=in_range=full:in_color_matrix=bt601:out_range=tv:out_color_matrix=bt709,format=yuv420p',
    // high quality by default; the cap keeps the file under GitHub's 100 MB limit despite animated film grain
    '-c:v', 'libx264', '-preset', 'slow', '-crf', String(args.crf || 15), '-maxrate', String(args.maxrate || '24M'), '-bufsize', '48M', '-profile:v', 'high', '-colorspace', 'bt709', '-color_primaries', 'bt709', '-color_trc', 'bt709');
  if (args.audio) ff.push('-c:a', 'aac', '-b:a', '320k', '-shortest');
  ff.push('-movflags', '+faststart', out);
  const r = spawnSync('ffmpeg', ff, { stdio: 'inherit' });
  if (r.status !== 0) process.exit(r.status || 1);
  console.log('  wrote', out, (fs.statSync(out).size / 1048576).toFixed(1) + ' MB');
}

(async () => {
  if (args.encode) encode(); else await renderFrames();
})();
