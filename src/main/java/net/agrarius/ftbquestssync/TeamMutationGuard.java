package net.agrarius.ftbquestssync;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class TeamMutationGuard {

    private static final ThreadLocal<Map<UUID, Integer>> teamSuppressions = ThreadLocal.withInitial(HashMap::new);
    private static final ThreadLocal<Map<UUID, Integer>> playerSuppressions = ThreadLocal.withInitial(HashMap::new);

    private TeamMutationGuard() {
    }

    public static boolean isTeamSuppressed(UUID teamId) {
        return teamSuppressions.get().getOrDefault(teamId, 0) > 0;
    }

    public static boolean isPlayerSuppressed(UUID playerId) {
        return playerSuppressions.get().getOrDefault(playerId, 0) > 0;
    }

    public static Scope suppressTeam(UUID teamId) {
        Map<UUID, Integer> map = teamSuppressions.get();
        map.put(teamId, map.getOrDefault(teamId, 0) + 1);
        return new Scope(map, teamId);
    }

    public static Scope suppressPlayer(UUID playerId) {
        Map<UUID, Integer> map = playerSuppressions.get();
        map.put(playerId, map.getOrDefault(playerId, 0) + 1);
        return new Scope(map, playerId);
    }

    public static final class Scope implements AutoCloseable {
        private final Map<UUID, Integer> suppressions;
        private final UUID id;
        private boolean closed;

        private Scope(Map<UUID, Integer> suppressions, UUID id) {
            this.suppressions = suppressions;
            this.id = id;
        }

        @Override
        public void close() {
            if (closed) return;
            int depth = suppressions.getOrDefault(id, 0) - 1;
            if (depth <= 0) suppressions.remove(id);
            else suppressions.put(id, depth);
            closed = true;
        }
    }
}
