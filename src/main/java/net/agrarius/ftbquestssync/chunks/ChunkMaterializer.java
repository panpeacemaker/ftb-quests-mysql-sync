package net.agrarius.ftbquestssync.chunks;

import net.agrarius.ftbquestssync.Config;
import net.agrarius.ftbquestssync.FTBQuestsSync;
import net.agrarius.ftbquestssync.MySQLBackend;
import net.agrarius.ftbquestssync.TeamLoadStateRegistry;
import net.agrarius.ftbquestssync.teams.TeamMaterializer;

import dev.ftb.mods.ftbchunks.api.ClaimResult;
import dev.ftb.mods.ftbchunks.api.ClaimedChunk;
import dev.ftb.mods.ftbchunks.api.ClaimedChunkManager;
import dev.ftb.mods.ftbchunks.api.ChunkTeamData;
import dev.ftb.mods.ftbchunks.api.FTBChunksAPI;
import dev.ftb.mods.ftblibrary.math.ChunkDimPos;
import dev.ftb.mods.ftbteams.api.FTBTeamsAPI;
import dev.ftb.mods.ftbteams.api.Team;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class ChunkMaterializer {
    private static final ConcurrentHashMap<String, Set<UUID>> pendingByDim = new ConcurrentHashMap<>();
    private static MinecraftServer server;
    private ChunkMaterializer() {}
    public static void initialize(MinecraftServer minecraftServer) { server = minecraftServer; }
    public static void materializeOnLogin(ServerPlayer player) {
        if (!Config.syncChunks) return;
        FTBTeamsAPI.api().getManager().getTeamForPlayer(player)
                .or(() -> FTBTeamsAPI.api().getManager().getPlayerTeamForPlayerID(player.getUUID()))
                .ifPresent(team -> materializeTeam(team.getId()));
    }
    public static void materializeTeam(UUID teamId) {
        if (!Config.syncChunks || server == null || !MySQLBackend.getInstance().isAvailable()) return;
        ChunkApplyGuard.setState(teamId, ChunkApplyGuard.State.LOADING);
        MySQLBackend.getInstance().isChunksSeededAsync().whenComplete((seeded, seedErr) -> {
            if (seedErr != null || !Boolean.TRUE.equals(seeded)) {
                ChunkApplyGuard.setState(teamId, ChunkApplyGuard.State.UNKNOWN);
                FTBQuestsSync.LOGGER.warn("Chunk materialize skipped until seed marker exists: team={}", teamId, seedErr);
                return;
            }
            MySQLBackend.getInstance().loadChunkClaimsAsync(teamId).whenComplete((rows, error) -> {
                if (error != null) { fail(teamId, "DB load failed", error); return; }
                server.execute(() -> apply(teamId, rows == null ? List.of() : rows));
            });
        });
    }
    public static void onLevelLoad(ServerLevel level) {
        if (!Config.syncChunks) return;
        server = level.getServer();
        String dim = level.dimension().location().toString();
        MySQLBackend.getInstance().loadChunkClaimsForDimensionAsync(dim).whenComplete((rows, error) -> {
            if (error != null) { FTBQuestsSync.LOGGER.error("Chunk dimension DB load failed dim={}", dim, error); return; }
            level.getServer().execute(() -> materializeTeamsForLoadedDim(dim, rows == null ? List.of() : rows));
        });
    }
    public static void materializeAllLoaded(MinecraftServer minecraftServer) {
        if (!Config.syncChunks) return;
        server = minecraftServer;
        for (ServerLevel level : minecraftServer.getAllLevels()) onLevelLoad(level);
    }
    public static void refreshTeamClaims(UUID teamId) {
        if (!Config.syncChunks) return;
        Team team = FTBTeamsAPI.api().getManager().getTeamByID(teamId).orElse(null);
        if (team == null) {
            FTBQuestsSync.LOGGER.debug("refreshTeamClaims: team not found {}", teamId);
            return;
        }
        if (!FTBChunksAPI.api().isManagerLoaded()) return;
        try {
            Object mgr = Class.forName("dev.ftb.mods.ftbchunks.data.ClaimedChunkManagerImpl").getMethod("getInstance").invoke(null);
            Object data = mgr.getClass().getMethod("getOrCreateData", dev.ftb.mods.ftbteams.api.Team.class).invoke(mgr, team);
            Object srv = mgr.getClass().getMethod("getMinecraftServer").invoke(mgr);
            data.getClass().getMethod("syncChunksToAll", net.minecraft.server.MinecraftServer.class).invoke(data, srv);
            FTBQuestsSync.LOGGER.debug("Refreshed chunk claims for team {} color change", teamId);
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.warn("refreshTeamClaims failed for team {}", teamId, e);
        }
    }
    private static void materializeTeamsForLoadedDim(String dim, List<ChunkClaimRecord> rows) {
        Set<UUID> teams = new HashSet<>();
        rows.forEach(row -> teams.add(row.teamId()));
        Set<UUID> pending = pendingByDim.remove(dim);
        if (pending != null) teams.addAll(pending);
        if (FTBChunksAPI.api().isManagerLoaded()) {
            for (ClaimedChunk chunk : FTBChunksAPI.api().getManager().getAllClaimedChunks()) {
                if (dim.equals(chunk.getPos().dimension().location().toString())) teams.add(chunk.getTeamData().getTeam().getId());
            }
        }
        teams.forEach(ChunkMaterializer::materializeTeam);
    }
    private static void apply(UUID teamId, List<ChunkClaimRecord> rows) {
        ChunkApplyGuard.setState(teamId, ChunkApplyGuard.State.APPLYING);
        try (ChunkApplyGuard.Scope ignored = ChunkApplyGuard.suppress(teamId)) {
            ApplySet set = loadedRows(teamId, rows);
            Team team = FTBTeamsAPI.api().getManager().getTeamByID(teamId).orElse(null);
            if (team == null && TeamMaterializer.ensureTeamMaterialized(teamId)) team = FTBTeamsAPI.api().getManager().getTeamByID(teamId).orElse(null);
            if (team == null) throw new IllegalStateException("team not present");
            ClaimedChunkManager mgr = FTBChunksAPI.api().getManager();
            ChunkTeamData data = mgr.getOrCreateData(team);
            int forceCount = Config.chunkForceLoadSync ? (int) set.rows.stream().filter(ChunkClaimRecord::forceLoaded).count() : 0;
            if (!ChunkLimitPatcher.ensureCapacity(data, set.rows.size(), forceCount)) throw new IllegalStateException("limit patch failed");
            CommandSourceStack src = server.createCommandSourceStack();
            Map<String, ChunkClaimRecord> desired = desiredMap(set.rows);
            Map<String, ClaimedChunk> local = localMap(data, set.loadedDims);
            unclaimStale(data, src, desired, local);
            claimMissing(mgr, data, src, teamId, desired, local);
            syncForceLoad(mgr, data, src, desired);
            ChunkApplyGuard.setState(teamId, ChunkApplyGuard.State.LOADED);
            FTBQuestsSync.LOGGER.info("Chunk materialize complete team={} rows={} pendingDims={}", teamId, desired.size(), set.missingDims.size());
        } catch (Exception e) { fail(teamId, "apply failed", e); }
    }
    private static ApplySet loadedRows(UUID teamId, List<ChunkClaimRecord> rows) {
        List<ChunkClaimRecord> loaded = new ArrayList<>();
        Set<String> loadedDims = loadedDims();
        Set<String> missingDims = new HashSet<>();
        for (ChunkClaimRecord row : rows) {
            ResourceKey<Level> key = dimensionKey(row.dimension());
            if (key == null || server.getLevel(key) == null) missingDims.add(row.dimension());
            else loaded.add(row);
        }
        refreshPending(teamId, missingDims);
        return new ApplySet(loaded, loadedDims, missingDims);
    }
    private static Set<String> loadedDims() {
        Set<String> dims = new HashSet<>();
        for (ServerLevel level : server.getAllLevels()) dims.add(level.dimension().location().toString());
        return dims;
    }
    private static void refreshPending(UUID teamId, Set<String> missingDims) {
        pendingByDim.forEach((dim, teams) -> teams.remove(teamId));
        pendingByDim.entrySet().removeIf(entry -> entry.getValue().isEmpty());
        for (String dim : missingDims) pendingByDim.computeIfAbsent(dim, d -> ConcurrentHashMap.newKeySet()).add(teamId);
    }
    private static Map<String, ChunkClaimRecord> desiredMap(List<ChunkClaimRecord> rows) {
        Map<String, ChunkClaimRecord> map = new HashMap<>();
        for (ChunkClaimRecord row : rows) map.put(row.key(), row);
        return map;
    }
    private static Map<String, ClaimedChunk> localMap(ChunkTeamData data, Set<String> loadedDims) {
        Map<String, ClaimedChunk> map = new HashMap<>();
        for (ClaimedChunk chunk : data.getClaimedChunks()) {
            String dim = chunk.getPos().dimension().location().toString();
            if (loadedDims.contains(dim)) map.put(ChunkClaimRecord.key(dim, chunk.getPos().x(), chunk.getPos().z()), chunk);
        }
        return map;
    }
    private static void unclaimStale(ChunkTeamData data, CommandSourceStack src, Map<String, ChunkClaimRecord> desired, Map<String, ClaimedChunk> local) {
        for (Map.Entry<String, ClaimedChunk> e : local.entrySet()) {
            if (desired.containsKey(e.getKey())) continue;
            ClaimedChunk chunk = e.getValue();
            ChunkClaimRecord row = ChunkClaimRecord.from(data.getTeam().getId(), chunk);
            if (chunk.isForceLoaded()) checkForceLoad(data.unForceLoad(src, chunk.getPos(), false), "unForceLoad stale", row);
            check(data.unclaim(src, chunk.getPos(), false), "unclaim stale", row);
        }
    }
    private static void claimMissing(ClaimedChunkManager mgr, ChunkTeamData data, CommandSourceStack src, UUID teamId, Map<String, ChunkClaimRecord> desired, Map<String, ClaimedChunk> local) {
        for (ChunkClaimRecord row : desired.values()) {
            if (local.containsKey(row.key())) continue;
            ClaimResult result = data.claim(src, pos(row), false);
            if (!result.isSuccess() && clearDbOwnedLocalConflict(mgr, src, teamId, row)) result = data.claim(src, pos(row), false);
            check(result, "claim", row);
            ChunkLimitPatcher.patchMetadata(mgr.getChunk(pos(row)), row);
        }
    }
    private static boolean clearDbOwnedLocalConflict(ClaimedChunkManager mgr, CommandSourceStack src, UUID teamId, ChunkClaimRecord row) {
        ClaimedChunk stale = mgr.getChunk(pos(row));
        if (stale == null) return false;
        UUID staleTeam = stale.getTeamData().getTeam().getId();
        UUID dbOwner = MySQLBackend.getInstance().loadChunkOwner(row.dimension(), row.x(), row.z()).orElse(null);
        if (!teamId.equals(dbOwner) || teamId.equals(staleTeam)) return false;
        try (ChunkApplyGuard.Scope ignored = ChunkApplyGuard.suppress(staleTeam)) {
            if (stale.isForceLoaded()) stale.getTeamData().unForceLoad(src, stale.getPos(), false);
            stale.getTeamData().unclaim(src, stale.getPos(), false);
            FTBQuestsSync.LOGGER.warn("Removed stale local chunk owner={} at {} for DB owner={}", staleTeam, stale.getPos(), teamId);
            return true;
        }
    }
    private static void syncForceLoad(ClaimedChunkManager mgr, ChunkTeamData data, CommandSourceStack src, Map<String, ChunkClaimRecord> desired) {
        if (!Config.chunkForceLoadSync) return;
        for (ChunkClaimRecord row : desired.values()) {
            ChunkDimPos pos = pos(row);
            ClaimedChunk chunk = mgr.getChunk(pos);
            if (chunk == null) continue;
            ClaimResult result = row.forceLoaded() == chunk.isForceLoaded() ? ClaimResult.success()
                    : row.forceLoaded() ? data.forceLoad(src, pos, false) : data.unForceLoad(src, pos, false);
            checkForceLoad(result, row.forceLoaded() ? "forceLoad" : "unForceLoad", row);
            ChunkLimitPatcher.patchMetadata(chunk, row);
        }
    }
    private static void checkForceLoad(ClaimResult result, String op, ChunkClaimRecord row) {
        if (result == null) {
            FTBQuestsSync.LOGGER.debug("{} no-op for {} team={}", op, row.key(), row.teamId());
            return;
        }
        if (!result.isSuccess()) {
            FTBQuestsSync.LOGGER.warn("{} failed for {} team={} result={}", op, row.key(), row.teamId(), result.getResultId());
        }
    }
    private static void check(ClaimResult result, String op, ChunkClaimRecord row) {
        if (result == null || !result.isSuccess()) throw new IllegalStateException(op + " failed for " + row.key()
                + " team=" + row.teamId() + " result=" + (result == null ? null : result.getResultId()));
    }
    private static ChunkDimPos pos(ChunkClaimRecord row) { return new ChunkDimPos(dimensionKey(row.dimension()), row.x(), row.z()); }
    private static ResourceKey<Level> dimensionKey(String dimension) {
        ResourceLocation id = ResourceLocation.tryParse(dimension);
        return id == null ? null : ResourceKey.create(Registries.DIMENSION, id);
    }
    private static void fail(UUID teamId, String message, Throwable error) {
        ChunkApplyGuard.setState(teamId, ChunkApplyGuard.State.FAILED);
        FTBQuestsSync.LOGGER.error("Chunk materialize failed team={} {}", teamId, message, error);
    }
    private record ApplySet(List<ChunkClaimRecord> rows, Set<String> loadedDims, Set<String> missingDims) {}
}
