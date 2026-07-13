package dev.anthonyw.frontiers.contract;

import dev.anthonyw.frontiers.DistantFrontiers;
import dev.anthonyw.frontiers.core.AnnulusPoint;
import dev.anthonyw.frontiers.core.ContractsConfigParser;
import dev.anthonyw.frontiers.elite.Elites;
import dev.anthonyw.frontiers.util.McRand;
import dev.anthonyw.frontiers.heat.HeatManager;
import dev.anthonyw.frontiers.ring.Ring;
import dev.anthonyw.frontiers.ring.RingManager;
import dev.anthonyw.frontiers.state.FrontiersState;
import dev.anthonyw.frontiers.util.SpawnUtil;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.fml.loading.FMLPaths;

import javax.annotation.Nullable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The Contract Board: one system for everything players can DO out there.
 *
 * - Rotating bounties (3 per in-game day): a named quarry that materializes
 *   near accepting hunters once they reach its ring.
 * - Supply caches: coordinates in a ring; a guarded loot chest appears when a
 *   tracking player closes in. Created by ring Charters and by purchased
 *   Cache Maps.
 * - Ring Charters: each frontier ring's one-time objectives (slay elites +
 *   recover its cache). Completing both charters the ring server-wide:
 *   fanfare, Marks for everyone online, and the next shop tier unlocks.
 */
public final class ContractBoard {
    public static final ContractBoard INSTANCE = new ContractBoard();

    public static final String TAG_BOUNTY_ID = "df_bounty_id";
    public static final String TAG_HUNTER = "df_hunter";

    private static final int[] BOUNTY_REWARD_BY_DANGER = {0, 15, 25, 40, 60};

    private static volatile ContractsConfigParser.Data config =
            ContractsConfigParser.parse(ContractsConfigParser.DEFAULT_JSON).data();

    private ContractBoard() {
    }

    // ------------------------------------------------------------------
    // Config (parsing lives in the unit-tested core module)

    public static List<String> loadConfig() {
        Path file = FMLPaths.CONFIGDIR.get().resolve(DistantFrontiers.MODID).resolve("contracts.json");
        List<String> errors = new ArrayList<>();
        try {
            Files.createDirectories(file.getParent());
            if (!Files.exists(file)) {
                Files.writeString(file, ContractsConfigParser.DEFAULT_JSON);
            }
            ContractsConfigParser.Result result = ContractsConfigParser.parse(Files.readString(file));
            errors.addAll(result.errors());
            for (String id : result.data().bountyMobs()) {
                if (EntityType.byString(id).isEmpty()) {
                    errors.add("contracts.json: entity " + id + " does not exist in this pack");
                }
            }
            config = result.data();
            errors.forEach(e -> DistantFrontiers.LOGGER.warn("contracts.json: {}", e));
        } catch (Exception e) {
            errors.add("could not read contracts.json: " + e.getMessage());
            DistantFrontiers.LOGGER.error("Failed to load contracts.json, using defaults", e);
        }
        return errors;
    }

    public static String randomAmbushMob(ServerLevel level) {
        List<String> mobs = config.bountyMobs();
        return mobs.get(level.random.nextInt(mobs.size()));
    }

    public static int charterSlayTarget(Ring ring) {
        return config.charterSlayTarget(ring.danger());
    }

