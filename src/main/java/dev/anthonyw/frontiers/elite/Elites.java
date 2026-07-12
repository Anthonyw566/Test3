package dev.anthonyw.frontiers.elite;

import dev.anthonyw.frontiers.ring.Ring;
import dev.anthonyw.frontiers.scaling.SpawnScaling;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.util.RandomSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Mob;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;

/**
 * Rolls elite and champion promotions for freshly spawned hostiles.
 *
 * The generated name IS the telegraph: the epithet always states the mob's
 * headline modifier ("Belgath the Vile" leaves acid, guaranteed). Champions
 * additionally glow. Stage 2 wires the modifiers' actual behaviors; the
 * promotion, naming, pairing rules and inspection data are final here.
 */
public final class Elites {
    private static final String[] NAME_START = {
            "Kar", "Mor", "Vel", "Dra", "Ul", "Bel", "Naz", "Thra", "Gor", "Sel", "Az", "Ir"};
    private static final String[] NAME_END = {
            "gath", "ok", "ira", "un", "eth", "maw", "rik", "osh", "ul", "ez", "ar", "im"};

    private Elites() {
    }

    public static void maybePromote(Mob mob, Ring ring) {
        RandomSource random = mob.getRandom();
        if (ring.eliteChance() <= 0 || random.nextDouble() >= ring.eliteChance()) {
            return;
        }
        List<EliteModifier> pool = resolvePool(ring);
        if (pool.isEmpty()) {
            return;
        }

        boolean champion = random.nextDouble() < ring.championChance();
        List<EliteModifier> chosen = pick(pool, champion ? 2 : 1, random);
        if (chosen.isEmpty()) {
            return;
        }
        champion = chosen.size() == 2; // a one-modifier pool can't produce a champion

        CompoundTag data = mob.getPersistentData();
        data.putInt(SpawnScaling.TAG_TIER, champion ? 2 : 1);
        data.putString(SpawnScaling.TAG_MODS,
                String.join(",", chosen.stream().map(EliteModifier::id).toList()));

        String name = generateName(random) + " " + chosen.get(0).epithet();
        mob.setCustomName(Component.literal(name)
                .withStyle(champion ? ChatFormatting.GOLD : ChatFormatting.YELLOW));
        mob.setCustomNameVisible(true);
        if (champion) {
            mob.addEffect(new MobEffectInstance(MobEffects.GLOWING, -1, 0, false, false));
        }
        // TODO(stage 2): apply each modifier's attributes and ticking behavior.
    }

    /**
     * Draws up to {@code count} modifiers: categories must differ and banned
     * pairs never roll, so champions are spicy but always fair.
     */
    private static List<EliteModifier> pick(List<EliteModifier> pool, int count, RandomSource random) {
        List<EliteModifier> remaining = new ArrayList<>(pool);
        List<EliteModifier> out = new ArrayList<>();
        EnumSet<EliteModifier.Category> usedCategories = EnumSet.noneOf(EliteModifier.Category.class);
        while (out.size() < count && !remaining.isEmpty()) {
            EliteModifier candidate = remaining.remove(random.nextInt(remaining.size()));
            if (usedCategories.contains(candidate.category())) {
                continue;
            }
            if (out.stream().anyMatch(existing -> banned(existing, candidate))) {
                continue;
            }
            out.add(candidate);
            usedCategories.add(candidate.category());
        }
        return out;
    }

    /** Pairs that are individually fine but miserable together. */
    private static boolean banned(EliteModifier a, EliteModifier b) {
        return (a == EliteModifier.SUMMONER && b == EliteModifier.VENGEFUL)
                || (a == EliteModifier.VENGEFUL && b == EliteModifier.SUMMONER);
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

    private static String generateName(RandomSource random) {
        return NAME_START[random.nextInt(NAME_START.length)]
                + NAME_END[random.nextInt(NAME_END.length)];
    }
}
