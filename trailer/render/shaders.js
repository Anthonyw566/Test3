// GLSL for every pass. Scene shaders draw a background into an HDR target;
// the compositor passes (composite → bloom chain → final) grade it.

export const VS = `#version 300 es
in vec2 aPos;
void main() { gl_Position = vec4(aPos, 0.0, 1.0); }`;

// ---------------------------------------------------------------- common
const COMMON = `#version 300 es
precision highp float;
precision highp int;
out vec4 fragColor;
uniform vec2 uOut;

// Lattice values come from a 256x256 table that JS also holds (lib.js
// HASH_TABLE), so the CPU can evaluate exactly the terrain the GPU draws.
uniform highp sampler2D uHashTex;
float hashI(ivec2 p) { return texelFetch(uHashTex, p & 255, 0).r; }

// Value noise with analytic derivatives (quintic). Returns (n, dn/dx, dn/dy).
vec3 noised(vec2 x) {
  vec2 fl = floor(x);
  ivec2 i = ivec2(fl) + ivec2(50000);
  vec2 f = x - fl;
  vec2 u = f * f * f * (f * (f * 6.0 - 15.0) + 10.0);
  vec2 du = 30.0 * f * f * (f * (f - 2.0) + 1.0);
  float a = hashI(i), b = hashI(i + ivec2(1, 0)), c = hashI(i + ivec2(0, 1)), d = hashI(i + ivec2(1, 1));
  float k1 = b - a, k2 = c - a, k4 = a - b - c + d;
  return vec3(-1.0 + 2.0 * (a + k1 * u.x + k2 * u.y + k4 * u.x * u.y), 2.0 * du * vec2(k1 + k4 * u.y, k2 + k4 * u.x));
}

// Cheap float hash/noise for texture detail that JS never needs to match.
float h12(vec2 p) { vec3 p3 = fract(vec3(p.xyx) * 0.1031); p3 += dot(p3, p3.yzx + 33.33); return fract((p3.x + p3.y) * p3.z); }
vec2 h22(vec2 p) { vec3 p3 = fract(vec3(p.xyx) * vec3(0.1031, 0.1030, 0.0973)); p3 += dot(p3, p3.yzx + 33.33); return fract((p3.xx + p3.yz) * p3.zy); }
float vn(vec2 p) {
  vec2 i = floor(p), f = fract(p);
  vec2 u = f * f * (3.0 - 2.0 * f);
  return mix(mix(h12(i), h12(i + vec2(1, 0)), u.x), mix(h12(i + vec2(0, 1)), h12(i + vec2(1, 1)), u.x), u.y);
}
const mat2 R2 = mat2(0.8, -0.6, 0.6, 0.8);
float fbm(vec2 p, int oct) {
  float s = 0.0, a = 0.5;
  for (int i = 0; i < 9; i++) { if (i >= oct) break; s += a * vn(p); p = R2 * p * 2.03 + 17.1; a *= 0.5; }
  return s;
}
float luma(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }
`;

