# Further Out — Design

## The idea

ATM10 gets easy: once you have good gear, monsters are background noise and
any hole in the ground is a fortress. This mod makes distance from spawn the
difficulty dial, and it makes the *group* the unit of survival.

## Principles

1. **New situations, not bigger numbers.** Scaling is modest and capped at
   2x. Challenge comes from abilities, digging and decisions.
2. **Every mechanic creates a moment between players**: a rescue, a swap,
   a hand-off, a "get over here and hit it".
3. **Readable and counterable.** Every ability shows itself before it acts
   (particles plus a recognisable vanilla sound), and each has a counter:
   a shield, cover, distance, a friend.
4. **Challenging, never a chore.** Apply the "after the tenth time" test.
   Setbacks are short and recoverable. Rare things stay rare. Nothing wrecks
   a base, nothing takes your tools, and nothing traps you.
5. **Quiet presentation.** Sound, particles and mob behaviour first, text
   last. There are no titles, broadcasts, lore or invented names. The only
   chat lines are one-time tips.

## The systems

| System | Rule | The moment it creates |
|---|---|---|
| **Danger levels** | Distance from spawn sets the level; the safe area is safe | "Do we push past level 3 tonight?" |
| **Night** | Every level's edge pulls in by a fifth after dark | "It's getting dark, head back before it gets worse" |
| **Digging** | Monsters dig natural terrain to reach you (never near a base) | Hearing cracking through the wall of your hidey-hole |
| **Noise** | Explosions and fights bring idle monsters over to look | Blasting a cave open is a decision, not a free action |
| **Warping** | A charged hit teleports you: up, aside, or into a friend's place | Your friend is suddenly in your fight |
| **Thieving** | Takes one stackable hotbar item and runs | A three-person chase through a cave |
| **Magnetic** | Telegraphed pull of everyone it can see | The group gets clumped… next to the Volatile |
| **Volatile** | Explodes two seconds after death | "Back off!" |
| **Warded** | Shrugs off its target while a second player is near | "I can't hurt it, you hit it" |
| **Warped Ender Pearl** | Rare champion drop: swap with whatever is closest to where it lands | "Get me out of here" / "Why am I next to a creeper" |
| **Downed** | Die near a friend: crawl, bleed out, get helped up | Rescues through a crowd of monsters |
| **Marked** | Elite kills can mark you; hit a player to pass it | Greed versus safety, hot potato |
| **Keeper** | Die away from spawn: a weak zombie in your head keeps your things | "That's me over there. Hit it." |
| **Bait** | A rare diamond on a cave floor that twitches, then becomes a monster | "Don't touch it, it moved" |
| **Alone in the dark** | Rare sounds behind a lone player, which only they hear | "Did you hear that?" "Hear what?" |

### How they interact
- A marked player draws monsters, which pulls them off a downed friend, or onto one.
- A mark moves to the nearest player on death, so you can stand next to the marked player to protect them, or keep your distance.
- Warping swaps put a friend into an ambush, or pull them away from a revive.
- Magnetic clumps the group together, and Volatile punishes clumps.
- Warded only works in groups, so groups need to coordinate.
- Digging means the group can't wait out a mark in a sealed hole.
- A Volatile's blast is noise too, so the fight you just won draws the next one.
- A Warped Ender Pearl thrown next to a downed friend swaps them out of the crowd, and puts you into it.
- Being marked at night means more monsters to lure, at a higher level.
- If you die, a friend can kill your keeper and hold your things for you, or not.

## Guard rails
- **Safe area**: no natural monsters and no digging. A mark still runs out
  there but pays nothing, so going home is always an option.
- **Digging** only takes blocks in `furtherout:diggable` (natural terrain).
  It never touches block entities and never digs within 8 blocks of one (a
  bed, chest, furnace or machine), and each mob has a 12-block budget.
- **Warping** recharges for 12 seconds, and a shield stops it. It never
  affects creative, spectator or downed players. Tosses need open sky.
  Nether rifts only happen at level 4 and pull you back after 20 seconds.
  Thieving and Warping never roll together.
- **Thieving** never takes the held item or anything unstackable. The thief
  can't despawn while carrying, and the loot is indestructible when it drops.
- **Volatile** damage is capped at 8 (before armour) and breaks no blocks.
- **Downed** never intercepts void deaths, `/kill`, or anyone alone.
  Logging out while down counts as giving up.
- **Noise** only moves monsters that have nothing better to do. A player's
  fights make noise at most every 5 seconds, and the safe area is silent.
- **Sounds in the dark** need real darkness, nobody within 48 blocks and
  distance from spawn, and come 8 to 20 minutes apart. Nothing is ever
  spawned with them.
- **Bait** only replaces a cave spawn (no sky) at level 2 or deeper, with a
  player within 32 blocks, at 0.4%. It always gives the bait back: the
  monster drops it. Creepers are never used, because they would blow up
  the bait.
- **Keepers** are weak (10 health, 1 damage) so you can win bare-handed,
  never despawn, burn or drown, and stay within a few blocks of where you
  died. They only take what's left after other mods (grave mods) have had
  their pick, and do nothing with keepInventory or in the void.
- **Farms**: spawners, spawn eggs, commands, breeding and conversions are
  never scaled, promoted or made to dig.

## What was cut
- An earlier draft had heat, a currency, a shop, contracts, surge nights and
  supply caches. All of it was bookkeeping rather than moments between
  players.
- A later version had named rings with flavour text, big animated titles,
  generated elite names and server-wide announcements. That was too loud for
  a pack that otherwise feels like Minecraft.

All of it is in git history.

## Architecture
- `core/` is pure Java with no Minecraft. It holds config parsing and
  validation plus every rule (`DownedState`, `MarkRules`, `DigRules`,
  `KeeperRules`, `NoiseRules`, `MimicRules`, `DarkSoundRules`,
  `PearlRules`, warp rolls, magnet math, danger level lookup with night),
  and it is unit-tested.
- `src/main/java/.../` is thin NeoForge event glue:
  - `mob/`: spawning, elites, abilities, the dig and investigate goals,
    noise, bait, the Warped Ender Pearl, rewards
  - `player/`: Downed, Marked, Nether rifts, keepers, sounds in the dark
  - `ring/`: danger level lookup and the indicator
  - `command/`: `/rings`
  - `fx/`: sounds and the optional pack
  - A single `ServerTicker` drives all periodic work.
- No mixins, no registries, no network channels. That keeps it server-only
  and compatible with 400+ mods.
- `gametest/` holds in-game tests on a headless server, run in CI on every push.
