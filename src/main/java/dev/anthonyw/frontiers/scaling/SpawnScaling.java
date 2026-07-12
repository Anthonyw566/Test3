package dev.anthonyw.frontiers.scaling;

import dev.anthonyw.frontiers.DistantFrontiers;
import dev.anthonyw.frontiers.elite.Elites;
import dev.anthonyw.frontiers.ring.Ring;
import dev.anthonyw.frontiers.ring.RingManager;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Enemy;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.FinalizeSpawnEvent;

/**
 * Assigns a ring tier to hostile mobs when they spawn and applies the ring's
 * (hard-capped) stat scaling exactly once. Also enforces the Hearth's
 * no-natural-hostiles rule.
 *
 * Design rules encoded here:
 * - Tier is decided at spawn from spawn position and never re-rolled when the
 *   mob wanders across a boundary.
 * - Spawner/summoned/machine mobs are excluded (no scaling, no elites) so mob
 *   farms cannot print elite loot.
 * - Attribute modifiers use fixed ResourceLocation ids and a persistent-data
 *   flag so they can never stack, even if another mod refires spawn events.
 */
public final class SpawnScaling {
    public static final String TAG_SCALED = "df_scaled";
    public static final String TAG_RING = "df_ring";
    public static final String TAG_TIER = "df_tier"; // 0 normal, 1 elite, 2 champion
    public static final String TAG_MODS = "df_mods";

    private static final ResourceLocation HEALTH_ID = id("ring_health");
    private static final ResourceLocation DAMAGE_ID = id("ring_damage");
    private static final ResourceLocation SPEED_ID = id("ring_speed");

    @SubscribeEvent
    public void onFinalizeSpawn(FinalizeSpawnEvent event) {
        Mob mob = event.getEntity();
        if (!(mob.level() instanceof ServerLevel level)) {
            return;
        }
        if (!isHostile(mob)) {
            return;
        }
        RingManager mgr = RingManager.get();
        if (mgr == null) {
            return;
        }
        Ring ring = mgr.ringAt(level, mob.getX(), mob.getZ());
        if (ring == null) {
            return;
        }

        boolean natural = mgr.isNaturalSpawnType(event.getSpawnType());

        // Hearth: block natural hostile spawns outright. Mobs dragged, summoned
        // or spawned by machines are left alone so nothing a player owns can
        // silently vanish.
        if (ring.suppressHostileSpawns()) {
            if (natural) {
                event.setCanceled(true);
                mob.discard();
            }
            return;
        }

        if (!natural || mgr.isExcluded(mob)) {
            return;
        }

        CompoundTag data = mob.getPersistentData();
        if (data.getBoolean(TAG_SCALED)) {
            return;
        }
        data.putBoolean(TAG_SCALED, true);
        data.putString(TAG_RING, ring.id());

        applyRingScaling(mob, ring, mgr);
        Elites.maybePromote(mob, ring);
    }

    private static void applyRingScaling(Mob mob, Ring ring, RingManager mgr) {
        addMultiplier(mob.getAttribute(Attributes.MAX_HEALTH), HEALTH_ID,
                Math.min(ring.healthMult(), mgr.maxHealthMult()) - 1.0);
        addMultiplier(mob.getAttribute(Attributes.ATTACK_DAMAGE), DAMAGE_ID,
                Math.min(ring.damageMult(), mgr.maxDamageMult()) - 1.0);
        addMultiplier(mob.getAttribute(Attributes.MOVEMENT_SPEED), SPEED_ID,
                Math.min(ring.speedMult(), mgr.maxSpeedMult()) - 1.0);
        mob.setHealth(mob.getMaxHealth());
    }

    private static void addMultiplier(AttributeInstance attribute, ResourceLocation id, double amount) {
        if (attribute == null || amount == 0.0 || attribute.hasModifier(id)) {
            return;
        }
        attribute.addPermanentModifier(new AttributeModifier(id, amount,
                AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
    }

    private static boolean isHostile(Mob mob) {
        return mob instanceof Enemy || mob.getType().getCategory() == MobCategory.MONSTER;
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(DistantFrontiers.MODID, path);
    }
}
