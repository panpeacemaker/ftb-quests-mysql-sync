package net.agrarius.ftbquestssync.teams;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Non-blocking in-memory map of player -> canonical DB team id.
 *
 * Populated off-thread from MySQL membership on login/materialize and read
 * synchronously from the reward-claim hook (server thread) so the TEAM-scope
 * dedup key stays identical across servers even when the local FTB team object
 * is momentarily stale/split. Falls back to the caller's local team id on a
 * cache miss, so it can never make claim resolution worse than before.
 */
public final class MembershipCache {

    private static final Map<UUID, UUID> playerToTeam = new ConcurrentHashMap<>();

    private MembershipCache() {
    }

    public static void put(UUID playerUuid, UUID teamId) {
        if (playerUuid == null || teamId == null) return;
        playerToTeam.put(playerUuid, teamId);
    }

    /**
     * Resolve the canonical team for a player, preferring the cached DB value
     * and falling back to the supplied local team id on a miss.
     */
    public static UUID resolveTeam(UUID playerUuid, UUID localTeamId) {
        UUID cached = playerToTeam.get(playerUuid);
        return cached != null ? cached : localTeamId;
    }

}
