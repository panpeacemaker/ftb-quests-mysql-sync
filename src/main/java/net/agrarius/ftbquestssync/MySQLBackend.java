package net.agrarius.ftbquestssync;

import net.minecraft.nbt.CompoundTag;

import net.agrarius.ftbquestssync.config.Config;
import net.agrarius.ftbquestssync.nbt.NbtCompat;
import net.agrarius.ftbquestssync.persistence.ConnectionProvider;
import net.agrarius.ftbquestssync.persistence.SchemaManager;
import net.agrarius.ftbquestssync.quests.TeamLoadStateRegistry;
import net.agrarius.ftbquestssync.quests.rank.RankSoloProgress;
import net.agrarius.ftbquestssync.quests.rank.repository.RankProgressRepository;
import net.agrarius.ftbquestssync.quests.reward.repository.RewardClaimRepository;
import net.agrarius.ftbquestssync.chunks.ChunkClaimRecord;
import net.agrarius.ftbquestssync.chunks.ChunkClaimRepository;
import net.agrarius.ftbquestssync.teams.model.TeamInfoRow;
import net.agrarius.ftbquestssync.teams.model.TeamInviteRow;
import net.agrarius.ftbquestssync.teams.model.TeamMaterializationRow;
import net.agrarius.ftbquestssync.teams.model.TeamMemberRow;
import net.agrarius.ftbquestssync.teams.model.TeamMembershipRow;
import net.agrarius.ftbquestssync.teams.model.TeamStateRow;
import net.agrarius.ftbquestssync.teams.repository.InviteRepository;
import net.agrarius.ftbquestssync.teams.repository.MembershipRepository;
import net.agrarius.ftbquestssync.teams.repository.PlayerNameRepository;
import net.agrarius.ftbquestssync.teams.repository.TeamInfoRepository;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
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

    private static final String SQL_CLONE_TEAM_SCOPED_CLAIMS =
            "INSERT IGNORE INTO ftbquests_reward_claim_scopes "
            + "(scope_type, scope_uuid, reward_id, cycle, state, team_id, claimed_at_ms, granted_by_server) "
            + "SELECT scope_type, ?, reward_id, cycle, state, team_id, claimed_at_ms, granted_by_server "
            + "FROM ftbquests_reward_claim_scopes WHERE scope_type='TEAM' AND scope_uuid=?";

    private static final String SQL_UPSERT_MEMBERSHIP =
            "INSERT INTO ftbquests_team_membership (player_uuid, team_id, rank, updated_by_server) "
            + "VALUES (?, ?, ?, ?) "
            + "ON DUPLICATE KEY UPDATE team_id=VALUES(team_id), rank=VALUES(rank), "
            + "updated_by_server=VALUES(updated_by_server)";

    private static final String SQL_SELECT_MIGRATED_TEAM_IDS =
            "SELECT team_id FROM ftbquests_teamdata WHERE server_id = ?";

    private static final String SQL_SELECT_SERVER_ID =
            "SELECT server_id FROM ftbquests_teamdata WHERE team_id = ?";

    private ConnectionProvider connectionProvider;
    private RewardClaimRepository rewardClaimRepository;
    private RankProgressRepository rankProgressRepository;
    private ChunkClaimRepository chunkClaimRepository;
    private TeamInfoRepository teamInfoRepository;
    private MembershipRepository membershipRepository;
    private InviteRepository inviteRepository;
    private PlayerNameRepository playerNameRepository;
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
                        + "Set /opt/agrarius/config/ftbquestssync-common.toml password "
                        + "or -Dftbquestssync.mysql.password");
                initialized = false;
                return;
            }

            connectionProvider = new ConnectionProvider();
            connectionProvider.open();
            rewardClaimRepository = new RewardClaimRepository(connectionProvider);
            rankProgressRepository = new RankProgressRepository(connectionProvider);
            chunkClaimRepository = new ChunkClaimRepository(connectionProvider);
            teamInfoRepository = new TeamInfoRepository(connectionProvider);
            membershipRepository = new MembershipRepository(connectionProvider);
            inviteRepository = new InviteRepository(connectionProvider);
            playerNameRepository = new PlayerNameRepository(connectionProvider);
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

    public static class SaveResult {
        public final UUID teamId;
        public final long revision;
        public final String hashHex;

        SaveResult(UUID teamId, long revision, String hashHex) {
            this.teamId = teamId;
            this.revision = revision;
            this.hashHex = hashHex;
        }
    }

    /**
     * Result from a migration write that distinguishes "skipped because a
     * live row already exists with different content" from "written" or
     * "skipped because hash unchanged".
     */
    public static final class MigrationSaveResult extends SaveResult {
        public final boolean skippedExisting;

        MigrationSaveResult(UUID teamId, long revision, String hashHex, boolean skippedExisting) {
            super(teamId, revision, hashHex);
            this.skippedExisting = skippedExisting;
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

        public ScopedClaimResult(boolean firstClaim, boolean cycleComplete) {
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
        return rewardClaimRepository.tryClaimRewardScoped(teamId, rewardId, scopeType, scopeUuid, cycle, claimedAtMs, questRewardIds);
    }

    public java.util.List<UUID> selectMigratedTeamIds(String serverIdTag) {
        java.util.List<UUID> ids = new java.util.ArrayList<>();
        if (!isAvailable()) return ids;
        try (Connection conn = connectionProvider.getConnection();
             PreparedStatement ps = conn.prepareStatement(SQL_SELECT_MIGRATED_TEAM_IDS)) {
            ps.setString(1, serverIdTag);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    try {
                        ids.add(UUID.fromString(rs.getString(1)));
                    } catch (IllegalArgumentException ignored) { }
                }
            }
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.warn("selectMigratedTeamIds failed for serverIdTag={}", serverIdTag, e);
        }
        return ids;
    }

    public boolean seedClaimScope(UUID teamId, long rewardId, String scopeType, UUID scopeUuid,
                                  long cycle, long claimedAtMs, String serverIdTag) {
        if (!isAvailable()) return false;
        return rewardClaimRepository.seedClaimScope(teamId, rewardId, scopeType, scopeUuid, cycle, claimedAtMs, serverIdTag);
    }

    public CompletableFuture<Boolean> tryClaimRewardScopedAsync(UUID teamId, long rewardId, String scopeType, UUID scopeUuid, long claimedAtMs) {
        try {
            return CompletableFuture.supplyAsync(() -> rewardClaimRepository.tryClaimRewardScoped(teamId, rewardId, scopeType, scopeUuid, claimedAtMs), dbExecutor);
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.warn("DB queue full; cannot guard reward claim team={} reward={} scopeType={}", teamId, rewardId, scopeType, e);
            return CompletableFuture.completedFuture(!Config.rewardFailClosed);
        }
    }

    public CompletableFuture<ScopedClaimResult> tryClaimRewardScopedAsync(
            UUID teamId, long rewardId, String scopeType, UUID scopeUuid, long cycle, long claimedAtMs, long[] questRewardIds) {
        try {
            return CompletableFuture.supplyAsync(
                    () -> rewardClaimRepository.tryClaimRewardScoped(teamId, rewardId, scopeType, scopeUuid, cycle, claimedAtMs, questRewardIds),
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
        return saveTeamDataInternal(teamId, tag, RedisSync.getInstance().getServerId());
    }

    /**
     * Migration entry point: writes a team-data row with a caller-
     * supplied {@code server_id} so imported rows can be distinguished
     * from live sync writes (default tag is {@code "migrator"}; see
     * {@code Config.migrationServerIdTag}).
     *
     * <p>When {@code overwriteExisting} is {@code false} (the default) and a
     * row already exists for {@code teamId} with a <em>different</em> hash,
     * the write is skipped and a {@link MigrationSaveResult} with
     * {@code skippedExisting=true} is returned so the migrator can count it.
     */
    public SaveResult saveTeamDataMigration(UUID teamId, CompoundTag tag, String serverIdTag, boolean overwriteExisting) {
        if (!isAvailable()) return null;

        try (Connection conn = connectionProvider.getConnection()) {
            CompoundTag sanitized = tag.copy();
            RankSoloProgress.stripRankSharedProgress(sanitized);
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            NbtCompat.writeCompressed(sanitized, buf);
            byte[] bytes = buf.toByteArray();
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes);
            String hashHex = HexFormat.of().formatHex(hash);

            SaveResult existing = loadMeta(conn, teamId, hash);
            if (shouldSkipMigrationWrite(existing, hash, overwriteExisting)) {
                if (existing.hashHex != null && java.util.Arrays.equals(
                        HexFormat.of().parseHex(existing.hashHex), hash)) {
                    FTBQuestsSync.LOGGER.info(
                            "Skipped FTB team data write to MySQL (content unchanged): team={} revision={} hash={} serverId={}",
                            teamId, existing.revision, existing.hashHex, serverIdTag);
                    return existing;
                }
                String existingServerId = loadExistingServerId(conn, teamId);
                FTBQuestsSync.LOGGER.warn(
                        "Migration skipped existing team row with different content: teamId={} existingRevision={} existingServerId={}",
                        teamId, existing.revision, existingServerId);
                return new MigrationSaveResult(teamId, existing.revision, existing.hashHex, true);
            }

            try (PreparedStatement ps = conn.prepareStatement(SQL_UPSERT)) {
                ps.setString(1, teamId.toString());
                ps.setBytes(2, bytes);
                ps.setBytes(3, hash);
                ps.setString(4, serverIdTag);
                int rows = ps.executeUpdate();

                SaveResult meta = loadMeta(conn, teamId, hash);
                FTBQuestsSync.LOGGER.info(
                        "Saved FTB team data to MySQL: team={} bytes={} rows={} revision={} hash={} serverId={}",
                        teamId, bytes.length, rows, meta.revision, meta.hashHex, serverIdTag);
                return meta;
            }
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.error("MySQL save failed for team {}", teamId, e);
            return null;
        }
    }

    private String loadExistingServerId(Connection conn, UUID teamId) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(SQL_SELECT_SERVER_ID)) {
            ps.setString(1, teamId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getString(1);
                }
            }
        }
        return "unknown";
    }

    /**
     * Pure skip-decision logic extracted for unit testing.
     *
     * @return true when the migration write should be skipped:
     *         - row exists (revision &gt; 0) AND hash is identical (idempotent), OR
     *         - row exists AND hash differs AND overwriteExisting is false
     */
    static boolean shouldSkipMigrationWrite(SaveResult existing, byte[] newHash, boolean overwriteExisting) {
        if (existing == null || existing.revision <= 0) {
            return false;
        }
        boolean sameHash = existing.hashHex != null && java.util.Arrays.equals(
                HexFormat.of().parseHex(existing.hashHex), newHash);
        if (sameHash) {
            return true;
        }
        return !overwriteExisting;
    }

    private SaveResult saveTeamDataInternal(UUID teamId, CompoundTag tag, String serverId) {
        if (!isAvailable()) return null;

        try (Connection conn = connectionProvider.getConnection()) {
            CompoundTag sanitized = tag.copy();
            RankSoloProgress.stripRankSharedProgress(sanitized);
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            NbtCompat.writeCompressed(sanitized, buf);
            byte[] bytes = buf.toByteArray();
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes);

            // Idempotency check: if the existing row's data_hash matches the
            // payload we are about to write, skip the upsert entirely. This
            // keeps a re-run, a second peer, or a marker reset from bumping
            // the revision counter on identical content (which would look
            // like a live data change to other peers and trigger an
            // unnecessary Redis reload).
            SaveResult existing = loadMeta(conn, teamId, hash);
            if (existing.revision > 0 && java.util.Arrays.equals(existing.hashHex == null ? null
                    : HexFormat.of().parseHex(existing.hashHex), hash)) {
                FTBQuestsSync.LOGGER.info(
                        "Skipped FTB team data write to MySQL (content unchanged): team={} revision={} hash={} serverId={}",
                        teamId, existing.revision, existing.hashHex, serverId);
                return existing;
            }

            try (PreparedStatement ps = conn.prepareStatement(SQL_UPSERT)) {
                ps.setString(1, teamId.toString());
                ps.setBytes(2, bytes);
                ps.setBytes(3, hash);
                ps.setString(4, serverId);
                int rows = ps.executeUpdate();

                SaveResult meta = loadMeta(conn, teamId, hash);
                FTBQuestsSync.LOGGER.info(
                        "Saved FTB team data to MySQL: team={} bytes={} rows={} revision={} hash={} serverId={}",
                        teamId, bytes.length, rows, meta.revision, meta.hashHex, serverId);
                return meta;
            }
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
        rewardClaimRepository.deleteRewardClaim(teamId, rewardId, claimUuid);
    }

    public void deleteRewardClaimScopedAsync(String scopeType, UUID scopeUuid, long rewardId) {
        if (!isAvailable()) return;
        try {
            CompletableFuture.runAsync(() -> rewardClaimRepository.deleteRewardClaimScoped(scopeType, scopeUuid, rewardId), dbExecutor);
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.warn("DB queue full; cannot delete scoped reward claim scopeType={} scopeUuid={} reward={}",
                    scopeType, scopeUuid, rewardId, e);
        }
    }

    public void deleteAllClaimsForReward(UUID teamId, long rewardId) {
        if (!isAvailable()) return;
        rewardClaimRepository.deleteAllClaimsForReward(teamId, rewardId);
    }

    public void deleteAllClaimsForRewardAsync(UUID teamId, long rewardId) {
        if (!isAvailable()) return;
        try {
            CompletableFuture.runAsync(() -> {
                rewardClaimRepository.deleteAllClaimsForReward(teamId, rewardId);
                rewardClaimRepository.deleteAllScopedClaimsForReward(teamId, rewardId);
            }, dbExecutor);
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.warn("DB queue full; cannot delete all reward claims team={} reward={}", teamId, rewardId, e);
        }
    }

    public void upsertTeamInfo(UUID teamId, String type, String name, UUID owner, String color) {
        if (!isAvailable()) return;
        try {
            teamInfoRepository.upsertTeamInfo(teamId, type, name, owner, color);
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.error("upsertTeamInfo failed for {}", teamId, e);
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
            teamInfoRepository.upsertTeamInfoNoOwner(teamId, type, name, owner, color);
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.error("upsertTeamInfoNoOwner failed for {}", teamId, e);
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
            return CompletableFuture.runAsync(() -> teamInfoRepository.upsertTeamInfoNoOwner(teamId, type, name, owner, color), dbExecutor);
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.warn("DB queue full; cannot upsert team info {}", teamId, e);
            return CompletableFuture.failedFuture(e);
        }
    }

    public CompletableFuture<Void> upsertTeamInfoFuture(UUID teamId, String type, String name, UUID owner, String color) {
        if (!isAvailable()) return CompletableFuture.failedFuture(new IllegalStateException("MySQL unavailable"));
        try {
            return CompletableFuture.runAsync(() -> teamInfoRepository.upsertTeamInfo(teamId, type, name, owner, color), dbExecutor);
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.warn("DB queue full; cannot upsert team info {}", teamId, e);
            return CompletableFuture.failedFuture(e);
        }
    }

    public void markTeamDeleted(UUID teamId) {
        if (!isAvailable()) return;
        try {
            teamInfoRepository.markTeamDeleted(teamId);
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
            membershipRepository.upsertMembership(playerUuid, teamId, rank);
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.error("upsertMembership failed for player={} team={}", playerUuid, teamId, e);
        }
    }

    public CompletableFuture<Void> upsertMembershipFuture(UUID playerUuid, UUID teamId, String rank) {
        if (!isAvailable()) return CompletableFuture.failedFuture(new IllegalStateException("MySQL unavailable"));
        try {
            return CompletableFuture.runAsync(() -> membershipRepository.upsertMembership(playerUuid, teamId, rank), dbExecutor);
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
        membershipRepository.upsertOwnPlayerMembershipIfAbsentOrSelf(playerUuid, rank);
    }

    public void upsertOwnPlayerMembershipIfAbsentOrSelfAsync(UUID playerUuid, String rank) {
        if (!isAvailable()) return;
        try {
            CompletableFuture.runAsync(() -> membershipRepository.upsertOwnPlayerMembershipIfAbsentOrSelf(playerUuid, rank), dbExecutor);
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.warn("DB queue full; cannot upsert solo membership player={}", playerUuid, e);
        }
    }

    public void upsertPlayerName(UUID playerUuid, String playerName) {
        if (!isAvailable() || playerName == null || playerName.isBlank()) return;
        playerNameRepository.upsertPlayerName(playerUuid, playerName);
    }

    public void upsertPlayerNameAsync(UUID playerUuid, String playerName) {
        if (!isAvailable()) return;
        try {
            CompletableFuture.runAsync(() -> playerNameRepository.upsertPlayerName(playerUuid, playerName), dbExecutor);
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.warn("DB queue full; cannot upsert player name player={}", playerUuid, e);
        }
    }

    public List<UUID> selectMembershipUuidsMissingName() {
        if (!isAvailable()) return List.of();
        return playerNameRepository.selectMissingNameUuids();
    }

    public void backfillPlayerNamesAsync(Map<UUID, String> resolved) {
        if (!isAvailable() || resolved == null || resolved.isEmpty()) return;
        Map<UUID, String> copy = new HashMap<>(resolved);
        try {
            CompletableFuture.runAsync(() -> {
                int n = 0;
                for (Map.Entry<UUID, String> e : copy.entrySet()) {
                    if (e.getValue() == null || e.getValue().isBlank()) continue;
                    playerNameRepository.upsertPlayerName(e.getKey(), e.getValue());
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
        return playerNameRepository.selectUuidByName(playerName);
    }

    public Optional<String> selectPlayerNameByUuid(UUID playerUuid) {
        if (!isAvailable()) return Optional.empty();
        return playerNameRepository.selectNameByUuid(playerUuid);
    }

    public Map<UUID, String> selectPlayerNames(Collection<UUID> playerUuids) {
        if (!isAvailable() || playerUuids == null || playerUuids.isEmpty()) return Map.of();
        return playerNameRepository.selectNames(playerUuids);
    }

    public CompletableFuture<Map<UUID, String>> selectPlayerNamesAsync(Collection<UUID> playerUuids) {
        if (!isAvailable()) return CompletableFuture.completedFuture(Map.of());
        try {
            return CompletableFuture.supplyAsync(() -> playerNameRepository.selectNames(playerUuids), dbExecutor);
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.warn("DB queue full; cannot select player names for {} uuid(s)",
                    playerUuids == null ? 0 : playerUuids.size(), e);
            return CompletableFuture.completedFuture(Map.of());
        }
    }

    public java.util.Optional<TeamMembershipRow> selectMembership(UUID playerUuid) {
        if (!isAvailable()) return java.util.Optional.empty();
        return membershipRepository.selectMembership(playerUuid);
    }

    public CompletableFuture<TeamMaterializationRow> loadTeamMaterializationAsync(UUID playerUuid) {
        if (!isAvailable()) return CompletableFuture.completedFuture(null);
        try {
            return CompletableFuture.supplyAsync(() -> {
                TeamMembershipRow membership = membershipRepository.selectMembership(playerUuid).orElse(null);
                if (membership == null) return null;
                UUID teamId = membership.teamId();
                TeamInfoRow info = teamInfoRepository.selectTeamInfo(teamId).orElse(null);
                java.util.List<TeamMemberRow> members = membershipRepository.selectTeamMembers(teamId);
                java.util.List<TeamInviteRow> invites = inviteRepository.selectInvitesForTeam(teamId);
                return new TeamMaterializationRow(membership, info, members, invites);
            }, dbExecutor);
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.warn("DB queue full; cannot load team materialization player={}", playerUuid, e);
            return CompletableFuture.completedFuture(null);
        }
    }

    public java.util.Optional<TeamInfoRow> selectTeamInfo(UUID teamId) {
        if (!isAvailable()) return java.util.Optional.empty();
        return teamInfoRepository.selectTeamInfo(teamId);
    }

    public CompletableFuture<java.util.Optional<TeamInfoRow>> selectTeamInfoAsync(UUID teamId) {
        if (!isAvailable()) return CompletableFuture.completedFuture(java.util.Optional.empty());
        try {
            return CompletableFuture.supplyAsync(() -> teamInfoRepository.selectTeamInfo(teamId), dbExecutor);
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.warn("DB queue full; cannot select team info {}", teamId, e);
            return CompletableFuture.completedFuture(java.util.Optional.empty());
        }
    }

    public CompletableFuture<TeamStateRow> loadTeamStateAsync(UUID teamId) {
        if (!isAvailable()) return CompletableFuture.completedFuture(null);
        try {
            return CompletableFuture.supplyAsync(() -> new TeamStateRow(
                    teamInfoRepository.selectTeamInfo(teamId).orElse(null),
                    membershipRepository.selectTeamMembers(teamId),
                    inviteRepository.selectInvitesForTeam(teamId)), dbExecutor);
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.warn("DB queue full; cannot load team state {}", teamId, e);
            return CompletableFuture.completedFuture(null);
        }
    }

    public boolean updateTeamOwner(UUID teamId, UUID newOwner) {
        if (!isAvailable()) return false;
        return teamInfoRepository.updateTeamOwner(teamId, newOwner);
    }


    public void upsertRankProgress(UUID playerUuid, long questId, long taskId, long progress, long completedAtMs) {
        if (!isAvailable()) return;
        rankProgressRepository.upsertRankProgress(playerUuid, questId, taskId, progress, completedAtMs);
    }

    public CompletableFuture<Void> upsertRankProgressAsync(UUID playerUuid, long questId, long taskId, long progress, long completedAtMs) {
        if (!isAvailable()) return CompletableFuture.completedFuture(null);
        try {
            return CompletableFuture.runAsync(() -> rankProgressRepository.upsertRankProgress(playerUuid, questId, taskId, progress, completedAtMs), dbExecutor);
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.warn("DB queue full; cannot upsert rank progress player={} quest={}", playerUuid, questId, e);
            return CompletableFuture.completedFuture(null);
        }
    }

    public void deleteRankProgress(UUID playerUuid, long questId) {
        if (!isAvailable()) return;
        rankProgressRepository.deleteRankProgress(playerUuid, questId);
    }

    public CompletableFuture<Void> deleteRankProgressAsync(UUID playerUuid, long questId) {
        if (!isAvailable()) return CompletableFuture.completedFuture(null);
        try {
            return CompletableFuture.runAsync(() -> rankProgressRepository.deleteRankProgress(playerUuid, questId), dbExecutor);
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.warn("DB queue full; cannot delete rank progress player={} quest={}", playerUuid, questId, e);
            return CompletableFuture.completedFuture(null);
        }
    }

    public java.util.List<RankProgressRow> loadRankProgress(UUID playerUuid) {
        if (!isAvailable()) return java.util.List.of();
        return rankProgressRepository.loadRankProgress(playerUuid);
    }

    public CompletableFuture<java.util.List<RankProgressRow>> loadRankProgressAsync(UUID playerUuid) {
        if (!isAvailable()) return CompletableFuture.completedFuture(java.util.List.of());
        try {
            return CompletableFuture.supplyAsync(() -> rankProgressRepository.loadRankProgress(playerUuid), dbExecutor);
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.warn("DB queue full; cannot load rank progress player={}", playerUuid, e);
            return CompletableFuture.completedFuture(java.util.List.of());
        }
    }

    public CompletableFuture<ChunkWriteResult> upsertChunkClaimAsync(ChunkClaimRecord record) {
        if (!isAvailable()) return CompletableFuture.completedFuture(new ChunkWriteResult(false, null));
        try {
            return CompletableFuture.supplyAsync(() -> chunkClaimRepository.upsertChunkClaim(record), dbExecutor);
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.warn("DB queue full; cannot upsert chunk claim {}", record.key(), e);
            return CompletableFuture.completedFuture(new ChunkWriteResult(false, null));
        }
    }

    public CompletableFuture<Boolean> deleteChunkClaimAsync(UUID teamId, String dimension, int x, int z) {
        if (!isAvailable()) return CompletableFuture.completedFuture(false);
        try {
            return CompletableFuture.supplyAsync(() -> chunkClaimRepository.deleteChunkClaim(teamId, dimension, x, z), dbExecutor);
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.warn("DB queue full; cannot delete chunk claim team={} dim={} x={} z={}",
                    teamId, dimension, x, z, e);
            return CompletableFuture.completedFuture(false);
        }
    }

    public CompletableFuture<Boolean> replaceChunkClaimsForTeamsAsync(Set<UUID> teamIds, List<ChunkClaimRecord> rows) {
        if (!isAvailable()) return CompletableFuture.completedFuture(false);
        Set<UUID> teamCopy = new HashSet<>(teamIds);
        List<ChunkClaimRecord> rowCopy = new ArrayList<>(rows);
        try {
            return CompletableFuture.supplyAsync(() -> chunkClaimRepository.replaceChunkClaimsForTeams(teamCopy, rowCopy), dbExecutor);
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.warn("DB queue full; cannot replace chunk claims for teams={}", teamIds, e);
            return CompletableFuture.completedFuture(false);
        }
    }

    public CompletableFuture<List<ChunkClaimRecord>> loadChunkClaimsAsync(UUID teamId) {
        if (!isAvailable()) return CompletableFuture.completedFuture(List.of());
        try {
            return CompletableFuture.supplyAsync(() -> chunkClaimRepository.loadChunkClaims(teamId), dbExecutor);
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.warn("DB queue full; cannot load chunk claims team={}", teamId, e);
            return CompletableFuture.completedFuture(List.of());
        }
    }

    public List<ChunkClaimRecord> loadChunkClaims(UUID teamId) {
        if (!isAvailable()) return List.of();
        return chunkClaimRepository.loadChunkClaims(teamId);
    }

    public CompletableFuture<List<ChunkClaimRecord>> loadChunkClaimsForDimensionAsync(String dimension) {
        if (!isAvailable()) return CompletableFuture.completedFuture(List.of());
        try {
            return CompletableFuture.supplyAsync(() -> chunkClaimRepository.loadChunkClaimsForDimension(dimension), dbExecutor);
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.warn("DB queue full; cannot load chunk claims dim={}", dimension, e);
            return CompletableFuture.completedFuture(List.of());
        }
    }

    public Optional<UUID> loadChunkOwner(String dimension, int x, int z) {
        if (!isAvailable()) return Optional.empty();
        return chunkClaimRepository.loadChunkOwner(dimension, x, z);
    }

    public CompletableFuture<Boolean> isChunksSeededAsync() {
        if (!isAvailable()) return CompletableFuture.completedFuture(false);
        try {
            return CompletableFuture.supplyAsync(() -> chunkClaimRepository.isChunksSeeded(), dbExecutor);
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.warn("DB queue full; cannot read chunk seed marker", e);
            return CompletableFuture.completedFuture(false);
        }
    }

    public CompletableFuture<Integer> seedChunkClaimsAsync(Collection<ChunkClaimRecord> rows) {
        if (!isAvailable()) return CompletableFuture.completedFuture(0);
        List<ChunkClaimRecord> copy = new ArrayList<>(rows);
        try {
            return CompletableFuture.supplyAsync(() -> chunkClaimRepository.seedChunkClaims(copy), dbExecutor);
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.warn("DB queue full; cannot seed chunk claims", e);
            return CompletableFuture.completedFuture(0);
        }
    }

    public record ChunkWriteResult(boolean success, UUID conflictOwner) {
    }

    public static final class RankProgressRow {
        public final long questId;
        public final long taskId;
        public final long progress;
        public final long completedAtMs;

        public RankProgressRow(long questId, long taskId, long progress, long completedAtMs) {
            this.questId = questId;
            this.taskId = taskId;
            this.progress = progress;
            this.completedAtMs = completedAtMs;
        }
    }

    public java.util.List<TeamMemberRow> selectTeamMembers(UUID teamId) {
        if (!isAvailable()) return java.util.List.of();
        return membershipRepository.selectTeamMembers(teamId);
    }

    public void upsertTeamInvite(UUID teamId, UUID invitedUuid, UUID inviterUuid) {
        if (!isAvailable()) return;
        inviteRepository.upsertInvite(teamId, invitedUuid, inviterUuid);
    }

    public void upsertTeamInviteAsync(UUID teamId, UUID invitedUuid, UUID inviterUuid) {
        if (!isAvailable()) return;
        try {
            CompletableFuture.runAsync(() -> inviteRepository.upsertInvite(teamId, invitedUuid, inviterUuid), dbExecutor);
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.warn("DB queue full; cannot upsert team invite team={} invited={}", teamId, invitedUuid, e);
        }
    }

    public void deleteTeamInvite(UUID teamId, UUID invitedUuid) {
        if (!isAvailable()) return;
        inviteRepository.deleteInvite(teamId, invitedUuid);
    }

    public void deleteTeamInviteAsync(UUID teamId, UUID invitedUuid) {
        if (!isAvailable()) return;
        try {
            CompletableFuture.runAsync(() -> inviteRepository.deleteInvite(teamId, invitedUuid), dbExecutor);
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.warn("DB queue full; cannot delete team invite team={} invited={}", teamId, invitedUuid, e);
        }
    }

    public void deleteTeamInvitesForTeam(UUID teamId) {
        if (!isAvailable()) return;
        inviteRepository.deleteInvitesForTeam(teamId);
    }

    public void deleteTeamInvitesForTeamAsync(UUID teamId) {
        if (!isAvailable()) return;
        try {
            CompletableFuture.runAsync(() -> inviteRepository.deleteInvitesForTeam(teamId), dbExecutor);
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.warn("DB queue full; cannot delete team invites for team={}", teamId, e);
        }
    }

    public java.util.Optional<TeamInviteRow> selectTeamInvite(UUID teamId, UUID invitedUuid) {
        if (!isAvailable()) return java.util.Optional.empty();
        return inviteRepository.selectInvite(teamId, invitedUuid);
    }

    public java.util.List<TeamInviteRow> selectTeamInvites(UUID teamId) {
        if (!isAvailable()) return java.util.List.of();
        return inviteRepository.selectInvitesForTeam(teamId);
    }

    public java.util.List<TeamInviteRow> selectInvitesForPlayer(UUID playerUuid) {
        if (!isAvailable()) return java.util.List.of();
        return inviteRepository.selectInvitesForPlayer(playerUuid);
    }

    public CompletableFuture<java.util.List<TeamInviteRow>> selectInvitesForPlayerAsync(UUID playerUuid) {
        if (!isAvailable()) return CompletableFuture.completedFuture(java.util.List.of());
        try {
            return CompletableFuture.supplyAsync(() -> inviteRepository.selectInvitesForPlayer(playerUuid), dbExecutor);
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.warn("DB queue full; cannot select invites for player={}", playerUuid, e);
            return CompletableFuture.completedFuture(java.util.List.of());
        }
    }

    public CompletableFuture<Void> acceptMembershipFuture(UUID playerUuid, UUID teamId, String rank) {
        if (!isAvailable()) return CompletableFuture.failedFuture(new IllegalStateException("MySQL unavailable"));
        try {
            return CompletableFuture.runAsync(() -> {
                try {
                    membershipRepository.acceptMembership(playerUuid, teamId, rank);
                } catch (Exception e) {
                    throw new CompletionException(e);
                }
            }, dbExecutor);
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.warn("DB queue full; cannot accept membership player={} team={}", playerUuid, teamId, e);
            return CompletableFuture.failedFuture(e);
        }
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
