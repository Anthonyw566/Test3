package dev.anthonyw.furtherout.ring;

import dev.anthonyw.furtherout.fx.Fx;
import dev.anthonyw.furtherout.fx.Sfx;
import dev.anthonyw.furtherout.util.Tips;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Lets a player know, quietly, when the danger level around them changes:
 * one line above the hotbar and a soft sound (lower going out, brighter
 * coming home). Nightfall can raise the level where you stand; the line then
 * says so. The sound is rate-limited so walking along a boundary stays
 * quiet. Called once per second per player from the server ticker.
 */
public final class BoundaryWatcher {
    private static final int SOUND_COOLDOWN_TICKS = 300;

    private static final Map<UUID, Integer> lastDanger = new HashMap<>();
    private static final Map<UUID, Long> lastSound = new HashMap<>();

    private BoundaryWatcher() {
    }

    public static void tick(ServerPlayer player) {
        Ring ring = RingManager.ringOf(player);
        UUID id = player.getUUID();
        if (ring == null) {
            lastDanger.remove(id);
            return;
        }
        int danger = ring.safeZone() ? -1 : ring.danger();
        Integer previous = lastDanger.put(id, danger);
        if (previous == null || previous == danger) {
            return;
        }
        RingManager mgr = RingManager.get();
        boolean night = mgr != null && mgr.raisedByNight(player.serverLevel(), player.getX(), player.getZ());
        player.displayClientMessage(night
                ? ring.label().copy().append(Component.literal(" · night").withStyle(ChatFormatting.DARK_GRAY))
                : ring.label(), true);
        long now = player.serverLevel().getGameTime();
        if (now - lastSound.getOrDefault(id, Long.MIN_VALUE / 2) >= SOUND_COOLDOWN_TICKS) {
            lastSound.put(id, now);
            Fx.soundTo(player, danger > previous ? Sfx.DANGER_UP : Sfx.DANGER_DOWN);
        }
        if (previous < 0) {
            Tips.once(player, "leaving_safe_area",
                    "Monsters get tougher the further you go from spawn. /rings shows where you stand.");
        }
        if (night && danger > previous) {
            Tips.once(player, "night", "At night, danger reaches closer to spawn. It eases off again at dawn.");
        }
    }

    public static void forget(UUID player) {
        lastDanger.remove(player);
        lastSound.remove(player);
    }

    public static void clear() {
        lastDanger.clear();
        lastSound.clear();
    }
}
