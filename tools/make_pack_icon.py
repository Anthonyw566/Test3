#!/usr/bin/env python3
"""Draws the optional pack's icon: a top-down patch of ground that turns from
grass near the middle to stone and then deepslate toward the edge - danger
rising with distance, in Minecraft's own palette.

Usage (needs pillow, numpy):
    python3 tools/make_pack_icon.py
"""
import numpy as np
from PIL import Image

SIZE = 64
SCALE = 2  # 128x128 file, crisp 2x2 pixels
RINGS = [  # (outer radius in pixels, base colour)
    (9, (95, 159, 53)),     # grass
    (17, (121, 85, 58)),    # dirt
    (24, (125, 125, 125)),  # stone
    (30, (84, 84, 90)),     # deepslate
    (99, (44, 42, 52)),     # dark
]


def main():
    rng = np.random.default_rng(7)
    img = np.zeros((SIZE, SIZE, 3), dtype=np.uint8)
    c = (SIZE - 1) / 2
    for y in range(SIZE):
        for x in range(SIZE):
            # A little jitter on the edges so the bands look like ground, not a target.
            r = np.hypot(x - c, y - c) + rng.uniform(-1.2, 1.2)
            base = next(col for radius, col in RINGS if r <= radius)
            shade = rng.uniform(0.86, 1.08)
            img[y, x] = [min(255, int(v * shade)) for v in base]
    Image.fromarray(img).resize((SIZE * SCALE, SIZE * SCALE), Image.NEAREST).save("resourcepack/pack.png")
    print("wrote resourcepack/pack.png")


if __name__ == "__main__":
    main()
