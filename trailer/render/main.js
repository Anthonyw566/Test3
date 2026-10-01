// Render stage. render.mjs drives this page frame by frame:
//   await setup({ terrainScale })  → compiles shaders, loads fonts
//   renderFrame(i)                 → draws frame i into the canvas
// No wall clock is ever read; time is always frame / fps.
import { W, H, sceneAt, HASH_TABLE } from './lib.js';
import { createGL, createCompositor } from './gl.js';
import * as S from './shaders.js';
import { drawOpen } from './scenes/open.js';
import { drawTerrain } from './scenes/terrain.js';
import { drawGauge } from './scenes/gauge.js';
import { drawChampions } from './scenes/champions.js';
import { drawBank } from './scenes/bank.js';
import { drawTitle } from './scenes/title.js';

let env = null;

const SCENES = {
  open: (t, e) => ({ target: e.targets.full, post: drawOpen(t, e.tl, e.G, e.progs, e.targets.full, e.ctx) }),
  terrain: (t, e) => ({ target: e.targets.terrain, post: drawTerrain(t, e.tl, e.G, e.progs, e.targets.terrain, e.ctx) }),
  gauge: (t, e) => ({ target: e.targets.full, post: drawGauge(t, e.tl, e.G, e.progs, e.targets.full, e.ctx) }),
  champions: (t, e) => ({ target: e.targets.half, post: drawChampions(t, e.tl, e.G, e.progs, e.targets.half, e.ctx) }),
  bank: (t, e) => ({ target: e.targets.full, post: drawBank(t, e.tl, e.G, e.progs, e.targets.full, e.ctx) }),
  title: (t, e) => ({ target: e.targets.full, post: drawTitle(t, e.tl, e.G, e.progs, e.targets.full, e.ctx) }),
};

window.setup = async (opts = {}) => {
  const tl = await (await fetch('../timeline.json')).json();
  const faces = [
    '400 20px "Cormorant Garamond"', '500 20px "Cormorant Garamond"', '600 20px "Cormorant Garamond"', '700 20px "Cormorant Garamond"',
    'italic 500 20px "Cormorant Garamond"', 'italic 600 20px "Cormorant Garamond"',
    '400 20px "IM Fell English"', 'italic 400 20px "IM Fell English"',
    '500 20px "Playfair Display"',
    '400 20px "IBM Plex Mono"', '500 20px "IBM Plex Mono"', '600 20px "IBM Plex Mono"',
  ];
  await Promise.all(faces.map((f) => document.fonts.load(f)));
  await document.fonts.ready;

  const canvas = document.getElementById('out');
  const G = createGL(canvas);
  const comp = createCompositor(G);
  const progs = {
    terrain: G.program((opts.terrainPatch ?? []).reduce((src, [a, b]) => src.split(a).join(b), S.FS_TERRAIN)),
    hearth: G.program(S.FS_HEARTHMAP),
    panel: G.program(S.FS_PANEL),
    smoke: G.program(S.FS_SMOKE),
    black: comp.black,
    hashTex: G.dataTexture(HASH_TABLE, 256, 256),
  };
  const ts = opts.terrainScale ?? 0.75;
  const targets = {
    full: G.target(W, H, true),
    half: G.target(W / 2, H / 2, true),
    terrain: G.target(Math.round(W * ts), Math.round(H * ts), true),
  };
  const overlay = document.createElement('canvas');
  overlay.width = W;
  overlay.height = H;
  const ctx = overlay.getContext('2d');
  env = { tl, G, comp, progs, targets, overlay, ctx };
  const dbg = G.gl.getExtension('WEBGL_debug_renderer_info');
  const renderer = dbg ? G.gl.getParameter(dbg.UNMASKED_RENDERER_WEBGL) : G.gl.getParameter(G.gl.RENDERER);
  return { floatOK: G.floatOK, frames: Math.round(tl.duration * tl.fps), fps: tl.fps, renderer };
};

window.renderFrame = (frame) => {
  const { tl, ctx, comp, overlay, G } = env;
  const t = frame / tl.fps;
  ctx.setTransform(1, 0, 0, 1, 0, 0);
  ctx.globalAlpha = 1;
  ctx.globalCompositeOperation = 'source-over';
  ctx.filter = 'none';
  ctx.clearRect(0, 0, W, H);
  const scene = sceneAt(tl, t);
  const { target, post } = SCENES[scene.id](t, env);
  comp.run(target, overlay, post, frame);
  // Force the GPU process to finish (gl.finish() alone does not block).
  const px = new Uint8Array(4);
  G.gl.readPixels(0, 0, 1, 1, G.gl.RGBA, G.gl.UNSIGNED_BYTE, px);
  return scene.id;
};
