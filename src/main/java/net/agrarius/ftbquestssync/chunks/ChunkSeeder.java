package net.agrarius.ftbquestssync.chunks;

import net.agrarius.ftbquestssync.config.Config;
import net.agrarius.ftbquestssync.FTBQuestsSync;
import net.agrarius.ftbquestssync.MySQLBackend;
import net.agrarius.ftbquestssync.RedisSync;

import dev.ftb.mods.ftbchunks.api.ClaimedChunk;
import dev.ftb.mods.ftbchunks.api.FTBChunksAPI;
import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ChunkSeeder {

    private static final AtomicBoolean running = new AtomicBoolean(false);

    private ChunkSeeder() {
    }

    public static void runIfConfigured(MinecraftServer server) {
        if (!Config.chunkSeedOnStart || !Config.chunkCanonicalServerId.equals(RedisSync.getInstance().getServerId())) return;
        if (!running.compareAndSet(false, true)) return;
        MySQLBackend.getInstance().isChunksSeededAsync().thenAccept(seeded -> server.execute(() -> {
            try {
                if (seeded) {
                    FTBQuestsSync.LOGGER.info("FTB Chunks seed skipped: marker already set");
                    running.set(false);
                    return;
                }
                if (!FTBChunksAPI.api().isManagerLoaded()) {
                    FTBQuestsSync.LOGGER.warn("FTB Chunks seed delayed: manager not loaded");
                    running.set(false);
                    return;
                }
                List<ChunkClaimRecord> rows = new ArrayList<>();
                for (ClaimedChunk chunk : FTBChunksAPI.api().getManager().getAllClaimedChunks()) {
                    if (chunk.getTeamData() == null || chunk.getTeamData().getTeam() == null) continue;
                    rows.add(ChunkClaimRecord.from(chunk));
                }
                MySQLBackend.getInstance().seedChunkClaimsAsync(rows)
                        .thenCompose(inserted -> MySQLBackend.getInstance().isChunksSeededAsync())
                        .thenAccept(success -> {
                            running.set(false);
                            if (success) publishSnapshots(rows);
                        });
            } catch (Exception e) {
                running.set(false);
                FTBQuestsSync.LOGGER.error("FTB Chunks seed failed", e);
            }
        }));
    }

    private static void publishSnapshots(List<ChunkClaimRecord> rows) {
        Set<java.util.UUID> teams = new HashSet<>();
        for (ChunkClaimRecord row : rows) teams.add(row.teamId());
        for (java.util.UUID teamId : teams) RedisSync.getInstance().publishChunkUpdate("chunks_snapshot", teamId);
    }
}