// ---------------------------------------------------------------- terrain
// A raymarched heightfield whose character is a function of distance from
// spawn: soft meadows in the Hearth become eroded ridges, then basalt with
// lava seams in the Ashenfront. The flight path is carved as a valley so the
// camera can stay low. terrainH() is mirrored exactly in terrain.js.
export const FS_TERRAIN = COMMON + `
uniform vec2 uRes;
uniform float uTime;
uniform vec3 uCamPos;
uniform mat3 uCamRot;
uniform float uFov;
uniform vec2 uOrigin;
uniform vec4 uBounds;      // world radii where Verge, Wildmarch, Duskreach, Ashenfront begin
uniform vec3 uSunDir;
uniform vec3 uSunCol;
uniform vec3 uSkyTop;
uniform vec3 uSkyHor;
uniform vec3 uFogCol;
uniform float uFogDen;
uniform float uStars;
uniform vec3 uMoonDir;
uniform float uMoon;
uniform float uCurtainR;
uniform vec3 uCurtainCol;
uniform float uCurtainAmt;
uniform float uAmbient;
uniform float uCloudAmt;
uniform vec3 uCloudTint;
uniform vec4 uEyes[6];      // watchers: world xyz + how open the eyes are
uniform float uStepK;       // march relaxation: gentle terrain tolerates longer steps

float ringIndex(vec2 p) {
  float r = length(p - uOrigin);
  if (r < uBounds.x) return r / uBounds.x;
  if (r < uBounds.y) return 1.0 + (r - uBounds.x) / (uBounds.y - uBounds.x);
  if (r < uBounds.z) return 2.0 + (r - uBounds.y) / (uBounds.z - uBounds.y);
  if (r < uBounds.w) return 3.0 + (r - uBounds.z) / (uBounds.w - uBounds.z);
  return 4.0 + (r - uBounds.w) / (uBounds.w - uBounds.z);
}

float pathX(float z) { return 14.0 * sin(z * 0.021) + 22.0 * sin(z * 0.0073 + 1.3); }

// oct may be fractional: the last octave fades in, so detail can fall off
// with distance without a visible seam.
float terrainH(vec2 x, float oct) {
  float w = ringIndex(x);
  vec2 p = x * 0.045;
  float a = 0.0, b = 1.0;
  vec2 d = vec2(0.0);
  // Erosion strength grows outward: home is smooth, the frontier is carved.
  float ero = mix(0.35, 1.3, smoothstep(0.0, 3.5, w));
  int n = int(ceil(oct));
  for (int i = 0; i < 12; i++) {
    if (i >= n) break;
    vec3 nz = noised(p);
    float wgt = i == n - 1 ? oct - float(n - 1) : 1.0;
    d += nz.yz * wgt;
    a += wgt * b * nz.x / (1.0 + ero * dot(d, d));
    b *= 0.5;
    p = R2 * p * 2.0;
  }
  float amp = mix(1.6, 9.5, smoothstep(0.2, 4.2, w));
  float h = a * amp + mix(0.0, 3.0, smoothstep(1.5, 4.5, w));
  float dx = x.x - pathX(x.y);
  float sig = mix(9.0, 7.5, smoothstep(0.0, 4.0, w));
  h -= exp(-dx * dx / (2.0 * sig * sig)) * mix(1.5, 8.0, smoothstep(0.5, 4.0, w));
  return h;
}

float march(vec3 ro, vec3 rd, float tmax) {
  float t = 0.2, tp = 0.2, hp = 1.0;
  for (int i = 0; i < 160; i++) {
    vec3 p = ro + rd * t;
    if (p.y > 24.0 && rd.y >= 0.0) return tmax + 1.0;
    float oct = clamp(10.0 - 1.3 * log2(1.0 + t / 3.0), 5.0, 8.0);
    float h = p.y - terrainH(p.xz, oct);
    if (h < 0.002 * t) {
      // Overshot: secant step back to the surface instead of a staircase.
      if (h < 0.0 && i > 0) t = tp + (t - tp) * hp / (hp - h);
      return t;
    }
    if (t > tmax) break;
    tp = t; hp = h;
    t += max(uStepK * h, 0.005 * t);
  }
  return t;
}

vec3 calcNormal(vec3 p, float t) {
  float e = 0.004 * t + 0.002;
  float oct = clamp(12.0 - 1.5 * log2(1.0 + t / 2.0), 5.0, 10.0);
  return normalize(vec3(
    terrainH(p.xz - vec2(e, 0), oct) - terrainH(p.xz + vec2(e, 0), oct),
    2.0 * e,
    terrainH(p.xz - vec2(0, e), oct) - terrainH(p.xz + vec2(0, e), oct)));
}

float softShadow(vec3 ro, vec3 rd) {
  float res = 1.0, t = 0.15;
  for (int i = 0; i < 20; i++) {
    vec3 p = ro + rd * t;
    if (p.y > 24.0) break;
    float h = p.y - terrainH(p.xz, 4.0);
    res = min(res, 10.0 * h / t);
    t += clamp(h, 0.2, 4.0);
    if (res < 0.001 || t > 60.0) break;
  }
  return clamp(res, 0.0, 1.0);
}

vec3 sky(vec3 rd, float forFog) {
  float y = max(rd.y, 0.0);
  vec3 col = mix(uSkyHor, uSkyTop, pow(y, 0.45));
  float sd = max(dot(rd, uSunDir), 0.0);
  col += uSunCol * (0.30 * pow(sd, 6.0) + 0.55 * pow(sd, 48.0));
  if (forFog < 0.5) {
    col += uSunCol * 8.0 * smoothstep(0.9993, 0.9997, sd);
    // Thin cloud deck lit by the sun.
    if (rd.y > 0.0) {
      float tc = (90.0 - uCamPos.y) / rd.y;
      vec2 cp = (uCamPos.xz + rd.xz * tc) * 0.006 + vec2(uTime * 0.01, 0.0);
      float cl = smoothstep(0.42, 0.85, fbm(cp, 6));
      float lit = 0.6 + 0.8 * pow(sd, 3.0);
      vec3 cc = mix(uSkyHor * 0.9, uCloudTint, 0.6) * lit;
      col = mix(col, cc, cl * uCloudAmt * smoothstep(0.0, 0.12, rd.y));
    }
    // Stars and moon for the dark rings.
    if (uStars > 0.0 && rd.y > 0.0) {
      vec2 sp = vec2(atan(rd.z, rd.x) * 220.0, rd.y * 300.0);
      vec2 cell = floor(sp);
      vec2 jit = h22(cell);
      float star = smoothstep(0.08, 0.0, length(fract(sp) - jit)) * step(0.93, h12(cell + 3.1));
      star *= 0.6 + 0.4 * sin(uTime * 3.0 + h12(cell) * 40.0);
      col += vec3(0.85, 0.9, 1.0) * star * uStars * smoothstep(0.02, 0.25, rd.y);
    }
    if (uMoon > 0.0) {
      float md = dot(rd, uMoonDir);
      col += vec3(1.0, 0.92, 0.85) * uMoon * (smoothstep(0.99955, 0.99975, md) * 3.0 + 0.10 * pow(max(md, 0.0), 120.0));
    }
  }
  return col;
}

vec3 palette(float w, float slope, vec2 xz, vec3 n, out float emissive) {
  float fn = fbm(xz * 0.12, 4);
  float fine = fbm(xz * 1.3, 3);
  vec3 lush   = mix(vec3(0.16, 0.26, 0.08), vec3(0.30, 0.38, 0.12), fn);
  vec3 meadow = mix(vec3(0.34, 0.36, 0.12), vec3(0.50, 0.44, 0.18), fn);
  vec3 dry    = mix(vec3(0.46, 0.34, 0.14), vec3(0.58, 0.44, 0.20), fn);
  vec3 dusk   = mix(vec3(0.26, 0.14, 0.10), vec3(0.36, 0.20, 0.14), fn);
  vec3 ash    = mix(vec3(0.07, 0.06, 0.06), vec3(0.13, 0.11, 0.10), fn);
  vec3 ground = w < 1.0 ? mix(lush, meadow, smoothstep(0.6, 1.0, w))
              : w < 2.0 ? mix(meadow, dry, smoothstep(1.4, 2.0, w))
              : w < 3.0 ? mix(dry, dusk, smoothstep(2.5, 3.0, w))
              : mix(dusk, ash, smoothstep(3.4, 4.0, w));
  vec3 rock = mix(vec3(0.30, 0.27, 0.24), vec3(0.16, 0.12, 0.11), smoothstep(1.5, 4.0, w));
  rock *= 0.75 + 0.5 * fine;
  // Forest canopy patches (darker, rougher) until the dead rings.
  float forest = smoothstep(0.52, 0.6, fbm(xz * 0.09 + 31.0, 4)) * (1.0 - smoothstep(2.6, 3.2, w)) * smoothstep(0.75, 0.9, slope);
  ground = mix(ground, vec3(0.05, 0.09, 0.04) * (0.7 + 0.6 * fine), forest * 0.85);
  vec3 col = mix(rock, ground * (0.85 + 0.3 * fine), smoothstep(0.62, 0.82, slope));
  // Ashenfront: cooled basalt plates split by glowing cracks (Voronoi edge
  // distance), brightest where molten rock pools in low ground.
  emissive = 0.0;
  if (w > 3.4) {
    vec2 q = xz * 0.55 + 0.4 * vec2(vn(xz * 0.7), vn(xz * 0.7 + 9.0));
    vec2 g = floor(q), fq = fract(q);
    float f1 = 8.0, f2 = 8.0;
    for (int j = -1; j <= 1; j++) for (int i = -1; i <= 1; i++) {
      vec2 o = vec2(i, j);
      float dd = length(o + h22(g + o) - fq);
      if (dd < f1) { f2 = f1; f1 = dd; } else if (dd < f2) f2 = dd;
    }
    float crack = 1.0 - smoothstep(0.0, 0.05 + 0.05 * fine, f2 - f1);
    // Only some seams are still molten; the rest have cooled to black.
    float heat = smoothstep(0.42, 0.72, fbm(xz * 0.06 + 4.0, 4));
    float cellHeat = step(0.35, h12(g + 0.5));
    emissive = crack * heat * mix(0.25, 1.0, cellHeat) * smoothstep(3.6, 4.2, w) * smoothstep(0.55, 0.85, slope);
    col *= 1.0 - 0.6 * crack * smoothstep(3.6, 4.2, w);
  }
  return col;
}

void main() {
  vec2 frag = gl_FragCoord.xy;
  vec2 uv = (frag - 0.5 * uOut) / uOut.y;
  vec3 rd = normalize(uCamRot * vec3(uv * uFov, 1.0));
  vec3 ro = uCamPos;

  float tmax = 230.0;
  float t = march(ro, rd, tmax);
  vec3 col;
  bool hit = t < tmax;
  if (hit) {
    vec3 p = ro + rd * t;
    vec3 n = calcNormal(p, t);
    float w = ringIndex(p.xz);
    float em;
    vec3 alb = palette(w, n.y, p.xz, n, em);
    float dif = clamp(dot(n, uSunDir), 0.0, 1.0);
    float sh = dif > 0.001 ? softShadow(p + n * 0.05, uSunDir) : 0.0;
    float amb = 0.5 + 0.5 * n.y;
    float bac = clamp(dot(n, normalize(vec3(-uSunDir.x, 0.0, -uSunDir.z))), 0.0, 1.0);
    float moonDif = clamp(dot(n, uMoonDir), 0.0, 1.0);
    vec3 lin = uSunCol * dif * sh * 2.2
             + uSkyTop * amb * uAmbient * 1.2
             + uSunCol * bac * 0.12 * uAmbient
             + vec3(0.35, 0.42, 0.62) * moonDif * uMoon * 0.32;
    col = alb * lin;
    // Lava glows from inside, flickering slowly.
    col += em * vec3(3.6, 0.95, 0.2) * (0.8 + 0.2 * sin(uTime * 2.0 + p.x * 0.3));
    // Aerial perspective with sun in-scattering, plus low-lying haze.
    float fog = 1.0 - exp(-t * uFogDen - max(0.0, 3.0 - p.y) * 0.02 * t * uFogDen * 6.0);
    vec3 fc = uFogCol + uSunCol * 0.35 * pow(max(dot(rd, uSunDir), 0.0), 8.0);
    col = mix(col, fc, clamp(fog, 0.0, 1.0));
  } else {
    col = sky(rd, 0.0);
    float hz = exp(-max(rd.y, 0.0) * 14.0);
    col = mix(col, uFogCol + uSunCol * 0.35 * pow(max(dot(rd, uSunDir), 0.0), 8.0), hz * 0.7);
  }

  // The ring boundary: a shimmering curtain of light on a circle around spawn.
  if (uCurtainAmt > 0.0) {
    vec2 o = ro.xz - uOrigin;
    vec2 d = rd.xz;
    float a = dot(d, d), b = 2.0 * dot(o, d), c = dot(o, o) - uCurtainR * uCurtainR;
    float disc = b * b - 4.0 * a * c;
    if (disc > 0.0) {
      float tc = (-b + sqrt(disc)) / (2.0 * a);
      if (tc > 0.0 && (tc < t || !hit)) {
        vec3 q = ro + rd * tc;
        float ground = terrainH(q.xz, 4.0);
        float hgt = q.y - ground;
        float ang = atan(q.z - uOrigin.y, q.x - uOrigin.x) * uCurtainR;
        float streak = fbm(vec2(ang * 0.9, hgt * 0.05 - uTime * 0.7), 4);
        streak = pow(smoothstep(0.35, 0.8, streak), 2.2);
        // Light rises off the ground in shafts and thins out with height.
        float vert = smoothstep(-0.3, 0.8, hgt) * exp(-max(hgt, 0.0) * 0.07);
        float base = exp(-abs(hgt) * 1.6) * 1.6;
        // Fades as you reach it, so the crossing itself is a flash, not a fog.
        float near = exp(-tc * 0.02) * smoothstep(0.5, 6.0, tc);
        col += uCurtainCol * (vert * (0.02 + 2.3 * streak) + base * (0.5 + streak)) * near * uCurtainAmt;
      }
    }
  }

  // Eyes in the treeline, depth-tested against the terrain hit.
  vec3 camR = uCamRot[0], camU = uCamRot[1];
  for (int i = 0; i < 6; i++) {
    vec4 e = uEyes[i];
    if (e.w <= 0.001) continue;
    for (int s = 0; s < 2; s++) {
      vec3 ep = e.xyz + camR * (s == 0 ? -0.14 : 0.14);
      vec3 v = ep - ro;
      float te = dot(v, rd);
      if (te <= 0.0 || (hit && te > t + 0.35)) continue;
      vec3 perp = v - rd * te;
      float ex = dot(perp, camR), ey = dot(perp, camU);
      float sc = 0.035 + te * 0.0008;
      float open = max(e.w, 0.05);
      float q = (ex * ex) / (sc * sc) + (ey * ey) / (sc * sc * 0.3 * open * open);
      col += vec3(2.8, 1.3, 0.22) * (exp(-q) * 2.2 + 0.12 * exp(-sqrt(q) * 0.8)) * e.w;
    }
  }
  fragColor = vec4(col, 1.0);
}`;

