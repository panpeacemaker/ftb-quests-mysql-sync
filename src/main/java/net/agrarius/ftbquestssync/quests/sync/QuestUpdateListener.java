package net.agrarius.ftbquestssync.quests.sync;

import dev.ftb.mods.ftblibrary.snbt.SNBTCompoundTag;
import dev.ftb.mods.ftbquests.api.FTBQuestsAPI;
import dev.ftb.mods.ftbquests.net.DisplayCompletionToastMessage;
import dev.ftb.mods.ftbquests.net.ObjectCompletedMessage;
import dev.ftb.mods.ftbquests.net.ObjectCompletedResetMessage;
import dev.ftb.mods.ftbquests.net.ResetRewardMessage;
import dev.ftb.mods.ftbquests.net.SyncTeamDataMessage;
import dev.ftb.mods.ftbquests.net.UpdateTaskProgressMessage;
import dev.ftb.mods.ftbquests.quest.BaseQuestFile;
import dev.ftb.mods.ftbquests.quest.Chapter;
import dev.ftb.mods.ftbquests.quest.Quest;
import dev.ftb.mods.ftbquests.quest.QuestObjectBase;
import dev.ftb.mods.ftbquests.quest.TeamData;
import dev.ftb.mods.ftbquests.quest.reward.Reward;
import dev.ftb.mods.ftbteams.api.FTBTeamsAPI;
import net.agrarius.ftbquestssync.Config;
import net.agrarius.ftbquestssync.FTBQuestsSync;
import net.agrarius.ftbquestssync.MySQLBackend;
import net.agrarius.ftbquestssync.QuestTeamUpdateEvent;
import net.agrarius.ftbquestssync.RankSoloProgress;
import net.agrarius.ftbquestssync.RedisSync;
import net.agrarius.ftbquestssync.RedisEventParser;
import net.agrarius.ftbquestssync.ShopRepeatableSync;
import net.agrarius.ftbquestssync.TeamLoadStateRegistry;
import net.agrarius.ftbquestssync.messaging.MessageListener;
import net.agrarius.ftbquestssync.messaging.RedisChannels;
import net.minecraft.Util;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles inbound quest team update messages from Redis.
 */
public class QuestUpdateListener implements MessageListener {

    private static final long SEEN_EVENT_TTL_MS = 10 * 60 * 1_000L;

    private final MinecraftServer server;
    private final ConcurrentHashMap<UUID, Long> seenEvents = new ConcurrentHashMap<>();

    public QuestUpdateListener(MinecraftServer server) {
        this.server = server;
    }

    @Override
    public void onMessage(String channel, String message) {
        try {
            if (!RedisChannels.QUEST_CHANNEL.equals(channel)) return;
            handleQuestEvent(message);
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.warn("Bad Redis message on channel={}: {}", channel, message, e);
        }
    }

    private void handleQuestEvent(String message) {
        if (!Config.syncQuests) return;
        pruneSeenEvents();
        QuestTeamUpdateEvent parsed = RedisEventParser.parseQuestEvent(message);
        if (parsed == null) {
            parsed = RedisEventParser.parseLegacyQuestEvent(message);
        }
        if (parsed == null) return;
        final QuestTeamUpdateEvent event = parsed;

        if (RedisSync.getInstance().getServerId().equals(event.sourceServer())) return;
        if (seenEvents.putIfAbsent(event.eventId(), System.currentTimeMillis()) != null) return;
        if (server == null) return;

        FTBQuestsSync.LOGGER.info("Received remote Redis team update: source={} team={} revision={} hash={} forceReplace={}",
                event.sourceServer(), event.teamId(), event.revision(), event.hashHex(), event.forceReplace());
        MySQLBackend.getInstance().loadTeamDataAsync(event.teamId()).whenComplete((fresh, error) -> {
            if (error != null) {
                FTBQuestsSync.LOGGER.error("Async MySQL load failed for remote team {}", event.teamId(), error);
                return;
            }
            if (fresh == null) return;
            server.execute(() -> applyRemoteUpdate(event.teamId(), fresh, event.forceReplace()));
        });
    }

