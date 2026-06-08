package net.agrarius.ftbquestssync;

import net.minecraft.nbt.CompoundTag;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.UUID;
import java.util.Map;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Singleton HikariCP-backed MySQL store for FTB Quests TeamData blobs.
 *
 * Schema:
 * TeamData writes are enqueued onto a bounded executor. The Minecraft server
 * thread only serializes an immutable NBT snapshot; JDBC and Redis publish run
 * off-thread. Local vanilla file saves are left enabled as fallback.
 *
 * Reconstruction: decompiled from ftb-quests-mysql-sync-1.0.1-test.jar; CFR
 * choked on the try-with-resources blocks of save/load, so those bodies were
 * hand-reconstructed from `javap -c` bytecode. SQL strings copied verbatim
 * from the LDC pool. The reconstruction uses unshaded class names — the
 * Gradle build is responsible for shadowing/relocating HikariCP + the MySQL
 * driver into `net.agrarius.ftbquestssync.shaded.*`.
 */
public class MySQLBackend {

    private static final MySQLBackend INSTANCE = new MySQLBackend();

    /** SQL strings extracted from javap LDC entries of the 1.0.1-test jar. */
    private static final String SQL_UPSERT =
            "INSERT INTO ftbquests_teamdata (team_id, data, data_hash, revision, server_id) "
            + "VALUES (?, ?, ?, 1, ?) "
            + "ON DUPLICATE KEY UPDATE data = VALUES(data), data_hash = VALUES(data_hash), "
            + "revision = revision + 1, server_id = VALUES(server_id)";

    private static final String SQL_INSERT_INITIAL =
            "INSERT INTO ftbquests_teamdata (team_id, data, data_hash, revision, server_id) "
            + "VALUES (?, ?, ?, 1, ?)";

    private static final String SQL_SELECT =
            "SELECT data, server_id, updated_at, revision, data_hash FROM ftbquests_teamdata WHERE team_id = ?";

    private static final String SQL_SELECT_META =
            "SELECT revision, data_hash FROM ftbquests_teamdata WHERE team_id = ?";

    private static final String SQL_SELECT_TEAMDATA_FOR_UPDATE =
            "SELECT data FROM ftbquests_teamdata WHERE team_id = ? FOR UPDATE";

    private static final String SQL_SELECT_TEAMDATA_META_FOR_UPDATE =
            "SELECT data, revision, data_hash FROM ftbquests_teamdata WHERE team_id = ? FOR UPDATE";

    private static final String SQL_TRY_CLAIM_SCOPED =
            "INSERT IGNORE INTO ftbquests_reward_claim_scopes "
            + "(scope_type, scope_uuid, reward_id, cycle, state, team_id, claimed_at_ms, granted_by_server) "
            + "VALUES (?, ?, ?, ?, 'GRANTED', ?, ?, ?)";

    private static final String SQL_CLONE_TEAM_SCOPED_CLAIMS =
            "INSERT IGNORE INTO ftbquests_reward_claim_scopes "
            + "(scope_type, scope_uuid, reward_id, cycle, state, team_id, claimed_at_ms, granted_by_server) "
            + "SELECT scope_type, ?, reward_id, cycle, state, team_id, claimed_at_ms, granted_by_server "
            + "FROM ftbquests_reward_claim_scopes WHERE scope_type='TEAM' AND scope_uuid=?";

    private static final String SQL_DELETE_CLAIM =
            "DELETE FROM ftbquests_reward_claims WHERE team_id=? AND reward_id=? AND claim_uuid=?";

    private static final String SQL_DELETE_ALL_CLAIMS_FOR_REWARD =
            "DELETE FROM ftbquests_reward_claims WHERE team_id=? AND reward_id=?";

    private static final String SQL_DELETE_CLAIM_SCOPED =
            "DELETE FROM ftbquests_reward_claim_scopes WHERE scope_type=? AND scope_uuid=? AND reward_id=?";

    private static final String SQL_DELETE_ALL_CLAIMS_SCOPED_FOR_REWARD =
            "DELETE FROM ftbquests_reward_claim_scopes WHERE team_id=? AND reward_id=?";

    private static final String SQL_UPSERT_TEAM_INFO =
            "INSERT INTO ftbquests_team_info (team_id, team_type, team_name, owner_uuid, team_color, deleted, updated_by_server) "
            + "VALUES (?, ?, ?, ?, ?, 0, ?) "
            + "ON DUPLICATE KEY UPDATE team_type=VALUES(team_type), team_name=VALUES(team_name), "
            + "owner_uuid=VALUES(owner_uuid), team_color=VALUES(team_color), deleted=0, updated_by_server=VALUES(updated_by_server)";

    private static final String SQL_UPSERT_TEAM_INFO_NO_OWNER =
            "INSERT INTO ftbquests_team_info (team_id, team_type, team_name, owner_uuid, team_color, deleted, updated_by_server) "
            + "VALUES (?, ?, ?, ?, ?, 0, ?) "
            + "ON DUPLICATE KEY UPDATE team_type=VALUES(team_type), team_name=VALUES(team_name), "
            + "team_color=VALUES(team_color), deleted=0, updated_by_server=VALUES(updated_by_server)";

    private static final String SQL_MARK_TEAM_DELETED =
            "UPDATE ftbquests_team_info SET deleted=1, updated_by_server=? WHERE team_id=?";

    private static final String SQL_UPSERT_MEMBERSHIP =
            "INSERT INTO ftbquests_team_membership (player_uuid, team_id, rank, updated_by_server) "
            + "VALUES (?, ?, ?, ?) "
            + "ON DUPLICATE KEY UPDATE team_id=VALUES(team_id), rank=VALUES(rank), "
            + "updated_by_server=VALUES(updated_by_server)";

    private static final String SQL_UPSERT_PLAYER_NAME =
            "INSERT INTO ftbquests_player_names (player_uuid, player_name) "
            + "VALUES (?, ?) ON DUPLICATE KEY UPDATE player_name=VALUES(player_name)";

    private static final String SQL_SELECT_PLAYER_UUID_BY_NAME =
            "SELECT player_uuid FROM ftbquests_player_names "
            + "WHERE LOWER(player_name) = LOWER(?) ORDER BY updated_at DESC LIMIT 1";

    private static final String SQL_SELECT_PLAYER_NAME_BY_UUID =
            "SELECT player_name FROM ftbquests_player_names WHERE player_uuid = ?";

    private static final String SQL_UPSERT_OWN_PLAYER_MEMBERSHIP_IF_ABSENT_OR_SELF =
            "INSERT INTO ftbquests_team_membership (player_uuid, team_id, rank, updated_by_server) "
            + "VALUES (?, ?, ?, ?) "
            + "ON DUPLICATE KEY UPDATE "
            + "rank=IF(team_id=VALUES(team_id), VALUES(rank), rank), "
            + "updated_by_server=IF(team_id=VALUES(team_id), VALUES(updated_by_server), updated_by_server)";

    private static final String SQL_SELECT_MEMBERSHIP =
            "SELECT team_id, rank FROM ftbquests_team_membership WHERE player_uuid = ?";

    private static final String SQL_SELECT_MEMBERS_OF_TEAM =
            "SELECT player_uuid, rank FROM ftbquests_team_membership WHERE team_id = ?";

    private static final String SQL_SELECT_TEAM_INFO =
            "SELECT team_type, team_name, owner_uuid, deleted, team_color FROM ftbquests_team_info WHERE team_id = ?";

    private static final String SQL_UPDATE_TEAM_OWNER =
            "UPDATE ftbquests_team_info SET owner_uuid=?, deleted=0, updated_by_server=? WHERE team_id=?";

    private static final String SQL_DEMOTE_OTHER_OWNERS =
            "UPDATE ftbquests_team_membership SET rank='OFFICER', updated_by_server=? WHERE team_id=? AND player_uuid<>? AND rank='OWNER'";

    private static final String SQL_UPSERT_RANK_PROGRESS =
            "INSERT INTO ftbquests_rank_progress "
            + "(player_uuid, quest_id, task_id, progress, completed_at_ms, updated_by_server) "
            + "VALUES (?, ?, ?, ?, ?, ?) "
            + "ON DUPLICATE KEY UPDATE "
            + "progress=VALUES(progress), "
            + "completed_at_ms=CASE "
            + "WHEN VALUES(completed_at_ms)=0 THEN 0 "
            + "WHEN completed_at_ms=0 THEN VALUES(completed_at_ms) "
            + "ELSE completed_at_ms END, "
            + "updated_by_server=VALUES(updated_by_server)";

    private static final String SQL_SELECT_RANK_PROGRESS =
            "SELECT quest_id, task_id, progress, completed_at_ms FROM ftbquests_rank_progress WHERE player_uuid=?";

    private static final String SQL_DELETE_RANK_PROGRESS =
            "DELETE FROM ftbquests_rank_progress WHERE player_uuid=? AND quest_id=?";

    private static final String SQL_UPSERT_CHUNK_CLAIM =
            "INSERT INTO ftbchunks_team_claims "
            + "(team_id, dimension, chunk_x, chunk_z, force_loaded, force_load_expiry_ms, claimed_at_ms, updated_at_ms, updated_by_server) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) "
            + "ON DUPLICATE KEY UPDATE "
            + "force_loaded=IF(team_id=VALUES(team_id), VALUES(force_loaded), force_loaded), "
            + "force_load_expiry_ms=IF(team_id=VALUES(team_id), VALUES(force_load_expiry_ms), force_load_expiry_ms), "
            + "claimed_at_ms=IF(team_id=VALUES(team_id), VALUES(claimed_at_ms), claimed_at_ms), "
            + "updated_at_ms=IF(team_id=VALUES(team_id), VALUES(updated_at_ms), updated_at_ms), "
            + "updated_by_server=IF(team_id=VALUES(team_id), VALUES(updated_by_server), updated_by_server)";

    private static final String SQL_INSERT_CHUNK_CLAIM_IGNORE =
            "INSERT IGNORE INTO ftbchunks_team_claims "
            + "(team_id, dimension, chunk_x, chunk_z, force_loaded, force_load_expiry_ms, claimed_at_ms, updated_at_ms, updated_by_server) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private static final String SQL_DELETE_CHUNK_CLAIM =
            "DELETE FROM ftbchunks_team_claims WHERE team_id=? AND dimension=? AND chunk_x=? AND chunk_z=?";

