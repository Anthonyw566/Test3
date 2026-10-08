#!/usr/bin/env python3
"""Renders a ~48 second explainer trailer for Distant Frontiers.

Every visual is drawn in code (reusing the resource pack's pixel art from
make_art.py) and every sound comes from make_sounds.py, plus a small
synthesized music bed. Output: media/trailer.mp4 (H.264 + AAC).

Usage (needs pillow, numpy, scipy, soundfile, imageio-ffmpeg):
    python3 tools/make_trailer.py [--frames 0,300,600]   # optional: just dump preview frames
"""
import argparse
import math
import os
import subprocess
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

sys.path.insert(0, os.path.dirname(__file__))
import make_art as art  # noqa: E402
import make_sounds as snd  # noqa: E402

W, H, FPS = 1280, 720, 30
BG = (18, 14, 26)
FONT_BOLD = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"
FONT = "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"
RING_COLORS = ["#3fb34f", "#ffd84a", "#f0a830", "#e0404a", "#9a1a2a"]
RING_NAMES = ["The Hearth", "The Verge", "The Wildmarch", "The Duskreach", "The Ashenfront"]

_fonts = {}


def font(size, bold=True):
    key = (size, bold)
    if key not in _fonts:
        _fonts[key] = ImageFont.truetype(FONT_BOLD if bold else FONT, size)
    return _fonts[key]


# ----------------------------------------------------------------------------- easing + helpers

def clamp(x, lo=0.0, hi=1.0):
    return max(lo, min(hi, x))


def prog(t, t0, t1):
    return clamp((t - t0) / max(1e-6, t1 - t0))


def ease_out(x):
    return 1 - (1 - x) ** 3


def ease_in_out(x):
    return 3 * x * x - 2 * x * x * x


def ease_back(x):
    c1, c3 = 1.70158, 2.70158
    return 1 + c3 * (x - 1) ** 3 + c1 * (x - 1) ** 2


def rgb(h, a=255):
    return art.hexc(h, a)


