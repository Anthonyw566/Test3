# Further Out

A server-side difficulty mod for **All The Mods 10** (NeoForge, Minecraft 1.21.1),
made for a small group of friends.

**Monsters get tougher the further you go from spawn, and the further out
you are, the more you need each other.**

## Install

1. Download `furtherout-*.jar` from the
   [latest release](https://github.com/Anthonyw566/Test3/releases/tag/latest).
2. Put it in your **server's** `mods/` folder and restart.
3. That's it. Players don't install anything.

To try it alone first, drop the same jar into your ATM10 *client's* `mods/`
folder and open a singleplayer world. `/rings elite warper` spawns an elite
to meet; open the world to LAN with a friend to try Downed and Marked.

In game, `/rings help` lists the rules and `/rings` tells you where you stand.

### Optional sound pack
When players join, the server offers a tiny pack (about 100 KB) with seven
soft sounds: the danger level changing, a heartbeat while you're down, being
marked. Everything else uses vanilla sounds on purpose. Players who decline
hear vanilla sounds instead, and nothing breaks. You can turn it off, make it
required, or self-host it under `resourcePack` in `mechanics.json`.

## How it plays

### Danger levels
Distance from spawn sets the danger level. When it changes, a single line
shows above your hotbar ("Danger level 2") with a soft sound.

| Level | Distance | What changes |
|---|---|---|
| **Safe area** | 0–400 | No monsters spawn and nothing digs. |
| **1** | 400–1,200 | Monsters a little tougher, a few dig, the first elites. |
| **2** | 1,200–2,800 | More diggers, all five abilities, the first champions. |
| **3** | 2,800–5,600 | Tougher again, champions more common. |
| **4** | 5,600+ | The toughest. Warps can pull you into the Nether for a moment. |

The Nether counts as level 3 and the End as level 4. Other dimensions
(mining dimensions etc.) are left alone. Spawner farms are never touched.

### Digging
Outside the safe area, some monsters dig through stone and dirt to reach a
player they can't get to. You can't hide in a hole in the ground. You'll
hear the cracking first.

They only dig **natural terrain** (stone, dirt, sand, gravel, netherrack and
so on). They never break anything you'd build with, and they never dig
within 8 blocks of a bed, chest, furnace or machine. Each one gives up after
12 blocks.

### Elites
A few monsters spawn as elites, with an ability in front of their name, like
*Warping Husk*. Look at one to read it. Champions have two abilities. Each
ability shows itself before it acts and has a counter:

| Ability | What it does | Counter |
|---|---|---|
| **Warping** | Swirls purple when charged. A charged hit teleports you: up into the air, a few blocks away, or **swapped with a nearby friend**. | Block the hit with a shield, or back off while it swirls. It needs 12 s to recharge. |
| **Thieving** | Grabs one stackable item from your hotbar and runs, glowing and shedding crumbs of it. It never takes what's in your hand, and never tools, weapons, armour or totems. | Chase it down. The item drops when it dies and can't burn or despawn. |
| **Magnetic** | Clicks, draws sparks to everyone it can see, then reels them in. | Get behind a block when you hear the click. |
| **Volatile** | Hisses like a creeper for two seconds after it dies, then explodes. Blocks are never broken, and the damage is capped. | Step back. |
| **Warded** | Barely hurt by the player it's chasing, **but only while a second player is close by**. | Someone else hits it. Solo players fight a normal mob. |

Elites drop extra loot from their danger level's loot table (champions
roll twice) and bonus XP.

### Downed, not dead
If you'd die while a friend is within 64 blocks, you go **down** instead.
You crawl and glow, and you can't fight, eat or build. A friend **crouching
next to you for 4 seconds** helps you up. A red boss bar shows your 45
seconds to you and to everyone close enough to help. You'll hear your
heartbeat. Hits still land while you're down: each costs time and interrupts
a revive. Hold sneak to give up. If you're alone, fall into the void or use
`/kill`, you just die.

### Marked
Killing an elite can mark you (a champion always does). For 2½ minutes:
- you glow, and monsters nearby come for you
- a couple of small groups find you
- **everything you kill drops double**