    private void applyRemoteUpdate(UUID teamId, CompoundTag fresh, boolean forceReplace) {
        try {
            if (!Config.syncQuests) return;

            BaseQuestFile file = FTBQuestsAPI.api().getQuestFile(false);
            Map<UUID, TeamData> map = getTeamDataMap(file);
            if (map == null) return;

            TeamData existing = map.get(teamId);
            TeamData teamData;
            boolean mergedLocalProgress = false;
            CompoundTag localBeforeMerge = null;

            // Declare merged here so it's accessible in the lambda below
            CompoundTag merged = null;

            if (existing != null && !forceReplace) {
                // MERGE local + remote so concurrent edits across servers don't
                // erase each other. Without this, last-writer-wins replacement
                // wipes completions made between local save and remote receive.
                CompoundTag localTag;
                try {
                    localTag = ((CompoundTag) existing.serializeNBT()).copy();
                } catch (Exception serFail) {
                    FTBQuestsSync.LOGGER.warn(
                            "Could not serialize local team {} for merge - falling back to replace",
                            teamId, serFail);
                    localTag = null;
                }
                localBeforeMerge = localTag == null ? null : localTag.copy();
                merged = (localTag != null) ? TeamDataMerger.mergeTeamDataNbt(localTag, fresh, file) : fresh;
                TeamData mergeStaging = new TeamData(teamId, file);
                mergeStaging.deserializeNBT(SNBTCompoundTag.of(merged));
                try {
                    existing.deserializeNBT(SNBTCompoundTag.of(mergeStaging.serializeNBT()));
                } catch (Exception rollbackEx) {
                    FTBQuestsSync.LOGGER.error(
                            "Rollback: applyRemoteUpdate could not write merged state into live team={} — remote update dropped",
                            teamId, rollbackEx);
                    return;
                }
                teamData = existing;
                mergedLocalProgress = localTag != null && !TeamDataMerger.nbtEquals(merged, fresh);
                if (mergedLocalProgress) {
                    // Force the next saveIfChanged to push the merged state
                    // back so the cluster converges instead of oscillating.
                    try {
                        Field sf = TeamData.class.getDeclaredField("shouldSave");
                        sf.setAccessible(true);
                        sf.setBoolean(existing, true);
                    } catch (Exception e) {
                        FTBQuestsSync.LOGGER.debug("Could not mark merged team {} dirty", teamId, e);
                    }
                    existing.markDirty();
                }
            } else if (existing != null) {
                merged = fresh;
                TeamData replaceStaging = new TeamData(teamId, file);
                replaceStaging.deserializeNBT(SNBTCompoundTag.of(fresh));
                try {
                    existing.deserializeNBT(SNBTCompoundTag.of(replaceStaging.serializeNBT()));
                } catch (Exception rollbackEx) {
                    FTBQuestsSync.LOGGER.error(
                            "Rollback: applyRemoteUpdate could not write replacement state into live team={} — remote update dropped",
                            teamId, rollbackEx);
                    return;
                }
                teamData = existing;
                FTBQuestsSync.LOGGER.info("forceReplace applied to team {}", teamId);
            } else {
                merged = fresh;
                teamData = new TeamData(teamId, file);
                teamData.deserializeNBT(SNBTCompoundTag.of(fresh));
                map.put(teamId, teamData);
            }

            TeamLoadStateRegistry.setTeamLoadState(teamId, TeamLoadStateRegistry.TeamLoadState.LOADED);
            QuestSyncService.clearRewardsBlocked(teamData);
            boolean advancedShopCycle = !forceReplace && localBeforeMerge != null && ShopCycleMergePolicy.hasAdvancedShopCycle(localBeforeMerge, fresh, file);
            int forcedShopResets = ShopRepeatableSync.resetAllCompleteShopQuests(teamData);
            final TeamData finalTeamData = teamData;
            final boolean finalMerged = mergedLocalProgress;
            CompoundTag deltaSnapshot = forcedShopResets > 0 ? ((CompoundTag) teamData.serializeNBT()).copy() : merged;
            final CompoundTag finalDeltaSnapshot = deltaSnapshot;
            final CompoundTag finalLocalBeforeMerge = localBeforeMerge;
            final CompoundTag finalFresh = fresh;
            final boolean finalAdvancedShopCycle = advancedShopCycle;
            final int finalForcedShopResets = forcedShopResets;
            // Push to online members when there is ANY meaningful delta. Previously this
            // only fired for sendFullTeamData / shop cycles, so a plain task completion on
            // the peer server never reached this server's clients until an unrelated DB
            // write happened to ride along (issue #15: "needs an external update"). Detect
            // a completion/progress delta vs the pre-merge local state and push then too.
            boolean hasCompletionOrProgressDelta = false;
            if (finalLocalBeforeMerge != null) {
                CompoundTag beforeC = finalLocalBeforeMerge.getCompound("completed");
                CompoundTag afterC = finalDeltaSnapshot.getCompound("completed");
                CompoundTag beforeP = finalLocalBeforeMerge.getCompound("task_progress");
                CompoundTag afterP = finalDeltaSnapshot.getCompound("task_progress");
                hasCompletionOrProgressDelta = !TeamDataMerger.nbtEquals(beforeC, afterC) || !TeamDataMerger.nbtEquals(beforeP, afterP);
            }
            final boolean finalHasDelta = hasCompletionOrProgressDelta;
            FTBTeamsAPI.api().getManager().getTeamByID(teamId).ifPresent(team -> {
                if (!Config.sendFullTeamData && !finalAdvancedShopCycle && finalForcedShopResets == 0
                        && !finalHasDelta) return;
                Collection<ServerPlayer> members = team.getOnlineMembers();
                if (!members.isEmpty()) {
                    ArrayList<ServerPlayer> memberList = new ArrayList<>(members);

                    new SyncTeamDataMessage(finalTeamData, true).sendTo(memberList);
                    if (finalAdvancedShopCycle) {
                        sendShopResetPacketsForAdvancedCycles(file, finalLocalBeforeMerge, finalFresh, teamId, memberList);
                    }
                    for (ServerPlayer m : memberList) {
                        try {
                            TeamData selfData = TeamData.get(m);
                            if (selfData.getTeamId().equals(finalTeamData.getTeamId())) {
                                server.tell(new net.minecraft.server.TickTask(server.getTickCount() + 1, () -> RankSoloProgress.pushToPlayerAsync(selfData, m)));
                            }
                        } catch (Exception e) {
                            FTBQuestsSync.LOGGER.debug("Solo re-push skipped for {}", m.getUUID(), e);
                        }
                    }

                    // Send ObjectCompletedMessage for each completed quest so the
                    // client refreshes its quest GUI and shows toast notifications.
                    CompoundTag completedNbt = finalDeltaSnapshot.getCompound("completed");
                    if (!completedNbt.isEmpty()) {
                        for (String key : completedNbt.getAllKeys()) {
                            try {
                                long objectId = Long.parseLong(key, 16);
                                new ObjectCompletedMessage(teamId, objectId).sendTo(memberList);
                            } catch (Exception e) {
                                FTBQuestsSync.LOGGER.debug("Skipping completed object packet for key={} team={}", key, teamId, e);
                            }
                        }
                    }

                    // Completion toast for OTHER-server teammates: ObjectCompletedMessage only
                    // refreshes their GUI, no popup. Diff newly-completed vs before-merge; the
                    // size<=5 + non-null guards suppress toast spam on initial/catch-up syncs.
                    CompoundTag beforeCompleted = finalLocalBeforeMerge != null
                            ? finalLocalBeforeMerge.getCompound("completed") : new CompoundTag();
                    java.util.List<Long> newlyCompleted = new java.util.ArrayList<>();
                    for (String key : completedNbt.getAllKeys()) {
                        if (!beforeCompleted.contains(key)) {
                            try {
                                newlyCompleted.add(Long.parseLong(key, 16));
                            } catch (NumberFormatException ignored) {
                            }
                        }
                    }
                    if (finalLocalBeforeMerge != null && !newlyCompleted.isEmpty() && newlyCompleted.size() <= 5) {
                        for (long objectId : newlyCompleted) {
                            new DisplayCompletionToastMessage(objectId).sendTo(memberList);
                        }
                        FTBQuestsSync.LOGGER.info("Sent {} completion toast(s) to {} teammate(s) for team {}",
                                newlyCompleted.size(), memberList.size(), teamId);
                    }
                    // UpdateTaskProgressMessage takes (TeamData, long, long) in
                    // this FTB Quests version (2001.4.18).
                    CompoundTag progressNbt = finalDeltaSnapshot.getCompound("task_progress");
                    if (!progressNbt.isEmpty()) {
                        for (String key : progressNbt.getAllKeys()) {
                            try {
                                long taskId = Long.parseLong(key, 16);
                                long progress = progressNbt.getLong(key);
                                new UpdateTaskProgressMessage(finalTeamData, taskId, progress).sendTo(memberList);
                            } catch (Exception e) {
                                FTBQuestsSync.LOGGER.debug("Skipping task progress packet for key={} team={}", key, teamId, e);
                            }
                        }
                    }

                    FTBQuestsSync.LOGGER.info(
                            "Applied remote team update: team={} players={} forceReplace={} mergedLocalProgress={} forcedShopResets={} completed={} progress={}",
                            teamId, members.size(), forceReplace, finalMerged, finalForcedShopResets,
                            finalDeltaSnapshot.getCompound("completed").size(),
                            finalDeltaSnapshot.getCompound("task_progress").size());
                } else {
                    FTBQuestsSync.LOGGER.info(
                            "Applied remote team update: team={} no online members forceReplace={} mergedLocalProgress={} forcedShopResets={}",
                            teamId, forceReplace, finalMerged, finalForcedShopResets);
                }
            });
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.error("Failed to apply remote update for team {}", teamId, e);
        }
    }

