package dev.anthonyw.frontiers.elite;

import java.util.Locale;

/**
 * The v1 modifier library. Stage 2 of the implementation plan gives each of
 * these its full behavior (abilities, particles, sounds); the enum already
 * carries the design contract: an epithet that doubles as the player-facing
 * telegraph, and a category used to keep champion pairings fair - a champion
 * rolls two modifiers from different categories, so "fast AND teleporting"
 * can never happen.
 */
public enum EliteModifier {
    SWIFT("the Swift", Category.MOBILITY),        // +35% speed, -25% health
    STONEHIDE("the Stoneskinned", Category.DEFENSE), // +armor, +KB resist, -15% speed
    SUMMONER("the Caller", Category.SUPPORT),     // periodically calls 2 weak minions
    BLINKSTEP("the Unseen", Category.MOBILITY),   // short teleport when hit, 6s cooldown
    CORROSIVE("the Vile", Category.OFFENSE),      // lingering harming cloud on death
    VENGEFUL("the Wrathful", Category.OFFENSE);   // enrages briefly when an ally dies

    public enum Category { MOBILITY, DEFENSE, OFFENSE, SUPPORT }

    private final String epithet;
    private final Category category;

    EliteModifier(String epithet, Category category) {
        this.epithet = epithet;
        this.category = category;
    }

    public String epithet() {
        return epithet;
    }

    public Category category() {
        return category;
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static EliteModifier byId(String id) {
        try {
            return valueOf(id.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
