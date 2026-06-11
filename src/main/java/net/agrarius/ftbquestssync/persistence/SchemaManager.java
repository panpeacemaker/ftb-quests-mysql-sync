package net.agrarius.ftbquestssync.persistence;

import net.agrarius.ftbquestssync.FTBQuestsSync;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

public final class SchemaManager {

    private final ConnectionProvider connectionProvider;

    public SchemaManager(ConnectionProvider connectionProvider) {
        this.connectionProvider = connectionProvider;
    }

    private static final String SQL_CREATE =
            "CREATE TABLE IF NOT EXISTS ftbquests_teamdata ("
            + "team_id CHAR(36) PRIMARY KEY,"
            + "data LONGBLOB NOT NULL,"
            + "data_hash BINARY(32) NULL,"
            + "revision BIGINT NOT NULL DEFAULT 0,"
            + "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,"
            + "server_id VARCHAR(64) NULL"
            + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4";

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

    private static final String SQL_CREATE_CLAIMS_SCOPED =
            "CREATE TABLE IF NOT EXISTS ftbquests_reward_claim_scopes ("
            + "scope_type VARCHAR(16) NOT NULL,"
            + "scope_uuid CHAR(36) NOT NULL,"
            + "reward_id BIGINT NOT NULL,"
            + "cycle BIGINT NOT NULL DEFAULT 0,"
            + "state VARCHAR(16) NOT NULL DEFAULT 'GRANTED',"
            + "team_id CHAR(36) NULL,"
            + "claimed_at_ms BIGINT NOT NULL,"
            + "granted_by_server VARCHAR(64) NOT NULL,"
            + "granted_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,"
            + "PRIMARY KEY (scope_type, scope_uuid, reward_id, cycle),"
            + "INDEX idx_reward_scope (reward_id, scope_type),"
            + "INDEX idx_claim_scope_cycle (scope_type, scope_uuid, cycle)"
            + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4";

    private static final String SQL_CLAIMS_SCOPED_PK_CYCLE =
            "SELECT COUNT(*) FROM information_schema.statistics "
            + "WHERE table_schema=DATABASE() AND table_name='ftbquests_reward_claim_scopes' "
            + "AND index_name='PRIMARY' AND column_name='cycle'";

    private static final String SQL_CLAIMS_SCOPED_CYCLE_INDEX =
            "SELECT COUNT(*) FROM information_schema.statistics "
            + "WHERE table_schema=DATABASE() AND table_name='ftbquests_reward_claim_scopes' "
            + "AND index_name='idx_claim_scope_cycle'";

    private static final String SQL_CREATE_TEAM_INFO =
            "CREATE TABLE IF NOT EXISTS ftbquests_team_info ("
            + "team_id CHAR(36) PRIMARY KEY,"
            + "team_type VARCHAR(32) NOT NULL,"
            + "team_name VARCHAR(255) NULL,"
            + "owner_uuid CHAR(36) NULL,"
            + "team_color VARCHAR(16) NULL,"
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

    private static final String SQL_CREATE_PLAYER_NAMES =
            "CREATE TABLE IF NOT EXISTS ftbquests_player_names ("
            + "player_uuid CHAR(36) PRIMARY KEY,"
            + "player_name VARCHAR(16) NOT NULL,"
            + "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,"
            + "INDEX idx_player_name (player_name)"
            + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4";

    private static final String SQL_CREATE_RANK_PROGRESS =
            "CREATE TABLE IF NOT EXISTS ftbquests_rank_progress ("
            + "player_uuid CHAR(36) NOT NULL,"
            + "quest_id BIGINT NOT NULL,"
            + "task_id BIGINT NOT NULL,"
            + "progress BIGINT NOT NULL DEFAULT 0,"
            + "completed_at_ms BIGINT NOT NULL DEFAULT 0,"
            + "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,"
            + "updated_by_server VARCHAR(64) NOT NULL DEFAULT '',"
            + "PRIMARY KEY (player_uuid, quest_id, task_id),"
            + "INDEX idx_task (task_id),"
            + "INDEX idx_quest (quest_id)"
            + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4";