// ---------------------------------------------------------------- hearth / map
// One shader for the opening: a hearth fire, which the camera pulls away
// from until it becomes the centre point of a topographic survey map.
export const FS_HEARTHMAP = COMMON + `
uniform float uTime;
uniform vec2 uCenter;      // map coords at screen centre
uniform float uZoom;       // screen px per map unit
uniform float uMap;        // map visibility
uniform float uFire;       // fire intensity
uniform float uFireScale;  // px
uniform vec2 uFirePx;      // screen px (y down)
uniform float uFlicker;
uniform vec4 uInk;         // ring zone wash reveal (first four zones)
uniform float uInk5;
uniform vec4 uRadii;       // map radii of boundaries
uniform float uMapDim;
uniform vec3 uTint;

float mapHeight(vec2 m) {
  return fbm(m * 0.009 + 3.0, 5) + 0.06 * fbm(m * 0.04, 3);
}

vec3 flame(vec2 q, float t) {
  // q: fire-local, x right, y up, base at 0, height about 1.
  float n1 = fbm(vec2(q.x * 3.2, q.y * 2.4 - t * 3.1), 5);
  float n2 = fbm(vec2(q.x * 6.0 + 7.0, q.y * 4.0 - t * 5.3), 4);
  vec2 w = q + vec2((n1 - 0.5) * 0.42 * q.y, (n2 - 0.5) * 0.18);
  float width = 0.42 * (1.0 - smoothstep(-0.1, 1.15, w.y)) + 0.02;
  float body = smoothstep(width, width * 0.15, abs(w.x)) * smoothstep(-0.12, 0.05, w.y);
  float tongues = smoothstep(0.35, 0.85, n2 + (1.0 - w.y) * 0.55);
  float f = body * tongues;
  float core = f * smoothstep(0.65, 0.0, w.y) * smoothstep(width * 0.6, 0.0, abs(w.x));
  vec3 c = vec3(1.6, 0.42, 0.06) * f + vec3(1.8, 1.25, 0.55) * core * 1.4;
  return c;
}

void main() {
  vec2 px = vec2(gl_FragCoord.x, uOut.y - gl_FragCoord.y) * (1920.0 / uOut.x);
  vec2 m = uCenter + (px - vec2(960.0, 540.0)) / uZoom;
  vec3 col = vec3(0.0);

  if (uMap > 0.0) {
    float h = mapHeight(m);
    float levels = 16.0;
    float hv = h * levels;
    float fw = fwidth(hv);
    float fr = fract(hv);
    float line = 1.0 - smoothstep(0.0, fw * 1.4, min(fr, 1.0 - fr));
    float idx = 1.0 - smoothstep(0.0, fw * 2.4, abs(fract(hv / 5.0 + 0.1) - 0.1) * 5.0);
    // Hillshade from the height gradient.
    vec2 e = vec2(0.6 / max(uZoom, 0.01) + 0.5, 0.0);
    float hx = mapHeight(m + e.xy) - mapHeight(m - e.xy);
    float hy = mapHeight(m + e.yx) - mapHeight(m - e.yx);
    float shade = clamp(0.5 + (hx - hy) * 25.0, 0.0, 1.0);
    vec3 paper = vec3(0.045, 0.050, 0.060) + vec3(0.03, 0.026, 0.02) * shade;
    paper *= 0.85 + 0.3 * fbm(px * 0.35, 3);
    // Ring zone washes appear as each ring is inked.
    float r = length(m);
    vec3 z0 = vec3(0.56, 0.84, 0.63), z1 = vec3(0.95, 0.84, 0.49), z2 = vec3(0.94, 0.65, 0.19), z3 = vec3(0.91, 0.33, 0.25), z4 = vec3(0.70, 0.13, 0.12);
    vec3 zc = r < uRadii.x ? z0 * uInk.x : r < uRadii.y ? z1 * uInk.y : r < uRadii.z ? z2 * uInk.z : r < uRadii.w ? z3 * uInk.w : z4 * uInk5;
    paper += zc * 0.035;
    vec3 ink = vec3(0.62, 0.58, 0.50) * (0.22 * line + 0.35 * idx * line);
    ink = mix(ink, ink * (zc / max(0.001, max(zc.r, max(zc.g, zc.b)))), 0.5 * step(0.001, dot(zc, zc)));
    col = (paper + ink) * uMap * uMapDim;
  }

  if (uFire > 0.0) {
    vec2 q = (px - uFirePx) / uFireScale;
    q.y = -q.y;
    // Three tongues at different heights and phases read as a hearth fire
    // rather than a candle.
    vec3 f = flame(q + vec2(0.0, 0.05), uTime);
    f = max(f, flame((q + vec2(0.17, 0.02)) * vec2(1.1, 1.55), uTime + 3.7) * 0.85);
    f = max(f, flame((q + vec2(-0.16, 0.03)) * vec2(1.1, 1.3), uTime + 7.1) * 0.9);
    f = max(f, flame((q + vec2(0.05, 0.0)) * vec2(1.4, 2.1), uTime + 11.3) * 0.8);
    // Embers bed and warm halo.
    float bed = exp(-dot(q * vec2(0.9, 6.0), q * vec2(0.9, 6.0)));
    float halo = exp(-length(q - vec2(0.0, 0.35)) * 1.6);
    vec3 fire = f + vec3(1.2, 0.35, 0.06) * bed * (0.7 + 0.3 * uFlicker)
              + vec3(0.55, 0.20, 0.05) * halo * (0.55 + 0.25 * uFlicker);
    col += fire * uFire * uTint;
  }
  fragColor = vec4(col, 1.0);
}`;

