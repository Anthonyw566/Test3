package dev.anthonyw.furtherout.core;

import java.util.Locale;

/**
 * The five elite abilities. The adjective goes in front of the mob's name
 * ("Warping Husk"), so looking at it tells you what it does; the tip is shown
 * once per player, the first time that ability touches them.
 */
public enum EliteModifier {
    WARPER("Warping",
            "Warping mobs shimmer purple when charged, and a charged hit teleports you. Block it with a shield or back off."),
    THIEF("Thieving",
            "Thieving mobs grab something from your hotbar - even what's in your hand - and run. Kill it to get it back."),
    MAGNETIC("Magnetic",
            "Magnetic mobs crackle, then pull in everyone who can see them. Duck behind a block when you hear the click."),
    VOLATILE("Volatile",
            "Volatile mobs explode a couple of seconds after they die. Step back when you hear the hiss."),
    WARDED("Warded",
            "Warded mobs shrug off hits from the player they're chasing, and it stings. Get a friend to hit it.");

    private final String adjective;
    private final String tip;

    EliteModifier(String adjective, String tip) {
        this.adjective = adjective;
        this.tip = tip;
    }

    public String adjective() {
        return adjective;
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
