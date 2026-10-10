package dev.anthonyw.furtherout.player;

import dev.anthonyw.furtherout.FurtherOut;
import dev.anthonyw.furtherout.config.Configs;
import dev.anthonyw.furtherout.core.DownedState;
import dev.anthonyw.furtherout.core.MechanicsConfig;
import dev.anthonyw.furtherout.fx.Fx;
import dev.anthonyw.furtherout.fx.Sfx;
import dev.anthonyw.furtherout.util.Tips;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.BossEvent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Downed & Revive. When a player would die and a friend is within rescue
 * range, they go down instead: crawling, glowing, unable to fight, use items
 * or build, and bleeding out. A friend crouching beside them for a few
 * seconds pulls them back up. Mobs keep hitting downed players (each hit eats
 * bleed time and interrupts a revive), so rescues mean clearing the area.
 *
 * A red boss bar shows the bleed-out to the downed player and to everyone
 * close enough to help; it turns green while someone is reviving. The downed
 * player hears their own heartbeat.
 *
 * Alone? Nobody can hear you - you just die. Void, /kill and other
 * "bypasses invulnerability" deaths are never intercepted.
 */
public final class DownedManager {
    public static final DownedManager INSTANCE = new DownedManager();

    private static final ResourceLocation SLOW = id("downed_speed");
    private static final ResourceLocation NO_JUMP = id("downed_jump");
    private static final ResourceLocation NO_HIT = id("downed_damage");
    private static final ResourceLocation NO_MINE = id("downed_mining");

    private static final class Entry {
        final DownedState state;
        final DamageSource cause;
        final int total;
        final ServerBossEvent ownBar;
        final ServerBossEvent friendsBar;

        Entry(DownedState state, DamageSource cause, int total, String name) {
            this.state = state;
            this.cause = cause;
            this.total = total;
            this.ownBar = new ServerBossEvent(Component.literal("Bleeding out"),
                    BossEvent.BossBarColor.RED, BossEvent.BossBarOverlay.PROGRESS);
            this.friendsBar = new ServerBossEvent(Component.literal(name + " is down"),
                    BossEvent.BossBarColor.RED, BossEvent.BossBarOverlay.PROGRESS);
        }

        void hide() {
            ownBar.removeAllPlayers();
            friendsBar.removeAllPlayers();
        }
    }

    private final Map<UUID, Entry> downed = new HashMap<>();
    private final Set<UUID> finishing = new HashSet<>();

    private DownedManager() {
    }

    public static boolean isDowned(Player player) {
        return INSTANCE.downed.containsKey(player.getUUID()) || INSTANCE.finishing.contains(player.getUUID());
    }