// ---------------------------------------------------------------- panel
// Crinkle-finish instrument panel for the Heat gauge, lit by a warm lamp.
export const FS_PANEL = COMMON + `
uniform float uTime;
uniform vec2 uLight;      // px
uniform float uAlarm;     // red pulse
uniform float uBright;
float crinkle(vec2 p) {
  float a = fbm(p * 0.045, 5);
  float b = fbm(p * 0.22 + a * 3.0, 4);
  return a * 0.6 + b * 0.4;
}
void main() {
  vec2 px = vec2(gl_FragCoord.x, uOut.y - gl_FragCoord.y) * (1920.0 / uOut.x);
  float c = crinkle(px);
  float cx = crinkle(px + vec2(1.5, 0.0)) - crinkle(px - vec2(1.5, 0.0));
  float cy = crinkle(px + vec2(0.0, 1.5)) - crinkle(px - vec2(0.0, 1.5));
  vec3 n = normalize(vec3(-cx * 3.0, -cy * 3.0, 1.0));
  vec3 L = normalize(vec3(uLight - px, 420.0));
  float d = max(dot(n, L), 0.0);
  float spec = pow(max(dot(reflect(-L, n), vec3(0, 0, 1)), 0.0), 18.0);
  float fall = exp(-length(px - uLight) / 900.0);
  vec3 base = mix(vec3(0.050, 0.055, 0.050), vec3(0.085, 0.085, 0.075), c);
  vec3 col = base * (0.35 + 1.4 * d * fall) + vec3(0.55, 0.45, 0.32) * spec * fall * 0.35;
  col *= 0.6 + 0.6 * fall;
  // fine scratches
  float s = smoothstep(0.996, 1.0, vn(vec2(px.x * 0.02 + px.y * 0.3, px.y * 0.01) * 3.0));
  col += s * 0.03 * fall;
  col = mix(col, col * vec3(1.7, 0.45, 0.38) + vec3(0.06, 0.0, 0.0), uAlarm);
  fragColor = vec4(col * uBright, 1.0);
}`;