    private void sendShopResetPacketsForAdvancedCycles(
            BaseQuestFile file, CompoundTag local, CompoundTag remote, UUID teamId, Collection<ServerPlayer> members) {
        if (file == null || local == null || remote == null || members.isEmpty()) return;
        for (Chapter chapter : file.getAllChapters()) {
            if (!Config.teamClaimChapterIds.contains(chapter.getId())) continue;
            for (Quest quest : chapter.getQuests()) {
                String questKey = code(quest.id);
                int localCycle = local.getCompound("completion_count").getInt(questKey);
                int remoteCycle = remote.getCompound("completion_count").getInt(questKey);
                if (remoteCycle <= localCycle) continue;
                new ObjectCompletedResetMessage(teamId, quest.id).sendTo(members);
                for (Reward reward : quest.getRewards()) {
                    new ResetRewardMessage(teamId, Util.NIL_UUID, reward.id).sendTo(members);
                }
                FTBQuestsSync.LOGGER.info("Sent shop reset packets for remote advanced cycle: team={} quest={} {}->{}",
                        teamId, quest.id, localCycle, remoteCycle);
            }
        }
    }

    private static String code(long id) {
        return QuestObjectBase.getCodeString(id);
    }

    private Map<UUID, TeamData> getTeamDataMap(BaseQuestFile file) {
        try {
            Field f = BaseQuestFile.class.getDeclaredField("teamDataMap");
            f.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<UUID, TeamData> map = (Map<UUID, TeamData>) f.get(file);
            return map;
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.error("Cannot access BaseQuestFile.teamDataMap", e);
            return null;
        }
    }

    private void pruneSeenEvents() {
        long cutoff = System.currentTimeMillis() - SEEN_EVENT_TTL_MS;
        Iterator<Map.Entry<UUID, Long>> it = seenEvents.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue() < cutoff) {
                it.remove();
            }
        }
    }
}
