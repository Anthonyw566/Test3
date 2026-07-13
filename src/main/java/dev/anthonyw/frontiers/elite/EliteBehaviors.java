package dev.anthonyw.frontiers.elite;

import dev.anthonyw.frontiers.DistantFrontiers;
import dev.anthonyw.frontiers.core.AbilitiesConfig;
import dev.anthonyw.frontiers.core.EliteModifier;
import dev.anthonyw.frontiers.ring.Ring;
import dev.anthonyw.frontiers.ring.RingManager;
import dev.anthonyw.frontiers.scaling.SpawnScaling;
import dev.anthonyw.frontiers.util.McRand;
import dev.anthonyw.frontiers.util.SpawnUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.monster.Silverfish;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Event-driven behavior for the elite modifiers. Every ability is telegraphed:
 * ambient particles identify the modifier at a glance, and each activation has
 * its own sound. Nothing here stuns players or deals unavoidable damage.
 *
 * The two "world fights back" abilities:
 * - WARPER: its hits can teleport the PLAYER - flung skyward, position-swapped,
 *   scattered sideways, or (rare, deep rings, config) ripped straight into the
 *   Nether. Cooldown per mob, chance per hit, all configurable.
 * - SIEGER: mines through cover to reach a hiding target, with vanilla crack
 *   animation, capped hardness, a lifetime block budget, and hard rules: never
 *   blocks with block entities (chests/machines), never inside the Hearth.
 */
public final class EliteBehaviors {
    private static final String TAG_BLINK_READY = "df_blink_ready"; // game time gates
    private static final String TAG_SUMMON_READY = "df_summon_ready";
    private static final String TAG_WARP_READY = "df_warp_ready";
    private static final String TAG_MINION = "df_minion";
    private static final String TAG_BREAK_POS = "df_break_pos";
    private static final String TAG_BREAK_PROGRESS = "df_break_prog";
    private static final String TAG_BLOCKS_BROKEN = "df_blocks_broken";
    public static final String TAG_NO_MARKS = "df_no_marks";

    private static volatile AbilitiesConfig config = AbilitiesConfig.defaults();

    public static AbilitiesConfig config() {
        return config;
    }

    public static List<String> loadConfig() {
        Path file = FMLPaths.CONFIGDIR.get().resolve(DistantFrontiers.MODID).resolve("abilities.json");
        List<String> errors = new ArrayList<>();
        try {
            Files.createDirectories(file.getParent());
            if (!Files.exists(file)) {
                Files.writeString(file, AbilitiesConfig.DEFAULT_JSON);
            }
            config = AbilitiesConfig.parse(Files.readString(file), errors);
            errors.forEach(e -> DistantFrontiers.LOGGER.warn("abilities.json: {}", e));
        } catch (Exception e) {
            errors.add("could not read abilities.json: " + e.getMessage());
            DistantFrontiers.LOGGER.error("Failed to load abilities.json, using defaults", e);
            config = AbilitiesConfig.defaults();
        }
        return errors;
    }

    /** Hunters dig. Appends SIEGER to an already-promoted mob (config-gated by caller). */
    public static void forceSieger(Mob mob) {
        CompoundTag data = mob.getPersistentData();
        String mods = data.getString(SpawnScaling.TAG_MODS);
        if (mods.isEmpty() || mods.contains(EliteModifier.SIEGER.id())) {
            return;
        }
        data.putString(SpawnScaling.TAG_MODS, mods + "," + EliteModifier.SIEGER.id());
    }

    // ------------------------------------------------------------------
    // Ticking abilities

    @SubscribeEvent
    public void onEntityTick(EntityTickEvent.Post event) {
        Entity entity = event.getEntity();
        if (!(entity instanceof Mob mob) || mob.level().isClientSide) {
            return;
        }
        if (mob.tickCount % 10 != 0) {
            return;
        }
        int tier = Elites.tier(mob);
        if (tier <= 0 || !(mob.level() instanceof ServerLevel level)) {
            return;
        }

        // Siegers act on the fast (0.5s) cadence so digging feels active.
        if (config.siegerEnabled() && Elites.hasModifier(mob, EliteModifier.SIEGER)) {
            tickSieger(mob, level);
        }

        if (mob.tickCount % 20 != 0) {
            return;
        }
        ambientTelegraph(mob, level);

        if (Elites.hasModifier(mob, EliteModifier.SUMMONER) && mob.getTarget() != null) {
            tickSummoner(mob, level);
        }
        if (Elites.hasModifier(mob, EliteModifier.CORROSIVE) && mob.getTarget() != null
                && mob.distanceToSqr(mob.getTarget()) < 16 && mob.tickCount % 160 == 0) {
            spawnAcid(level, mob.getX(), mob.getY(), mob.getZ(), 1.5f, 60);
        }
    }

