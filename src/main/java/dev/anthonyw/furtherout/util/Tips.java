package dev.anthonyw.furtherout.util;

import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/**
 * One-line explanations, each shown to a player once ever (it survives
 * deaths and restarts). Everything else in the mod is told through sound,
 * particles and the action bar; these are the only chat messages.
 */
public final class Tips {
    public static final Tips INSTANCE = new Tips();
    private static final String TAG = "fo_tips";

    private Tips() {
    }

    /** Shows {@code text} unless this player has already seen the tip called {@code key}. */
    public static void once(ServerPlayer player, String key, String text) {
        CompoundTag tips = player.getPersistentData().getCompound(TAG);
        if (tips.getBoolean(key)) {
            return;
        }
        tips.putBoolean(key, true);
        player.getPersistentData().put(TAG, tips);
        player.sendSystemMessage(Component.literal(text).withStyle(ChatFormatting.GRAY));
    }

    public static boolean seen(ServerPlayer player, String key) {
        return player.getPersistentData().getCompound(TAG).getBoolean(key);
    }

    /** Persistent data doesn't follow a player through death on its own. */
    @SubscribeEvent
    public void onClone(PlayerEvent.Clone event) {
        CompoundTag tips = event.getOriginal().getPersistentData().getCompound(TAG);
        if (!tips.isEmpty()) {
            event.getEntity().getPersistentData().put(TAG, tips.copy());
        }
    }
}
