package dev.anthonyw.frontiers.fx;

import dev.anthonyw.frontiers.DistantFrontiers;
import dev.anthonyw.frontiers.core.EliteModifier;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;

/**
 * Code points of the resource pack's font glyphs. Must match the layout that
 * tools/make_art.py writes (it prints this table when run).
 */
public final class Glyphs {
    public static final ResourceLocation ICONS = ResourceLocation.fromNamespaceAndPath(DistantFrontiers.MODID, "icons");
    public static final ResourceLocation BIG = ResourceLocation.fromNamespaceAndPath(DistantFrontiers.MODID, "big");

    // 16px inline icons (font distantfrontiers:icons)
    public static final int WARPER = 0xE000;
    public static final int THIEF = 0xE001;
    public static final int MAGNETIC = 0xE002;
    public static final int VOLATILE = 0xE003;
    public static final int WARDED = 0xE004;
    public static final int HEX = 0xE005;
    public static final int DOWNED = 0xE006;
    public static final int REVIVE = 0xE007;
    public static final int ELITE = 0xE008;
    public static final int CHAMPION = 0xE009;
    public static final int DANGER = 0xE00A;

    // 32px title flipbooks (font distantfrontiers:big)
    public static final String[] RING_ORDER = {"hearth", "verge", "wildmarch", "duskreach", "ashenfront"};
    public static final int RING_REVEAL = 0xE200; // 5 frames per ring, in RING_ORDER
    public static final int RING_REVEAL_FRAMES = 5;
    public static final int HEX_ANIM = 0xE219;
    public static final int HEX_FRAMES = 7;
    public static final int DOWNED_ANIM = 0xE220;
    public static final int DOWNED_FRAMES = 6;
    public static final int REVIVE_ANIM = 0xE226;
    public static final int REVIVE_FRAMES = 6;

    private Glyphs() {
    }

    public static MutableComponent icon(int codePoint) {
        return Component.literal(Character.toString(codePoint)).withStyle(s -> s.withFont(ICONS));
    }

    public static MutableComponent big(int codePoint) {
        return Component.literal(Character.toString(codePoint)).withStyle(s -> s.withFont(BIG));
    }

    public static int ability(EliteModifier modifier) {
        return switch (modifier) {
            case WARPER -> WARPER;
            case THIEF -> THIEF;
            case MAGNETIC -> MAGNETIC;
            case VOLATILE -> VOLATILE;
            case WARDED -> WARDED;
        };
    }

    /** First frame of a ring's emblem reveal, or -1 for rings the pack has no art for. */
    public static int ringReveal(String ringId) {
        for (int i = 0; i < RING_ORDER.length; i++) {
            if (RING_ORDER[i].equals(ringId)) {
                return RING_REVEAL + i * RING_REVEAL_FRAMES;
            }
        }
        return -1;
    }
}
