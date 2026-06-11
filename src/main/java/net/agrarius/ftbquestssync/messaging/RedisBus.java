package net.agrarius.ftbquestssync.messaging;

import net.agrarius.ftbquestssync.Config;
import net.agrarius.ftbquestssync.FTBQuestsSync;

import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPubSub;
import redis.clients.jedis.Protocol;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Pure Redis transport: connection pool lifecycle, subscriber loop, and publish.
 *
 * <p>Contains no quest/chunk/team business logic. Inbound messages are handed
 * to the configured {@link MessageRouter} for fan-out to registered listeners.</p>
 */
public class RedisBus {

    private final MessageRouter router;
    private final String threadName;

    private JedisPool pool;
    private ExecutorService subscriberExec;
    private Jedis subscriberConn;
    private volatile boolean enabled;

    public RedisBus(MessageRouter router, String threadName) {
        this.router = router;
        this.threadName = threadName;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public JedisPool getPool() {
        return pool;
    }

    /**
     * Opens the Jedis pool and starts the subscriber thread.
     *
     * @return true if the pool was opened and ping succeeded
     */
    public boolean initialize() {
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
                Thread t = new Thread(r, threadName);
                t.setDaemon(true);
                return t;
            });
            subscriberExec.submit(this::subscribeLoop);
            return true;
        } catch (Exception e) {
            enabled = false;
            FTBQuestsSync.LOGGER.error("RedisBus init failed", e);
            return false;
        }
    }

    /**
     * Publishes a payload on the given channel.
     *
     * @param channel the Redis channel name
     * @param payload the raw message
     */
    public void publish(String channel, String payload) {
        if (!enabled || pool == null) return;
        try (Jedis jedis = pool.getResource()) {
            jedis.publish(channel, payload);
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.warn("RedisBus publish failed on channel={}", channel, e);
        }
    }

    public void shutdown() {
        enabled = false;
        try {
            if (subscriberConn != null) subscriberConn.disconnect();
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.debug("RedisBus subscriber disconnect during shutdown failed", e);
        }
        if (subscriberExec != null) subscriberExec.shutdownNow();
        if (pool != null) pool.close();
    }

    private void subscribeLoop() {
        while (enabled && !Thread.currentThread().isInterrupted()) {
            String[] channels = router.getChannels();
            if (channels.length == 0) {
                try {
                    Thread.sleep(1_000L);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
                continue;
            }
            try {
                subscriberConn = pool.getResource();
                subscriberConn.subscribe(new JedisPubSub() {
                    @Override
                    public void onMessage(String channel, String message) {
                        router.dispatch(channel, message);
                    }
                }, channels);
            } catch (Exception e) {
                if (!enabled) continue;
                FTBQuestsSync.LOGGER.warn("RedisBus subscriber error, reconnecting in 5s", e);
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
                    FTBQuestsSync.LOGGER.debug("RedisBus subscriber close failed", e);
                }
                subscriberConn = null;
            }
        }
    }
}