def text(img, xy, s, size, color=(255, 255, 255), alpha=1.0, anchor="mm", bold=True, shadow=True):
    if alpha <= 0:
        return
    layer = Image.new("RGBA", img.size, (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    f = font(size, bold)
    a = int(255 * clamp(alpha))
    if shadow:
        d.text((xy[0] + 3, xy[1] + 3), s, font=f, fill=(0, 0, 0, int(a * 0.6)), anchor=anchor)
    d.text(xy, s, font=f, fill=(*color[:3], a), anchor=anchor)
    img.alpha_composite(layer)


def paste(img, sprite, center, alpha=1.0):
    if alpha <= 0:
        return
    if alpha < 1:
        sprite = sprite.copy()
        sprite.putalpha(sprite.getchannel("A").point(lambda v: int(v * alpha)))
    x = int(center[0] - sprite.width / 2)
    y = int(center[1] - sprite.height / 2)
    img.alpha_composite(sprite, (x, y)) if 0 <= x and 0 <= y and x + sprite.width <= W and y + sprite.height <= H \
        else img.paste(sprite, (x, y), sprite)


_scaled = {}


def big(canvas_or_img, scale, key=None):
    """Nearest-neighbor upscale (cached) so pixel art stays crisp."""
    k = (key, scale) if key else None
    if k and k in _scaled:
        return _scaled[k]
    img = canvas_or_img.image(anchor=False) if isinstance(canvas_or_img, art.Canvas) else canvas_or_img
    out = img.resize((img.width * scale, img.height * scale), Image.NEAREST)
    if k:
        _scaled[k] = out
    return out


def caption(img, t, title, sub=None, t_in=0.0):
    a = ease_out(prog(t, t_in, t_in + 0.5))
    text(img, (W // 2, 70 - 20 * (1 - a)), title, 44, alpha=a)
    if sub:
        b = ease_out(prog(t, t_in + 0.3, t_in + 0.8))
        text(img, (W // 2, 122), sub, 24, (200, 190, 220), alpha=b, bold=False)


def vignette():
    y, x = np.mgrid[0:H, 0:W]
    d = np.sqrt(((x - W / 2) / (W / 2)) ** 2 + ((y - H / 2) / (H / 2)) ** 2)
    a = np.clip((d - 0.65) * 0.9, 0, 0.75)
    v = np.zeros((H, W, 4), dtype=np.uint8)
    v[..., 3] = (a * 255).astype(np.uint8)
    return Image.fromarray(v, "RGBA")


VIGNETTE = vignette()


# ----------------------------------------------------------------------------- sprites

def person(shirt, pants="#2f3a8f", skin="#c8996b", hair="#3b2a1a", zombie=False):
    c = art.Canvas(24)
    c.fill(c.rect(8, 0, 16, 8), rgb(skin))
    if not zombie:
        c.fill(c.rect(8, 0, 16, 2) | c.rect(8, 2, 9, 4) | c.rect(15, 2, 16, 3), rgb(hair))
    c.fill(c.rect(8, 8, 16, 15), rgb(shirt))
    if zombie:
        c.fill(c.rect(16, 9, 22, 11), rgb(skin))       # arms reaching forward
    else:
        c.fill(c.rect(6, 8, 8, 14) | c.rect(16, 8, 18, 14), rgb(shirt))
        c.fill(c.rect(6, 13, 8, 15) | c.rect(16, 13, 18, 15), rgb(skin))
    c.fill(c.rect(8, 15, 12, 22) | c.rect(12, 15, 16, 22), rgb(pants))
    c.light(0.2, 0.25)
    eye = rgb("#1b1225") if zombie else rgb("#ffffff")
    c.px[4, 13] = c.px[4, 14] = eye
    if not zombie:
        c.px[4, 14] = rgb("#3a6ad8")
    c.outline()
    return c


def crouched(sprite_canvas):
    img = sprite_canvas.image(anchor=False)
    return img.resize((img.width, int(img.height * 0.78)), Image.NEAREST)


PLAYER_A = person("#2fb7c8")
PLAYER_B = person("#f08a2a", pants="#4a3a2a")
PLAYER_C = person("#9a5ad8", pants="#2a2a2a")
ZOMBIE = person("#2fa3a6", skin="#5fae4f", zombie=True)


def block(kind, seed=0):
    rng = np.random.default_rng(seed)
    c = art.Canvas(16)
    base = {"stone": "#7d7d85", "dirt": "#7a5236", "grass": "#7a5236", "deep": "#4b4a55"}[kind]
    for y in range(16):
        for x in range(16):
            k = rng.uniform(-0.18, 0.15)
            c.px[y, x] = art.shade(rgb(base), k)
    if kind == "grass":
        for x in range(16):
            for y in range(int(3 + rng.integers(0, 2))):
                c.px[y, x] = art.shade(rgb("#4fa63a"), rng.uniform(-0.15, 0.15))
    return c.image(anchor=False)


def crack(stage, seed=5):
    """Breaking overlay, stage 0..9."""
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    rng = np.random.default_rng(seed)
    for i in range(stage + 1):
        x, y = 8, 8
        for _ in range(2 + i // 2):
            nx, ny = x + rng.integers(-4, 5), y + rng.integers(-4, 5)
            d.line([(x, y), (nx, ny)], fill=(20, 16, 24, 200))
            x, y = nx, ny
    return img


# ----------------------------------------------------------------------------- scenes
# Each scene: (start, end, draw(img, t_local)). Sound cues are listed separately.

EMBERS = np.random.default_rng(1).uniform(0, 1, (90, 4))


def embers(img, t, color="#ff9a2e", count=90):
    d = ImageDraw.Draw(img)
    for i in range(count):
        x0, phase, speed, size = EMBERS[i]
        y = H - ((t * (30 + 60 * speed) + phase * H) % (H + 40))
        x = x0 * W + 20 * math.sin(t * 1.3 + i)
        a = int(120 + 100 * math.sin(t * 3 + i))
        s = 2 + int(size * 3)
        d.rectangle([x, y, x + s, y + s], fill=rgb(color, max(30, a)))


def rings_disc(img, center, radii, t_grow=1.0, alpha=1.0):
    d = ImageDraw.Draw(img)
    for i in reversed(range(len(radii))):
        r = radii[i] * t_grow
        col = rgb(RING_COLORS[i])
        fill = art.shade(col, -0.62)
        d.ellipse([center[0] - r, center[1] - r, center[0] + r, center[1] + r],
                  fill=(*fill[:3], int(255 * alpha)), outline=(*col[:3], int(255 * alpha)), width=3)


def scene_title(img, t):
    embers(img, t)
    g = ease_out(prog(t, 0, 1.6))
    rings_disc(img, (W // 2, H // 2), [55, 110, 165, 225, 290], g, alpha=0.55)
    a = ease_out(prog(t, 1.0, 1.8))
    text(img, (W // 2, H // 2 - 30 + 30 * (1 - a)), "DISTANT FRONTIERS", 86, (255, 236, 200), a)
    b = ease_out(prog(t, 2.0, 2.6))
    text(img, (W // 2, H // 2 + 45), "a difficulty mod for All The Mods 10", 28, (230, 220, 245), b, bold=False)
    c = ease_out(prog(t, 2.6, 3.2))
    text(img, (W // 2, H // 2 + 85), "made for playing with (and against) your friends", 24, (190, 180, 210), c, bold=False)


RADII = [45, 95, 145, 200, 255]
MAP_C = (400, 435)


def scene_rings(img, t):
    caption(img, t, "The further from spawn, the worse it gets.", "Five rings around spawn. Home is safe. Out there, it isn't.")
    rings_disc(img, MAP_C, RADII)
    rng = np.random.default_rng(4)
    d = ImageDraw.Draw(img)
    # Elites get denser outward.
    for i, density in enumerate([0, 3, 7, 12, 20]):
        inner = RADII[i - 1] if i else 0
        for _ in range(density):
            ang = rng.uniform(0, 2 * math.pi)
            r = rng.uniform(inner + 8, RADII[i] - 8)
            x, y = MAP_C[0] + r * math.cos(ang), MAP_C[1] + r * math.sin(ang)
            pulse = 0.5 + 0.5 * math.sin(t * 4 + r)
            d.ellipse([x - 4, y - 4, x + 4, y + 4], fill=rgb("#ffd84a" if i < 3 else "#ff5a3a", int(140 + 100 * pulse)))
    # The player walks out.
    walk = ease_in_out(prog(t, 0.8, 6.0))
    px = MAP_C[0] + walk * (RADII[-1] + 40)
    paste(img, big(PLAYER_A, 2, "pa2"), (px, MAP_C[1] - 18))
    dist = walk * (RADII[-1] + 40)
    idx = next((i for i, r in enumerate(RADII) if dist <= r), 4)
    # Labels.
    for i, name in enumerate(RING_NAMES):
        lit = i == idx
        skulls = " " + "☠" * i if i else "  (safe)"
        text(img, (760, 230 + i * 56), name + skulls, 28 if lit else 23,
             rgb(RING_COLORS[i]) if lit else (150, 140, 165), 1.0, anchor="lm")
    # Current ring's emblem pops.
    since = 0.0
    for i, r in enumerate(RADII):
        if dist > (RADII[i - 1] if i else -1):
            since = (walk * (RADII[-1] + 40) - (RADII[i - 1] if i else 0)) / 60
    pop = ease_back(clamp(since * 2.5))
    emblem = art.EMBLEMS[idx][1]()
    size = max(1, int(4 * pop))
    paste(img, big(emblem, size, f"em{idx}"), (1000, 590))


def scene_dig(img, t):
    caption(img, t, "Hiding doesn't work anymore.", "Outside the Hearth, mobs sense you through walls and dig in.")
    tile = 64
    ground_y = 520
    for x in range(0, W, tile):
        img.paste(big(block("grass", x), 4, f"grass{x % 3}"), (x, ground_y))
        img.paste(big(block("dirt", x + 1), 4, f"dirt{x % 3}"), (x, ground_y + tile))
        img.paste(big(block("deep", x + 2), 4, f"deep{x % 3}"), (x, ground_y + 2 * tile))
    bx = 760
    walls = [(bx, ground_y - 3 * tile), (bx + tile, ground_y - 3 * tile), (bx + 2 * tile, ground_y - 3 * tile),
             (bx, ground_y - 2 * tile), (bx, ground_y - tile), (bx + 2 * tile, ground_y - 2 * tile),
             (bx + 2 * tile, ground_y - tile)]
    stone = big(block("stone", 9), 4, "stone")
    dig_start, per_block = 2.4, 1.3
    broken = int(clamp((t - dig_start) / per_block, 0, 2))
    gone = [walls[4], walls[3]][:broken]
    for w in walls:
        if w not in gone:
            img.paste(stone, w)
    inside = (bx + tile + tile // 2, ground_y - 48)
    paste(img, big(PLAYER_A, 4, "pa4"), inside)
    zx = 120 + ease_out(prog(t, 0.2, 2.3)) * (bx - 160) + (tile * 1.1 if t > dig_start + 2 * per_block else 0)
    paste(img, big(ZOMBIE, 4, "z4"), (zx, ground_y - 48))
    if dig_start <= t < dig_start + 2 * per_block:
        i = int((t - dig_start) // per_block)
        stage = int(((t - dig_start) % per_block) / per_block * 10)
        target = [walls[4], walls[3]][i]
        img.alpha_composite(big(crack(min(9, stage), seed=i), 4), target)
    if t > dig_start + 2 * per_block:
        a = ease_back(prog(t, dig_start + 2 * per_block, dig_start + 2 * per_block + 0.3))
        text(img, (inside[0], inside[1] - 80), "!", int(60 * max(0.1, a)), (255, 80, 80), 1.0)


ABILITIES = [
    ("WARPING", art.icon_warper, "Its hits teleport you - sometimes into your friend's place."),
    ("THIEF", art.icon_thief, "Steals from your hotbar and runs. Kill it to get it back."),
    ("MAGNETIC", art.icon_magnetic, "Drags everyone nearby toward it. Watch the sparks."),
    ("VOLATILE", art.icon_volatile, "Explodes a moment after it dies. Back off."),
    ("WARDED", art.icon_warded, "Barely hurt by whoever it's chasing. A friend has to hit it."),
]
CARD = 2.4


def scene_elites(img, t):
    caption(img, t, "Elites have abilities.", "Named, glowing, and built to mess with your group.")
    i = min(4, int(t // CARD))
    lt = t - i * CARD
    name, make_icon, desc = ABILITIES[i]
    a = ease_out(prog(lt, 0, 0.35))
    paste(img, big(make_icon(), 9, f"ab{i}"), (250, 360 - 20 * (1 - a)), a)
    text(img, (250, 470), name, 40, (255, 216, 74), a)
    text(img, (W // 2, 640), desc, 28, (235, 228, 245), a, bold=False)
    # Mini vignette on the right.
    cx, cy = 820, 380
    d = ImageDraw.Draw(img)
    d.rounded_rectangle([cx - 300, cy - 150, cx + 300, cy + 150], 18, fill=(30, 24, 42, 255), outline=(70, 60, 95, 255), width=3)
    pa, pb, z = big(PLAYER_A, 3, "pa3"), big(PLAYER_B, 3, "pb3"), big(ZOMBIE, 3, "z3")
    if name == "WARPING":
        s = prog(lt, 0.9, 1.1)
        left, right = (cx - 180, cy + 40), (cx + 180, cy + 40)
        ap, bp = (left, right) if s < 0.5 else (right, left)
        paste(img, z, (cx - 110, cy + 40))
        paste(img, pa, ap)
        paste(img, pb, bp)
        if 0.85 < lt < 1.4:
            for p in (left, right):
                for k in range(14):
                    ang = k / 14 * 2 * math.pi + lt * 6
                    rr = 40 * (lt - 0.85) + 10
                    d.rectangle([p[0] + rr * math.cos(ang), p[1] + rr * math.sin(ang) - 30,
                                 p[0] + rr * math.cos(ang) + 6, p[1] + rr * math.sin(ang) - 24], fill=rgb("#c58cff"))
    elif name == "THIEF":
        run = ease_in_out(prog(lt, 1.1, 2.2))
        paste(img, pa, (cx - 170, cy + 40))
        zx = cx - 90 + run * 330
        paste(img, z, (zx, cy + 40))
        f = ease_out(prog(lt, 0.5, 0.95))
        gx = (cx - 170) + f * (zx - (cx - 170)) if lt < 1.1 else zx
        gy = cy - 40 - 50 * math.sin(math.pi * f) if lt < 1.1 else cy - 10
        paste(img, big(art.icon_thief(), 3, "thief3"), (gx, gy))
    elif name == "MAGNETIC":
        pull = ease_out(prog(lt, 1.2, 1.7))
        paste(img, z, (cx, cy + 40))
        for p, sprite in [((cx - 230, cy + 40), pa), ((cx + 230, cy + 40), pb)]:
            x = p[0] + (cx - p[0]) * 0.65 * pull
            paste(img, sprite, (x, p[1] - 40 * math.sin(math.pi * pull)))
        if lt < 1.2:
            for k in range(20):
                ang = k / 20 * 2 * math.pi
                rr = 140 * (1 - prog(lt, 0.2, 1.2))
                d.rectangle([cx + rr * math.cos(ang), cy + rr * math.sin(ang), cx + rr * math.cos(ang) + 5,
                             cy + rr * math.sin(ang) + 5], fill=rgb("#7ff4ff"))
    elif name == "VOLATILE":
        paste(img, pa, (cx - 200, cy + 40))
        if lt < 0.6:
            paste(img, z, (cx + 40, cy + 40))
        elif lt < 1.4:
            fallen = z.rotate(-90, expand=True)
            flash = int((lt - 0.6) * 8) % 2 == 0
            paste(img, fallen, (cx + 40, cy + 80))
            if flash:
                d.ellipse([cx, cy + 40, cx + 80, cy + 120], fill=(255, 255, 255, 120))
        else:
            r = 40 + 200 * ease_out(prog(lt, 1.4, 1.8))
            al = int(255 * (1 - prog(lt, 1.6, 2.3)))
            d.ellipse([cx + 40 - r, cy + 80 - r, cx + 40 + r, cy + 80 + r], fill=(255, 170, 60, al // 2),
                      outline=(255, 230, 150, al), width=6)
    elif name == "WARDED":
        paste(img, pa, (cx - 150, cy + 40))
        paste(img, pb, (cx + 170, cy + 40))
        paste(img, z, (cx, cy + 40))
        if 0.4 < lt < 1.0:
            text(img, (cx - 70, cy - 40), "tink", 26, (180, 210, 255), 1 - prog(lt, 0.6, 1.0))
            for k in range(8):
                ang = k / 8 * 2 * math.pi
                d.line([cx - 55, cy + 10, cx - 55 + 22 * math.cos(ang), cy + 10 + 22 * math.sin(ang)], fill=rgb("#d9e8ff"), width=3)
        if 1.3 < lt < 2.2:
            text(img, (cx + 70, cy - 50 - 30 * prog(lt, 1.3, 2.2)), "-8", 34, (255, 90, 90), 1 - prog(lt, 1.8, 2.2))


def anim_frame(frames, t, fps=12, hold=True):
    i = int(t * fps)
    return frames[min(i, len(frames) - 1)] if hold else frames[i % len(frames)]


DOWN_FRAMES = art.anim_downed()
REVIVE_FRAMES = art.anim_revive()
HEX_FRAMES = art.anim_hex()


def scene_downed(img, t):
    caption(img, t, "Die near a friend and you go down instead.", "Friends crouch beside you to pull you back up. Mobs don't wait.")
    revived = t > 4.6
    frames = REVIVE_FRAMES if revived else DOWN_FRAMES
    f = anim_frame(frames, t - 4.6 if revived else t, fps=8)
    paste(img, big(f, 6), (300, 400))
    ground = 500
    d = ImageDraw.Draw(img)
    d.rectangle([520, ground, 1180, ground + 8], fill=(60, 50, 80, 255))
    a_img = big(PLAYER_A, 4, "pa4")
    if revived:
        paste(img, a_img, (760, ground - 48))
    else:
        lying = a_img.rotate(90, expand=True)
        paste(img, lying, (760, ground - 18))
        glow = int(80 + 60 * math.sin(t * 6))
        d.ellipse([690, ground - 40, 830, ground + 10], outline=(255, 60, 60, glow), width=4)
    walk = ease_out(prog(t, 0.8, 2.2))
    bx = 1120 - walk * 260
    crouch = 2.3 < t < 4.6
    b_img = crouched(PLAYER_B) if crouch else big(PLAYER_B, 1, None)
    b_img = b_img.resize((b_img.width * 4, b_img.height * 4), Image.NEAREST) if crouch else big(PLAYER_B, 4, "pb4")
    paste(img, b_img, (bx, ground - b_img.height / 2))
    if crouch:
        p = prog(t, 2.4, 4.5)
        d.rounded_rectangle([660, 300, 960, 330], 8, fill=(30, 24, 42, 255), outline=(90, 200, 110, 255), width=3)
        d.rounded_rectangle([664, 304, 664 + 292 * p, 326], 6, fill=(80, 220, 100, 255))
        text(img, (810, 280), "Reviving...", 24, (160, 240, 170), 1.0, bold=False)
    if revived:
        text(img, (810, 280), "Back up!", 34, (120, 255, 140), ease_out(prog(t, 4.6, 5.0)))


def scene_hex(img, t):
    caption(img, t, "Kill an elite... and you might get HEXED.", "Everything hunts you. Loot drops double. Survive it - or pass it on.")
    f = anim_frame(HEX_FRAMES, t, fps=8)
    paste(img, big(f, 6), (230, 400))
    d = ImageDraw.Draw(img)
    ground = 520
    d.rectangle([420, ground, 1220, ground + 8], fill=(60, 50, 80, 255))
    tagged = t > 4.7
    ax = 640 + ease_in_out(prog(t, 3.6, 4.6)) * 220
    bx = 960
    holder = (bx, ground - 48) if tagged else (ax, ground - 48)
    pulse = int(110 + 80 * math.sin(t * 8))
    d.ellipse([holder[0] - 70, holder[1] - 90, holder[0] + 70, holder[1] + 60], fill=(150, 60, 220, pulse // 3),
              outline=(200, 120, 255, pulse), width=4)
    paste(img, big(PLAYER_A, 4, "pa4"), (ax, ground - 48))
    paste(img, big(PLAYER_B, 4, "pb4"), (bx, ground - 48))
    # Mobs converge on whoever holds it.
    for k in range(5):
        side = -1 if k % 2 == 0 else 1
        start = holder[0] + side * (420 + 60 * k)
        mx = start + (holder[0] + side * (130 + 30 * k) - start) * ease_out(prog(t, 0.8 + 0.2 * k, 3.0 + 0.2 * k))
        if 400 < mx < W - 40:
            z = big(ZOMBIE, 3, "z3")
            paste(img, z if side < 0 else z.transpose(Image.FLIP_LEFT_RIGHT), (mx, ground - 36))
    if 4.5 < t < 5.2:
        p = prog(t, 4.5, 5.0)
        x = ax + (bx - ax) * p
        d.arc([ax, ground - 200, bx, ground], 200, 340, fill=(200, 120, 255, 255), width=6)
        d.ellipse([x - 10, ground - 140 - 40 * math.sin(math.pi * p), x + 10, ground - 120 - 40 * math.sin(math.pi * p)], fill=rgb("#ff5cf4"))
    if tagged:
        a = ease_back(prog(t, 4.7, 5.0))
        text(img, (bx, ground - 250), "TAG - YOU'RE IT", int(40 * max(0.2, a)), (230, 160, 255), 1.0)


def scene_end(img, t):
    embers(img, t + 20, "#c58cff", 60)
    for i, (name, make) in enumerate(art.EMBLEMS):
        a = ease_back(prog(t, 0.1 * i, 0.1 * i + 0.5))
        if a > 0:
            paste(img, big(make(), max(1, int(4 * a)), f"end{i}{int(4 * a)}"), (W // 2 + (i - 2) * 170, 220))
    text(img, (W // 2, 380), "DISTANT FRONTIERS", 70, (255, 236, 200), ease_out(prog(t, 0.6, 1.2)))
    text(img, (W // 2, 455), "One jar on your server. Your friends install nothing.", 30, (230, 220, 245),
         ease_out(prog(t, 1.2, 1.7)), bold=False)
    text(img, (W // 2, 520), "/rings help", 34, (255, 216, 74), ease_out(prog(t, 1.8, 2.3)))


SCENES = [
    (0.0, 4.5, scene_title),
    (4.5, 11.5, scene_rings),
    (11.5, 17.5, scene_dig),
    (17.5, 17.5 + 5 * CARD, scene_elites),
    (29.5, 35.5, scene_downed),
    (35.5, 42.0, scene_hex),
    (42.0, 47.5, scene_end),
]
DURATION = SCENES[-1][1]

# (time, sound, gain)
CUES = [(0.3, "ring/deeper", 0.9),
        (5.7, "ring/deeper", 0.3), (6.9, "ring/deeper", 0.35), (8.2, "ring/deeper", 0.4), (9.7, "ring/deeper", 0.5),
        (17.5 + 0 * CARD + 0.95, "warper/warp", 0.9),
        (17.5 + 1 * CARD + 0.5, "thief/steal", 0.9),
        (17.5 + 2 * CARD + 0.0, "magnetic/charge", 0.7), (17.5 + 2 * CARD + 1.2, "magnetic/pulse", 0.9),
        (17.5 + 3 * CARD + 0.6, "volatile/fuse", 0.6),
        (17.5 + 4 * CARD + 0.45, "warded/deflect", 0.9),
        (29.6, "downed/fall", 0.9), (34.1, "downed/revive", 0.9),
        (35.6, "hex/curse", 0.8), (37.6, "hex/ambush", 0.5), (40.2, "hex/pass", 0.9),
        (42.2, "elite/champion_slain", 0.8)]


def dig_hits():
    """Block-hit clicks while the zombie digs, and two breaks."""
    cues = []
    for i in range(2):
        start = 11.5 + 2.4 + i * 1.3
        for k in range(5):
            cues.append((start + k * 0.25, "hit", 0.45))
        cues.append((start + 1.28, "break", 0.7))
    return cues


def hit_sound(kind):
    if kind == "hit":
        x = snd.bandpass(snd.noise(0.07, 21), 300, 2500) * snd.decay(0.07, 0.015)
        x += snd.osc(snd.glide(220, 120, 0.07), 0.07) * snd.decay(0.07, 0.02) * 0.6
    else:
        x = snd.lowpass(snd.noise(0.35, 22), 1800) * snd.decay(0.35, 0.08)
        for k in range(6):
            x = snd.place(x, snd.bandpass(snd.noise(0.05, 30 + k), 600, 4000) * snd.decay(0.05, 0.01) * 0.5, 0.03 * k)
    return x / (np.max(np.abs(x)) + 1e-9)


def music(duration):
    """A quiet bed: minor pad + bass, with a pluck arpeggio in the middle section."""
    sr = snd.SR
    total = np.zeros(int(sr * duration) + sr)
    chords = [["A2", "C3", "E3"], ["F2", "A2", "C3"], ["C3", "E3", "G3"], ["G2", "B2", "D3"]]
    bar = 3.0
    t = 0.0
    k = 0
    while t < duration:
        chord = chords[k % 4]
        pad = sum(snd.osc(snd.note(n), bar + 0.6, "saw") + snd.osc(snd.note(n) * 1.004, bar + 0.6, "saw") for n in chord)
        pad = snd.lowpass(pad, 700) * snd.adsr(bar + 0.6, 0.8, 0.4, 0.8, 0.9) * 0.05
        bass = snd.osc(snd.note(chord[0]) / 2, bar, "tri") * snd.adsr(bar, 0.05, 0.6, 0.5, 0.4) * 0.12
        total = snd.place(total, pad + np.pad(bass, (0, len(pad) - len(bass))), t)
        if 11.5 <= t < 42:
            step = bar / 8
            for j in range(8):
                n = chord[j % 3]
                pl = snd.pluck(snd.note(n) * (2 if j % 4 < 2 else 4), 0.4, tau=0.12, bright=0.2) * 0.045
                total = snd.place(total, pl, t + j * step)
        t += bar
        k += 1
    return total[: int(sr * duration)]


def build_audio(path):
    sr = snd.SR
    mix = music(DURATION)
    cache = {}
    for at, name, gain in CUES + dig_hits():
        if name not in cache:
            cache[name] = hit_sound(name) if name in ("hit", "break") else snd.SOUNDS[name]()
        mix = snd.place(mix, cache[name] * gain * 0.8, at)
    mix = mix[: int(sr * DURATION)]
    fade = int(sr * 1.5)
    mix[-fade:] *= np.linspace(1, 0, fade)
    mix = mix / (np.max(np.abs(mix)) + 1e-9) * 0.89
    import soundfile as sf
    sf.write(path, np.stack([mix, mix], axis=1).astype(np.float32), sr)


def render_frame(t):
    img = Image.new("RGBA", (W, H), (*BG, 255))
    for start, end, draw in SCENES:
        if start <= t < end:
            draw(img, t - start)
            # Quick fade at scene edges.
            edge = min(t - start, end - t)
            if edge < 0.25:
                fade = Image.new("RGBA", (W, H), (*BG, int(255 * (1 - edge / 0.25))))
                img.alpha_composite(fade)
            break
    img.alpha_composite(VIGNETTE)
    return img.convert("RGB")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--frames", help="comma-separated times in seconds to dump as PNGs instead of rendering")
    parser.add_argument("--out", default="media/trailer.mp4")
    args = parser.parse_args()

    if args.frames:
        os.makedirs("/tmp/claude-0/frames", exist_ok=True)
        for s in args.frames.split(","):
            render_frame(float(s)).save(f"/tmp/claude-0/frames/f_{float(s):05.1f}.png")
        print("wrote preview frames")
        return

    import imageio_ffmpeg
    ffmpeg = imageio_ffmpeg.get_ffmpeg_exe()
    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    wav = "/tmp/claude-0/trailer_audio.wav"
    build_audio(wav)
    proc = subprocess.Popen([ffmpeg, "-y", "-loglevel", "error",
                             "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", f"{W}x{H}", "-r", str(FPS), "-i", "-",
                             "-i", wav, "-c:v", "libx264", "-preset", "medium", "-crf", "22", "-pix_fmt", "yuv420p",
                             "-c:a", "aac", "-b:a", "160k", "-shortest", "-movflags", "+faststart", args.out],
                            stdin=subprocess.PIPE)
    frames = int(DURATION * FPS)
    for i in range(frames):
        proc.stdin.write(render_frame(i / FPS).tobytes())
        if i % 150 == 0:
            print(f"  frame {i}/{frames}", flush=True)
    proc.stdin.close()
    proc.wait()
    print("wrote", args.out, f"({os.path.getsize(args.out) / 1e6:.1f} MB, {DURATION:.1f}s)")


if __name__ == "__main__":
    main()