    /** One second-interval particle puff per modifier so players can read the threat. */
    private static void ambientTelegraph(Mob mob, ServerLevel level) {
        String mods = mob.getPersistentData().getString(SpawnScaling.TAG_MODS);
        if (mods.isEmpty()) {
            return;
        }
        for (String id : mods.split(",")) {
            EliteModifier modifier = EliteModifier.byId(id);
            if (modifier == null) {
                continue;
            }
            SimpleParticleType particle = switch (modifier) {
                case SWIFT -> ParticleTypes.CLOUD;
                case STONEHIDE -> ParticleTypes.WHITE_ASH;
                case SUMMONER -> ParticleTypes.ENCHANT;
                case BLINKSTEP -> ParticleTypes.PORTAL;
                case CORROSIVE -> ParticleTypes.ITEM_SLIME;
                case VENGEFUL -> ParticleTypes.ANGRY_VILLAGER;
                case WARPER -> ParticleTypes.REVERSE_PORTAL;
                case SIEGER -> ParticleTypes.CRIT;
            };
            level.sendParticles(particle,
                    mob.getX(), mob.getY() + mob.getBbHeight() * 0.6, mob.getZ(),
                    3, 0.3, 0.4, 0.3, 0.02);
        }
    }

    private static void tickSummoner(Mob mob, ServerLevel level) {
        CompoundTag data = mob.getPersistentData();
        long now = level.getGameTime();
        if (now < data.getLong(TAG_SUMMON_READY)) {
            return;
        }
        long nearbyMinions = level.getEntitiesOfClass(Mob.class,
                        mob.getBoundingBox().inflate(16),
                        m -> m.getPersistentData().getBoolean(TAG_MINION))
                .size();
        if (nearbyMinions >= 2) {
            return;
        }
        data.putLong(TAG_SUMMON_READY, now + 240); // 12s
        level.playSound(null, mob.blockPosition(), SoundEvents.EVOKER_PREPARE_SUMMON,
                SoundSource.HOSTILE, 1.0f, 1.0f);
        for (int i = 0; i < 2; i++) {
            BlockPos pos = mob.blockPosition().offset(
                    level.random.nextInt(5) - 2, 0, level.random.nextInt(5) - 2);
            Silverfish minion = EntityType.SILVERFISH.spawn(level, pos, MobSpawnType.MOB_SUMMONED);
            if (minion != null) {
                minion.getPersistentData().putBoolean(TAG_MINION, true);
                minion.getPersistentData().putBoolean(TAG_NO_MARKS, true);
                level.sendParticles(ParticleTypes.ENCHANT,
                        pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                        20, 0.3, 0.5, 0.3, 0.5);
            }
        }
    }

    // ------------------------------------------------------------------
    // Sieger: dig to the hiding player

