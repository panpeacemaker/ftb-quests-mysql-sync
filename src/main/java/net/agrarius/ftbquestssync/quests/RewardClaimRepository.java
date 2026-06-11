package net.agrarius.ftbquestssync.quests;

import net.agrarius.ftbquestssync.Config;
import net.agrarius.ftbquestssync.FTBQuestsSync;
import net.agrarius.ftbquestssync.MySQLBackend;
import net.agrarius.ftbquestssync.RedisSync;
import net.agrarius.ftbquestssync.persistence.ConnectionProvider;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;

public final class RewardClaimRepository {

    private final ConnectionProvider connectionProvider;

    public RewardClaimRepository(ConnectionProvider connectionProvider) {
        this.connectionProvider = connectionProvider;
    }

    private static final String SQL_TRY_CLAIM_SCOPED =
            "INSERT IGNORE INTO ftbquests_reward_claim_scopes "
            + "(scope_type, scope_uuid, reward_id, cycle, state, team_id, claimed_at_ms, granted_by_server) "
            + "VALUES (?, ?, ?, ?, 'GRANTED', ?, ?, ?)";

    private static final String SQL_DELETE_CLAIM =
            "DELETE FROM ftbquests_reward_claims WHERE team_id=? AND reward_id=? AND claim_uuid=?";

    private static final String SQL_DELETE_ALL_CLAIMS_FOR_REWARD =
            "DELETE FROM ftbquests_reward_claims WHERE team_id=? AND reward_id=?";

    private static final String SQL_DELETE_CLAIM_SCOPED =
            "DELETE FROM ftbquests_reward_claim_scopes WHERE scope_type=? AND scope_uuid=? AND reward_id=?";

    private static final String SQL_DELETE_ALL_CLAIMS_SCOPED_FOR_REWARD =
            "DELETE FROM ftbquests_reward_claim_scopes WHERE team_id=? AND reward_id=?";

    public boolean tryClaimReward(UUID teamId, long rewardId, UUID claimUuid, long claimedAtMs) {
        return tryClaimRewardScoped(teamId, rewardId, "LEGACY", claimUuid, claimedAtMs);
    }

    public boolean tryClaimRewardScoped(UUID teamId, long rewardId, String scopeType, UUID scopeUuid, long claimedAtMs) {
        return tryClaimRewardScoped(teamId, rewardId, scopeType, scopeUuid, 0L, claimedAtMs, new long[]{rewardId}).firstClaim;
    }

    public MySQLBackend.ScopedClaimResult tryClaimRewardScoped(
            UUID teamId, long rewardId, String scopeType, UUID scopeUuid, long cycle, long claimedAtMs, long[] questRewardIds) {
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
                return new MySQLBackend.ScopedClaimResult(false, false);
            }
            boolean cycleComplete = cycleRewardIds.length > 0
                    && countClaimsInCycle(conn, scopeType, scopeUuid, cycle, cycleRewardIds) >= cycleRewardIds.length;
            FTBQuestsSync.LOGGER.info(
                    "Reward claim GRANTED (first): team={} reward={} scopeType={} scopeUuid={} cycle={} cycleComplete={} server={}",
                    teamId, rewardId, scopeType, scopeUuid, cycle, cycleComplete, RedisSync.getInstance().getServerId());
            return new MySQLBackend.ScopedClaimResult(true, cycleComplete);
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.error(
                    "tryClaimReward failed for team={} reward={} scopeType={} cycle={} - rewardFailClosed={}",
                    teamId, rewardId, scopeType, cycle, Config.rewardFailClosed, e);
            return new MySQLBackend.ScopedClaimResult(!Config.rewardFailClosed, false);
        }
    }

    public boolean seedClaimScope(UUID teamId, long rewardId, String scopeType, UUID scopeUuid,
                                  long cycle, long claimedAtMs, String serverIdTag) {
        try (Connection conn = connectionProvider.getConnection();
             PreparedStatement ps = conn.prepareStatement(SQL_TRY_CLAIM_SCOPED)) {
            ps.setString(1, scopeType);
            ps.setString(2, scopeUuid.toString());
            ps.setLong(3, rewardId);
            ps.setLong(4, cycle);
            ps.setString(5, teamId.toString());
            ps.setLong(6, claimedAtMs);
            ps.setString(7, serverIdTag);
            return ps.executeUpdate() > 0;
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.warn("seedClaimScope failed team={} reward={} scope={}", teamId, rewardId, scopeType, e);
            return false;
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

    public void deleteRewardClaim(UUID teamId, long rewardId, UUID claimUuid) {
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

    public void deleteRewardClaimScoped(String scopeType, UUID scopeUuid, long rewardId) {
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

    public void deleteAllScopedClaimsForReward(UUID teamId, long rewardId) {
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
}