// ---------------------------------------------------------------- smoke
// Domain-warped smoke with an underlight; tinted per champion modifier.
export const FS_SMOKE = COMMON + `
uniform float uTime;
uniform vec3 uTint;
uniform vec3 uUnder;
uniform float uDensity;
uniform float uSwirl;
void main() {
  vec2 px = vec2(gl_FragCoord.x, uOut.y - gl_FragCoord.y) * (1920.0 / uOut.x);
  vec2 p = px / 1080.0 * 2.2;
  float t = uTime * 0.18;
  vec2 c = vec2(1920.0 / 1080.0 * 1.1, 1.1);
  vec2 dc = p - c;
  float ang = uSwirl * exp(-dot(dc, dc) * 0.8);
  p = c + mat2(cos(ang), -sin(ang), sin(ang), cos(ang)) * dc;
  vec2 q = vec2(fbm(p + vec2(0.0, t), 5), fbm(p + vec2(5.2, 1.3 - t), 5));
  vec2 r = vec2(fbm(p + 3.0 * q + vec2(1.7, 9.2) + 0.15 * t, 5), fbm(p + 3.0 * q + vec2(8.3, 2.8) - 0.12 * t, 5));
  float f = fbm(p + 3.5 * r, 6);
  float dens = pow(smoothstep(0.3, 0.95, f), 1.6) * uDensity;
  float under = smoothstep(1.4, 0.0, p.y * 0.45 + 0.2) ;
  vec3 col = uTint * dens * (0.35 + 0.65 * length(q)) + uUnder * dens * under * 1.2;
  col += uUnder * 0.04 * under;
  fragColor = vec4(col, 1.0);
}`;

