package net.agrarius.ftbquestssync;

import dev.ftb.mods.ftbchunks.api.ClaimedChunk;
import dev.ftb.mods.ftbchunks.api.ChunkTeamData;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

public final class ChunkLimitPatcher {

    private ChunkLimitPatcher() {
    }

    public static boolean ensureCapacity(ChunkTeamData data, int claimCount, int forceCount) {
        try {
            if (data.getMaxClaimChunks() < claimCount) {
                data.setExtraClaimChunks(data.getExtraClaimChunks() + claimCount - data.getMaxClaimChunks());
            }
            if (data.getMaxForceLoadChunks() < forceCount) {
                data.setExtraForceLoadChunks(data.getExtraForceLoadChunks() + forceCount - data.getMaxForceLoadChunks());
            }
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.error("Chunk limit patch failed for team={}", data.getTeam().getId(), e);
            return false;
        }
        invokeUpdateLimits(data);
        if (data.getMaxClaimChunks() < claimCount) setCachedLimit(data, "maxClaimChunks", claimCount);
        if (forceCount > 0 && data.getMaxForceLoadChunks() < forceCount) setCachedLimit(data, "maxForceLoadChunks", forceCount);
        boolean ok = data.getMaxClaimChunks() >= claimCount
                && (forceCount == 0 || data.getMaxForceLoadChunks() >= forceCount);
        if (!ok) {
            FTBQuestsSync.LOGGER.error("Chunk limit patch insufficient for team={} claims {}/{} force {}/{}",
                    data.getTeam().getId(), claimCount, data.getMaxClaimChunks(), forceCount, data.getMaxForceLoadChunks());
        }
        return ok;
    }

    private static void invokeUpdateLimits(ChunkTeamData data) {
        try {
            Method method;
            try {
                method = data.getClass().getMethod("updateLimits");
            } catch (NoSuchMethodException e) {
                method = data.getClass().getDeclaredMethod("updateLimits");
                method.setAccessible(true);
            }
            method.invoke(data);
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.warn("Chunk limit updateLimits reflection failed for team={}", data.getTeam().getId(), e);
        }
    }

    private static void setCachedLimit(ChunkTeamData data, String fieldName, int value) {
        try {
            Field field = data.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.setInt(data, value);
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.warn("Chunk cached limit field patch failed team={} field={} value={}",
                    data.getTeam().getId(), fieldName, value, e);
        }
    }

    public static void patchMetadata(ClaimedChunk chunk, ChunkClaimRecord record) {
        patchClaimedTime(chunk, record.claimedAtMs());
        try {
            chunk.setForceLoadExpiryTime(record.expiryMs());
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.warn("Could not patch force-load expiry for {}", record.key(), e);
        }
    }

    private static void patchClaimedTime(ClaimedChunk chunk, long claimedAtMs) {
        try {
            Method setter = chunk.getClass().getMethod("setClaimedTime", long.class);
            setter.invoke(chunk, claimedAtMs);
            return;
        } catch (NoSuchMethodException e) {
            FTBQuestsSync.LOGGER.warn("ClaimedChunk.setClaimedTime missing; trying field patch");
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.warn("ClaimedChunk.setClaimedTime failed; trying field patch", e);
        }
        try {
            Field time = chunk.getClass().getDeclaredField("time");
            time.setAccessible(true);
            time.setLong(chunk, claimedAtMs);
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.warn("Could not patch claimed time for {}", chunk.getPos(), e);
        }
    }
}
