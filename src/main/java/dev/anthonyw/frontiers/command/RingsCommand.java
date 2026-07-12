package dev.anthonyw.frontiers.command;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import dev.anthonyw.frontiers.DistantFrontiers;
import dev.anthonyw.frontiers.contract.ContractBoard;
import dev.anthonyw.frontiers.economy.Shop;
import dev.anthonyw.frontiers.elite.EliteModifier;
import dev.anthonyw.frontiers.elite.Elites;
import dev.anthonyw.frontiers.event.SurgeManager;
import dev.anthonyw.frontiers.heat.HeatManager;
import dev.anthonyw.frontiers.ring.BoundaryWatcher;
import dev.anthonyw.frontiers.ring.Ring;
import dev.anthonyw.frontiers.ring.RingManager;
import dev.anthonyw.frontiers.scaling.SpawnScaling;
import dev.anthonyw.frontiers.state.FrontiersState;
import dev.anthonyw.frontiers.util.SpawnUtil;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.fml.loading.FMLPaths;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * /rings - everything players and admins touch directly.
 *
 * Players:  info · list · zoneat · board · accept · shop · buy · heat
 * Admins:   reload · setorigin · inspect · simulate · surge · marks ·
 *           heatset · charter · bounties reroll · debug boundaries
 */
public final class RingsCommand {
    private RingsCommand() {
    }