export const FS_BLACK = COMMON + `void main() { fragColor = vec4(0.0, 0.0, 0.0, 1.0); }`;

// ---------------------------------------------------------------- compositor
export const FS_COMPOSITE = COMMON + `
uniform sampler2D uScene;
uniform sampler2D uOverlay;
uniform vec2 uRes;
uniform vec4 uWarp;    // centre uv, swirl, pinch
uniform vec2 uShake;   // px
uniform float uCA;
uniform float uOverlayGain;
uniform float uSceneGain;
uniform float uLetterbox;
void main() {
  vec2 uv = gl_FragCoord.xy / uRes;
  float lb = uLetterbox * 138.0;
  float bar = step(gl_FragCoord.y, lb) + step(uRes.y - lb, gl_FragCoord.y);
  uv += uShake / uRes;
  float aspect = uRes.x / uRes.y;
  if (uWarp.z != 0.0 || uWarp.w != 0.0) {
    vec2 d = uv - uWarp.xy;
    d.x *= aspect;
    float r2 = dot(d, d);
    float ang = uWarp.z * exp(-r2 * 5.0);
    d = mat2(cos(ang), -sin(ang), sin(ang), cos(ang)) * d;
    d *= 1.0 + uWarp.w * exp(-r2 * 7.0);
    d.x /= aspect;
    uv = uWarp.xy + d;
  }
  vec2 cd = uv - 0.5;
  vec2 off = cd * uCA * (1.0 + 3.0 * dot(cd, cd));
  vec3 sc = vec3(texture(uScene, uv + off).r, texture(uScene, uv).g, texture(uScene, uv - off).b) * uSceneGain;
  vec4 ov = texture(uOverlay, uv);
  vec3 oc = ov.rgb;
  sc *= 1.0 - clamp(bar, 0.0, 1.0);
  vec3 col = sc * (1.0 - ov.a) + oc * uOverlayGain;
  fragColor = vec4(col, 1.0);
}`;