    private static final String SQL_DELETE_CHUNK_CLAIMS_FOR_TEAM =
            "DELETE FROM ftbchunks_team_claims WHERE team_id=?";

    private static final String SQL_SELECT_CHUNK_CLAIMS_TEAM =
            "SELECT team_id, dimension, chunk_x, chunk_z, force_loaded, force_load_expiry_ms, claimed_at_ms "
            + "FROM ftbchunks_team_claims WHERE team_id=?";

    private static final String SQL_SELECT_CHUNK_CLAIMS_DIMENSION =
            "SELECT team_id, dimension, chunk_x, chunk_z, force_loaded, force_load_expiry_ms, claimed_at_ms "
            + "FROM ftbchunks_team_claims WHERE dimension=?";

    private static final String SQL_SELECT_CHUNK_OWNER =
            "SELECT team_id FROM ftbchunks_team_claims WHERE dimension=? AND chunk_x=? AND chunk_z=?";

    private static final String SQL_SELECT_SYNC_META =
            "SELECT meta_value FROM ftbquestssync_meta WHERE meta_key=?";

    private static final String SQL_UPSERT_SYNC_META =
            "INSERT INTO ftbquestssync_meta (meta_key, meta_value) VALUES (?, ?) "
            + "ON DUPLICATE KEY UPDATE meta_value=VALUES(meta_value)";

    private static final String SQL_UPSERT_INVITE =
            "INSERT INTO ftbquests_team_invites (team_id, invited_uuid, inviter_uuid, updated_by_server) "
            + "VALUES (?, ?, ?, ?) "
            + "ON DUPLICATE KEY UPDATE inviter_uuid=VALUES(inviter_uuid), updated_by_server=VALUES(updated_by_server)";

    private static final String SQL_DELETE_INVITE =
            "DELETE FROM ftbquests_team_invites WHERE team_id=? AND invited_uuid=?";

    private static final String SQL_DELETE_INVITES_FOR_TEAM =
            "DELETE FROM ftbquests_team_invites WHERE team_id=?";

    private static final String SQL_SELECT_INVITE =
            "SELECT team_id, invited_uuid, inviter_uuid FROM ftbquests_team_invites WHERE team_id=? AND invited_uuid=?";

    private static final String SQL_SELECT_INVITES_FOR_TEAM =
            "SELECT team_id, invited_uuid, inviter_uuid FROM ftbquests_team_invites WHERE team_id=?";

    private static final String SQL_SELECT_INVITES_FOR_PLAYER =
            "SELECT team_id, invited_uuid, inviter_uuid FROM ftbquests_team_invites WHERE invited_uuid=?";

    private ConnectionProvider connectionProvider;
    private volatile boolean initialized;
    private final ExecutorService dbExecutor = new ThreadPoolExecutor(
            1,
            1,
            30L,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(256),
            r -> {
                Thread t = new Thread(r, "FTBQuestsSync-DB");
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.AbortPolicy());

    private MySQLBackend() {
    }

    public static MySQLBackend getInstance() {
        return INSTANCE;
    }

    public boolean isAvailable() {
        return initialized && connectionProvider != null && connectionProvider.isAvailable();
    }

    public void initialize() {
        try {
            if (Config.mysqlPassword == null || Config.mysqlPassword.isBlank()) {
                FTBQuestsSync.LOGGER.error(
                        "MySQL password missing; sync disabled. "
                        + "Set /opt/agrarius/config/ftbquestssync-server.toml password "
                        + "or -Dftbquestssync.mysql.password");
                initialized = false;
                return;
            }

            connectionProvider = new ConnectionProvider();
            connectionProvider.open();
            new SchemaManager(connectionProvider).ensureSchema();
            initialized = true;

            FTBQuestsSync.LOGGER.info("MySQL ready: {}:{}/{} user={} pool={}/{}",
                    Config.mysqlHost, Config.mysqlPort, Config.mysqlDatabase,
                    Config.mysqlUsername, Config.mysqlMinIdle, Config.mysqlMaxPool);
        } catch (Exception e) {
            initialized = false;
            FTBQuestsSync.LOGGER.error("MySQL init failed - quest sync disabled", e);
        }
    }

    public static final class SaveResult {
        public final UUID teamId;
        public final long revision;
        public final String hashHex;

        private SaveResult(UUID teamId, long revision, String hashHex) {
            this.teamId = teamId;
            this.revision = revision;
            this.hashHex = hashHex;
        }
    }

    private static final class LockedTeamDataRow {
        private final byte[] data;
        private final long revision;
        private final byte[] hash;

        private LockedTeamDataRow(byte[] data, long revision, byte[] hash) {
            this.data = data;
            this.revision = revision;
            this.hash = hash;
        }
    }

    private static final class SerializedTeamData {
        private final byte[] bytes;
        private final byte[] hash;

        private SerializedTeamData(byte[] bytes, byte[] hash) {
            this.bytes = bytes;
            this.hash = hash;
        }
    }

    public static final class ScopedClaimResult {
        public final boolean firstClaim;
        public final boolean cycleComplete;

        private ScopedClaimResult(boolean firstClaim, boolean cycleComplete) {
            this.firstClaim = firstClaim;
            this.cycleComplete = cycleComplete;
        }
    }

    /**
     * Atomic claim guard. Returns true if this is the FIRST claim for the
     * (team_id, reward_id, claim_uuid) tuple across ALL servers; false if
     * another server has already recorded the claim.
     *
     * Caller MUST pass the QuestKey-equivalent UUID:
     *   - team UUID when {@code reward.isTeamReward()} (one claim per team)
     *   - player UUID otherwise               (one claim per player)
     *
     * MUST be called off the server thread (HikariCP getConnection may block).
     */
    public boolean tryClaimReward(UUID teamId, long rewardId, UUID claimUuid, long claimedAtMs) {
        return tryClaimRewardScoped(teamId, rewardId, "LEGACY", claimUuid, claimedAtMs);
    }

    public boolean tryClaimRewardScoped(UUID teamId, long rewardId, String scopeType, UUID scopeUuid, long claimedAtMs) {
        return tryClaimRewardScoped(teamId, rewardId, scopeType, scopeUuid, 0L, claimedAtMs, new long[]{rewardId}).firstClaim;
    }

    public ScopedClaimResult tryClaimRewardScoped(
            UUID teamId, long rewardId, String scopeType, UUID scopeUuid, long cycle, long claimedAtMs, long[] questRewardIds) {
        if (!isAvailable()) {
            FTBQuestsSync.LOGGER.warn(
                    "DB unavailable - allowing reward claim with NO cross-server guard "
                    + "(team={} reward={} scopeType={} scopeUuid={} cycle={})", teamId, rewardId, scopeType, scopeUuid, cycle);
            return new ScopedClaimResult(!Config.rewardFailClosed, false);
        }
        try (Connection conn = connectionProvider.getConnection();
             PreparedStatement ps = conn.prepareStatement(SQL_TRY_CLAIM_SCOPED)) {

            ps.setString(1, scopeType);
            ps.setString(2, scopeUuid.toString());
            ps.setLong(3, rewardId);
            ps.setLong(4, cycle);
            ps.setString(5, teamId.toString());
            ps.setLong(6, claimedAtMs);
            ps.setString(7, RedisSync.getInstance().getServerId());
            int rows = ps.executeUpdate();
            long[] cycleRewardIds = questRewardIds == null ? new long[]{rewardId} : questRewardIds;

            if (rows == 0) {
                FTBQuestsSync.LOGGER.info(
                        "Reward claim REFUSED (duplicate): team={} reward={} scopeType={} scopeUuid={} cycle={}",
                        teamId, rewardId, scopeType, scopeUuid, cycle);
                return new ScopedClaimResult(false, false);
            }
            boolean cycleComplete = cycleRewardIds.length > 0
                    && countClaimsInCycle(conn, scopeType, scopeUuid, cycle, cycleRewardIds) >= cycleRewardIds.length;
            FTBQuestsSync.LOGGER.info(
                    "Reward claim GRANTED (first): team={} reward={} scopeType={} scopeUuid={} cycle={} cycleComplete={} server={}",
                    teamId, rewardId, scopeType, scopeUuid, cycle, cycleComplete, RedisSync.getInstance().getServerId());
            return new ScopedClaimResult(true, cycleComplete);
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.error(
                    "tryClaimReward failed for team={} reward={} scopeType={} cycle={} - rewardFailClosed={}",
                    teamId, rewardId, scopeType, cycle, Config.rewardFailClosed, e);
            return new ScopedClaimResult(!Config.rewardFailClosed, false);
        }
    }

    private int countClaimsInCycle(Connection conn, String scopeType, UUID scopeUuid, long cycle, long[] rewardIds) throws Exception {
        if (rewardIds == null || rewardIds.length == 0) return 0;
        StringBuilder sql = new StringBuilder(
                "SELECT COUNT(*) FROM ftbquests_reward_claim_scopes "
                + "WHERE scope_type=? AND scope_uuid=? AND cycle=? AND state='GRANTED' AND reward_id IN (");
        for (int i = 0; i < rewardIds.length; i++) {
            if (i > 0) sql.append(',');
            sql.append('?');
        }
        sql.append(')');
        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            ps.setString(1, scopeType);
            ps.setString(2, scopeUuid.toString());
            ps.setLong(3, cycle);
            for (int i = 0; i < rewardIds.length; i++) {
                ps.setLong(4 + i, rewardIds[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    public CompletableFuture<Boolean> tryClaimRewardScopedAsync(UUID teamId, long rewardId, String scopeType, UUID scopeUuid, long claimedAtMs) {
        try {
            return CompletableFuture.supplyAsync(() -> tryClaimRewardScoped(teamId, rewardId, scopeType, scopeUuid, claimedAtMs), dbExecutor);
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.warn("DB queue full; cannot guard reward claim team={} reward={} scopeType={}", teamId, rewardId, scopeType, e);
            return CompletableFuture.completedFuture(!Config.rewardFailClosed);
        }
    }

    public CompletableFuture<ScopedClaimResult> tryClaimRewardScopedAsync(
            UUID teamId, long rewardId, String scopeType, UUID scopeUuid, long cycle, long claimedAtMs, long[] questRewardIds) {
        try {
            return CompletableFuture.supplyAsync(
                    () -> tryClaimRewardScoped(teamId, rewardId, scopeType, scopeUuid, cycle, claimedAtMs, questRewardIds),
                    dbExecutor);
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.warn("DB queue full; cannot guard reward claim team={} reward={} scopeType={} cycle={}",
                    teamId, rewardId, scopeType, cycle, e);
            return CompletableFuture.completedFuture(new ScopedClaimResult(!Config.rewardFailClosed, false));
        }
    }

    public CompletableFuture<CompoundTag> loadTeamDataAsync(UUID teamId) {
        if (!isAvailable()) return CompletableFuture.completedFuture(null);
        try {
            return CompletableFuture.supplyAsync(() -> loadTeamData(teamId), dbExecutor);
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.warn("DB queue full; cannot load team {} asynchronously", teamId, e);
            return CompletableFuture.completedFuture(null);
        }
    }

    /**
     * Upserts the serialized team blob. Returns true on a successful write.
     * MUST be called off the server thread (HikariCP getConnection may block).
     */
    public CompletableFuture<SaveResult> saveTeamDataAsync(UUID teamId, CompoundTag tag) {
        if (!isAvailable()) return CompletableFuture.completedFuture(null);
        TeamLoadStateRegistry.TeamLoadState state = TeamLoadStateRegistry.getTeamLoadState(teamId);
        // LOADED is the only state where a revision-incrementing upsert is safe.
        // UNKNOWN/NEW means we may not have a DB row yet: the upsert path would
        // either clobber an unloaded existing row or be blocked outright. Route
        // those through an INSERT-only first write that runs the existence check
        // and the insert on the single dbExecutor thread, so a brand-new party's
        // blob is persisted without ever overwriting populated DB data.
        try {
            if (state == TeamLoadStateRegistry.TeamLoadState.LOADED) {
                return CompletableFuture.supplyAsync(() -> saveTeamData(teamId, tag), dbExecutor);
            }
            if (state == TeamLoadStateRegistry.TeamLoadState.UNKNOWN || state == TeamLoadStateRegistry.TeamLoadState.NEW) {
                return CompletableFuture.supplyAsync(() -> saveInitialTeamDataIfAbsent(teamId, tag), dbExecutor);
            }
            FTBQuestsSync.LOGGER.warn("Save blocked for team {} - load state={}", teamId, state);
            return CompletableFuture.completedFuture(null);
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.warn("DB queue full; vanilla file save remains fallback for team {}", teamId, e);
            return CompletableFuture.completedFuture(null);
        }
    }

    private SaveResult saveInitialTeamDataIfAbsent(UUID teamId, CompoundTag tag) {
        if (!isAvailable()) return null;
        TeamLoadStateRegistry.TeamLoadState state = TeamLoadStateRegistry.getTeamLoadState(teamId);
        if (state == TeamLoadStateRegistry.TeamLoadState.LOADED) return saveTeamData(teamId, tag);
        if (state != TeamLoadStateRegistry.TeamLoadState.UNKNOWN && state != TeamLoadStateRegistry.TeamLoadState.NEW) {
            FTBQuestsSync.LOGGER.warn("Save blocked for team {} - load state={}", teamId, state);
            return null;
        }
        try (Connection conn = connectionProvider.getConnection()) {
            CompoundTag sanitized = tag.copy();
            RankSoloProgress.stripRankSharedProgress(sanitized);
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            NbtCompat.writeCompressed(sanitized, buf);
            byte[] bytes = buf.toByteArray();
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes);

            // Existence check + INSERT-only run back-to-back on the single dbExecutor
            // thread. A plain INSERT (no ON DUPLICATE KEY) cannot overwrite a row
            // another server already wrote; a duplicate-key here means a peer won
            // the race, so we block and leave the load state for a proper reload.
            SaveResult existing = loadMeta(conn, teamId, hash);
            if (existing.revision > 0) {
                TeamLoadStateRegistry.setTeamLoadState(teamId, TeamLoadStateRegistry.TeamLoadState.UNKNOWN);
                FTBQuestsSync.LOGGER.warn(
                        "Save blocked for team {} - DB row already exists rev={}; needs reload before write",
                        teamId, existing.revision);
                return null;
            }
            try (PreparedStatement ps = conn.prepareStatement(SQL_INSERT_INITIAL)) {
                ps.setString(1, teamId.toString());
                ps.setBytes(2, bytes);
                ps.setBytes(3, hash);
                ps.setString(4, RedisSync.getInstance().getServerId());
                ps.executeUpdate();
            } catch (java.sql.SQLException e) {
                if (e.getErrorCode() == 1062) {
                    TeamLoadStateRegistry.setTeamLoadState(teamId, TeamLoadStateRegistry.TeamLoadState.UNKNOWN);
                    FTBQuestsSync.LOGGER.warn(
                            "Initial save lost race for team {} - peer wrote first; blocking until reload", teamId);
                    return null;
                }
                throw e;
            }
            TeamLoadStateRegistry.setTeamLoadState(teamId, TeamLoadStateRegistry.TeamLoadState.LOADED);
            String hashHex = HexFormat.of().formatHex(hash);
            FTBQuestsSync.LOGGER.info(
                    "Saved FTB team data to MySQL (initial): team={} bytes={} revision=1 hash={} serverId={}",
                    teamId, bytes.length, hashHex, RedisSync.getInstance().getServerId());
            return new SaveResult(teamId, 1L, hashHex);
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.error("Initial MySQL save failed for team {}", teamId, e);
            return null;
        }
    }

    /**
     * Blocking write. Prefer {@link #saveTeamDataAsync(UUID, CompoundTag)} from
     * Minecraft-thread hooks.
     */
    public SaveResult saveTeamData(UUID teamId, CompoundTag tag) {
        if (!isAvailable()) return null;

        try (Connection conn = connectionProvider.getConnection();
             PreparedStatement ps = conn.prepareStatement(SQL_UPSERT)) {

            CompoundTag sanitized = tag.copy();
            RankSoloProgress.stripRankSharedProgress(sanitized);
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            NbtCompat.writeCompressed(sanitized, buf);
            byte[] bytes = buf.toByteArray();
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes);

            ps.setString(1, teamId.toString());
            ps.setBytes(2, bytes);
            ps.setBytes(3, hash);
            ps.setString(4, RedisSync.getInstance().getServerId());
            int rows = ps.executeUpdate();

            SaveResult meta = loadMeta(conn, teamId, hash);

            FTBQuestsSync.LOGGER.info(
                    "Saved FTB team data to MySQL: team={} bytes={} rows={} revision={} hash={} serverId={}",
                    teamId, bytes.length, rows, meta.revision, meta.hashHex, RedisSync.getInstance().getServerId());
            return meta;
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.error("MySQL save failed for team {}", teamId, e);
            return null;
        }
    }

    private SaveResult loadMeta(Connection conn, UUID teamId, byte[] fallbackHash) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(SQL_SELECT_META)) {
            ps.setString(1, teamId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    byte[] hash = rs.getBytes(2);
                    return new SaveResult(teamId, rs.getLong(1), HexFormat.of().formatHex(hash != null ? hash : fallbackHash));
                }
            }
        }
        return new SaveResult(teamId, 0L, HexFormat.of().formatHex(fallbackHash));
    }

