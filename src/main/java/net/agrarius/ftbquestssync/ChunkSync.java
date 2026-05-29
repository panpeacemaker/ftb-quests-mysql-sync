package net.agrarius.ftbquestssync;

import dev.architectury.event.CompoundEventResult;
import dev.ftb.mods.ftbchunks.api.ClaimResult;
import dev.ftb.mods.ftbchunks.api.ClaimedChunk;
import dev.ftb.mods.ftbchunks.api.ChunkTeamData;
import dev.ftb.mods.ftbchunks.api.FTBChunksAPI;
import dev.ftb.mods.ftbchunks.api.event.ClaimedChunkEvent;
import dev.ftb.mods.ftbteams.api.FTBTeamsAPI;
import dev.ftb.mods.ftbteams.api.Team;
import dev.ftb.mods.ftbteams.api.event.PlayerJoinedPartyTeamEvent;
import dev.ftb.mods.ftbteams.api.event.PlayerLeftPartyTeamEvent;
import dev.ftb.mods.ftbteams.api.event.TeamEvent;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.TickTask;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class ChunkSync {

    private static final ChunkSync INSTANCE = new ChunkSync();

    private final ConcurrentHashMap<UUID, String> pendingReasons = new ConcurrentHashMap<>();
    private final Set<UUID> scheduled = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService publishExec = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "FTBQuestsSync-Chunk-Pub");
        t.setDaemon(true);
        return t;
    });
    private volatile boolean registered;

    private ChunkSync() {
    }

    public static ChunkSync getInstance() {
        return INSTANCE;
    }

    public void registerEventListeners() {
        if (registered) return;
        registered = true;
        if (!Config.syncChunks) {
            FTBQuestsSync.LOGGER.info("ChunkSync listener registration skipped: syncChunks=false");
            return;
        }
        ClaimedChunkEvent.BEFORE_CLAIM.register(this::beforeChange);
        ClaimedChunkEvent.BEFORE_UNCLAIM.register(this::beforeChange);
        ClaimedChunkEvent.BEFORE_LOAD.register(this::beforeChange);
        ClaimedChunkEvent.BEFORE_UNLOAD.register(this::beforeChange);
        ClaimedChunkEvent.AFTER_CLAIM.register((src, chunk) -> upsert(chunk, "chunks_claim"));
        ClaimedChunkEvent.AFTER_UNCLAIM.register((src, chunk) -> delete(chunk, "chunks_unclaim"));
        ClaimedChunkEvent.AFTER_LOAD.register((src, chunk) -> upsert(chunk, "chunks_force_load"));
        ClaimedChunkEvent.AFTER_UNLOAD.register((src, chunk) -> upsert(chunk, "chunks_force_unload"));
        TeamEvent.PLAYER_JOINED_PARTY.register(this::onJoinedParty);
        TeamEvent.PLAYER_LEFT_PARTY.register(this::onLeftParty);
        FTBQuestsSync.LOGGER.info("ChunkSync event listeners registered");
    }

    private CompoundEventResult<ClaimResult> beforeChange(CommandSourceStack src, ClaimedChunk chunk) {
        UUID teamId = teamId(chunk);
        if (teamId == null || ChunkApplyGuard.isSuppressed(teamId)) return CompoundEventResult.pass();
        if (!ChunkApplyGuard.shouldBlockPlayerAction(teamId)) return CompoundEventResult.pass();

        ChunkMaterializer.materializeTeam(teamId);
        FTBQuestsSync.LOGGER.warn("Blocked FTB Chunks change while team chunk state is {}: team={} chunk={}",
                ChunkApplyGuard.getState(teamId), teamId, chunk.getPos());
        return CompoundEventResult.interruptFalse(ClaimResult.customProblem("ftbquestssync_chunk_sync_loading"));
    }

    private void upsert(ClaimedChunk chunk, String reason) {
        if (reason.contains("force") && !Config.chunkForceLoadSync) return;
        UUID teamId = teamId(chunk);
        if (teamId == null || ChunkApplyGuard.isSuppressed(teamId)) return;
        boolean needsReconcile = !ChunkApplyGuard.isLoaded(teamId);
        ChunkClaimRecord record = ChunkClaimRecord.from(teamId, chunk);
        MySQLBackend.getInstance().upsertChunkClaimAsync(record).whenComplete((result, err) -> {
            if (err != null || result == null || !result.success()) {
                UUID owner = result == null ? null : result.conflictOwner();
                FTBQuestsSync.LOGGER.warn("Chunk upsert failed/conflicted: team={} chunk={} owner={} reason={}",
                        teamId, record.key(), owner, reason, err);
                ChunkMaterializer.materializeTeam(teamId);
                return;
            }
            schedulePublish(teamId, reason);
            if (needsReconcile) ChunkMaterializer.materializeTeam(teamId);
        });
    }

    private void delete(ClaimedChunk chunk, String reason) {
        UUID teamId = teamId(chunk);
        if (teamId == null || ChunkApplyGuard.isSuppressed(teamId)) return;
        boolean needsReconcile = !ChunkApplyGuard.isLoaded(teamId);
        String dim = ChunkClaimRecord.dimensionId(chunk.getPos().dimension());
        MySQLBackend.getInstance().deleteChunkClaimAsync(teamId, dim, chunk.getPos().x(), chunk.getPos().z())
                .whenComplete((ok, err) -> {
                    if (err != null || !Boolean.TRUE.equals(ok)) {
                        FTBQuestsSync.LOGGER.warn("Chunk delete failed: team={} dim={} x={} z={}",
                                teamId, dim, chunk.getPos().x(), chunk.getPos().z(), err);
                        ChunkMaterializer.materializeTeam(teamId);
                        return;
                    }
                    schedulePublish(teamId, reason);
                    if (needsReconcile) ChunkMaterializer.materializeTeam(teamId);
                });
    }

    private void onJoinedParty(PlayerJoinedPartyTeamEvent ev) {
        Set<UUID> teams = new HashSet<>();
        teams.add(ev.getTeam().getId());
        if (ev.getPreviousTeam() != null) teams.add(ev.getPreviousTeam().getId());
        schedulePartySnapshot(ev.getPlayer().getServer(), teams);
    }

    private void onLeftParty(PlayerLeftPartyTeamEvent ev) {
        Set<UUID> teams = new HashSet<>();
        teams.add(ev.getTeam().getId());
        if (ev.getPlayerTeam() != null) teams.add(ev.getPlayerTeam().getId());
        schedulePartySnapshot(ev.getPlayer().getServer(), teams);
    }

    private void schedulePartySnapshot(MinecraftServer server, Set<UUID> teamIds) {
        if (teamIds.isEmpty() || causedByTeamMaterializer(teamIds)) return;
        server.tell(new TickTask(server.getTickCount() + 1, () -> snapshotTeams(teamIds)));
    }

    private boolean causedByTeamMaterializer(Set<UUID> teamIds) {
        for (UUID teamId : teamIds) {
            if (MySQLBackend.getTeamLoadState(teamId) == MySQLBackend.TeamLoadState.LOADING) return true;
        }
        return false;
    }

    private void snapshotTeams(Set<UUID> teamIds) {
        List<ChunkClaimRecord> rows = new ArrayList<>();
        for (UUID teamId : teamIds) {
            Team team = FTBTeamsAPI.api().getManager().getTeamByID(teamId).orElse(null);
            if (team == null || !FTBChunksAPI.api().isManagerLoaded()) continue;
            ChunkTeamData data = FTBChunksAPI.api().getManager().getOrCreateData(team);
            for (ClaimedChunk chunk : data.getClaimedChunks()) {
                rows.add(ChunkClaimRecord.from(teamId, chunk));
            }
        }
        MySQLBackend.getInstance().replaceChunkClaimsForTeamsAsync(teamIds, rows).whenComplete((ok, err) -> {
            if (err != null || !Boolean.TRUE.equals(ok)) {
                FTBQuestsSync.LOGGER.warn("Party chunk snapshot failed: teams={}", teamIds, err);
                return;
            }
            for (UUID teamId : teamIds) schedulePublish(teamId, "chunks_party_transfer");
        });
    }

    private UUID teamId(ClaimedChunk chunk) {
        if (chunk == null || chunk.getTeamData() == null || chunk.getTeamData().getTeam() == null) return null;
        return chunk.getTeamData().getTeam().getId();
    }

    private void schedulePublish(UUID teamId, String reason) {
        pendingReasons.put(teamId, reason);
        if (!scheduled.add(teamId)) return;
        publishExec.schedule(() -> {
            scheduled.remove(teamId);
            String latest = pendingReasons.remove(teamId);
            if (latest != null) RedisSync.getInstance().publishChunkUpdate(latest, teamId);
        }, 300L, TimeUnit.MILLISECONDS);
    }

    public void shutdown() {
        publishExec.shutdownNow();
    }
}
