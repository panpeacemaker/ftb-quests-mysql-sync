package net.agrarius.ftbquestssync.migration;

import net.agrarius.ftbquestssync.FTBQuestsSync;
import net.agrarius.ftbquestssync.MySQLBackend;
import net.agrarius.ftbquestssync.RankSoloProgress;
import net.minecraft.nbt.CompoundTag;

import java.util.UUID;

/**
 * Extracts per-player solo/rank progress out of a legacy team blob and writes
 * it into {@code ftbquests_rank_progress}.
 *
 * <p>The runtime keeps solo/rank progress per player in a dedicated table, not
 * in the team blob: every team-blob save runs through
 * {@link RankSoloProgress#stripRankSharedProgress(CompoundTag)} which deletes
 * solo task/quest entries from the blob. The legacy export stores everything in
 * one flat {@code task_progress}/{@code completed} blob keyed by task id only,
 * so without this step the solo progress would be stripped on the first save
 * and never replayed (the {@code rank_progress} table would stay empty).
 *
 * <p>Must run after the quest file is loaded so the policy scan
 * ({@link RankSoloProgress#isRankTask}, {@link RankSoloProgress#questIdForTask})
 * is available to classify tasks and resolve their owning quest.
 */
public final class RankProgressMigrator {

    private RankProgressMigrator() {}

    public static int migrate(UUID playerUuid, CompoundTag legacyTag, boolean dryRun) {
        if (!RankSoloProgress.isInitialized()) {
            FTBQuestsSync.LOGGER.warn(
                    "Rank progress migration skipped for player={}: RankSoloProgress not initialized (quest file not loaded)",
                    playerUuid);
            return 0;
        }
        if (legacyTag == null || !legacyTag.contains("task_progress", 10)) {
            return 0;
        }
        CompoundTag taskProgress = legacyTag.getCompound("task_progress");
        CompoundTag completed = legacyTag.contains("completed", 10)
                ? legacyTag.getCompound("completed") : new CompoundTag();

        MySQLBackend db = MySQLBackend.getInstance();
        int written = 0;
        for (String taskHex : taskProgress.getAllKeys()) {
            long taskId = parseHexId(taskHex);
            if (taskId == 0L || !RankSoloProgress.isRankTask(taskId)) {
                continue;
            }
            long questId = RankSoloProgress.questIdForTask(taskId);
            if (questId == 0L) {
                continue;
            }
            long progress = taskProgress.getLong(taskHex);
            long completedAtMs = completed.contains(taskHex) ? completed.getLong(taskHex) : 0L;
            if (!dryRun) {
                db.upsertRankProgress(playerUuid, questId, taskId, progress, completedAtMs);
            }
            written++;
        }
        if (written > 0) {
            FTBQuestsSync.LOGGER.info(
                    "Rank progress migration: player={} rankRows={} dryRun={}", playerUuid, written, dryRun);
        }
        return written;
    }

    private static long parseHexId(String hex) {
        try {
            return Long.parseUnsignedLong(hex, 16);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
