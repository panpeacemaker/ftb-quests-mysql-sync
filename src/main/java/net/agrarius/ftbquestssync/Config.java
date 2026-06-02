package net.agrarius.ftbquestssync;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Config loaded from /opt/agrarius/config/ftbquestssync-server.toml.
 * System properties (`-D…`) override TOML values.
 *
 * Server identity precedence (high → low):
 *   1. -Dftbquestssync.server.id
 *   2. TOML key `serverId`
 *   3. -Dftbquestssync.serverId  (legacy)
 *   4. -Dluckperms.server       (AT convention)
 *   5. "unknown-<random8hex>"    (UNSTABLE — restart generates new id;
 *                                  set #1 or #2 in production!)
 */
public final class Config {

    static String mysqlHost = "127.0.0.1";
    static int mysqlPort = 3306;
    static String mysqlDatabase = "agrarius_test";
    static String mysqlUsername = "agrarius";
    static String mysqlPassword = "";
    static int mysqlMaxPool = 4;
    static int mysqlMinIdle = 1;
    static boolean mysqlUseSsl = true;
    static boolean mysqlAllowPublicKeyRetrieval = false;

    static String redisHost = "127.0.0.1";
    static int redisPort = 6379;
    static String redisPassword = "";

    public static boolean syncQuests = true;
    public static boolean syncTeams = false;
    public static boolean syncChunks = false;
    public static boolean chunkSeedOnStart = false;
    public static String chunkCanonicalServerId = "agr1";
    public static boolean chunkForceLoadSync = true;
    public static boolean sendFullTeamData = true;
    public static boolean sendDeltaPackets = false;
    public static String conflictPolicy = "reload_remote";
    public static String policyMode = "blacklist";
    public static Set<Long> soloChapterIds = Set.of(0x3622ED01311E6763L, 0x67F6F5055518AC4FL);
    public static Set<Long> repeatableSoloChapterIds = Set.of();
    public static Set<Long> soloQuestIds = Set.of();
    public static Set<Long> soloTaskIds = Set.of();
    public static Set<Long> teamClaimChapterIds = Set.of(0x3CEC7F7BAD54E4C6L);
    public static Set<Long> teamSharedChapterIds = Set.of(
            0x330C551154E3E367L, 0x312B5D7DC7779EFEL, 0x2742F3918C76DA81L, 0x37CD2B0E77E895EDL,
            0x587F453EE1BAFB79L, 0x13B88A5F6D9187F8L, 0x75C4C0229F2D2798L, 0x459D60183D34C29EL,
            0x5617484BFC479624L, 0x450EB2B6DF25A9ECL, 0x05330DA4070C75C9L, 0x62A72817F92AC262L,
            0x4BF4EE11E23C71FCL, 0x10A89CD14C4DAF63L, 0x54A26707B4CD39CCL, 0x74FB988DE790BE0DL);
    public static boolean syncSoloProgressPerPlayer = true;
    public static boolean soloRewardsPerPlayer = true;
    public static boolean teamRewardsDedupGlobal = true;
    public static boolean rewardFailClosed = true;

    /** Resolved at {@link #reload()}; never null after first reload. */
    static String serverId;

    private Config() {
    }

    static void reload() {
        Map<String, String> toml = readToml(Path.of("/opt/agrarius/config/ftbquestssync-server.toml"));

        mysqlHost = prop("ftbquestssync.mysql.host", toml.getOrDefault("host", mysqlHost));
        mysqlPort = intProp("ftbquestssync.mysql.port", toml.getOrDefault("port", String.valueOf(mysqlPort)), mysqlPort);
        mysqlDatabase = prop("ftbquestssync.mysql.database", toml.getOrDefault("database", mysqlDatabase));
        mysqlUsername = prop("ftbquestssync.mysql.username", toml.getOrDefault("username", mysqlUsername));
        mysqlPassword = prop("ftbquestssync.mysql.password", toml.getOrDefault("password", mysqlPassword));
        mysqlMaxPool = intProp("ftbquestssync.mysql.maxPoolSize", toml.getOrDefault("maxPoolSize", String.valueOf(mysqlMaxPool)), mysqlMaxPool);
        mysqlMinIdle = intProp("ftbquestssync.mysql.minIdle", toml.getOrDefault("minIdle", String.valueOf(mysqlMinIdle)), mysqlMinIdle);
        mysqlUseSsl = boolProp("ftbquestssync.mysql.useSsl", toml.getOrDefault("useSsl", String.valueOf(mysqlUseSsl)), mysqlUseSsl);
        mysqlAllowPublicKeyRetrieval = boolProp("ftbquestssync.mysql.allowPublicKeyRetrieval", toml.getOrDefault("allowPublicKeyRetrieval", String.valueOf(mysqlAllowPublicKeyRetrieval)), mysqlAllowPublicKeyRetrieval);

        redisHost = prop("ftbquestssync.redis.host", toml.getOrDefault("redisHost", redisHost));
        redisPort = intProp("ftbquestssync.redis.port", toml.getOrDefault("redisPort", String.valueOf(redisPort)), redisPort);
        redisPassword = prop("ftbquestssync.redis.password", toml.getOrDefault("redisPassword", redisPassword));

        syncQuests = boolProp("ftbquestssync.syncQuests", toml.getOrDefault("syncQuests", String.valueOf(syncQuests)), syncQuests);
        syncTeams = boolProp("ftbquestssync.syncTeams", toml.getOrDefault("syncTeams", String.valueOf(syncTeams)), syncTeams);
        syncChunks = boolProp("ftbquestssync.syncChunks", toml.getOrDefault("syncChunks", String.valueOf(syncChunks)), syncChunks);
        chunkSeedOnStart = boolProp("ftbquestssync.chunkSeedOnStart", toml.getOrDefault("chunkSeedOnStart", String.valueOf(chunkSeedOnStart)), chunkSeedOnStart);
        chunkCanonicalServerId = prop("ftbquestssync.chunkCanonicalServerId", toml.getOrDefault("chunkCanonicalServerId", chunkCanonicalServerId)).trim();
        chunkForceLoadSync = boolProp("ftbquestssync.chunkForceLoadSync", toml.getOrDefault("chunkForceLoadSync", String.valueOf(chunkForceLoadSync)), chunkForceLoadSync);
        sendFullTeamData = boolProp("ftbquestssync.sendFullTeamData", toml.getOrDefault("sendFullTeamData", String.valueOf(sendFullTeamData)), sendFullTeamData);
        sendDeltaPackets = boolProp("ftbquestssync.sendDeltaPackets", toml.getOrDefault("sendDeltaPackets", String.valueOf(sendDeltaPackets)), sendDeltaPackets);
        conflictPolicy = prop("ftbquestssync.conflictPolicy", toml.getOrDefault("conflictPolicy", conflictPolicy));
        policyMode = prop("ftbquestssync.policy.mode", toml.getOrDefault("mode", policyMode)).trim().toLowerCase();
        if (!"blacklist".equals(policyMode) && !"whitelist".equals(policyMode)) {
            FTBQuestsSync.LOGGER.warn("Invalid policy mode '{}', using blacklist", policyMode);
            policyMode = "blacklist";
        }
        soloChapterIds = longSetProp("ftbquestssync.policy.soloChapterIds", toml.get("soloChapterIds"), soloChapterIds);
        repeatableSoloChapterIds = longSetProp("ftbquestssync.policy.repeatableSoloChapterIds", toml.get("repeatableSoloChapterIds"), repeatableSoloChapterIds);
        soloQuestIds = longSetProp("ftbquestssync.policy.soloQuestIds", toml.get("soloQuestIds"), soloQuestIds);
        soloTaskIds = longSetProp("ftbquestssync.policy.soloTaskIds", toml.get("soloTaskIds"), soloTaskIds);
        teamClaimChapterIds = longSetProp("ftbquestssync.policy.teamClaimChapterIds", toml.get("teamClaimChapterIds"), teamClaimChapterIds);
        teamSharedChapterIds = longSetProp("ftbquestssync.policy.teamSharedChapterIds", toml.get("teamSharedChapterIds"), teamSharedChapterIds);
        syncSoloProgressPerPlayer = boolProp("ftbquestssync.policy.syncSoloProgressPerPlayer", toml.getOrDefault("syncSoloProgressPerPlayer", String.valueOf(syncSoloProgressPerPlayer)), syncSoloProgressPerPlayer);
        soloRewardsPerPlayer = boolProp("ftbquestssync.policy.soloRewardsPerPlayer", toml.getOrDefault("soloRewardsPerPlayer", String.valueOf(soloRewardsPerPlayer)), soloRewardsPerPlayer);
        teamRewardsDedupGlobal = boolProp("ftbquestssync.policy.teamRewardsDedupGlobal", toml.getOrDefault("teamRewardsDedupGlobal", String.valueOf(teamRewardsDedupGlobal)), teamRewardsDedupGlobal);
        rewardFailClosed = boolProp("ftbquestssync.policy.rewardFailClosed", toml.getOrDefault("rewardFailClosed", String.valueOf(rewardFailClosed)), rewardFailClosed);

        String tomlServerId = toml.getOrDefault("serverId",
                System.getProperty("ftbquestssync.serverId",
                        System.getProperty("luckperms.server",
                                "unknown-" + UUID.randomUUID().toString().substring(0, 8))));
        serverId = prop("ftbquestssync.server.id", tomlServerId);

        FTBQuestsSync.LOGGER.info(
                "Config loaded: mysql={}:{} db={} user={} passwordSet={} mysqlSsl={} redis={}:{} redisPasswordSet={} serverId={} "
                + "syncQuests={} syncTeams={} syncChunks={} chunkSeedOnStart={} chunkCanonicalServerId={} chunkForceLoadSync={} "
                + "sendFullTeamData={} sendDeltaPackets={} conflictPolicy={}",
                mysqlHost, mysqlPort, mysqlDatabase, mysqlUsername,
                !mysqlPassword.isBlank(), mysqlUseSsl, redisHost, redisPort, !redisPassword.isBlank(), serverId,
                syncQuests, syncTeams, syncChunks, chunkSeedOnStart, chunkCanonicalServerId, chunkForceLoadSync,
                sendFullTeamData, sendDeltaPackets, conflictPolicy);
        FTBQuestsSync.LOGGER.info(
                "Policy loaded: mode={} soloChapterIds={} repeatableSoloChapterIds={} soloQuestIds={} soloTaskIds={} syncSoloProgressPerPlayer={} soloRewardsPerPlayer={} teamRewardsDedupGlobal={} rewardFailClosed={}",
                policyMode, soloChapterIds, repeatableSoloChapterIds, soloQuestIds, soloTaskIds,
                syncSoloProgressPerPlayer, soloRewardsPerPlayer, teamRewardsDedupGlobal, rewardFailClosed);

        if (serverId.startsWith("unknown-")) {
            FTBQuestsSync.LOGGER.warn(
                    "serverId is unstable (random per restart). "
                    + "Set TOML `serverId = \"agrN\"`, JVM arg `-Dftbquestssync.server.id=agrN`, "
                    + "or AT-style `-Dluckperms.server=agrN`.");
        }
        warnIfLocalhostBackend("MySQL", mysqlHost);
        warnIfLocalhostBackend("Redis", redisHost);
    }

    private static void warnIfLocalhostBackend(String backend, String host) {
        if (host == null) return;
        String normalized = host.trim().toLowerCase();
        boolean localhost = "localhost".equals(normalized)
                || "127.0.0.1".equals(normalized)
                || "::1".equals(normalized)
                || "0:0:0:0:0:0:0:1".equals(normalized);
        if (localhost) {
            FTBQuestsSync.LOGGER.warn(
                    "{} host is localhost ({}). Cross-server Agrarius sync requires a shared backend reachable from every server.",
                    backend, host);
        }
    }

    private static String prop(String key, String fallback) {
        return System.getProperty(key, fallback);
    }

    private static int intProp(String key, String fallback, int defaultValue) {
        try {
            return Integer.parseInt(System.getProperty(key, fallback).trim());
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private static boolean boolProp(String key, String fallback, boolean defaultValue) {
        String value = System.getProperty(key, fallback);
        if ("true".equalsIgnoreCase(value) || "yes".equalsIgnoreCase(value) || "1".equals(value)) return true;
        if ("false".equalsIgnoreCase(value) || "no".equalsIgnoreCase(value) || "0".equals(value)) return false;
        return defaultValue;
    }

    private static Set<Long> longSetProp(String key, String tomlValue, Set<Long> fallback) {
        String raw = System.getProperty(key, tomlValue == null ? null : tomlValue);
        if (raw == null || raw.isBlank()) return fallback;
        String value = raw.trim();
        if (value.startsWith("[") && value.endsWith("]")) {
            value = value.substring(1, value.length() - 1);
        }
        Set<Long> parsed = new HashSet<>();
        for (String part : value.split(",")) {
            String token = part.trim();
            if (token.isEmpty()) continue;
            if (token.length() >= 2 && token.startsWith("\"") && token.endsWith("\"")) {
                token = token.substring(1, token.length() - 1);
            }
            try {
                parsed.add(parseLongId(token));
            } catch (NumberFormatException e) {
                FTBQuestsSync.LOGGER.warn("Ignoring invalid id '{}' in {}", token, key);
            }
        }
        return parsed;
    }

    private static long parseLongId(String token) {
        String normalized = token.trim();
        if (normalized.startsWith("0x") || normalized.startsWith("0X")) {
            return Long.parseUnsignedLong(normalized.substring(2), 16);
        }
        boolean hex = normalized.matches("(?i)[0-9a-f]{16}");
        return hex ? Long.parseUnsignedLong(normalized, 16) : Long.parseLong(normalized);
    }

    /**
     * Minimal TOML reader: single-section flat key/value, ignores `[section]`
     * headers, strips quotes and `#` comments. Sufficient for this mod's needs.
     */
    private static Map<String, String> readToml(Path path) {
        Map<String, String> map = new HashMap<>();
        if (!Files.isRegularFile(path)) {
            FTBQuestsSync.LOGGER.warn("Config file {} not found, using system properties/defaults", path);
            return map;
        }
        try {
            for (String raw : Files.readAllLines(path)) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#") || line.startsWith("[")) continue;
                int eq = line.indexOf('=');
                if (eq < 0) continue;

                String key = line.substring(0, eq).trim();
                String value = line.substring(eq + 1).trim();
                int hash = value.indexOf('#');
                if (hash >= 0) value = value.substring(0, hash).trim();
                if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                    value = value.substring(1, value.length() - 1);
                }
                map.put(key, value);
            }
        } catch (IOException e) {
            FTBQuestsSync.LOGGER.error("Failed to read config {}, using defaults", path, e);
        }
        return map;
    }
}
