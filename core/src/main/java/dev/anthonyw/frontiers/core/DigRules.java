package dev.anthonyw.frontiers.core;

/**
 * Rules for mobs that tunnel to a hiding player. They breach hideouts; they
 * don't erase bases: no chests/machines (block entities), nothing harder than
 * the cap, nothing blacklisted, never inside the safe zone, and a lifetime
 * block budget per mob.
 */
public final class DigRules {
    public enum Verdict { OK, AIR, UNBREAKABLE, TOO_HARD, BLOCK_ENTITY, BLACKLISTED, SAFE_ZONE, BUDGET_SPENT }

    private DigRules() {
    }

    public static Verdict canBreak(boolean isAir, float hardness, boolean hasBlockEntity, String blockId,
                                   boolean inSafeZone, int brokenSoFar, MechanicsConfig.Digging cfg) {
        if (isAir) {
            return Verdict.AIR;
        }
        if (inSafeZone) {
            return Verdict.SAFE_ZONE;
        }
        if (brokenSoFar >= cfg.maxBlocksPerMob()) {
            return Verdict.BUDGET_SPENT;
        }
        if (hardness < 0) {
            return Verdict.UNBREAKABLE;
        }
        if (hardness > cfg.maxHardness()) {
            return Verdict.TOO_HARD;
        }
        if (hasBlockEntity && cfg.protectBlockEntities()) {
            return Verdict.BLOCK_ENTITY;
        }
        if (cfg.blockBlacklist().contains(blockId)) {
            return Verdict.BLACKLISTED;
        }
        return Verdict.OK;
    }

    /** Ticks to break a block: ~1.5s per point of hardness, clamped to 1s..15s. */
    public static int breakTicks(float hardness, double breakSpeed) {
        int ticks = (int) Math.round(hardness * 30 / Math.max(0.1, breakSpeed));
        return Math.max(20, Math.min(300, ticks));
    }

    /** Elites always dig (if enabled); normal mobs dig if their type is listed and the ring's roll hits. */
    public static boolean shouldDig(String entityId, boolean elite, double ringDigChance, double roll,
                                    MechanicsConfig.Digging cfg) {
        if (!cfg.enabled()) {
            return false;
        }
        if (elite && cfg.elitesAlwaysDig()) {
            return true;
        }
        return cfg.diggers().contains(entityId) && roll < ringDigChance;
    }
}
