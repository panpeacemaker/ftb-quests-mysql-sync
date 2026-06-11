package net.agrarius.ftbquestssync.teams.repository;

import net.agrarius.ftbquestssync.FTBQuestsSync;
import net.agrarius.ftbquestssync.RedisSync;
import net.agrarius.ftbquestssync.persistence.ConnectionProvider;
import net.agrarius.ftbquestssync.teams.model.TeamInfoRow;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Optional;
import java.util.UUID;

public final class TeamInfoRepository {

    private final ConnectionProvider connectionProvider;

    public TeamInfoRepository(ConnectionProvider connectionProvider) {
        this.connectionProvider = connectionProvider;
    }

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

    private static final String SQL_SELECT_TEAM_INFO =
            "SELECT team_type, team_name, owner_uuid, deleted, team_color FROM ftbquests_team_info WHERE team_id = ?";

    private static final String SQL_UPDATE_TEAM_OWNER =
            "UPDATE ftbquests_team_info SET owner_uuid=?, deleted=0, updated_by_server=? WHERE team_id=?";

    private static final String SQL_DEMOTE_OTHER_OWNERS =
            "UPDATE ftbquests_team_membership SET rank='OFFICER', updated_by_server=? WHERE team_id=? AND player_uuid<>? AND rank='OWNER'";

    private static final String SQL_UPSERT_MEMBERSHIP =
            "INSERT INTO ftbquests_team_membership (player_uuid, team_id, rank, updated_by_server) "
            + "VALUES (?, ?, ?, ?) "
            + "ON DUPLICATE KEY UPDATE team_id=VALUES(team_id), rank=VALUES(rank), "
            + "updated_by_server=VALUES(updated_by_server)";

    public void upsertTeamInfo(UUID teamId, String type, String name, UUID owner, String color) {
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
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.error("upsertTeamInfo failed for {}", teamId, e);
            throw new RuntimeException(e);
        }
    }

    public void upsertTeamInfoNoOwner(UUID teamId, String type, String name, UUID owner, String color) {
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
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.error("upsertTeamInfoNoOwner failed for {}", teamId, e);
            throw new RuntimeException(e);
        }
    }

    public void markTeamDeleted(UUID teamId) {
        try (Connection conn = connectionProvider.getConnection();
             PreparedStatement ps = conn.prepareStatement(SQL_MARK_TEAM_DELETED)) {
            ps.setString(1, RedisSync.getInstance().getServerId());
            ps.setString(2, teamId.toString());
            ps.executeUpdate();
            FTBQuestsSync.LOGGER.info("Team marked deleted: id={}", teamId);
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.error("markTeamDeleted failed for {}", teamId, e);
            throw new RuntimeException(e);
        }
    }

    public Optional<TeamInfoRow> selectTeamInfo(UUID teamId) {
        try (Connection conn = connectionProvider.getConnection();
             PreparedStatement ps = conn.prepareStatement(SQL_SELECT_TEAM_INFO)) {
            ps.setString(1, teamId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(new TeamInfoRow(
                        rs.getString(1),
                        rs.getString(2),
                        rs.getString(3) == null ? null : UUID.fromString(rs.getString(3)),
                        rs.getInt(4) == 1,
                        rs.getString(5)));
            }
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.error("selectTeamInfo failed for team={}", teamId, e);
            return Optional.empty();
        }
    }

    public boolean updateTeamOwner(UUID teamId, UUID newOwner) {
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
}
