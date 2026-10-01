#!/usr/bin/env node
// Builds the trailer end to end, on Windows, macOS or Linux:
// dependencies → score → picture → mux.
//
//   npm run build:gpu        use your graphics card (recommended; a browser window opens while it renders)
//   npm run build            CPU only (SwiftShader), works anywhere but takes about an hour
//   node build.mjs --gpu --workers 4 --scale 1    extra flags are passed to render.mjs
import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.dirname(fileURLToPath(import.meta.url));
const WIN = process.platform === 'win32';
const passthrough = process.argv.slice(2);

function die(msg) {
  console.error(`\n✖ ${msg}`);
  process.exit(1);
}
// Only npm needs a shell on Windows (it's npm.cmd); everything else is a real
// executable, and going through cmd.exe would mangle quoted arguments.
function has(cmd, args) {
  const r = spawnSync(cmd, args, { stdio: 'ignore' });
  return !r.error && r.status === 0;
}
function run(cmd, args, label) {
  console.log(`\n▸ ${label}`);
  const r = spawnSync(cmd, args, { stdio: 'inherit', cwd: ROOT, shell: WIN && cmd === 'npm' });
  if (r.error || r.status !== 0) die(`${label} failed`);
}

// ---- prerequisites
if (!has('ffmpeg', ['-version'])) {
  die('ffmpeg was not found on your PATH.\n  Windows: winget install Gyan.FFmpeg   (then open a new terminal)\n  macOS:   brew install ffmpeg\n  Linux:   sudo apt install ffmpeg');
}
const python = ['python3', 'python', 'py'].find((c) => has(c, ['-c', 'import sys; sys.exit(sys.version_info < (3, 9))']));
if (!python) die('Python 3.9+ was not found. Install it from https://www.python.org/downloads/ (tick "Add to PATH").');

if (!fs.existsSync(path.join(ROOT, 'node_modules', 'playwright'))) {
  run('npm', ['install', '--no-audit', '--no-fund'], 'Installing Node packages');
}
const { chromium } = await import('playwright');
if (!fs.existsSync(chromium.executablePath())) {
  run(process.execPath, [path.join(ROOT, 'node_modules', 'playwright', 'cli.js'), 'install', 'chromium'], 'Downloading Chromium for Playwright');
}
if (!has(python, ['-c', 'import numpy, scipy'])) {
  run(python, ['-m', 'pip', 'install', '-r', path.join('audio', 'requirements.txt')], 'Installing numpy and scipy');
}

// ---- build
const t0 = Date.now();
run(python, [path.join('audio', 'score.py')], 'Composing and mixing the score → out/audio.wav');
run(process.execPath, ['render.mjs', '--out', 'video.mp4', ...passthrough], 'Rendering the picture → out/video.mp4');
const out = path.join(ROOT, 'distant-frontiers-trailer.mp4');
run('ffmpeg', [
  '-y', '-loglevel', 'error',
  '-i', path.join(ROOT, 'out', 'video.mp4'), '-i', path.join(ROOT, 'out', 'audio.wav'),
  '-map', '0:v', '-map', '1:a', '-c:v', 'copy', '-c:a', 'aac', '-b:a', '320k', '-ar', '48000', '-shortest',
  '-movflags', '+faststart', out,
], 'Muxing picture and sound');
console.log(`\n✔ ${out}  (${((Date.now() - t0) / 60000).toFixed(1)} min)`);
