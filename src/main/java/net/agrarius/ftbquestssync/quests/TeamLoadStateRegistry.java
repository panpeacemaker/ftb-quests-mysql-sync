package net.agrarius.ftbquestssync.quests;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class TeamLoadStateRegistry {

    public enum TeamLoadState { UNKNOWN, LOADING, LOADED, NEW, FAILED }

    private static final ConcurrentHashMap<UUID, TeamLoadState> teamLoadStates = new ConcurrentHashMap<>();

    public static TeamLoadState getTeamLoadState(UUID teamId) {
        return teamLoadStates.getOrDefault(teamId, TeamLoadState.UNKNOWN);
    }

    public static void setTeamLoadState(UUID teamId, TeamLoadState state) {
        teamLoadStates.put(teamId, state);
    }

    public static boolean isTeamSafeToWrite(UUID teamId) {
        return getTeamLoadState(teamId) == TeamLoadState.LOADED;
    }

    private TeamLoadStateRegistry() {}
}
