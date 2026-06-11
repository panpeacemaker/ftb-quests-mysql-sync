package net.agrarius.ftbquestssync.migration;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Shared guard so {@code /ftbsync migrate} and {@code /ftbsync importteams}
 * cannot run concurrently. Only one migration-like operation at a time.
 */
public final class MigrationGuard {

    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);

    private MigrationGuard() {
    }

    public static boolean tryAcquire() {
        return RUNNING.compareAndSet(false, true);
    }

    public static void release() {
        RUNNING.set(false);
    }

    public static boolean isRunning() {
        return RUNNING.get();
    }
}
