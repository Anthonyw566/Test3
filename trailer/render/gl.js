// Minimal WebGL2 plumbing: programs, render targets, a fullscreen triangle,
// and the compositor that turns (scene shader + 2D overlay) into the final
// graded frame with bloom, grain, aberration and lens effects.
import { W, H } from './lib.js';
import * as S from './shaders.js';

export function createGL(canvas) {
  const gl = canvas.getContext('webgl2', {
    antialias: false,
    alpha: false,
    preserveDrawingBuffer: true,
    premultipliedAlpha: false,
  });
  if (!gl) throw new Error('WebGL2 unavailable');
  const floatOK = !!gl.getExtension('EXT_color_buffer_float');
  gl.getExtension('OES_texture_float_linear');

  const vao = gl.createVertexArray();
  gl.bindVertexArray(vao);
  const buf = gl.createBuffer();
  gl.bindBuffer(gl.ARRAY_BUFFER, buf);
  gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 3, -1, -1, 3]), gl.STATIC_DRAW);
  gl.enableVertexAttribArray(0);
  gl.vertexAttribPointer(0, 2, gl.FLOAT, false, 0, 0);

  function compile(type, src) {
    const s = gl.createShader(type);
    gl.shaderSource(s, src);
    gl.compileShader(s);
    if (!gl.getShaderParameter(s, gl.COMPILE_STATUS)) {
      const log = gl.getShaderInfoLog(s);
      const numbered = src.split('\n').map((l, i) => `${i + 1}: ${l}`).join('\n');
      throw new Error(`Shader compile failed:\n${log}\n${numbered}`);
    }
    return s;
  }

  function program(fs) {
    const p = gl.createProgram();
    gl.attachShader(p, compile(gl.VERTEX_SHADER, S.VS));
    gl.attachShader(p, compile(gl.FRAGMENT_SHADER, fs));
    gl.bindAttribLocation(p, 0, 'aPos');
    gl.linkProgram(p);
    if (!gl.getProgramParameter(p, gl.LINK_STATUS)) throw new Error(gl.getProgramInfoLog(p));
    const locs = {};
    return {
      p,
      use(uniforms = {}) {
        gl.useProgram(p);
        let unit = 0;
        for (const [name, v] of Object.entries(uniforms)) {
          if (!(name in locs)) locs[name] = gl.getUniformLocation(p, name);
          const loc = locs[name];
          if (loc === null) continue;
          if (v && v.v4) {
            gl.uniform4fv(loc, v.v4);
          } else if (v && v.tex) {
            gl.activeTexture(gl.TEXTURE0 + unit);
            gl.bindTexture(gl.TEXTURE_2D, v.tex);
            gl.uniform1i(loc, unit++);
          } else if (typeof v === 'number') gl.uniform1f(loc, v);
          else if (v.length === 2) gl.uniform2fv(loc, v);
          else if (v.length === 3) gl.uniform3fv(loc, v);
          else if (v.length === 4) gl.uniform4fv(loc, v);
          else if (v.length === 9) gl.uniformMatrix3fv(loc, false, v);
          else gl.uniform1fv(loc, v);
        }
      },
    };
  }

  function target(w, h, hdr = true) {
    const tex = gl.createTexture();
    gl.bindTexture(gl.TEXTURE_2D, tex);
    const useF = hdr && floatOK;
    gl.texImage2D(gl.TEXTURE_2D, 0, useF ? gl.RGBA16F : gl.RGBA8, w, h, 0, gl.RGBA, useF ? gl.HALF_FLOAT : gl.UNSIGNED_BYTE, null);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
    const fb = gl.createFramebuffer();
    gl.bindFramebuffer(gl.FRAMEBUFFER, fb);
    gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0, gl.TEXTURE_2D, tex, 0);
    return { tex, fb, w, h };
  }

  function draw(prog, uniforms, tgt) {
    gl.bindFramebuffer(gl.FRAMEBUFFER, tgt ? tgt.fb : null);
    gl.viewport(0, 0, tgt ? tgt.w : W, tgt ? tgt.h : H);
    prog.use({ uOut: [tgt ? tgt.w : W, tgt ? tgt.h : H], ...uniforms });
    gl.drawArrays(gl.TRIANGLES, 0, 3);
  }

  // Texture fed from a 2D canvas each frame (premultiplied, y-flipped so it
  // lines up with gl_FragCoord's bottom-left origin).
  function canvasTexture() {
    const tex = gl.createTexture();
    gl.bindTexture(gl.TEXTURE_2D, tex);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
    return {
      tex,
      upload(canvas) {
        gl.bindTexture(gl.TEXTURE_2D, tex);
        gl.pixelStorei(gl.UNPACK_FLIP_Y_WEBGL, true);
        gl.pixelStorei(gl.UNPACK_PREMULTIPLY_ALPHA_WEBGL, true);
        gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, canvas);
        gl.pixelStorei(gl.UNPACK_FLIP_Y_WEBGL, false);
        gl.pixelStorei(gl.UNPACK_PREMULTIPLY_ALPHA_WEBGL, false);
      },
    };
  }

  function dataTexture(data, w, h) {
    const tex = gl.createTexture();
    gl.bindTexture(gl.TEXTURE_2D, tex);
    gl.pixelStorei(gl.UNPACK_ALIGNMENT, 1);
    gl.texImage2D(gl.TEXTURE_2D, 0, gl.R32F, w, h, 0, gl.RED, gl.FLOAT, data);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.NEAREST);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.NEAREST);
    return { tex };
  }

  return { gl, program, target, draw, canvasTexture, dataTexture, floatOK };
}

