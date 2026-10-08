#!/usr/bin/env python3
"""Draws all Distant Frontiers pixel art and writes the resource-pack font
sheets: inline icons (16px), ring emblems (32px) and title flipbook frames
(32px), plus the pack icon.

Everything is drawn procedurally at native resolution with hard edges, an
automatic dark outline and top-left lighting, so it reads as Minecraft-style
pixel art.

Usage (needs pillow, numpy):
    python3 tools/make_art.py [--preview preview.png]
Writes into resourcepack/ and prints the glyph code points the mod uses.
"""
import argparse
import json
import math
import os

import numpy as np
from PIL import Image, ImageDraw

PACK = "resourcepack"
TEX = os.path.join(PACK, "assets/distantfrontiers/textures/font")
FONT = os.path.join(PACK, "assets/distantfrontiers/font")

OUTLINE = (27, 18, 37, 255)


def hexc(h, a=255):
    h = h.lstrip("#")
    return (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16), a)


def shade(c, k):
    """Lighten (k>0) or darken (k<0) a color."""
    r, g, b, a = c
    if k > 0:
        return (int(r + (255 - r) * k), int(g + (255 - g) * k), int(b + (255 - b) * k), a)
    return (int(r * (1 + k)), int(g * (1 + k)), int(b * (1 + k)), a)


class Canvas:
    """A tiny RGBA pixel canvas with hard-edged shapes."""

    def __init__(self, size):
        self.size = size
        self.px = np.zeros((size, size, 4), dtype=np.uint8)

    # -- shapes (every shape is a boolean mask over pixel centers)
    def grid(self):
        y, x = np.mgrid[0:self.size, 0:self.size] + 0.5
        return x, y

    def fill(self, mask, color):
        self.px[mask] = color

    def disc(self, cx, cy, r):
        x, y = self.grid()
        return (x - cx) ** 2 + (y - cy) ** 2 <= r * r

    def ellipse(self, cx, cy, rx, ry):
        x, y = self.grid()
        return ((x - cx) / rx) ** 2 + ((y - cy) / ry) ** 2 <= 1

    def rect(self, x0, y0, x1, y1):
        x, y = self.grid()
        return (x >= x0) & (x < x1) & (y >= y0) & (y < y1)

    def poly(self, points):
        img = Image.new("L", (self.size, self.size), 0)
        ImageDraw.Draw(img).polygon(points, fill=255)
        return np.array(img) > 0

    def line(self, points, width=1):
        img = Image.new("L", (self.size, self.size), 0)
        ImageDraw.Draw(img).line(points, fill=255, width=width)
        return np.array(img) > 0

    def implicit(self, fn):
        x, y = self.grid()
        return fn(x, y)

    # -- finishing passes
    def opaque(self):
        return self.px[..., 3] > 0

    def light(self, amount=0.35, dark=0.3, skip=()):
        """Top/left edges catch light, bottom/right edges fall into shadow."""
        o = self.opaque()
        out = self.px.copy()
        s = self.size
        for yy in range(s):
            for xx in range(s):
                if not o[yy, xx] or tuple(self.px[yy, xx]) in skip:
                    continue
                up = yy == 0 or not o[yy - 1, xx]
                left = xx == 0 or not o[yy, xx - 1]
                down = yy == s - 1 or not o[yy + 1, xx]
                right = xx == s - 1 or not o[yy, xx + 1]
                c = tuple(int(v) for v in self.px[yy, xx])
                if up or left:
                    out[yy, xx] = shade(c, amount)
                elif down or right:
                    out[yy, xx] = shade(c, -dark)
        self.px = out

    def outline(self, color=OUTLINE):
        o = self.opaque()
        grow = o.copy()
        grow[1:, :] |= o[:-1, :]
        grow[:-1, :] |= o[1:, :]
        grow[:, 1:] |= o[:, :-1]
        grow[:, :-1] |= o[:, 1:]
        self.px[grow & ~o] = color

    def glow(self, color, radius=2, alpha=90):
        """Soft halo behind existing pixels (used for the Hex and emblems)."""
        o = self.opaque()
        halo = np.zeros_like(o)
        for dy in range(-radius, radius + 1):
            for dx in range(-radius, radius + 1):
                if dx * dx + dy * dy <= radius * radius:
                    halo |= np.roll(np.roll(o, dy, 0), dx, 1)
        add = halo & ~o
        self.px[add] = (*color[:3], alpha)

    def image(self, anchor=True):
        img = Image.fromarray(self.px, "RGBA")
        if anchor:
            # Near-invisible pixel in the last column: Minecraft sizes glyphs by their
            # rightmost visible column, so this keeps every frame the same width and
            # flipbook animations from jittering sideways.
            img.putpixel((self.size - 1, 0), (0, 0, 0, 1))
        return img


