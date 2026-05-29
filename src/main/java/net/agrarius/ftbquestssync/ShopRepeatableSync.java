package net.agrarius.ftbquestssync;

import net.agrarius.ftbquestssync.mixin.TeamDataAccessor;
import dev.ftb.mods.ftbquests.quest.Chapter;
import dev.ftb.mods.ftbquests.quest.Quest;
import dev.ftb.mods.ftbquests.quest.TeamData;
import dev.ftb.mods.ftbquests.quest.reward.Reward;
import dev.ftb.mods.ftbquests.util.QuestKey;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import net.minecraft.Util;

import java.util.UUID;

public final class ShopRepeatableSync {

    private ShopRepeatableSync() {}

    public static boolean isShopQuest(Quest quest) {
        if (quest == null) return false;
        Chapter chapter = quest.getChapter();
        return chapter != null && Config.teamClaimChapterIds.contains(chapter.getId());
    }

    public static long[] rewardIds(Quest quest) {
        return quest.getRewards().stream().mapToLong(reward -> reward.id).toArray();
    }

    public static boolean allRewardsClaimed(TeamData teamData, Quest quest) {
        if (teamData == null || quest == null) return false;
        Object2LongMap<QuestKey> claimed = ((TeamDataAccessor) teamData).ftbQuestsSync$getClaimedRewards();
        int count = 0;
        for (Reward reward : quest.getRewards()) {
            count++;
            if (!claimed.containsKey(QuestKey.create(Util.NIL_UUID, reward.id))) return false;
        }
        return count > 0;
    }

    public static boolean resetIfCycleComplete(TeamData teamData, Quest quest, UUID playerUuid, long now, long expectedCycle) {
        if (!isShopQuest(quest) || !quest.canBeRepeated() || !teamData.isCompleted(quest)) return false;
        int cycle = teamData.getCompletionCount(quest);
        if ((long) cycle != expectedCycle) return false;

        Object2LongMap<QuestKey> claimed = ((TeamDataAccessor) teamData).ftbQuestsSync$getClaimedRewards();
        int rewards = 0;
        for (Reward reward : quest.getRewards()) {
            rewards++;
            QuestKey key = QuestKey.create(Util.NIL_UUID, reward.id);
            if (!claimed.containsKey(key)) {
                claimed.put(key, now);
            }
        }
        if (rewards == 0 || !allRewardsClaimed(teamData, quest)) return false;

        UUID actor = playerUuid == null ? Util.NIL_UUID : playerUuid;
        if (!quest.checkRepeatable(teamData, actor)) return false;
        TeamDataAccessor accessor = (TeamDataAccessor) teamData;
        accessor.ftbQuestsSync$getCompletionCount().put(quest.id, cycle + 1);
        if (quest.getRepeatCooldown() > 0) {
            accessor.ftbQuestsSync$getQuestRepeatableTime().put(
                    quest.id, System.currentTimeMillis() + quest.getRepeatCooldown() * 1000L);
        }
        teamData.clearCachedProgress();
        teamData.markDirty();
        FTBQuestsSync.LOGGER.info("Shop repeat reset forced: team={} quest={} cycle={} nextCycle={}",
                teamData.getTeamId(), quest.id, cycle, cycle + 1);
        return true;
    }

    public static int resetAllCompleteShopQuests(TeamData teamData) {
        if (teamData == null || teamData.getFile() == null) return 0;
        int resets = 0;
        long now = System.currentTimeMillis();
        for (Chapter chapter : teamData.getFile().getAllChapters()) {
            if (!Config.teamClaimChapterIds.contains(chapter.getId())) continue;
            for (Quest quest : chapter.getQuests()) {
                if (allRewardsClaimed(teamData, quest)
                        && resetIfCycleComplete(teamData, quest, Util.NIL_UUID, now, teamData.getCompletionCount(quest))) {
                    resets++;
                }
            }
        }
        return resets;
    }
}