export const FS_BRIGHT = COMMON + `
uniform sampler2D uTex;
uniform float uThreshold;
uniform float uKnee;
void main() {
  vec2 uv = gl_FragCoord.xy / uOut;
  vec2 tx = 0.5 / uOut;
  vec3 c = (texture(uTex, uv + vec2(-tx.x, -tx.y) * 0.5).rgb + texture(uTex, uv + vec2(tx.x, -tx.y) * 0.5).rgb
          + texture(uTex, uv + vec2(-tx.x, tx.y) * 0.5).rgb + texture(uTex, uv + vec2(tx.x, tx.y) * 0.5).rgb) * 0.25;
  float br = max(c.r, max(c.g, c.b));
  float soft = clamp(br - uThreshold + uKnee, 0.0, 2.0 * uKnee);
  soft = soft * soft / (4.0 * uKnee + 1e-4);
  float k = max(soft, br - uThreshold) / max(br, 1e-4);
  fragColor = vec4(c * k, 1.0);
}`;

export const FS_DOWN = COMMON + `
uniform sampler2D uTex;
uniform vec2 uTexel;
void main() {
  vec2 uv = gl_FragCoord.xy / uOut;
  vec2 hp = uTexel;
  vec3 s = texture(uTex, uv).rgb * 4.0;
  s += texture(uTex, uv - hp).rgb;
  s += texture(uTex, uv + hp).rgb;
  s += texture(uTex, uv + vec2(hp.x, -hp.y)).rgb;
  s += texture(uTex, uv - vec2(hp.x, -hp.y)).rgb;
  fragColor = vec4(s / 8.0, 1.0);
}`;

