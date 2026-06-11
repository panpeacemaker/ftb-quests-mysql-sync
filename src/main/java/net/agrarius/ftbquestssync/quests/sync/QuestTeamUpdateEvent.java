package net.agrarius.ftbquestssync.quests.sync;

import com.google.gson.annotations.SerializedName;

import java.util.UUID;

/**
 * DTO for quest team update events published on {@code agrarius:quests:team-updated}.
 * Field names are kept identical to the legacy hand-rolled JSON wire format
 * so old nodes can parse messages from new nodes.
 */
public record QuestTeamUpdateEvent(
        @SerializedName("eventId") UUID eventId,
        @SerializedName("sourceServer") String sourceServer,
        @SerializedName("entityType") String entityType,
        @SerializedName("entityId") UUID teamId,
        @SerializedName("revision") long revision,
        @SerializedName("hash") String hashHex,
        @SerializedName("reason") String reason,
        @SerializedName("forceReplace") boolean forceReplace
) {
}
