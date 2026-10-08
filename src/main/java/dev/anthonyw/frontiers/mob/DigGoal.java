package dev.anthonyw.frontiers.mob;

import dev.anthonyw.frontiers.config.Configs;
import dev.anthonyw.frontiers.core.DigRules;
import dev.anthonyw.frontiers.core.MechanicsConfig;
import dev.anthonyw.frontiers.ring.RingManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.EnumSet;

/**
 * Lets a mob tunnel to a player it can't reach. Players hiding in a sealed
 * hut, a 1x1 hole or a pillar base are no longer safe outside the Hearth.
 *
 * The mob only digs when there is no walkable path to its quarry. It mines
 * the first solid block between it and the player (with the vanilla crack
 * animation and hit sounds, so you hear it coming), then lets its normal AI
 * charge through the gap. Diggers can sense a player through walls within
 * {@code senseRange}, so you can't hide by breaking line of sight.
 *
 * Limits (all in mechanics.json): hardness cap, no block entities (chests and
 * machines are always safe), a block blacklist, nothing in the safe zone, and
 * a lifetime budget of blocks per mob.
 */
public final class DigGoal extends Goal {
    public static final String TAG_DUG = "df_dug";
    private static final double REACH = 3.5;

    private final Mob mob;
    private int recheck;
    @Nullable
    private ServerPlayer quarry;
    @Nullable
    private BlockPos target;
    private int progress;
    private int needed;

    public DigGoal(Mob mob) {
        this.mob = mob;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    public static void attach(Mob mob) {
        boolean present = mob.goalSelector.getAvailableGoals().stream()
                .anyMatch(wrapped -> wrapped.getGoal() instanceof DigGoal);
        if (!present) {
            mob.goalSelector.addGoal(1, new DigGoal(mob));
        }
    }

    @Override
    public boolean canUse() {
        if (--recheck > 0) {
            return false;
        }
        recheck = 20;
        MechanicsConfig.Digging cfg = Configs.mechanics().digging();
        if (!cfg.enabled() || !(mob.level() instanceof ServerLevel level)) {
            return false;
        }
        if (cfg.respectMobGriefingRule() && !level.getGameRules().getBoolean(GameRules.RULE_MOBGRIEFING)) {
            return false;
        }
        if (mob.getPersistentData().getInt(TAG_DUG) >= cfg.maxBlocksPerMob()) {
            return false;
        }
        ServerPlayer player = findQuarry(level, cfg);
        if (player == null) {
            return false;
        }
        if (mob.getTarget() == null) {
            mob.setTarget(player); // sensed through the wall: now it's coming
        }
        if (canReach(player)) {
            return false;
        }
        BlockPos block = findObstruction(level, player);
        if (block == null || verdict(level, block, cfg) != DigRules.Verdict.OK) {
            return false;
        }
        quarry = player;
        target = block;
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        if (quarry == null || target == null || !quarry.isAlive() || !(mob.level() instanceof ServerLevel level)) {
            return false;
        }
        if (mob.distanceToSqr(target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5) > (REACH + 1) * (REACH + 1)) {
            return false;
        }
        return verdict(level, target, Configs.mechanics().digging()) == DigRules.Verdict.OK;
    }

    @Override
    public void start() {
        progress = 0;
        ServerLevel level = (ServerLevel) mob.level();
        float hardness = level.getBlockState(target).getDestroySpeed(level, target);
        needed = DigRules.breakTicks(hardness, Configs.mechanics().digging().breakSpeed());
        mob.getNavigation().stop();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        if (target == null || !(mob.level() instanceof ServerLevel level)) {
            return;
        }
        mob.getLookControl().setLookAt(target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5);
        progress++;
        if (progress % 5 == 0) {
            BlockState state = level.getBlockState(target);
            mob.swing(InteractionHand.MAIN_HAND);
            level.playSound(null, target, state.getSoundType().getHitSound(), SoundSource.HOSTILE, 0.8f, 0.7f);
            level.destroyBlockProgress(mob.getId(), target, Math.min(9, progress * 10 / Math.max(1, needed)));
        }
        if (progress >= needed) {
            level.destroyBlockProgress(mob.getId(), target, -1);
            level.destroyBlock(target, Configs.mechanics().digging().dropBlocks(), mob);
            mob.getPersistentData().putInt(TAG_DUG, mob.getPersistentData().getInt(TAG_DUG) + 1);
            if (quarry != null) {
                mob.setTarget(quarry);
            }
            target = null;
            recheck = 0; // look for the next block right away
        }
    }

    @Override
    public void stop() {
        if (target != null && mob.level() instanceof ServerLevel level) {
            level.destroyBlockProgress(mob.getId(), target, -1);
        }
        target = null;
        progress = 0;
    }

    @Nullable
    private ServerPlayer findQuarry(ServerLevel level, MechanicsConfig.Digging cfg) {
        if (mob.getTarget() instanceof ServerPlayer p && valid(p) && mob.distanceToSqr(p) <= 20 * 20) {
            return p;
        }
        Player nearest = level.getNearestPlayer(mob.getX(), mob.getY(), mob.getZ(), cfg.senseRange(),
                e -> e instanceof ServerPlayer sp && valid(sp));
        return nearest instanceof ServerPlayer sp ? sp : null;
    }

    private static boolean valid(ServerPlayer p) {
        return p.isAlive() && !p.isCreative() && !p.isSpectator();
    }

    private boolean canReach(ServerPlayer player) {
        Path path = mob.getNavigation().createPath(player, 0);
        return path != null && path.canReach();
    }

    /** The first solid block between the mob and the player, within arm's reach. */
    @Nullable
    private BlockPos findObstruction(ServerLevel level, ServerPlayer player) {
        Vec3 eye = mob.getEyePosition();
        BlockHitResult hit = level.clip(new ClipContext(eye, player.getEyePosition(),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mob));
        if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().distToCenterSqr(eye) <= REACH * REACH) {
            return hit.getBlockPos();
        }
        // Clear line of sight but no path (e.g. a wall at foot level): chew what's in front.
        Vec3 toward = new Vec3(player.getX() - mob.getX(), 0, player.getZ() - mob.getZ());
        if (toward.lengthSqr() < 1.0e-4) {
            return null;
        }
        Vec3 step = toward.normalize();
        BlockPos head = BlockPos.containing(eye.add(step));
        BlockPos feet = BlockPos.containing(mob.position().add(step).add(0, 0.5, 0));
        BlockPos[] candidates = player.getY() < mob.getY() - 1.5
                ? new BlockPos[]{feet.below(), feet, head}
                : new BlockPos[]{head, feet};
        for (BlockPos pos : candidates) {
            if (!level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()) {
                return pos;
            }
        }
        return null;
    }

    private DigRules.Verdict verdict(ServerLevel level, BlockPos pos, MechanicsConfig.Digging cfg) {
        BlockState state = level.getBlockState(pos);
        return DigRules.canBreak(
                state.isAir() || state.getCollisionShape(level, pos).isEmpty(),
                state.getDestroySpeed(level, pos),
                state.hasBlockEntity(),
                BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(),
                RingManager.isSafe(level, pos),
                mob.getPersistentData().getInt(TAG_DUG),
                cfg);
    }
}
