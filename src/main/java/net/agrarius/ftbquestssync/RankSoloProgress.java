package net.agrarius.ftbquestssync;

import dev.ftb.mods.ftbquests.api.FTBQuestsAPI;
import dev.ftb.mods.ftbquests.net.ObjectCompletedMessage;
import dev.ftb.mods.ftbquests.net.ObjectStartedMessage;
import dev.ftb.mods.ftbquests.net.UpdateTaskProgressMessage;
import dev.ftb.mods.ftbquests.quest.BaseQuestFile;
import dev.ftb.mods.ftbquests.quest.Chapter;
import dev.ftb.mods.ftbquests.quest.Quest;
import dev.ftb.mods.ftbquests.quest.TeamData;
import dev.ftb.mods.ftbquests.quest.task.Task;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class RankSoloProgress {

    private static Set<Long> soloQuestIds = new HashSet<>();
    private static Set<Long> soloTaskIds = new HashSet<>();
    private static Set<Long> repeatableSoloQuestIds = new HashSet<>();
    private static Map<Long, Long> taskToQuestMap = new HashMap<>();
    private static final Map<UUID, Set<Long>> completedSoloQuestsByPlayer = new ConcurrentHashMap<>();
    private static volatile boolean initialized = false;

    private RankSoloProgress() {}

    /**
     * Build solo quest/task whitelist from chapter data at server start.
     * Called from FTBQuestsSync.onServerStarted() after quest file is loaded.
     */
    public static void init() {
        try {
            BaseQuestFile file = FTBQuestsAPI.api().getQuestFile(false);
            if (file == null) {
                FTBQuestsSync.LOGGER.warn("RankSoloProgress: quest file not available yet, skipping init");
                return;
            }
            Set<Long> quests = new HashSet<>();
            Set<Long> tasks = new HashSet<>();
            Set<Long> repeatableQuests = new HashSet<>();
            Map<Long, Long> taskQuest = new HashMap<>();
            Set<Long> seenChapters = new HashSet<>();
            Set<Long> seenQuests = new HashSet<>();
            Set<Long> seenTasks = new HashSet<>();

            for (Chapter chapter : file.getAllChapters()) {
                long chapterId = chapter.getId();
                seenChapters.add(chapterId);
                boolean chapterListed = Config.soloChapterIds.contains(chapterId);

                int questCount = 0;
                int taskCount = 0;
                for (Quest quest : chapter.getQuests()) {
                    seenQuests.add(quest.id);
                    boolean questListed = Config.soloQuestIds.contains(quest.id);
                    boolean questSolo = isPolicySolo(chapterListed, questListed, false);
                    if (questSolo) {
                        quests.add(quest.id);
                        if (quest.canBeRepeated()) {
                            repeatableQuests.add(quest.id);
                        }
                    }
                    questCount++;
                    for (Task task : quest.getTasks()) {
                        seenTasks.add(task.id);
                        boolean taskSolo = isPolicySolo(chapterListed, questListed, Config.soloTaskIds.contains(task.id));
                        if (taskSolo) {
                            tasks.add(task.id);
                        }
                        taskQuest.put(task.id, quest.id);
                        taskCount++;
                    }
                }
                if (Config.soloChapterIds.contains(chapterId) || "whitelist".equals(Config.policyMode)) {
                    FTBQuestsSync.LOGGER.info(
                            "RankSoloProgress: policy chapter {} (id={}) scanned with {} quests, {} tasks",
                            chapter.getRawTitle(), String.format("%016X", chapterId), questCount, taskCount);
                }
            }

            warnMissing("chapter", Config.soloChapterIds, seenChapters);
            warnMissing("quest", Config.soloQuestIds, seenQuests);
            warnMissing("task", Config.soloTaskIds, seenTasks);

            soloQuestIds = quests;
            soloTaskIds = tasks;
            repeatableSoloQuestIds = repeatableQuests;
            taskToQuestMap = taskQuest;
            initialized = true;
            FTBQuestsSync.LOGGER.info(
                    "RankSoloProgress: initialized policy mode={} with {} solo quests, {} repeatable solo quests and {} solo tasks",
                    Config.policyMode, soloQuestIds.size(), repeatableSoloQuestIds.size(), soloTaskIds.size());
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.error("RankSoloProgress.init failed", e);
        }
    }

    private static boolean isPolicySolo(boolean chapterListed, boolean questListed, boolean taskListed) {
        boolean listed = chapterListed || questListed || taskListed;
        return "whitelist".equals(Config.policyMode) ? !listed : listed;
    }

    private static void warnMissing(String type, Set<Long> configured, Set<Long> seen) {
        for (long id : configured) {
            if (!seen.contains(id)) {
                FTBQuestsSync.LOGGER.warn("RankSoloProgress policy references missing {} id={}", type, String.format("%016X", id));
            }
        }
    }

    public static boolean isRankQuest(long id) {
        return soloQuestIds.contains(id);
    }

    public static boolean isRankTask(long id) {
        return soloTaskIds.contains(id);
    }

    public static boolean isRankReward(long id) {
        return false;
    }

    public static boolean isRepeatableSoloQuest(long questId) {
        return repeatableSoloQuestIds.contains(questId);
    }

    public static boolean isPlayerSoloQuestComplete(UUID playerUuid, long questId) {
        if (isRepeatableSoloQuest(questId)) return false;
        Set<Long> completed = completedSoloQuestsByPlayer.get(playerUuid);
        return completed != null && completed.contains(questId);
    }

    public static long questIdForTask(long taskId) {
        return taskToQuestMap.getOrDefault(taskId, 0L);
    }

    public static long taskIdForQuest(long questId) {
        for (Map.Entry<Long, Long> e : taskToQuestMap.entrySet()) {
            if (e.getValue() == questId) return e.getKey();
        }
        return 0L;
    }

    public static void stripRankSharedProgress(CompoundTag tag) {
        if (tag == null || !initialized) return;
        removeKeys(tag, "task_progress", soloTaskIds);
        removeKeys(tag, "started", soloTaskIds);
        removeKeys(tag, "completed", soloTaskIds);
        removeKeys(tag, "started", soloQuestIds);
        removeKeys(tag, "completed", soloQuestIds);
        removeKeys(tag, "completion_count", soloQuestIds);
        removeKeys(tag, "repeatable", soloQuestIds);
    }

    private static void removeKeys(CompoundTag root, String key, Set<Long> ids) {
        CompoundTag sub = root.getCompound(key);
        if (sub == null || sub.isEmpty()) return;
        for (long id : ids) {
            sub.remove(code(id));
        }
    }

    private static String code(long id) {
        return String.format("%016X", id);
    }

    public static void handleRankTaskProgress(TeamData teamData, ServerPlayer player, Task task, long progress) {
        if (player == null || task == null || !isRankTask(task.id) || !Config.syncSoloProgressPerPlayer) return;
        long max = task.getMaxProgress();
        long clamped = Math.max(0L, Math.min(progress, max));
        long questId = task.getQuest().id;
        if (isRepeatableSoloQuest(questId)) {
            Set<Long> completedSet = completedSoloQuestsByPlayer.get(player.getUUID());
            if (completedSet != null) {
                completedSet.remove(questId);
            }
            MySQLBackend.getInstance().deleteRankProgressAsync(player.getUUID(), questId);
            FTBQuestsSync.LOGGER.info("Repeatable solo progress kept vanilla-only: player={} quest={} task={} progress={}/{}",
                    player.getUUID(), questId, task.id, clamped, max);
            return;
        }
        boolean completed = clamped >= max;
        if (completed) {
            completedSoloQuestsByPlayer.computeIfAbsent(player.getUUID(), ignored -> ConcurrentHashMap.newKeySet()).add(questId);
        }
        MySQLBackend.getInstance().upsertRankProgressAsync(player.getUUID(), questId, task.id, clamped, completed ? System.currentTimeMillis() : 0L);
        sendRankProgress(teamData, player, questId, task.id, clamped, completed);
        FTBQuestsSync.LOGGER.info("Solo progress: player={} quest={} task={} progress={}/{}", player.getUUID(), questId, task.id, clamped, max);
    }

    public static void pushToPlayerAsync(TeamData teamData, ServerPlayer player) {
        if (teamData == null || player == null) return;
        MySQLBackend.getInstance().loadRankProgressAsync(player.getUUID()).whenComplete((rows, error) -> {
            if (error != null) {
                FTBQuestsSync.LOGGER.warn("Solo progress async load failed for player={}", player.getUUID(), error);
                return;
            }
            player.getServer().execute(() -> {
                for (MySQLBackend.RankProgressRow row : rows.values()) {
                    // Repeatable solo progress is kept in DB and injected per-player on login.
                    // It is NOT stored in shared TeamData, so it does not sync across team members.
                    if (!isRepeatableSoloQuest(row.questId) && row.completedAtMs > 0L) {
                        completedSoloQuestsByPlayer.computeIfAbsent(player.getUUID(), ignored -> ConcurrentHashMap.newKeySet()).add(row.questId);
                    }
                    sendRankProgress(teamData, player, row.questId, row.taskId, row.progress, row.completedAtMs > 0L);
                }
            });
        });
    }

    private static void sendRankProgress(TeamData teamData, ServerPlayer player, long questId, long taskId, long progress, boolean completed) {
        UUID teamId = teamData.getTeamId();
        new ObjectStartedMessage(teamId, taskId).sendTo(player);
        new ObjectStartedMessage(teamId, questId).sendTo(player);
        new UpdateTaskProgressMessage(teamData, taskId, progress).sendTo(player);
        if (completed) {
            new ObjectCompletedMessage(teamId, taskId).sendTo(player);
            new ObjectCompletedMessage(teamId, questId).sendTo(player);
        }
    }
}