    private static final String SQL_RANK_PK_COLUMNS =
            "SELECT COUNT(*) FROM information_schema.statistics "
            + "WHERE table_schema=DATABASE() AND table_name='ftbquests_rank_progress' "
            + "AND index_name='PRIMARY' AND column_name='task_id'";

    private static final String SQL_CREATE_CHUNK_CLAIMS =
            "CREATE TABLE IF NOT EXISTS ftbchunks_team_claims ("
            + "team_id CHAR(36) NOT NULL,"
            + "dimension VARCHAR(191) NOT NULL,"
            + "chunk_x INT NOT NULL,"
            + "chunk_z INT NOT NULL,"
            + "force_loaded TINYINT(1) NOT NULL DEFAULT 0,"
            + "force_load_expiry_ms BIGINT NOT NULL DEFAULT 0,"
            + "claimed_at_ms BIGINT NOT NULL DEFAULT 0,"
            + "updated_at_ms BIGINT NOT NULL,"
            + "updated_by_server VARCHAR(64) NOT NULL,"
            + "created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,"
            + "updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,"
            + "PRIMARY KEY (team_id, dimension, chunk_x, chunk_z),"
            + "UNIQUE KEY uk_ftbchunks_claim_pos (dimension, chunk_x, chunk_z),"
            + "KEY idx_ftbchunks_force_loaded (force_loaded, dimension),"
            + "KEY idx_ftbchunks_updated_ms (updated_at_ms)"
            + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4";

    private static final String SQL_CREATE_META =
            "CREATE TABLE IF NOT EXISTS ftbquestssync_meta ("
            + "meta_key VARCHAR(128) PRIMARY KEY,"
            + "meta_value VARCHAR(512) NOT NULL,"
            + "updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP"
            + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4";

    private static final String SQL_CREATE_INVITES =
            "CREATE TABLE IF NOT EXISTS ftbquests_team_invites ("
            + "team_id CHAR(36) NOT NULL, invited_uuid CHAR(36) NOT NULL, inviter_uuid CHAR(36) NULL,"
            + "created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,"
            + "updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,"
            + "updated_by_server VARCHAR(64) NOT NULL,"
            + "PRIMARY KEY (team_id, invited_uuid), KEY idx_invited_uuid (invited_uuid), KEY idx_team_id (team_id)"
            + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4";

    public void ensureSchema() throws Exception {
        try (Connection conn = connectionProvider.getConnection();
             Statement st = conn.createStatement()) {
            st.execute(SQL_CREATE);
            st.execute(SQL_CREATE_CLAIMS);
            st.execute(SQL_CREATE_CLAIMS_SCOPED);
            st.execute(SQL_CREATE_TEAM_INFO);
            ensureColumn(st, "ftbquests_team_info", "team_color", "VARCHAR(16) NULL");
            st.execute(SQL_CREATE_TEAM_MEMBERSHIP);
            st.execute(SQL_CREATE_PLAYER_NAMES);
            st.execute(SQL_CREATE_RANK_PROGRESS);
            st.execute(SQL_CREATE_CHUNK_CLAIMS);
            st.execute(SQL_CREATE_META);
            st.execute(SQL_CREATE_INVITES);
            ensureColumn(st, "ftbquests_teamdata", "data_hash", "BINARY(32) NULL");
            ensureColumn(st, "ftbquests_teamdata", "revision", "BIGINT NOT NULL DEFAULT 0");
            ensureColumn(st, "ftbquests_reward_claim_scopes", "cycle", "BIGINT NOT NULL DEFAULT 0");
            migrateRankProgressPk(conn, st);
            migrateRewardClaimScopesCyclePk(conn, st);
        }
    }

