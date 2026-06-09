package net.agrarius.ftbquestssync.migration;

import dev.ftb.mods.ftblibrary.snbt.SNBT;
import dev.ftb.mods.ftblibrary.snbt.SNBTCompoundTag;
import net.agrarius.ftbquestssync.Config;
import net.agrarius.ftbquestssync.FTBQuestsSync;
import net.agrarius.ftbquestssync.MySQLBackend;
import net.minecraft.nbt.CompoundTag;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One-shot migrator that pulls every player's legacy per-player quest export
 * (a per-player ZIP containing a {@code quests.snbt} entry) from Redis
 * (preferred) or a source MariaDB, converts it into the canonical FTB Quests
 * {@code TeamData} NBT format, and UPSERTs it into {@code ftbquests_teamdata}
 * so the rest of this mod's live sync machinery can take over from the first
 * server start.
 *
 * Idempotent: re-runs only touch rows whose contents actually differ
 * (revision+1 on the mod's UPSERT path is the canonical dedup signal).
 */
public final class LegacyQuestMigrator {

    private static final Path DEFAULT_MARKER_DIR = Path.of("/opt/agrarius/config");

    private static final java.util.concurrent.atomic.AtomicBoolean RUNNING = new java.util.concurrent.atomic.AtomicBoolean(false);

    private LegacyQuestMigrator() {
    }

    private static Path markerDir() {
        String configured = Config.migrationMarkerDir;
        if (configured == null || configured.isBlank()) {
            return DEFAULT_MARKER_DIR;
        }
        return Path.of(configured);
    }

    private static Path markerPath() {
        String sid = Config.getServerId() == null ? "unknown" : Config.getServerId();
        // Per-server marker so a multi-server cluster never races on the same
        // file or silently no-ops a peer that hasn't run yet.
        return markerDir().resolve("ftbquestssync.migration.done." + sid);
    }

    public static boolean isRunning() {
        return RUNNING.get();
    }

    public static void runIfNeeded() {
        if (!Config.migrationRunOnBoot) {
            return;
        }
        if (!Config.migrationRunOnServerId.isEmpty()
                && !Config.migrationRunOnServerId.equals(Config.getServerId())) {
            FTBQuestsSync.LOGGER.info(
                    "Legacy quest migration skipped: this serverId={} does not match runOnServerId={}",
                    Config.getServerId(), Config.migrationRunOnServerId);
            return;
        }
        Path marker = markerPath();
        if (Files.exists(marker)) {
            FTBQuestsSync.LOGGER.info("Legacy quest migration already completed for serverId={} (marker at {}); skipping",
                    Config.getServerId(), marker);
            return;
        }
        if (!MySQLBackend.getInstance().isAvailable()) {
            FTBQuestsSync.LOGGER.warn("Legacy quest migration skipped: target MySQL not available");
            return;
        }
        runNow();
    }

    /**
     * Manual entry point used by the {@code /ftbsync migrate} operator
     * command. Skips the {@code runOnServerId} whitelist and the marker
     * pre-check; the operator is presumed to know what they are doing.
     * Guarded by {@link #RUNNING} so two concurrent invocations (e.g.
     * the operator double-tapping the command) do not race on the
     * marker file or on the {@code Config} statics.
     */
    public static void runNow() {
        if (!RUNNING.compareAndSet(false, true)) {
            FTBQuestsSync.LOGGER.warn("Legacy quest migration already in progress; ignoring duplicate run");
            return;
        }
        try {
            runNowInternal();
        } finally {
            RUNNING.set(false);
        }
    }

    private static void runNowInternal() {
        long t0 = System.currentTimeMillis();
        AtomicInteger players = new AtomicInteger();
        AtomicInteger upserts = new AtomicInteger();
        AtomicInteger skippedNoSnbt = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();

        // Snapshot mutable config into locals so a concurrent
        // /ftbsync migrate invocation cannot change them mid-run.
        final boolean dryRun = Config.migrationDryRun;
        final int maxPlayers = Config.migrationMaxPlayers;
        final String mysqlHost = Config.migrationSourceMysqlHost;
        final String migrationServerIdTag = Config.migrationServerIdTag;

        List<PlayerBlobSource> sources = new ArrayList<>();
        sources.add(new RedisBlobSource());
        if (mysqlHost != null && !mysqlHost.isBlank()) {
            sources.add(new MysqlBlobSource());
        }

        Map<UUID, byte[]> blobs = new LinkedHashMap<>();
        for (PlayerBlobSource src : sources) {
            try {
                Map<UUID, byte[]> loaded = src.loadAll();
                FTBQuestsSync.LOGGER.info("Migration source {} returned {} blob(s)", src.describe(), loaded.size());
                for (Map.Entry<UUID, byte[]> e : loaded.entrySet()) {
                    blobs.putIfAbsent(e.getKey(), e.getValue());
                }
            } catch (Exception ex) {
                FTBQuestsSync.LOGGER.warn("Migration source {} failed: {}", src.describe(), ex.toString());
            }
        }

        int cap = maxPlayers > 0 ? Math.min(maxPlayers, blobs.size()) : blobs.size();
        int processed = 0;
        for (Map.Entry<UUID, byte[]> entry : blobs.entrySet()) {
            if (processed >= cap) break;
            processed++;
            UUID player = entry.getKey();
            byte[] zip = entry.getValue();
            players.incrementAndGet();
            try {
                byte[] snbt = ZipSnbtExtractor.extractQuestsSnbt(zip);
                if (snbt == null) {
                    skippedNoSnbt.incrementAndGet();
                    FTBQuestsSync.LOGGER.debug("Migration: player {} has no quests.snbt entry, skipping", player);
                    continue;
                }
                ParsedExport parsed = snbtToCompoundTag(snbt, player);
                if (parsed == null) {
                    skippedNoSnbt.incrementAndGet();
                    continue;
                }
                if (dryRun) {
                    FTBQuestsSync.LOGGER.info(
                            "Migration [DRY-RUN] would upsert player={} teamId={} party={} partyName={} bytes={}",
                            player, parsed.teamId, parsed.isParty, parsed.partyName, snbt.length);
                    upserts.incrementAndGet();
                    continue;
                }
                MySQLBackend.SaveResult res = MySQLBackend.getInstance().saveTeamDataMigration(
                        parsed.teamId, parsed.tag, migrationServerIdTag);
                upserts.incrementAndGet();
                FTBQuestsSync.LOGGER.info(
                        "Migration upsert: player={} teamId={} party={} partyName={} bytes={} revision={} hash={} serverIdTag={}",
                        player, parsed.teamId, parsed.isParty, parsed.partyName,
                        snbt.length, res.revision, res.hashHex, migrationServerIdTag);
            } catch (Exception ex) {
                failed.incrementAndGet();
                FTBQuestsSync.LOGGER.warn("Migration failed for player {}", player, ex);
            }
        }

        long ms = System.currentTimeMillis() - t0;
        FTBQuestsSync.LOGGER.info(
                "Legacy quest migration done: playersSeen={} upserts={} skippedNoSnbt={} failed={} elapsedMs={} dryRun={}",
                players.get(), upserts.get(), skippedNoSnbt.get(), failed.get(), ms, dryRun);

        if (!dryRun && failed.get() == 0 && upserts.get() > 0) {
            Path marker = markerPath();
            try {
                Path parent = marker.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.createFile(marker);
                FTBQuestsSync.LOGGER.info("Migration marker written to {}", marker);
            } catch (IOException ioe) {
                FTBQuestsSync.LOGGER.warn("Could not write migration marker {}", marker, ioe);
            }
        } else if (!dryRun && failed.get() > 0) {
            FTBQuestsSync.LOGGER.warn("Migration finished with {} failure(s); marker NOT written so a restart will retry", failed.get());
        }
    }

    /**
     * Parsed view of the legacy {@code quests.snbt} entry: the
     * re-packed {@link CompoundTag} plus the resolved FTB team id and
     * the original party name (if the export belonged to a party
     * rather than a solo team).
     */
    private static final class ParsedExport {
        final CompoundTag tag;
        final UUID teamId;
        final boolean isParty;
        final String partyName;

        ParsedExport(CompoundTag tag, UUID teamId, boolean isParty, String partyName) {
            this.tag = tag;
            this.teamId = teamId;
            this.isParty = isParty;
            this.partyName = partyName;
        }
    }

    private static ParsedExport snbtToCompoundTag(byte[] snbtBytes, UUID fallbackPlayerUuid) {
        String text = new String(snbtBytes, StandardCharsets.UTF_8).trim();
        if (text.isEmpty()) return null;
        try {
            List<String> lines = Arrays.asList(text.split("\\R", -1));
            SNBTCompoundTag snbt = SNBT.readLines(lines);
            // SNBTCompoundTag IS a CompoundTag, but to guarantee the writeCompressed
            // path we re-pack into a vanilla CompoundTag (NbtCompat only writes
            // net.minecraft.nbt.CompoundTag fields, not SNBT properties).
            CompoundTag vanilla = new CompoundTag();
            vanilla.merge(snbt);
            // Some legacy exports have the player's UUID as a 32-char hex without
            // dashes; rewrite to canonical 8-4-4-4-12 form so the FTB Quests TeamData
            // loader accepts it.
            UUID playerUuid = fallbackPlayerUuid;
            if (vanilla.contains("uuid", 8)) {
                String u = vanilla.getString("uuid").replace("-", "");
                if (u.length() == 32) {
                    String canonical = u.substring(0, 8) + "-" + u.substring(8, 12) + "-"
                            + u.substring(12, 16) + "-" + u.substring(16, 20) + "-" + u.substring(20);
                    vanilla.putString("uuid", canonical);
                    try {
                        playerUuid = UUID.fromString(canonical);
                    } catch (IllegalArgumentException ignored) { }
                }
            } else {
                vanilla.putString("uuid", fallbackPlayerUuid.toString());
            }
            // FTB Quests name field is "<display>#<team-uuid>". For solo teams
            // the team uuid equals the player uuid; for parties it is a
            // distinct uuid. Resolve the team uuid from the suffix if present.
            UUID teamId = playerUuid;
            boolean isParty = false;
            String partyName = null;
            String nameField = vanilla.getString("name");
            int hashIdx = nameField.indexOf('#');
            if (hashIdx >= 0 && hashIdx + 1 < nameField.length()) {
                String suffix = nameField.substring(hashIdx + 1);
                if (suffix.length() == 32 && suffix.matches("[0-9a-fA-F]{32}")) {
                    try {
                        teamId = UUID.fromString(suffix.substring(0, 8) + "-" + suffix.substring(8, 12) + "-"
                                + suffix.substring(12, 16) + "-" + suffix.substring(16, 20) + "-"
                                + suffix.substring(20));
                        isParty = !teamId.equals(playerUuid);
                        partyName = nameField.substring(0, hashIdx);
                    } catch (IllegalArgumentException ignored) { }
                } else if (suffix.length() == 36) {
                    try {
                        teamId = UUID.fromString(suffix);
                        isParty = !teamId.equals(playerUuid);
                        partyName = nameField.substring(0, hashIdx);
                    } catch (IllegalArgumentException ignored) { }
                }
            }
            return new ParsedExport(vanilla, teamId, isParty, partyName);
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.warn("SNBT parse failed ({} bytes): {}", snbtBytes.length, e.toString());
            return null;
        }
    }
}