    // ------------------------------------------------------------------ going down

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || player instanceof FakePlayer) {
            return;
        }
        UUID id = player.getUUID();
        if (finishing.contains(id)) {
            return; // our own bleed-out: let it happen
        }
        Entry previous = downed.remove(id);
        if (previous != null) {
            previous.hide();
            clearEffects(player); // died some other way while down (void, /kill)
            return;
        }
        MechanicsConfig.Downed cfg = Configs.mechanics().downed();
        if (!cfg.enabled() || event.getSource().is(DamageTypeTags.BYPASSES_INVULNERABILITY)) {
            return;
        }
        if (!rescuerInRange(player, cfg)) {
            return; // nobody close enough to save you
        }
        event.setCanceled(true);
        goDown(player, event.getSource());
    }

    /** Puts a player into the downed state. Public for /rings down and in-game tests. */
    public void goDown(ServerPlayer player, DamageSource cause) {
        MechanicsConfig.Downed cfg = Configs.mechanics().downed();
        player.setHealth(1f);
        player.stopRiding();
        player.clearFire();
        Entry entry = new Entry(new DownedState(cfg.bleedOutTicks()), cause, cfg.bleedOutTicks(),
                player.getName().getString());
        downed.put(player.getUUID(), entry);
        modifier(player, Attributes.MOVEMENT_SPEED, SLOW, -0.7);
        modifier(player, Attributes.JUMP_STRENGTH, NO_JUMP, -1.0);
        modifier(player, Attributes.ATTACK_DAMAGE, NO_HIT, -1.0);
        modifier(player, Attributes.BLOCK_BREAK_SPEED, NO_MINE, -1.0);

        Fx.sound(player.serverLevel(), player.position(), Sfx.DOWNED_FALL);
        updateBars(player, entry, List.of(), cfg);
        Tips.once(player, "downed", "You're down. A friend can crouch next to you for a few seconds to"
                + " help you up. Hold sneak to give up.");
    }

    // ------------------------------------------------------------------ while down

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onIncomingDamage(LivingIncomingDamageEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        Entry entry = downed.get(player.getUUID());
        if (entry == null || event.getSource().is(DamageTypeTags.BYPASSES_INVULNERABILITY)) {
            return;
        }
        entry.state.onDamage(event.getAmount(), Configs.mechanics().downed());
        event.setCanceled(true);
        player.serverLevel().sendParticles(ParticleTypes.DAMAGE_INDICATOR, player.getX(), player.getY() + 0.5,
                player.getZ(), 4, 0.2, 0.2, 0.2, 0.1);
    }

    /** Downed players can't fight, use items or build their way out. */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onAttack(AttackEntityEvent event) {
        if (isDowned(event.getEntity())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onUseItem(PlayerInteractEvent.RightClickItem event) {
        if (isDowned(event.getEntity())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onUseBlock(PlayerInteractEvent.RightClickBlock event) {
        if (isDowned(event.getEntity())) {
            event.setCanceled(true);
        }
    }

    /** Called every 5 ticks by the server ticker. */
    public void tick(MinecraftServer server) {
        if (downed.isEmpty()) {
            return;
        }
        MechanicsConfig.Downed cfg = Configs.mechanics().downed();
        for (UUID id : new ArrayList<>(downed.keySet())) {
            Entry entry = downed.get(id);
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            if (player == null || !player.isAlive()) {
                downed.remove(id).hide();
                continue;
            }
            List<ServerPlayer> revivers = revivers(player, cfg);
            boolean rescuer = rescuerInRange(player, cfg);
            DownedState.Result result = entry.state.tick(5, !revivers.isEmpty(), player.isShiftKeyDown(), rescuer, cfg);

            player.setHealth(1f);
            player.addEffect(new MobEffectInstance(MobEffects.GLOWING, 30, 0, false, false));
            if (server.getTickCount() % 20 < 5) {
                player.serverLevel().sendParticles(ParticleTypes.FALLING_LAVA, player.getX(), player.getY() + 0.3,
                        player.getZ(), 2, 0.25, 0.1, 0.25, 0);
                if (revivers.isEmpty()) {
                    Fx.soundTo(player, Sfx.DOWNED_HEARTBEAT);
                }
            }
            updateBars(player, entry, revivers, cfg);
            if (!rescuer && revivers.isEmpty()) {
                player.displayClientMessage(Component.literal("Nobody is close enough to help")
                        .withStyle(ChatFormatting.GRAY), true);
            }

            switch (result) {
                case REVIVED -> revive(player);
                case BLED_OUT, GAVE_UP -> finish(player, entry.cause);
                case CONTINUE -> {
                }
            }
        }
    }

    /**
     * Own bar: "Bleeding out" / "Being helped up" / "Giving up".
     * Friends' bar: "Name is down" / "Helping Name up", shown to everyone in rescue range.
     */
    private void updateBars(ServerPlayer player, Entry entry, List<ServerPlayer> revivers, MechanicsConfig.Downed cfg) {
        DownedState state = entry.state;
        float bleed = Math.max(0f, Math.min(1f, state.bleedTicks() / (float) entry.total));
        String name = player.getName().getString();
        if (!revivers.isEmpty()) {
            float revive = (float) state.reviveProgress(cfg);
            entry.ownBar.setName(Component.literal("Being helped up"));
            entry.ownBar.setColor(BossEvent.BossBarColor.GREEN);
            entry.ownBar.setProgress(revive);
            entry.friendsBar.setName(Component.literal("Helping " + name + " up"));
            entry.friendsBar.setColor(BossEvent.BossBarColor.GREEN);
            entry.friendsBar.setProgress(revive);
        } else if (state.giveUpProgress(cfg) > 0) {
            entry.ownBar.setName(Component.literal("Giving up"));
            entry.ownBar.setColor(BossEvent.BossBarColor.WHITE);
            entry.ownBar.setProgress((float) state.giveUpProgress(cfg));
            entry.friendsBar.setName(Component.literal(name + " is down"));
            entry.friendsBar.setColor(BossEvent.BossBarColor.RED);
            entry.friendsBar.setProgress(bleed);
        } else {
            entry.ownBar.setName(Component.literal("Bleeding out"));
            entry.ownBar.setColor(BossEvent.BossBarColor.RED);
            entry.ownBar.setProgress(bleed);
            entry.friendsBar.setName(Component.literal(name + " is down"));
            entry.friendsBar.setColor(BossEvent.BossBarColor.RED);
            entry.friendsBar.setProgress(bleed);
        }
        entry.ownBar.addPlayer(player);
        double range = cfg.rescueRange() * cfg.rescueRange();
        for (ServerPlayer other : player.getServer().getPlayerList().getPlayers()) {
            boolean near = other != player && other.level() == player.level() && other.distanceToSqr(player) <= range;
            if (near) {
                entry.friendsBar.addPlayer(other);
            } else {
                entry.friendsBar.removePlayer(other);
            }
        }
    }

    private void revive(ServerPlayer player) {
        MechanicsConfig.Downed cfg = Configs.mechanics().downed();
        Entry entry = downed.remove(player.getUUID());
        if (entry != null) {
            entry.hide();
        }
        clearEffects(player);
        player.setHealth(Math.min(player.getMaxHealth(), cfg.reviveHealth()));
        player.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 60, 2));
        Fx.sound(player.serverLevel(), player.position(), Sfx.DOWNED_REVIVE);
        player.serverLevel().sendParticles(ParticleTypes.HEART, player.getX(), player.getY() + 1.2, player.getZ(),
                6, 0.4, 0.4, 0.4, 0.1);
    }

    /** Ends the downed state with a real death (keeps the original cause for the death message). */
    private void finish(ServerPlayer player, DamageSource cause) {
        UUID id = player.getUUID();
        Entry entry = downed.remove(id);
        if (entry != null) {
            entry.hide();
        }
        clearEffects(player);
        finishing.add(id);
        try {
            if (!player.hurt(cause, Float.MAX_VALUE) && player.isAlive()) {
                player.kill();
            }
        } finally {
            finishing.remove(id);
        }
    }

    /** Leaving the game while down counts as giving up. */
    @SubscribeEvent
    public void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            Entry entry = downed.get(player.getUUID());
            if (entry != null) {
                finish(player, entry.cause);
            }
        }
    }

    public void clearAll() {
        downed.values().forEach(Entry::hide);
        downed.clear();
        finishing.clear();
    }

    // ------------------------------------------------------------------ helpers

    private static boolean canHelp(ServerPlayer helper, ServerPlayer downedPlayer) {
        return helper != downedPlayer && helper.isAlive() && !helper.isSpectator() && !isDowned(helper);
    }

    private static boolean rescuerInRange(ServerPlayer player, MechanicsConfig.Downed cfg) {
        double range = cfg.rescueRange() * cfg.rescueRange();
        for (ServerPlayer other : player.serverLevel().players()) {
            if (canHelp(other, player) && other.distanceToSqr(player) <= range) {
                return true;
            }
        }
        return false;
    }

    private static List<ServerPlayer> revivers(ServerPlayer player, MechanicsConfig.Downed cfg) {
        List<ServerPlayer> out = new ArrayList<>();
        double range = cfg.reviveRadius() * cfg.reviveRadius();
        for (ServerPlayer other : player.serverLevel().players()) {
            if (canHelp(other, player) && other.isShiftKeyDown() && other.distanceToSqr(player) <= range) {
                out.add(other);
            }
        }
        return out;
    }

    private static void modifier(ServerPlayer player, Holder<Attribute> attribute, ResourceLocation id, double amount) {
        AttributeInstance instance = player.getAttribute(attribute);
        if (instance != null && !instance.hasModifier(id)) {
            instance.addTransientModifier(new AttributeModifier(id, amount,
                    AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        }
    }

    private static void clearEffects(ServerPlayer player) {
        for (var pair : List.of(
                Map.entry(Attributes.MOVEMENT_SPEED, SLOW),
                Map.entry(Attributes.JUMP_STRENGTH, NO_JUMP),
                Map.entry(Attributes.ATTACK_DAMAGE, NO_HIT),
                Map.entry(Attributes.BLOCK_BREAK_SPEED, NO_MINE))) {
            AttributeInstance instance = player.getAttribute(pair.getKey());
            if (instance != null) {
                instance.removeModifier(pair.getValue());
            }
        }
        player.removeEffect(MobEffects.GLOWING);
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(FurtherOut.MODID, path);
    }
}
