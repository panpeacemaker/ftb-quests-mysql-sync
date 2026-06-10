package net.agrarius.ftbquestssync.migration;

import dev.ftb.mods.ftblibrary.snbt.SNBT;
import dev.ftb.mods.ftblibrary.snbt.SNBTCompoundTag;
import dev.ftb.mods.ftbquests.quest.ServerQuestFile;
import dev.ftb.mods.ftbquests.quest.reward.Reward;
import net.agrarius.ftbquestssync.Config;
import net.agrarius.ftbquestssync.FTBQuestsSync;
import net.agrarius.ftbquestssync.MySQLBackend;
import net.minecraft.nbt.CompoundTag;

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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One-shot migrator that pulls every player's legacy per-player quest export
 * (a per-player ZIP containing a {@code quests.snbt} entry) from Redis
 * (preferred) or a source MariaDB, converts it into the canonical FTB Quests
 * {@code TeamData} NBT format, and UPSERTs it into {@code ftbquests_teamdata}
 * so the rest of this mod's live sync machinery can take over from the first
 * server start.
 *
 * Idempotent: the underlying {@code saveTeamDataInternal} short-circuits when
 * the new payload's hash matches the existing row's hash, so a re-run, a
 * second peer, or a marker reset cannot bump the revision counter on
 * identical content.
 */
public final class LegacyQuestMigrator {