export const FS_UP = COMMON + `
uniform sampler2D uTex;
uniform sampler2D uBase;
uniform vec2 uTexel;
void main() {
  vec2 uv = gl_FragCoord.xy / uOut;
  vec2 hp = uTexel * 0.5;
  vec3 s = texture(uTex, uv + vec2(-hp.x * 2.0, 0.0)).rgb;
  s += texture(uTex, uv + vec2(-hp.x, hp.y)).rgb * 2.0;
  s += texture(uTex, uv + vec2(0.0, hp.y * 2.0)).rgb;
  s += texture(uTex, uv + vec2(hp.x, hp.y)).rgb * 2.0;
  s += texture(uTex, uv + vec2(hp.x * 2.0, 0.0)).rgb;
  s += texture(uTex, uv + vec2(hp.x, -hp.y)).rgb * 2.0;
  s += texture(uTex, uv + vec2(0.0, -hp.y * 2.0)).rgb;
  s += texture(uTex, uv + vec2(-hp.x, -hp.y)).rgb * 2.0;
  fragColor = vec4(s / 12.0 + texture(uBase, uv).rgb, 1.0);
}`;

export const FS_FINAL = COMMON + `
uniform sampler2D uTex;
uniform sampler2D uBloom;
uniform vec2 uRes;
uniform float uBloomAmt;
uniform float uExposure;
uniform vec3 uLift;
uniform vec3 uGamma;
uniform vec3 uGain;
uniform float uSat;
uniform float uVignette;
uniform float uGrain;
uniform float uFrame;
uniform float uFade;
uniform vec3 uFlash;
uniform float uScanline;
vec3 shoulder(vec3 c) {
  // Linear to 0.75, then a smooth roll-off to 1: UI colours stay true while
  // lava, sun and flashes compress like film instead of clipping.
  vec3 x = max(c - 0.75, 0.0);
  return min(c, 0.75) + 0.25 * (1.0 - exp(-x * 4.0));
}
void main() {
  vec2 uv = gl_FragCoord.xy / uRes;
  vec3 c = texture(uTex, uv).rgb;
  vec3 b = texture(uBloom, uv).rgb / 5.0;
  c += b * uBloomAmt;
  c = c * uExposure + uFlash;
  c = shoulder(c);
  c = uGain * (c + uLift * (1.0 - c));
  c = pow(max(c, 0.0), 1.0 / uGamma);
  c = mix(vec3(luma(c)), c, uSat);
  vec2 v = (uv - 0.5) * vec2(uRes.x / uRes.y, 1.0);
  c *= mix(1.0, smoothstep(1.25, 0.25, length(v) * 1.15), uVignette);
  float l = luma(c);
  float g = h12(gl_FragCoord.xy + fract(uFrame * 0.6180339) * 1000.0) + h12(gl_FragCoord.xy * 1.37 + fract(uFrame * 0.3819) * 773.0) - 1.0;
  c += g * uGrain * (0.35 + 2.4 * l * (1.0 - l));
  c *= uFade;
  c += (h12(gl_FragCoord.xy + uFrame) - 0.5) / 255.0;
  fragColor = vec4(clamp(c, 0.0, 1.0), 1.0);
}`;
