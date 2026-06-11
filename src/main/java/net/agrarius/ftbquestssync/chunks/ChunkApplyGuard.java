package net.agrarius.ftbquestssync.chunks;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ChunkApplyGuard {

    public enum State { UNKNOWN, LOADING, APPLYING, LOADED, FAILED }

    private static final ConcurrentHashMap<UUID, State> states = new ConcurrentHashMap<>();
    private static final ThreadLocal<Map<UUID, Integer>> suppressions = ThreadLocal.withInitial(HashMap::new);

    private ChunkApplyGuard() {
    }

    public static State getState(UUID teamId) {
        return states.getOrDefault(teamId, State.UNKNOWN);
    }

    public static void setState(UUID teamId, State state) {
        states.put(teamId, state);
    }

    public static boolean blocksPlayerAction(UUID teamId) {
        return getState(teamId) != State.LOADED;
    }

    public static boolean shouldBlockPlayerAction(UUID teamId) {
        return blocksPlayerAction(teamId);
    }

    public static boolean isLoaded(UUID teamId) {
        return getState(teamId) == State.LOADED;
    }

    public static boolean isSuppressed(UUID teamId) {
        return suppressions.get().getOrDefault(teamId, 0) > 0;
    }

    public static Scope suppress(UUID teamId) {
        Map<UUID, Integer> map = suppressions.get();
        map.put(teamId, map.getOrDefault(teamId, 0) + 1);
        return new Scope(teamId);
    }

    public static final class Scope implements AutoCloseable {
        private final UUID teamId;
        private boolean closed;

        private Scope(UUID teamId) {
            this.teamId = teamId;
        }

        @Override
        public void close() {
            if (closed) return;
            Map<UUID, Integer> map = suppressions.get();
            int depth = map.getOrDefault(teamId, 0) - 1;
            if (depth <= 0) map.remove(teamId);
            else map.put(teamId, depth);
            closed = true;
        }
    }
}
