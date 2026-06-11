package net.agrarius.ftbquestssync.teams.repository;

import net.agrarius.ftbquestssync.FTBQuestsSync;
import net.agrarius.ftbquestssync.RedisSync;
import net.agrarius.ftbquestssync.persistence.ConnectionProvider;
import net.agrarius.ftbquestssync.teams.model.TeamInviteRow;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class InviteRepository {

    private final ConnectionProvider connectionProvider;

    public InviteRepository(ConnectionProvider connectionProvider) {
        this.connectionProvider = connectionProvider;
    }

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

    public void upsertInvite(UUID teamId, UUID invitedUuid, UUID inviterUuid) {
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

    public void deleteInvite(UUID teamId, UUID invitedUuid) {
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

    public void deleteInvitesForTeam(UUID teamId) {
        try (Connection conn = connectionProvider.getConnection();
             PreparedStatement ps = conn.prepareStatement(SQL_DELETE_INVITES_FOR_TEAM)) {
            ps.setString(1, teamId.toString());
            int rows = ps.executeUpdate();
            FTBQuestsSync.LOGGER.info("Team invites deleted for team: team={} rows={}", teamId, rows);
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.error("deleteTeamInvitesForTeam failed team={}", teamId, e);
        }
    }

    public Optional<TeamInviteRow> selectInvite(UUID teamId, UUID invitedUuid) {
        try (Connection conn = connectionProvider.getConnection();
             PreparedStatement ps = conn.prepareStatement(SQL_SELECT_INVITE)) {
            ps.setString(1, teamId.toString());
            ps.setString(2, invitedUuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(new TeamInviteRow(
                        UUID.fromString(rs.getString(1)),
                        UUID.fromString(rs.getString(2)),
                        rs.getString(3) == null ? null : UUID.fromString(rs.getString(3))));
            }
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.error("selectTeamInvite failed team={} invited={}", teamId, invitedUuid, e);
            return Optional.empty();
        }
    }

    public List<TeamInviteRow> selectInvitesForTeam(UUID teamId) {
        List<TeamInviteRow> result = new ArrayList<>();
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

    public List<TeamInviteRow> selectInvitesForPlayer(UUID playerUuid) {
        List<TeamInviteRow> result = new ArrayList<>();
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
}
