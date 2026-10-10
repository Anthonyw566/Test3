package dev.anthonyw.furtherout.fx;

import dev.anthonyw.furtherout.FurtherOut;
import dev.anthonyw.furtherout.config.Configs;
import dev.anthonyw.furtherout.core.MechanicsConfig;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ClientboundResourcePackPushPacket;
import net.minecraft.network.protocol.common.ServerboundResourcePackPacket;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Offers the small optional sound pack to every player on join and
 * remembers who actually loaded it, so each player hears either the pack's
 * sound or the vanilla one - never silence.
 *
 * The pack URL and SHA-1 default to the ones baked into this jar at build
 * time (the pack is published next to the jar on the GitHub release);
 * mechanics.json can point at a self-hosted copy instead.
 */
public final class ResourcePacks {
    public static final ResourcePacks INSTANCE = new ResourcePacks();
    public static final UUID PACK_ID = UUID.nameUUIDFromBytes("furtherout:pack".getBytes(StandardCharsets.UTF_8));

    private static final String HANDLER = "furtherout_pack_watch";
    private static final Map<UUID, ServerboundResourcePackPacket.Action> STATUS = new ConcurrentHashMap<>();

    private static volatile String builtInUrl;
    private static volatile String builtInSha1;
    private static boolean warnedMissing;
    private static boolean warnedWatch;

    private ResourcePacks() {
    }

    /** True once this player's client reports the pack as loaded. */
    public static boolean hasPack(ServerPlayer player) {
        return STATUS.get(player.getUUID()) == ServerboundResourcePackPacket.Action.SUCCESSFULLY_LOADED;
    }

    /** In-game tests use this to exercise the with-pack code paths. */
    public static void setLoadedForTesting(ServerPlayer player, boolean loaded) {
        if (loaded) {
            STATUS.put(player.getUUID(), ServerboundResourcePackPacket.Action.SUCCESSFULLY_LOADED);
        } else {
            STATUS.remove(player.getUUID());
        }
    }

    @SubscribeEvent
    public void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        MechanicsConfig.Pack cfg = Configs.mechanics().pack();
        if (!cfg.enabled()) {
            return;
        }
        String url = cfg.url().isEmpty() ? builtIn()[0] : cfg.url();
        String sha1 = cfg.sha1().isEmpty() ? builtIn()[1] : cfg.sha1();
        if (url.isEmpty() || sha1.isEmpty()) {
            if (!warnedMissing) {
                warnedMissing = true;
                FurtherOut.LOGGER.warn("No resource pack info in this build and none in mechanics.json - "
                        + "players hear vanilla sounds only.");
            }
            return;
        }
        watch(player);
        player.connection.send(new ClientboundResourcePackPushPacket(PACK_ID, url, sha1, cfg.required(),
                Optional.of(Component.literal("Further Out: a few extra sounds. ")
                        .withStyle(ChatFormatting.WHITE)
                        .append(Component.literal(cfg.required()
                                ? "Required on this server."
                                : "Optional, everything works without it.").withStyle(ChatFormatting.GRAY)))));
    }

    @SubscribeEvent
    public void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        STATUS.remove(event.getEntity().getUUID());
    }

    /**
     * Listens on the player's connection for their answer to our pack offer.
     * Vanilla exposes no event for this, so a tiny read-only handler sits in
     * front of the packet handler; if anything about the pipeline is
     * unexpected we just skip it and that player keeps vanilla effects.
     */
    private static void watch(ServerPlayer player) {
        UUID id = player.getUUID();
        try {
            Channel channel = player.connection.getConnection().channel();
            if (channel == null || channel.pipeline().get(HANDLER) != null) {
                return;
            }
            channel.pipeline().addBefore("packet_handler", HANDLER, new ChannelInboundHandlerAdapter() {
                @Override
                public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                    if (msg instanceof ServerboundResourcePackPacket answer && PACK_ID.equals(answer.id())) {
                        STATUS.put(id, answer.action());
                    }
                    super.channelRead(ctx, msg);
                }
            });
        } catch (RuntimeException e) {
            // Expected for the in-game tests' mock players; worth one line for anyone else.
            if (!warnedWatch) {
                warnedWatch = true;
                FurtherOut.LOGGER.warn("Couldn't watch {}'s resource pack status; they'll hear vanilla sounds: {}",
                        player.getName().getString(), e.toString());
            }
        }
    }

    /** {url, sha1} written into the jar by the build (see build.gradle). */
    private static String[] builtIn() {
        if (builtInUrl == null) {
            Properties props = new Properties();
            try (InputStream in = ResourcePacks.class.getResourceAsStream("/furtherout-pack.properties")) {
                if (in != null) {
                    props.load(in);
                }
            } catch (Exception e) {
                FurtherOut.LOGGER.warn("Couldn't read built-in resource pack info", e);
            }
            builtInSha1 = props.getProperty("sha1", "").trim();
            builtInUrl = props.getProperty("url", "").trim();
        }
        return new String[]{builtInUrl, builtInSha1};
    }

    public static void clear() {
        STATUS.clear();
    }
}
