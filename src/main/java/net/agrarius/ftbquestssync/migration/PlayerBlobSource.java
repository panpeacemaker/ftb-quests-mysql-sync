package net.agrarius.ftbquestssync.migration;

import java.util.Map;
import java.util.UUID;

/**
 * Source of legacy per-player data blobs.
 *
 * Each implementation returns a map of player UUID → raw zip bytes containing
 * the legacy per-player quest export. Implementations are read-only and may be
 * queried exactly once per migration run.
 */
public interface PlayerBlobSource {

    /**
     * @return immutable snapshot keyed by player UUID. May be empty but never null.
     * @throws Exception if the source is unreachable or misconfigured; the caller
     *                   will log and continue with the next source.
     */
    Map<UUID, byte[]> loadAll() throws Exception;

    /**
     * Called for each (player, blob) pair as it is read from the source.
     * Returning {@code false} stops iteration early (e.g. maxPlayers cap hit).
     *
     * <p>The implementation returns the count of blobs that were skipped
     * because they exceeded {@code maxBlobBytes}.
     */
    @FunctionalInterface
    interface BlobConsumer {
        boolean accept(UUID player, byte[] blob);
    }

    /**
     * Iterates over the source without loading everything into memory first.
     * The default implementation delegates to {@link #loadAll()}.
     *
     * @return number of blobs skipped because they were oversized
     */
    default int forEach(BlobConsumer consumer) throws Exception {
        int skippedOversized = 0;
        for (Map.Entry<UUID, byte[]> e : loadAll().entrySet()) {
            if (!consumer.accept(e.getKey(), e.getValue())) {
                break;
            }
        }
        return skippedOversized;
    }

    /**
     * Short human label for logs (e.g. "redis://host:6379 db=0 prefix=…").
     */
    String describe();
}
