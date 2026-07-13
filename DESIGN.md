# Distant Frontiers — Design & Scope Document

*A distance-based difficulty and expedition mod for a private 3–5 player All The Mods 10 server (NeoForge 1.21.1).*

**Status: DESIGN FOR REVIEW.** Base scaffold code exists (see `src/`), full implementation starts after this document is approved. Section 21 lists the decisions that need a human yes/no.

---

## 0. What I changed from the brief, and why

The brief was treated as a rough draft. These are the significant deviations, all in service of *fun per hour of development*:

1. **Added an extraction/tension system ("Heat") as the flagship mechanic.** The brief's ring system makes the world *harder* with distance, but hardness alone isn't fun — the games that nail this feeling (Deep Rock Galactic, Lethal Company, extraction shooters) all share one loop: *the longer you stay out and the greedier you get, the spicier it gets — and you only bank your winnings when you make it home.* Heat is cheap to implement (one number per player) and generates the single best co-op moment there is: "one more cache… no, we should go back. GO BACK." Details in §3.
2. **Merged bounties, objectives, and quests into ONE system: Contracts on a board.** The brief had objectives, bounties, and progression as parallel systems. One board, three contract flavors (rotating bounties, cache recoveries, one-time ring Charters). One system to build, one place for players to look.
3. **Ambushes are driven by Heat, not by random timers.** Random ambush events punish players arbitrarily. Heat-driven ambushes are *legible* ("we're at Hunted, of course we got jumped") and *player-controlled* (you can always choose to bank and reset). Same content, better feeling.
4. **Cut elite tiers from four to two** (Elite, Champion). Four tiers for a 5-player server is inventory bloat. Nemesis returns later as a *system*, not a tier (§17).
5. **Killing elites raises your Heat.** One rule, three wins: farming self-limits without hard caps, expeditions naturally escalate to a climax, and "we just killed six elites" *feels* like the world should be angry at you.
6. **v1 is a server-only mod.** No custom blocks, items, or GUIs in v1 — chat menus, titles, particles, and vanilla loot only. This means **your friends install nothing**: you drop one jar in the server's mods folder and everyone plays. This constraint shapes several v1 choices (chat-based shop, virtual currency) and is the single biggest practical win available. Custom blocks/GUIs can come in 1.1 as an *optional* client add-on.
7. **Cut several brief items as feel-bad or low-value:** visibility-reduction debuffs, regen suppression, teleport interference, escort objectives, front-facing armor. Reasons in §16.
8. **First-discovery fanfare added.** First player to set foot in each ring triggers a server-wide announcement + reward. Nearly free to build, creates a race on day one.

---

## 1. The concept, restated

The world is divided into five concentric zones centred on spawn. The innermost zone, **The Hearth**, is home: safe, no hostile spawns, where the base and the Contract Board live. Each ring outward is more dangerous — not just statistically, but *behaviorally*: elites with telegraphed abilities, coordinated ambushes, night surges, and named champions.

Danger and reward both point outward. Players take Contracts from the board, travel out as a group, build Heat as they hunt and loot, then race home to bank an expedition bonus. Completing a ring's one-time **Charter** permanently unlocks conveniences and better contracts for the whole server. The mod reuses ATM10's mobs, loot, and travel — it adds *situations*, not content.

## 2. What makes it fun (not just hard)

| Fun driver | How the mod delivers it |
|---|---|
| **Anticipation** | Ring names, danger ratings, entry fanfare, "next ring" distance readout. The map itself is a difficulty curve you can see. |
| **Tension with a dial** | Heat. Players always know why things are escalating and can always choose to cash out. Risk is *opt-in greed*, never a slot machine. |
| **Legibility** | Every elite ability is announced by the mob's generated name ("Belgath **the Vile**"), a glow, particles, and sound. Players should never ask "what killed me?" |
| **Co-op texture** | Modifiers create target-priority calls ("focus the Caller!"), ambushes create back-to-back moments, cash-out creates group decisions. |
| **Shared milestones** | Charters and first-discoveries are server-wide announcements with fireworks. Five friends get shared war stories. |
| **Session shape** | Everything is tuned for the real unit of play: the 30–60 minute "expedition night." Take a contract, go out, spike, extract, spend. |
| **No sponges, ever** | Hard caps on stat scaling (§8). Ring 4 mobs hit ~×1.7, never ×10. Difficulty past that comes from *behavior and numbers of situations*, not HP. |

