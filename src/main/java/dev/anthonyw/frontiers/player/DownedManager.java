package dev.anthonyw.frontiers.player;

import dev.anthonyw.frontiers.DistantFrontiers;
import dev.anthonyw.frontiers.config.Configs;
import dev.anthonyw.frontiers.core.DownedState;
import dev.anthonyw.frontiers.core.HexRules;
import dev.anthonyw.frontiers.core.MechanicsConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
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

        Entry(DownedState state, DamageSource cause) {
            this.state = state;
            this.cause = cause;
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
        if (downed.remove(id) != null) {
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
        downed.put(player.getUUID(), new Entry(new DownedState(cfg.bleedOutTicks()), cause));
        modifier(player, Attributes.MOVEMENT_SPEED, SLOW, -0.7);
        modifier(player, Attributes.JUMP_STRENGTH, NO_JUMP, -1.0);
        modifier(player, Attributes.ATTACK_DAMAGE, NO_HIT, -1.0);
        modifier(player, Attributes.BLOCK_BREAK_SPEED, NO_MINE, -1.0);

        player.connection.send(new ClientboundSetTitlesAnimationPacket(5, 50, 10));
        player.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal(
                "Hold on - a friend can crouch next to you to revive you").withStyle(ChatFormatting.GRAY)));
        player.connection.send(new ClientboundSetTitleTextPacket(
                Component.literal("YOU'RE DOWN").withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD)));
        player.serverLevel().playSound(null, player.blockPosition(), SoundEvents.PLAYER_HURT_SWEET_BERRY_BUSH,
                SoundSource.PLAYERS, 1.0f, 0.6f);
        MinecraftServer server = player.serverLevel().getServer();
        server.getPlayerList().broadcastSystemMessage(Component.literal("✚ " + player.getName().getString()
                        + " is down at " + player.blockPosition().toShortString()
                        + "! Crouch next to them to revive (" + HexRules.formatTicks(cfg.bleedOutTicks()) + ").")
                .withStyle(ChatFormatting.RED), false);
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
                downed.remove(id);
                continue;
            }
            List<ServerPlayer> revivers = revivers(player, cfg);
            boolean rescuer = rescuerInRange(player, cfg);
            DownedState.Result result = entry.state.tick(5, !revivers.isEmpty(), player.isShiftKeyDown(), rescuer, cfg);

            player.setHealth(1f);
            player.addEffect(new MobEffectInstance(MobEffects.GLOWING, 30, 0, false, false));
            if (player.tickCount % 20 < 5) {
                player.serverLevel().sendParticles(ParticleTypes.FALLING_LAVA, player.getX(), player.getY() + 0.3,
                        player.getZ(), 2, 0.25, 0.1, 0.25, 0);
            }
            showStatus(player, entry.state, revivers, rescuer, cfg);

            switch (result) {
                case REVIVED -> revive(player, revivers.get(0));
                case BLED_OUT -> finish(player, entry.cause, "bled out");
                case GAVE_UP -> finish(player, entry.cause, "gave up");
                case CONTINUE -> {
                }
            }
        }
    }

    private void showStatus(ServerPlayer player, DownedState state, List<ServerPlayer> revivers,
                            boolean rescuer, MechanicsConfig.Downed cfg) {
        String bar = DownedState.bar(state.reviveProgress(cfg), 10);
        if (!revivers.isEmpty()) {
            player.displayClientMessage(Component.literal("Being revived by " + revivers.get(0).getName().getString()
                    + "  " + bar).withStyle(ChatFormatting.GREEN), true);
            for (ServerPlayer reviver : revivers) {
                reviver.displayClientMessage(Component.literal("Reviving " + player.getName().getString()
                        + "  " + bar).withStyle(ChatFormatting.GREEN), true);
            }
        } else if (state.giveUpProgress(cfg) > 0) {
            player.displayClientMessage(Component.literal("Letting go... "
                    + DownedState.bar(state.giveUpProgress(cfg), 10)).withStyle(ChatFormatting.DARK_GRAY), true);
        } else {
            player.displayClientMessage(Component.literal("DOWN " + HexRules.formatTicks(state.bleedTicks())
                            + (rescuer ? "  ·  hold crouch to give up" : "  ·  nobody is close enough to save you"))
                    .withStyle(ChatFormatting.RED), true);
        }
    }

    private void revive(ServerPlayer player, ServerPlayer reviver) {
        MechanicsConfig.Downed cfg = Configs.mechanics().downed();
        downed.remove(player.getUUID());
        clearEffects(player);
        player.setHealth(Math.min(player.getMaxHealth(), cfg.reviveHealth()));
        player.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 60, 2));
        player.serverLevel().playSound(null, player.blockPosition(), SoundEvents.TOTEM_USE,
                SoundSource.PLAYERS, 0.6f, 1.3f);
        player.serverLevel().sendParticles(ParticleTypes.HEART, player.getX(), player.getY() + 1.2, player.getZ(),
                8, 0.4, 0.4, 0.4, 0.1);
        player.serverLevel().getServer().getPlayerList().broadcastSystemMessage(Component.literal(
                        "✚ " + reviver.getName().getString() + " pulled " + player.getName().getString() + " back up!")
                .withStyle(ChatFormatting.GREEN), false);
    }

    /** Ends the downed state with a real death (keeps the original cause for the death message). */
    private void finish(ServerPlayer player, DamageSource cause, String how) {
        UUID id = player.getUUID();
        downed.remove(id);
        clearEffects(player);
        finishing.add(id);
        try {
            DistantFrontiers.LOGGER.debug("{} {}", player.getName().getString(), how);
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
                finish(player, entry.cause, "logged out");
            }
        }
    }

    public void clearAll() {
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
                java.util.Map.entry(Attributes.MOVEMENT_SPEED, SLOW),
                java.util.Map.entry(Attributes.JUMP_STRENGTH, NO_JUMP),
                java.util.Map.entry(Attributes.ATTACK_DAMAGE, NO_HIT),
                java.util.Map.entry(Attributes.BLOCK_BREAK_SPEED, NO_MINE))) {
            AttributeInstance instance = player.getAttribute(pair.getKey());
            if (instance != null) {
                instance.removeModifier(pair.getValue());
            }
        }
        player.removeEffect(MobEffects.GLOWING);
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(DistantFrontiers.MODID, path);
    }
}
