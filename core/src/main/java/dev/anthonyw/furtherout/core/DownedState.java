package dev.anthonyw.furtherout.core;

/**
 * One downed player's timers. Instead of dying (when a friend is close enough
 * to help) a player goes down: they crawl, can't fight, and bleed out unless
 * someone crouches beside them long enough to pull them up.
 *
 * Rules:
 * - bleed-out counts down; it pauses while someone is actively reviving
 * - with no possible rescuer in range it drains {@code aloneBleedMultiplier}x faster
 * - every hit taken while down costs bleed time and resets revive progress,
 *   so friends have to clear the mobs first
 * - revive progress decays (2x speed) when the reviver stops
 * - crouching while down (and not being revived) for giveUpTicks = accept death
 */
public final class DownedState {
    public enum Result { CONTINUE, REVIVED, BLED_OUT, GAVE_UP }

    private int bleedTicks;
    private int reviveTicks;
    private int giveUpTicks;

    public DownedState(int bleedTicks) {
        this.bleedTicks = bleedTicks;
    }

    public Result tick(int dt, boolean beingRevived, boolean crouching, boolean rescuerInRange,
                       MechanicsConfig.Downed cfg) {
        if (beingRevived) {
            reviveTicks += dt;
            giveUpTicks = 0;
            if (reviveTicks >= cfg.reviveTicks()) {
                return Result.REVIVED;
            }
            return Result.CONTINUE; // bleeding pauses while a friend is working on you
        }
        reviveTicks = Math.max(0, reviveTicks - 2 * dt);

        if (crouching) {
            giveUpTicks += dt;
            if (giveUpTicks >= cfg.giveUpTicks()) {
                return Result.GAVE_UP;
            }
        } else {
            giveUpTicks = 0;
        }

        bleedTicks -= dt * (rescuerInRange ? 1 : cfg.aloneBleedMultiplier());
        return bleedTicks <= 0 ? Result.BLED_OUT : Result.CONTINUE;
    }

    /** A hit while down: costs bleed time (at least one second) and interrupts any revive. */
    public void onDamage(float amount, MechanicsConfig.Downed cfg) {
        int lost = (int) Math.max(20, Math.round(amount * cfg.ticksLostPerDamage()));
        bleedTicks -= lost;
        reviveTicks = 0;
    }

    public int bleedTicks() {
        return Math.max(0, bleedTicks);
    }

    public double reviveProgress(MechanicsConfig.Downed cfg) {
        return Math.min(1.0, reviveTicks / (double) cfg.reviveTicks());
    }

    public double giveUpProgress(MechanicsConfig.Downed cfg) {
        return Math.min(1.0, giveUpTicks / (double) cfg.giveUpTicks());
    }
}