# ============================================================================ 16px icons

def icon_warper():
    c = Canvas(16)
    c.fill(c.disc(8, 8, 7), hexc("#4b1a7a"))
    for i in range(140):
        t = i / 140 * 4.2 * math.pi
        r = 0.6 + t * 0.48
        x, y = 8 + r * math.cos(t), 8 + r * math.sin(t)
        if 0 <= x < 16 and 0 <= y < 16 and r < 6.5:
            col = hexc("#e7c8ff") if r < 3 else hexc("#b56bff")
            c.px[int(y), int(x)] = col
    c.fill(c.disc(8, 8, 1.2), hexc("#ffffff"))
    c.outline()
    return c


def icon_thief():
    c = Canvas(16)
    # A swag sack, cinched at the neck, with a stolen coin spilling out.
    c.fill(c.ellipse(7.5, 10.5, 5.5, 4.5), hexc("#9a6a3a"))
    c.fill(c.poly([(5, 3), (10, 3), (9, 7), (6, 7)]), hexc("#9a6a3a"))
    c.fill(c.rect(5, 6, 10, 7.5), hexc("#e3c27a"))
    c.light(0.35, 0.35)
    c.outline()
    c.fill(c.disc(12.5, 12.5, 2.4), hexc("#ffd84a"))
    c.px[11, 12] = hexc("#fff7c2")
    c.px[13, 14] = hexc("#c48a1c")
    for x, y in [(11, 10), (14, 10), (15, 12), (15, 13), (14, 15), (11, 15), (10, 13), (10, 12)]:
        if c.px[y, x, 3] == 0:
            c.px[y, x] = OUTLINE
    return c


def icon_magnetic():
    c = Canvas(16)
    outer = c.disc(8, 7, 6.5) & ~c.disc(8, 7, 3) & c.rect(0, 0, 16, 9)
    legs_l = c.rect(1.5, 7, 5, 14)
    legs_r = c.rect(11, 7, 14.5, 14)
    c.fill(outer | legs_l | legs_r, hexc("#d42c2c"))
    c.fill(c.rect(1.5, 11, 5, 14) | c.rect(11, 11, 14.5, 14), hexc("#d7dde6"))
    c.light()
    c.outline()
    for x, y in [(0, 15), (15, 15), (7, 14), (8, 15)]:
        c.px[y, x] = hexc("#7ff4ff")
    return c


def icon_volatile():
    c = Canvas(16)
    c.fill(c.disc(7, 9.5, 5.5), hexc("#3a3550"))
    c.fill(c.rect(9, 3, 12, 6), hexc("#6b6585"))
    c.light(0.4, 0.35)
    c.outline()
    c.fill(c.line([(12, 3), (13, 1)]), hexc("#b07b4a"))
    c.px[0, 14] = hexc("#ffffff")
    c.px[1, 13] = c.px[0, 13] = hexc("#ffd84a")
    c.px[1, 15] = hexc("#ff9a2e")
    c.px[7, 5] = hexc("#8d86a8")
    return c


def icon_warded():
    c = Canvas(16)
    c.fill(c.poly([(2, 2), (14, 2), (14, 8), (8, 14.5), (2, 8)]), hexc("#4c6f91"))
    c.fill(c.poly([(4, 4), (12, 4), (12, 8), (8, 12), (4, 8)]), hexc("#8fb6d9"))
    c.fill(c.ellipse(8, 7, 2.6, 1.6), hexc("#ffffff"))
    c.fill(c.disc(8, 7, 0.9), hexc("#1b3a5a"))
    c.light(0.3, 0.25, skip=[hexc("#ffffff"), hexc("#1b3a5a")])
    c.outline()
    return c