    private static final Path DEFAULT_MARKER_DIR = Path.of("/opt/agrarius/config");

    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);

    private static final ExecutorService MIGRATION_EXECUTOR = Executors.newSingleThreadExecutor(
            new MigrationThreadFactory());

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

    /**
     * Boot-time entry point. Schedules the migration on a background daemon
     * thread so a slow source (large Redis dump, slow MariaDB, huge ZIP)
     * never stalls server start. The server thread returns to its caller
     * before any source read happens.
     */
    public static void runIfNeededAsync() {
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
        MigrationOptions opts = MigrationOptions.fromConfig();
        FTBQuestsSync.LOGGER.info("Scheduling auto migration: dryRun={} maxPlayers={} serverIdTag={}",
                opts.dryRun, opts.maxPlayers, opts.serverIdTag);
        MIGRATION_EXECUTOR.execute(() -> runNow(opts));
    }

    /**
     * Manual entry point used by the {@code /ftbsync migrate} operator
     * command. Skips the {@code runOnServerId} whitelist and the marker
     * pre-check; the operator is presumed to know what they are doing.
     * Guarded by {@link #RUNNING} so two concurrent invocations (e.g.
     * the operator double-tapping the command) do not race on the
     * marker file or on the {@code Config} statics.
     */
    public static void runNow(MigrationOptions opts) {
        if (!RUNNING.compareAndSet(false, true)) {
            FTBQuestsSync.LOGGER.warn("Legacy quest migration already in progress; ignoring duplicate run");
            return;
        }
        try {
            runNowInternal(opts);
        } finally {
            RUNNING.set(false);
        }
    }

    private static void runNowInternal(MigrationOptions opts) {
        long t0 = System.currentTimeMillis();
        AtomicInteger players = new AtomicInteger();
        AtomicInteger upserts = new AtomicInteger();
        AtomicInteger skippedNoSnbt = new AtomicInteger();
        AtomicInteger skippedDuplicate = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();

        UuidRemapper remapper = opts.remapUuids
                ? UuidRemapper.load(Path.of(opts.usercachePath))
                : UuidRemapper.empty();
        AtomicInteger remapped = new AtomicInteger();
        AtomicInteger remapMissed = new AtomicInteger();
        AtomicInteger bindings = new AtomicInteger();
        AtomicInteger rankRows = new AtomicInteger();
        AtomicInteger claimScopes = new AtomicInteger();

        List<PlayerBlobSource> sources = new ArrayList<>();
        sources.add(new RedisBlobSource());
        if (opts.mysqlHost != null && !opts.mysqlHost.isBlank()) {
            sources.add(new MysqlBlobSource());
        }

        // First pass: dedup by player UUID, then by teamId.
        Map<UUID, byte[]> blobs = new LinkedHashMap<>();
        for (PlayerBlobSource src : sources) {
            try {
                Map<UUID, byte[]> loaded = src.loadAll();
                FTBQuestsSync.LOGGER.info("Migration source {} returned {} blob(s)", src.describe(), loaded.size());
                for (Map.Entry<UUID, byte[]> e : loaded.entrySet()) {
                    blobs.putIfAbsent(e.getKey(), e.getValue());
                }
            } catch (Throwable ex) {
                FTBQuestsSync.LOGGER.warn("Migration source {} failed", src.describe(), ex);
            }
        }

        int cap = opts.maxPlayers > 0 ? Math.min(opts.maxPlayers, blobs.size()) : blobs.size();
        int processed = 0;
        boolean capReached = false;
        Map<UUID, byte[]> teamMap = new LinkedHashMap<>();
        for (Map.Entry<UUID, byte[]> entry : blobs.entrySet()) {
            if (processed >= cap) {
                capReached = true;
                break;
            }
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
                parsed = applyUuidRemap(parsed, remapper, remapped, remapMissed);
                if (!parsed.isParty) {
                    int teamKeys = normalizeTeamRewardClaimKeys(parsed.tag);
                    if (teamKeys > 0) {
                        FTBQuestsSync.LOGGER.info(
                                "Migration team-reward claim keys normalized to NIL_UUID: player={} teamId={} keys={}",
                                player, parsed.teamId, teamKeys);
                    }
                }
                if (opts.dryRun) {
                    FTBQuestsSync.LOGGER.info(
                            "Migration [DRY-RUN] would upsert player={} teamId={} party={} partyName={} bytes={}",
                            player, parsed.teamId, parsed.isParty, parsed.partyName, snbt.length);
                    upserts.incrementAndGet();
                    if (!parsed.isParty) {
                        rankRows.addAndGet(RankProgressMigrator.migrate(parsed.teamId, parsed.tag, true));
                        claimScopes.addAndGet(RewardClaimScopeSeeder.seedFromBlob(
                                parsed.teamId, parsed.teamId, parsed.tag, opts.serverIdTag, true));
                    }
                    continue;
                }
                // Multiple players resolving to the same team (party) are
                // collapsed to a single write; the first parse wins.
                if (teamMap.putIfAbsent(parsed.teamId, new byte[]{1}) != null) {
                    skippedDuplicate.incrementAndGet();
                    FTBQuestsSync.LOGGER.info(
                            "Migration: player {} resolves to teamId={} already covered by an earlier export; skipping (party member dedup)",
                            player, parsed.teamId);
                    continue;
                }
                MySQLBackend.SaveResult res = MySQLBackend.getInstance().saveTeamDataMigration(
                        parsed.teamId, parsed.tag, opts.serverIdTag);
                upserts.incrementAndGet();
                FTBQuestsSync.LOGGER.info(
                        "Migration upsert: player={} teamId={} party={} partyName={} bytes={} revision={} hash={} serverIdTag={}",
                        player, parsed.teamId, parsed.isParty, parsed.partyName,
                        snbt.length, res.revision, res.hashHex, opts.serverIdTag);
                writeSoloTeamBindings(parsed, bindings);
                if (!parsed.isParty) {
                    rankRows.addAndGet(RankProgressMigrator.migrate(parsed.teamId, parsed.tag, false));
                    claimScopes.addAndGet(RewardClaimScopeSeeder.seedFromBlob(
                            parsed.teamId, parsed.teamId, parsed.tag, opts.serverIdTag, false));
                }
            } catch (Throwable ex) {
                // Catch Throwable (incl. OutOfMemoryError) so a single bad
                // blob does not kill the daemon migration thread and leave
                // the rest of the run unprocessed.
                failed.incrementAndGet();
                FTBQuestsSync.LOGGER.warn("Migration failed for player {}", player, ex);
            }
        }

        long ms = System.currentTimeMillis() - t0;
        FTBQuestsSync.LOGGER.info(
                "Legacy quest migration done: playersSeen={} upserts={} remappedUuids={} remapMissed={} soloBindings={} rankRows={} claimScopes={} skippedNoSnbt={} skippedDuplicate={} failed={} elapsedMs={} dryRun={} capReached={}",
                players.get(), upserts.get(), remapped.get(), remapMissed.get(), bindings.get(), rankRows.get(), claimScopes.get(),
                skippedNoSnbt.get(), skippedDuplicate.get(), failed.get(), ms, opts.dryRun, capReached);

        FTBQuestsSync.LOGGER.info(
                "===== MIGRATION SUMMARY ===== players={} migrated={} uuidRemapped={} uuidMissedNoUsercache={} rankRows={} claimScopes={} failed={} {}",
                players.get(), upserts.get(), remapped.get(), remapMissed.get(), rankRows.get(), claimScopes.get(), failed.get(),
                opts.dryRun ? "(DRY-RUN, nothing written)" : "");
        if (remapMissed.get() > 0) {
            FTBQuestsSync.LOGGER.warn(
                    "===== {} player(s) had NO usercache entry: their data stays under the legacy offline UUID and they will NOT see it until they appear in usercache.json. Refresh usercache and re-run migration for those players. =====",
                    remapMissed.get());
        }

        if (!opts.dryRun && failed.get() == 0 && !capReached) {
            // Only write the marker when the run actually exhausted the
            // source list. A cap hit (maxPlayers < total) means the next
            // boot should continue from where we left off.
            if (upserts.get() == 0 && blobs.isEmpty()) {
                FTBQuestsSync.LOGGER.warn(
                        "Migration source returned ZERO blobs for serverId={}; marker NOT written so a later run can pick up the source",
                        Config.getServerId());
            } else {
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
            }
        } else if (!opts.dryRun && failed.get() > 0) {
            FTBQuestsSync.LOGGER.warn("Migration finished with {} failure(s); marker NOT written so a restart will retry", failed.get());
        } else if (!opts.dryRun && capReached) {
            FTBQuestsSync.LOGGER.warn("Migration hit maxPlayers={} cap (more players remain); marker NOT written so a restart will continue",
                    opts.maxPlayers);
        }
    }

    /**
     * Writes the team_info + self-membership rows for a migrated SOLO player so
     * the very first login resolves teamId=playerUuid and loads the migrated
     * quest data immediately. Without this, the login fallback only creates the
     * membership row asynchronously, so the migrated data would not appear until
     * the player's SECOND login. Party exports are skipped: FTB Teams owns party
     * membership and materializes it on login.
     */
    private static void writeSoloTeamBindings(ParsedExport parsed, AtomicInteger bindings) {
        if (parsed.isParty) {
            return;
        }
        UUID teamId = parsed.teamId;
        String displayName = parsed.tag.getString("name");
        int hashIdx = displayName.indexOf('#');
        if (hashIdx >= 0) {
            displayName = displayName.substring(0, hashIdx);
        }
        if (displayName.isBlank()) {
            displayName = teamId.toString();
        }
        MySQLBackend db = MySQLBackend.getInstance();
        db.upsertTeamInfo(teamId, "PLAYER", displayName, teamId, "0");
        db.upsertMembership(teamId, teamId, "OWNER");
        bindings.incrementAndGet();
    }

    /**
     * Rewrites a solo export's player/team UUID to the player's current UUID
     * from the usercache when the two differ. Party exports are left untouched:
     * a party UUID is not a player UUID and has no usercache entry; FTB Teams
     * materializes party membership on first login.
     */
    private static ParsedExport applyUuidRemap(ParsedExport parsed, UuidRemapper remapper,
                                               AtomicInteger remapped, AtomicInteger remapMissed) {
        if (parsed.isParty) {
            return parsed;
        }
        String displayName = parsed.tag.getString("name");
        int hashIdx = displayName.indexOf('#');
        if (hashIdx >= 0) {
            displayName = displayName.substring(0, hashIdx);
        }
        UUID current = remapper.currentUuidFor(displayName).orElse(null);
        if (current == null) {
            remapMissed.incrementAndGet();
            FTBQuestsSync.LOGGER.warn(
                    "Migration UUID remap MISS: name={} legacyUuid={} not found in usercache; "
                    + "data stays under legacy uuid and the player will not see it until they appear in the usercache",
                    displayName, parsed.teamId);
            return parsed;
        }
        if (current.equals(parsed.teamId)) {
            return parsed;
        }
        CompoundTag rewritten = parsed.tag.copy();
        rewritten.putString("uuid", current.toString());
        int rewrittenClaims = remapClaimedRewardKeys(rewritten, parsed.teamId, current);
        FTBQuestsSync.LOGGER.info(
                "Migration UUID remap: name={} legacyUuid={} -> currentUuid={} claimedRewardKeys={}",
                displayName, parsed.teamId, current, rewrittenClaims);
        remapped.incrementAndGet();
        return new ParsedExport(rewritten, current, false, parsed.partyName);
    }

    /**
     * Rewrites the per-player UUID prefix inside {@code claimed_rewards} keys.
     * FTB Quests stores reward-claim keys as "&lt;dashless-player-uuid&gt;:&lt;reward-id&gt;"
     * and checks claims by the player's CURRENT uuid. Without rewriting the
     * prefix from the legacy uuid to the current one, every reward appears
     * unclaimed after migration even though the player already claimed it.
     */
    private static int remapClaimedRewardKeys(CompoundTag tag, UUID oldId, UUID newId) {
        if (!tag.contains("claimed_rewards", 10)) {
            return 0;
        }
        CompoundTag claimed = tag.getCompound("claimed_rewards");
        String oldPrefix = oldId.toString().replace("-", "");
        String newPrefix = newId.toString().replace("-", "");
        CompoundTag remappedClaims = new CompoundTag();
        int changed = 0;
        for (String key : claimed.getAllKeys()) {
            String newKey = key;
            if (key.startsWith(oldPrefix + ":")) {
                newKey = newPrefix + key.substring(oldPrefix.length());
                changed++;
            }
            remappedClaims.put(newKey, claimed.get(key).copy());
        }
        tag.put("claimed_rewards", remappedClaims);
        return changed;
    }

    /**
     * Rewrites the UUID prefix of {@code claimed_rewards} keys that belong to
     * team rewards to {@code NIL_UUID}.
     *
     * <p>The legacy mod stored every claim (team or player) under the player's
     * uuid, but vanilla FTB Quests keys a TEAM reward claim under
     * {@code QuestKey.forReward}'s {@code Util.NIL_UUID}. The client GUI reads
     * the claim state with that key, so without this normalization a migrated
     * team reward shows the claim button even though the server-side dedup
     * guard already refuses the re-claim. Needs the loaded quest file to tell
     * team rewards apart; unknown reward ids are left untouched.
     */
    private static int normalizeTeamRewardClaimKeys(CompoundTag tag) {
        if (!tag.contains("claimed_rewards", 10)) {
            return 0;
        }
        ServerQuestFile file = ServerQuestFile.INSTANCE;
        if (file == null) {
            return 0;
        }
        CompoundTag claimed = tag.getCompound("claimed_rewards");
        String nilPrefix = net.minecraft.Util.NIL_UUID.toString().replace("-", "");
        CompoundTag normalized = new CompoundTag();
        int changed = 0;
        for (String key : claimed.getAllKeys()) {
            int colon = key.lastIndexOf(':');
            String newKey = key;
            if (colon > 0) {
                long rewardId = parseHexId(key.substring(colon + 1));
                Reward reward = rewardId == 0L ? null : file.getReward(rewardId);
                if (reward != null && reward.isTeamReward()) {
                    String candidate = nilPrefix + key.substring(colon);
                    if (!candidate.equals(key)) {
                        newKey = candidate;
                        changed++;
                    }
                }
            }
            normalized.put(newKey, claimed.get(key).copy());
        }
        tag.put("claimed_rewards", normalized);
        return changed;
    }

    private static long parseHexId(String hex) {
        try {
            return Long.parseUnsignedLong(hex, 16);
        } catch (NumberFormatException e) {
            return 0L;
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
            // The team id is the blob's own uuid field. For a solo export it
            // equals the player uuid (the DB-key uuid); for a party export the
            // legacy mod serialized the shared party TeamData, whose uuid is the
            // distinct party uuid. So a uuid that differs from the DB-key player
            // uuid means this is a party export. The name suffix
            // ("<display>#<shortid>") is only a display hint and may be an 8-char
            // short id, so it is not authoritative for the team id.
            UUID teamId = playerUuid;
            boolean isParty = !playerUuid.equals(fallbackPlayerUuid);
            String partyName = null;
            String nameField = vanilla.getString("name");
            int hashIdx = nameField.indexOf('#');
            if (isParty && hashIdx > 0) {
                partyName = nameField.substring(0, hashIdx);
            }
            return new ParsedExport(vanilla, teamId, isParty, partyName);
        } catch (Throwable e) {
            FTBQuestsSync.LOGGER.warn("SNBT parse failed ({} bytes)", snbtBytes.length, e);
            return null;
        }
    }

    private static final class MigrationThreadFactory implements ThreadFactory {
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "FTBQuestsSync-Migration");
            t.setDaemon(true);
            return t;
        }
    }
}
