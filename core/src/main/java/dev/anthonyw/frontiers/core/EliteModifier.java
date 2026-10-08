package dev.anthonyw.frontiers.core;

import java.util.Locale;

/**
 * The five elite abilities. Each one is built to create a moment between
 * players, not just a bigger health bar. The epithet becomes part of the
 * mob's name ("Karok the Warping"), so a glance tells you what you're facing;
 * the tip is shown to a player the first time each ability hits them.
 */
public enum EliteModifier {
    WARPER("the Warping",
            "Its hits can teleport you: flung skyward, swapped with a nearby friend, or thrown aside."),
    THIEF("the Thief",
            "It steals an item from your hotbar and runs. Kill it to get your item back."),
    MAGNETIC("the Magnetic",
            "Every few seconds it drags everyone nearby toward itself. Watch the sparks."),
    VOLATILE("the Volatile",
            "It explodes a moment after it dies. Back off when it drops."),
    WARDED("the Warded",
            "It shrugs off hits from whoever it's chasing. Someone ELSE has to hurt it.");

    private final String epithet;
    private final String tip;

    EliteModifier(String epithet, String tip) {
        this.epithet = epithet;
        this.tip = tip;
    }

    public String epithet() {
        return epithet;
    }

    public String tip() {
        return tip;
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static EliteModifier byId(String id) {
        if (id == null) {
            return null;
        }
        try {
            return valueOf(id.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