## 3. Core gameplay loop

```
 PREPARE (Hearth)          EXPEDITION (Rings 1–4)              RETURN (Hearth)
┌────────────────┐   ┌──────────────────────────────────┐   ┌────────────────┐
│ Check board     │   │ Travel out; cross ring boundaries │   │ Cross into      │
│ Accept contract │──▶│ Fight elites → Heat rises         │──▶│ Hearth: bank    │
│ Gear up, stock  │   │ Complete contract objective       │   │ field Marks     │
│ food & potions  │   │ Heat ≥ Hunted → ambushes begin    │   │ × Heat bonus    │
└────────────────┘   │ Heat ≥ Marked → a Hunter comes    │   │ Spend at shop   │
        ▲            │ Choose: push deeper or extract?   │   │ Charter fanfare │
        └────────────┴──────────────────────────────────┴───┴────────┬───────┘
                                                                      ▼
                                                          unlocks make next trip better
```

**Heat, precisely:**
- Accrues only in Ring 2+ (Ring 1 stays casual for quick trips). Rates ≈ 1/min in Ring 2, 2/min in Ring 3, 3/min in Ring 4, **+5 per elite killed**, +10 per champion. Capped at 100. Persisted on the player (relogging does not clear it).
- Thresholds: **Calm** (0–24) → **Restless** (25–49) → **Hunted** (50–74) → **Marked** (75–100). Shown on the action bar while outside the Hearth.
- At Hunted+: periodic ambush checks (pack of ring-appropriate mobs + 1 elite spawns around the party; 8-min cooldown; 10-second audible warning — wolves howl, "You are being hunted…").
- At Marked: occasionally a named **Hunter** champion spawns tracking the party (15-min cooldown). Killing it pays well.
- Marks earned in the field are **field Marks**. Re-entering the Hearth (or, in 1.1, an outpost) banks them at `× (1 + Heat/200)` — up to +50% — and resets Heat. Dying banks them at 50% with no bonus.
- Ambush mobs drop no Marks (anti-AFK-farming), but Hunters do.

## 4 & 5. The rings

Five zones. Radii are defaults; everything is config. Ring 1 is deliberately gentle — it teaches the elite vocabulary. Identity comes from *which systems are active*, not just bigger numbers.

| # | Name | Radius | Danger | Identity — what's actually different |
|---|---|---|---|---|
| 0 | **The Hearth** | 0–400 | ☠ 0, green | No natural hostile spawns. Board, shop, cash-out, respawn. Home stays cozy forever. |
| 1 | **The Verge** | 400–1200 | ☠ 1, yellow | Light stat scaling. Elites appear (1 modifier, ~4%). No Heat. *"The lights of home fade behind you."* |
| 2 | **The Wildmarch** | 1200–2800 | ☠ 2, gold | Heat begins. Ambushes possible. Elites ~8%, first Champions (2 modifiers, named). Packs spawn slightly larger. *"Something out here watches back."* |
| 3 | **The Duskreach** | 2800–5600 | ☠ 3, red | Night surges concentrate here. Champions common. Caches worth real money. Hunters at Marked. *"The dark here has teeth."* |
| 4 | **The Ashenfront** | 5600+ | ☠ 4, dark red | Max scaling (still capped). Elites ~16%, champion-heavy, ambushes larger, best contracts and cache tables. *"Turn back — or make history."* |

Ring crossing feedback: colored title + one-line flavor subtitle + sound (level-up chime moving inward, low elder-guardian toll moving outward) + action-bar update. A 30-second **grace period** after crossing outward suppresses ambush checks (anti-frustration). Crossings are quiet after the first time each session except the action bar (anti-spam).

