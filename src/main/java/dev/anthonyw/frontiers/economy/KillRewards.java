package dev.anthonyw.frontiers.economy;

import dev.anthonyw.frontiers.contract.ContractBoard;
import dev.anthonyw.frontiers.elite.EliteBehaviors;
import dev.anthonyw.frontiers.elite.Elites;
import dev.anthonyw.frontiers.event.SurgeManager;
import dev.anthonyw.frontiers.heat.HeatManager;
import dev.anthonyw.frontiers.ring.Ring;
import dev.anthonyw.frontiers.ring.RingManager;
import dev.anthonyw.frontiers.scaling.SpawnScaling;
import dev.anthonyw.frontiers.state.FrontiersState;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.Mob;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;

/**
 * Turns kills into consequences: Marks (with daily diminishing returns and
 * every farm exploit closed), Heat bumps, champion kill announcements,
 * bounty completion and charter progress. Also applies the player death rule.
 *
 * Farm-proofing summary:
 * - only real players earn credit (FakePlayers and machines never do)
 * - mobs from spawners/summons/machines never carry a tier in the first place
 * - ambush mobs and summoned minions are tagged no-marks
 * - kills inside the Hearth pay nothing (no dragging champions to golems)
 * - past 10 elite kills per day payouts halve, past 30 they stop
 */
public final class KillRewards {

    @SubscribeEvent
    public void onDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && !(player instanceof FakePlayer)) {
            HeatManager.onPlayerDeath(player);
            return;
        }
        if (!(event.getEntity() instanceof Mob mob) || !(mob.level() instanceof ServerLevel level)) {
            return;
        }

        CompoundTag data = mob.getPersistentData();
        ServerPlayer killer = null;
        if (event.getSource().getEntity() instanceof ServerPlayer sp && !(sp instanceof FakePlayer)) {
            killer = sp;
        }

        int bountyId = data.getInt(ContractBoard.TAG_BOUNTY_ID);
        if (bountyId > 0) {
            ContractBoard.onBountyMobDeath(level, mob, bountyId, killer);
        }

        int tier = Elites.tier(mob);
        if (tier <= 0 || killer == null) {
            return;
        }

        // Killing elites angers the frontier no matter where or how.
        HeatManager.addKillHeat(killer, tier);

        RingManager mgr = RingManager.get();
        FrontiersState state = FrontiersState.get(level.getServer());
        Ring deathRing = mgr == null ? null : mgr.ringAt(level, mob.getX(), mob.getZ());
        boolean inSafeZone = deathRing == null || deathRing.danger() <= 0;

        String homeRing = data.getString(SpawnScaling.TAG_RING);
        if (!inSafeZone && !homeRing.isEmpty()) {
            ContractBoard.onEliteKilledForCharter(killer, homeRing);
        }

        // Champion fanfare + bonus XP (bounty quarries get their own broadcast).
        if (tier >= 2 && bountyId == 0 && mob.getCustomName() != null) {
            String where = deathRing == null ? "" : " (" + deathRing.name() + ")";
            level.getServer().getPlayerList().broadcastSystemMessage(Component.literal(
                            "☠ " + mob.getCustomName().getString() + " has fallen to "
                                    + killer.getName().getString() + where + ".")
                    .withStyle(ChatFormatting.GOLD), false);
            int danger = deathRing == null ? 2 : deathRing.danger();
            ExperienceOrb.award(level, mob.position(), 20 + 10 * danger);
        }

        // Hunters pay well and killing one buys breathing room.
        if (data.getBoolean(ContractBoard.TAG_HUNTER)) {
            int danger = deathRing == null ? 2 : deathRing.danger();
            int reward = 10 + 5 * danger;
            state.addField(killer.getUUID(), reward);
            HeatManager.relieveHeat(killer, 25);
            killer.sendSystemMessage(Component.literal(
                            "The hunter is slain. ◈ " + reward + " (field) — the frontier loses your scent.")
                    .withStyle(ChatFormatting.GOLD));
            return;
        }

        // Standard elite trickle.
        if (inSafeZone || data.getBoolean(EliteBehaviors.TAG_NO_MARKS) || bountyId > 0) {
            return;
        }
        int danger = deathRing.danger();
        double base = tier >= 2 ? danger * 3 : danger;
        double diminishing = state.registerEliteKill(killer.getUUID());
        double surge = SurgeManager.marksMultiplier(deathRing.id());
        int payout = (int) Math.round(base * diminishing * surge);
        if (payout > 0) {
            state.addField(killer.getUUID(), payout);
            killer.displayClientMessage(Component.literal("+◈ " + payout + " (field)")
                    .withStyle(ChatFormatting.AQUA), true);
        }
    }
}
