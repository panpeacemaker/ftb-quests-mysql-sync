package net.agrarius.ftbquestssync;

import dev.ftb.mods.ftbquests.quest.Chapter;
import dev.ftb.mods.ftbquests.quest.Quest;
import dev.ftb.mods.ftbquests.quest.TeamData;
import dev.ftb.mods.ftbquests.quest.reward.Reward;
import dev.ftb.mods.ftbquests.util.QuestKey;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import net.minecraft.Util;

import java.lang.reflect.Field;
import java.util.UUID;

public final class ShopRepeatableSync {

    private static volatile Field claimedRewardsField;
    private static volatile Field completionCountField;
    private static volatile Field questRepeatableTimeField;

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
        Object2LongMap claimed = claimedRewards(teamData);
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

        Object2LongMap claimed = claimedRewards(teamData);
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
        completionCount(teamData).put(quest.id, cycle + 1);
        if (quest.getRepeatCooldown() > 0) {
            questRepeatableTime(teamData).put(
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

    private static Object2LongMap claimedRewards(TeamData teamData) {
        Object value = fieldValue(teamData, "claimedRewards");
        if (value instanceof Object2LongMap map) return map;
        throw new IllegalStateException("TeamData.claimedRewards has unexpected type");
    }

    private static Long2IntMap completionCount(TeamData teamData) {
        Object value = fieldValue(teamData, "completionCount");
        if (value instanceof Long2IntMap map) return map;
        throw new IllegalStateException("TeamData.completionCount has unexpected type");
    }

    private static Long2LongMap questRepeatableTime(TeamData teamData) {
        Object value = fieldValue(teamData, "questRepeatableTime");
        if (value instanceof Long2LongMap map) return map;
        throw new IllegalStateException("TeamData.questRepeatableTime has unexpected type");
    }

    private static Object fieldValue(TeamData teamData, String name) {
        try {
            return field(name).get(teamData);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot access TeamData." + name, e);
        }
    }

    private static Field field(String name) throws NoSuchFieldException {
        Field cached = switch (name) {
            case "claimedRewards" -> claimedRewardsField;
            case "completionCount" -> completionCountField;
            case "questRepeatableTime" -> questRepeatableTimeField;
            default -> null;
        };
        if (cached != null) return cached;

        synchronized (ShopRepeatableSync.class) {
            cached = switch (name) {
                case "claimedRewards" -> claimedRewardsField;
                case "completionCount" -> completionCountField;
                case "questRepeatableTime" -> questRepeatableTimeField;
                default -> null;
            };
            if (cached != null) return cached;

            Field resolved = TeamData.class.getDeclaredField(name);
            resolved.setAccessible(true);
            switch (name) {
                case "claimedRewards" -> claimedRewardsField = resolved;
                case "completionCount" -> completionCountField = resolved;
                case "questRepeatableTime" -> questRepeatableTimeField = resolved;
                default -> throw new NoSuchFieldException(name);
            }
            return resolved;
        }
    }
}
