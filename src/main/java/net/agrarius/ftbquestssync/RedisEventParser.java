package net.agrarius.ftbquestssync;

import net.agrarius.ftbquestssync.quests.sync.QuestTeamUpdateEvent;

import com.google.gson.Gson;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Parser for Redis pub/sub payloads.
 * Contains no Minecraft/Forge dependencies so it can be unit-tested headlessly.
 */
public class RedisEventParser {

    public static final Gson GSON = new Gson();

    public static QuestTeamUpdateEvent parseQuestEvent(String payload) {
        try {
            return GSON.fromJson(payload, QuestTeamUpdateEvent.class);
        } catch (Exception e) {
            return null;
        }
    }

    public static ChunkClaimsUpdateEvent parseChunkEvent(String payload) {
        try {
            return GSON.fromJson(payload, ChunkClaimsUpdateEvent.class);
        } catch (Exception e) {
            return null;
        }
    }

    public static boolean isLegacyChunkPayload(String payload) {
        if (payload == null) return false;
        String[] parts = payload.split("\\|", 4);
        return parts.length >= 4 && parts[1].startsWith("chunks_");
    }

    /**
     * Parses a legacy pipe-delimited chunk payload.
     * Format: {@code serverId|reason|teamId|-}
     */
    public static ChunkClaimsUpdateEvent parseLegacyChunkEvent(String payload) {
        String[] parts = payload.split("\\|", 4);
        if (parts.length < 4 || !parts[1].startsWith("chunks_")) {
            return null;
        }
        try {
            return new ChunkClaimsUpdateEvent(parts[0], parts[1], UUID.fromString(parts[2]));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Parses a legacy non-JSON quest payload.
     * Format: {@code sourceServer:teamId}
     * Returns a {@link QuestTeamUpdateEvent} with a deterministic {@code eventId}
     * derived from the raw payload so deduplication actually works.
     */
    public static QuestTeamUpdateEvent parseLegacyQuestEvent(String payload) {
        String trimmed = payload.trim();
        if (trimmed.startsWith("{")) {
            return null;
        }
        int colon = payload.indexOf(':');
        if (colon <= 0) {
            return null;
        }
        try {
            String sourceServer = payload.substring(0, colon);
            UUID teamId = UUID.fromString(payload.substring(colon + 1));
            UUID eventId = deterministicEventId(payload);
            return new QuestTeamUpdateEvent(
                    eventId,
                    sourceServer,
                    "quest_team",
                    teamId,
                    -1L,
                    "",
                    "",
                    false
            );
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Generates a deterministic UUID from a raw payload string.
     * Fixes issue #31: identical legacy messages now share the same eventId,
     * so the {@code seenEvents} dedup set can suppress duplicates.
     */
    public static UUID deterministicEventId(String payload) {
        return UUID.nameUUIDFromBytes(payload.getBytes(StandardCharsets.UTF_8));
    }
}