// The compositor owns every pass after the scene shader.
export function createCompositor(G) {
  const progs = {
    comp: G.program(S.FS_COMPOSITE),
    bright: G.program(S.FS_BRIGHT),
    down: G.program(S.FS_DOWN),
    up: G.program(S.FS_UP),
    final: G.program(S.FS_FINAL),
    black: G.program(S.FS_BLACK),
  };
  const hdr = G.target(W, H, true);
  const chain = [];
  let w = W / 2, h = H / 2;
  for (let i = 0; i < 5; i++) {
    chain.push(G.target(Math.round(w), Math.round(h), true));
    w /= 2; h /= 2;
  }
  const ups = chain.slice(0, -1).map((c) => G.target(c.w, c.h, true));
  const overlay = G.canvasTexture();

  return {
    overlay,
    black: progs.black,
    // scene: {tex} of the scene render target (or null for black)
    run(sceneTarget, overlayCanvas, post, frameIndex) {
      overlay.upload(overlayCanvas);
      G.draw(progs.comp, {
        uScene: sceneTarget,
        uOverlay: overlay,
        uRes: [W, H],
        uWarp: post.warp ?? [0.5, 0.5, 0, 0],
        uShake: post.shake ?? [0, 0],
        uCA: post.ca ?? 0.0015,
        uOverlayGain: post.overlayGain ?? 1,
        uSceneGain: post.sceneGain ?? 1,
        uLetterbox: post.letterbox ?? 0,
      }, hdr);

      G.draw(progs.bright, { uTex: hdr, uThreshold: post.bloomThreshold ?? 0.72, uKnee: 0.35 }, chain[0]);
      for (let i = 1; i < chain.length; i++) {
        G.draw(progs.down, { uTex: chain[i - 1], uTexel: [1 / chain[i - 1].w, 1 / chain[i - 1].h] }, chain[i]);
      }
      let src = chain[chain.length - 1];
      for (let i = chain.length - 2; i >= 0; i--) {
        G.draw(progs.up, { uTex: src, uBase: chain[i], uTexel: [1 / src.w, 1 / src.h] }, ups[i]);
        src = ups[i];
      }

      G.draw(progs.final, {
        uTex: hdr,
        uBloom: src,
        uRes: [W, H],
        uBloomAmt: post.bloom ?? 0.55,
        uExposure: post.exposure ?? 1,
        uLift: post.lift ?? [0, 0, 0],
        uGamma: post.gamma ?? [1, 1, 1],
        uGain: post.gain ?? [1, 1, 1],
        uSat: post.sat ?? 1,
        uVignette: post.vignette ?? 0.35,
        // Grain is scaled down globally: it is what costs the most bits in H.264.
        uGrain: (post.grain ?? 0.045) * 0.42,
        uFrame: frameIndex,
        uFade: post.fade ?? 1,
        uFlash: post.flash ?? [0, 0, 0],
        uScanline: post.scanline ?? 0,
      }, null);
    },
  };
}
