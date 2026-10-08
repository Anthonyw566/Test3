package dev.anthonyw.frontiers.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.anthonyw.frontiers.config.Configs;
import dev.anthonyw.frontiers.core.EliteModifier;
import dev.anthonyw.frontiers.core.HexRules;
import dev.anthonyw.frontiers.core.ModifierPicker;
import dev.anthonyw.frontiers.mob.DigGoal;
import dev.anthonyw.frontiers.mob.Elites;
import dev.anthonyw.frontiers.player.DownedManager;
import dev.anthonyw.frontiers.player.HexManager;
import dev.anthonyw.frontiers.ring.Ring;
import dev.anthonyw.frontiers.ring.RingManager;
import dev.anthonyw.frontiers.util.McRand;
import dev.anthonyw.frontiers.util.SpawnUtil;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * /rings                       where am I, how dangerous is it, am I hexed
 * /rings help                  the rules in six lines
 * Ops:
 * /rings elite [entity] [abilities...|champion]   spawn a test elite next to you
 * /rings hex <player> [seconds] · /rings unhex <player>
 * /rings down <player>         put someone in the downed state (revive practice)
 * /rings inspect               what the nearest mob is carrying
 * /rings boundary              show the nearest ring edge with particles
 * /rings reload                reload rings.json + mechanics.json
 */