Dimensions: v1 rings apply to the Overworld only. Nether/End get a flat "Ring 2-equivalent" profile (configurable), because radial distance is meaningless in a 1:8 dimension. Revisit later if wanted.

## 6. Progression model: soft, server-wide, one-time "Charters"

**Recommendation: soft progression.** Anyone can walk anywhere on day one; deep rings are simply lethal to the unprepared. A warning line appears when you enter a ring above the server's Charter level ("The Duskreach is uncharted — the Board offers no support here yet"). Hard locks were rejected because ATM10 *requires* travel (structures, biomes, allthemodium) and fighting the pack is a losing battle; boss gates were rejected because ATM10's bosses belong to other mods' progression.

**Chartering a ring** = completing its 2 one-time Charter contracts (e.g., Verge: *slay 5 Verge elites* + *recover the Verge supply cache*). Chartering fires server-wide fireworks/announcement and permanently unlocks, for everyone:
- The next tier of shop stock and bounty contracts.
- A ring perk at home (examples: Verge → daily free Field Rations kit at the board; Wildmarch → cache maps purchasable; Duskreach → +1 daily bounty slot; Ashenfront → prestige cosmetics).
- Intel: `/rings inspect` shows that ring's champions' modifiers from further away.

Progression is **server-wide, not per-team** in v1 — a 3–5 player private server is one team. (FTB Teams integration is a 1.1 option if ever needed.) No percentage power creep: unlocks are conveniences, information, and access, so there's no runaway scaling to balance.

## 7. Reward model