def icon_hex():
    c = Canvas(16)
    c.fill(c.ellipse(8, 7, 6, 5.5), hexc("#e9dcff"))
    c.fill(c.rect(5, 10, 11, 14), hexc("#e9dcff"))
    c.fill(c.ellipse(5.5, 7.5, 1.7, 1.9) | c.ellipse(10.5, 7.5, 1.7, 1.9), hexc("#8f3fe0"))
    c.fill(c.poly([(8, 9), (7, 11), (9, 11)]), hexc("#4b1a7a"))
    c.light(0.2, 0.3, skip=[hexc("#8f3fe0"), hexc("#4b1a7a")])
    for x in (6, 8, 10):
        c.px[13, x] = hexc("#4b1a7a")
    c.outline()
    c.glow(hexc("#b56bff"), 1, 110)
    return c


def heart(c, cx, cy, s, color):
    def fn(x, y):
        u, v = (x - cx) / s, -(y - cy) / s
        return (u * u + v * v - 1) ** 3 - u * u * v ** 3 <= 0
    c.fill(c.implicit(fn), color)


def icon_downed():
    c = Canvas(16)
    heart(c, 8, 8, 6.2, hexc("#d42c2c"))
    c.light(0.35, 0.3)
    crack = c.line([(8, 3), (6, 6), (9, 9), (7, 13)], 1)
    c.px[crack & c.opaque()] = (0, 0, 0, 0)
    c.outline()
    return c


def icon_revive():
    c = Canvas(16)
    heart(c, 8, 8, 6.2, hexc("#3fcf5a"))
    c.light(0.4, 0.3)
    c.fill(c.rect(7, 4, 9, 11) | c.rect(4.5, 6.5, 11.5, 8.5), hexc("#ffffff"))
    c.outline()
    return c


def icon_elite():
    c = Canvas(16)
    pts = []
    for i in range(10):
        r = 7.2 if i % 2 == 0 else 3.2
        a = -math.pi / 2 + i * math.pi / 5
        pts.append((8 + r * math.cos(a), 8.5 + r * math.sin(a)))
    c.fill(c.poly(pts), hexc("#ffd84a"))
    c.light(0.4, 0.3)
    c.outline()
    return c


def icon_champion():
    c = Canvas(16)
    c.fill(c.poly([(2, 5), (5, 9), (8, 3), (11, 9), (14, 5), (13, 13), (3, 13)]), hexc("#ffb72e"))
    c.fill(c.rect(3, 11, 13, 13), hexc("#d4881a"))
    for x in (5, 8, 11):
        c.px[12, x] = hexc("#ff4d6d")
    c.light(0.4, 0.3)
    c.outline()
    return c


def icon_skull_small():
    """Danger rating pip."""
    c = Canvas(16)
    c.fill(c.ellipse(8, 7, 5, 4.5), hexc("#d9d2e3"))
    c.fill(c.rect(5.5, 9, 10.5, 13), hexc("#d9d2e3"))
    c.fill(c.disc(6, 7.5, 1.4) | c.disc(10, 7.5, 1.4), hexc("#2a2433"))
    c.light(0.2, 0.3, skip=[hexc("#2a2433")])
    c.outline()
    return c


ICONS = [
    ("WARPER", icon_warper), ("THIEF", icon_thief), ("MAGNETIC", icon_magnetic),
    ("VOLATILE", icon_volatile), ("WARDED", icon_warded), ("HEX", icon_hex),
    ("DOWNED", icon_downed), ("REVIVE", icon_revive), ("ELITE", icon_elite),
    ("CHAMPION", icon_champion), ("DANGER", icon_skull_small),
]


# ============================================================================ 32px emblems

def emblem_hearth():
    c = Canvas(32)
    c.fill(c.poly([(4, 16), (16, 5), (28, 16)]), hexc("#3fb34f"))          # roof
    c.fill(c.rect(21, 6, 25, 13), hexc("#7a5a44"))                         # chimney
    c.fill(c.rect(7, 15, 25, 28), hexc("#c99a6b"))                         # walls
    c.fill(c.rect(13.5, 19, 18.5, 28), hexc("#ff9a2e"))                    # glowing door
    c.fill(c.rect(9, 18, 12, 21) | c.rect(20, 18, 23, 21), hexc("#ffd84a"))
    c.light(0.3, 0.3)
    c.outline()
    for i, (x, y) in enumerate([(23, 3), (25, 1), (22, 0)]):
        c.px[y, x] = hexc("#cfcfcf", 200 - 50 * i)
    c.glow(hexc("#ffb347"), 1, 70)
    return c


