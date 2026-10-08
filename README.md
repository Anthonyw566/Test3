# Distant Frontiers

A server-side difficulty mod for **All The Mods 10** (NeoForge, Minecraft 1.21.1),
built for a small group of friends.

**The further you go from spawn, the worse it gets — and the more your friends matter.**
Sometimes they'll save you. Sometimes they'll hand you a curse and run.

## Install

1. Download `distantfrontiers-*.jar` from the
   [latest release](https://github.com/Anthonyw566/Test3/releases/tag/latest).
2. Put it in your **server's** `mods/` folder and restart.
3. Done. Players **don't install anything** — every effect uses vanilla
   titles, particles, sounds and chat.

Want to try it alone first? Drop the same jar into your ATM10 *client's*
`mods/` folder and open a singleplayer world. Use `/rings elite warper` to
meet an elite, or open the world to LAN with a friend to try Downed and the Hex.

Type `/rings help` in game for the rules, `/rings` to see where you are.

## How it plays

### The rings
Distance from spawn sets the danger. Crossing a boundary shows a title.

| Ring | Distance | What changes |
|---|---|---|
| **The Hearth** | 0–400 | Safe. No hostiles spawn, nothing digs in, the Hex is paused. |
| **The Verge** | 400–1,200 | Mobs a bit tougher, 1 in 4 diggers, first elites. |
| **The Wildmarch** | 1,200–2,800 | Half the diggers, all abilities, champions appear. |
| **The Duskreach** | 2,800–5,600 | Most mobs dig, elites and champions are common. |
| **The Ashenfront** | 5,600+ | Everything digs. Warpers can rip you into the Nether. |

The Nether counts as the Duskreach and the End as the Ashenfront. Other
dimensions (mining dims etc.) are untouched.

### Hiding doesn't work anymore
Outside the Hearth, many mobs **dig through walls** to reach you. They can
sense you through blocks, you'll hear the cracking, and the blocks drop as
items. They never break chests or machines, obsidian, or anything inside the
Hearth, and each mob can only dig so far. Spawner farms aren't affected.

### Elites
Some mobs spawn as named **elites** (yellow) or **champions** (gold, glowing,
two abilities). The name tells you what it does — *"Karok the Warping"*.

| Ability | What it does |
|---|---|
| **Warping** | Its hits can teleport you: flung into the sky, **swapped with a nearby friend**, or thrown sideways. Rarely, deep out, it rips you into the Nether. |
| **Thief** | Steals an item from your hotbar and runs. Kill it and the item drops (glowing and indestructible). |
| **Magnetic** | Sparks, a hum… then it yanks everyone nearby toward itself. |
| **Volatile** | Explodes a moment after it dies. Back off. Breaks no blocks. |
| **Warded** | Barely hurt by whoever it's chasing. **Someone else has to hit it.** |

Elites drop loot from their ring's loot table (champions roll twice) and bonus XP.

### Downed, not dead
If you'd die while a friend is within 64 blocks, you go **down** instead: you
crawl, glow, and can't fight, eat, or build. A friend **crouching next to you
for 4 seconds** pulls you back up. You have 45 seconds — but mobs keep
hitting downed players, each hit costs time, and getting hit stops the revive.
Hold crouch to give up. Alone, the void, or `/kill`: you just die.

### The Hex
Kill an elite and its dying curse may land on you (champions always curse).
While **hexed**: you glow, every hostile nearby comes for you, ambushes keep
arriving, and **everything you kill drops double**.

- Survive the 4-minute timer → XP and a loot roll.
- Or **punch a friend** to pass it on. No tag-backs for 10 seconds.
- The timer freezes in the Hearth. You can't wait it out at home — but you
  *can* come home and smack whoever's building.
- Die hexed and it jumps to the nearest player.

## Commands

| Command | Who | |
|---|---|---|
| `/rings` | everyone | Your ring, distance, danger, Hex timer |
| `/rings help` | everyone | The rules in six lines |
| `/rings elite [entity] [abilities…\|champion]` | op | Spawn a test elite, e.g. `/rings elite minecraft:skeleton warper` |
| `/rings hex <player> [seconds]` / `unhex` | op | Hand out / clear the Hex |
| `/rings down <player>` | op | Practice revives |
| `/rings inspect` | op | Nearest mob's tier, abilities, digger budget |
| `/rings boundary` | op | Particle arc on the nearest ring edge |
| `/rings reload` | op | Reload both config files, report problems |

## Config

`config/distantfrontiers/` (written on first start, reload with `/rings reload`):

- **`rings.json`** — ring sizes, names, colors, how tough mobs are, elite /
  champion / digger chances, which abilities appear, elite loot tables, which
  dimension uses which ring, scaling caps, excluded spawn types.
- **`mechanics.json`** — every number for every ability, digging, Downed and
  the Hex. Only write the keys you want to change; the rest use defaults.

Typos are reported in the server log and by `/rings reload` and never crash
the server. A bad value falls back to its default.

### Tuning cheat-sheet (the knobs that matter most)

| Feels like… | Change |
|---|---|
| Too much digging | `rings.json` → lower `mobs.digChance` per ring, or `mechanics.json` → `digging.senseRange` |
| Bases near the edge of the Hearth get wrecked | Make the Hearth bigger (`outerRadius` of `hearth`) |
| Elites everywhere / too rare | `elites.eliteChance` and `championChance` per ring |
| Downed is too forgiving / too harsh | `downed.bleedOutSeconds`, `reviveSeconds`, `rescueRange` |
| The Hex is too brutal | `hex.durationSeconds`, `hex.ambushEverySeconds`, `hex.ambushBaseSize` |
| Nobody ever wants the Hex | raise `hex.survivalXp`, keep `doubleDrops` on |
| Warpers too chaotic | `warper.procChance`, or set `swapWeight` to 0 to stop friend-swaps |
| An ATM10 dimension should be dangerous | `rings.json` → `dimensionRings`, e.g. `"allthemodium:the_other": "duskreach"` |

## Development

```
./run-tests.sh            # core rule tests (no Minecraft needed)
./gradlew build           # the mod jar -> build/libs/
./gradlew runGameTestServer   # in-game tests on a headless server
```

- `core/` holds the engine-free rules: config parsing and validation, ring
  lookup, Downed timers, Hex rules, dig rules, ability rolls, magnet math.
  70 JUnit tests.
- `src/main/java/.../gametest/` holds 21 in-game tests. They spawn real
  mobs and mock survival players, then check that a downed player gets
  revived, the Hex passes on a punch, a warper swaps two players, a thief's
  loot drops when it dies, a digger breaks into a bunker (but not in the
  Hearth), natural spawns are scaled exactly once, and so on.
- CI (`.github/workflows/build.yml`) runs everything on every push and
  publishes the jar as the `latest` pre-release.

See [`DESIGN.md`](DESIGN.md) for why it works the way it does.
