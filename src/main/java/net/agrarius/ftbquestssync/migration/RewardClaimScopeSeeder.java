package net.agrarius.ftbquestssync.migration;

import dev.ftb.mods.ftbquests.quest.Quest;
import dev.ftb.mods.ftbquests.quest.ServerQuestFile;
import dev.ftb.mods.ftbquests.quest.reward.Reward;
import net.agrarius.ftbquestssync.Config;
import net.agrarius.ftbquestssync.FTBQuestsSync;
import net.agrarius.ftbquestssync.MembershipCache;
import net.agrarius.ftbquestssync.MySQLBackend;
import net.agrarius.ftbquestssync.RankSoloProgress;
import net.minecraft.nbt.CompoundTag;

import java.util.UUID;

/**
 * Seeds {@code ftbquests_reward_claim_scopes} directly from a migrated team
 * blob so already-claimed rewards cannot be claimed again after migration.
 *
 * <p>The runtime claim guard ({@code RewardClaimMixin}) keys a claim on
 * {@code (scope_type, scope_uuid, reward_id, cycle)} and refuses a claim whose
 * row already exists. This seeder reads the claimed rewards straight from the
 * migrated blob's {@code claimed_rewards} map (NOT from a live {@code TeamData}
 * object, which on a fresh boot has not yet loaded the migrated state and would
 * report almost everything as unclaimed) and replicates the EXACT scope/cycle
 * policy of {@code RewardClaimMixin}:
 * <ul>
 *   <li>cycle comes from the blob's {@code completion_count} for repeatable /
 *       team-shared chapters, else 0;
 *   <li>scope is PLAYER only for solo (rank/QoL) chapters with global dedup,
 *       otherwise TEAM.
 * </ul>
 *
 * <p>Must run after the quest file is loaded so reward {@code ->} quest
 * {@code ->} chapter resolution and the policy scan are available.
 */
public final class RewardClaimScopeSeeder {

    private RewardClaimScopeSeeder() {}

    public static int seedFromBlob(UUID teamId, UUID playerUuid, CompoundTag legacyTag,
                                   String serverIdTag, boolean dryRun) {
        ServerQuestFile file = ServerQuestFile.INSTANCE;
        if (file == null) {
            FTBQuestsSync.LOGGER.warn("seedFromBlob: quest file not loaded; skipping team={}", teamId);
            return 0;
        }
        if (legacyTag == null || !legacyTag.contains("claimed_rewards", 10)) {
            return 0;
        }
        CompoundTag claimed = legacyTag.getCompound("claimed_rewards");
        CompoundTag completionCount = legacyTag.contains("completion_count", 10)
                ? legacyTag.getCompound("completion_count") : new CompoundTag();
        CompoundTag completed = legacyTag.contains("completed", 10)
                ? legacyTag.getCompound("completed") : new CompoundTag();

        MySQLBackend db = MySQLBackend.getInstance();
        long now = System.currentTimeMillis();
        int written = 0;

        for (String claimKey : claimed.getAllKeys()) {
            long rewardId = rewardIdFromClaimKey(claimKey);
            if (rewardId == 0L) {
                continue;
            }
            Reward reward = file.getReward(rewardId);
            if (reward == null || reward.getQuest() == null) {
                continue;
            }
            Quest quest = reward.getQuest();
            long chapterId = quest.getChapter() != null ? quest.getChapter().getId() : 0L;

            boolean teamClaimOverride = Config.teamClaimChapterIds.contains(chapterId);
            boolean teamShared = Config.teamSharedChapterIds.contains(chapterId) && !teamClaimOverride;
            boolean rankQuestReward = RankSoloProgress.isRankQuest(quest.id);
            boolean soloChapter = Config.soloChapterIds.contains(chapterId) && !teamClaimOverride && !teamShared;
            boolean soloScopedReward = (rankQuestReward || soloChapter) && Config.soloRewardsPerPlayer;
            boolean effectiveTeamReward = reward.isTeamReward();

            if (!soloScopedReward && !teamClaimOverride && !teamShared && !effectiveTeamReward) {
                continue;
            }

            String scopeType = (soloScopedReward && Config.teamRewardsDedupGlobal) ? "PLAYER" : "TEAM";
            UUID scopeUuid = "TEAM".equals(scopeType)
                    ? MembershipCache.resolveTeam(playerUuid, teamId)
                    : playerUuid;
            long cycle = completionCycle(quest, completionCount, completed,
                    teamClaimOverride || teamShared);
            long claimedAtMs = claimed.getLong(claimKey);
            if (claimedAtMs <= 0L) {
                claimedAtMs = now;
            }

            if (!dryRun && db.seedClaimScope(teamId, rewardId, scopeType, scopeUuid, cycle, claimedAtMs, serverIdTag)) {
                written++;
            } else if (dryRun) {
                written++;
            }
        }

        if (written > 0) {
            FTBQuestsSync.LOGGER.info(
                    "Reward claim scope seed: team={} player={} scopes={} dryRun={}",
                    teamId, playerUuid, written, dryRun);
        }
        return written;
    }

    private static long completionCycle(Quest quest, CompoundTag completionCount,
                                        CompoundTag completed, boolean scoped) {
        if (!scoped) {
            return 0L;
        }
        String questHex = hexId(quest.id);
        if (quest.canBeRepeated()) {
            return completionCount.getLong(questHex);
        }
        return completed.contains(questHex) ? 1L : 0L;
    }

    private static long rewardIdFromClaimKey(String claimKey) {
        int colon = claimKey.lastIndexOf(':');
        String hex = colon >= 0 ? claimKey.substring(colon + 1) : claimKey;
        try {
            return Long.parseUnsignedLong(hex, 16);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static String hexId(long id) {
        return String.format("%016X", id);
    }
}