    private void migrateRewardClaimScopesCyclePk(Connection conn, Statement st) {
        try {
            boolean pkHasCycle;
            try (ResultSet rs = st.executeQuery(SQL_CLAIMS_SCOPED_PK_CYCLE)) {
                pkHasCycle = rs.next() && rs.getInt(1) > 0;
            }
            if (pkHasCycle) return;

            boolean locked;
            try (PreparedStatement lock = conn.prepareStatement("SELECT GET_LOCK(?, 30)")) {
                lock.setString(1, "ftbquests_reward_claim_scopes_cycle_pk_v1");
                try (ResultSet rs = lock.executeQuery()) {
                    locked = rs.next() && rs.getInt(1) == 1;
                }
            }
            if (!locked) {
                FTBQuestsSync.LOGGER.warn("Claim scope cycle migration: could not acquire advisory lock; skipping this run");
                return;
            }
            try {
                try (ResultSet rs = st.executeQuery(SQL_CLAIMS_SCOPED_PK_CYCLE)) {
                    if (rs.next() && rs.getInt(1) > 0) return;
                }
                boolean indexExists;
                try (ResultSet rs = st.executeQuery(SQL_CLAIMS_SCOPED_CYCLE_INDEX)) {
                    indexExists = rs.next() && rs.getInt(1) > 0;
                }
                String alter = "ALTER TABLE ftbquests_reward_claim_scopes "
                        + "DROP PRIMARY KEY, "
                        + "ADD PRIMARY KEY (scope_type, scope_uuid, reward_id, cycle)";
                if (!indexExists) {
                    alter += ", ADD INDEX idx_claim_scope_cycle (scope_type, scope_uuid, cycle)";
                }
                st.execute(alter);
                FTBQuestsSync.LOGGER.info(
                        "Claim scope cycle migration: rebuilt PRIMARY KEY to (scope_type, scope_uuid, reward_id, cycle)");
            } finally {
                try (PreparedStatement unlock = conn.prepareStatement("SELECT RELEASE_LOCK(?)")) {
                    unlock.setString(1, "ftbquests_reward_claim_scopes_cycle_pk_v1");
                    unlock.execute();
                }
            }
        } catch (java.sql.SQLException e) {
            FTBQuestsSync.LOGGER.warn("Claim scope cycle migration failed: {}", e.getMessage());
        }
    }

    private void migrateRankProgressPk(Connection conn, Statement st) {
        try {
            boolean pkHasTask;
            try (ResultSet rs = st.executeQuery(SQL_RANK_PK_COLUMNS)) {
                pkHasTask = rs.next() && rs.getInt(1) > 0;
            }
            if (pkHasTask) return;

            boolean locked;
            try (PreparedStatement lock = conn.prepareStatement("SELECT GET_LOCK(?, 30)")) {
                lock.setString(1, "ftbquests_rank_progress_pk_v2");
                try (ResultSet rs = lock.executeQuery()) {
                    locked = rs.next() && rs.getInt(1) == 1;
                }
            }
            if (!locked) {
                FTBQuestsSync.LOGGER.warn("Rank PK migration: could not acquire advisory lock; skipping this run");
                return;
            }
            try {
                try (ResultSet rs = st.executeQuery(SQL_RANK_PK_COLUMNS)) {
                    if (rs.next() && rs.getInt(1) > 0) return;
                }
                st.execute("ALTER TABLE ftbquests_rank_progress "
                        + "DROP PRIMARY KEY, "
                        + "ADD PRIMARY KEY (player_uuid, quest_id, task_id)");
                FTBQuestsSync.LOGGER.info("Rank PK migration: rebuilt PRIMARY KEY to (player_uuid, quest_id, task_id)");
            } finally {
                try (PreparedStatement unlock = conn.prepareStatement("SELECT RELEASE_LOCK(?)")) {
                    unlock.setString(1, "ftbquests_rank_progress_pk_v2");
                    unlock.execute();
                }
            }
        } catch (java.sql.SQLException e) {
            FTBQuestsSync.LOGGER.warn("Rank PK migration failed: {}", e.getMessage());
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
}
