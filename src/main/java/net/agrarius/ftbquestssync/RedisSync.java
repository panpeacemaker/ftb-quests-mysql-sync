package net.agrarius.ftbquestssync;

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
import dev.ftb.mods.ftbquests.quest.task.Task;
import dev.ftb.mods.ftbquests.util.QuestKey;
import dev.ftb.mods.ftbteams.api.FTBTeamsAPI;
import net.minecraft.Util;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPubSub;
import redis.clients.jedis.Protocol;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class RedisSync {

    private static final RedisSync INSTANCE = new RedisSync();
    private static final String CHANNEL = "agrarius:quests:team-updated";
    private static final long SEEN_EVENT_TTL_MS = 10 * 60 * 1_000L;

    private final String fallbackServerId;
    private final ConcurrentHashMap<UUID, Long> seenEvents = new ConcurrentHashMap<>();

    private JedisPool pool;
    private ExecutorService subscriberExec;
    private Jedis subscriberConn;
    private MinecraftServer server;
    private volatile boolean enabled;

    private RedisSync() {
        this.fallbackServerId = (Config.serverId != null)
                ? Config.serverId
                : System.getProperty("ftbquestssync.server.id",
                        "unknown-" + UUID.randomUUID().toString().substring(0, 8));
    }

    public static RedisSync getInstance() {
        return INSTANCE;
    }

    public String getServerId() {
        return (Config.serverId != null) ? Config.serverId : fallbackServerId;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void initialize(MinecraftServer server) {
        this.server = server;
        try {
            String password = Config.redisPassword.isBlank() ? null : Config.redisPassword;
            pool = new JedisPool(new GenericObjectPoolConfig<>(), Config.redisHost, Config.redisPort,
                    Protocol.DEFAULT_TIMEOUT, password);
            try (Jedis jedis = pool.getResource()) {
                String pong = jedis.ping();
                if (!"PONG".equals(pong)) {
                    throw new IllegalStateException("Unexpected Redis PING: " + pong);
                }
            }
            enabled = true;
            subscriberExec = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "FTBQuestsSync-Redis-Sub");
                t.setDaemon(true);
                return t;
            });
            subscriberExec.submit(this::subscribeLoop);
            FTBQuestsSync.LOGGER.info("Redis ready: {}:{} channel={} serverId={}",
                    Config.redisHost, Config.redisPort, CHANNEL, getServerId());
        } catch (Exception e) {
            enabled = false;
            FTBQuestsSync.LOGGER.error("Redis init failed - cross-server live invalidation disabled", e);
        }
    }

    private void subscribeLoop() {
        while (enabled && !Thread.currentThread().isInterrupted()) {
            try {
                subscriberConn = pool.getResource();
                subscriberConn.subscribe(new JedisPubSub() {
                    @Override
                    public void onMessage(String channel, String message) {
                        handleRemoteUpdate(message);
                    }
                }, CHANNEL);
            } catch (Exception e) {
                if (!enabled) continue;
                FTBQuestsSync.LOGGER.warn("Redis subscriber error, reconnecting in 5s", e);
                try {
                    Thread.sleep(5_000L);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            } finally {
                try {
                    if (subscriberConn != null) subscriberConn.close();
                } catch (Exception e) {
                    FTBQuestsSync.LOGGER.debug("Redis subscriber close failed", e);
                }
                subscriberConn = null;
            }
        }
    }

    public void publishTeamUpdate(UUID teamId, long revision, String hashHex) {
        if (!enabled || !Config.syncQuests) return;

        try (Jedis jedis = pool.getResource()) {
            String eventId = UUID.randomUUID().toString();
            String payload = "{"
                    + "\"eventId\":\"" + eventId + "\","
                    + "\"sourceServer\":\"" + escapeJson(getServerId()) + "\","
                    + "\"entityType\":\"quest_team\","
                    + "\"entityId\":\"" + teamId + "\","
                    + "\"revision\":" + revision + ","
                    + "\"hash\":\"" + escapeJson(hashHex) + "\","
                    + "\"reason\":\"saveIfChanged\""
                    + "}";
            jedis.publish(CHANNEL, payload);
            FTBQuestsSync.LOGGER.info("Published Redis team update: {}", payload);
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.warn("Redis publish failed for {}", teamId, e);
        }
    }

    public void publishChunkUpdate(String reason, UUID teamId) {
        if (!enabled || !Config.syncChunks) return;

        String payload = getServerId() + "|" + reason + "|" + teamId + "|-";
        try (Jedis jedis = pool.getResource()) {
            jedis.publish(CHANNEL, payload);
            FTBQuestsSync.LOGGER.info("Published Redis chunk invalidation: {}", payload);
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.warn("Redis chunk publish failed: {}", payload, e);
        }
    }

    private void handleRemoteUpdate(String message) {
        try {
            if (handleChunkMessage(message)) return;
            if (!Config.syncQuests) return;
            pruneSeenEvents();
            RemoteEvent event = parseEvent(message);
            if (event == null) return;

            if (getServerId().equals(event.sourceServer)) return;
            if (seenEvents.putIfAbsent(event.eventId, System.currentTimeMillis()) != null) return;
            if (server == null) return;

            FTBQuestsSync.LOGGER.info("Received remote Redis team update: source={} team={} revision={} hash={} forceReplace={}",
                    event.sourceServer, event.teamId, event.revision, event.hashHex, event.forceReplace);
            MySQLBackend.getInstance().loadTeamDataAsync(event.teamId).whenComplete((fresh, error) -> {
                if (error != null) {
                    FTBQuestsSync.LOGGER.error("Async MySQL load failed for remote team {}", event.teamId, error);
                    return;
                }
                if (fresh == null) return;
                server.execute(() -> applyRemoteUpdate(event.teamId, fresh, event.forceReplace));
            });
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.warn("Bad Redis message: {}", message, e);
        }
    }

    private boolean handleChunkMessage(String message) {
        String[] parts = message.split("\\|", 4);
        if (parts.length < 4 || !parts[1].startsWith("chunks_")) return false;
        if (!Config.syncChunks) return true;
        String sourceServer = parts[0];
        if (getServerId().equals(sourceServer)) return true;
        UUID teamId = UUID.fromString(parts[2]);
        FTBQuestsSync.LOGGER.info("Received remote chunk invalidation: source={} reason={} team={}",
                sourceServer, parts[1], teamId);
        ChunkMaterializer.materializeTeam(teamId);
        return true;
    }

    private RemoteEvent parseEvent(String message) {
        String trimmed = message.trim();
        if (!trimmed.startsWith("{")) {
            int colon = message.indexOf(':');
            if (colon <= 0) return null;
            return new RemoteEvent(UUID.randomUUID(), message.substring(0, colon),
                    UUID.fromString(message.substring(colon + 1)), -1L, "", false);
        }
        UUID eventId = UUID.fromString(jsonValue(trimmed, "eventId"));
        String sourceServer = jsonValue(trimmed, "sourceServer");
        UUID teamId = UUID.fromString(jsonValue(trimmed, "entityId"));
        long revision = Long.parseLong(jsonNumber(trimmed, "revision", "-1"));
        String hash = jsonValue(trimmed, "hash");
        boolean forceReplace = jsonBool(trimmed, "forceReplace");
        return new RemoteEvent(eventId, sourceServer, teamId, revision, hash, forceReplace);
    }

    private static String jsonValue(String json, String key) {
        String needle = "\"" + key + "\":\"";
        int start = json.indexOf(needle);
        if (start < 0) return "";
        start += needle.length();
        int end = json.indexOf('"', start);
        return end < 0 ? "" : json.substring(start, end).replace("\\\"", "\"").replace("\\\\", "\\");
    }

    private static String jsonNumber(String json, String key, String fallback) {
        String needle = "\"" + key + "\":";
        int start = json.indexOf(needle);
        if (start < 0) return fallback;
        start += needle.length();
        int end = start;
        while (end < json.length() && Character.isDigit(json.charAt(end))) end++;
        return end == start ? fallback : json.substring(start, end);
    }

    private static boolean jsonBool(String json, String key) {
        int k = json.indexOf("\"" + key + "\"");
        if (k < 0) return false;
        int colon = json.indexOf(':', k);
        if (colon < 0) return false;
        int i = colon + 1;
        while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;
        return json.startsWith("true", i) || json.startsWith("\"true\"", i);
    }

    private static String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static final class RemoteEvent {
        private final UUID eventId;
        private final String sourceServer;
        private final UUID teamId;
        private final long revision;
        private final String hashHex;
        private final boolean forceReplace;

        private RemoteEvent(UUID eventId, String sourceServer, UUID teamId, long revision, String hashHex, boolean forceReplace) {
            this.eventId = eventId;
            this.sourceServer = sourceServer;
            this.teamId = teamId;
            this.revision = revision;
            this.hashHex = hashHex;
            this.forceReplace = forceReplace;
        }
    }

    public void forceReloadAndPushTo(UUID teamId, ServerPlayer recipient) {
        if (server == null) return;
        MySQLBackend.getInstance().loadTeamDataAsync(teamId).whenComplete((fresh, error) -> {
            if (error != null) {
                FTBQuestsSync.LOGGER.error(
                        "Force reload async DB load failed for team={} player={}",
                        teamId, recipient.getUUID(), error);
                return;
            }
            if (fresh == null) {
                FTBQuestsSync.LOGGER.info(
                        "Force reload on login: no DB row for team={} (player={}) - keeping local state",
                        teamId, recipient.getUUID());
                TeamLoadStateRegistry.setTeamLoadState(teamId, TeamLoadStateRegistry.TeamLoadState.NEW);
                return;
            }
            server.execute(() -> {
                try {
                    if (!Config.syncQuests) return;
                    if (recipient.connection == null) return;
                    BaseQuestFile file = FTBQuestsAPI.api().getQuestFile(false);
                    if (file == null) return;

                    TeamData staging = new TeamData(teamId, file);
                    staging.deserializeNBT(SNBTCompoundTag.of(fresh));
                    clearCachedProgress(staging);

                    TeamData live = file.getOrCreateTeamData(teamId);
                    try {
                        live.deserializeNBT(SNBTCompoundTag.of(staging.serializeNBT()));
                    } catch (Exception rollbackEx) {
                        FTBQuestsSync.LOGGER.error(
                                "Rollback: could not write staging into live TeamData for team={} — local state preserved",
                                teamId, rollbackEx);
                        return;
                    }
                    clearCachedProgress(live);
                    clearRewardsBlocked(live);
                    TeamLoadStateRegistry.setTeamLoadState(teamId, TeamLoadStateRegistry.TeamLoadState.LOADED);

                    new SyncTeamDataMessage(live, true).sendTo(java.util.List.of(recipient));
                    RankSoloProgress.pushToPlayerAsync(live, recipient);
                    server.tell(new net.minecraft.server.TickTask(server.getTickCount() + 40, () -> RankSoloProgress.pushToPlayerAsync(live, recipient)));
                    FTBQuestsSync.LOGGER.info(
                            "Force reload on login: team={} pushed SyncTeamDataMessage to player={}",
                            teamId, recipient.getUUID());
                } catch (Exception e) {
                    FTBQuestsSync.LOGGER.error(
                            "Force reload on login failed for team={} player={}",
                            teamId, recipient.getUUID(), e);
                }
            });
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
                merged = (localTag != null) ? mergeTeamDataNbt(localTag, fresh, file) : fresh;
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
                mergedLocalProgress = localTag != null && !nbtEquals(merged, fresh);
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
            clearRewardsBlocked(teamData);
            boolean advancedShopCycle = !forceReplace && localBeforeMerge != null && hasAdvancedShopCycle(localBeforeMerge, fresh, file);
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
                hasCompletionOrProgressDelta = !nbtEquals(beforeC, afterC) || !nbtEquals(beforeP, afterP);
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

    /**
     * Field-level union of TeamData NBT.
     *
     * Concurrent quest completions on two servers BOTH need to survive after
     * Redis sync; replacing the local snapshot with the remote one (vanilla
     * behaviour) would erase whichever side wrote second. So we union the
     * progress maps key-by-key:
     *
     *   started, completed             - keep the earliest timestamp
     *   task_progress, completion_count - keep the highest count
     *   claimed_rewards, repeatable    - union, prefer local on key clash
     *   player_data                    - union per-player compound tags
     *
     * Top-level scalars (uuid, version, name, lock, rewards_blocked) come
     * from the remote snapshot when present so server-driven config edits
     * still propagate.
     */
    static CompoundTag mergeTeamDataNbt(CompoundTag local, CompoundTag remote, BaseQuestFile file) {
        CompoundTag out = local.copy();
        CompoundTag remoteForMerge = remote.copy();
        for (String key : remote.getAllKeys()) {
            if (!out.contains(key)) {
                out.put(key, remote.get(key).copy());
            }
        }
        applyShopCycleMergePolicy(out, local, remoteForMerge, file);
        mergeMapMinLong(out, remoteForMerge, "started");
        mergeMapMinLong(out, remoteForMerge, "completed");
        mergeMapMaxLong(out, remoteForMerge, "task_progress");
        mergeMapMaxLong(out, remoteForMerge, "completion_count");
        mergeCompoundUnion(out, remoteForMerge, "claimed_rewards");
        mergeCompoundUnion(out, remoteForMerge, "repeatable");
        mergeCompoundUnion(out, remoteForMerge, "player_data");
        // Scalars: prefer remote (server-driven) where it actually has the key.
        for (String scalar : new String[]{"name", "lock", "rewards_blocked"}) {
            if (remoteForMerge.contains(scalar)) {
                out.put(scalar, remoteForMerge.get(scalar).copy());
            }
        }
        return out;
    }

    private static void applyShopCycleMergePolicy(CompoundTag out, CompoundTag local, CompoundTag remote, BaseQuestFile file) {
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

    private static boolean hasAdvancedShopCycle(CompoundTag local, CompoundTag remote, BaseQuestFile file) {
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

    private static void mergeMapMinLong(CompoundTag out, CompoundTag remote, String key) {
        CompoundTag remoteMap = remote.getCompound(key);
        if (remoteMap.isEmpty()) return;
        CompoundTag merged = out.getCompound(key).copy();
        for (String k : remoteMap.getAllKeys()) {
            long remoteVal = remoteMap.getLong(k);
            if (!merged.contains(k)) {
                merged.putLong(k, remoteVal);
            } else {
                long localVal = merged.getLong(k);
                merged.putLong(k, Math.min(localVal, remoteVal));
            }
        }
        out.put(key, merged);
    }

    private static void mergeMapMaxLong(CompoundTag out, CompoundTag remote, String key) {
        CompoundTag remoteMap = remote.getCompound(key);
        if (remoteMap.isEmpty()) return;
        CompoundTag merged = out.getCompound(key).copy();
        for (String k : remoteMap.getAllKeys()) {
            long remoteVal = remoteMap.getLong(k);
            if (!merged.contains(k)) {
                merged.putLong(k, remoteVal);
            } else {
                long localVal = merged.getLong(k);
                merged.putLong(k, Math.max(localVal, remoteVal));
            }
        }
        out.put(key, merged);
    }

    private static void mergeCompoundUnion(CompoundTag out, CompoundTag remote, String key) {
        CompoundTag remoteMap = remote.getCompound(key);
        if (remoteMap.isEmpty()) return;
        CompoundTag merged = out.getCompound(key).copy();
        for (String k : remoteMap.getAllKeys()) {
            if (!merged.contains(k)) {
                merged.put(k, remoteMap.get(k).copy());
            }
        }
        out.put(key, merged);
    }

    private static boolean nbtEquals(CompoundTag a, CompoundTag b) {
        try {
            return a.equals(b);
        } catch (Exception e) {
            return false;
        }
    }

    private static volatile java.lang.reflect.Field rewardsBlockedField;
    private static volatile boolean rewardsBlockedFieldChecked = false;

    private static void clearRewardsBlocked(TeamData td) {
        if (!rewardsBlockedFieldChecked) {
            synchronized (RedisSync.class) {
                if (!rewardsBlockedFieldChecked) {
                    try {
                        rewardsBlockedField = TeamData.class.getDeclaredField("rewardsBlocked");
                        rewardsBlockedField.setAccessible(true);
                    } catch (NoSuchFieldException e) {
                        FTBQuestsSync.LOGGER.warn("TeamData.rewardsBlocked field not found");
                    }
                    rewardsBlockedFieldChecked = true;
                }
            }
        }
        if (rewardsBlockedField == null) return;
        try {
            rewardsBlockedField.setBoolean(td, false);
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.debug("clearRewardsBlocked failed", e);
        }
    }

    private static volatile Method clearCachedProgressMethod;
    private static volatile boolean clearCachedProgressChecked = false;

    private static void clearCachedProgress(TeamData td) {
        if (!clearCachedProgressChecked) {
            synchronized (RedisSync.class) {
                if (!clearCachedProgressChecked) {
                    try {
                        clearCachedProgressMethod = TeamData.class.getMethod("clearCachedProgress");
                    } catch (NoSuchMethodException e) {
                        FTBQuestsSync.LOGGER.warn(
                                "TeamData.clearCachedProgress() not found — stale quest caches will not be"
                                + " invalidated after reload (FTB Quests version mismatch?)");
                    }
                    clearCachedProgressChecked = true;
                }
            }
        }
        if (clearCachedProgressMethod == null) return;
        try {
            clearCachedProgressMethod.invoke(td);
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.debug("clearCachedProgress invocation failed", e);
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

    public JedisPool getPool() {
        return pool;
    }

    public void shutdown() {
        enabled = false;
        try {
            if (subscriberConn != null) subscriberConn.disconnect();
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.debug("Redis subscriber disconnect during shutdown failed", e);
        }
        if (subscriberExec != null) subscriberExec.shutdownNow();
        if (pool != null) pool.close();
    }
}
