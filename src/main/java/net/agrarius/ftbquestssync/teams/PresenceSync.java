package net.agrarius.ftbquestssync.teams;

import net.agrarius.ftbquestssync.config.Config;
import net.agrarius.ftbquestssync.FTBQuestsSync;
import net.agrarius.ftbquestssync.RedisSync;

import dev.ftb.mods.ftbteams.api.FTBTeamsAPI;
import dev.ftb.mods.ftbteams.data.PlayerTeam;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPubSub;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Cross-server player presence tracked via Redis with TTL + heartbeat.
 * The FTB Teams GUI online indicator is driven by {@link PlayerTeam#createClientPlayer()}
 * returning a {@link dev.ftb.mods.ftbteams.api.client.KnownClientPlayer} whose
 * {@code online()} flag is overridden by the mixin to reflect remote presence.
 */
public class PresenceSync {

    private static final PresenceSync INSTANCE = new PresenceSync();
    private static final String CHANNEL = "ftbquests:presence";
    private static final String REDIS_KEY_PREFIX = "ftbquests:presence:";
    private static final long TTL_SECONDS = 45L;
    private static final long HEARTBEAT_INTERVAL_MS = 15_000L;
    private static final long GRACE_MS = 5_000L;

    private final ConcurrentHashMap<UUID, RemotePresence> remoteCache = new ConcurrentHashMap<>();
    private final ScheduledExecutorService heartbeatExec = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "FTBQuestsSync-Presence-Heartbeat");
        t.setDaemon(true);
        return t;
    });
    private final ExecutorService redisExec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "FTBQuestsSync-Presence-Redis");
        t.setDaemon(true);
        return t;
    });
    private final ExecutorService subscriberExec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "FTBQuestsSync-Presence-Subscriber");
        t.setDaemon(true);
        return t;
    });

    private volatile boolean enabled;
    private MinecraftServer server;

    private PresenceSync() {
    }

    public static PresenceSync getInstance() {
        return INSTANCE;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void initialize(MinecraftServer server) {
        if (!Config.syncTeams) {
            FTBQuestsSync.LOGGER.info("PresenceSync init skipped: syncTeams=false");
            return;
        }
        this.server = server;
        JedisPool pool = RedisSync.getInstance().getPool();
        if (pool == null) {
            FTBQuestsSync.LOGGER.warn("PresenceSync init skipped: Redis pool not available");
            return;
        }
        try {
            try (Jedis jedis = pool.getResource()) {
                String pong = jedis.ping();
                if (!"PONG".equals(pong)) {
                    throw new IllegalStateException("Unexpected Redis PING: " + pong);
                }
            }
            enabled = true;
            heartbeatExec.scheduleAtFixedRate(
                    this::heartbeat, HEARTBEAT_INTERVAL_MS, HEARTBEAT_INTERVAL_MS, TimeUnit.MILLISECONDS);
            subscriberExec.submit(this::subscribeLoop);
            FTBQuestsSync.LOGGER.info("PresenceSync ready: channel={} serverId={}",
                    CHANNEL, RedisSync.getInstance().getServerId());
        } catch (Exception e) {
            enabled = false;
            FTBQuestsSync.LOGGER.error("PresenceSync init failed - cross-server presence disabled", e);
        }
    }

    public void shutdown() {
        enabled = false;
        heartbeatExec.shutdownNow();
        redisExec.shutdownNow();
        subscriberExec.shutdownNow();
    }

    public void onPlayerLogin(UUID uuid) {
        if (!enabled || uuid == null) return;
        redisExec.submit(() -> markOnline(uuid));
    }

    public void onPlayerLogout(UUID uuid) {
        if (!enabled || uuid == null) return;
        redisExec.submit(() -> markOffline(uuid));
    }

    public void markAllLocalOffline() {
        if (!enabled || server == null) return;
        List<UUID> uuids = new ArrayList<>();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            uuids.add(p.getUUID());
        }
        if (uuids.isEmpty()) return;
        redisExec.submit(() -> {
            for (UUID uuid : uuids) {
                markOffline(uuid);
            }
        });
    }

    private void markOnline(UUID uuid) {
        String serverId = RedisSync.getInstance().getServerId();
        String key = REDIS_KEY_PREFIX + uuid;
        JedisPool pool = RedisSync.getInstance().getPool();
        if (pool == null) return;
        try (Jedis jedis = pool.getResource()) {
            jedis.setex(key, TTL_SECONDS, serverId);
            jedis.publish(CHANNEL, serverId + "|online|" + uuid);
            FTBQuestsSync.LOGGER.debug("Presence online: uuid={} server={}", uuid, serverId);
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.warn("Presence markOnline failed for uuid={}", uuid, e);
        }
    }

    private void markOffline(UUID uuid) {
        String serverId = RedisSync.getInstance().getServerId();
        String key = REDIS_KEY_PREFIX + uuid;
        JedisPool pool = RedisSync.getInstance().getPool();
        if (pool == null) return;
        try (Jedis jedis = pool.getResource()) {
            String current = jedis.get(key);
            if (serverId.equals(current)) {
                jedis.del(key);
            }
            jedis.publish(CHANNEL, serverId + "|offline|" + uuid);
            FTBQuestsSync.LOGGER.debug("Presence offline: uuid={} server={}", uuid, serverId);
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.warn("Presence markOffline failed for uuid={}", uuid, e);
        }
    }

    private void heartbeat() {
        if (!enabled || server == null) return;
        server.execute(() -> {
            List<UUID> localOnline = new ArrayList<>();
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                localOnline.add(p.getUUID());
            }
            List<UUID> pruned = pruneRemoteCache();
            for (UUID uuid : pruned) {
                pushPresence(uuid);
            }
            if (localOnline.isEmpty()) return;
            redisExec.submit(() -> runHeartbeatRedis(localOnline));
        });
    }

    private void runHeartbeatRedis(List<UUID> localOnline) {
        String serverId = RedisSync.getInstance().getServerId();
        JedisPool pool = RedisSync.getInstance().getPool();
        if (pool == null) return;
        try (Jedis jedis = pool.getResource()) {
            for (UUID uuid : localOnline) {
                String key = REDIS_KEY_PREFIX + uuid;
                jedis.setex(key, TTL_SECONDS, serverId);
                jedis.publish(CHANNEL, serverId + "|online|" + uuid);
            }
            FTBQuestsSync.LOGGER.debug("Presence heartbeat: {} players", localOnline.size());
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.warn("Presence heartbeat failed", e);
        }
    }

    private List<UUID> pruneRemoteCache() {
        long cutoff = System.currentTimeMillis() - (TTL_SECONDS * 1000L) - GRACE_MS;
        List<UUID> removed = new ArrayList<>();
        remoteCache.entrySet().removeIf(entry -> {
            if (entry.getValue().expiresAt < cutoff) {
                removed.add(entry.getKey());
                return true;
            }
            return false;
        });
        return removed;
    }

    private void subscribeLoop() {
        JedisPool pool = RedisSync.getInstance().getPool();
        if (pool == null) return;
        Jedis subscriberConn = null;
        while (enabled && !Thread.currentThread().isInterrupted()) {
            try {
                subscriberConn = pool.getResource();
                subscriberConn.subscribe(new JedisPubSub() {
                    @Override
                    public void onMessage(String channel, String message) {
                        handleRemote(message);
                    }
                }, CHANNEL);
            } catch (Exception e) {
                if (!enabled) continue;
                FTBQuestsSync.LOGGER.warn("Presence subscriber error, reconnecting in 5s", e);
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
                    FTBQuestsSync.LOGGER.debug("Presence subscriber close failed", e);
                }
                subscriberConn = null;
            }
        }
    }

    private void handleRemote(String message) {
        try {
            String[] parts = message.split("\\|", 3);
            if (parts.length != 3) return;
            String sourceServer = parts[0];
            String action = parts[1];
            UUID uuid = UUID.fromString(parts[2]);
            String serverId = RedisSync.getInstance().getServerId();
            if (serverId.equals(sourceServer)) return;

            long now = System.currentTimeMillis();
            if ("online".equals(action)) {
                remoteCache.put(uuid, new RemotePresence(sourceServer, now + TTL_SECONDS * 1000L));
                if (server != null) {
                    server.execute(() -> pushPresence(uuid));
                }
            } else if ("offline".equals(action)) {
                remoteCache.remove(uuid);
                if (server != null) {
                    server.execute(() -> pushPresence(uuid));
                }
            }
            FTBQuestsSync.LOGGER.debug("Presence remote: {} {} from {}", uuid, action, sourceServer);
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.warn("Bad presence message: {}", message, e);
        }
    }

    private void pushPresence(UUID uuid) {
        try {
            var mgr = FTBTeamsAPI.api().getManager();
            var teamOpt = mgr.getPlayerTeamForPlayerID(uuid);
            if (teamOpt.isPresent() && teamOpt.get() instanceof PlayerTeam pt) {
                pt.updatePresence();
                FTBQuestsSync.LOGGER.debug("Pushed presence update for uuid={}", uuid);
            }
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.debug("pushPresence failed for uuid={}", uuid, e);
        }
    }

    public static boolean isOnlineAnywhere(UUID uuid) {
        if (uuid == null) return false;
        PresenceSync inst = INSTANCE;
        if (inst.server != null && inst.server.getPlayerList().getPlayer(uuid) != null) {
            return true;
        }
        RemotePresence rp = inst.remoteCache.get(uuid);
        if (rp == null) return false;
        return System.currentTimeMillis() < rp.expiresAt;
    }

    private static final class RemotePresence {
        final String serverId;
        final long expiresAt;

        RemotePresence(String serverId, long expiresAt) {
            this.serverId = serverId;
            this.expiresAt = expiresAt;
        }
    }
}
