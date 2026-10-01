#!/usr/bin/env node
// Frame-accurate renderer: drives render/index.html in headless Chromium,
// one frame at a time, and pipes the frames into ffmpeg.
//
//   node render.mjs                     full film → out/video.mp4 (no audio)
//   node render.mjs --still 23.4 31.0   PNG stills at those times → out/stills/
//   node render.mjs --from 10 --to 15   render a time range only
//   node render.mjs --workers 3         parallel browser workers (default: 3)
//   node render.mjs --scale 0.75        terrain render scale (default: 0.75, or 1 with --gpu)
//   node render.mjs --gpu               use your graphics card (opens a browser window while rendering)
import { chromium } from 'playwright';
import { spawn } from 'node:child_process';
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.dirname(fileURLToPath(import.meta.url));
const OUT = path.join(ROOT, 'out');
const args = process.argv.slice(2);
const opt = (name, def) => {
  const i = args.indexOf(`--${name}`);
  return i >= 0 ? args[i + 1] : def;
};
const stills = (() => {
  const i = args.indexOf('--still');
  if (i < 0) return null;
  const ts = [];
  for (let j = i + 1; j < args.length && !args[j].startsWith('--'); j++) ts.push(parseFloat(args[j]));
  return ts;
})();
// --gpu: hardware WebGL. Without it, Chromium renders on the CPU with
// SwiftShader, which works anywhere (including GPU-less cloud machines) but
// is ~50-100x slower on the terrain shader.
const GPU = args.includes('--gpu');
const WORKERS = parseInt(opt('workers', '3'), 10);
const SCALE = parseFloat(opt('scale', GPU ? '1' : '0.75'));

const timeline = JSON.parse(fs.readFileSync(path.join(ROOT, 'timeline.json'), 'utf8'));
const FPS = timeline.fps;
const TOTAL = Math.round(timeline.duration * FPS);

// ---- tiny static server (fonts and ES modules need http, not file://)
const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.mjs': 'text/javascript', '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff' };
const server = http.createServer((req, res) => {
  if (req.url === '/favicon.ico') {
    res.writeHead(204);
    res.end();
    return;
  }
  const p = path.join(ROOT, decodeURIComponent(new URL(req.url, 'http://x').pathname));
  if (!p.startsWith(ROOT) || !fs.existsSync(p) || fs.statSync(p).isDirectory()) {
    res.writeHead(404);
    res.end();
    return;
  }
  res.writeHead(200, { 'Content-Type': MIME[path.extname(p)] ?? 'application/octet-stream' });
  fs.createReadStream(p).pipe(res);
});
await new Promise((r) => server.listen(0, '127.0.0.1', r));
const URL_ = `http://127.0.0.1:${server.address().port}/render/index.html`;

const CHROME_ARGS = GPU
  ? [
    // A headed window is the most reliable way to get the real GPU on
    // Windows, macOS and Linux desktops; keep it rendering when covered.
    '--ignore-gpu-blocklist', '--enable-gpu-rasterization', '--disable-gpu-vsync', '--font-render-hinting=none',
    '--disable-renderer-backgrounding', '--disable-backgrounding-occluded-windows', '--disable-background-timer-throttling',
  ]
  : [
    // Software 2D canvas: on SwiftShader the "accelerated" canvas is ~4x slower.
    '--use-angle=swiftshader', '--enable-unsafe-swiftshader', '--ignore-gpu-blocklist', '--disable-gpu-vsync',
    '--font-render-hinting=none', '--disable-accelerated-2d-canvas',
  ];
let announced = false;

async function openStage() {
  const browser = await chromium.launch({ headless: !GPU, args: CHROME_ARGS });
  const page = await browser.newPage({ viewport: { width: 1920, height: 1080 }, deviceScaleFactor: 1 });
  page.on('console', (m) => { if (m.type() === 'error') console.error('[page]', m.text()); });
  page.on('pageerror', (e) => console.error('[page error]', e.message));
  await page.goto(URL_);
  await page.waitForFunction(() => typeof window.setup === 'function');
  const info = await page.evaluate((s) => window.setup({ terrainScale: s }), SCALE);
  if (!announced) {
    announced = true;
    console.log(`WebGL renderer: ${info.renderer}`);
    if (GPU && /swiftshader|llvmpipe|software/i.test(info.renderer)) {
      console.warn('warning: --gpu was requested but Chromium fell back to software rendering (check GPU drivers).');
    }
  }
  return { browser, page, info };
}