**One currency: ◈ Marks** (virtual, ledger-based — no physical token item, so it can't be duped, hoppered, or voided). Earned from: contracts (main source, fixed amounts), elite/champion kills (small trickle with daily diminishing returns), first-discoveries, charters, surge-night bonuses. Field-earned Marks flow through the Heat cash-out (§3).

**Spend at the board** (`/rings shop`, clickable chat menu in v1) on config-defined stock, unlocked by Charter tier. Example stock:

| Offer | Cost | Notes |
|---|---|---|
| Repair Kit (materials bundle) | 10 | quantities of existing ATM10 repair mats |
| Brewer's Crate (potions/food) | 15 | consumables for the next run |
| Enchanter's Satchel (lapis, books, XP bottles) | 25 | acceleration, not skips |
| **Cache Map** | 20 | *generates a cache contract in a ring of your choice — players buy their own adventure* |
| Keepsake (cosmetic trophy variants) | 30 | renamed/lored vanilla items |
| Hearth Upgrade tokens | 100–300 | one-time unlocks (see §11) |

**Principles enforced in config, not vibes:** no ATM10 endgame items in pools; rewards accelerate the pack's own progression rather than bypass it; one-time contracts pay ~5–10× a repeatable bounty; kill-trickle halves after 10 elites/day/player. Every pool and price is a JSON the server owner can edit.

Rejected: loot crates with random rolls as the primary reward (RNG disappointment on a 40-minute expedition feels terrible; caches already provide the "open a box" moment), per-ring currencies (wallet clutter).

## 8. Enemy scaling & the modifier library

**Base ring scaling (applied once at spawn, hard-capped):** health ×1.0/1.15/1.3/1.5/1.75 by ring, damage ×1.0/1.1/1.25/1.45/1.7, speed up to ×1.15. Absolute config caps at ×2.0 — Apotheosis and friends already buff mobs, and stacking must never produce a sponge. Excluded from scaling *and* from elite rolls: anything spawner-/machine-/summon-spawned, tamed, boss-tagged, or on the entity blacklist.

**Elites** (1 modifier, colored name, ~4–16% of natural hostile spawns by ring) and **Champions** (2 modifiers from *different categories*, generated name + epithet, glowing outline, kill announced in chat). The name IS the telegraph: the epithet states the scariest modifier.

**v1 modifier library (8):**

| Modifier | Epithet | Effect | Telegraph |
|---|---|---|---|
| Swift | *the Swift* | +35% speed, −25% health | white trail particles |
| Stonehide | *the Stoneskinned* | +armor, +KB resist, −15% speed | stone chips, gray name |
| Summoner | *the Caller* | every ~12s calls 2 weak minions (max 2 alive, minions drop nothing) | raised-arms pause, bell toll |
| Blinkstep | *the Unseen* | teleports 6–10 blocks when hit (6s cooldown) | ender particles + sound |
| Corrosive | *the Vile* | leaves a lingering harming cloud on death; occasional acid spit | dripping green particles |
| Vengeful | *the Wrathful* | +speed/+damage for 8s when a nearby ally dies | red flash + roar |
| **Warper** | *the Warping* | its hits can teleport **the player**: flung ~10 blocks skyward, position-swapped with the attacker, scattered 10–16 blocks sideways — or, rarely (Ashenfront, 5% of procs, config), **ripped straight into the Nether** at 1:8 coordinates | reverse-portal particles; every warp has sound + message |
| **Sieger** | *the Sunderer* | mines through cover to reach a hiding target: vanilla crack animation, hardness-scaled speed | crit particles, block-hit sounds, visible cracks |

Warper rules: 25% proc chance per hit, 8s cooldown per mob, never triggers on creative/spectator, toss requires 6+ blocks of headroom (else it degrades to scatter), Nether rifts search for a safe landing (feet/head clear, solid non-lava floor) and degrade to scatter if none exists — spicy, never insta-lethal. A rift is broadcast to the whole server, because that's a story.

Sieger rules (the anti-"hide in a dirt hut" answer): digs only when its target is unreachable (no line of sight, or pathing gave up), only blocks within reach along the eye-line, hardness cap (default 5.0 — obsidian-class blocks and everything on the blacklist are safe), **never blocks with block entities** (chests, barrels, ATM10 machines — breaching, not robbing), **never inside the Hearth**, and each sieger has a lifetime budget of 32 blocks. Broken blocks drop as items by default. Hunters (the Marked-heat stalkers) always carry Sieger — when the frontier sends someone for you, walls are a delay, not a defense.

1.1 adds: **Shieldbearer** (*the Warden* — nearby allies take −30% damage; kill it first) and **Warded** (*the Undying* — strong regen unless hit within 3s). Categories (mobility/defense/offense/support/breach) gate pairings: a Champion draws from two different categories, and banned pairs (Summoner+Vengeful) are checked explicitly — Swift/Blinkstep/Warper share the mobility category so no champion ever has two kinds of teleport. Abilities never stun-lock, never one-shot, and never remove player control — that rule is absolute (a Nether rift moves you, it doesn't kill you).

## 9. Objectives → Contracts

All objectives are **Contracts** on the board (`/rings board`): server-shared, accept via click, tracked in the action bar, complete for Marks. v1 templates, chosen for implementation-cost-to-fun ratio:

1. **Bounty: Hunt** *(repeatable, 3 rotate daily)* — "A [Vile Skeleton Champion] terrorizes the Wildmarch." When the accepting party is in the target ring, the quarry spawns near them after a tense delay ("Your quarry is near…"). Cheapest possible content: it's the elite system pointed at a name. Bounties bias toward rings the party has chartered ±1.
2. **Recovery: Supply Cache** *(via charter contracts + purchasable Cache Maps)* — coordinates in the target ring; a guarded vanilla chest with a configured loot table + field Marks. Destination + fight + treasure, no custom worldgen (chest is placed at a surface-scanned position on arrival).
3. **Charter contracts** *(one-time per ring, 2 each)* — reuse templates 1–2 plus simple counters ("slay 5 elites in the Verge", "survive a surge night in the Duskreach").

Deferred: wave-defense ("Hold the Beacon", 1.1 — reuses ambush wave code once it exists), timed extraction contracts (2.0), escort (never — vanilla pathfinding AI will humiliate us), "complete an ATM10 advancement in-ring" (1.1 garnish).

## 10. Events (v1: two, both systemic)

1. **Ambush / Hunter** — not a scheduled event but the Heat system's output (§3). This is deliberate: the "event" fires exactly when the fiction says it should.
2. **Surge Night** — each dusk, 35% chance one ring surges until dawn, announced server-wide ("*The Duskreach stirs tonight.*"): elite chance ×2, Marks ×1.5 in that ring. Cost: a multiplier and a broadcast. Payoff: "drop everything, it's a Duskreach night" — a reason to revisit earlier rings forever.

1.1: **Supply Drop** (announced falling cache, first party there wins — reuses cache code). Rejected for v1: anything requiring custom structures or that interrupts players in their base.

## 11. The safe zone stays useful

The Hearth is where the loop *closes*: board, shop, cash-out threshold, respawn, charter fireworks, and Hearth Upgrades bought with Marks — e.g., **Muster Bell** (5-min party buff when an expedition departs), **Rations Rack** (daily consumables), **Trophy licenses** (cosmetics). Deliberately *not* a colony sim: upgrades are unlocks, not buildings to manage. Players still physically build the base themselves — the mod gives them reasons to gather there before and after every run. Low-risk home events (traveling merchant) slot into 1.1 if wanted.

## 12. Why leave home? (the motivation stack)

Overlapping pulls, so no single one must carry the design: Marks exist only outside → everything in §7 requires expeditions; contracts give *specific* destinations (direction beats wandering); charters gate shop tiers and home perks; surge nights create "tonight!" impulses; first-discoveries reward the bold immediately; champions announce themselves in chat (glory); caches and Cache Maps make greed actionable; and Heat makes the trip itself the game, not travel time between two chores. Nothing gates ATM10 tech — the mod competes for *evenings*, not for progression.

## 13. Travel without tedium

v1 ships **no custom travel** — ATM10 already solves travel (waystones/teleports/flight arrive quickly in that pack; exact mods verified against the server's list during implementation). The mod avoids tedium structurally: bounties bias toward reachable rings, caches generate in the target ring *near the party's bearing* (not across the world), and nothing requires daily pilgrimages to Ring 4. **Outposts (1.1):** build a base in a chartered ring, activate with a modest resource cost → becomes a respawn point, remote board access, and a remote *cash-out* point (bank Heat without going all the way home — a meaningful strategic unlock, one per ring). No custom teleport network unless the pack's options prove insufficient in play.

## 14. Exploits & mitigations

| Exploit | Mitigation |
|---|---|
| Mob farms printing Marks | Spawner/machine/summon/FakePlayer kills: no elite rolls, no Marks. Kill credit requires a real player's recent damage. |
| AFK elite grinding | Kill trickle halves after 10/day/player; elite kills raise Heat → ambushes (which drop nothing) end AFK sessions. |
| Relog to clear Heat | Heat persists on the player. |
| Dragging champions into Hearth golem grinders | Marks only credit for kills outside the Hearth; suppression never deletes player-dragged mobs (no item-vanishing griefs). |
| Cache camping/automation | Caches exist only via contracts/maps, one active per party, cooldown between generations, chest is a normal chest once looted. |
| Charter re-runs | One-time flags in world SavedData. |
| Boundary-line camping (tower on Ring 3 edge, snipe Ring 4) | Explicitly not solved — clever basing is fun, and Heat still accrues. |

## 15. Minimum viable release (v1.0) — challenged and trimmed

The brief's MVP list, minus a bounty/objective split (merged), minus any GUI (chat menus), minus outposts (1.1), minus one event (ambushes ride on Heat):

1. Ring core: config, origin, boundary detection, entry fanfare, grace periods, safe-zone spawn suppression, `/rings info|list|zoneat`.
2. Mob scaling with caps + exclusion rules.
3. Elites & Champions: 6 modifiers, categories/pairing rules, name generator, glow, kill announcements, `/rings inspect`.
4. Heat: accrual, thresholds, action bar, ambushes, Hunters, cash-out, death rule.
5. Marks ledger + chat shop with config stock and charter-gated tiers.
6. Contract board: 3 rotating bounties/day, cache recovery, 2 charter contracts per ring, first-discovery bonuses.
7. Surge nights.
8. Admin/debug: `/rings reload|validate|setorigin|debug boundaries|inspect|simulate elite|event surge|marks give|charter set`.
9. Ships as one **server-only jar**; friends install nothing.

## 16. Removed or postponed (with reasons)

**Removed:** escort/carry-the-NPC objectives (AI cost, guaranteed jank), front-facing armor (unreadable in a scrum), visibility debuffs & regen suppression (feel-bad, anti-fun), teleport interference (fights the pack's core conveniences), 4 elite tiers (2 carry the same information load), per-ring currencies, full quest-book UI, custom dimensions/worldgen/structures, colony mechanics, percentage team buffs (power creep).
**Postponed:** outposts, trophies, GUI broker block, wave defense, supply drops, merchant visits, Shieldbearer/Warded (→1.1); nemesis system, ring-boss gauntlets (curated ATM10 boss bounties), map-mod markers, journal screen, timed extraction contracts, FTB Teams support (→2.0).

## 17. Roadmap

- **1.1 (quality of life + texture):** outposts with remote cash-out; champion trophies (lored vanilla items: *"Belgath the Vile — slain by Anthony, Duskreach, Day 47"*); Hold-the-Beacon contract; Supply Drop event; Shieldbearer + Warded; broker block & GUI (optional client mod from here on); featured-ring weekly rotation; merchant visits.
- **2.0 (systems with memory):** **Nemesis** — a champion that kills a player is promoted: keeps its name, gains a modifier, appears on the board with a kill count and grudge lines, pays triple when finally slain (implementation is small: champions already have identity — this just persists it); ring-boss charter finales using existing ATM10 bosses; Xaero's/FTB-map markers for contracts; ring journal/stats; timed extraction contracts.
- **Experimental (only if the server asks):** personal difficulty offsets, cursed zones, seasonal "New Frontier" prestige (re-roll ring layout outward), story fragments in cache lore.

## 18. Technical structure

- **Platform:** NeoForge 21.1.x / Minecraft 1.21.1 / Java 21 / ModDevGradle 2. **Zero mixins** — everything rides documented NeoForge events, which maximizes compatibility with ATM10's 400+ mods. **Server-only:** no registered blocks/items/entities/network channels in v1; `displayTest` set so clients without the mod connect freely.
- **Key events:** `FinalizeSpawnEvent` (tier assignment, elite rolls, safe-zone suppression, exclusions), `PlayerTickEvent.Post` (1s cadence: boundary detection, Heat, action bar), `LivingDeathEvent` (kill credit, Marks, announcements, Corrosive cloud, Vengeful trigger), `LivingIncomingDamageEvent` (Blinkstep), `ServerTickEvent` (contract/event scheduler, ambient modifier particles), `RegisterCommandsEvent`, `ServerStartingEvent` (config load).
- **State:** entity data via NBT persistent data (`df_ring`, `df_tier`, `df_mods`, `df_scaled` — applied once, never restacked; attribute modifiers use fixed `ResourceLocation` IDs so they can't stack); player Heat/daily-counters via player persistent NBT; server state (ledger, charters, board rotation, first-discoveries) via one `SavedData` on the overworld.
- **Config:** plain JSON under `config/distantfrontiers/` — `rings.json`, `modifiers.json`, `shop.json`, `contracts.json` — generated with commented defaults on first run, hot-reloaded by `/rings reload`, validated with line-precise errors (radii monotonic, chances 0–1, multipliers within caps, item IDs resolved against the running pack so typos surface immediately).
- **Package layout:** `ring/` (Ring, RingManager, BoundaryWatcher) · `scaling/` (SpawnScaling) · `elite/` (EliteModifier, Elites, NameGen) · `heat/` · `economy/` (ledger, shop) · `contract/` (board, templates, rotation) · `event/` (surge) · `command/` · `state/` (SavedData).

## 19. Example configuration (abridged `rings.json`)

```jsonc
{
  "origin": { "useWorldSpawn": true, "x": 0, "z": 0 },
  "dimensions": ["minecraft:overworld"],
  "rings": [
    { "id": "hearth", "name": "The Hearth", "color": "green", "danger": 0,
      "outerRadius": 400, "entryMessage": "You feel the safety of home.",
      "suppressHostileSpawns": true,
      "mobs": { "healthMult": 1.0, "damageMult": 1.0, "speedMult": 1.0 },
      "elites": { "eliteChance": 0.0, "championChance": 0.0, "modifiers": [] },
      "heat": { "gainPerMinute": 0 } },

    { "id": "wildmarch", "name": "The Wildmarch", "color": "gold", "danger": 2,
      "outerRadius": 2800, "entryMessage": "Something out here watches back.",
      "suppressHostileSpawns": false,
      "mobs": { "healthMult": 1.3, "damageMult": 1.25, "speedMult": 1.05 },
      "elites": { "eliteChance": 0.08, "championChance": 0.15,
                  "modifiers": ["swift","stonehide","summoner","blinkstep","corrosive","vengeful"] },
      "heat": { "gainPerMinute": 1.0 } },

    { "id": "ashenfront", "name": "The Ashenfront", "color": "dark_red", "danger": 4,
      "outerRadius": -1, "entryMessage": "Turn back — or make history.",
      "mobs": { "healthMult": 1.75, "damageMult": 1.7, "speedMult": 1.15 },
      "elites": { "eliteChance": 0.16, "championChance": 0.35, "modifiers": ["*"] },
      "heat": { "gainPerMinute": 3.0 } }
  ],
  "scalingCaps": { "maxHealthMult": 2.0, "maxDamageMult": 2.0, "maxSpeedMult": 1.25 },
  "exclusions": { "spawnTypes": ["SPAWNER","MOB_SUMMONED","CONVERSION","BUCKET","SPAWN_EGG","COMMAND","DISPENSER"],
                  "entityBlacklist": [], "skipBosses": true }
}
```

## 20. Staged implementation plan

| Stage | Delivers | Definition of done |
|---|---|---|
| 0 ✅ | Project scaffold, this document | `gradle build` produces a loading jar |
| 1 | Ring core (§15.1) | Walk outward on a test server: fanfare, suppression, commands all work |
| 2 | Scaling + elites (§15.2–3) | Champions spawn named, telegraphed, capped; inspect works |
| 3 | Heat (§15.4) | Full tension loop on a test world: accrue → ambush → hunter → cash out |
| 4 | Marks + shop (§15.5) | Earn, bank, spend; ledgers survive restarts |
| 5 | Contracts + charters (§15.6) + surges | The complete v1 loop, end to end |
| 6 | ATM10 integration pass + balance | Runs inside actual ATM10; blacklists tuned (Apotheosis bosses etc.); tuning cheat-sheet written |

Each stage is independently testable on a dev server. The group can start playing after Stage 5; Stage 6 is tuning against the real pack.

## 21. Decisions needing approval before implementation

1. **Name:** "Distant Frontiers" (modid `distantfrontiers`) — or propose another.
2. **Ring names/theme + default radii** (§4) — Hearth 400 / Verge 1200 / Wildmarch 2800 / Duskreach 5600 / Ashenfront ∞.
3. **Heat system in v1** — my flagship addition; confirm you want it (it shapes everything).
4. **Server-only v1** (friends install nothing; UI is chat/titles until 1.1) — confirm the trade.
5. **Server-wide shared progression** (no per-team split) — OK for your group?
6. **Death rule** for field Marks (bank 50%, lose bonus) — harsher/softer?
7. **Marks as virtual currency** (no physical token item) — OK?
8. **Overworld-only rings** in v1 (flat profile elsewhere) — OK?
9. **No custom travel in v1**; outposts in 1.1 — OK?
10. **Default dials** (elite %s, caps, prices in §7–8) — approve as starting points for playtest tuning.