    /**
     * Loads and decompresses the team blob. Returns null when there is no row
     * or when an error occurs (caller falls back to vanilla file).
     */
    public CompoundTag loadTeamData(UUID teamId) {
        if (!isAvailable()) return null;

        try (Connection conn = connectionProvider.getConnection();
             PreparedStatement ps = conn.prepareStatement(SQL_SELECT)) {

            ps.setString(1, teamId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    FTBQuestsSync.LOGGER.debug("No MySQL FTB team data for {}", teamId);
                    return null;
                }
                byte[] bytes = rs.getBytes(1);
                CompoundTag tag = NbtCompat.readCompressed(new ByteArrayInputStream(bytes));
                RankSoloProgress.stripRankSharedProgress(tag);
                FTBQuestsSync.LOGGER.info(
                        "Loaded FTB team data from MySQL: team={} bytes={} sourceServer={}",
                        teamId, bytes.length, rs.getString(2));
                return tag;
            }
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.error("MySQL load failed for team {}", teamId, e);
            return null;
        }
    }

    public boolean migratePartyMemberToSolo(UUID partyTeamId, UUID playerUuid) {
        if (!isAvailable()) return false;
        String serverId = RedisSync.getInstance().getServerId();

        try (Connection conn = connectionProvider.getConnection()) {
            boolean previousAutoCommit = conn.getAutoCommit();
            try {
                conn.setAutoCommit(false);

                byte[] partyBytes = selectTeamDataBytesForUpdate(conn, partyTeamId);
                if (partyBytes == null) {
                    conn.rollback();
                    FTBQuestsSync.LOGGER.warn(
                            "Party-to-solo migration skipped: no party teamdata party={} player={}",
                            partyTeamId, playerUuid);
                    return false;
                }

                LockedTeamDataRow soloRow = selectTeamDataRowForUpdate(conn, playerUuid);
                CompoundTag partyTag = NbtCompat.readCompressed(new ByteArrayInputStream(partyBytes));
                RankSoloProgress.stripRankSharedProgress(partyTag);
                int partyCompleted = completedEntryCount(partyTag);
                int soloCompleted = -1;
                String blobDecision;
                boolean shouldWriteParty;

                if (soloRow == null) {
                    shouldWriteParty = true;
                    blobDecision = "copy_party_no_solo_row";
                } else {
                    CompoundTag soloTag = NbtCompat.readCompressed(new ByteArrayInputStream(soloRow.data));
                    soloCompleted = completedEntryCount(soloTag);
                    shouldWriteParty = soloCompleted <= partyCompleted;
                    blobDecision = shouldWriteParty ? "copy_party_not_poorer" : "keep_richer_solo";
                }

                int blobRows = 0;
                if (shouldWriteParty) {
                    SerializedTeamData serialized = serializeTeamData(partyTag);
                    if (soloRow != null && hashesEqual(soloRow.hash, serialized.hash)) {
                        blobDecision = "keep_already_current";
                    } else {
                        try (PreparedStatement ps = conn.prepareStatement(SQL_UPSERT)) {
                            ps.setString(1, playerUuid.toString());
                            ps.setBytes(2, serialized.bytes);
                            ps.setBytes(3, serialized.hash);
                            ps.setString(4, serverId);
                            blobRows = ps.executeUpdate();
                        }
                    }
                }

                int clonedScopes;
                try (PreparedStatement ps = conn.prepareStatement(SQL_CLONE_TEAM_SCOPED_CLAIMS)) {
                    ps.setString(1, playerUuid.toString());
                    ps.setString(2, partyTeamId.toString());
                    clonedScopes = ps.executeUpdate();
                }

                int membershipRows;
                try (PreparedStatement ps = conn.prepareStatement(SQL_UPSERT_MEMBERSHIP)) {
                    ps.setString(1, playerUuid.toString());
                    ps.setString(2, playerUuid.toString());
                    ps.setString(3, "OWNER");
                    ps.setString(4, serverId);
                    membershipRows = ps.executeUpdate();
                }

                conn.commit();
                FTBQuestsSync.LOGGER.info(
                        "Party-to-solo migration committed: party={} player={} decision={} partyCompleted={} soloCompleted={} soloRevision={} blobRows={} scopesCloned={} membershipRows={}",
                        partyTeamId, playerUuid, blobDecision, partyCompleted, soloCompleted,
                        soloRow == null ? 0L : soloRow.revision, blobRows, clonedScopes, membershipRows);
                return blobRows > 0 || clonedScopes > 0;
            } catch (Exception e) {
                try {
                    conn.rollback();
                } catch (Exception rollbackError) {
                    FTBQuestsSync.LOGGER.warn(
                            "Party-to-solo migration rollback failed party={} player={}",
                            partyTeamId, playerUuid, rollbackError);
                }
                throw e;
            } finally {
                try {
                    conn.setAutoCommit(previousAutoCommit);
                } catch (Exception restoreError) {
                    FTBQuestsSync.LOGGER.warn(
                            "Party-to-solo migration autocommit restore failed party={} player={}",
                            partyTeamId, playerUuid, restoreError);
                }
            }
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.error("Party-to-solo migration failed party={} player={}", partyTeamId, playerUuid, e);
            return false;
        }
    }

    public CompletableFuture<SaveResult> migratePartyMemberToSoloAsync(UUID partyTeamId, UUID playerUuid) {
        if (!isAvailable()) return CompletableFuture.completedFuture(null);
        try {
            return CompletableFuture.supplyAsync(() -> {
                boolean migrated = migratePartyMemberToSolo(partyTeamId, playerUuid);
                return migrated ? loadMetaForPublish(playerUuid) : null;
            }, dbExecutor);
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.warn(
                    "DB queue full; cannot migrate party member to solo party={} player={}",
                    partyTeamId, playerUuid, e);
            return CompletableFuture.completedFuture(null);
        }
    }

    public void migratePartyToSoloMembers(UUID partyTeamId, Collection<UUID> memberUuids) {
        if (!isAvailable() || memberUuids == null || memberUuids.isEmpty()) return;
        List<UUID> members = new ArrayList<>(memberUuids);
        for (UUID memberUuid : members) {
            if (memberUuid == null) continue;
            migratePartyMemberToSoloAsync(partyTeamId, memberUuid).whenComplete((result, error) -> {
                if (error != null) {
                    FTBQuestsSync.LOGGER.error(
                            "Party-to-solo async migration failed party={} player={}",
                            partyTeamId, memberUuid, error);
                    return;
                }
                if (result != null) {
                    RedisSync.getInstance().publishTeamUpdate(result.teamId, result.revision, result.hashHex);
                }
            });
        }
    }

    private byte[] selectTeamDataBytesForUpdate(Connection conn, UUID teamId) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(SQL_SELECT_TEAMDATA_FOR_UPDATE)) {
            ps.setString(1, teamId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getBytes(1) : null;
            }
        }
    }

    private LockedTeamDataRow selectTeamDataRowForUpdate(Connection conn, UUID teamId) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(SQL_SELECT_TEAMDATA_META_FOR_UPDATE)) {
            ps.setString(1, teamId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new LockedTeamDataRow(rs.getBytes(1), rs.getLong(2), rs.getBytes(3));
            }
        }
    }

    private SerializedTeamData serializeTeamData(CompoundTag tag) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        NbtCompat.writeCompressed(tag, buf);
        byte[] bytes = buf.toByteArray();
        return new SerializedTeamData(bytes, MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private int completedEntryCount(CompoundTag tag) {
        return tag == null ? 0 : tag.getCompound("completed").size();
    }

    private boolean hashesEqual(byte[] first, byte[] second) {
        return first != null && second != null && MessageDigest.isEqual(first, second);
    }

    private SaveResult loadMetaForPublish(UUID teamId) {
        if (!isAvailable()) return null;
        try (Connection conn = connectionProvider.getConnection();
             PreparedStatement ps = conn.prepareStatement(SQL_SELECT)) {
            ps.setString(1, teamId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                byte[] hash = rs.getBytes(5);
                if (hash == null) {
                    hash = MessageDigest.getInstance("SHA-256").digest(rs.getBytes(1));
                }
                return new SaveResult(teamId, rs.getLong(4), HexFormat.of().formatHex(hash));
            }
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.error("loadMetaForPublish failed team={}", teamId, e);
            return null;
        }
    }

    public void deleteRewardClaim(UUID teamId, long rewardId, UUID claimUuid) {
        if (!isAvailable()) return;
        try (Connection conn = connectionProvider.getConnection();
             PreparedStatement ps = conn.prepareStatement(SQL_DELETE_CLAIM)) {
            ps.setString(1, teamId.toString());
            ps.setLong(2, rewardId);
            ps.setString(3, claimUuid.toString());
            int rows = ps.executeUpdate();
            FTBQuestsSync.LOGGER.info("Reward claim DELETED (reset): team={} reward={} claimUuid={} rows={}",
                    teamId, rewardId, claimUuid, rows);
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.error("deleteRewardClaim failed team={} reward={}", teamId, rewardId, e);
        }
    }

    public void deleteRewardClaimScopedAsync(String scopeType, UUID scopeUuid, long rewardId) {
        if (!isAvailable()) return;
        try {
            CompletableFuture.runAsync(() -> deleteRewardClaimScoped(scopeType, scopeUuid, rewardId), dbExecutor);
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.warn("DB queue full; cannot delete scoped reward claim scopeType={} scopeUuid={} reward={}",
                    scopeType, scopeUuid, rewardId, e);
        }
    }

    private void deleteRewardClaimScoped(String scopeType, UUID scopeUuid, long rewardId) {
        if (!isAvailable()) return;
        try (Connection conn = connectionProvider.getConnection();
             PreparedStatement ps = conn.prepareStatement(SQL_DELETE_CLAIM_SCOPED)) {
            ps.setString(1, scopeType);
            ps.setString(2, scopeUuid.toString());
            ps.setLong(3, rewardId);
            int rows = ps.executeUpdate();
            FTBQuestsSync.LOGGER.info("Scoped reward claim DELETED: scopeType={} scopeUuid={} reward={} rows={}",
                    scopeType, scopeUuid, rewardId, rows);
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.error("deleteRewardClaimScoped failed scopeType={} scopeUuid={} reward={}",
                    scopeType, scopeUuid, rewardId, e);
        }
    }

    public void deleteAllClaimsForReward(UUID teamId, long rewardId) {
        if (!isAvailable()) return;
        try (Connection conn = connectionProvider.getConnection();
             PreparedStatement ps = conn.prepareStatement(SQL_DELETE_ALL_CLAIMS_FOR_REWARD)) {
            ps.setString(1, teamId.toString());
            ps.setLong(2, rewardId);
            int rows = ps.executeUpdate();
            FTBQuestsSync.LOGGER.info("All claims DELETED for reward: team={} reward={} rows={}",
                    teamId, rewardId, rows);
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.error("deleteAllClaimsForReward failed team={} reward={}", teamId, rewardId, e);
        }
    }

    public void deleteAllClaimsForRewardAsync(UUID teamId, long rewardId) {
        if (!isAvailable()) return;
        try {
            CompletableFuture.runAsync(() -> {
                deleteAllClaimsForReward(teamId, rewardId);
                deleteAllScopedClaimsForReward(teamId, rewardId);
            }, dbExecutor);
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.warn("DB queue full; cannot delete all reward claims team={} reward={}", teamId, rewardId, e);
        }
    }

    private void deleteAllScopedClaimsForReward(UUID teamId, long rewardId) {
        if (!isAvailable()) return;
        try (Connection conn = connectionProvider.getConnection();
             PreparedStatement ps = conn.prepareStatement(SQL_DELETE_ALL_CLAIMS_SCOPED_FOR_REWARD)) {
            ps.setString(1, teamId.toString());
            ps.setLong(2, rewardId);
            int rows = ps.executeUpdate();
            FTBQuestsSync.LOGGER.info("All scoped claims DELETED for reward: team={} reward={} rows={}",
                    teamId, rewardId, rows);
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.error("deleteAllScopedClaimsForReward failed team={} reward={}", teamId, rewardId, e);
        }
    }

    public void upsertTeamInfo(UUID teamId, String type, String name, UUID owner, String color) {
        if (!isAvailable()) return;
        try {
            upsertTeamInfoOrThrow(teamId, type, name, owner, color);
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.error("upsertTeamInfo failed for {}", teamId, e);
        }
    }

    private void upsertTeamInfoOrThrow(UUID teamId, String type, String name, UUID owner, String color) throws Exception {
        try (Connection conn = connectionProvider.getConnection();
             PreparedStatement ps = conn.prepareStatement(SQL_UPSERT_TEAM_INFO)) {
            ps.setString(1, teamId.toString());
            ps.setString(2, type);
            ps.setString(3, name);
            ps.setString(4, owner == null ? null : owner.toString());
            ps.setString(5, color);
            ps.setString(6, RedisSync.getInstance().getServerId());
            int rows = ps.executeUpdate();
            FTBQuestsSync.LOGGER.info("Team info upsert: id={} type={} name={} owner={} color={} rows={}",
                    teamId, type, name, owner, color, rows);
        }
    }

    public void upsertTeamInfoAsync(UUID teamId, String type, String name, UUID owner, String color) {
        if (!isAvailable()) return;
        try {
            CompletableFuture.runAsync(() -> upsertTeamInfo(teamId, type, name, owner, color), dbExecutor);
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.warn("DB queue full; cannot upsert team info {}", teamId, e);
        }
    }

    public void upsertTeamInfoNoOwner(UUID teamId, String type, String name, UUID owner, String color) {
        if (!isAvailable()) return;
        try {
            upsertTeamInfoNoOwnerOrThrow(teamId, type, name, owner, color);
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.error("upsertTeamInfoNoOwner failed for {}", teamId, e);
        }
    }

    private void upsertTeamInfoNoOwnerOrThrow(UUID teamId, String type, String name, UUID owner, String color) throws Exception {
        try (Connection conn = connectionProvider.getConnection();
             PreparedStatement ps = conn.prepareStatement(SQL_UPSERT_TEAM_INFO_NO_OWNER)) {
            ps.setString(1, teamId.toString());
            ps.setString(2, type);
            ps.setString(3, name);
            ps.setString(4, owner == null ? null : owner.toString());
            ps.setString(5, color);
            ps.setString(6, RedisSync.getInstance().getServerId());
            int rows = ps.executeUpdate();
            FTBQuestsSync.LOGGER.info("Team info upsert (no-owner): id={} type={} name={} color={} rows={}",
                    teamId, type, name, color, rows);
        }
    }

    public void upsertTeamInfoNoOwnerAsync(UUID teamId, String type, String name, UUID owner, String color) {
        if (!isAvailable()) return;
        try {
            CompletableFuture.runAsync(() -> upsertTeamInfoNoOwner(teamId, type, name, owner, color), dbExecutor);
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.warn("DB queue full; cannot upsert team info {}", teamId, e);
        }
    }

    public CompletableFuture<Void> upsertTeamInfoNoOwnerFuture(UUID teamId, String type, String name, UUID owner, String color) {
        if (!isAvailable()) return CompletableFuture.failedFuture(new IllegalStateException("MySQL unavailable"));
        try {
            return CompletableFuture.runAsync(() -> {
                try {
                    upsertTeamInfoNoOwnerOrThrow(teamId, type, name, owner, color);
                } catch (Exception e) {
                    throw new CompletionException(e);
                }
            }, dbExecutor);
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.warn("DB queue full; cannot upsert team info {}", teamId, e);
            return CompletableFuture.failedFuture(e);
        }
    }

    public CompletableFuture<Void> upsertTeamInfoFuture(UUID teamId, String type, String name, UUID owner, String color) {
        if (!isAvailable()) return CompletableFuture.failedFuture(new IllegalStateException("MySQL unavailable"));
        try {
            return CompletableFuture.runAsync(() -> {
                try {
                    upsertTeamInfoOrThrow(teamId, type, name, owner, color);
                } catch (Exception e) {
                    throw new CompletionException(e);
                }
            }, dbExecutor);
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.warn("DB queue full; cannot upsert team info {}", teamId, e);
            return CompletableFuture.failedFuture(e);
        }
    }

    public void markTeamDeleted(UUID teamId) {
        if (!isAvailable()) return;
        try (Connection conn = connectionProvider.getConnection();
             PreparedStatement ps = conn.prepareStatement(SQL_MARK_TEAM_DELETED)) {
            ps.setString(1, RedisSync.getInstance().getServerId());
            ps.setString(2, teamId.toString());
            ps.executeUpdate();
            FTBQuestsSync.LOGGER.info("Team marked deleted: id={}", teamId);
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.error("markTeamDeleted failed for {}", teamId, e);
        }
    }

    public void markTeamDeletedAsync(UUID teamId) {
        if (!isAvailable()) return;
        try {
            CompletableFuture.runAsync(() -> markTeamDeleted(teamId), dbExecutor);
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.warn("DB queue full; cannot mark team deleted {}", teamId, e);
        }
    }

    public void upsertMembership(UUID playerUuid, UUID teamId, String rank) {
        if (!isAvailable()) return;
        try {
            upsertMembershipOrThrow(playerUuid, teamId, rank);
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.error("upsertMembership failed for player={} team={}", playerUuid, teamId, e);
        }
    }

    private void upsertMembershipOrThrow(UUID playerUuid, UUID teamId, String rank) throws Exception {
        try (Connection conn = connectionProvider.getConnection();
             PreparedStatement ps = conn.prepareStatement(SQL_UPSERT_MEMBERSHIP)) {
            ps.setString(1, playerUuid.toString());
            ps.setString(2, teamId.toString());
            ps.setString(3, rank);
            ps.setString(4, RedisSync.getInstance().getServerId());
            int rows = ps.executeUpdate();
            FTBQuestsSync.LOGGER.info("Membership upsert: player={} team={} rank={} rows={}",
                    playerUuid, teamId, rank, rows);
        }
    }

    public CompletableFuture<Void> upsertMembershipFuture(UUID playerUuid, UUID teamId, String rank) {
        if (!isAvailable()) return CompletableFuture.failedFuture(new IllegalStateException("MySQL unavailable"));
        try {
            return CompletableFuture.runAsync(() -> {
                try {
                    upsertMembershipOrThrow(playerUuid, teamId, rank);
                } catch (Exception e) {
                    throw new CompletionException(e);
                }
            }, dbExecutor);
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.warn("DB queue full; cannot upsert membership player={} team={}", playerUuid, teamId, e);
            return CompletableFuture.failedFuture(e);
        }
    }

    public void upsertMembershipAsync(UUID playerUuid, UUID teamId, String rank) {
        if (!isAvailable()) return;
        try {
            CompletableFuture.runAsync(() -> upsertMembership(playerUuid, teamId, rank), dbExecutor);
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.warn("DB queue full; cannot upsert membership player={} team={}", playerUuid, teamId, e);
        }
    }

    public void upsertOwnPlayerMembershipIfAbsentOrSelf(UUID playerUuid, String rank) {
        if (!isAvailable()) return;
        try (Connection conn = connectionProvider.getConnection();
             PreparedStatement ps = conn.prepareStatement(SQL_UPSERT_OWN_PLAYER_MEMBERSHIP_IF_ABSENT_OR_SELF)) {
            ps.setString(1, playerUuid.toString());
            ps.setString(2, playerUuid.toString());
            ps.setString(3, rank);
            ps.setString(4, RedisSync.getInstance().getServerId());
            int rows = ps.executeUpdate();
            FTBQuestsSync.LOGGER.info("Solo membership upsert (guarded): player={} rows={}", playerUuid, rows);
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.error("upsertOwnPlayerMembershipIfAbsentOrSelf failed for player={}", playerUuid, e);
        }
    }

    public void upsertOwnPlayerMembershipIfAbsentOrSelfAsync(UUID playerUuid, String rank) {
        if (!isAvailable()) return;
        try {
            CompletableFuture.runAsync(() -> upsertOwnPlayerMembershipIfAbsentOrSelf(playerUuid, rank), dbExecutor);
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.warn("DB queue full; cannot upsert solo membership player={}", playerUuid, e);
        }
    }

    public void upsertPlayerName(UUID playerUuid, String playerName) {
        if (!isAvailable() || playerName == null || playerName.isBlank()) return;
        try (Connection conn = connectionProvider.getConnection();
             PreparedStatement ps = conn.prepareStatement(SQL_UPSERT_PLAYER_NAME)) {
            ps.setString(1, playerUuid.toString());
            ps.setString(2, playerName);
            ps.executeUpdate();
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.error("upsertPlayerName failed player={} name={}", playerUuid, playerName, e);
        }
    }

    public void upsertPlayerNameAsync(UUID playerUuid, String playerName) {
        if (!isAvailable()) return;
        try {
            CompletableFuture.runAsync(() -> upsertPlayerName(playerUuid, playerName), dbExecutor);
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.warn("DB queue full; cannot upsert player name player={}", playerUuid, e);
        }
    }

    public List<UUID> selectMembershipUuidsMissingName() {
        if (!isAvailable()) return List.of();
        String sql = "SELECT m.player_uuid FROM ftbquests_team_membership m "
                + "LEFT JOIN ftbquests_player_names n ON n.player_uuid = m.player_uuid "
                + "WHERE n.player_uuid IS NULL OR n.player_name IS NULL OR n.player_name = ''";
        List<UUID> out = new ArrayList<>();
        try (Connection conn = connectionProvider.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                try {
                    out.add(UUID.fromString(rs.getString(1)));
                } catch (IllegalArgumentException ignored) {
                }
            }
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.error("selectMembershipUuidsMissingName failed", e);
        }
        return out;
    }

    public void backfillPlayerNamesAsync(Map<UUID, String> resolved) {
        if (!isAvailable() || resolved == null || resolved.isEmpty()) return;
        Map<UUID, String> copy = new HashMap<>(resolved);
        try {
            CompletableFuture.runAsync(() -> {
                int n = 0;
                for (Map.Entry<UUID, String> e : copy.entrySet()) {
                    if (e.getValue() == null || e.getValue().isBlank()) continue;
                    upsertPlayerName(e.getKey(), e.getValue());
                    n++;
                }
                FTBQuestsSync.LOGGER.info("Player-name backfill complete: {} name(s) written", n);
            }, dbExecutor);
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.warn("DB queue full; player-name backfill skipped", e);
        }
    }

    public Optional<UUID> selectPlayerUuidByName(String playerName) {
        if (!isAvailable() || playerName == null || playerName.isBlank()) return Optional.empty();
        try (Connection conn = connectionProvider.getConnection();
             PreparedStatement ps = conn.prepareStatement(SQL_SELECT_PLAYER_UUID_BY_NAME)) {
            ps.setString(1, playerName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(UUID.fromString(rs.getString(1))) : Optional.empty();
            }
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.error("selectPlayerUuidByName failed name={}", playerName, e);
            return Optional.empty();
        }
    }

    public Optional<String> selectPlayerNameByUuid(UUID playerUuid) {
        if (!isAvailable()) return Optional.empty();
        try (Connection conn = connectionProvider.getConnection();
             PreparedStatement ps = conn.prepareStatement(SQL_SELECT_PLAYER_NAME_BY_UUID)) {
            ps.setString(1, playerUuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.ofNullable(rs.getString(1)) : Optional.empty();
            }
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.error("selectPlayerNameByUuid failed for player={}", playerUuid, e);
            return Optional.empty();
        }
    }

    public Map<UUID, String> selectPlayerNames(Collection<UUID> playerUuids) {
        Map<UUID, String> result = new HashMap<>();
        if (!isAvailable() || playerUuids == null || playerUuids.isEmpty()) return result;
        List<UUID> uuids = new ArrayList<>();
        for (UUID uuid : playerUuids) {
            if (uuid != null) uuids.add(uuid);
        }
        if (uuids.isEmpty()) return result;

        StringBuilder sql = new StringBuilder(
                "SELECT player_uuid, player_name FROM ftbquests_player_names WHERE player_uuid IN (");
        for (int i = 0; i < uuids.size(); i++) {
            if (i > 0) sql.append(',');
            sql.append('?');
        }
        sql.append(')');

        try (Connection conn = connectionProvider.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < uuids.size(); i++) {
                ps.setString(i + 1, uuids.get(i).toString());
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    try {
                        UUID uuid = UUID.fromString(rs.getString(1));
                        String name = rs.getString(2);
                        if (name != null && !name.isBlank()) {
                            result.put(uuid, name);
                        }
                    } catch (IllegalArgumentException ignored) {
                    }
                }
            }
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.error("selectPlayerNames failed for {} uuid(s)", uuids.size(), e);
        }
        return result;
    }

    public CompletableFuture<Map<UUID, String>> selectPlayerNamesAsync(Collection<UUID> playerUuids) {
        if (!isAvailable()) return CompletableFuture.completedFuture(Map.of());
        try {
            return CompletableFuture.supplyAsync(() -> selectPlayerNames(playerUuids), dbExecutor);
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.warn("DB queue full; cannot select player names for {} uuid(s)",
                    playerUuids == null ? 0 : playerUuids.size(), e);
            return CompletableFuture.completedFuture(Map.of());
        }
    }

    public java.util.Optional<TeamMembershipRow> selectMembership(UUID playerUuid) {
        if (!isAvailable()) return java.util.Optional.empty();
        try (Connection conn = connectionProvider.getConnection();
             PreparedStatement ps = conn.prepareStatement(SQL_SELECT_MEMBERSHIP)) {
            ps.setString(1, playerUuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return java.util.Optional.empty();
                return java.util.Optional.of(new TeamMembershipRow(
                        UUID.fromString(rs.getString(1)),
                        rs.getString(2)));
            }
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.error("selectMembership failed for player={}", playerUuid, e);
            return java.util.Optional.empty();
        }
    }

    public CompletableFuture<TeamMaterializationRow> loadTeamMaterializationAsync(UUID playerUuid) {
        if (!isAvailable()) return CompletableFuture.completedFuture(null);
        try {
            return CompletableFuture.supplyAsync(() -> {
                TeamMembershipRow membership = selectMembership(playerUuid).orElse(null);
                if (membership == null) return null;
                UUID teamId = membership.teamId();
                TeamInfoRow info = selectTeamInfo(teamId).orElse(null);
                java.util.List<TeamMemberRow> members = selectTeamMembers(teamId);
                java.util.List<TeamInviteRow> invites = selectTeamInvites(teamId);
                return new TeamMaterializationRow(membership, info, members, invites);
            }, dbExecutor);
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.warn("DB queue full; cannot load team materialization player={}", playerUuid, e);
            return CompletableFuture.completedFuture(null);
        }
    }

    public java.util.Optional<TeamInfoRow> selectTeamInfo(UUID teamId) {
        if (!isAvailable()) return java.util.Optional.empty();
        try (Connection conn = connectionProvider.getConnection();
             PreparedStatement ps = conn.prepareStatement(SQL_SELECT_TEAM_INFO)) {
            ps.setString(1, teamId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return java.util.Optional.empty();
                return java.util.Optional.of(new TeamInfoRow(
                        rs.getString(1),
                        rs.getString(2),
                        rs.getString(3) == null ? null : UUID.fromString(rs.getString(3)),
                        rs.getInt(4) == 1,
                        rs.getString(5)));
            }
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.error("selectTeamInfo failed for team={}", teamId, e);
            return java.util.Optional.empty();
        }
    }

    public CompletableFuture<java.util.Optional<TeamInfoRow>> selectTeamInfoAsync(UUID teamId) {
        if (!isAvailable()) return CompletableFuture.completedFuture(java.util.Optional.empty());
        try {
            return CompletableFuture.supplyAsync(() -> selectTeamInfo(teamId), dbExecutor);
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.warn("DB queue full; cannot select team info {}", teamId, e);
            return CompletableFuture.completedFuture(java.util.Optional.empty());
        }
    }

    public CompletableFuture<TeamStateRow> loadTeamStateAsync(UUID teamId) {
        if (!isAvailable()) return CompletableFuture.completedFuture(null);
        try {
            return CompletableFuture.supplyAsync(() -> new TeamStateRow(
                    selectTeamInfo(teamId).orElse(null),
                    selectTeamMembers(teamId),
                    selectTeamInvites(teamId)), dbExecutor);
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.warn("DB queue full; cannot load team state {}", teamId, e);
            return CompletableFuture.completedFuture(null);
        }
    }

    public boolean updateTeamOwner(UUID teamId, UUID newOwner) {
        if (!isAvailable()) return false;
        String serverId = RedisSync.getInstance().getServerId();
        try (Connection conn = connectionProvider.getConnection()) {
            boolean previousAutoCommit = conn.getAutoCommit();
            try {
                conn.setAutoCommit(false);

                int ownerRows;
                try (PreparedStatement ps = conn.prepareStatement(SQL_UPDATE_TEAM_OWNER)) {
                    ps.setString(1, newOwner.toString());
                    ps.setString(2, serverId);
                    ps.setString(3, teamId.toString());
                    ownerRows = ps.executeUpdate();
                }

                if (ownerRows <= 0) {
                    conn.rollback();
                    FTBQuestsSync.LOGGER.warn("Team owner update skipped: team={} owner={} rows=0", teamId, newOwner);
                    return false;
                }

                int demotedRows;
                try (PreparedStatement ps = conn.prepareStatement(SQL_DEMOTE_OTHER_OWNERS)) {
                    ps.setString(1, serverId);
                    ps.setString(2, teamId.toString());
                    ps.setString(3, newOwner.toString());
                    demotedRows = ps.executeUpdate();
                }

                int membershipRows;
                try (PreparedStatement ps = conn.prepareStatement(SQL_UPSERT_MEMBERSHIP)) {
                    ps.setString(1, newOwner.toString());
                    ps.setString(2, teamId.toString());
                    ps.setString(3, "OWNER");
                    ps.setString(4, serverId);
                    membershipRows = ps.executeUpdate();
                }

                conn.commit();
                FTBQuestsSync.LOGGER.info(
                        "Team owner update: team={} owner={} rows={} demoted={} membershipRows={}",
                        teamId, newOwner, ownerRows, demotedRows, membershipRows);
                return true;
            } catch (Exception e) {
                try {
                    conn.rollback();
                } catch (Exception rollbackError) {
                    FTBQuestsSync.LOGGER.warn("updateTeamOwner rollback failed team={} owner={}", teamId, newOwner, rollbackError);
                }
                throw e;
            } finally {
                try {
                    conn.setAutoCommit(previousAutoCommit);
                } catch (Exception restoreError) {
                    FTBQuestsSync.LOGGER.warn("updateTeamOwner autocommit restore failed team={} owner={}",
                            teamId, newOwner, restoreError);
                }
            }
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.error("updateTeamOwner failed team={} owner={}", teamId, newOwner, e);
            return false;
        }
    }


    public void upsertRankProgress(UUID playerUuid, long questId, long taskId, long progress, long completedAtMs) {
        if (!isAvailable()) return;
        try (Connection conn = connectionProvider.getConnection();
             PreparedStatement ps = conn.prepareStatement(SQL_UPSERT_RANK_PROGRESS)) {
            ps.setString(1, playerUuid.toString());
            ps.setLong(2, questId);
            ps.setLong(3, taskId);
            ps.setLong(4, progress);
            ps.setLong(5, completedAtMs);
            ps.setString(6, RedisSync.getInstance().getServerId());
            ps.executeUpdate();
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.error("upsertRankProgress failed player={} quest={}", playerUuid, questId, e);
        }
    }

    public CompletableFuture<Void> upsertRankProgressAsync(UUID playerUuid, long questId, long taskId, long progress, long completedAtMs) {
        if (!isAvailable()) return CompletableFuture.completedFuture(null);
        try {
            return CompletableFuture.runAsync(() -> upsertRankProgress(playerUuid, questId, taskId, progress, completedAtMs), dbExecutor);
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.warn("DB queue full; cannot upsert rank progress player={} quest={}", playerUuid, questId, e);
            return CompletableFuture.completedFuture(null);
        }
    }

    public void deleteRankProgress(UUID playerUuid, long questId) {
        if (!isAvailable()) return;
        try (Connection conn = connectionProvider.getConnection();
             PreparedStatement ps = conn.prepareStatement(SQL_DELETE_RANK_PROGRESS)) {
            ps.setString(1, playerUuid.toString());
            ps.setLong(2, questId);
            ps.executeUpdate();
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.error("deleteRankProgress failed player={} quest={}", playerUuid, questId, e);
        }
    }

    public CompletableFuture<Void> deleteRankProgressAsync(UUID playerUuid, long questId) {
        if (!isAvailable()) return CompletableFuture.completedFuture(null);
        try {
            return CompletableFuture.runAsync(() -> deleteRankProgress(playerUuid, questId), dbExecutor);
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.warn("DB queue full; cannot delete rank progress player={} quest={}", playerUuid, questId, e);
            return CompletableFuture.completedFuture(null);
        }
    }

    public java.util.List<RankProgressRow> loadRankProgress(UUID playerUuid) {
        java.util.List<RankProgressRow> result = new java.util.ArrayList<>();
        if (!isAvailable()) return result;
        try (Connection conn = connectionProvider.getConnection();
             PreparedStatement ps = conn.prepareStatement(SQL_SELECT_RANK_PROGRESS)) {
            ps.setString(1, playerUuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new RankProgressRow(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4)));
                }
            }
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.error("loadRankProgress failed player={}", playerUuid, e);
        }
        return result;
    }

    public CompletableFuture<java.util.List<RankProgressRow>> loadRankProgressAsync(UUID playerUuid) {
        if (!isAvailable()) return CompletableFuture.completedFuture(java.util.List.of());
        try {
            return CompletableFuture.supplyAsync(() -> loadRankProgress(playerUuid), dbExecutor);
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.warn("DB queue full; cannot load rank progress player={}", playerUuid, e);
            return CompletableFuture.completedFuture(java.util.List.of());
        }
    }

    public CompletableFuture<ChunkWriteResult> upsertChunkClaimAsync(ChunkClaimRecord record) {
        if (!isAvailable()) return CompletableFuture.completedFuture(new ChunkWriteResult(false, null));
        try {
            return CompletableFuture.supplyAsync(() -> upsertChunkClaim(record), dbExecutor);
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.warn("DB queue full; cannot upsert chunk claim {}", record.key(), e);
            return CompletableFuture.completedFuture(new ChunkWriteResult(false, null));
        }
    }

    private ChunkWriteResult upsertChunkClaim(ChunkClaimRecord record) {
        try (Connection conn = connectionProvider.getConnection();
             PreparedStatement ps = conn.prepareStatement(SQL_UPSERT_CHUNK_CLAIM)) {
            bindChunkClaim(ps, record, System.currentTimeMillis());
            ps.executeUpdate();
            Optional<UUID> owner = loadChunkOwner(conn, record.dimension(), record.x(), record.z());
            if (owner.isPresent() && !owner.get().equals(record.teamId())) {
                FTBQuestsSync.LOGGER.warn("Chunk claim conflict: team={} chunk={} dbOwner={}",
                        record.teamId(), record.key(), owner.get());
                return new ChunkWriteResult(false, owner.get());
            }
            return new ChunkWriteResult(true, null);
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.error("upsertChunkClaim failed team={} chunk={}", record.teamId(), record.key(), e);
            return new ChunkWriteResult(false, null);
        }
    }

    public CompletableFuture<Boolean> deleteChunkClaimAsync(UUID teamId, String dimension, int x, int z) {
        if (!isAvailable()) return CompletableFuture.completedFuture(false);
        try {
            return CompletableFuture.supplyAsync(() -> deleteChunkClaim(teamId, dimension, x, z), dbExecutor);
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.warn("DB queue full; cannot delete chunk claim team={} dim={} x={} z={}",
                    teamId, dimension, x, z, e);
            return CompletableFuture.completedFuture(false);
        }
    }

    private boolean deleteChunkClaim(UUID teamId, String dimension, int x, int z) {
        try (Connection conn = connectionProvider.getConnection();
             PreparedStatement ps = conn.prepareStatement(SQL_DELETE_CHUNK_CLAIM)) {
            ps.setString(1, teamId.toString());
            ps.setString(2, dimension);
            ps.setInt(3, x);
            ps.setInt(4, z);
            ps.executeUpdate();
            return true;
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.error("deleteChunkClaim failed team={} dim={} x={} z={}", teamId, dimension, x, z, e);
            return false;
        }
    }

    public CompletableFuture<Boolean> replaceChunkClaimsForTeamsAsync(Set<UUID> teamIds, List<ChunkClaimRecord> rows) {
        if (!isAvailable()) return CompletableFuture.completedFuture(false);
        Set<UUID> teamCopy = new HashSet<>(teamIds);
        List<ChunkClaimRecord> rowCopy = new ArrayList<>(rows);
        try {
            return CompletableFuture.supplyAsync(() -> replaceChunkClaimsForTeams(teamCopy, rowCopy), dbExecutor);
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.warn("DB queue full; cannot replace chunk claims for teams={}", teamIds, e);
            return CompletableFuture.completedFuture(false);
        }
    }

    private boolean replaceChunkClaimsForTeams(Set<UUID> teamIds, List<ChunkClaimRecord> rows) {
        try (Connection conn = connectionProvider.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement del = conn.prepareStatement(SQL_DELETE_CHUNK_CLAIMS_FOR_TEAM)) {
                for (UUID teamId : teamIds) {
                    del.setString(1, teamId.toString());
                    del.addBatch();
                }
                del.executeBatch();
            }
            batchInsertChunkClaims(conn, rows, false);
            conn.commit();
            FTBQuestsSync.LOGGER.info("Chunk claims snapshot replaced: teams={} rows={}", teamIds, rows.size());
            return true;
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.error("replaceChunkClaimsForTeams failed teams={}", teamIds, e);
            return false;
        }
    }

    public CompletableFuture<List<ChunkClaimRecord>> loadChunkClaimsAsync(UUID teamId) {
        if (!isAvailable()) return CompletableFuture.completedFuture(List.of());
        try {
            return CompletableFuture.supplyAsync(() -> loadChunkClaims(teamId), dbExecutor);
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.warn("DB queue full; cannot load chunk claims team={}", teamId, e);
            return CompletableFuture.completedFuture(List.of());
        }
    }

    public List<ChunkClaimRecord> loadChunkClaims(UUID teamId) {
        List<ChunkClaimRecord> rows = new ArrayList<>();
        if (!isAvailable()) return rows;
        try (Connection conn = connectionProvider.getConnection();
             PreparedStatement ps = conn.prepareStatement(SQL_SELECT_CHUNK_CLAIMS_TEAM)) {
            ps.setString(1, teamId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) rows.add(readChunkClaim(rs));
            }
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.error("loadChunkClaims failed team={}", teamId, e);
        }
        return rows;
    }

    public CompletableFuture<List<ChunkClaimRecord>> loadChunkClaimsForDimensionAsync(String dimension) {
        if (!isAvailable()) return CompletableFuture.completedFuture(List.of());
        try {
            return CompletableFuture.supplyAsync(() -> loadChunkClaimsForDimension(dimension), dbExecutor);
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.warn("DB queue full; cannot load chunk claims dim={}", dimension, e);
            return CompletableFuture.completedFuture(List.of());
        }
    }

    private List<ChunkClaimRecord> loadChunkClaimsForDimension(String dimension) {
        List<ChunkClaimRecord> rows = new ArrayList<>();
        try (Connection conn = connectionProvider.getConnection();
             PreparedStatement ps = conn.prepareStatement(SQL_SELECT_CHUNK_CLAIMS_DIMENSION)) {
            ps.setString(1, dimension);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) rows.add(readChunkClaim(rs));
            }
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.error("loadChunkClaimsForDimension failed dim={}", dimension, e);
        }
        return rows;
    }

    public Optional<UUID> loadChunkOwner(String dimension, int x, int z) {
        if (!isAvailable()) return Optional.empty();
        try (Connection conn = connectionProvider.getConnection()) {
            return loadChunkOwner(conn, dimension, x, z);
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.error("loadChunkOwner failed dim={} x={} z={}", dimension, x, z, e);
            return Optional.empty();
        }
    }

    private Optional<UUID> loadChunkOwner(Connection conn, String dimension, int x, int z) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(SQL_SELECT_CHUNK_OWNER)) {
            ps.setString(1, dimension);
            ps.setInt(2, x);
            ps.setInt(3, z);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(UUID.fromString(rs.getString(1))) : Optional.empty();
            }
        }
    }

    public CompletableFuture<Boolean> isChunksSeededAsync() {
        if (!isAvailable()) return CompletableFuture.completedFuture(false);
        try {
            return CompletableFuture.supplyAsync(this::isChunksSeeded, dbExecutor);
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.warn("DB queue full; cannot read chunk seed marker", e);
            return CompletableFuture.completedFuture(false);
        }
    }

    private boolean isChunksSeeded() {
        try (Connection conn = connectionProvider.getConnection();
             PreparedStatement ps = conn.prepareStatement(SQL_SELECT_SYNC_META)) {
            ps.setString(1, "ftbchunks_seeded");
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && "true".equalsIgnoreCase(rs.getString(1));
            }
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.error("isChunksSeeded failed", e);
            return false;
        }
    }

    public CompletableFuture<Integer> seedChunkClaimsAsync(Collection<ChunkClaimRecord> rows) {
        if (!isAvailable()) return CompletableFuture.completedFuture(0);
        List<ChunkClaimRecord> copy = new ArrayList<>(rows);
        try {
            return CompletableFuture.supplyAsync(() -> seedChunkClaims(copy), dbExecutor);
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.warn("DB queue full; cannot seed chunk claims", e);
            return CompletableFuture.completedFuture(0);
        }
    }

    private int seedChunkClaims(List<ChunkClaimRecord> rows) {
        try (Connection conn = connectionProvider.getConnection()) {
            conn.setAutoCommit(false);
            int inserted = batchInsertChunkClaims(conn, rows, false);
            try (PreparedStatement ps = conn.prepareStatement(SQL_UPSERT_SYNC_META)) {
                ps.setString(1, "ftbchunks_seeded");
                ps.setString(2, "true");
                ps.executeUpdate();
            }
            conn.commit();
            return inserted;
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.error("seedChunkClaims failed rows={}", rows.size(), e);
            return 0;
        }
    }

    private int batchInsertChunkClaims(Connection conn, List<ChunkClaimRecord> rows, boolean upsert) throws SQLException {
        String sql = upsert ? SQL_UPSERT_CHUNK_CLAIM : SQL_INSERT_CHUNK_CLAIM_IGNORE;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            long now = System.currentTimeMillis();
            for (ChunkClaimRecord row : rows) {
                bindChunkClaim(ps, row, now);
                ps.addBatch();
            }
            int count = 0;
            for (int n : ps.executeBatch()) {
                if (n > 0 || n == Statement.SUCCESS_NO_INFO) count++;
            }
            return count;
        }
    }

    private void bindChunkClaim(PreparedStatement ps, ChunkClaimRecord row, long updatedAtMs) throws SQLException {
        ps.setString(1, row.teamId().toString());
        ps.setString(2, row.dimension());
        ps.setInt(3, row.x());
        ps.setInt(4, row.z());
        ps.setBoolean(5, row.forceLoaded());
        ps.setLong(6, row.forceLoaded() ? row.expiryMs() : 0L);
        ps.setLong(7, row.claimedAtMs());
        ps.setLong(8, updatedAtMs);
        ps.setString(9, RedisSync.getInstance().getServerId());
    }

    private ChunkClaimRecord readChunkClaim(ResultSet rs) throws SQLException {
        return new ChunkClaimRecord(
                UUID.fromString(rs.getString(1)),
                rs.getString(2),
                rs.getInt(3),
                rs.getInt(4),
                rs.getBoolean(5),
                rs.getLong(6),
                rs.getLong(7));
    }

    public record ChunkWriteResult(boolean success, UUID conflictOwner) {
    }

    public static final class RankProgressRow {
        public final long questId;
        public final long taskId;
        public final long progress;
        public final long completedAtMs;

        private RankProgressRow(long questId, long taskId, long progress, long completedAtMs) {
            this.questId = questId;
            this.taskId = taskId;
            this.progress = progress;
            this.completedAtMs = completedAtMs;
        }
    }

    public record TeamMembershipRow(UUID teamId, String rank) {
    }

    public java.util.List<TeamMemberRow> selectTeamMembers(UUID teamId) {
        java.util.List<TeamMemberRow> result = new java.util.ArrayList<>();
        if (!isAvailable()) return result;
        try (Connection conn = connectionProvider.getConnection();
             PreparedStatement ps = conn.prepareStatement(SQL_SELECT_MEMBERS_OF_TEAM)) {
            ps.setString(1, teamId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new TeamMemberRow(UUID.fromString(rs.getString(1)), rs.getString(2)));
                }
            }
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.error("selectTeamMembers failed for team={}", teamId, e);
        }
        return result;
    }

    public void upsertTeamInvite(UUID teamId, UUID invitedUuid, UUID inviterUuid) {
        if (!isAvailable()) return;
        try (Connection conn = connectionProvider.getConnection();
             PreparedStatement ps = conn.prepareStatement(SQL_UPSERT_INVITE)) {
            ps.setString(1, teamId.toString());
            ps.setString(2, invitedUuid.toString());
            ps.setString(3, inviterUuid == null ? null : inviterUuid.toString());
            ps.setString(4, RedisSync.getInstance().getServerId());
            int rows = ps.executeUpdate();
            FTBQuestsSync.LOGGER.info("Team invite upsert: team={} invited={} inviter={} rows={}",
                    teamId, invitedUuid, inviterUuid, rows);
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.error("upsertTeamInvite failed team={} invited={}", teamId, invitedUuid, e);
        }
    }

    public void upsertTeamInviteAsync(UUID teamId, UUID invitedUuid, UUID inviterUuid) {
        if (!isAvailable()) return;
        try {
            CompletableFuture.runAsync(() -> upsertTeamInvite(teamId, invitedUuid, inviterUuid), dbExecutor);
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.warn("DB queue full; cannot upsert team invite team={} invited={}", teamId, invitedUuid, e);
        }
    }

    public void deleteTeamInvite(UUID teamId, UUID invitedUuid) {
        if (!isAvailable()) return;
        try (Connection conn = connectionProvider.getConnection();
             PreparedStatement ps = conn.prepareStatement(SQL_DELETE_INVITE)) {
            ps.setString(1, teamId.toString());
            ps.setString(2, invitedUuid.toString());
            int rows = ps.executeUpdate();
            FTBQuestsSync.LOGGER.info("Team invite deleted: team={} invited={} rows={}", teamId, invitedUuid, rows);
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.error("deleteTeamInvite failed team={} invited={}", teamId, invitedUuid, e);
        }
    }

    public void deleteTeamInviteAsync(UUID teamId, UUID invitedUuid) {
        if (!isAvailable()) return;
        try {
            CompletableFuture.runAsync(() -> deleteTeamInvite(teamId, invitedUuid), dbExecutor);
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.warn("DB queue full; cannot delete team invite team={} invited={}", teamId, invitedUuid, e);
        }
    }

    public void deleteTeamInvitesForTeam(UUID teamId) {
        if (!isAvailable()) return;
        try (Connection conn = connectionProvider.getConnection();
             PreparedStatement ps = conn.prepareStatement(SQL_DELETE_INVITES_FOR_TEAM)) {
            ps.setString(1, teamId.toString());
            int rows = ps.executeUpdate();
            FTBQuestsSync.LOGGER.info("Team invites deleted for team: team={} rows={}", teamId, rows);
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.error("deleteTeamInvitesForTeam failed team={}", teamId, e);
        }
    }

    public void deleteTeamInvitesForTeamAsync(UUID teamId) {
        if (!isAvailable()) return;
        try {
            CompletableFuture.runAsync(() -> deleteTeamInvitesForTeam(teamId), dbExecutor);
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.warn("DB queue full; cannot delete team invites for team={}", teamId, e);
        }
    }

    public java.util.Optional<TeamInviteRow> selectTeamInvite(UUID teamId, UUID invitedUuid) {
        if (!isAvailable()) return java.util.Optional.empty();
        try (Connection conn = connectionProvider.getConnection();
             PreparedStatement ps = conn.prepareStatement(SQL_SELECT_INVITE)) {
            ps.setString(1, teamId.toString());
            ps.setString(2, invitedUuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return java.util.Optional.empty();
                return java.util.Optional.of(new TeamInviteRow(
                        UUID.fromString(rs.getString(1)),
                        UUID.fromString(rs.getString(2)),
                        rs.getString(3) == null ? null : UUID.fromString(rs.getString(3))));
            }
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.error("selectTeamInvite failed team={} invited={}", teamId, invitedUuid, e);
            return java.util.Optional.empty();
        }
    }

    public java.util.List<TeamInviteRow> selectTeamInvites(UUID teamId) {
        java.util.List<TeamInviteRow> result = new java.util.ArrayList<>();
        if (!isAvailable()) return result;
        try (Connection conn = connectionProvider.getConnection();
             PreparedStatement ps = conn.prepareStatement(SQL_SELECT_INVITES_FOR_TEAM)) {
            ps.setString(1, teamId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new TeamInviteRow(
                            UUID.fromString(rs.getString(1)),
                            UUID.fromString(rs.getString(2)),
                            rs.getString(3) == null ? null : UUID.fromString(rs.getString(3))));
                }
            }
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.error("selectTeamInvites failed for team={}", teamId, e);
        }
        return result;
    }

    public java.util.List<TeamInviteRow> selectInvitesForPlayer(UUID playerUuid) {
        java.util.List<TeamInviteRow> result = new java.util.ArrayList<>();
        if (!isAvailable()) return result;
        try (Connection conn = connectionProvider.getConnection();
             PreparedStatement ps = conn.prepareStatement(SQL_SELECT_INVITES_FOR_PLAYER)) {
            ps.setString(1, playerUuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new TeamInviteRow(
                            UUID.fromString(rs.getString(1)),
                            UUID.fromString(rs.getString(2)),
                            rs.getString(3) == null ? null : UUID.fromString(rs.getString(3))));
                }
            }
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.error("selectInvitesForPlayer failed for player={}", playerUuid, e);
        }
        return result;
    }

    public CompletableFuture<java.util.List<TeamInviteRow>> selectInvitesForPlayerAsync(UUID playerUuid) {
        if (!isAvailable()) return CompletableFuture.completedFuture(java.util.List.of());
        try {
            return CompletableFuture.supplyAsync(() -> selectInvitesForPlayer(playerUuid), dbExecutor);
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.warn("DB queue full; cannot select invites for player={}", playerUuid, e);
            return CompletableFuture.completedFuture(java.util.List.of());
        }
    }

    public CompletableFuture<Void> acceptMembershipFuture(UUID playerUuid, UUID teamId, String rank) {
        if (!isAvailable()) return CompletableFuture.failedFuture(new IllegalStateException("MySQL unavailable"));
        try {
            return CompletableFuture.runAsync(() -> {
                try (Connection conn = connectionProvider.getConnection()) {
                    boolean previousAutoCommit = conn.getAutoCommit();
                    try {
                        conn.setAutoCommit(false);
                        try (PreparedStatement ps = conn.prepareStatement(SQL_UPSERT_MEMBERSHIP)) {
                            ps.setString(1, playerUuid.toString());
                            ps.setString(2, teamId.toString());
                            ps.setString(3, rank);
                            ps.setString(4, RedisSync.getInstance().getServerId());
                            ps.executeUpdate();
                        }
                        try (PreparedStatement ps = conn.prepareStatement(SQL_DELETE_INVITE)) {
                            ps.setString(1, teamId.toString());
                            ps.setString(2, playerUuid.toString());
                            ps.executeUpdate();
                        }
                        conn.commit();
                        FTBQuestsSync.LOGGER.info("Membership accepted atomically: player={} team={} rank={}",
                                playerUuid, teamId, rank);
                    } catch (Exception e) {
                        try {
                            conn.rollback();
                        } catch (Exception rollbackError) {
                            FTBQuestsSync.LOGGER.warn("acceptMembership rollback failed player={} team={}",
                                    playerUuid, teamId, rollbackError);
                        }
                        throw e;
                    } finally {
                        try {
                            conn.setAutoCommit(previousAutoCommit);
                        } catch (Exception restoreError) {
                            FTBQuestsSync.LOGGER.warn("acceptMembership autocommit restore failed player={} team={}",
                                    playerUuid, teamId, restoreError);
                        }
                    }
                } catch (Exception e) {
                    throw new CompletionException(e);
                }
            }, dbExecutor);
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.warn("DB queue full; cannot accept membership player={} team={}", playerUuid, teamId, e);
            return CompletableFuture.failedFuture(e);
        }
    }

    public record TeamMemberRow(UUID playerUuid, String rank) {
    }

    public record TeamInfoRow(String type, String name, UUID owner, boolean deleted, String color) {
    }

    public record TeamInviteRow(UUID teamId, UUID invitedUuid, UUID inviterUuid) {
    }

    public record TeamMaterializationRow(TeamMembershipRow membership, TeamInfoRow info, java.util.List<TeamMemberRow> members, java.util.List<TeamInviteRow> invites) {
    }

    public record TeamStateRow(TeamInfoRow info, java.util.List<TeamMemberRow> members, java.util.List<TeamInviteRow> invites) {
    }

    public void shutdown() {
        initialized = false;
        try {
            dbExecutor.shutdown();
            dbExecutor.awaitTermination(5L, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        if (connectionProvider != null) {
            connectionProvider.close();
        }
    }
}
