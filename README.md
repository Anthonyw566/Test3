# Distant Frontiers

Distance-based difficulty rings, elite enemies, expedition Heat, and a contract
board for a private **All The Mods 10** server (NeoForge / Minecraft 1.21.1).

The further you travel from spawn, the more dangerous — and more rewarding —
the world becomes. Home stays safe; the frontier does not.

**Read [`DESIGN.md`](DESIGN.md)** for the full gameplay design and tuning
rationale.

## What v1 includes

- **Five data-driven rings** (The Hearth → The Verge → The Wildmarch → The
  Duskreach → The Ashenfront) with entry fanfare, safe-zone spawn suppression,
  first-discovery announcements and uncharted-territory warnings.
- **Capped mob scaling** by ring, with farm-proof exclusions (spawner,
  summoned and machine mobs never scale, never pay).
- **Elites & Champions**: six telegraphed modifiers (Swift, Stonehide,
  Summoner, Blinkstep, Corrosive, Vengeful) with category pairing rules,
  generated names whose epithet states the threat, glowing champions, ambient
  particle telegraphs and kill announcements.
- **Expedition Heat**: builds in Ring 2+, jumps on elite kills. High Heat
  brings warned ambushes, then a named Hunter. Field Marks bank only at home —
  with up to +50% bonus scaled by Heat on arrival. Death banks half, no bonus.
- **Marks economy**: one virtual currency, daily diminishing returns on kill
  trickle, no physical token to dupe or automate.
- **The Contract Board** (`/rings board`): three rotating daily bounties
  (a named quarry that materializes near hunters in its ring), guarded supply
  caches with vanilla loot tables, and one-time **ring Charters** that unlock
  shop tiers and pay everyone online.
- **Expedition Broker** (`/rings shop`): config-driven stock (repair kits,
  rations, enchanting bundles, a Cache Map that generates an adventure in your
  current ring).
- **Surge nights**: 35% chance each dusk that one ring surges until dawn —
  double elites, 1.5× Marks, announced server-wide.

## Commands

Players: `/rings info · list · zoneat <x> <z> · board · accept <id> · shop ·
buy <offer> · heat`
Admins: `/rings reload · setorigin · inspect · simulate elite|champion ·
surge <ring>|stop · marks give <player> <amt> · heatset <value> ·
charter <ring> · bounties reroll · debug boundaries`

## Configuration

Generated on first run under `config/distantfrontiers/`:

- `rings.json` — radii, names, colors, mob multipliers, elite chances, heat
  rates, scaling caps, exclusions
- `contracts.json` — bounty mob pool, cache loot tables, charter targets
- `shop.json` — broker stock, prices, tier gating

All hot-reloadable with `/rings reload` (validation errors are reported and
the previous config stays active).

## Deployment

Server-side only: drop the built jar into the server's `mods/` folder.
Players install **nothing** — every UI element is chat, titles, action bar,
sounds and particles.

## Building

```
gradle build        # jar lands in build/libs/
gradle runServer    # dev server for testing
```

Requires Java 21. Built against NeoForge 21.1.x with ModDevGradle.

> Note: not yet compile-verified — the build needs network access to
> `maven.neoforged.net`, `libraries.minecraft.net`, `piston-meta.mojang.com`
> and `piston-data.mojang.com`, which this workspace's network policy
> currently blocks. First build will likely surface a handful of mapping-name
> fixes; the architecture does not depend on them.