    private static final SuggestionProvider<CommandSourceStack> RING_IDS = (ctx, builder) -> {
        RingManager mgr = RingManager.get();
        if (mgr != null) {
            return SharedSuggestionProvider.suggest(
                    mgr.rings().stream().map(Ring::id).toList(), builder);
        }
        return builder.buildFuture();
    };

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("rings")
                // ---- player commands
                .then(Commands.literal("info").executes(RingsCommand::info))
                .then(Commands.literal("list").executes(RingsCommand::list))
                .then(Commands.literal("zoneat")
                        .then(Commands.argument("x", IntegerArgumentType.integer())
                                .then(Commands.argument("z", IntegerArgumentType.integer())
                                        .executes(RingsCommand::zoneAt))))
                .then(Commands.literal("board").executes(ctx -> {
                    ContractBoard.showBoard(ctx.getSource().getPlayerOrException());
                    return 1;
                }))
                .then(Commands.literal("accept")
                        .then(Commands.argument("id", IntegerArgumentType.integer(1))
                                .executes(ctx -> {
                                    ContractBoard.accept(ctx.getSource().getPlayerOrException(),
                                            IntegerArgumentType.getInteger(ctx, "id"));
                                    return 1;
                                })))
                .then(Commands.literal("shop").executes(ctx -> {
                    Shop.showShop(ctx.getSource().getPlayerOrException());
                    return 1;
                }))
                .then(Commands.literal("buy")
                        .then(Commands.argument("offer", StringArgumentType.word())
                                .executes(ctx -> {
                                    Shop.buy(ctx.getSource().getPlayerOrException(),
                                            StringArgumentType.getString(ctx, "offer"));
                                    return 1;
                                })))
                .then(Commands.literal("heat").executes(RingsCommand::heat))
                // ---- admin commands
                .then(Commands.literal("reload")
                        .requires(source -> source.hasPermission(2))
                        .executes(RingsCommand::reload))
                .then(Commands.literal("setorigin")
                        .requires(source -> source.hasPermission(2))
                        .executes(RingsCommand::setOrigin))
                .then(Commands.literal("inspect")
                        .requires(source -> source.hasPermission(2))
                        .executes(RingsCommand::inspect))
                .then(Commands.literal("simulate")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.literal("elite").executes(ctx -> simulate(ctx, false)))
                        .then(Commands.literal("champion").executes(ctx -> simulate(ctx, true))))
                .then(Commands.literal("surge")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.literal("stop").executes(ctx -> {
                            SurgeManager.stop(ctx.getSource().getServer());
                            ctx.getSource().sendSuccess(() -> Component.literal("Surge cleared."), true);
                            return 1;
                        }))
                        .then(Commands.argument("ring", StringArgumentType.word())
                                .suggests(RING_IDS)
                                .executes(RingsCommand::startSurge)))
                .then(Commands.literal("marks")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.literal("give")
                                .then(Commands.argument("player", EntityArgument.player())
                                        .then(Commands.argument("amount", IntegerArgumentType.integer())
                                                .executes(RingsCommand::giveMarks)))))
                .then(Commands.literal("heatset")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.argument("value", FloatArgumentType.floatArg(0, 100))
                                .executes(RingsCommand::setHeat)))
                .then(Commands.literal("charter")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.argument("ring", StringArgumentType.word())
                                .suggests(RING_IDS)
                                .executes(RingsCommand::forceCharter)))
                .then(Commands.literal("bounties")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.literal("reroll").executes(ctx -> {
                            ContractBoard.adminReroll(ctx.getSource().getServer());
                            ctx.getSource().sendSuccess(() -> Component.literal("Bounties rerolled."), true);
                            return 1;
                        })))
                .then(Commands.literal("debug")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.literal("boundaries").executes(RingsCommand::debugBoundaries))));
    }

    // ------------------------------------------------------------------
    // Player commands

    private static int info(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ServerLevel level = player.serverLevel();
        RingManager mgr = manager(ctx.getSource());
        if (mgr == null) {
            return 0;
        }
        Ring ring = mgr.ringAt(level, player.getX(), player.getZ());
        if (ring == null) {
            ctx.getSource().sendSuccess(() -> Component.literal("Rings are not active in this dimension."), false);
            return 1;
        }
        int distance = (int) mgr.distanceFromOrigin(level, player.getX(), player.getZ());
        double next = mgr.blocksToNextRing(level, player.getX(), player.getZ());
        ctx.getSource().sendSuccess(() -> BoundaryWatcher.dangerReadout(ring), false);
        ctx.getSource().sendSuccess(() -> Component.literal(
                        "  " + distance + " blocks from the Hearth"
                                + (next >= 0 ? ", next ring in " + (int) next + " blocks"
                                : ", the frontier has no end"))
                .withStyle(ChatFormatting.GRAY), false);
        return 1;
    }

    private static int heat(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        FrontiersState state = FrontiersState.get(ctx.getSource().getServer());
        float heat = state.heat(player.getUUID());
        ctx.getSource().sendSuccess(() -> Component.literal(
                        "Heat: " + HeatManager.heatLabel(heat) + " (" + (int) heat + "/100)")
                .withStyle(HeatManager.heatColor(heat))
                .append(Component.literal("   ◈ " + state.banked(player.getUUID()) + " banked · "
                                + state.fieldMarks(player.getUUID()) + " field")
                        .withStyle(ChatFormatting.AQUA)), false);
        return 1;
    }

    private static int list(CommandContext<CommandSourceStack> ctx) {
        RingManager mgr = manager(ctx.getSource());
        if (mgr == null) {
            return 0;
        }
        FrontiersState state = FrontiersState.get(ctx.getSource().getServer());
        double inner = 0;
        for (Ring ring : mgr.rings()) {
            String range = ring.unbounded()
                    ? (int) inner + "+ blocks"
                    : (int) inner + " – " + (int) ring.outerRadius() + " blocks";
            if (!ring.unbounded()) {
                inner = ring.outerRadius();
            }
            String suffix = "  " + range
                    + (state.isChartered(ring.id()) ? "  ✔ chartered" : "")
                    + (SurgeManager.isSurging(ring.id()) ? "  ⚡SURGE" : "");
            ctx.getSource().sendSuccess(() -> BoundaryWatcher.dangerReadout(ring)
                    .copy().append(Component.literal(suffix).withStyle(ChatFormatting.GRAY)), false);
        }
        return 1;
    }

    private static int zoneAt(CommandContext<CommandSourceStack> ctx) {
        RingManager mgr = manager(ctx.getSource());
        if (mgr == null) {
            return 0;
        }
        int x = IntegerArgumentType.getInteger(ctx, "x");
        int z = IntegerArgumentType.getInteger(ctx, "z");
        ServerLevel level = ctx.getSource().getLevel();
        Ring ring = mgr.ringAt(level, x, z);
        if (ring == null) {
            ctx.getSource().sendSuccess(() -> Component.literal("Rings are not active in this dimension."), false);
            return 1;
        }
        ctx.getSource().sendSuccess(() -> Component.literal("(" + x + ", " + z + ") is in ")
                .withStyle(ChatFormatting.GRAY).append(BoundaryWatcher.dangerReadout(ring)), false);
        return 1;
    }

    // ------------------------------------------------------------------
    // Admin commands

    private static int reload(CommandContext<CommandSourceStack> ctx) {
        List<String> errors = RingManager.load();
        ContractBoard.loadConfig();
        Shop.load();
        if (errors.isEmpty()) {
            int count = RingManager.get() == null ? 0 : RingManager.get().rings().size();
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "Reloaded " + count + " rings, contracts and shop.").withStyle(ChatFormatting.GREEN), true);
            return 1;
        }
        ctx.getSource().sendFailure(Component.literal(
                "Config has " + errors.size() + " problem(s); keeping the previous ring config:"));
        for (String error : errors) {
            ctx.getSource().sendFailure(Component.literal("  - " + error).withStyle(ChatFormatting.RED));
        }
        return 0;
    }

    /** Rewrites origin in rings.json to the caller's position and reloads. */
    private static int setOrigin(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        Path file = FMLPaths.CONFIGDIR.get().resolve(DistantFrontiers.MODID).resolve("rings.json");
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            JsonObject origin = root.has("origin") ? root.getAsJsonObject("origin") : new JsonObject();
            origin.addProperty("useWorldSpawn", false);
            origin.addProperty("x", (int) player.getX());
            origin.addProperty("z", (int) player.getZ());
            root.add("origin", origin);
            Files.writeString(file, new GsonBuilder().setPrettyPrinting().create().toJson(root));
        } catch (Exception e) {
            ctx.getSource().sendFailure(Component.literal("Could not update rings.json: " + e.getMessage()));
            return 0;
        }
        RingManager.load();
        ctx.getSource().sendSuccess(() -> Component.literal(
                        "Ring origin set to (" + (int) player.getX() + ", " + (int) player.getZ() + ").")
                .withStyle(ChatFormatting.GREEN), true);
        return 1;
    }

    private static int inspect(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        Optional<Mob> nearest = player.serverLevel()
                .getEntitiesOfClass(Mob.class, player.getBoundingBox().inflate(8))
                .stream()
                .min(Comparator.comparingDouble(player::distanceToSqr));
        if (nearest.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("No mob within 8 blocks."));
            return 0;
        }
        Mob mob = nearest.get();
        CompoundTag data = mob.getPersistentData();
        String tier = switch (data.getInt(SpawnScaling.TAG_TIER)) {
            case 1 -> "ELITE";
            case 2 -> "CHAMPION";
            default -> data.getBoolean(SpawnScaling.TAG_SCALED) ? "normal (scaled)" : "unscaled";
        };
        String ring = data.getString(SpawnScaling.TAG_RING);
        String mods = data.getString(SpawnScaling.TAG_MODS);
        String flags = (data.getBoolean("df_no_marks") ? " no-marks" : "")
                + (data.getBoolean(ContractBoard.TAG_HUNTER) ? " hunter" : "")
                + (data.getInt(ContractBoard.TAG_BOUNTY_ID) > 0
                ? " bounty#" + data.getInt(ContractBoard.TAG_BOUNTY_ID) : "");
        ctx.getSource().sendSuccess(() -> Component.literal(
                        mob.getName().getString()
                                + "\n  tier: " + tier + (flags.isEmpty() ? "" : " (" + flags.trim() + ")")
                                + "\n  home ring: " + (ring.isEmpty() ? "-" : ring)
                                + "\n  modifiers: " + (mods.isEmpty() ? "-" : describeModifiers(mods))
                                + "\n  health: " + (int) mob.getHealth() + "/" + (int) mob.getMaxHealth()),
                false);
        return 1;
    }

    private static String describeModifiers(String csv) {
        StringBuilder out = new StringBuilder();
        for (String id : csv.split(",")) {
            EliteModifier modifier = EliteModifier.byId(id);
            if (out.length() > 0) {
                out.append(", ");
            }
            out.append(id);
            if (modifier != null) {
                out.append(" (").append(modifier.epithet()).append(")");
            }
        }
        return out.toString();
    }

    private static int simulate(CommandContext<CommandSourceStack> ctx, boolean champion)
            throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ServerLevel level = player.serverLevel();
        RingManager mgr = manager(ctx.getSource());
        if (mgr == null) {
            return 0;
        }
        Ring ring = mgr.ringAt(level, player.getX(), player.getZ());
        if (ring == null) {
            ctx.getSource().sendFailure(Component.literal("Rings are not active here."));
            return 0;
        }
        BlockPos pos = SpawnUtil.findGroundNear(level, player.blockPosition(), 3, 6);
        if (pos == null) {
            pos = player.blockPosition();
        }
        Mob mob = SpawnUtil.spawnScaled(level, ring, ContractBoard.randomAmbushMob(level), pos);
        if (mob == null) {
            ctx.getSource().sendFailure(Component.literal("Could not spawn a test mob here."));
            return 0;
        }
        Elites.promote(mob, ring, champion, null);
        ctx.getSource().sendSuccess(() -> Component.literal("Spawned test "
                + (champion ? "champion" : "elite") + ": " + mob.getName().getString()), false);
        return 1;
    }

    private static int startSurge(CommandContext<CommandSourceStack> ctx) {
        RingManager mgr = manager(ctx.getSource());
        if (mgr == null) {
            return 0;
        }
        Ring ring = mgr.byId(StringArgumentType.getString(ctx, "ring"));
        if (ring == null || ring.danger() <= 0) {
            ctx.getSource().sendFailure(Component.literal("Unknown or safe ring."));
            return 0;
        }
        long dayTime = ctx.getSource().getServer().overworld().getDayTime();
        SurgeManager.start(ctx.getSource().getServer(), ring, dayTime + 6000);
        return 1;
    }

    private static int giveMarks(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
        int amount = IntegerArgumentType.getInteger(ctx, "amount");
        FrontiersState.get(ctx.getSource().getServer()).addBanked(target.getUUID(), amount);
        ctx.getSource().sendSuccess(() -> Component.literal(
                "Gave ◈ " + amount + " (banked) to " + target.getName().getString() + "."), true);
        return 1;
    }

    private static int setHeat(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        float value = FloatArgumentType.getFloat(ctx, "value");
        FrontiersState.get(ctx.getSource().getServer()).setHeat(player.getUUID(), value);
        ctx.getSource().sendSuccess(() -> Component.literal("Heat set to " + (int) value + "."), true);
        return 1;
    }

    private static int forceCharter(CommandContext<CommandSourceStack> ctx) {
        RingManager mgr = manager(ctx.getSource());
        if (mgr == null) {
            return 0;
        }
        Ring ring = mgr.byId(StringArgumentType.getString(ctx, "ring"));
        if (ring == null) {
            ctx.getSource().sendFailure(Component.literal("Unknown ring."));
            return 0;
        }
        FrontiersState.get(ctx.getSource().getServer()).charter(ring.id());
        ctx.getSource().sendSuccess(() -> Component.literal(ring.name() + " force-chartered."), true);
        return 1;
    }

    /**
     * Draws a particle arc along the nearest ring boundary - only the section
     * near the player, never the whole circle.
     */
    private static int debugBoundaries(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ServerLevel level = player.serverLevel();
        RingManager mgr = manager(ctx.getSource());
        if (mgr == null) {
            return 0;
        }
        if (!mgr.appliesTo(level)) {
            ctx.getSource().sendFailure(Component.literal("Rings are not active in this dimension."));
            return 0;
        }

        double distance = mgr.distanceFromOrigin(level, player.getX(), player.getZ());
        double boundary = -1;
        for (Ring ring : mgr.rings()) {
            if (ring.unbounded()) {
                continue;
            }
            if (boundary < 0 || Math.abs(ring.outerRadius() - distance) < Math.abs(boundary - distance)) {
                boundary = ring.outerRadius();
            }
        }
        if (boundary <= 0) {
            ctx.getSource().sendFailure(Component.literal("No bounded ring boundaries configured."));
            return 0;
        }

        double[] origin = mgr.origin(level);
        double angleToPlayer = Math.atan2(player.getZ() - origin[1], player.getX() - origin[0]);
        double halfArc = Math.min(Math.PI, 48.0 / boundary);
        int points = 96;
        for (int i = 0; i <= points; i++) {
            double angle = angleToPlayer - halfArc + (2 * halfArc * i / points);
            double x = origin[0] + Math.cos(angle) * boundary;
            double z = origin[1] + Math.sin(angle) * boundary;
            double y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, (int) x, (int) z) + 1.5;
            level.sendParticles(ParticleTypes.END_ROD, x, y, z, 2, 0.0, 0.4, 0.0, 0.0);
        }
        final double shownBoundary = boundary;
        ctx.getSource().sendSuccess(() -> Component.literal(
                        "Boundary at radius " + (int) shownBoundary + " marked with particles ("
                                + (int) Math.abs(shownBoundary - distance) + " blocks from you).")
                .withStyle(ChatFormatting.AQUA), false);
        return 1;
    }

    private static RingManager manager(CommandSourceStack source) {
        RingManager mgr = RingManager.get();
        if (mgr == null) {
            source.sendFailure(Component.literal("Ring config is not loaded (see server log)."));
        }
        return mgr;
    }
}
