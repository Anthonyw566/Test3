package dev.anthonyw.furtherout.core;

/**
 * Rules for mobs that tunnel to a hiding player. They dig you out of a hole
 * in the ground; they never take a base apart:
 * - only natural terrain (stone, dirt, sand, gravel... see the
 *   furtherout:diggable block tag), never anything you'd build with
 * - never within {@code baseRadius} of a bed, chest, furnace or machine
 * - never a block entity itself, nothing harder than the cap, nothing
 *   blacklisted, never inside the safe zone
 * - a small lifetime block budget per mob
 */
public final class DigRules {
    public enum Verdict {
        OK, AIR, UNBREAKABLE, TOO_HARD, BLOCK_ENTITY, BLACKLISTED, NOT_NATURAL, NEAR_BASE, SAFE_ZONE, BUDGET_SPENT
    }

    /** What the mob is looking at. {@code natural} = in the furtherout:diggable tag. */
    public record Block(boolean isAir, float hardness, boolean hasBlockEntity, String id, boolean natural) {
    }

    /** Where it is. {@code nearBase} = a block entity (bed, chest, machine...) within baseRadius. */
    public record Place(boolean inSafeZone, boolean nearBase, int brokenSoFar) {
    }

    private DigRules() {
    }

    public static Verdict canBreak(Block block, Place place, MechanicsConfig.Digging cfg) {
        if (block.isAir()) {
            return Verdict.AIR;
        }
        if (place.inSafeZone()) {
            return Verdict.SAFE_ZONE;
        }
        if (place.brokenSoFar() >= cfg.maxBlocksPerMob()) {
            return Verdict.BUDGET_SPENT;
        }
        if (block.hardness() < 0) {
            return Verdict.UNBREAKABLE;
        }
        if (block.hardness() > cfg.maxHardness()) {
            return Verdict.TOO_HARD;
        }
        if (block.hasBlockEntity() && cfg.protectBlockEntities()) {
            return Verdict.BLOCK_ENTITY;
        }
        if (cfg.blockBlacklist().contains(block.id())) {
            return Verdict.BLACKLISTED;
        }
        if (cfg.naturalBlocksOnly() && !block.natural()) {
            return Verdict.NOT_NATURAL;
        }
        if (cfg.baseRadius() > 0 && place.nearBase()) {
            return Verdict.NEAR_BASE;
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
