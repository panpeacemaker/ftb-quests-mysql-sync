package net.agrarius.ftbquestssync.chunks;

import net.agrarius.ftbquestssync.Config;

import dev.ftb.mods.ftbchunks.api.ClaimedChunk;
import dev.ftb.mods.ftblibrary.math.ChunkDimPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.UUID;

public record ChunkClaimRecord(
        UUID teamId,
        String dimension,
        int x,
        int z,
        boolean forceLoaded,
        long expiryMs,
        long claimedAtMs) {

    public static ChunkClaimRecord from(ClaimedChunk chunk) {
        return from(chunk.getTeamData().getTeam().getId(), chunk);
    }

    public static ChunkClaimRecord from(UUID teamId, ClaimedChunk chunk) {
        ChunkDimPos pos = chunk.getPos();
        boolean forceLoaded = Config.chunkForceLoadSync && chunk.isForceLoaded();
        return new ChunkClaimRecord(
                teamId,
                dimensionId(pos.dimension()),
                pos.x(),
                pos.z(),
                forceLoaded,
                forceLoaded ? chunk.getForceLoadExpiryTime() : 0L,
                chunk.getTimeClaimed());
    }

    public String key() {
        return key(dimension, x, z);
    }

    public static String key(String dimension, int x, int z) {
        return dimension + '|' + x + '|' + z;
    }

    public static String dimensionId(ResourceKey<Level> dimension) {
        return dimension.location().toString();
    }
}
