package net.agrarius.ftbquestssync.migration;

import dev.ftb.mods.ftbquests.api.FTBQuestsAPI;
import dev.ftb.mods.ftbquests.quest.BaseQuestFile;
import dev.ftb.mods.ftbquests.quest.Chapter;
import dev.ftb.mods.ftbquests.quest.Quest;
import dev.ftb.mods.ftbquests.quest.reward.Reward;
import dev.ftb.mods.ftbquests.quest.task.Task;
import net.agrarius.ftbquestssync.FTBQuestsSync;
import net.agrarius.ftbquestssync.quests.rank.RankSoloProgress;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Immutable snapshot of quest / reward / rank metadata captured on the
 * server thread before a background migration starts. Prevents off-thread
 * access to {@code ServerQuestFile}, {@code RankSoloProgress}, and other
 * FTB/Minecraft objects from the migration worker thread.
 */
public final class MigrationSnapshot {

    public final boolean rankInitialized;
    public final Set<Long> rankTaskIds;
    public final Map<Long, Long> rankTaskToQuestId;
    public final Set<Long> rankQuestIds;
    public final Set<Long> teamRewardIds;
    public final Map<Long, RewardMeta> rewardMeta;

    private MigrationSnapshot(boolean rankInitialized, Set<Long> rankTaskIds,
                              Map<Long, Long> rankTaskToQuestId, Set<Long> rankQuestIds,
                              Set<Long> teamRewardIds, Map<Long, RewardMeta> rewardMeta) {
        this.rankInitialized = rankInitialized;
        this.rankTaskIds = rankTaskIds;
        this.rankTaskToQuestId = rankTaskToQuestId;
        this.rankQuestIds = rankQuestIds;
        this.teamRewardIds = teamRewardIds;
        this.rewardMeta = rewardMeta;
    }

    /**
     * Capture everything on the <b>server thread</b>. Safe to call after
     * {@code FTBQuestsSync.onServerStarted()} because the quest file is
     * guaranteed loaded and {@link RankSoloProgress#init()} has run.
     *
     * @return never null; fields are empty collections when the quest file
     *         is not available (migration gracefully no-ops).
     */
    public static MigrationSnapshot capture() {
        boolean rankInit = RankSoloProgress.isInitialized();
        Set<Long> rankTasks = new HashSet<>();
        Map<Long, Long> taskToQuest = new HashMap<>();
        Set<Long> rankQuests = new HashSet<>();
        Set<Long> teamRewards = new HashSet<>();
        Map<Long, RewardMeta> rewards = new HashMap<>();

        try {
            BaseQuestFile file = FTBQuestsAPI.api().getQuestFile(false);
            if (file != null) {
                for (Chapter chapter : file.getAllChapters()) {
                    for (Quest quest : chapter.getQuests()) {
                        if (RankSoloProgress.isRankQuest(quest.id)) {
                            rankQuests.add(quest.id);
                        }
                        for (Task task : quest.getTasks()) {
                            if (RankSoloProgress.isRankTask(task.id)) {
                                rankTasks.add(task.id);
                                taskToQuest.put(task.id, quest.id);
                            }
                        }
                        for (Reward reward : quest.getRewards()) {
                            if (reward.isTeamReward()) {
                                teamRewards.add(reward.id);
                            }
                            long chapterId = quest.getChapter() != null ? quest.getChapter().getId() : 0L;
                            rewards.put(reward.id, new RewardMeta(
                                    quest.id, chapterId, reward.isTeamReward(), quest.canBeRepeated()));
                        }
                    }
                }
            }
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.warn("MigrationSnapshot.capture failed", e);
        }

        return new MigrationSnapshot(
                rankInit,
                Set.copyOf(rankTasks),
                Map.copyOf(taskToQuest),
                Set.copyOf(rankQuests),
                Set.copyOf(teamRewards),
                Map.copyOf(rewards));
    }

    /**
     * Lightweight metadata for a single reward, sufficient to run
     * {@link RewardClaimScopeSeeder} without touching FTB objects.
     */
    public static final class RewardMeta {
        public final long questId;
        public final long chapterId;
        public final boolean teamReward;
        public final boolean repeatable;

        RewardMeta(long questId, long chapterId, boolean teamReward, boolean repeatable) {
            this.questId = questId;
            this.chapterId = chapterId;
            this.teamReward = teamReward;
            this.repeatable = repeatable;
        }
    }
}