def emblem_verge():
    c = Canvas(32)
    for i, a in enumerate(range(-160, -10, 25)):
        r = math.radians(a)
        c.fill(c.line([(16 + 9 * math.cos(r), 19 + 9 * math.sin(r)),
                       (16 + 14 * math.cos(r), 19 + 14 * math.sin(r))], 2), hexc("#ffe27a"))
    c.fill(c.disc(16, 19, 7.5) & c.rect(0, 0, 32, 19), hexc("#ffcc33"))
    c.light(0.35, 0.2)
    c.fill(c.rect(2, 19, 30, 21), hexc("#c48a1c"))
    c.fill(c.rect(5, 23, 27, 24), hexc("#8a5f14"))
    c.fill(c.rect(9, 26, 23, 27), hexc("#5c3f0e"))
    c.outline()
    return c


def emblem_wildmarch():
    c = Canvas(32)
    for dx in (-7, 0, 7):
        c.fill(c.poly([(13 + dx, 3), (17 + dx, 3), (19 + dx, 29), (15 + dx, 22)]), hexc("#f0a830"))
    c.light(0.45, 0.35)
    c.outline()
    c.glow(hexc("#ff7a1a"), 1, 70)
    return c


def emblem_duskreach():
    c = Canvas(32)
    moon = c.disc(16, 16, 12) & ~c.disc(21, 12, 10)
    c.fill(moon, hexc("#e0404a"))
    c.light(0.35, 0.35)
    c.fill(c.ellipse(19, 18, 6, 3), hexc("#ffe6c9"))
    c.fill(c.ellipse(19, 18, 1.4, 2.6), hexc("#2a0a10"))
    c.outline()
    c.glow(hexc("#ff3b4a"), 1, 80)
    return c


def emblem_ashenfront():
    c = Canvas(32)
    c.fill(c.ellipse(16, 15, 10.5, 9.5), hexc("#d8cfc6"))
    c.fill(c.rect(10, 20, 22, 28), hexc("#d8cfc6"))
    c.fill(c.ellipse(11.5, 15, 3, 3.4) | c.ellipse(20.5, 15, 3, 3.4), hexc("#2a0a10"))
    c.fill(c.poly([(16, 18), (14, 22), (18, 22)]), hexc("#2a0a10"))
    for x in (12, 15, 18, 21):
        c.fill(c.rect(x, 25, x + 1, 28), hexc("#6b5d55"))
    c.light(0.2, 0.35, skip=[hexc("#2a0a10")])
    c.fill(c.disc(11.5, 15.5, 1) | c.disc(20.5, 15.5, 1), hexc("#ff5a1f"))
    c.outline()
    for x, y, col in [(5, 6, "#ff9a2e"), (27, 4, "#ffd84a"), (3, 18, "#ff5a1f"), (28, 21, "#ff9a2e"), (8, 2, "#ff5a1f")]:
        c.px[y, x] = hexc(col)
    c.glow(hexc("#ff3b1f"), 1, 70)
    return c


EMBLEMS = [("hearth", emblem_hearth), ("verge", emblem_verge), ("wildmarch", emblem_wildmarch),
           ("duskreach", emblem_duskreach), ("ashenfront", emblem_ashenfront)]


# ============================================================================ flipbooks

def scaled(src, scale, flash=0.0, alpha=1.0):
    """Re-render a canvas at a different scale (nearest neighbor), centered, with optional white flash."""
    s = src.size
    img = Image.fromarray(src.px, "RGBA")
    n = max(1, int(round(s * scale)))
    img = img.resize((n, n), Image.NEAREST)
    out = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    off = (s - n) // 2
    if n > s:
        img = img.crop((-off, -off, -off + s, -off + s))
        off = 0
    out.paste(img, (off, off), img)
    arr = np.array(out).astype(float)
    if flash > 0:
        arr[..., :3] = arr[..., :3] * (1 - flash) + 255 * flash
    arr[..., 3] *= alpha
    c = Canvas(s)
    c.px = arr.clip(0, 255).astype(np.uint8)
    return c


def anim_emblem(make):
    """Ring entry: the emblem punches in, flashes, and settles."""
    base = make()
    return [scaled(base, 0.35, alpha=0.5), scaled(base, 0.7, alpha=0.85), scaled(base, 1.15, flash=0.7),
            scaled(base, 0.95, flash=0.25), base]


