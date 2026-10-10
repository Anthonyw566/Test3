package dev.anthonyw.furtherout.util;

import dev.anthonyw.furtherout.FurtherOut;
import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.List;

/** Run something N ticks from now on the server thread (fuses, wind-ups). */
public final class Scheduler {
    private record Task(long runAt, Runnable action) {
    }

    private static final List<Task> TASKS = new ArrayList<>();

    private Scheduler() {
    }

    public static void schedule(MinecraftServer server, int delayTicks, Runnable action) {
        TASKS.add(new Task(server.getTickCount() + Math.max(1, delayTicks), action));
    }

    public static void tick(MinecraftServer server) {
        if (TASKS.isEmpty()) {
            return;
        }
        long now = server.getTickCount();
        List<Task> due = new ArrayList<>();
        TASKS.removeIf(task -> {
            if (task.runAt() <= now) {
                due.add(task);
                return true;
            }
            return false;
        });
        for (Task task : due) {
            try {
                task.action().run();
            } catch (RuntimeException e) {
                FurtherOut.LOGGER.error("Scheduled task failed", e);
            }
        }
    }

    public static void clear() {
        TASKS.clear();
    }
}