    // ------------------------------------------------------------------
    // Daily rotation

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (server.overworld().getGameTime() % 100 != 0) {
            return;
        }
        RingManager mgr = RingManager.get();
        if (mgr == null) {
            return;
        }
        FrontiersState state = FrontiersState.get(server);
        long day = server.overworld().getDayTime() / 24000;
        if (state.startNewDayIfNeeded(day)) {
            rotateBounties(server, state, mgr);
        }
        ensureCharterCache(server, state, mgr);
    }

    /** /rings bounties reroll - immediate rotation for testing/tuning. */
    public static void adminReroll(MinecraftServer server) {
        RingManager mgr = RingManager.get();
        if (mgr != null) {
            rotateBounties(server, FrontiersState.get(server), mgr);
        }
    }

    private static void rotateBounties(MinecraftServer server, FrontiersState state, RingManager mgr) {
        state.bounties.clear();
        List<Ring> candidates = eligibleBountyRings(state, mgr);
        if (candidates.isEmpty()) {
            return;
        }
        ServerLevel overworld = server.overworld();
        for (int i = 0; i < 3; i++) {
            Ring ring = candidates.get(overworld.random.nextInt(candidates.size()));
            FrontiersState.Bounty bounty = new FrontiersState.Bounty();
            bounty.id = state.nextContractId();
            bounty.ringId = ring.id();
            bounty.mobId = randomAmbushMob(overworld);
            bounty.quarryName = Elites.generateName(overworld.random);
            bounty.champion = overworld.random.nextDouble() < (ring.danger() >= 3 ? 0.5 : 0.2);
            bounty.reward = BOUNTY_REWARD_BY_DANGER[Math.min(4, Math.max(1, ring.danger()))]
                    + (bounty.champion ? 10 : 0);
            state.bounties.add(bounty);
        }
        state.setDirty();
        if (!server.getPlayerList().getPlayers().isEmpty()) {
            server.getPlayerList().broadcastSystemMessage(Component.literal(
                            "The Contract Board has new bounties. (/rings board)")
                    .withStyle(ChatFormatting.GOLD), false);
        }
    }

    /** Bounties target chartered rings and the current frontier. */
    private static List<Ring> eligibleBountyRings(FrontiersState state, RingManager mgr) {
        List<Ring> out = new ArrayList<>();
        String frontier = activeCharterRing(state, mgr);
        for (Ring ring : mgr.rings()) {
            if (ring.danger() <= 0) {
                continue;
            }
            if (state.isChartered(ring.id()) || ring.id().equals(frontier)) {
                out.add(ring);
            }
        }
        return out;
    }

    /** The lowest unchartered frontier ring, or null when everything is chartered. */
    @Nullable
    public static String activeCharterRing(FrontiersState state, RingManager mgr) {
        for (Ring ring : mgr.rings()) {
            if (ring.danger() > 0 && !state.isChartered(ring.id())) {
                return ring.id();
            }
        }
        return null;
    }

    private static void ensureCharterCache(MinecraftServer server, FrontiersState state, RingManager mgr) {
        String frontier = activeCharterRing(state, mgr);
        if (frontier == null || state.charterCacheRecovered(frontier)) {
            return;
        }
        for (FrontiersState.Cache cache : state.caches) {
            if (cache.charterCache && cache.ringId.equals(frontier) && !cache.opened) {
                return;
            }
        }
        Ring ring = mgr.byId(frontier);
        if (ring == null) {
            return;
        }
        createCache(server, ring, true, null);
    }

    /** Creates a cache contract; purchaser (if any) starts tracking it immediately. */
    public static FrontiersState.Cache createCache(MinecraftServer server, Ring ring,
                                                   boolean charter, @Nullable ServerPlayer purchaser) {
        FrontiersState state = FrontiersState.get(server);
        ServerLevel overworld = server.overworld();
        RingManager mgr = RingManager.get();

        double inner = mgr.innerRadius(ring.id());
        double outer = ring.unbounded() ? inner + 1200 : ring.outerRadius();
        double[] offset = AnnulusPoint.roll(new McRand(overworld.random), inner, outer);
        double[] origin = mgr.origin(overworld);

        FrontiersState.Cache cache = new FrontiersState.Cache();
        cache.id = state.nextContractId();
        cache.ringId = ring.id();
        cache.x = (int) (origin[0] + offset[0]);
        cache.z = (int) (origin[1] + offset[1]);
        cache.charterCache = charter;
        cache.reward = 10 + 10 * ring.danger();
        if (purchaser != null) {
            cache.accepted.add(purchaser.getUUID());
        }
        state.caches.add(cache);
        state.setDirty();
        return cache;
    }

    // ------------------------------------------------------------------
    // Per-player tick (called from HeatManager's 1s cadence)

    public static void tickPlayer(ServerPlayer player, ServerLevel level, Ring ring, FrontiersState state) {
        for (FrontiersState.Bounty bounty : state.bounties) {
            if (!bounty.completed && bounty.spawnedEntity == null
                    && bounty.ringId.equals(ring.id())
                    && bounty.accepted.contains(player.getUUID())
                    && level.random.nextDouble() < 0.05) {
                spawnQuarry(player, level, ring, bounty, state);
            }
        }
        for (FrontiersState.Cache cache : state.caches) {
            if (!cache.opened && !cache.placed
                    && cache.accepted.contains(player.getUUID())
                    && cache.ringId.equals(ring.id())) {
                double dx = player.getX() - cache.x;
                double dz = player.getZ() - cache.z;
                if (dx * dx + dz * dz < 64 * 64) {
                    placeCache(level, ring, cache, state);
                    player.sendSystemMessage(Component.literal(
                                    "You spot the supply cache — it is guarded.")
                            .withStyle(ChatFormatting.YELLOW));
                }
            }
        }
    }

    private static void spawnQuarry(ServerPlayer player, ServerLevel level, Ring ring,
                                    FrontiersState.Bounty bounty, FrontiersState state) {
        BlockPos pos = SpawnUtil.findGroundNear(level, player.blockPosition(), 24, 40);
        if (pos == null) {
            return;
        }
        Mob quarry = SpawnUtil.spawnScaled(level, ring, bounty.mobId, pos);
        if (quarry == null) {
            return;
        }
        Elites.promote(quarry, ring, bounty.champion, bounty.quarryName);
        quarry.getPersistentData().putInt(TAG_BOUNTY_ID, bounty.id);
        quarry.setPersistenceRequired();
        bounty.spawnedEntity = quarry.getUUID();
        state.setDirty();
        for (ServerPlayer nearby : level.getPlayers(p -> p.distanceToSqr(player) < 64 * 64)) {
            nearby.sendSystemMessage(Component.literal("Your quarry is near…")
                    .withStyle(ChatFormatting.GOLD, ChatFormatting.ITALIC));
            nearby.playNotifySound(SoundEvents.ELDER_GUARDIAN_CURSE, SoundSource.MASTER, 0.4f, 1.4f);
        }
    }

    private static void placeCache(ServerLevel level, Ring ring, FrontiersState.Cache cache,
                                   FrontiersState state) {
        BlockPos surface = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                new BlockPos(cache.x, 0, cache.z));
        level.setBlockAndUpdate(surface, Blocks.CHEST.defaultBlockState());
        if (level.getBlockEntity(surface) instanceof RandomizableContainerBlockEntity chest) {
            ResourceLocation table = ResourceLocation.tryParse(
                    config.cacheLootTables().getOrDefault(ring.id(), config.cacheLootTables().getOrDefault(
                            "default", "minecraft:chests/simple_dungeon")));
            if (table != null) {
                chest.setLootTable(ResourceKey.create(Registries.LOOT_TABLE, table), level.random.nextLong());
            }
        }
        for (int i = 0; i < 3 + ring.danger() / 2; i++) {
            BlockPos guardPos = SpawnUtil.findGroundNear(level, surface, 3, 8);
            if (guardPos == null) {
                continue;
            }
            Mob guard = SpawnUtil.spawnScaled(level, ring, randomAmbushMob(level), guardPos);
            if (guard != null) {
                guard.setPersistenceRequired();
                if (i == 0) {
                    Elites.promote(guard, ring, false, null);
                }
            }
        }
        level.sendParticles(ParticleTypes.TOTEM_OF_UNDYING,
                surface.getX() + 0.5, surface.getY() + 1.5, surface.getZ() + 0.5,
                40, 0.5, 1.0, 0.5, 0.2);
        cache.placed = true;
        cache.placedX = surface.getX();
        cache.placedY = surface.getY();
        cache.placedZ = surface.getZ();
        state.setDirty();
    }

    // ------------------------------------------------------------------
    // Completion

    /** Called from KillRewards when a mob carrying a bounty tag dies. */
    public static void onBountyMobDeath(ServerLevel level, Mob mob, int bountyId,
                                        @Nullable ServerPlayer killer) {
        FrontiersState state = FrontiersState.get(level.getServer());
        FrontiersState.Bounty bounty = null;
        for (FrontiersState.Bounty b : state.bounties) {
            if (b.id == bountyId) {
                bounty = b;
                break;
            }
        }
        if (bounty == null || bounty.completed) {
            return;
        }
        if (killer == null) {
            // Died to the world; the quarry will resurface for the hunters.
            bounty.spawnedEntity = null;
            state.setDirty();
            return;
        }
        bounty.completed = true;
        List<ServerPlayer> party = level.getPlayers(p -> p.distanceToSqr(killer) < 48 * 48);
        for (ServerPlayer member : party) {
            state.addField(member.getUUID(), bounty.reward);
            member.sendSystemMessage(Component.literal("Bounty claimed: ")
                    .withStyle(ChatFormatting.GOLD)
                    .append(Component.literal("◈ " + bounty.reward + " (field)")
                            .withStyle(ChatFormatting.AQUA)));
        }
        String quarry = mob.getCustomName() == null ? bounty.quarryName : mob.getCustomName().getString();
        level.getServer().getPlayerList().broadcastSystemMessage(Component.literal(
                        "☠ " + quarry + " has been slain by " + killer.getName().getString() + ".")
                .withStyle(ChatFormatting.GOLD), false);
        state.bounties.remove(bounty);
        state.setDirty();
    }

    /** Called from the RightClickBlock hook when a placed cache is opened. */
    public static void onCacheOpened(ServerLevel level, BlockPos pos, ServerPlayer opener) {
        FrontiersState state = FrontiersState.get(level.getServer());
        for (FrontiersState.Cache cache : state.caches) {
            if (!cache.placed || cache.opened
                    || cache.placedX != pos.getX() || cache.placedY != pos.getY()
                    || cache.placedZ != pos.getZ()) {
                continue;
            }
            cache.opened = true;
            List<ServerPlayer> party = level.getPlayers(p -> p.distanceToSqr(opener) < 48 * 48);
            for (ServerPlayer member : party) {
                state.addField(member.getUUID(), cache.reward);
                member.sendSystemMessage(Component.literal("Cache recovered: ")
                        .withStyle(ChatFormatting.GREEN)
                        .append(Component.literal("◈ " + cache.reward + " each (field)")
                                .withStyle(ChatFormatting.AQUA)));
            }
            if (cache.charterCache) {
                state.markCharterCacheRecovered(cache.ringId);
                maybeCompleteCharter(level.getServer());
            }
            state.setDirty();
            return;
        }
    }

    @SubscribeEvent
    public void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getEntity() instanceof ServerPlayer player)
                || !(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        onCacheOpened(level, event.getPos(), player);
    }

    /** Charter slay progress, called on every credited elite kill. */
    public static void onEliteKilledForCharter(ServerPlayer killer, String mobRingId) {
        MinecraftServer server = killer.serverLevel().getServer();
        FrontiersState state = FrontiersState.get(server);
        RingManager mgr = RingManager.get();
        if (mgr == null || !mobRingId.equals(activeCharterRing(state, mgr))) {
            return;
        }
        Ring ring = mgr.byId(mobRingId);
        if (ring == null) {
            return;
        }
        int target = charterSlayTarget(ring);
        if (state.charterSlain(mobRingId) >= target) {
            return;
        }
        state.incrementCharterSlain(mobRingId);
        int slain = state.charterSlain(mobRingId);
        killer.sendSystemMessage(Component.literal(
                        "Charter of " + ring.name() + ": elites slain " + Math.min(slain, target) + "/" + target)
                .withStyle(ChatFormatting.YELLOW));
        maybeCompleteCharter(server);
    }

    public static void maybeCompleteCharter(MinecraftServer server) {
        FrontiersState state = FrontiersState.get(server);
        RingManager mgr = RingManager.get();
        if (mgr == null) {
            return;
        }
        String frontier = activeCharterRing(state, mgr);
        if (frontier == null) {
            return;
        }
        Ring ring = mgr.byId(frontier);
        if (ring == null) {
            return;
        }
        if (state.charterSlain(frontier) < charterSlayTarget(ring)
                || !state.charterCacheRecovered(frontier)) {
            return;
        }
        state.charter(frontier);
        int reward = 50 + 25 * ring.danger();
        server.getPlayerList().broadcastSystemMessage(Component.literal("★ ")
                .withStyle(ChatFormatting.GOLD)
                .append(Component.literal(ring.name()).withStyle(ring.color(), ChatFormatting.BOLD))
                .append(Component.literal(" has been chartered! The Board expands its offers.")
                        .withStyle(ChatFormatting.GOLD)), false);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            state.addBanked(player.getUUID(), reward);
            player.sendSystemMessage(Component.literal("Charter reward: ◈ " + reward + " (banked)")
                    .withStyle(ChatFormatting.AQUA));
            player.playNotifySound(SoundEvents.PLAYER_LEVELUP, SoundSource.MASTER, 1.0f, 1.0f);
            player.serverLevel().sendParticles(ParticleTypes.TOTEM_OF_UNDYING,
                    player.getX(), player.getY() + 1, player.getZ(), 60, 0.5, 1.0, 0.5, 0.3);
        }
    }

    // ------------------------------------------------------------------
    // Board UI (clickable chat)

    public static void showBoard(ServerPlayer player) {
        MinecraftServer server = player.serverLevel().getServer();
        FrontiersState state = FrontiersState.get(server);
        RingManager mgr = RingManager.get();
        if (mgr == null) {
            player.sendSystemMessage(Component.literal("The Board is closed (ring config not loaded)."));
            return;
        }
        player.sendSystemMessage(Component.literal("═══ The Contract Board ═══")
                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));

        // Charter section
        String frontier = activeCharterRing(state, mgr);
        if (frontier == null) {
            player.sendSystemMessage(Component.literal("All rings are chartered. The frontier is yours.")
                    .withStyle(ChatFormatting.GREEN));
        } else {
            Ring ring = mgr.byId(frontier);
            int target = charterSlayTarget(ring);
            String cacheState = state.charterCacheRecovered(frontier) ? "recovered ✔" : "not recovered";
            player.sendSystemMessage(Component.literal("Charter of ").withStyle(ChatFormatting.WHITE)
                    .append(Component.literal(ring.name()).withStyle(ring.color()))
                    .append(Component.literal(" — elites " + Math.min(state.charterSlain(frontier), target)
                            + "/" + target + " · cache " + cacheState).withStyle(ChatFormatting.GRAY)));
        }

        // Bounties
        if (state.bounties.isEmpty()) {
            player.sendSystemMessage(Component.literal("No bounties posted today.")
                    .withStyle(ChatFormatting.GRAY));
        }
        for (FrontiersState.Bounty bounty : state.bounties) {
            Ring ring = mgr.byId(bounty.ringId);
            String mobName = EntityType.byString(bounty.mobId)
                    .map(t -> t.getDescription().getString()).orElse(bounty.mobId);
            MutableComponent line = Component.literal("◈" + bounty.reward + " ")
                    .withStyle(ChatFormatting.AQUA)
                    .append(Component.literal("Hunt " + bounty.quarryName
                                    + " (" + mobName + (bounty.champion ? " Champion" : " Elite") + ") — ")
                            .withStyle(ChatFormatting.WHITE))
                    .append(Component.literal(ring == null ? bounty.ringId : ring.name())
                            .withStyle(ring == null ? ChatFormatting.WHITE : ring.color()));
            line.append(acceptButton(player, state, bounty.accepted.contains(player.getUUID()), bounty.id));
            player.sendSystemMessage(line);
        }

        // Caches
        for (FrontiersState.Cache cache : state.caches) {
            if (cache.opened) {
                continue;
            }
            Ring ring = mgr.byId(cache.ringId);
            MutableComponent line = Component.literal("◈" + cache.reward + " ")
                    .withStyle(ChatFormatting.AQUA)
                    .append(Component.literal((cache.charterCache ? "Charter cache" : "Supply cache")
                                    + " near (" + cache.x + ", " + cache.z + ") — ")
                            .withStyle(ChatFormatting.WHITE))
                    .append(Component.literal(ring == null ? cache.ringId : ring.name())
                            .withStyle(ring == null ? ChatFormatting.WHITE : ring.color()));
            line.append(acceptButton(player, state, cache.accepted.contains(player.getUUID()), cache.id));
            player.sendSystemMessage(line);
        }

        // Footer
        float heat = state.heat(player.getUUID());
        player.sendSystemMessage(Component.literal(
                        "◈ " + state.banked(player.getUUID()) + " banked · "
                                + state.fieldMarks(player.getUUID()) + " field · Heat: "
                                + HeatManager.heatLabel(heat))
                .withStyle(ChatFormatting.DARK_AQUA));
    }

    private static Component acceptButton(ServerPlayer player, FrontiersState state,
                                          boolean accepted, int id) {
        if (accepted) {
            return Component.literal(" [tracking]").withStyle(ChatFormatting.DARK_GRAY);
        }
        return Component.literal(" [Accept]").withStyle(style -> style
                .withColor(ChatFormatting.GREEN)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/rings accept " + id))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                        Component.literal("Take this contract"))));
    }

    public static void accept(ServerPlayer player, int contractId) {
        FrontiersState state = FrontiersState.get(player.serverLevel().getServer());
        for (FrontiersState.Bounty bounty : state.bounties) {
            if (bounty.id == contractId && !bounty.completed) {
                bounty.accepted.add(player.getUUID());
                state.setDirty();
                Ring ring = RingManager.get() == null ? null : RingManager.get().byId(bounty.ringId);
                player.sendSystemMessage(Component.literal(
                                "Contract accepted. Hunt " + bounty.quarryName + " in "
                                        + (ring == null ? bounty.ringId : ring.name()) + ".")
                        .withStyle(ChatFormatting.GOLD));
                return;
            }
        }
        for (FrontiersState.Cache cache : state.caches) {
            if (cache.id == contractId && !cache.opened) {
                cache.accepted.add(player.getUUID());
                state.setDirty();
                player.sendSystemMessage(Component.literal(
                                "Tracking the cache near (" + cache.x + ", " + cache.z + ").")
                        .withStyle(ChatFormatting.GOLD));
                return;
            }
        }
        player.sendSystemMessage(Component.literal("No such open contract.").withStyle(ChatFormatting.RED));
    }
}