public final class RingsCommand {
    private RingsCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("rings")
                .executes(RingsCommand::info)
                .then(Commands.literal("info").executes(RingsCommand::info))
                .then(Commands.literal("help").executes(RingsCommand::help))
                .then(Commands.literal("elite").requires(s -> s.hasPermission(2))
                        .executes(ctx -> elite(ctx, ""))
                        .then(Commands.argument("spec", StringArgumentType.greedyString())
                                .executes(ctx -> elite(ctx, StringArgumentType.getString(ctx, "spec")))))
                .then(Commands.literal("hex").requires(s -> s.hasPermission(2))
                        .then(Commands.argument("player", EntityArgument.player())
                                .executes(ctx -> hex(ctx, -1))
                                .then(Commands.argument("seconds", IntegerArgumentType.integer(1, 3600))
                                        .executes(ctx -> hex(ctx, IntegerArgumentType.getInteger(ctx, "seconds"))))))
                .then(Commands.literal("unhex").requires(s -> s.hasPermission(2))
                        .then(Commands.argument("player", EntityArgument.player()).executes(RingsCommand::unhex)))
                .then(Commands.literal("down").requires(s -> s.hasPermission(2))
                        .then(Commands.argument("player", EntityArgument.player()).executes(RingsCommand::down)))
                .then(Commands.literal("inspect").requires(s -> s.hasPermission(2)).executes(RingsCommand::inspect))
                .then(Commands.literal("boundary").requires(s -> s.hasPermission(2)).executes(RingsCommand::boundary))
                .then(Commands.literal("reload").requires(s -> s.hasPermission(2)).executes(RingsCommand::reload)));
    }

    private static int info(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        RingManager mgr = RingManager.get();
        Ring ring = RingManager.ringOf(player);
        if (mgr == null || ring == null) {
            ctx.getSource().sendSuccess(() -> Component.literal("No rings in this dimension - vanilla rules apply.")
                    .withStyle(ChatFormatting.GRAY), false);
            return 1;
        }
        ServerLevel level = player.serverLevel();
        MutableComponent line = ring.title().copy();
        if (mgr.isRadial(level)) {
            int distance = (int) mgr.distanceFromOrigin(level, player.getX(), player.getZ());
            double next = mgr.blocksToNextRing(level, player.getX(), player.getZ());
            line.append(Component.literal("  " + distance + " blocks out"
                    + (next >= 0 ? ", next ring in " + (int) Math.ceil(next) : ", no end in sight"))
                    .withStyle(ChatFormatting.GRAY));
        }
        ctx.getSource().sendSuccess(() -> line, false);
        if (ring.safeZone()) {
            ctx.getSource().sendSuccess(() -> Component.literal("  Safe: no natural hostiles, no digging, Hex paused.")
                    .withStyle(ChatFormatting.GREEN), false);
        } else {
            ctx.getSource().sendSuccess(() -> Component.literal(String.format(
                    "  Elites %d%% · Champions %d%% of elites · Diggers %d%% · Mobs x%.2f health",
                    Math.round(ring.eliteChance() * 100), Math.round(ring.championChance() * 100),
                    Math.round(ring.digChance() * 100), ring.healthMult())).withStyle(ChatFormatting.GRAY), false);
        }
        int hex = HexManager.remaining(player);
        if (hex > 0) {
            ctx.getSource().sendSuccess(() -> Component.literal("  You are HEXED: " + HexRules.formatTicks(hex) + " left.")
                    .withStyle(ChatFormatting.DARK_PURPLE), false);
        }
        return 1;
    }

    private static int help(CommandContext<CommandSourceStack> ctx) {
        String[] lines = {
                "§6§lDistant Frontiers§r - the further from spawn, the worse it gets.",
                "§aThe Hearth§7 (around spawn) is safe: no hostiles spawn, nothing digs in.",
                "§7Outside it, mobs §fdig through walls§7 to reach you. Hiding isn't safe anymore.",
                "§eElites§7 have abilities: §dWarping§7 (teleports you/friends), §dThief§7 (steals & runs),"
                        + " §dMagnetic§7 (pulls you in), §dVolatile§7 (explodes on death), §dWarded§7 (only a friend can hurt it).",
                "§cDowned:§7 die near a friend and you go down instead. Friends §fcrouch next to you§7 to revive.",
                "§5Hex:§7 killing elites can curse you - everything hunts you, loot doubles."
                        + " Survive it for a reward, or §fpunch a friend§7 to pass it on.",
                "§7/rings shows where you are and how dangerous it is."
        };
        for (String line : lines) {
            ctx.getSource().sendSuccess(() -> Component.literal(line), false);
        }
        return 1;
    }

    /** /rings elite [minecraft:skeleton] [warper thief | champion] */
    private static int elite(CommandContext<CommandSourceStack> ctx, String spec) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ServerLevel level = player.serverLevel();
        String entity = "minecraft:zombie";
        StringBuilder abilities = new StringBuilder();
        boolean champion = false;
        for (String token : spec.trim().split("\\s+")) {
            if (token.contains(":")) {
                entity = token;
            } else if (token.equalsIgnoreCase("champion")) {
                champion = true;
            } else {
                abilities.append(token).append(' ');
            }
        }
        ModifierPicker.Parsed parsed = ModifierPicker.parse(abilities.toString());
        if (!parsed.unknown().isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("Unknown abilities: " + parsed.unknown()
                    + ". Try: " + Arrays.stream(EliteModifier.values()).map(EliteModifier::id).toList()));
            return 0;
        }
        List<EliteModifier> mods = new ArrayList<>(parsed.modifiers());
        if (mods.isEmpty()) {
            mods.addAll(ModifierPicker.pick(Arrays.asList(EliteModifier.values()), champion ? 2 : 1,
                    new McRand(level.random)));
        }
        BlockPos pos = SpawnUtil.findGroundNear(level, player.blockPosition(), 3, 6);
        Ring ring = RingManager.ringOf(player);
        Mob mob = SpawnUtil.spawnForRing(level, ring == null || ring.safeZone() ? null : ring, entity,
                pos == null ? player.blockPosition() : pos);
        if (mob == null) {
            ctx.getSource().sendFailure(Component.literal("Couldn't spawn " + entity + " here."));
            return 0;
        }
        Elites.promote(mob, mods);
        ctx.getSource().sendSuccess(() -> Component.literal("Spawned " + Elites.displayName(mob)), true);
        return 1;
    }

    private static int hex(CommandContext<CommandSourceStack> ctx, int seconds) throws CommandSyntaxException {
        ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
        int ticks = seconds > 0 ? seconds * 20 : Configs.mechanics().hex().durationTicks();
        HexManager.INSTANCE.give(target, ticks, " by an admin");
        ctx.getSource().sendSuccess(() -> Component.literal("Hexed " + target.getName().getString()
                + " for " + HexRules.formatTicks(ticks)), true);
        return 1;
    }

    private static int unhex(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
        HexManager.INSTANCE.clear(target);
        ctx.getSource().sendSuccess(() -> Component.literal("Cleared the Hex from " + target.getName().getString()), true);
        return 1;
    }

    private static int down(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
        if (DownedManager.isDowned(target)) {
            ctx.getSource().sendFailure(Component.literal(target.getName().getString() + " is already down."));
            return 0;
        }
        DownedManager.INSTANCE.goDown(target, target.damageSources().generic());
        return 1;
    }

    private static int inspect(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        Optional<Mob> nearest = player.serverLevel()
                .getEntitiesOfClass(Mob.class, player.getBoundingBox().inflate(10))
                .stream().min(Comparator.comparingDouble(player::distanceToSqr));
        if (nearest.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("No mob within 10 blocks."));
            return 0;
        }
        Mob mob = nearest.get();
        String tier = switch (Elites.tier(mob)) {
            case 1 -> "elite";
            case 2 -> "champion";
            default -> "normal";
        };
        String text = Elites.displayName(mob)
                + "\n  tier: " + tier + "   home ring: " + (Elites.homeRing(mob).isEmpty() ? "-" : Elites.homeRing(mob))
                + "\n  abilities: " + (Elites.modifiers(mob).isEmpty() ? "-" : Elites.modifiers(mob))
                + "\n  digger: " + (Elites.isDigger(mob) ? "yes (" + mob.getPersistentData().getInt(DigGoal.TAG_DUG)
                + "/" + Configs.mechanics().digging().maxBlocksPerMob() + " blocks used)" : "no")
                + "\n  health: " + Math.round(mob.getHealth()) + "/" + Math.round(mob.getMaxHealth());
        ctx.getSource().sendSuccess(() -> Component.literal(text), false);
        return 1;
    }

    /** Particle arc along the nearest ring edge - only the nearby stretch, never the whole circle. */
    private static int boundary(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ServerLevel level = player.serverLevel();
        RingManager mgr = RingManager.get();
        if (mgr == null || !mgr.isRadial(level)) {
            ctx.getSource().sendFailure(Component.literal("No ring edges in this dimension."));
            return 0;
        }
        double distance = mgr.distanceFromOrigin(level, player.getX(), player.getZ());
        double edge = -1;
        for (Ring ring : mgr.rings()) {
            if (!ring.unbounded() && (edge < 0 || Math.abs(ring.outerRadius() - distance) < Math.abs(edge - distance))) {
                edge = ring.outerRadius();
            }
        }
        if (edge <= 0) {
            ctx.getSource().sendFailure(Component.literal("No ring edges configured."));
            return 0;
        }
        double[] origin = mgr.origin(level);
        double facing = Math.atan2(player.getZ() - origin[1], player.getX() - origin[0]);
        double halfArc = Math.min(Math.PI, 48.0 / edge);
        for (int i = 0; i <= 96; i++) {
            double angle = facing - halfArc + 2 * halfArc * i / 96;
            double x = origin[0] + Math.cos(angle) * edge;
            double z = origin[1] + Math.sin(angle) * edge;
            double y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, (int) Math.floor(x), (int) Math.floor(z)) + 1.5;
            level.sendParticles(player, ParticleTypes.END_ROD, true, x, y, z, 3, 0, 0.6, 0, 0);
        }
        final double shown = edge;
        ctx.getSource().sendSuccess(() -> Component.literal("Ring edge at radius " + (int) shown + " ("
                + (int) Math.abs(shown - distance) + " blocks from you) marked with particles."), false);
        return 1;
    }

    private static int reload(CommandContext<CommandSourceStack> ctx) {
        List<String> problems = Configs.loadAll();
        if (problems.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal("Reloaded rings.json and mechanics.json.")
                    .withStyle(ChatFormatting.GREEN), true);
            return 1;
        }
        ctx.getSource().sendFailure(Component.literal("Reloaded with " + problems.size() + " problem(s):"));
        for (String problem : problems) {
            ctx.getSource().sendFailure(Component.literal("  - " + problem));
        }
        return 0;
    }
}
