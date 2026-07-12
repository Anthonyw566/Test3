# Distant Frontiers

Distance-based difficulty rings, elite enemies, expedition Heat, and a contract
board for a private **All The Mods 10** server (NeoForge / Minecraft 1.21.1).

The further you travel from spawn, the more dangerous — and more rewarding —
the world becomes. Home stays safe; the frontier does not.

**Read [`DESIGN.md`](DESIGN.md) first.** It is the full gameplay/scope design
and lists the decisions awaiting approval before full implementation.

## Status

Design review + Stage 0/1 scaffold. Implemented so far:

- Data-driven ring config (`config/distantfrontiers/rings.json`, generated on
  first run) with validation and `/rings reload`
- Ring boundary detection with entry titles, flavor text, sounds and a danger
  readout
- Hearth safe-zone hostile spawn suppression
- Ring-based mob stat scaling with hard caps and farm-proof exclusions
  (spawners, summons, machines, bosses)
- Elite/Champion promotion rolls: generated names whose epithet telegraphs the
  modifier, champion glow, category-based pairing rules (behaviors land in
  Stage 2)
- Commands: `/rings info | list | zoneat`, and for ops
  `/rings reload | inspect | debug boundaries`

Server-side only: players do **not** install anything. Drop the built jar into
the server's `mods/` folder.

## Building

```
gradle build        # jar lands in build/libs/
gradle runServer    # dev server for testing
```

Requires Java 21. Built against NeoForge 21.1.x with ModDevGradle.

> Note: the scaffold has not been compile-verified yet — the build needs
> network access to `maven.neoforged.net`, `libraries.minecraft.net`,
> `piston-meta.mojang.com` and `piston-data.mojang.com`. Compile verification
> is the first step of implementation Stage 0/1 once those hosts are
> reachable from the build environment.
