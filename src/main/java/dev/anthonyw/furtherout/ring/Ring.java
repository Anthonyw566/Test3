package dev.anthonyw.furtherout.ring;

import dev.anthonyw.furtherout.core.RingDef;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

import java.util.List;

/** A ring from the config, plus how it's shown to players: "Safe area" or "Danger level N". */
public record Ring(RingDef def) {
    public static Ring of(RingDef def) {
        return new Ring(def);
    }

    public String id() {
        return def.id();
    }

    public int danger() {
        return def.danger();
    }

    public boolean safeZone() {
        return def.safeZone();
    }

    public boolean unbounded() {
        return def.unbounded();
    }

    public double outerRadius() {
        return def.outerRadius();
    }

    public double healthMult() {
        return def.healthMult();
    }

    public double damageMult() {
        return def.damageMult();
    }

    public double digChance() {
        return def.digChance();
    }

    public double eliteChance() {
        return def.eliteChance();
    }

    public double championChance() {
        return def.championChance();
    }

    public List<String> modifiers() {
        return def.modifiers();
    }

    public String eliteLoot() {
        return def.eliteLoot();
    }

    /** The colour of the danger number: calm to hot. */
    public ChatFormatting color() {
        if (safeZone()) {
            return ChatFormatting.GREEN;
        }
        return switch (Math.min(danger(), 4)) {
            case 0, 1 -> ChatFormatting.YELLOW;
            case 2 -> ChatFormatting.GOLD;
            case 3 -> ChatFormatting.RED;
            default -> ChatFormatting.DARK_RED;
        };
    }

    /** "Safe area" or "Danger level 2", quiet grey with a coloured number. */
    public Component label() {
        if (safeZone()) {
            return Component.literal("Safe area").withStyle(ChatFormatting.GREEN);
        }
        return Component.literal("Danger level ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(Integer.toString(danger())).withStyle(color()));
    }
}
