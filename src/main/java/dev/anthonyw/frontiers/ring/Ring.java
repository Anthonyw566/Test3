package dev.anthonyw.frontiers.ring;

import dev.anthonyw.frontiers.core.RingDef;
import net.minecraft.ChatFormatting;

import java.util.List;

/**
 * MC-side view of a ring: the engine-free {@link RingDef} from the core module
 * plus its resolved chat color. All the parsing and validation lives (and is
 * unit-tested) in core.
 */
public record Ring(RingDef def, ChatFormatting color) {
    public static Ring of(RingDef def) {
        ChatFormatting color = ChatFormatting.getByName(def.colorName());
        return new Ring(def, color == null || !color.isColor() ? ChatFormatting.WHITE : color);
    }

    public String id() {
        return def.id();
    }

    public String name() {
        return def.name();
    }

    public int danger() {
        return def.danger();
    }

    public double outerRadius() {
        return def.outerRadius();
    }

    public boolean unbounded() {
        return def.unbounded();
    }

    public String entryMessage() {
        return def.entryMessage();
    }

    public boolean suppressHostileSpawns() {
        return def.suppressHostileSpawns();
    }

    public double healthMult() {
        return def.healthMult();
    }

    public double damageMult() {
        return def.damageMult();
    }

    public double speedMult() {
        return def.speedMult();
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

    public double heatGainPerMinute() {
        return def.heatGainPerMinute();
    }
}