async function grab(page, frame) {
  await page.evaluate((f) => window.renderFrame(f), frame);
  return page.screenshot({ type: 'png', clip: { x: 0, y: 0, width: 1920, height: 1080 } });
}

function encoder(file) {
  const ff = spawn('ffmpeg', [
    '-y', '-loglevel', 'error',
    '-f', 'image2pipe', '-framerate', String(FPS), '-c:v', 'png', '-i', '-',
    '-vf', 'scale=out_color_matrix=bt709:out_range=tv,format=yuv420p',
    '-c:v', 'libx264', '-preset', 'medium', '-crf', '16', '-tune', 'film',
    '-colorspace', 'bt709', '-color_primaries', 'bt709', '-color_trc', 'bt709',
    '-movflags', '+faststart', file,
  ], { stdio: ['pipe', 'inherit', 'inherit'] });
  const done = new Promise((res, rej) => ff.on('close', (c) => (c === 0 ? res() : rej(new Error(`ffmpeg exited ${c}`)))));
  return { ff, done };
}

fs.mkdirSync(OUT, { recursive: true });

if (stills) {
  const dir = path.join(OUT, 'stills');
  fs.mkdirSync(dir, { recursive: true });
  const { browser, page } = await openStage();
  for (const t of stills) {
    const frame = Math.round(t * FPS);
    const t0 = Date.now();
    const png = await grab(page, frame);
    const file = path.join(dir, `t${t.toFixed(2).padStart(6, '0')}.png`);
    fs.writeFileSync(file, png);
    console.log(`${file}  (${Date.now() - t0} ms)`);
  }
  await browser.close();
  server.close();
  process.exit(0);
}

const from = Math.round(parseFloat(opt('from', '0')) * FPS);
const to = Math.min(TOTAL, Math.round(parseFloat(opt('to', String(timeline.duration))) * FPS));
const count = to - from;
const chunk = Math.ceil(count / WORKERS);
const started = Date.now();
let doneFrames = 0;

const segments = [];
await Promise.all(
  Array.from({ length: WORKERS }, async (_, w) => {
    const a = from + w * chunk;
    const b = Math.min(to, a + chunk);
    if (a >= b) return;
    // Segment names are per-output so concurrent renders can't clobber each other.
    const file = path.join(OUT, `${path.parse(opt('out', 'video.mp4')).name}.seg${String(w).padStart(2, '0')}.mp4`);
    segments[w] = file;
    const { browser, page } = await openStage();
    const { ff, done } = encoder(file);
    for (let f = a; f < b; f++) {
      const png = await grab(page, f);
      if (!ff.stdin.write(png)) await new Promise((r) => ff.stdin.once('drain', r));
      doneFrames++;
      if (doneFrames % 30 === 0) {
        const el = (Date.now() - started) / 1000;
        const eta = (el / doneFrames) * (count - doneFrames);
        console.log(`frames ${doneFrames}/${count}  elapsed ${el.toFixed(0)}s  eta ${eta.toFixed(0)}s`);
      }
    }
    ff.stdin.end();
    await done;
    await browser.close();
  }),
);

const list = path.join(OUT, `${path.parse(opt('out', 'video.mp4')).name}.segments.txt`);
fs.writeFileSync(list, segments.filter(Boolean).map((s) => `file '${path.basename(s)}'`).join('\n'));
const target = path.join(OUT, opt('out', 'video.mp4'));
await new Promise((res, rej) => {
  const ff = spawn('ffmpeg', ['-y', '-loglevel', 'error', '-f', 'concat', '-safe', '0', '-i', list, '-c', 'copy', target], { stdio: 'inherit' });
  ff.on('close', (c) => (c === 0 ? res() : rej(new Error('concat failed'))));
});
for (const s of segments.filter(Boolean)) fs.unlinkSync(s);
fs.unlinkSync(list);
console.log(`wrote ${target} (${count} frames in ${((Date.now() - started) / 1000).toFixed(0)}s)`);
server.close();
