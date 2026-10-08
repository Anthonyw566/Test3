# Distant Frontiers — Design

## The pitch

ATM10 gets easy: once you have good gear, mobs are background noise and any
dirt hut is a fortress. This mod makes distance from spawn the difficulty
dial, and it makes the *group* the unit of survival. Your friends can save
you, and your friends can ruin your day.

## Principles

1. **Harder means new situations, not bigger numbers.** Ring scaling is
   modest and capped (×2 max). The challenge comes from abilities, digging,
   and decisions.
2. **Every mechanic is a social mechanic.** Each one should create a moment
   *between players*: a rescue, a betrayal, a "get over here and hit it".
3. **Legible.** Names state abilities, auras show them, every activation has
   a sound, the first hit explains itself in chat. Nobody should die and ask
   "what was that?".
4. **Small.** A few systems that interact beat many that don't. No currency,
   no shop, no quest log, no custom items, nothing to install client-side.

## The systems

| System | One-line rule | The moment it creates |
|---|---|---|
| **Rings** | Distance from spawn sets danger; the Hearth is safe | "Do we push into the Duskreach tonight?" |
| **Diggers** | Mobs you can't reach dig to you (not in the Hearth, never chests/machines) | Hearing cracking through the wall of your "safe" hole |
| **Warper** | Hits teleport you — skyward, sideways, or *into your friend's place* | Your friend is suddenly in your fight, and you're in their lava parkour |
| **Thief** | Steals a hotbar item and flees | A three-person chase through a cave after someone's pickaxe |
| **Magnetic** | Telegraphed pull of everyone nearby | The group gets clumped… next to the Volatile |
| **Volatile** | Explodes after death | Who has the reach weapon? Everyone else, back off |
| **Warded** | Barely hurt by the one it's chasing | Solo players can't brute-force it. Call a friend |
| **Downed** | Die near a friend → crawl, bleed out, get revived by a crouch | Rescues through a mob crowd, or deciding not to |
| **The Hex** | Elite kills can curse you; punch a friend to pass it; double loot while held | Greed vs safety, hot potato, revenge |

### How they interact (the point of keeping the set small)
- Hexed players **lure** every mob, so they draw fire off a downed friend, or
  onto one.
- A Hex **jumps on death** to the nearest player. Stand next to the hexed guy
  and protect him, or keep your distance.
- Warper swaps put a friend into a Hex ambush, or pull them away from a revive.
- Magnetic clumps the group, and Volatile punishes clumps.
- Warded makes solo hunting slow, so the group goes out together, so Downed
  matters.
- Digging means the group can't turtle up in a pillbox to wait out a Hex.

## Guard rails (fun, not miserable)
- **Hearth**: no natural hostiles, no digging, Hex frozen. Home is always home.
- **Diggers** never touch block entities (chests, machines, storage), anything
  harder than 5.0, the blacklist, or the Hearth, and each mob has a 32-block
  lifetime budget. Broken blocks drop.
- **Warper** never warps creative/spectator/downed players. Tosses need open
  sky (otherwise sideways), and Nether rifts look for a safe landing.
  Thief + Warper never roll together (an unwinnable chase).
- **Thief** loot is indestructible and glowing when dropped, and drops when
  the thief is killed *or* removed in any other way.
- **Downed** never intercepts void or `/kill`, or anyone alone. Logging out
  while down counts as giving up.
- **Farms**: spawners, spawn eggs, commands, breeding and conversions are
  never scaled, promoted or made to dig.

## What was cut (and why)
An earlier draft had Heat, a Marks currency, a shop, a contract board,
charters, surge nights and supply caches. They were reasonable ideas, but
each one added bookkeeping and menus rather than moments between players.
They're in git history if a later version wants one back.

## Possible next steps (only if the group asks)
- **Nemesis**: a champion that kills a player keeps its name, gains an ability
  and comes back.
- **Outposts**: a second Hearth-like safe point the group has to build and defend.
- **One more ability per ring tier**, if five gets stale.
- Tuning pass after real play: dig chances, bleed-out time, Hex duration.

## Architecture
- `core/`: pure Java, no Minecraft. It holds config parsing and validation
  plus every rule (`DownedState`, `HexRules`, `DigRules`, warp rolls, magnet
  math, ring lookup) and is unit-tested.
- `src/main/java/.../`: thin NeoForge event glue. `mob/` covers spawning,
  elites, abilities, the dig goal and rewards. `player/` covers Downed and
  the Hex. `ring/` covers lookup and titles. `command/` holds `/rings`. One
  `ServerTicker` drives all periodic work.
- No mixins, no registries, no network channels. That keeps it server-only
  and maximally compatible with 400+ mods.
- `gametest/`: in-game tests on a headless server, run in CI on every push.
