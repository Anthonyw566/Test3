#!/usr/bin/env python3
"""Writes the GameTest arena structure: a 16x8x16 box with a stone floor.

Run from the repo root: python3 tools/make_arena.py
Output: src/main/resources/data/furtherout/structure/arena.nbt
"""
import gzip
import io
import struct

SIZE_X, SIZE_Y, SIZE_Z = 16, 8, 16
DATA_VERSION = 3955  # Minecraft 1.21.1

TAG_END, TAG_INT, TAG_STRING, TAG_LIST, TAG_COMPOUND = 0, 3, 8, 9, 10


def w_str(out, s):
    b = s.encode("utf-8")
    out.write(struct.pack(">H", len(b)))
    out.write(b)


def w_named(out, tag, name):
    out.write(struct.pack(">b", tag))
    w_str(out, name)


def w_int(out, name, value):
    w_named(out, TAG_INT, name)
    out.write(struct.pack(">i", value))


def w_string(out, name, value):
    w_named(out, TAG_STRING, name)
    w_str(out, value)


def w_int_list(out, name, values):
    w_named(out, TAG_LIST, name)
    out.write(struct.pack(">bi", TAG_INT, len(values)))
    for v in values:
        out.write(struct.pack(">i", v))


def w_compound_list(out, name, items):
    """items: list of callables that write the *contents* of each compound."""
    w_named(out, TAG_LIST, name)
    out.write(struct.pack(">bi", TAG_COMPOUND if items else TAG_END, len(items)))
    for write in items:
        write(out)
        out.write(struct.pack(">b", TAG_END))


def main():
    palette = ["minecraft:stone", "minecraft:air"]
    blocks = []
    for x in range(SIZE_X):
        for y in range(SIZE_Y):
            for z in range(SIZE_Z):
                state = 0 if y == 0 else 1
                blocks.append((x, y, z, state))

    out = io.BytesIO()
    w_named(out, TAG_COMPOUND, "")
    w_int(out, "DataVersion", DATA_VERSION)
    w_int_list(out, "size", [SIZE_X, SIZE_Y, SIZE_Z])
    w_compound_list(out, "palette", [
        (lambda name: lambda o: w_string(o, "Name", name))(n) for n in palette
    ])
    w_compound_list(out, "blocks", [
        (lambda b: lambda o: (w_int_list(o, "pos", [b[0], b[1], b[2]]), w_int(o, "state", b[3])))(b)
        for b in blocks
    ])
    w_compound_list(out, "entities", [])
    out.write(struct.pack(">b", TAG_END))

    path = "src/main/resources/data/furtherout/structure/arena.nbt"
    with gzip.open(path, "wb") as f:
        f.write(out.getvalue())
    print(f"wrote {path} ({len(blocks)} blocks)")


if __name__ == "__main__":
    main()
