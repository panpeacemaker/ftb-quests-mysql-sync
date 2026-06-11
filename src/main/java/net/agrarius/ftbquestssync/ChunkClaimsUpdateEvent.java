package net.agrarius.ftbquestssync;

import com.google.gson.annotations.SerializedName;

import java.util.UUID;

/**
 * DTO for chunk claim update events published on {@code agrarius:chunks:claims-updated}.
 */
record ChunkClaimsUpdateEvent(
        @SerializedName("serverId") String serverId,
        @SerializedName("reason") String reason,
        @SerializedName("teamId") UUID teamId
) {
}
