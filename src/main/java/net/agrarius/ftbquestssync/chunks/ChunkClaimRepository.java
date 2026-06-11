package net.agrarius.ftbquestssync.chunks;

import net.agrarius.ftbquestssync.FTBQuestsSync;
import net.agrarius.ftbquestssync.MySQLBackend;
import net.agrarius.ftbquestssync.RedisSync;
import net.agrarius.ftbquestssync.persistence.ConnectionProvider;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class ChunkClaimRepository {

    private final ConnectionProvider connectionProvider;

    public ChunkClaimRepository(ConnectionProvider connectionProvider) {
        this.connectionProvider = connectionProvider;
    }

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

    public MySQLBackend.ChunkWriteResult upsertChunkClaim(ChunkClaimRecord record) {
        try (Connection conn = connectionProvider.getConnection();
             PreparedStatement ps = conn.prepareStatement(SQL_UPSERT_CHUNK_CLAIM)) {
            bindChunkClaim(ps, record, System.currentTimeMillis());
            ps.executeUpdate();
            Optional<UUID> owner = loadChunkOwner(conn, record.dimension(), record.x(), record.z());
            if (owner.isPresent() && !owner.get().equals(record.teamId())) {
                FTBQuestsSync.LOGGER.warn("Chunk claim conflict: team={} chunk={} dbOwner={}",
                        record.teamId(), record.key(), owner.get());
                return new MySQLBackend.ChunkWriteResult(false, owner.get());
            }
            return new MySQLBackend.ChunkWriteResult(true, null);
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.error("upsertChunkClaim failed team={} chunk={}", record.teamId(), record.key(), e);
            return new MySQLBackend.ChunkWriteResult(false, null);
        }
    }

    public boolean deleteChunkClaim(UUID teamId, String dimension, int x, int z) {
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

    public boolean replaceChunkClaimsForTeams(Set<UUID> teamIds, List<ChunkClaimRecord> rows) {
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

    public List<ChunkClaimRecord> loadChunkClaims(UUID teamId) {
        List<ChunkClaimRecord> rows = new ArrayList<>();
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

    public List<ChunkClaimRecord> loadChunkClaimsForDimension(String dimension) {
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

    public boolean isChunksSeeded() {
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

    public int seedChunkClaims(List<ChunkClaimRecord> rows) {
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
}
