package net.agrarius.ftbquestssync.migration;

import net.agrarius.ftbquestssync.config.Config;
import net.agrarius.ftbquestssync.FTBQuestsSync;
import net.agrarius.ftbquestssync.RedisSync;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Reads legacy player blobs from Redis. Key shape (default):
 * {@code <keyPrefix><uuid-with-or-without-dashes>}.
 *
 * Uses the mod's shared {@link RedisSync#getInstance() pool} to avoid
 * holding a second long-lived connection. Key listing is done with
 * {@code SCAN} (cursor-based) rather than {@code KEYS} so a large legacy
 * data set does not block Redis. Per-blob size is bounded by
 * {@code migrationMaxBlobBytes} so a single malformed entry cannot
 * exhaust heap.
 */
public final class RedisBlobSource implements PlayerBlobSource {

    private final int db;
    private final String prefix;
    private final int maxBlobBytes;

    public RedisBlobSource() {
        this(Config.migrationRedisDb, Config.migrationRedisKeyPrefix, Config.migrationMaxBlobBytes);
    }

    public RedisBlobSource(int db, String prefix, int maxBlobBytes) {
        this.db = db;
        this.prefix = prefix == null ? "" : prefix;
        this.maxBlobBytes = maxBlobBytes;
    }

    @Override
    public Map<UUID, byte[]> loadAll() throws Exception {
        Map<UUID, byte[]> out = new HashMap<>();
        forEach((player, blob) -> {
            out.putIfAbsent(player, blob);
            return true;
        });
        return out;
    }

    @Override
    public int forEach(BlobConsumer consumer) throws Exception {
        int skippedOversized = 0;
        redis.clients.jedis.JedisPool pool = RedisSync.getInstance().getPool();
        try (Jedis jedis = pool.getResource()) {
            jedis.select(db);
            ScanParams params = new ScanParams().match(prefix + "*").count(256);
            String cursor = "0";
            do {
                ScanResult<String> scan = jedis.scan(cursor, params);
                for (String key : scan.getResult()) {
                    String suffix = key.substring(prefix.length());
                    UUID uuid = parseUuid(suffix);
                    if (uuid == null) {
                        FTBQuestsSync.LOGGER.debug("Migration: skipping non-UUID Redis key {}", key);
                        continue;
                    }
                    byte[] raw = jedis.get(key.getBytes(StandardCharsets.UTF_8));
                    if (raw == null || raw.length == 0) continue;
                    if (raw.length > maxBlobBytes) {
                        skippedOversized++;
                        FTBQuestsSync.LOGGER.warn(
                                "Migration: skipping oversize Redis blob key={} bytes={} (cap={})",
                                key, raw.length, maxBlobBytes);
                        continue;
                    }
                    if (!consumer.accept(uuid, raw)) {
                        return skippedOversized;
                    }
                }
                cursor = scan.getCursor();
            } while (!"0".equals(cursor));
        }
        return skippedOversized;
    }

    @Override
    public String describe() {
        return "redis db=" + db + " prefix=" + prefix;
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
