package net.agrarius.ftbquestssync.quests;

import net.agrarius.ftbquestssync.Config;
import net.agrarius.ftbquestssync.FTBQuestsSync;
import net.agrarius.ftbquestssync.MySQLBackend;
import net.agrarius.ftbquestssync.RedisSync;
import net.agrarius.ftbquestssync.persistence.ConnectionProvider;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class RankProgressRepository {

    private final ConnectionProvider connectionProvider;

    public RankProgressRepository(ConnectionProvider connectionProvider) {
        this.connectionProvider = connectionProvider;
    }

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

    public void upsertRankProgress(UUID playerUuid, long questId, long taskId, long progress, long completedAtMs) {
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

    public void deleteRankProgress(UUID playerUuid, long questId) {
        try (Connection conn = connectionProvider.getConnection();
             PreparedStatement ps = conn.prepareStatement(SQL_DELETE_RANK_PROGRESS)) {
            ps.setString(1, playerUuid.toString());
            ps.setLong(2, questId);
            ps.executeUpdate();
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.error("deleteRankProgress failed player={} quest={}", playerUuid, questId, e);
        }
    }

    public List<MySQLBackend.RankProgressRow> loadRankProgress(UUID playerUuid) {
        List<MySQLBackend.RankProgressRow> result = new ArrayList<>();
        try (Connection conn = connectionProvider.getConnection();
             PreparedStatement ps = conn.prepareStatement(SQL_SELECT_RANK_PROGRESS)) {
            ps.setString(1, playerUuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new MySQLBackend.RankProgressRow(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4)));
                }
            }
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.error("loadRankProgress failed player={}", playerUuid, e);
        }
        return result;
    }
}
