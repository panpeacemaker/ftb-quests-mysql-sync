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
    private static final Map<UUID, Map<Long, Long>> taskProgressByPlayer = new ConcurrentHashMap<>();
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
                boolean teamChapter = Config.teamClaimChapterIds.contains(chapterId)
                        || Config.teamSharedChapterIds.contains(chapterId);
                boolean chapterListed = Config.soloChapterIds.contains(chapterId);

                int questCount = 0;
                int taskCount = 0;
                for (Quest quest : chapter.getQuests()) {
                    seenQuests.add(quest.id);
                    boolean questListed = Config.soloQuestIds.contains(quest.id);
                    boolean questSolo = !teamChapter && isPolicySolo(chapterListed, questListed, false);
                    if (questSolo) {
                        quests.add(quest.id);
                        if (quest.canBeRepeated()) {
                            repeatableQuests.add(quest.id);
                        }
                    }
                    questCount++;
                    for (Task task : quest.getTasks()) {
                        seenTasks.add(task.id);
                        boolean taskSolo = !teamChapter && isPolicySolo(chapterListed, questListed, Config.soloTaskIds.contains(task.id));
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

    public static boolean isRepeatableSoloQuest(long questId) {
        return repeatableSoloQuestIds.contains(questId);
    }

    public static boolean isInitialized() {
        return initialized;
    }

    /**
     * Resolves the owning quest id for a task id from the policy scan built at
     * {@link #init()}. Returns 0 when the task is unknown. The legacy migrator
     * needs this to translate a flat {@code task_progress} blob (keyed by task
     * id only) into the {@code rank_progress} rows the runtime expects, which
     * are keyed by both quest id and task id.
     */
    public static long questIdForTask(long taskId) {
        return taskToQuestMap.getOrDefault(taskId, 0L);
    }

    private static long getTaskProgress(UUID playerUuid, long taskId) {
        Map<Long, Long> map = taskProgressByPlayer.get(playerUuid);
        return map == null ? 0L : map.getOrDefault(taskId, 0L);
    }

    private static void putTaskProgress(UUID playerUuid, long taskId, long progress) {
        taskProgressByPlayer.computeIfAbsent(playerUuid, ignored -> new ConcurrentHashMap<>()).put(taskId, progress);
    }

    public static boolean isPlayerSoloTaskComplete(UUID playerUuid, Task task) {
        if (task == null) return false;
        long max = task.getMaxProgress();
        if (max <= 0L) return false;
        return getTaskProgress(playerUuid, task.id) >= max;
    }

    public static boolean isPlayerSoloQuestComplete(UUID playerUuid, Quest quest) {
        if (quest == null || isRepeatableSoloQuest(quest.id) || !isRankQuest(quest.id)) return false;
        var tasks = quest.getTasks();
        if (tasks.isEmpty()) return false;
        for (Task task : tasks) {
            if (!isPlayerSoloTaskComplete(playerUuid, task)) return false;
        }
        return true;
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
        tag.putBoolean("rewards_blocked", false);
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
        UUID playerUuid = player.getUUID();
        Quest quest = task.getQuest();
        long questId = quest.id;
        long max = task.getMaxProgress();
        long clamped = Math.max(0L, Math.min(progress, max));

        if (isRepeatableSoloQuest(questId)) {
            Map<Long, Long> map = taskProgressByPlayer.get(playerUuid);
            if (map != null) map.remove(task.id);
            MySQLBackend.getInstance().deleteRankProgressAsync(playerUuid, questId);
            FTBQuestsSync.LOGGER.info("Repeatable solo progress kept vanilla-only: player={} quest={} task={} progress={}/{}",
                    playerUuid, questId, task.id, clamped, max);
            return;
        }

        boolean wasQuestComplete = isPlayerSoloQuestComplete(playerUuid, quest);
        boolean wasTaskComplete = isPlayerSoloTaskComplete(playerUuid, task);
        putTaskProgress(playerUuid, task.id, clamped);
        boolean taskComplete = max > 0L && clamped >= max;

        MySQLBackend.getInstance().upsertRankProgressAsync(
                playerUuid, questId, task.id, clamped, taskComplete ? System.currentTimeMillis() : 0L);

        sendTaskProgress(teamData.getTeamId(), player, questId, task.id, clamped, taskComplete, wasTaskComplete);

        boolean questComplete = isPlayerSoloQuestComplete(playerUuid, quest);
        if (questComplete && !wasQuestComplete) {
            new ObjectCompletedMessage(teamData.getTeamId(), questId).sendTo(player);
        }
        FTBQuestsSync.LOGGER.info("Solo progress: player={} quest={} task={} progress={}/{} taskDone={} questDone={}",
                playerUuid, questId, task.id, clamped, max, taskComplete, questComplete);
    }

    public static void pushToPlayerAsync(TeamData ignored, ServerPlayer player) {
        if (player == null) return;
        UUID playerUuid = player.getUUID();
        net.minecraft.server.MinecraftServer srv = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
        if (srv == null) return;
        pushToPlayerAsync(playerUuid, srv);
    }

    public static void pushToPlayerAsync(UUID playerUuid, net.minecraft.server.MinecraftServer server) {
        if (playerUuid == null || server == null) return;
        java.util.concurrent.CompletableFuture<java.util.List<MySQLBackend.RankProgressRow>> future;
        try {
            future = MySQLBackend.getInstance().loadRankProgressAsync(playerUuid);
        } catch (Throwable t) {
            FTBQuestsSync.LOGGER.error("pushToPlayerAsync: submit failed for player={}", playerUuid, t);
            return;
        }
        future.whenComplete((rows, error) -> {
            if (error != null) {
                FTBQuestsSync.LOGGER.error("pushToPlayerAsync: DB load failed for player={}", playerUuid, error);
                return;
            }
            FTBQuestsSync.LOGGER.info("pushToPlayerAsync: {} rank rows for player={}", rows == null ? 0 : rows.size(), playerUuid);
            if (rows == null || rows.isEmpty()) return;
            try {
                server.execute(() -> {
                    try {
                        if (!server.isRunning()) return;
                        ServerPlayer current = server.getPlayerList().getPlayer(playerUuid);
                        if (current == null) {
                            FTBQuestsSync.LOGGER.debug("pushToPlayerAsync: player={} not online, skipping", playerUuid);
                            return;
                        }
                        TeamData teamData;
                        try {
                            teamData = dev.ftb.mods.ftbquests.quest.TeamData.get(current);
                        } catch (Exception e) {
                            FTBQuestsSync.LOGGER.warn("pushToPlayerAsync: could not get TeamData for player={}", playerUuid, e);
                            return;
                        }
                        replayRows(teamData, current, playerUuid, rows);
                    } catch (Throwable t) {
                        FTBQuestsSync.LOGGER.error("pushToPlayerAsync: main-thread failed for player={}", playerUuid, t);
                    }
                });
            } catch (Throwable t) {
                FTBQuestsSync.LOGGER.error("pushToPlayerAsync: schedule failed for player={}", playerUuid, t);
            }
        });
    }

    private static void replayRows(TeamData teamData, ServerPlayer player, UUID playerUuid,
                                   java.util.List<MySQLBackend.RankProgressRow> rows) {
        UUID teamId = teamData.getTeamId();
        Set<Long> questsTouched = new HashSet<>();
        for (MySQLBackend.RankProgressRow row : rows) {
            if (isRepeatableSoloQuest(row.questId)) {
                MySQLBackend.getInstance().deleteRankProgressAsync(playerUuid, row.questId);
                continue;
            }
            putTaskProgress(playerUuid, row.taskId, row.progress);
            boolean taskComplete = row.completedAtMs > 0L;
            sendTaskProgress(teamId, player, row.questId, row.taskId, row.progress, taskComplete, false);
            questsTouched.add(row.questId);
        }
        for (long questId : questsTouched) {
            Quest quest = resolveQuest(questId);
            if (quest != null && isPlayerSoloQuestComplete(playerUuid, quest)) {
                new ObjectCompletedMessage(teamId, questId).sendTo(player);
            }
        }
    }

    private static Quest resolveQuest(long questId) {
        try {
            BaseQuestFile file = FTBQuestsAPI.api().getQuestFile(false);
            if (file == null) return null;
            for (Chapter chapter : file.getAllChapters()) {
                for (Quest quest : chapter.getQuests()) {
                    if (quest.id == questId) return quest;
                }
            }
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.debug("resolveQuest failed for {}", questId, e);
        }
        return null;
    }

    private static void sendTaskProgress(UUID teamId, ServerPlayer player, long questId, long taskId,
                                         long progress, boolean taskComplete, boolean wasTaskComplete) {
        TeamData teamData = dev.ftb.mods.ftbquests.quest.TeamData.get(player);
        new ObjectStartedMessage(teamId, taskId).sendTo(player);
        new ObjectStartedMessage(teamId, questId).sendTo(player);
        new UpdateTaskProgressMessage(teamData, taskId, progress).sendTo(player);
        if (taskComplete && !wasTaskComplete) {
            new ObjectCompletedMessage(teamId, taskId).sendTo(player);
        }
    }
}
