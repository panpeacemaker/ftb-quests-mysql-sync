package net.agrarius.ftbquestssync;

import net.agrarius.ftbquestssync.config.Config;
import net.agrarius.ftbquestssync.chunks.ChunkMaterializer;
import net.agrarius.ftbquestssync.messaging.MessageRouter;
import net.agrarius.ftbquestssync.messaging.RedisBus;
import net.agrarius.ftbquestssync.messaging.RedisChannels;
import net.agrarius.ftbquestssync.quests.sync.QuestSyncService;
import net.agrarius.ftbquestssync.quests.sync.QuestUpdateListener;
import net.agrarius.ftbquestssync.quests.sync.TeamDataMerger;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import dev.ftb.mods.ftbquests.quest.BaseQuestFile;
import redis.clients.jedis.JedisPool;

import java.util.UUID;

/**
 * Facade for Redis-backed cross-server live invalidation.
 *
 * <p>Slice 4 of issue #36 splits the former God class into the leaf
 * messaging package (transport) and the quests/sync package (quest merge
 * logic). RedisSync keeps the same public surface so existing callers in
 * mixins, FTBQuestsSync, MySQLBackend, TeamSync, PresenceSync and chunk code
 * keep compiling unchanged.</p>
 */
public class RedisSync {

    private static final RedisSync INSTANCE = new RedisSync();

    private final String fallbackServerId;

    private RedisBus bus;
    private MessageRouter router;
    private QuestSyncService questSyncService;
    private QuestUpdateListener questUpdateListener;
    private MinecraftServer server;

    private RedisSync() {
        this.fallbackServerId = (Config.getServerId() != null)
                ? Config.getServerId()
                : System.getProperty("ftbquestssync.server.id",
                        "unknown-" + UUID.randomUUID().toString().substring(0, 8));
    }

    public static RedisSync getInstance() {
        return INSTANCE;
    }

    public String getServerId() {
        return (Config.getServerId() != null) ? Config.getServerId() : fallbackServerId;
    }

    public boolean isEnabled() {
        return bus != null && bus.isEnabled();
    }

    public JedisPool getPool() {
        return bus == null ? null : bus.getPool();
    }

    public void initialize(MinecraftServer server) {
        this.server = server;
        try {
            this.router = new MessageRouter();
            this.bus = new RedisBus(router, "FTBQuestsSync-Redis-Sub");
            this.questUpdateListener = new QuestUpdateListener(server);
            this.questSyncService = new QuestSyncService(bus, server);

            // Chunk JSON on the dedicated chunk channel.
            router.register(RedisChannels.CHUNK_CHANNEL, (channel, message) -> {
                try {
                    handleChunkJson(message);
                } catch (Exception e) {
                    FTBQuestsSync.LOGGER.warn("Bad Redis message on channel={}: {}", channel, message, e);
                }
            });

            // Quest channel carries quest events plus a one-release legacy chunk fallback.
            router.register(RedisChannels.QUEST_CHANNEL, (channel, message) -> {
                try {
                    if (RedisEventParser.isLegacyChunkPayload(message)) {
                        handleLegacyChunk(message);
                    }
                } catch (Exception e) {
                    FTBQuestsSync.LOGGER.warn("Bad Redis message on channel={}: {}", channel, message, e);
                }
            });
            router.register(RedisChannels.QUEST_CHANNEL, questUpdateListener);

            if (!bus.initialize()) {
                FTBQuestsSync.LOGGER.error("Redis init failed - cross-server live invalidation disabled");
                return;
            }

            FTBQuestsSync.LOGGER.info("Redis ready: {}:{} questChannel={} chunkChannel={} serverId={}",
                    Config.redisHost, Config.redisPort, RedisChannels.QUEST_CHANNEL, RedisChannels.CHUNK_CHANNEL, getServerId());
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.error("Redis init failed - cross-server live invalidation disabled", e);
        }
    }

    public void publishTeamUpdate(UUID teamId, long revision, String hashHex) {
        if (questSyncService == null) return;
        questSyncService.publishTeamUpdate(teamId, revision, hashHex);
    }

    public void publishChunkUpdate(String reason, UUID teamId) {
        if (!isEnabled() || !Config.syncChunks) return;

        ChunkClaimsUpdateEvent event = new ChunkClaimsUpdateEvent(getServerId(), reason, teamId);
        String payload = RedisEventParser.GSON.toJson(event);
        if (bus != null) {
            bus.publish(RedisChannels.CHUNK_CHANNEL, payload);
        }
        FTBQuestsSync.LOGGER.info("Published Redis chunk invalidation: {}", payload);
    }

    public void forceReloadAndPushTo(UUID teamId, ServerPlayer recipient) {
        if (questSyncService == null) return;
        questSyncService.forceReloadAndPushTo(teamId, recipient);
    }

    /**
     * Package-private facade kept for the existing {@code RedisSyncMergeTest}.
     * Logic lives in {@link TeamDataMerger}.
     */
    static CompoundTag mergeTeamDataNbt(CompoundTag local, CompoundTag remote, BaseQuestFile file) {
        return TeamDataMerger.mergeTeamDataNbt(local, remote, file);
    }

    private void handleChunkJson(String message) {
        if (!Config.syncChunks) return;
        ChunkClaimsUpdateEvent event = RedisEventParser.parseChunkEvent(message);
        if (event == null) return;
        if (getServerId().equals(event.serverId())) return;
        FTBQuestsSync.LOGGER.info("Received remote chunk invalidation: source={} reason={} team={}",
                event.serverId(), event.reason(), event.teamId());
        ChunkMaterializer.materializeTeam(event.teamId());
    }

    private void handleLegacyChunk(String message) {
        if (!Config.syncChunks) return;
        ChunkClaimsUpdateEvent event = RedisEventParser.parseLegacyChunkEvent(message);
        if (event == null) return;
        if (getServerId().equals(event.serverId())) return;
        FTBQuestsSync.LOGGER.info("Received remote chunk invalidation: source={} reason={} team={}",
                event.serverId(), event.reason(), event.teamId());
        ChunkMaterializer.materializeTeam(event.teamId());
    }

    public void shutdown() {
        if (bus != null) bus.shutdown();
    }
}
