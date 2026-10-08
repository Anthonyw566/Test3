package dev.anthonyw.frontiers.ring;

import dev.anthonyw.frontiers.core.RingDef;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

import java.util.List;

/** A ring from the config plus its resolved chat color. */
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

    public boolean safeZone() {
        return def.safeZone();
    }

    public boolean unbounded() {
        return def.unbounded();
    }

    public double outerRadius() {
        return def.outerRadius();
    }

    public String entryMessage() {
        return def.entryMessage();
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

    /** "The Duskreach ☠☠☠" in the ring's color. */
    public Component title() {
        String skulls = danger() > 0 ? "  " + "☠".repeat(danger()) : "";
        return Component.literal(name() + skulls).withStyle(color);
    }
}
