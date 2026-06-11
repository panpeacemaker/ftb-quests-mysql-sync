package net.agrarius.ftbquestssync.teams.repository;

import net.agrarius.ftbquestssync.FTBQuestsSync;
import net.agrarius.ftbquestssync.RedisSync;
import net.agrarius.ftbquestssync.persistence.ConnectionProvider;
import net.agrarius.ftbquestssync.teams.model.TeamMemberRow;
import net.agrarius.ftbquestssync.teams.model.TeamMembershipRow;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;


public final class MembershipRepository {

    private final ConnectionProvider connectionProvider;

    public MembershipRepository(ConnectionProvider connectionProvider) {
        this.connectionProvider = connectionProvider;
    }

    private static final String SQL_UPSERT_MEMBERSHIP =
            "INSERT INTO ftbquests_team_membership (player_uuid, team_id, rank, updated_by_server) "
            + "VALUES (?, ?, ?, ?) "
            + "ON DUPLICATE KEY UPDATE team_id=VALUES(team_id), rank=VALUES(rank), "
            + "updated_by_server=VALUES(updated_by_server)";

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

    private static final String SQL_DELETE_INVITE =
            "DELETE FROM ftbquests_team_invites WHERE team_id=? AND invited_uuid=?";

    public void upsertMembership(UUID playerUuid, UUID teamId, String rank) {
        try (Connection conn = connectionProvider.getConnection();
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
            throw new RuntimeException(e);
        }
    }

    public void upsertOwnPlayerMembershipIfAbsentOrSelf(UUID playerUuid, String rank) {
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

    public Optional<TeamMembershipRow> selectMembership(UUID playerUuid) {
        try (Connection conn = connectionProvider.getConnection();
             PreparedStatement ps = conn.prepareStatement(SQL_SELECT_MEMBERSHIP)) {
            ps.setString(1, playerUuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(new TeamMembershipRow(
                        UUID.fromString(rs.getString(1)),
                        rs.getString(2)));
            }
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.error("selectMembership failed for player={}", playerUuid, e);
            return Optional.empty();
        }
    }

    public List<TeamMemberRow> selectTeamMembers(UUID teamId) {
        List<TeamMemberRow> result = new ArrayList<>();
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

    public void acceptMembership(UUID playerUuid, UUID teamId, String rank) throws Exception {
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
        }
    }
}
