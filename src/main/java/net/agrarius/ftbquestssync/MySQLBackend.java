package net.agrarius.ftbquestssync;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import net.minecraft.nbt.CompoundTag;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
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
    private static final String SQL_CREATE =
            "CREATE TABLE IF NOT EXISTS ftbquests_teamdata ("
            + "team_id CHAR(36) PRIMARY KEY,"
            + "data LONGBLOB NOT NULL,"
            + "data_hash BINARY(32) NULL,"
            + "revision BIGINT NOT NULL DEFAULT 0,"
            + "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,"
            + "server_id VARCHAR(64) NULL"
            + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4";

    private static final String SQL_UPSERT =
            "INSERT INTO ftbquests_teamdata (team_id, data, data_hash, revision, server_id) "
            + "VALUES (?, ?, ?, 1, ?) "
            + "ON DUPLICATE KEY UPDATE data = VALUES(data), data_hash = VALUES(data_hash), "
            + "revision = revision + 1, server_id = VALUES(server_id)";

    private static final String SQL_SELECT =
            "SELECT data, server_id, updated_at, revision, data_hash FROM ftbquests_teamdata WHERE team_id = ?";

    private static final String SQL_SELECT_META =
            "SELECT revision, data_hash FROM ftbquests_teamdata WHERE team_id = ?";

    private static final String SQL_CREATE_CLAIMS =
            "CREATE TABLE IF NOT EXISTS ftbquests_reward_claims ("
            + "team_id CHAR(36) NOT NULL,"
            + "reward_id BIGINT NOT NULL,"
            + "claim_uuid CHAR(36) NOT NULL,"
            + "claimed_at_ms BIGINT NOT NULL,"
            + "granted_by_server VARCHAR(64) NOT NULL,"
            + "granted_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,"
            + "PRIMARY KEY (team_id, reward_id, claim_uuid),"
            + "INDEX idx_team_reward (team_id, reward_id)"
            + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4";

    private static final String SQL_TRY_CLAIM =
            "INSERT IGNORE INTO ftbquests_reward_claims "
            + "(team_id, reward_id, claim_uuid, claimed_at_ms, granted_by_server) "
            + "VALUES (?, ?, ?, ?, ?)";

    private static final String SQL_CREATE_CLAIMS_SCOPED =
            "CREATE TABLE IF NOT EXISTS ftbquests_reward_claim_scopes ("
            + "scope_type VARCHAR(16) NOT NULL,"
            + "scope_uuid CHAR(36) NOT NULL,"
            + "reward_id BIGINT NOT NULL,"
            + "state VARCHAR(16) NOT NULL DEFAULT 'GRANTED',"
            + "team_id CHAR(36) NULL,"
            + "claimed_at_ms BIGINT NOT NULL,"
            + "granted_by_server VARCHAR(64) NOT NULL,"
            + "granted_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,"
            + "PRIMARY KEY (scope_type, scope_uuid, reward_id),"
            + "INDEX idx_reward_scope (reward_id, scope_type)"
            + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4";

    private static final String SQL_TRY_CLAIM_SCOPED =
            "INSERT IGNORE INTO ftbquests_reward_claim_scopes "
            + "(scope_type, scope_uuid, reward_id, state, team_id, claimed_at_ms, granted_by_server) "
            + "VALUES (?, ?, ?, 'GRANTED', ?, ?, ?)";

    private static final String SQL_DELETE_CLAIM =
            "DELETE FROM ftbquests_reward_claims WHERE team_id=? AND reward_id=? AND claim_uuid=?";

    private static final String SQL_DELETE_ALL_CLAIMS_FOR_REWARD =
            "DELETE FROM ftbquests_reward_claims WHERE team_id=? AND reward_id=?";

    private static final String SQL_DELETE_CLAIM_SCOPED =
            "DELETE FROM ftbquests_reward_claim_scopes WHERE scope_type=? AND scope_uuid=? AND reward_id=?";

    private static final String SQL_DELETE_ALL_CLAIMS_SCOPED_FOR_REWARD =
            "DELETE FROM ftbquests_reward_claim_scopes WHERE team_id=? AND reward_id=?";

    private static final String SQL_CREATE_TEAM_INFO =
            "CREATE TABLE IF NOT EXISTS ftbquests_team_info ("
            + "team_id CHAR(36) PRIMARY KEY,"
            + "team_type VARCHAR(32) NOT NULL,"
            + "team_name VARCHAR(255) NULL,"
            + "owner_uuid CHAR(36) NULL,"
            + "deleted TINYINT(1) NOT NULL DEFAULT 0,"
            + "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,"
            + "updated_by_server VARCHAR(64) NOT NULL"
            + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4";

    private static final String SQL_CREATE_TEAM_MEMBERSHIP =
            "CREATE TABLE IF NOT EXISTS ftbquests_team_membership ("
            + "player_uuid CHAR(36) PRIMARY KEY,"
            + "team_id CHAR(36) NOT NULL,"
            + "rank VARCHAR(32) NOT NULL DEFAULT 'MEMBER',"
            + "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,"
            + "updated_by_server VARCHAR(64) NOT NULL,"
            + "INDEX idx_team (team_id)"
            + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4";

    private static final String SQL_UPSERT_TEAM_INFO =
            "INSERT INTO ftbquests_team_info (team_id, team_type, team_name, owner_uuid, deleted, updated_by_server) "
            + "VALUES (?, ?, ?, ?, 0, ?) "
            + "ON DUPLICATE KEY UPDATE team_type=VALUES(team_type), team_name=VALUES(team_name), "
            + "owner_uuid=VALUES(owner_uuid), deleted=0, updated_by_server=VALUES(updated_by_server)";

    private static final String SQL_MARK_TEAM_DELETED =
            "UPDATE ftbquests_team_info SET deleted=1, updated_by_server=? WHERE team_id=?";

    private static final String SQL_UPSERT_MEMBERSHIP =
            "INSERT INTO ftbquests_team_membership (player_uuid, team_id, rank, updated_by_server) "
            + "VALUES (?, ?, ?, ?) "
            + "ON DUPLICATE KEY UPDATE team_id=VALUES(team_id), rank=VALUES(rank), "
            + "updated_by_server=VALUES(updated_by_server)";

    private static final String SQL_SELECT_MEMBERSHIP =
            "SELECT team_id, rank FROM ftbquests_team_membership WHERE player_uuid = ?";

    private static final String SQL_SELECT_MEMBERS_OF_TEAM =
            "SELECT player_uuid, rank FROM ftbquests_team_membership WHERE team_id = ?";

    private static final String SQL_SELECT_TEAM_INFO =
            "SELECT team_type, team_name, owner_uuid, deleted FROM ftbquests_team_info WHERE team_id = ?";

    private static final String SQL_CREATE_RANK_PROGRESS =
            "CREATE TABLE IF NOT EXISTS ftbquests_rank_progress ("
            + "player_uuid CHAR(36) NOT NULL,"
            + "quest_id BIGINT NOT NULL,"
            + "task_id BIGINT NOT NULL,"
            + "progress BIGINT NOT NULL DEFAULT 0,"
            + "completed_at_ms BIGINT NOT NULL DEFAULT 0,"
            + "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,"
            + "updated_by_server VARCHAR(64) NOT NULL,"
            + "PRIMARY KEY (player_uuid, quest_id),"
            + "INDEX idx_task (task_id)"
            + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4";

    private static final String SQL_UPSERT_RANK_PROGRESS =
            "INSERT INTO ftbquests_rank_progress "
            + "(player_uuid, quest_id, task_id, progress, completed_at_ms, updated_by_server) "
            + "VALUES (?, ?, ?, ?, ?, ?) "
            + "ON DUPLICATE KEY UPDATE task_id=VALUES(task_id), "
            + "progress=GREATEST(progress, VALUES(progress)), "
            + "completed_at_ms=GREATEST(completed_at_ms, VALUES(completed_at_ms)), "
            + "updated_by_server=VALUES(updated_by_server)";

    private static final String SQL_SELECT_RANK_PROGRESS =
            "SELECT quest_id, task_id, progress, completed_at_ms FROM ftbquests_rank_progress WHERE player_uuid=?";

    private static final String SQL_SELECT_RANK_COMPLETE =
            "SELECT completed_at_ms FROM ftbquests_rank_progress WHERE player_uuid=? AND quest_id=?";

    private static final String SQL_DELETE_RANK_PROGRESS =
            "DELETE FROM ftbquests_rank_progress WHERE player_uuid=? AND quest_id=?";

    private HikariDataSource dataSource;
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
        return initialized && dataSource != null && !dataSource.isClosed();
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

            HikariConfig hc = new HikariConfig();
            hc.setDriverClassName("com.mysql.cj.jdbc.Driver");
            hc.setJdbcUrl(String.format(
                    "jdbc:mysql://%s:%d/%s?useSSL=%s&requireSSL=%s&allowPublicKeyRetrieval=%s",
                    Config.mysqlHost, Config.mysqlPort, Config.mysqlDatabase,
                    Config.mysqlUseSsl, Config.mysqlUseSsl, Config.mysqlAllowPublicKeyRetrieval));
            hc.setUsername(Config.mysqlUsername);
            hc.setPassword(Config.mysqlPassword);
            hc.setMaximumPoolSize(Math.max(1, Config.mysqlMaxPool));
            hc.setMinimumIdle(Math.max(0, Config.mysqlMinIdle));
            hc.setConnectionTimeout(1_000L);
            hc.setPoolName("FTBQuestsSync-Pool");

            Class.forName("com.mysql.cj.jdbc.Driver");
            dataSource = new HikariDataSource(hc);
            ensureSchema();
            initialized = true;

            FTBQuestsSync.LOGGER.info("MySQL ready: {}:{}/{} user={} pool={}/{}",
                    Config.mysqlHost, Config.mysqlPort, Config.mysqlDatabase,
                    Config.mysqlUsername, Config.mysqlMinIdle, Config.mysqlMaxPool);
        } catch (Exception e) {
            initialized = false;
            FTBQuestsSync.LOGGER.error("MySQL init failed - quest sync disabled", e);
        }
    }

    private void ensureSchema() throws Exception {
        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement()) {
            st.execute(SQL_CREATE);
            st.execute(SQL_CREATE_CLAIMS);
            st.execute(SQL_CREATE_CLAIMS_SCOPED);
            st.execute(SQL_CREATE_TEAM_INFO);
            st.execute(SQL_CREATE_TEAM_MEMBERSHIP);
            st.execute(SQL_CREATE_RANK_PROGRESS);
            ensureColumn(st, "ftbquests_teamdata", "data_hash", "BINARY(32) NULL");
            ensureColumn(st, "ftbquests_teamdata", "revision", "BIGINT NOT NULL DEFAULT 0");
        }
    }

    private void ensureColumn(Statement st, String table, String column, String type) {
        try {
            st.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + type);
            FTBQuestsSync.LOGGER.info("Schema migration: added {}.{}", table, column);
        } catch (java.sql.SQLException e) {
            if (e.getErrorCode() == 1060) {
                return;
            }
            FTBQuestsSync.LOGGER.warn("Schema migration check failed for {}.{}: {}", table, column, e.getMessage());
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
        if (!isAvailable()) {
            FTBQuestsSync.LOGGER.warn(
                    "DB unavailable - allowing reward claim with NO cross-server guard "
                    + "(team={} reward={} scopeType={} scopeUuid={})", teamId, rewardId, scopeType, scopeUuid);
            return !Config.rewardFailClosed;
        }
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(SQL_TRY_CLAIM_SCOPED)) {

            ps.setString(1, scopeType);
            ps.setString(2, scopeUuid.toString());
            ps.setLong(3, rewardId);
            ps.setString(4, teamId.toString());
            ps.setLong(5, claimedAtMs);
            ps.setString(6, RedisSync.getInstance().getServerId());
            int rows = ps.executeUpdate();

            if (rows == 0) {
                FTBQuestsSync.LOGGER.info(
                        "Reward claim REFUSED (duplicate): team={} reward={} scopeType={} scopeUuid={}",
                        teamId, rewardId, scopeType, scopeUuid);
                return false;
            }
            FTBQuestsSync.LOGGER.info(
                    "Reward claim GRANTED (first): team={} reward={} scopeType={} scopeUuid={} server={}",
                    teamId, rewardId, scopeType, scopeUuid, RedisSync.getInstance().getServerId());
            return true;
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.error(
                    "tryClaimReward failed for team={} reward={} scopeType={} - rewardFailClosed={}",
                    teamId, rewardId, scopeType, Config.rewardFailClosed, e);
            return !Config.rewardFailClosed;
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
        try {
            return CompletableFuture.supplyAsync(() -> saveTeamData(teamId, tag), dbExecutor);
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.warn("DB queue full; vanilla file save remains fallback for team {}", teamId, e);
            return CompletableFuture.completedFuture(null);
        }
    }

    /**
     * Blocking write. Prefer {@link #saveTeamDataAsync(UUID, CompoundTag)} from
     * Minecraft-thread hooks.
     */
    public SaveResult saveTeamData(UUID teamId, CompoundTag tag) {
        if (!isAvailable()) return null;

        try (Connection conn = dataSource.getConnection();
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

        try (Connection conn = dataSource.getConnection();
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

    public void deleteRewardClaim(UUID teamId, long rewardId, UUID claimUuid) {
        if (!isAvailable()) return;
        try (Connection conn = dataSource.getConnection();
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
        try (Connection conn = dataSource.getConnection();
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
        try (Connection conn = dataSource.getConnection();
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
        try (Connection conn = dataSource.getConnection();
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

    public void upsertTeamInfo(UUID teamId, String type, String name, UUID owner) {
        if (!isAvailable()) return;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(SQL_UPSERT_TEAM_INFO)) {
            ps.setString(1, teamId.toString());
            ps.setString(2, type);
            ps.setString(3, name);
            ps.setString(4, owner == null ? null : owner.toString());
            ps.setString(5, RedisSync.getInstance().getServerId());
            int rows = ps.executeUpdate();
            FTBQuestsSync.LOGGER.info("Team info upsert: id={} type={} name={} owner={} rows={}",
                    teamId, type, name, owner, rows);
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.error("upsertTeamInfo failed for {}", teamId, e);
        }
    }

    public void upsertTeamInfoAsync(UUID teamId, String type, String name, UUID owner) {
        if (!isAvailable()) return;
        try {
            CompletableFuture.runAsync(() -> upsertTeamInfo(teamId, type, name, owner), dbExecutor);
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.warn("DB queue full; cannot upsert team info {}", teamId, e);
        }
    }

    public void markTeamDeleted(UUID teamId) {
        if (!isAvailable()) return;
        try (Connection conn = dataSource.getConnection();
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
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(SQL_UPSERT_MEMBERSHIP)) {
            ps.setString(1, playerUuid.toString());
            ps.setString(2, teamId.toString());
            ps.setString(3, rank);
            ps.setString(4, RedisSync.getInstance().getServerId());
            int rows = ps.executeUpdate();
            FTBQuestsSync.LOGGER.info("Membership upsert: player={} team={} rank={} rows={}",
                    playerUuid, teamId, rank, rows);
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.error("upsertMembership failed for player={} team={}", playerUuid, teamId, e);
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

    public java.util.Optional<TeamMembershipRow> selectMembership(UUID playerUuid) {
        if (!isAvailable()) return java.util.Optional.empty();
        try (Connection conn = dataSource.getConnection();
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
                TeamInfoRow info = selectTeamInfo(membership.teamId()).orElse(null);
                java.util.List<TeamMemberRow> members = selectTeamMembers(membership.teamId());
                return new TeamMaterializationRow(membership, info, members);
            }, dbExecutor);
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.warn("DB queue full; cannot load team materialization player={}", playerUuid, e);
            return CompletableFuture.completedFuture(null);
        }
    }

    public java.util.Optional<TeamInfoRow> selectTeamInfo(UUID teamId) {
        if (!isAvailable()) return java.util.Optional.empty();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(SQL_SELECT_TEAM_INFO)) {
            ps.setString(1, teamId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return java.util.Optional.empty();
                return java.util.Optional.of(new TeamInfoRow(
                        rs.getString(1),
                        rs.getString(2),
                        rs.getString(3) == null ? null : UUID.fromString(rs.getString(3)),
                        rs.getInt(4) == 1));
            }
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.error("selectTeamInfo failed for team={}", teamId, e);
            return java.util.Optional.empty();
        }
    }


    public void upsertRankProgress(UUID playerUuid, long questId, long taskId, long progress, long completedAtMs) {
        if (!isAvailable()) return;
        try (Connection conn = dataSource.getConnection();
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
        try (Connection conn = dataSource.getConnection();
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

    public boolean isRankComplete(UUID playerUuid, long questId) {
        if (!isAvailable()) return false;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(SQL_SELECT_RANK_COMPLETE)) {
            ps.setString(1, playerUuid.toString());
            ps.setLong(2, questId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getLong(1) > 0L;
            }
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.error("isRankComplete failed player={} quest={}", playerUuid, questId, e);
            return false;
        }
    }

    public Map<Long, RankProgressRow> loadRankProgress(UUID playerUuid) {
        Map<Long, RankProgressRow> result = new HashMap<>();
        if (!isAvailable()) return result;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(SQL_SELECT_RANK_PROGRESS)) {
            ps.setString(1, playerUuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    RankProgressRow row = new RankProgressRow(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4));
                    result.put(row.questId, row);
                }
            }
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.error("loadRankProgress failed player={}", playerUuid, e);
        }
        return result;
    }

    public CompletableFuture<Map<Long, RankProgressRow>> loadRankProgressAsync(UUID playerUuid) {
        if (!isAvailable()) return CompletableFuture.completedFuture(Map.of());
        try {
            return CompletableFuture.supplyAsync(() -> loadRankProgress(playerUuid), dbExecutor);
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.warn("DB queue full; cannot load rank progress player={}", playerUuid, e);
            return CompletableFuture.completedFuture(Map.of());
        }
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
        try (Connection conn = dataSource.getConnection();
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

    public record TeamMemberRow(UUID playerUuid, String rank) {
    }

    public record TeamInfoRow(String type, String name, UUID owner, boolean deleted) {
    }

    public record TeamMaterializationRow(TeamMembershipRow membership, TeamInfoRow info, java.util.List<TeamMemberRow> members) {
    }

    public void shutdown() {
        initialized = false;
        try {
            dbExecutor.shutdown();
            dbExecutor.awaitTermination(5L, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }
}
