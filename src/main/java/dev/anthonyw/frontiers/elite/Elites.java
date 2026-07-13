package dev.anthonyw.frontiers.elite;

import dev.anthonyw.frontiers.DistantFrontiers;
import dev.anthonyw.frontiers.core.EliteModifier;
import dev.anthonyw.frontiers.core.ModifierPicker;
import dev.anthonyw.frontiers.core.NameGen;
import dev.anthonyw.frontiers.event.SurgeManager;
import dev.anthonyw.frontiers.ring.Ring;
import dev.anthonyw.frontiers.scaling.SpawnScaling;
import dev.anthonyw.frontiers.util.McRand;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Rolls and applies elite/champion promotions. Selection rules (categories,
 * banned pairs) and naming live in the unit-tested core module; this class
 * applies the results to real mobs: tags, names, glow and attribute kits.
 * Ability behavior lives in {@link EliteBehaviors}.
 */
public final class Elites {
    private Elites() {
    }

    /** Natural spawn path: rolls chance (surge-aware), then promotes. */
    public static void maybePromote(Mob mob, Ring ring) {
        RandomSource random = mob.getRandom();
        double eliteChance = Math.min(0.9, ring.eliteChance() * SurgeManager.eliteMultiplier(ring.id()));
        if (eliteChance <= 0 || random.nextDouble() >= eliteChance) {
            return;
        }
        boolean champion = random.nextDouble() < ring.championChance();
        promote(mob, ring, champion, null);
    }

    /**
     * Forced promotion (bounty quarries, hunters, /rings simulate).
     * A non-null {@code baseName} pins the mob's personal name (bounty
     * quarries are named on the board before they exist).
     */
    public static void promote(Mob mob, Ring ring, boolean champion, String baseName) {
        CompoundTag data = mob.getPersistentData();
        if (data.getInt(SpawnScaling.TAG_TIER) > 0) {
            return; // already promoted; never stack
        }
        RandomSource random = mob.getRandom();
        List<EliteModifier> pool = resolvePool(ring);
        if (pool.isEmpty()) {
            pool = Arrays.asList(EliteModifier.values());
        }
        List<EliteModifier> chosen = ModifierPicker.pick(pool, champion ? 2 : 1, new McRand(random));
        if (chosen.isEmpty()) {
            return;
        }
        champion = champion && chosen.size() == 2;

        data.putInt(SpawnScaling.TAG_TIER, champion ? 2 : 1);
        data.putString(SpawnScaling.TAG_MODS,
                String.join(",", chosen.stream().map(EliteModifier::id).toList()));

        for (EliteModifier modifier : chosen) {
            applyAttributes(mob, modifier);
        }
        mob.setHealth(mob.getMaxHealth());

        String name = (baseName != null ? baseName : generateName(random))
                + " " + chosen.get(0).epithet();
        mob.setCustomName(Component.literal(name)
                .withStyle(champion ? ChatFormatting.GOLD : ChatFormatting.YELLOW));
        mob.setCustomNameVisible(true);
        if (champion) {
            mob.addEffect(new MobEffectInstance(MobEffects.GLOWING, -1, 0, false, false));
        }
    }

    /** Attribute-only parts of each modifier; ticking behavior is event-driven. */
    private static void applyAttributes(Mob mob, EliteModifier modifier) {
        switch (modifier) {
            case SWIFT -> {
                addModifier(mob, Attributes.MOVEMENT_SPEED, "swift_speed", 0.35,
                        AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
                addModifier(mob, Attributes.MAX_HEALTH, "swift_health", -0.25,
                        AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
            }
            case STONEHIDE -> {
                addModifier(mob, Attributes.ARMOR, "stonehide_armor", 8.0,
                        AttributeModifier.Operation.ADD_VALUE);
                addModifier(mob, Attributes.KNOCKBACK_RESISTANCE, "stonehide_kb", 0.6,
                        AttributeModifier.Operation.ADD_VALUE);
                addModifier(mob, Attributes.MOVEMENT_SPEED, "stonehide_speed", -0.15,
                        AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
            }
            case VENGEFUL, SUMMONER, BLINKSTEP, CORROSIVE, WARPER, SIEGER -> {
                // behavior-only; handled in EliteBehaviors
            }
        }
    }

    private static void addModifier(Mob mob, net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute> attribute,
                                    String path, double amount, AttributeModifier.Operation operation) {
        AttributeInstance instance = mob.getAttribute(attribute);
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath(DistantFrontiers.MODID, path);
        if (instance == null || instance.hasModifier(id)) {
            return;
        }
        instance.addPermanentModifier(new AttributeModifier(id, amount, operation));
    }

    private static List<EliteModifier> resolvePool(Ring ring) {
        List<EliteModifier> pool = new ArrayList<>();
        for (String id : ring.modifiers()) {
            if ("*".equals(id)) {
                return Arrays.asList(EliteModifier.values());
            }
            EliteModifier modifier = EliteModifier.byId(id);
            if (modifier != null) {
                pool.add(modifier);
            }
        }
        return pool;
    }

    public static String generateName(RandomSource random) {
        return NameGen.generate(new McRand(random));
    }

    public static boolean hasModifier(Mob mob, EliteModifier modifier) {
        String mods = mob.getPersistentData().getString(SpawnScaling.TAG_MODS);
        return !mods.isEmpty() && Arrays.asList(mods.split(",")).contains(modifier.id());
    }

    public static int tier(Mob mob) {
        return mob.getPersistentData().getInt(SpawnScaling.TAG_TIER);
    }
}
