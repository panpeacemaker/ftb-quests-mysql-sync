package net.agrarius.ftbquestssync.migration;

import net.agrarius.ftbquestssync.Config;
import net.agrarius.ftbquestssync.FTBQuestsSync;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Reads legacy player blobs from Redis. Key shape (default):
 * {@code <keyPrefix><uuid-with-or-without-dashes>}.
 *
 * The key suffix is parsed as a UUID; anything that does not parse is
 * skipped (logged at debug and ignored).
 */
public final class RedisBlobSource implements PlayerBlobSource {

    private final String host;
    private final int port;
    private final String password;
    private final int db;
    private final String prefix;

    public RedisBlobSource() {
        this(Config.getRedisHost(), Config.getRedisPort(), Config.getRedisPassword(),
                Config.migrationRedisDb, Config.migrationRedisKeyPrefix);
    }

    public RedisBlobSource(String host, int port, String password, int db, String prefix) {
        this.host = host;
        this.port = port;
        this.password = password;
        this.db = db;
        this.prefix = prefix;
    }

    @Override
    public Map<UUID, byte[]> loadAll() throws Exception {
        Map<UUID, byte[]> out = new HashMap<>();
        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(2);
        poolConfig.setMaxIdle(1);
        poolConfig.setMaxWait(java.time.Duration.ofSeconds(5));
        try (JedisPool pool = new JedisPool(poolConfig, host, port, 5000);
             Jedis jedis = pool.getResource()) {
            if (password != null && !password.isBlank()) {
                jedis.auth(password);
            }
            jedis.select(db);
            Set<String> keys = new LinkedHashSet<>(jedis.keys(prefix + "*"));
            for (String key : keys) {
                String suffix = key.substring(prefix.length());
                UUID uuid = parseUuid(suffix);
                if (uuid == null) {
                    FTBQuestsSync.LOGGER.debug("Migration: skipping non-UUID Redis key {}", key);
                    continue;
                }
                byte[] raw = jedis.get(key.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                if (raw == null || raw.length == 0) continue;
                out.put(uuid, raw);
            }
        }
        return out;
    }

    @Override
    public String describe() {
        return "redis://" + host + ":" + port + " db=" + db + " prefix=" + prefix;
    }

    private static UUID parseUuid(String s) {
        String t = s.trim();
        if (t.isEmpty()) return null;
        try {
            return t.contains("-") ? UUID.fromString(t) : UUID.fromString(
                    t.substring(0, 8) + "-" + t.substring(8, 12) + "-" + t.substring(12, 16) + "-"
                            + t.substring(16, 20) + "-" + t.substring(20));
        } catch (IndexOutOfBoundsException | IllegalArgumentException e) {
            return null;
        }
    }
}