    private static void tickSieger(Mob mob, ServerLevel level) {
        CompoundTag data = mob.getPersistentData();
        if (data.getInt(TAG_BLOCKS_BROKEN) >= config.siegerMaxBlocksPerMob()) {
            return; // budget spent - they breach bunkers, they don't delete bases
        }
        LivingEntity target = mob.getTarget();
        if (!(target instanceof ServerPlayer player) || !player.isAlive()
                || mob.distanceToSqr(player) > 24 * 24) {
            clearBreaking(mob, level, data);
            return;
        }
        // Only dig when the target is actually out of reach: no line of sight,
        // or pathing has given up while the player is still away.
        boolean blocked = !mob.hasLineOfSight(player);
        boolean stuck = mob.getNavigation().isDone() && mob.distanceToSqr(player) > 16;
        if (!blocked && !stuck) {
            clearBreaking(mob, level, data);
            return;
        }

        BlockPos targetBlock = findBlockingBlock(mob, player, level);
        if (targetBlock == null || !breakable(level, targetBlock)) {
            clearBreaking(mob, level, data);
            return;
        }

        long encoded = targetBlock.asLong();
        if (data.getLong(TAG_BREAK_POS) != encoded) {
            clearBreaking(mob, level, data);
            data.putLong(TAG_BREAK_POS, encoded);
            data.putInt(TAG_BREAK_PROGRESS, 0);
        }

        BlockState state = level.getBlockState(targetBlock);
        int ticksNeeded = Math.max(20, Math.min(300,
                (int) (state.getDestroySpeed(level, targetBlock) * 30 / Math.max(0.1, config.siegerBreakSpeed()))));
        int progress = data.getInt(TAG_BREAK_PROGRESS) + 10;
        data.putInt(TAG_BREAK_PROGRESS, progress);

        mob.swing(InteractionHand.MAIN_HAND);
        level.playSound(null, targetBlock, state.getSoundType().getHitSound(),
                SoundSource.HOSTILE, 0.5f, 0.8f);

        if (progress >= ticksNeeded) {
            level.destroyBlock(targetBlock, config.siegerDropsBlocks(), mob);
            data.putInt(TAG_BLOCKS_BROKEN, data.getInt(TAG_BLOCKS_BROKEN) + 1);
            clearBreaking(mob, level, data);
        } else {
            level.destroyBlockProgress(mob.getId(), targetBlock,
                    Math.min(9, progress * 10 / ticksNeeded));
        }
    }

    /** First solid block on the eye-to-eye ray, within digging reach. */
    private static BlockPos findBlockingBlock(Mob mob, ServerPlayer player, ServerLevel level) {
        Vec3 from = mob.getEyePosition();
        Vec3 to = player.getEyePosition();
        BlockHitResult hit = level.clip(new ClipContext(
                from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mob));
        if (hit.getType() == HitResult.Type.BLOCK
                && hit.getBlockPos().distToCenterSqr(from.x, from.y, from.z) <= 4.5 * 4.5) {
            return hit.getBlockPos();
        }
        // Ray is clear but the mob is stuck: chew the block in front of its face.
        Vec3 direction = to.subtract(from);
        BlockPos ahead = BlockPos.containing(from.add(direction.normalize()));
        if (!level.getBlockState(ahead).isAir()) {
            return ahead;
        }
        return null;
    }

