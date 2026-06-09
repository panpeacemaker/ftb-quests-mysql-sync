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
     * Short human label for logs (e.g. "redis://host:6379 db=0 prefix=…").
     */
    String describe();
}
