package net.agrarius.ftbquestssync.quests.sync;

import dev.ftb.mods.ftbquests.quest.BaseQuestFile;
import dev.ftb.mods.ftbquests.quest.Chapter;
import dev.ftb.mods.ftbquests.quest.Quest;
import dev.ftb.mods.ftbquests.quest.QuestObjectBase;
import dev.ftb.mods.ftbquests.quest.reward.Reward;
import dev.ftb.mods.ftbquests.quest.task.Task;
import dev.ftb.mods.ftbquests.util.QuestKey;
import net.agrarius.ftbquestssync.config.Config;
import net.minecraft.nbt.CompoundTag;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

/**
 * Shop-cycle merge policy for repeatable team-claim quests.
 *
 * <p>When a remote server has advanced a shop quest to a higher completion
 * cycle, the local state for that quest must be cleared so the new cycle can
 * start fresh. Conversely, when the local cycle is higher, the remote snapshot
 * is cleared in-memory before the rest of the merge runs.</p>
 */
final class ShopCycleMergePolicy {

    private ShopCycleMergePolicy() {
    }

    static void applyShopCycleMergePolicy(CompoundTag out, CompoundTag local, CompoundTag remote, BaseQuestFile file) {
        if (file == null) return;
        for (Chapter chapter : file.getAllChapters()) {
            if (!Config.teamClaimChapterIds.contains(chapter.getId())) continue;
            for (Quest quest : chapter.getQuests()) {
                String questKey = code(quest.id);
                int localCycle = local.getCompound("completion_count").getInt(questKey);
                int remoteCycle = remote.getCompound("completion_count").getInt(questKey);
                if (remoteCycle > localCycle) {
                    clearShopQuestState(out, quest);
                } else if (remoteCycle < localCycle) {
                    clearShopQuestState(remote, quest);
                }
            }
        }
    }

    static boolean hasAdvancedShopCycle(CompoundTag local, CompoundTag remote, BaseQuestFile file) {
        if (local == null || remote == null || file == null) return false;
        for (Chapter chapter : file.getAllChapters()) {
            if (!Config.teamClaimChapterIds.contains(chapter.getId())) continue;
            for (Quest quest : chapter.getQuests()) {
                String questKey = code(quest.id);
                if (remote.getCompound("completion_count").getInt(questKey)
                        > local.getCompound("completion_count").getInt(questKey)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void clearShopQuestState(CompoundTag tag, Quest quest) {
        removeMapKey(tag, "started", quest.id);
        removeMapKey(tag, "completed", quest.id);
        removeMapKey(tag, "repeatable", quest.id);
        removeMapKey(tag, "completion_count", quest.id);
        for (Task task : quest.getTasks()) {
            removeMapKey(tag, "started", task.id);
            removeMapKey(tag, "completed", task.id);
            removeMapKey(tag, "task_progress", task.id);
        }
        Set<Long> rewardIds = new HashSet<>();
        for (Reward reward : quest.getRewards()) {
            rewardIds.add(reward.id);
        }
        removeClaimedRewardKeys(tag, rewardIds);
    }

    private static void removeMapKey(CompoundTag root, String key, long id) {
        CompoundTag map = root.getCompound(key).copy();
        map.remove(code(id));
        root.put(key, map);
    }

    private static void removeClaimedRewardKeys(CompoundTag root, Set<Long> rewardIds) {
        if (rewardIds.isEmpty()) return;
        CompoundTag claimed = root.getCompound("claimed_rewards").copy();
        for (String key : new ArrayList<>(claimed.getAllKeys())) {
            try {
                if (rewardIds.contains(QuestKey.fromString(key).id())) {
                    claimed.remove(key);
                }
            } catch (Exception ignored) {
                // Ignore malformed keys from older data; vanilla will skip them too.
            }
        }
        root.put("claimed_rewards", claimed);
    }

    static String code(long id) {
        return QuestObjectBase.getCodeString(id);
    }
}