def anim_hex():
    """The Hex lands: smoke gathers into a skull whose eyes ignite."""
    frames = []
    rng = np.random.default_rng(3)
    skull = Canvas(32)
    skull.fill(skull.ellipse(16, 14, 10, 9), hexc("#efe4ff"))
    skull.fill(skull.rect(10.5, 19, 21.5, 27), hexc("#efe4ff"))
    skull.fill(skull.ellipse(11.5, 14.5, 3, 3.3) | skull.ellipse(20.5, 14.5, 3, 3.3), hexc("#3a1460"))
    skull.fill(skull.poly([(16, 18), (14, 21), (18, 21)]), hexc("#3a1460"))
    for x in (13, 16, 19):
        skull.fill(skull.rect(x, 24, x + 1, 27), hexc("#7a5aa0"))
    skull.light(0.15, 0.3, skip=[hexc("#3a1460")])
    skull.outline()
    for step in range(7):
        c = Canvas(32)
        # Swirling smoke that tightens toward the center.
        for _ in range(60):
            a = rng.uniform(0, 2 * math.pi)
            r = rng.uniform(3, 15) * (1 - step / 9)
            x, y = int(16 + r * math.cos(a)), int(15 + r * math.sin(a))
            if 0 <= x < 32 and 0 <= y < 32:
                c.px[y, x] = hexc("#8f3fe0", int(rng.uniform(90, 200)))
        if step >= 2:
            alpha = min(1.0, (step - 1) / 4)
            body = skull.px.copy().astype(float)
            body[..., 3] *= alpha
            m = body[..., 3] > 0
            c.px[m] = body[m].astype(np.uint8)
        if step >= 5:
            eyes = c.disc(11.5, 14.5, 1.5) | c.disc(20.5, 14.5, 1.5)
            c.fill(eyes, hexc("#ff5cf4") if step == 5 else hexc("#c93cff"))
            c.glow(hexc("#b56bff"), 1 if step == 5 else 2, 120 if step == 5 else 80)
        frames.append(c)
    return frames


def anim_downed():
    """Going down: the heart beats once, cracks, and splits."""
    frames = []
    for step, (size, gap) in enumerate([(10, 0), (12, 0), (10, 0), (10.5, 0), (10, 1), (10, 2)]):
        c = Canvas(32)
        heart(c, 16, 16, size, hexc("#d42c2c"))
        c.light(0.35, 0.3)
        if step >= 3:
            crack = c.line([(16, 6), (13, 12), (18, 17), (14, 23), (16, 28)], 1 + (step >= 4))
            c.px[crack & c.opaque()] = (0, 0, 0, 0)
        if gap:
            left = c.px[:, :16].copy()
            right = c.px[:, 16:].copy()
            c.px[:] = 0
            c.px[1:, max(0, 0 - gap):16 - gap] = left[:-1, gap:] if gap else left[:-1]
            c.px[1:, 16 + gap:] = right[:-1, :16 - gap]
        c.outline()
        frames.append(c)
    return frames


def anim_revive():
    """Back up: the heart mends with a burst of light."""
    frames = []
    for step in range(6):
        c = Canvas(32)
        heart(c, 16, 16, 9 + min(step, 3) * 0.6, hexc("#3fcf5a"))
        c.light(0.4, 0.3)
        if step < 2:
            crack = c.line([(16, 6), (13, 12), (18, 17), (14, 23), (16, 28)], 1)
            c.px[crack & c.opaque()] = (0, 0, 0, 0)
        c.fill(c.rect(14.5, 10, 17.5, 21) | c.rect(10.5, 14, 21.5, 17), hexc("#ffffff"))
        c.outline()
        if step >= 2:
            r = 4 + step * 2.2
            for i in range(10):
                a = i / 10 * 2 * math.pi + step
                x, y = int(16 + r * math.cos(a)), int(16 + r * math.sin(a))
                if 0 <= x < 32 and 0 <= y < 32:
                    c.px[y, x] = hexc("#fff7a8", max(60, 255 - step * 35))
        frames.append(c)
    return frames


# ============================================================================ sheets + font json