A purple boss bar shows the time left. If it runs out while you're away
from spawn, you get some XP and loot. Or **hit another player** to hand it
over; they can't pass it straight back. Going home always works, but the
mark pays nothing there. If you die marked, it moves to the nearest player.

### When you die
Away from spawn, your things don't scatter. A weak zombie **wearing your
head and your name** holds everything you dropped. It never despawns,
doesn't burn in daylight, and stays near where you died. Kill it and
everything spills out, and those items can't burn or despawn. Anyone can
kill it, friend or not. After you respawn, one line tells you where it is.
In the safe area, things drop the vanilla way.

## Commands

| Command | Who | |
|---|---|---|
| `/rings` | everyone | Your danger level, distance, time left on a mark |
| `/rings help` | everyone | The rules, one line each |
| `/rings elite [entity] [abilities…\|champion]` | op | Spawn a test elite, e.g. `/rings elite minecraft:skeleton warper` |
| `/rings mark <player> [seconds]` / `unmark` | op | Hand out or clear a mark |
| `/rings down <player>` | op | Practice revives |
| `/rings inspect` | op | Nearest mob's tier, abilities, digging budget |
| `/rings boundary` | op | Particles along the nearest danger level edge |
| `/rings reload` | op | Reload both config files and report problems |

## Config

`config/furtherout/` is written on first start; reload it with `/rings reload`.

- **`rings.json`**: the distance bands, how tough monsters are in each, elite,
  champion and digger chances, which abilities appear, elite loot tables,
  which dimension counts as which level, scaling caps, excluded spawn types.
- **`mechanics.json`**: every number for every ability, digging, Downed and
  Marked. Only write the keys you want to change; everything else uses the
  defaults.
- Which blocks can be dug is the block tag `furtherout:diggable`, which a
  datapack can change.

Typos are reported in the server log and by `/rings reload`, and never crash
the server. A bad value falls back to its default.

### Tuning cheat-sheet

| If it feels like… | Change |
|---|---|
| Too much digging | `rings.json`: lower `mobs.digChance`, or `mechanics.json`: `digging.maxBlocksPerMob` |
| Diggers get too close to bases | `digging.baseRadius` (default 8) |
| Elites everywhere, or too rare | `elites.eliteChance` and `championChance` per level |
| Downed is too forgiving or too harsh | `downed.bleedOutSeconds`, `reviveSeconds`, `rescueRange` |
| Marked is too much | `marked.durationSeconds`, `marked.ambushEverySeconds`, `marked.ambushBaseSize` |
| Warpers too chaotic | `warper.cooldownSeconds`, or set `swapWeight` to 0 to stop friend swaps |
| Volatile hits too hard | `volatile.maxDamage` |
| You'd rather have vanilla item drops on death | `"keeper": { "enabled": false }` |
| You don't want the sound pack offered | `mechanics.json`: `"resourcePack": { "enabled": false }` |
| An ATM10 dimension should be dangerous | `rings.json`: `dimensionRings`, e.g. `"allthemodium:the_other": "level3"` |

## Development

```
./run-tests.sh                # core rule tests (no Minecraft needed)
./gradlew build               # the mod jar -> build/libs/
./gradlew runGameTestServer   # in-game tests on a headless server
```

- `core/` holds the rules without Minecraft: config parsing and validation,
  danger level lookup, Downed timers, Marked rules, dig rules, ability rolls,
  magnet math. All of it is covered by JUnit tests.
- `src/main/java/.../gametest/` holds the in-game tests. They spawn real mobs
  and mock survival players and check things like: a downed player gets
  helped up, a shield stops a warp, a wall stops a magnet, a digger leaves
  planks and chests alone, and the volatile blast never one-shots.
- CI (`.github/workflows/build.yml`) runs everything on every push and
  publishes the jar and the sound pack as the `latest` pre-release.
- `tools/` regenerates the pack's sounds and icon from code (see
  [`tools/README.md`](tools/README.md)).

See [`DESIGN.md`](DESIGN.md) for why it works the way it does.
