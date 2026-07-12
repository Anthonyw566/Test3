package dev.anthonyw.frontiers.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.anthonyw.frontiers.elite.EliteModifier;
import dev.anthonyw.frontiers.ring.BoundaryWatcher;
import dev.anthonyw.frontiers.ring.Ring;
import dev.anthonyw.frontiers.ring.RingManager;
import dev.anthonyw.frontiers.scaling.SpawnScaling;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * /rings - player info and admin/debug tooling.
 *
 *   /rings info                    current ring, distance, next boundary
 *   /rings list                    all configured rings
 *   /rings zoneat <x> <z>          ring at a position
 *   /rings reload                  reload + validate config          (op)
 *   /rings inspect                 tier/modifiers of the nearest mob (op)
 *   /rings debug boundaries        particle arc on the nearest ring boundary (op)
 */
public final class RingsCommand {
    private RingsCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("rings")
                .then(Commands.literal("info").executes(RingsCommand::info))
                .then(Commands.literal("list").executes(RingsCommand::list))
                .then(Commands.literal("zoneat")
                        .then(Commands.argument("x", IntegerArgumentType.integer())
                                .then(Commands.argument("z", IntegerArgumentType.integer())
                                        .executes(RingsCommand::zoneAt))))
                .then(Commands.literal("reload")
                        .requires(source -> source.hasPermission(2))
                        .executes(RingsCommand::reload))
                .then(Commands.literal("inspect")
                        .requires(source -> source.hasPermission(2))
                        .executes(RingsCommand::inspect))
                .then(Commands.literal("debug")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.literal("boundaries").executes(RingsCommand::debugBoundaries))));
    }

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
                        + (next >= 0 ? ", next ring in " + (int) next + " blocks" : ", the frontier has no end"))
                .withStyle(ChatFormatting.GRAY), false);
        return 1;
    }

    private static int list(CommandContext<CommandSourceStack> ctx) {
        RingManager mgr = manager(ctx.getSource());
        if (mgr == null) {
            return 0;
        }
        double inner = 0;
        for (Ring ring : mgr.rings()) {
            String range = ring.unbounded()
                    ? (int) inner + "+ blocks"
                    : (int) inner + " - " + (int) ring.outerRadius() + " blocks";
            inner = ring.unbounded() ? inner : ring.outerRadius();
            String line = "  " + range;
            ctx.getSource().sendSuccess(() -> BoundaryWatcher.dangerReadout(ring)
                    .copy().append(Component.literal(line).withStyle(ChatFormatting.GRAY)), false);
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

    private static int reload(CommandContext<CommandSourceStack> ctx) {
        List<String> errors = RingManager.load();
        if (errors.isEmpty()) {
            int count = RingManager.get() == null ? 0 : RingManager.get().rings().size();
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "Reloaded " + count + " rings.").withStyle(ChatFormatting.GREEN), true);
            return 1;
        }
        ctx.getSource().sendFailure(Component.literal(
                "Config has " + errors.size() + " problem(s); keeping the previous config:"));
        for (String error : errors) {
            ctx.getSource().sendFailure(Component.literal("  - " + error).withStyle(ChatFormatting.RED));
        }
        return 0;
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
        ctx.getSource().sendSuccess(() -> Component.literal(
                mob.getName().getString()
                        + " [" + mob.getType().getDescriptionId() + "]"
                        + "\n  tier: " + tier
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