    private static boolean breakable(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) {
            return false;
        }
        float hardness = state.getDestroySpeed(level, pos);
        if (hardness < 0 || hardness > config.siegerMaxHardness()) {
            return false;
        }
        if (state.hasBlockEntity() && !config.siegerBreaksBlockEntities()) {
            return false; // chests, machines, storage - always safe
        }
        if (config.siegerBlockBlacklist().contains(
                BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString())) {
            return false;
        }
        RingManager mgr = RingManager.get();
        if (mgr != null) {
            Ring ring = mgr.ringAt(level, pos.getX(), pos.getZ());
            if (ring != null && ring.suppressHostileSpawns()) {
                return false; // the Hearth is inviolate
            }
        }
        return true;
    }

    private static void clearBreaking(Mob mob, ServerLevel level, CompoundTag data) {
        long encoded = data.getLong(TAG_BREAK_POS);
        if (encoded != 0) {
            level.destroyBlockProgress(mob.getId(), BlockPos.of(encoded), -1);
        }
        data.remove(TAG_BREAK_POS);
        data.remove(TAG_BREAK_PROGRESS);
    }

    // ------------------------------------------------------------------
    // On-hit abilities

    @SubscribeEvent
    public void onIncomingDamage(LivingIncomingDamageEvent event) {
        // Blinkstep: the elite escapes when hit.
        if (event.getEntity() instanceof Mob mob && mob.level() instanceof ServerLevel level
                && Elites.hasModifier(mob, EliteModifier.BLINKSTEP)) {
            blinkstep(mob, level);
        }
        // Warper: the PLAYER gets moved when hit by a warper.
        if (event.getEntity() instanceof ServerPlayer player
                && player.level() instanceof ServerLevel level
                && event.getSource().getEntity() instanceof Mob attacker
                && Elites.hasModifier(attacker, EliteModifier.WARPER)) {
            tryWarp(player, attacker, level);
        }
    }

    private static void blinkstep(Mob mob, ServerLevel level) {
        CompoundTag data = mob.getPersistentData();
        long now = level.getGameTime();
        if (now < data.getLong(TAG_BLINK_READY)) {
            return;
        }
        for (int attempt = 0; attempt < 8; attempt++) {
            double x = mob.getX() + (level.random.nextDouble() - 0.5) * 16;
            double y = mob.getY() + (level.random.nextInt(9) - 4);
            double z = mob.getZ() + (level.random.nextDouble() - 0.5) * 16;
            if (mob.randomTeleport(x, y, z, false)) {
                data.putLong(TAG_BLINK_READY, now + 120);
                level.playSound(null, BlockPos.containing(x, y, z), SoundEvents.ENDERMAN_TELEPORT,
                        SoundSource.HOSTILE, 1.0f, 1.2f);
                break;
            }
        }
    }

    private static void tryWarp(ServerPlayer player, Mob attacker, ServerLevel level) {
        if (player.isCreative() || player.isSpectator()) {
            return;
        }
        CompoundTag data = attacker.getPersistentData();
        long now = level.getGameTime();
        if (now < data.getLong(TAG_WARP_READY)) {
            return;
        }
        if (level.random.nextDouble() >= config.warperProcChance()) {
            return;
        }
        data.putLong(TAG_WARP_READY, now + config.warperCooldownTicks());

        Vec3 origin = player.position();
        RingManager mgr = RingManager.get();
        Ring ring = mgr == null ? null : mgr.ringAt(level, attacker.getX(), attacker.getZ());

        // The rare rift: straight into the Nether. Deep rings only, config-gated.
        if (ring != null && level.dimension() == Level.OVERWORLD
                && config.netherRiftRings().contains(ring.id())
                && level.random.nextDouble() < config.netherRiftChance()
                && riftToNether(player, attacker, level)) {
            warpFx(level, origin);
            return;
        }

        switch (config.rollWarpEffect(new McRand(level.random))) {
            case 0 -> toss(player, level, origin);
            case 1 -> swap(player, attacker, level, origin);
            default -> scatter(player, level, origin);
        }
    }

    /** Flung skyward: survivable fall damage, terrifying view. */
    private static void toss(ServerPlayer player, ServerLevel level, Vec3 origin) {
        int clear = 0;
        BlockPos feet = player.blockPosition();
        while (clear < 10 && level.getBlockState(feet.above(clear + 2))
                .getCollisionShape(level, feet.above(clear + 2)).isEmpty()) {
            clear++;
        }
        if (clear < 6) {
            scatter(player, level, origin);
            return;
        }
        player.teleportTo(player.getX(), player.getY() + clear, player.getZ());
        player.sendSystemMessage(Component.literal("The world flings you skyward!")
                .withStyle(ChatFormatting.DARK_PURPLE, ChatFormatting.ITALIC));
        warpFx(level, origin);
        warpFx(level, player.position());
    }

    /** Position swap with the attacker: suddenly you're in the middle of the pack. */
    private static void swap(ServerPlayer player, Mob attacker, ServerLevel level, Vec3 origin) {
        Vec3 mobPos = attacker.position();
        attacker.teleportTo(origin.x, origin.y, origin.z);
        player.teleportTo(mobPos.x, mobPos.y, mobPos.z);
        player.sendSystemMessage(Component.literal("You trade places with your attacker!")
                .withStyle(ChatFormatting.DARK_PURPLE, ChatFormatting.ITALIC));
        warpFx(level, origin);
        warpFx(level, mobPos);
    }

    /** Scattered sideways: lose your footing, your corridor, your plan. */
    private static void scatter(ServerPlayer player, ServerLevel level, Vec3 origin) {
        BlockPos pos = SpawnUtil.findGroundNear(level, player.blockPosition(), 10, 16);
        if (pos == null) {
            return;
        }
        player.teleportTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        player.sendSystemMessage(Component.literal("Space folds - you are elsewhere.")
                .withStyle(ChatFormatting.DARK_PURPLE, ChatFormatting.ITALIC));
        warpFx(level, origin);
        warpFx(level, player.position());
    }

    /** The story-generator: ripped into the Nether at 1:8 coordinates. */
    private static boolean riftToNether(ServerPlayer player, Mob attacker, ServerLevel level) {
        ServerLevel nether = level.getServer().getLevel(Level.NETHER);
        if (nether == null) {
            return false;
        }
        int x = (int) (player.getX() / 8);
        int z = (int) (player.getZ() / 8);
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int y = 96; y >= 34; y--) {
            cursor.set(x, y, z);
            boolean feetClear = nether.getBlockState(cursor).getCollisionShape(nether, cursor).isEmpty()
                    && nether.getBlockState(cursor).getFluidState().isEmpty();
            cursor.set(x, y + 1, z);
            boolean headClear = nether.getBlockState(cursor).getCollisionShape(nether, cursor).isEmpty();
            cursor.set(x, y - 1, z);
            boolean floorSolid = !nether.getBlockState(cursor).getCollisionShape(nether, cursor).isEmpty()
                    && nether.getBlockState(cursor).getFluidState().isEmpty();
            if (feetClear && headClear && floorSolid) {
                String mobName = attacker.getCustomName() == null
                        ? "a warper" : attacker.getCustomName().getString();
                player.teleportTo(nether, x + 0.5, y, z + 0.5, player.getYRot(), player.getXRot());
                player.playNotifySound(SoundEvents.PORTAL_TRAVEL, SoundSource.MASTER, 0.6f, 0.7f);
                player.sendSystemMessage(Component.literal(
                                "Reality tears. You smell sulfur. Find your own way home.")
                        .withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD));
                level.getServer().getPlayerList().broadcastSystemMessage(Component.literal(
                                "⚠ " + player.getName().getString() + " was ripped into the Nether by "
                                        + mobName + "!").withStyle(ChatFormatting.DARK_PURPLE), false);
                return true;
            }
        }
        return false;
    }

    private static void warpFx(ServerLevel level, Vec3 pos) {
        level.sendParticles(ParticleTypes.REVERSE_PORTAL, pos.x, pos.y + 1, pos.z,
                40, 0.4, 0.8, 0.4, 0.15);
        level.playSound(null, BlockPos.containing(pos.x, pos.y, pos.z),
                SoundEvents.ENDERMAN_TELEPORT, SoundSource.HOSTILE, 1.0f, 0.8f);
    }

    // ------------------------------------------------------------------
    // Death triggers

    /** Corrosive death clouds and Vengeful enrage triggers. */
    @SubscribeEvent
    public void onDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof Mob dead) || !(dead.level() instanceof ServerLevel level)) {
            return;
        }
        if (Elites.hasModifier(dead, EliteModifier.CORROSIVE)) {
            spawnAcid(level, dead.getX(), dead.getY(), dead.getZ(), 2.5f, 100);
        }

        // Nearby Vengeful elites enrage when any mob dies around them.
        for (Mob ally : level.getEntitiesOfClass(Mob.class,
                dead.getBoundingBox().inflate(12),
                m -> m != dead && Elites.hasModifier(m, EliteModifier.VENGEFUL))) {
            ally.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, 160, 1));
            ally.addEffect(new MobEffectInstance(MobEffects.DAMAGE_BOOST, 160, 0));
            level.sendParticles(ParticleTypes.ANGRY_VILLAGER,
                    ally.getX(), ally.getY() + ally.getBbHeight(), ally.getZ(),
                    10, 0.4, 0.4, 0.4, 0.1);
            level.playSound(null, ally.blockPosition(), SoundEvents.RAVAGER_ROAR,
                    SoundSource.HOSTILE, 0.8f, 1.3f);
        }
    }

    private static void spawnAcid(ServerLevel level, double x, double y, double z,
                                  float radius, int duration) {
        AreaEffectCloud cloud = new AreaEffectCloud(level, x, y, z);
        cloud.setRadius(radius);
        cloud.setDuration(duration);
        cloud.setWaitTime(10);
        cloud.setRadiusOnUse(-0.3f);
        cloud.setPotionContents(new PotionContents(Potions.HARMING));
        level.addFreshEntity(cloud);
    }
}