def sheet(canvases, cell, cols):
    rows = (len(canvases) + cols - 1) // cols
    img = Image.new("RGBA", (cell * cols, cell * rows), (0, 0, 0, 0))
    for i, c in enumerate(canvases):
        img.paste(c.image(anchor=cell >= 32), ((i % cols) * cell, (i // cols) * cell))
    return img


def chars(start, count, cols):
    out, code = [], start
    for r in range((count + cols - 1) // cols):
        row = ""
        for col in range(cols):
            idx = r * cols + col
            row += chr(code + idx) if idx < count else "\u0000"
        out.append(row)
    return out


def pack_icon():
    img = Image.new("RGBA", (128, 128), (20, 14, 28, 255))
    d = ImageDraw.Draw(img)
    colors = ["#7a1020", "#d42c2c", "#f0a830", "#ffd84a", "#3fb34f"]
    for i, col in enumerate(colors):
        r = 60 - i * 12
        d.ellipse([64 - r, 64 - r, 64 + r, 64 + r], fill=hexc(col))
    hearth = emblem_hearth().image(anchor=False).resize((40, 40), Image.NEAREST)
    img.paste(hearth, (44, 42), hearth)
    return img


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--preview")
    args = parser.parse_args()
    os.makedirs(TEX, exist_ok=True)
    os.makedirs(FONT, exist_ok=True)

    icons = [make() for _, make in ICONS]
    emblems = [make() for _, make in EMBLEMS]
    flip_emblems = [f for _, make in EMBLEMS for f in anim_emblem(make)]
    hexf, downf, revf = anim_hex(), anim_downed(), anim_revive()
    anim = flip_emblems + hexf + downf + revf

    sheet(icons, 16, 8).save(os.path.join(TEX, "icons.png"))
    sheet(emblems, 32, 8).save(os.path.join(TEX, "emblems.png"))
    sheet(anim, 32, 8).save(os.path.join(TEX, "anim.png"))
    pack_icon().save(os.path.join(PACK, "pack.png"))

    providers_icons = [{"type": "bitmap", "file": "distantfrontiers:font/icons.png",
                        "ascent": 7, "height": 8, "chars": chars(0xE000, len(icons), 8)}]
    providers_big = [
        {"type": "bitmap", "file": "distantfrontiers:font/emblems.png",
         "ascent": 14, "height": 16, "chars": chars(0xE100, len(emblems), 8)},
        {"type": "bitmap", "file": "distantfrontiers:font/anim.png",
         "ascent": 14, "height": 16, "chars": chars(0xE200, len(anim), 8)},
    ]
    with open(os.path.join(FONT, "icons.json"), "w") as f:
        json.dump({"providers": providers_icons}, f, indent=2, ensure_ascii=False)
    with open(os.path.join(FONT, "big.json"), "w") as f:
        json.dump({"providers": providers_big}, f, indent=2, ensure_ascii=False)

    # The mod's Glyphs class mirrors this layout.
    print("icons  (font distantfrontiers:icons):", {n: hex(0xE000 + i) for i, (n, _) in enumerate(ICONS)})
    print("emblems(font distantfrontiers:big):  ", {n: hex(0xE100 + i) for i, (n, _) in enumerate(EMBLEMS)})
    print("anim   (font distantfrontiers:big):   emblem reveals 0xe200 (5 per ring),",
          f"hex {hex(0xE200 + len(flip_emblems))} x{len(hexf)},",
          f"downed {hex(0xE200 + len(flip_emblems) + len(hexf))} x{len(downf)},",
          f"revive {hex(0xE200 + len(flip_emblems) + len(hexf) + len(downf))} x{len(revf)}")

    if args.preview:
        rows = [icons, emblems] + [anim[i:i + 8] for i in range(0, len(anim), 8)]
        scale, pad = 6, 8
        width = max(len(r) * (r[0].size * scale + pad) for r in rows) + pad
        height = sum(r[0].size * scale + pad for r in rows) + pad
        prev = Image.new("RGBA", (width, height), (40, 44, 52, 255))
        y = pad
        for r in rows:
            x = pad
            for c in r:
                img = c.image(anchor=False).resize((c.size * scale, c.size * scale), Image.NEAREST)
                prev.paste(img, (x, y), img)
                x += c.size * scale + pad
            y += r[0].size * scale + pad
        prev.save(args.preview)
        print("preview:", args.preview)


if __name__ == "__main__":
    main()
