package dev.anthonyw.frontiers.mob;

import dev.anthonyw.frontiers.DistantFrontiers;
import dev.anthonyw.frontiers.config.Configs;
import dev.anthonyw.frontiers.core.DigRules;
import dev.anthonyw.frontiers.core.EliteModifier;
import dev.anthonyw.frontiers.core.MechanicsConfig;
import dev.anthonyw.frontiers.core.ModifierPicker;
import dev.anthonyw.frontiers.core.NameGen;
import dev.anthonyw.frontiers.ring.Ring;
import dev.anthonyw.frontiers.ring.RingManager;
import dev.anthonyw.frontiers.util.McRand;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

import java.util.ArrayList;
import java.util.List;

/**
 * Everything a mob can be given: ring scaling (once, capped), elite/champion
 * promotion with a generated name that states its abilities, and the digger
 * flag. All state lives in the mob's persistent data so it survives restarts.
 */
public final class Elites {
    public static final String TAG_SCALED = "df_scaled";
    public static final String TAG_RING = "df_ring";
    public static final String TAG_TIER = "df_tier"; // 0 normal, 1 elite, 2 champion
    public static final String TAG_MODS = "df_mods";
    public static final String TAG_DIGGER = "df_digger";

    private static final ResourceLocation RING_HEALTH = id("ring_health");
    private static final ResourceLocation RING_DAMAGE = id("ring_damage");
    private static final ResourceLocation ELITE_HEALTH = id("elite_health");

    private Elites() {
    }

    // ------------------------------------------------------------------ queries

    public static int tier(Mob mob) {
        return mob.getPersistentData().getInt(TAG_TIER);
    }

    public static boolean isElite(Mob mob) {
        return tier(mob) > 0;
    }

    public static List<EliteModifier> modifiers(Mob mob) {
        List<EliteModifier> out = new ArrayList<>();
        String csv = mob.getPersistentData().getString(TAG_MODS);
        if (!csv.isEmpty()) {
            for (String id : csv.split(",")) {
                EliteModifier m = EliteModifier.byId(id);
                if (m != null) {
                    out.add(m);
                }
            }
        }
        return out;
    }

    public static boolean has(Mob mob, EliteModifier modifier) {
        String csv = mob.getPersistentData().getString(TAG_MODS);
        return !csv.isEmpty() && (csv.equals(modifier.id()) || csv.startsWith(modifier.id() + ",")
                || csv.endsWith("," + modifier.id()) || csv.contains("," + modifier.id() + ","));
    }

    public static String homeRing(Mob mob) {
        return mob.getPersistentData().getString(TAG_RING);
    }

    public static String displayName(Mob mob) {
        return mob.getCustomName() != null ? mob.getCustomName().getString() : mob.getName().getString();
    }

    // ------------------------------------------------------------------ scaling

    /** Applies the ring's (capped) health and damage multipliers exactly once. */
    public static void scale(Mob mob, Ring ring) {
        CompoundTag data = mob.getPersistentData();
        if (data.getBoolean(TAG_SCALED)) {
            return;
        }
        RingManager mgr = RingManager.get();
        double maxHealth = mgr == null ? 2.0 : mgr.maxHealthMult();
        double maxDamage = mgr == null ? 2.0 : mgr.maxDamageMult();
        data.putBoolean(TAG_SCALED, true);
        data.putString(TAG_RING, ring.id());
        multiply(mob, Attributes.MAX_HEALTH, RING_HEALTH, Math.min(ring.healthMult(), maxHealth));
        multiply(mob, Attributes.ATTACK_DAMAGE, RING_DAMAGE, Math.min(ring.damageMult(), maxDamage));
        mob.setHealth(mob.getMaxHealth());
    }

    // ------------------------------------------------------------------ elites

    /** Natural spawns: roll the ring's elite chance, then champion chance. */
    public static void rollElite(Mob mob, Ring ring) {
        if (ring.eliteChance() <= 0 || mob.getRandom().nextDouble() >= ring.eliteChance()) {
            return;
        }
        boolean champion = mob.getRandom().nextDouble() < ring.championChance();
        List<EliteModifier> pool = ModifierPicker.resolvePool(ring.modifiers());
        List<EliteModifier> mods = ModifierPicker.pick(pool, champion ? 2 : 1, new McRand(mob.getRandom()));
        promote(mob, mods);
    }

    /**
     * Turns a mob into an elite (1 ability) or champion (2+): name, glow,
     * health bonus, and digging (elites always dig by default).
     */
    public static void promote(Mob mob, List<EliteModifier> mods) {
        if (mods.isEmpty() || isElite(mob)) {
            return;
        }
        MechanicsConfig.Elites cfg = Configs.mechanics().elites();
        boolean champion = mods.size() >= 2;
        CompoundTag data = mob.getPersistentData();
        data.putInt(TAG_TIER, champion ? 2 : 1);
        data.putString(TAG_MODS, String.join(",", mods.stream().map(EliteModifier::id).toList()));

        multiply(mob, Attributes.MAX_HEALTH, ELITE_HEALTH,
                1.0 + (champion ? cfg.championHealthBonus() : cfg.eliteHealthBonus()));
        mob.setHealth(mob.getMaxHealth());

        mob.setCustomName(Component.literal(nameFor(NameGen.generate(new McRand(mob.getRandom())), mods))
                .withStyle(champion ? ChatFormatting.GOLD : ChatFormatting.YELLOW));
        mob.setCustomNameVisible(true);
        // Not persistent: elites despawn like any mob, so they never pile up in the wild.
        // (Thieves become persistent only while carrying someone's item.)
        if (champion) {
            mob.addEffect(new MobEffectInstance(MobEffects.GLOWING, -1, 0, false, false));
        }
        if (Configs.mechanics().digging().enabled() && Configs.mechanics().digging().elitesAlwaysDig()) {
            makeDigger(mob);
        }
    }

    /** "Karok the Warping" / "Karok the Thief & Volatile". */
    public static String nameFor(String base, List<EliteModifier> mods) {
        StringBuilder name = new StringBuilder(base).append(' ').append(mods.get(0).epithet());
        for (int i = 1; i < mods.size(); i++) {
            name.append(" & ").append(mods.get(i).epithet().replaceFirst("^the ", ""));
        }
        String s = name.toString();
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ------------------------------------------------------------------ digging

    /** Natural spawns: listed mob types dig with the ring's dig chance. */
    public static void rollDigger(Mob mob, Ring ring) {
        String type = BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()).toString();
        if (DigRules.shouldDig(type, isElite(mob), ring.digChance(), mob.getRandom().nextDouble(),
                Configs.mechanics().digging())) {
            makeDigger(mob);
        }
    }

    public static void makeDigger(Mob mob) {
        mob.getPersistentData().putBoolean(TAG_DIGGER, true);
        DigGoal.attach(mob);
    }

    public static boolean isDigger(Mob mob) {
        return mob.getPersistentData().getBoolean(TAG_DIGGER);
    }

    // ------------------------------------------------------------------ helpers

    private static void multiply(Mob mob, Holder<Attribute> attribute, ResourceLocation id, double mult) {
        AttributeInstance instance = mob.getAttribute(attribute);
        if (instance == null || mult == 1.0 || instance.hasModifier(id)) {
            return;
        }
        instance.addPermanentModifier(new AttributeModifier(id, mult - 1.0,
                AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(DistantFrontiers.MODID, path);
    }
}
