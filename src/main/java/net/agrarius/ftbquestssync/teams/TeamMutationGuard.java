package net.agrarius.ftbquestssync.teams;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Process-wide suppression guard for team mutations.
 *
 * Prevents sync loops: when the mod itself mutates a team (e.g. on remote
 * event apply), the resulting FTB Teams events must not be echoed back to
 * MySQL/Redis.  Replaced from ThreadLocal maps (which broke across async
 * boundaries) to counted ConcurrentHashMaps so server.execute() callbacks
 * and mixed sync/async paths see the same suppression state.
 *
 * Scopes must be kept short and closed in {@code finally}.
 */
public final class TeamMutationGuard {

    private static final ConcurrentHashMap<UUID, AtomicInteger> teamSuppressions = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, AtomicInteger> playerSuppressions = new ConcurrentHashMap<>();

    private TeamMutationGuard() {
    }

    public static boolean isTeamSuppressed(UUID teamId) {
        if (teamId == null) return false;
        AtomicInteger counter = teamSuppressions.get(teamId);
        return counter != null && counter.get() > 0;
    }

    public static boolean isPlayerSuppressed(UUID playerId) {
        if (playerId == null) return false;
        AtomicInteger counter = playerSuppressions.get(playerId);
        return counter != null && counter.get() > 0;
    }

    public static Scope suppressTeam(UUID teamId) {
        AtomicInteger counter = teamSuppressions.computeIfAbsent(teamId, k -> new AtomicInteger(0));
        counter.incrementAndGet();
        return new Scope(counter, teamId, true);
    }

    public static Scope suppressPlayer(UUID playerId) {
        AtomicInteger counter = playerSuppressions.computeIfAbsent(playerId, k -> new AtomicInteger(0));
        counter.incrementAndGet();
        return new Scope(counter, playerId, false);
    }

    public static final class Scope implements AutoCloseable {
        private final AtomicInteger counter;
        private final UUID id;
        private final boolean isTeam;
        private boolean closed;

        private Scope(AtomicInteger counter, UUID id, boolean isTeam) {
            this.counter = counter;
            this.id = id;
            this.isTeam = isTeam;
        }

        @Override
        public void close() {
            if (closed) return;
            int depth = counter.decrementAndGet();
            if (depth <= 0) {
                ConcurrentHashMap<UUID, AtomicInteger> map = isTeam ? teamSuppressions : playerSuppressions;
                map.remove(id, counter);
            }
            closed = true;
        }
    }
}
