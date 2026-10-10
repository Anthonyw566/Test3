package dev.anthonyw.furtherout.mob;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.EnumSet;

/**
 * "What was that?" - an idle monster walks over to where it heard a noise,
 * gives up after a while, and forgets about it once it gets there. Anything
 * with a real target (a player in sight) takes priority.
 */
public final class InvestigateGoal extends Goal {
    private static final int PRIORITY = 4; // above wandering, below attacking

    private final PathfinderMob mob;
    @Nullable
    private Vec3 spot;
    private long until;

    private InvestigateGoal(PathfinderMob mob) {
        this.mob = mob;
        setFlags(EnumSet.of(Flag.MOVE));
    }

    public static void attach(Mob mob) {
        if (mob instanceof PathfinderMob pathfinder && find(mob) == null) {
            mob.goalSelector.addGoal(PRIORITY, new InvestigateGoal(pathfinder));
        }
    }

    /** Sends the mob to look at {@code spot} for up to {@code ticks}. */
    public static void send(Mob mob, Vec3 spot, int ticks) {
        attach(mob);
        InvestigateGoal goal = find(mob);
        if (goal != null) {
            goal.spot = spot;
            goal.until = mob.level().getGameTime() + ticks;
        }
    }

    /** Where the mob is heading to look, or null. */
    @Nullable
    public static Vec3 spot(Mob mob) {
        InvestigateGoal goal = find(mob);
        return goal == null ? null : goal.spot;
    }

    @Nullable
    private static InvestigateGoal find(Mob mob) {
        return mob.goalSelector.getAvailableGoals().stream()
                .map(wrapped -> wrapped.getGoal())
                .filter(g -> g instanceof InvestigateGoal)
                .map(g -> (InvestigateGoal) g)
                .findFirst().orElse(null);
    }

    private boolean stillCurious() {
        return spot != null && mob.getTarget() == null && mob.level().getGameTime() < until
                && mob.distanceToSqr(spot) > 9;
    }

    @Override
    public boolean canUse() {
        if (spot != null && mob.level().getGameTime() >= until) {
            spot = null;
        }
        return stillCurious();
    }

    @Override
    public void start() {
        mob.getNavigation().moveTo(spot.x, spot.y, spot.z, 1.0);
    }

    @Override
    public boolean canContinueToUse() {
        return stillCurious() && !mob.getNavigation().isDone();
    }

    @Override
    public void stop() {
        if (spot != null && (mob.distanceToSqr(spot) <= 9 || mob.level().getGameTime() >= until)) {
            spot = null; // got there, or lost interest
        }
    }
}
