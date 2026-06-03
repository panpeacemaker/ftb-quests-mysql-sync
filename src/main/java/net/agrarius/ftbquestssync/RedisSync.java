package net.agrarius.ftbquestssync;

import dev.ftb.mods.ftblibrary.snbt.SNBTCompoundTag;
import dev.ftb.mods.ftbquests.api.FTBQuestsAPI;
import dev.ftb.mods.ftbquests.net.ObjectCompletedMessage;
import dev.ftb.mods.ftbquests.net.SyncTeamDataMessage;
import dev.ftb.mods.ftbquests.net.UpdateTaskProgressMessage;
import dev.ftb.mods.ftbquests.quest.BaseQuestFile;
import dev.ftb.mods.ftbquests.quest.TeamData;
import dev.ftb.mods.ftbteams.api.FTBTeamsAPI;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPubSub;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
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
            pool = new JedisPool(Config.redisHost, Config.redisPort);
            try (Jedis jedis = pool.getResource()) {
                if (!Config.redisPassword.isBlank()) {
                    jedis.auth(Config.redisPassword);
                }
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
                if (!Config.redisPassword.isBlank()) {
                    subscriberConn.auth(Config.redisPassword);
                }
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
            if (!Config.redisPassword.isBlank()) {
                jedis.auth(Config.redisPassword);
            }
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

    private void handleRemoteUpdate(String message) {
        try {
            if (!Config.syncQuests) return;
            pruneSeenEvents();
            RemoteEvent event = parseEvent(message);
            if (event == null) return;

            if (getServerId().equals(event.sourceServer)) return;
            if (seenEvents.putIfAbsent(event.eventId, System.currentTimeMillis()) != null) return;
            if (server == null) return;

            FTBQuestsSync.LOGGER.info("Received remote Redis team update: source={} team={} revision={} hash={}",
                    event.sourceServer, event.teamId, event.revision, event.hashHex);
            MySQLBackend.getInstance().loadTeamDataAsync(event.teamId).whenComplete((fresh, error) -> {
                if (error != null) {
                    FTBQuestsSync.LOGGER.error("Async MySQL load failed for remote team {}", event.teamId, error);
                    return;
                }
                if (fresh == null) return;
                server.execute(() -> applyRemoteUpdate(event.teamId, fresh));
            });
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.warn("Bad Redis message: {}", message, e);
        }
    }

    private RemoteEvent parseEvent(String message) {
        String trimmed = message.trim();
        if (!trimmed.startsWith("{")) {
            int colon = message.indexOf(':');
            if (colon <= 0) return null;
            return new RemoteEvent(UUID.randomUUID(), message.substring(0, colon),
                    UUID.fromString(message.substring(colon + 1)), -1L, "");
        }
        UUID eventId = UUID.fromString(jsonValue(trimmed, "eventId"));
        String sourceServer = jsonValue(trimmed, "sourceServer");
        UUID teamId = UUID.fromString(jsonValue(trimmed, "entityId"));
        long revision = Long.parseLong(jsonNumber(trimmed, "revision", "-1"));
        String hash = jsonValue(trimmed, "hash");
        return new RemoteEvent(eventId, sourceServer, teamId, revision, hash);
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

    private static String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static final class RemoteEvent {
        private final UUID eventId;
        private final String sourceServer;
        private final UUID teamId;
        private final long revision;
        private final String hashHex;

        private RemoteEvent(UUID eventId, String sourceServer, UUID teamId, long revision, String hashHex) {
            this.eventId = eventId;
            this.sourceServer = sourceServer;
            this.teamId = teamId;
            this.revision = revision;
            this.hashHex = hashHex;
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
                return;
            }
            server.execute(() -> {
            try {
                if (!Config.syncQuests) return;
                BaseQuestFile file = FTBQuestsAPI.api().getQuestFile(false);
                Map<UUID, TeamData> map = getTeamDataMap(file);
                if (map == null) return;

                TeamData teamData = new TeamData(teamId, file);
                teamData.deserializeNBT(SNBTCompoundTag.of(fresh));
                map.put(teamId, teamData);

                new SyncTeamDataMessage(teamData, true).sendTo(java.util.List.of(recipient));
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

    private void applyRemoteUpdate(UUID teamId, CompoundTag fresh) {
        try {
            if (!Config.syncQuests) return;

            BaseQuestFile file = FTBQuestsAPI.api().getQuestFile(false);
            Map<UUID, TeamData> map = getTeamDataMap(file);
            if (map == null) return;

            TeamData existing = map.get(teamId);
            TeamData teamData;
            boolean mergedLocalProgress = false;

            // Declare merged here so it's accessible in the lambda below
            CompoundTag merged = null;

            if (existing != null) {
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
                merged = (localTag != null) ? mergeTeamDataNbt(localTag, fresh) : fresh;
                existing.deserializeNBT(SNBTCompoundTag.of(merged));
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
                }
            } else {
                merged = fresh;
                teamData = new TeamData(teamId, file);
                teamData.deserializeNBT(SNBTCompoundTag.of(fresh));
                map.put(teamId, teamData);
            }

            final TeamData finalTeamData = teamData;
            final boolean finalMerged = mergedLocalProgress;
            CompoundTag deltaSnapshot = merged;
            FTBTeamsAPI.api().getManager().getTeamByID(teamId).ifPresent(team -> {
                if (!Config.sendFullTeamData) return;
                Collection<ServerPlayer> members = team.getOnlineMembers();
                if (!members.isEmpty()) {
                    ArrayList<ServerPlayer> memberList = new ArrayList<>(members);

                    new SyncTeamDataMessage(finalTeamData, true).sendTo(memberList);

                    // Send ObjectCompletedMessage for each completed quest so the
                    // client refreshes its quest GUI and shows toast notifications.
                    CompoundTag completedNbt = deltaSnapshot.getCompound("completed");
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
                    // UpdateTaskProgressMessage takes (TeamData, long, long) in
                    // this FTB Quests version (2001.4.18).
                    CompoundTag progressNbt = deltaSnapshot.getCompound("task_progress");
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
                            "Applied remote team update: team={} players={} mergedLocalProgress={} completed={} progress={}",
                            teamId, members.size(), finalMerged,
                            deltaSnapshot.getCompound("completed").size(),
                            deltaSnapshot.getCompound("task_progress").size());
                } else {
                    FTBQuestsSync.LOGGER.info(
                            "Applied remote team update: team={} no online members mergedLocalProgress={}",
                            teamId, finalMerged);
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
    static CompoundTag mergeTeamDataNbt(CompoundTag local, CompoundTag remote) {
        CompoundTag out = local.copy();
        for (String key : remote.getAllKeys()) {
            if (!out.contains(key)) {
                out.put(key, remote.get(key).copy());
            }
        }
        mergeMapMinLong(out, remote, "started");
        mergeMapMinLong(out, remote, "completed");
        mergeMapMaxLong(out, remote, "task_progress");
        mergeMapMaxLong(out, remote, "completion_count");
        mergeCompoundUnion(out, remote, "claimed_rewards");
        mergeCompoundUnion(out, remote, "repeatable");
        mergeCompoundUnion(out, remote, "player_data");
        // Scalars: prefer remote (server-driven) where it actually has the key.
        for (String scalar : new String[]{"name", "lock", "rewards_blocked"}) {
            if (remote.contains(scalar)) {
                out.put(scalar, remote.get(scalar).copy());
            }
        }
        return out;
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
