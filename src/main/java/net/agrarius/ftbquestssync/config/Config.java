package net.agrarius.ftbquestssync.config;

import net.agrarius.ftbquestssync.FTBQuestsSync;
import net.agrarius.ftbquestssync.chunks.RankBonusResolver;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.config.ModConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Forge COMMON-type configuration facade.
 *
 * <p>The spec is registered as {@link net.minecraftforge.fml.config.ModConfig.Type#COMMON}
 * deliberately: SERVER-type configs are synced to connecting clients, and this file
 * contains MySQL/Redis credentials. COMMON loads globally on the server side only.
 *
 * <p>Read sites continue to use the same public static fields and getters. Values are
 * refreshed from the Forge spec on {@link net.minecraftforge.fml.event.config.ModConfigEvent.Loading}
 * and {@link net.minecraftforge.fml.event.config.ModConfigEvent.Reloading}.
 *
 * <p>Legacy deployments used a hand-parsed flat TOML at
 * {@code /opt/agrarius/config/ftbquestssync-server.toml}. On first load, if the new
 * Forge file still has defaults and the legacy file exists, legacy values are copied
 * into the new config. The legacy file is left on disk and is no longer read after
 * migration.
 *
 * <p>System properties ({@code -Dftbquestssync.*}) override spec values with the same
 * precedence as before.
 */
public final class Config {

    // -------------------------------------------------------------------------
    // Public facade fields (kept identical to the hand-rolled TOML era so callers
    // do not need to change).
    // -------------------------------------------------------------------------
    public static String mysqlHost = "127.0.0.1";
    public static int mysqlPort = 3306;
    public static String mysqlDatabase = "agrarius_test";
    public static String mysqlUsername = "agrarius";
    public static String mysqlPassword = "";
    public static int mysqlMaxPool = 4;
    public static int mysqlMinIdle = 1;
    public static boolean mysqlUseSsl = true;
    public static boolean mysqlAllowPublicKeyRetrieval = false;

    public static String redisHost = "127.0.0.1";
    public static int redisPort = 6379;
    public static String redisPassword = "";

    public static boolean syncQuests = true;
    public static boolean syncTeams = false;
    public static boolean syncChunks = false;
    public static boolean chunkSeedOnStart = false;
    public static String chunkCanonicalServerId = "agr1";
    public static boolean chunkForceLoadSync = true;
    public static boolean sendFullTeamData = true;

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
    public static Map<String, Integer> rankBonuses = Map.of();

    public static boolean migrationRunOnBoot = false;
    public static String migrationRedisKeyPrefix = "stratos:data:player:blob:";
    public static int migrationRedisDb = 0;
    public static String migrationSourceMysqlHost = "";
    public static int migrationSourceMysqlPort = 3306;
    public static String migrationSourceMysqlDatabase = "CHANGEME";
    public static String migrationSourceMysqlUsername = "CHANGEME";
    public static String migrationSourceMysqlPassword = "";
    public static String migrationSourceMysqlPlayersTable = "core_players";
    public static String migrationSourceMysqlDataTable = "core_player_data";
    public static String migrationSourceMysqlCreatedAtColumn = "created_at";
    public static String migrationSourceMysqlIdPlayerColumn = "id_player";
    public static String migrationSourceMysqlDataColumn = "data";
    public static String migrationSourceMysqlUuidColumn = "uuid";
    public static String migrationSourceMysqlIdColumn = "id";
    public static String migrationServerIdTag = "migrator";
    public static int migrationMaxPlayers = 0;
    public static boolean migrationDryRun = false;
    public static int migrationMaxBlobBytes = 16 * 1024 * 1024;
    public static int migrationMaxSnbtBytes = 8 * 1024 * 1024;
    public static boolean migrationOverwriteExisting = false;
    public static String migrationRunOnServerId = "";
    public static String migrationMarkerDir = "/opt/agrarius/config";
    public static boolean migrationRemapUuids = true;
    public static String migrationUsercachePath = "/opt/agrarius/usercache.json";

    /** Resolved at {@link #refresh()}; never null after first refresh. */
    static String serverId;

    public static String getRedisHost() { return redisHost; }
    public static int getRedisPort() { return redisPort; }
    public static String getRedisPassword() { return redisPassword; }
    public static String getServerId() { return serverId; }

    private Config() {
    }

    // -------------------------------------------------------------------------
    // Forge config spec
    // -------------------------------------------------------------------------
    public static final ForgeConfigSpec SPEC;

    private static final ForgeConfigSpec.ConfigValue<String> MYSQL_HOST;
    private static final ForgeConfigSpec.IntValue MYSQL_PORT;
    private static final ForgeConfigSpec.ConfigValue<String> MYSQL_DATABASE;
    private static final ForgeConfigSpec.ConfigValue<String> MYSQL_USERNAME;
    private static final ForgeConfigSpec.ConfigValue<String> MYSQL_PASSWORD;
    private static final ForgeConfigSpec.IntValue MYSQL_MAX_POOL;
    private static final ForgeConfigSpec.IntValue MYSQL_MIN_IDLE;
    private static final ForgeConfigSpec.BooleanValue MYSQL_USE_SSL;
    private static final ForgeConfigSpec.BooleanValue MYSQL_ALLOW_PUBLIC_KEY_RETRIEVAL;

    private static final ForgeConfigSpec.ConfigValue<String> REDIS_HOST;
    private static final ForgeConfigSpec.IntValue REDIS_PORT;
    private static final ForgeConfigSpec.ConfigValue<String> REDIS_PASSWORD;

    private static final ForgeConfigSpec.ConfigValue<String> SERVER_ID;

    private static final ForgeConfigSpec.BooleanValue SYNC_QUESTS;
    private static final ForgeConfigSpec.BooleanValue SYNC_TEAMS;
    private static final ForgeConfigSpec.BooleanValue SYNC_CHUNKS;
    private static final ForgeConfigSpec.BooleanValue CHUNK_SEED_ON_START;
    private static final ForgeConfigSpec.ConfigValue<String> CHUNK_CANONICAL_SERVER_ID;
    private static final ForgeConfigSpec.BooleanValue CHUNK_FORCE_LOAD_SYNC;
    private static final ForgeConfigSpec.BooleanValue SEND_FULL_TEAM_DATA;

    private static final ForgeConfigSpec.ConfigValue<String> POLICY_MODE;
    private static final ForgeConfigSpec.ConfigValue<List<? extends String>> SOLO_CHAPTER_IDS;
    private static final ForgeConfigSpec.ConfigValue<List<? extends String>> REPEATABLE_SOLO_CHAPTER_IDS;
    private static final ForgeConfigSpec.ConfigValue<List<? extends String>> SOLO_QUEST_IDS;
    private static final ForgeConfigSpec.ConfigValue<List<? extends String>> SOLO_TASK_IDS;
    private static final ForgeConfigSpec.ConfigValue<List<? extends String>> TEAM_CLAIM_CHAPTER_IDS;
    private static final ForgeConfigSpec.ConfigValue<List<? extends String>> TEAM_SHARED_CHAPTER_IDS;
    private static final ForgeConfigSpec.BooleanValue SYNC_SOLO_PROGRESS_PER_PLAYER;
    private static final ForgeConfigSpec.BooleanValue SOLO_REWARDS_PER_PLAYER;
    private static final ForgeConfigSpec.BooleanValue TEAM_REWARDS_DEDUP_GLOBAL;
    private static final ForgeConfigSpec.BooleanValue REWARD_FAIL_CLOSED;
    private static final ForgeConfigSpec.ConfigValue<List<? extends String>> RANK_BONUSES;

    private static final ForgeConfigSpec.BooleanValue MIGRATION_RUN_ON_BOOT;
    private static final ForgeConfigSpec.ConfigValue<String> MIGRATION_REDIS_KEY_PREFIX;
    private static final ForgeConfigSpec.IntValue MIGRATION_REDIS_DB;
    private static final ForgeConfigSpec.ConfigValue<String> MIGRATION_SOURCE_MYSQL_HOST;
    private static final ForgeConfigSpec.IntValue MIGRATION_SOURCE_MYSQL_PORT;
    private static final ForgeConfigSpec.ConfigValue<String> MIGRATION_SOURCE_MYSQL_DATABASE;
    private static final ForgeConfigSpec.ConfigValue<String> MIGRATION_SOURCE_MYSQL_USERNAME;
    private static final ForgeConfigSpec.ConfigValue<String> MIGRATION_SOURCE_MYSQL_PASSWORD;
    private static final ForgeConfigSpec.ConfigValue<String> MIGRATION_SOURCE_MYSQL_PLAYERS_TABLE;
    private static final ForgeConfigSpec.ConfigValue<String> MIGRATION_SOURCE_MYSQL_DATA_TABLE;
    private static final ForgeConfigSpec.ConfigValue<String> MIGRATION_SOURCE_MYSQL_CREATED_AT_COLUMN;
    private static final ForgeConfigSpec.ConfigValue<String> MIGRATION_SOURCE_MYSQL_ID_PLAYER_COLUMN;
    private static final ForgeConfigSpec.ConfigValue<String> MIGRATION_SOURCE_MYSQL_DATA_COLUMN;
    private static final ForgeConfigSpec.ConfigValue<String> MIGRATION_SOURCE_MYSQL_UUID_COLUMN;
    private static final ForgeConfigSpec.ConfigValue<String> MIGRATION_SOURCE_MYSQL_ID_COLUMN;
    private static final ForgeConfigSpec.ConfigValue<String> MIGRATION_SERVER_ID_TAG;
    private static final ForgeConfigSpec.IntValue MIGRATION_MAX_PLAYERS;
    private static final ForgeConfigSpec.BooleanValue MIGRATION_DRY_RUN;
    private static final ForgeConfigSpec.IntValue MIGRATION_MAX_BLOB_BYTES;
    private static final ForgeConfigSpec.IntValue MIGRATION_MAX_SNBT_BYTES;
    private static final ForgeConfigSpec.BooleanValue MIGRATION_OVERWRITE_EXISTING;
    private static final ForgeConfigSpec.ConfigValue<String> MIGRATION_RUN_ON_SERVER_ID;
    private static final ForgeConfigSpec.ConfigValue<String> MIGRATION_MARKER_DIR;
    private static final ForgeConfigSpec.BooleanValue MIGRATION_REMAP_UUIDS;
    private static final ForgeConfigSpec.ConfigValue<String> MIGRATION_USERCACHE_PATH;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();

        builder.comment("MySQL connection settings")
                .push("mysql");
        MYSQL_HOST = builder.comment("MySQL host").define("host", "127.0.0.1");
        MYSQL_PORT = builder.comment("MySQL port").defineInRange("port", 3306, 1, 65535);
        MYSQL_DATABASE = builder.comment("MySQL database name").define("database", "agrarius_test");
        MYSQL_USERNAME = builder.comment("MySQL username").define("username", "agrarius");
        MYSQL_PASSWORD = builder.comment("MySQL password").define("password", "");
        MYSQL_MAX_POOL = builder.comment("Maximum connection pool size").defineInRange("maxPoolSize", 4, 1, Integer.MAX_VALUE);
        MYSQL_MIN_IDLE = builder.comment("Minimum idle connections").defineInRange("minIdle", 1, 0, Integer.MAX_VALUE);
        MYSQL_USE_SSL = builder.comment("Use SSL for MySQL connections").define("useSsl", true);
        MYSQL_ALLOW_PUBLIC_KEY_RETRIEVAL = builder.comment("Allow public key retrieval for MySQL").define("allowPublicKeyRetrieval", false);
        builder.pop();

        builder.comment("Redis connection settings")
                .push("redis");
        REDIS_HOST = builder.comment("Redis host").define("redisHost", "127.0.0.1");
        REDIS_PORT = builder.comment("Redis port").defineInRange("redisPort", 6379, 1, 65535);
        REDIS_PASSWORD = builder.comment("Redis password").define("redisPassword", "");
        builder.pop();

        builder.comment("Server identity")
                .push("server");
        SERVER_ID = builder.comment(
                        "Server id (e.g. agr1). Precedence: -Dftbquestssync.server.id, this value, " +
                                "-Dftbquestssync.serverId (legacy), -Dluckperms.server, unknown-<random>.")
                .define("serverId", "");
        builder.pop();

        builder.comment("Feature toggles")
                .push("features");
        SYNC_QUESTS = builder.comment("Synchronize quest data via MySQL").define("syncQuests", true);
        SYNC_TEAMS = builder.comment("Synchronize FTB Teams data via Redis").define("syncTeams", false);
        SYNC_CHUNKS = builder.comment("Synchronize chunk claims via MySQL").define("syncChunks", false);
        CHUNK_SEED_ON_START = builder.comment("Seed chunk claims from the canonical server on start").define("chunkSeedOnStart", false);
        CHUNK_CANONICAL_SERVER_ID = builder.comment("Canonical server id used for chunk seeding").define("chunkCanonicalServerId", "agr1");
        CHUNK_FORCE_LOAD_SYNC = builder.comment("Synchronize chunk force-load state").define("chunkForceLoadSync", true);
        SEND_FULL_TEAM_DATA = builder.comment("Send full team data on sync").define("sendFullTeamData", true);
        builder.pop();

        builder.comment("Solo / team policy configuration")
                .push("policy");
        POLICY_MODE = builder.comment("Policy mode: blacklist or whitelist").define("mode", "blacklist");
        SOLO_CHAPTER_IDS = builder.comment("Chapter IDs treated as solo-only").defineListAllowEmpty("soloChapterIds", List.of("3622ED01311E6763", "67F6F5055518AC4F"), o -> o instanceof String);
        REPEATABLE_SOLO_CHAPTER_IDS = builder.comment("Repeatable solo chapter IDs").defineListAllowEmpty("repeatableSoloChapterIds", List.of(), o -> o instanceof String);
        SOLO_QUEST_IDS = builder.comment("Solo quest IDs").defineListAllowEmpty("soloQuestIds", List.of(), o -> o instanceof String);
        SOLO_TASK_IDS = builder.comment("Solo task IDs").defineListAllowEmpty("soloTaskIds", List.of(), o -> o instanceof String);
        TEAM_CLAIM_CHAPTER_IDS = builder.comment("Team claim chapter IDs").defineListAllowEmpty("teamClaimChapterIds", List.of("3CEC7F7BAD54E4C6"), o -> o instanceof String);
        TEAM_SHARED_CHAPTER_IDS = builder.comment("Team-shared chapter IDs").defineListAllowEmpty("teamSharedChapterIds", List.of(
                "330C551154E3E367", "312B5D7DC7779EFE", "2742F3918C76DA81", "37CD2B0E77E895ED",
                "587F453EE1BAFB79", "13B88A5F6D9187F8", "75C4C0229F2D2798", "459D60183D34C29E",
                "5617484BFC479624", "450EB2B6DF25A9EC", "05330DA4070C75C9", "62A72817F92AC262",
                "4BF4EE11E23C71FC", "10A89CD14C4DAF63", "54A26707B4CD39CC", "74FB988DE790BE0D"), o -> o instanceof String);
        SYNC_SOLO_PROGRESS_PER_PLAYER = builder.comment("Track solo progress per player").define("syncSoloProgressPerPlayer", true);
        SOLO_REWARDS_PER_PLAYER = builder.comment("Give solo rewards per player instead of per team").define("soloRewardsPerPlayer", true);
        TEAM_REWARDS_DEDUP_GLOBAL = builder.comment("Deduplicate team rewards globally").define("teamRewardsDedupGlobal", true);
        REWARD_FAIL_CLOSED = builder.comment("Fail reward claims closed when policy cannot be resolved").define("rewardFailClosed", true);
        RANK_BONUSES = builder.comment("Per-rank chunk force-load bonuses (format: [\"rank=count\", ...])").defineListAllowEmpty("rankBonuses", List.of(), o -> o instanceof String);
        builder.pop();

        builder.comment("Legacy per-player quest-data migration options")
                .push("migration");
        MIGRATION_RUN_ON_BOOT = builder.comment("Run legacy migration on boot").define("runOnBoot", false);
        MIGRATION_REDIS_KEY_PREFIX = builder.comment("Redis key prefix for legacy player blobs").define("redisKeyPrefix", "stratos:data:player:blob:");
        MIGRATION_REDIS_DB = builder.comment("Redis DB for legacy blobs").defineInRange("redisDb", 0, 0, Integer.MAX_VALUE);
        MIGRATION_SOURCE_MYSQL_HOST = builder.comment("Source MySQL host for migration").define("sourceMysqlHost", "");
        MIGRATION_SOURCE_MYSQL_PORT = builder.comment("Source MySQL port for migration").defineInRange("sourceMysqlPort", 3306, 1, 65535);
        MIGRATION_SOURCE_MYSQL_DATABASE = builder.comment("Source MySQL database for migration").define("sourceMysqlDatabase", "CHANGEME");
        MIGRATION_SOURCE_MYSQL_USERNAME = builder.comment("Source MySQL username for migration").define("sourceMysqlUsername", "CHANGEME");
        MIGRATION_SOURCE_MYSQL_PASSWORD = builder.comment("Source MySQL password for migration").define("sourceMysqlPassword", "");
        MIGRATION_SOURCE_MYSQL_PLAYERS_TABLE = builder.comment("Source players table").define("sourcePlayersTable", "core_players");
        MIGRATION_SOURCE_MYSQL_DATA_TABLE = builder.comment("Source player data table").define("sourceDataTable", "core_player_data");
        MIGRATION_SOURCE_MYSQL_CREATED_AT_COLUMN = builder.comment("Source created_at column").define("sourceCreatedAtColumn", "created_at");
        MIGRATION_SOURCE_MYSQL_ID_PLAYER_COLUMN = builder.comment("Source id_player column").define("sourceIdPlayerColumn", "id_player");
        MIGRATION_SOURCE_MYSQL_DATA_COLUMN = builder.comment("Source data column").define("sourceDataColumn", "data");
        MIGRATION_SOURCE_MYSQL_UUID_COLUMN = builder.comment("Source uuid column").define("sourceUuidColumn", "uuid");
        MIGRATION_SOURCE_MYSQL_ID_COLUMN = builder.comment("Source id column").define("sourceIdColumn", "id");
        MIGRATION_SERVER_ID_TAG = builder.comment("Server id tag written by migrator").define("serverIdTag", "migrator");
        MIGRATION_MAX_PLAYERS = builder.comment("Maximum players to migrate (0 = no cap)").defineInRange("maxPlayers", 0, 0, Integer.MAX_VALUE);
        MIGRATION_DRY_RUN = builder.comment("Run migration in dry-run mode").define("dryRun", false);
        MIGRATION_MAX_BLOB_BYTES = builder.comment("Maximum legacy blob bytes (zip bomb guard)").defineInRange("maxBlobBytes", 16 * 1024 * 1024, 0, Integer.MAX_VALUE);
        MIGRATION_MAX_SNBT_BYTES = builder.comment("Maximum decompressed SNBT bytes").defineInRange("maxSnbtBytes", 8 * 1024 * 1024, 0, Integer.MAX_VALUE);
        MIGRATION_OVERWRITE_EXISTING = builder.comment("Overwrite existing target rows during migration").define("overwriteExisting", false);
        MIGRATION_RUN_ON_SERVER_ID = builder.comment("Only run migration on matching server id (empty = any)").define("runOnServerId", "");
        MIGRATION_MARKER_DIR = builder.comment("Directory for per-server migration marker files").define("markerDir", "/opt/agrarius/config");
        MIGRATION_REMAP_UUIDS = builder.comment("Remap legacy UUIDs to current UUIDs via usercache.json").define("remapUuids", true);
        MIGRATION_USERCACHE_PATH = builder.comment("Path to Mojang usercache.json").define("usercachePath", "/opt/agrarius/usercache.json");
        builder.pop();

        SPEC = builder.build();
    }

    private static Path currentConfigPath;

    /**
     * Refreshes static fields from the Forge spec.
     *
     * <p>Called automatically on config load/reload. The overload taking a
     * {@link ModConfig} is used to discover the config directory for legacy migration.
     */
    public static void refresh() {
        refresh(null);
    }

    public static void refresh(ModConfig modConfig) {
        if (modConfig != null) {
            currentConfigPath = modConfig.getFullPath();
        }

        readFromSpec();
        maybeMigrateLegacy();
        readFromSpec();
        applySystemPropertyOverrides();
        resolveServerId();
        validate();
        log();
    }

    /**
     * Legacy facade kept for callers that used the hand-rolled reload path.
     * Equivalent to {@link #refresh()}.
     */
    public static void reload() {
        refresh();
    }

    private static void readFromSpec() {
        mysqlHost = MYSQL_HOST.get();
        mysqlPort = MYSQL_PORT.get();
        mysqlDatabase = MYSQL_DATABASE.get();
        mysqlUsername = MYSQL_USERNAME.get();
        mysqlPassword = MYSQL_PASSWORD.get();
        mysqlMaxPool = MYSQL_MAX_POOL.get();
        mysqlMinIdle = MYSQL_MIN_IDLE.get();
        mysqlUseSsl = MYSQL_USE_SSL.get();
        mysqlAllowPublicKeyRetrieval = MYSQL_ALLOW_PUBLIC_KEY_RETRIEVAL.get();

        redisHost = REDIS_HOST.get();
        redisPort = REDIS_PORT.get();
        redisPassword = REDIS_PASSWORD.get();

        syncQuests = SYNC_QUESTS.get();
        syncTeams = SYNC_TEAMS.get();
        syncChunks = SYNC_CHUNKS.get();
        chunkSeedOnStart = CHUNK_SEED_ON_START.get();
        chunkCanonicalServerId = CHUNK_CANONICAL_SERVER_ID.get();
        chunkForceLoadSync = CHUNK_FORCE_LOAD_SYNC.get();
        sendFullTeamData = SEND_FULL_TEAM_DATA.get();

        policyMode = POLICY_MODE.get().trim().toLowerCase(Locale.ROOT);
        if (!"blacklist".equals(policyMode) && !"whitelist".equals(policyMode)) {
            FTBQuestsSync.LOGGER.warn("Invalid policy mode '{}', using blacklist", policyMode);
            policyMode = "blacklist";
        }
        soloChapterIds = parseLongSet(SOLO_CHAPTER_IDS.get());
        repeatableSoloChapterIds = parseLongSet(REPEATABLE_SOLO_CHAPTER_IDS.get());
        soloQuestIds = parseLongSet(SOLO_QUEST_IDS.get());
        soloTaskIds = parseLongSet(SOLO_TASK_IDS.get());
        teamClaimChapterIds = parseLongSet(TEAM_CLAIM_CHAPTER_IDS.get());
        teamSharedChapterIds = parseLongSet(TEAM_SHARED_CHAPTER_IDS.get());
        syncSoloProgressPerPlayer = SYNC_SOLO_PROGRESS_PER_PLAYER.get();
        soloRewardsPerPlayer = SOLO_REWARDS_PER_PLAYER.get();
        teamRewardsDedupGlobal = TEAM_REWARDS_DEDUP_GLOBAL.get();
        rewardFailClosed = REWARD_FAIL_CLOSED.get();
        rankBonuses = RankBonusResolver.parseConfig(String.join(",", RANK_BONUSES.get()));

        migrationRunOnBoot = MIGRATION_RUN_ON_BOOT.get();
        migrationRedisKeyPrefix = MIGRATION_REDIS_KEY_PREFIX.get();
        migrationRedisDb = MIGRATION_REDIS_DB.get();
        migrationSourceMysqlHost = MIGRATION_SOURCE_MYSQL_HOST.get();
        migrationSourceMysqlPort = MIGRATION_SOURCE_MYSQL_PORT.get();
        migrationSourceMysqlDatabase = MIGRATION_SOURCE_MYSQL_DATABASE.get();
        migrationSourceMysqlUsername = MIGRATION_SOURCE_MYSQL_USERNAME.get();
        migrationSourceMysqlPassword = MIGRATION_SOURCE_MYSQL_PASSWORD.get();
        migrationSourceMysqlPlayersTable = MIGRATION_SOURCE_MYSQL_PLAYERS_TABLE.get();
        migrationSourceMysqlDataTable = MIGRATION_SOURCE_MYSQL_DATA_TABLE.get();
        migrationSourceMysqlCreatedAtColumn = MIGRATION_SOURCE_MYSQL_CREATED_AT_COLUMN.get();
        migrationSourceMysqlIdPlayerColumn = MIGRATION_SOURCE_MYSQL_ID_PLAYER_COLUMN.get();
        migrationSourceMysqlDataColumn = MIGRATION_SOURCE_MYSQL_DATA_COLUMN.get();
        migrationSourceMysqlUuidColumn = MIGRATION_SOURCE_MYSQL_UUID_COLUMN.get();
        migrationSourceMysqlIdColumn = MIGRATION_SOURCE_MYSQL_ID_COLUMN.get();
        migrationServerIdTag = MIGRATION_SERVER_ID_TAG.get();
        migrationMaxPlayers = MIGRATION_MAX_PLAYERS.get();
        migrationDryRun = MIGRATION_DRY_RUN.get();
        migrationMaxBlobBytes = MIGRATION_MAX_BLOB_BYTES.get();
        migrationMaxSnbtBytes = MIGRATION_MAX_SNBT_BYTES.get();
        migrationOverwriteExisting = MIGRATION_OVERWRITE_EXISTING.get();
        migrationRunOnServerId = MIGRATION_RUN_ON_SERVER_ID.get();
        migrationMarkerDir = MIGRATION_MARKER_DIR.get();
        migrationRemapUuids = MIGRATION_REMAP_UUIDS.get();
        migrationUsercachePath = MIGRATION_USERCACHE_PATH.get();

        serverId = SERVER_ID.get();
    }

    private static void applySystemPropertyOverrides() {
        mysqlHost = prop("ftbquestssync.mysql.host", mysqlHost);
        mysqlPort = intProp("ftbquestssync.mysql.port", mysqlPort);
        mysqlDatabase = prop("ftbquestssync.mysql.database", mysqlDatabase);
        mysqlUsername = prop("ftbquestssync.mysql.username", mysqlUsername);
        mysqlPassword = prop("ftbquestssync.mysql.password", mysqlPassword);
        mysqlMaxPool = intProp("ftbquestssync.mysql.maxPoolSize", mysqlMaxPool);
        mysqlMinIdle = intProp("ftbquestssync.mysql.minIdle", mysqlMinIdle);
        mysqlUseSsl = boolProp("ftbquestssync.mysql.useSsl", mysqlUseSsl);
        mysqlAllowPublicKeyRetrieval = boolProp("ftbquestssync.mysql.allowPublicKeyRetrieval", mysqlAllowPublicKeyRetrieval);

        redisHost = prop("ftbquestssync.redis.host", redisHost);
        redisPort = intProp("ftbquestssync.redis.port", redisPort);
        redisPassword = prop("ftbquestssync.redis.password", redisPassword);

        syncQuests = boolProp("ftbquestssync.syncQuests", syncQuests);
        syncTeams = boolProp("ftbquestssync.syncTeams", syncTeams);
        syncChunks = boolProp("ftbquestssync.syncChunks", syncChunks);
        chunkSeedOnStart = boolProp("ftbquestssync.chunkSeedOnStart", chunkSeedOnStart);
        chunkCanonicalServerId = prop("ftbquestssync.chunkCanonicalServerId", chunkCanonicalServerId).trim();
        chunkForceLoadSync = boolProp("ftbquestssync.chunkForceLoadSync", chunkForceLoadSync);
        sendFullTeamData = boolProp("ftbquestssync.sendFullTeamData", sendFullTeamData);

        String modeProp = prop("ftbquestssync.policy.mode", policyMode).trim().toLowerCase(Locale.ROOT);
        if ("blacklist".equals(modeProp) || "whitelist".equals(modeProp)) {
            policyMode = modeProp;
        }
        soloChapterIds = overrideLongSet("ftbquestssync.policy.soloChapterIds", soloChapterIds);
        repeatableSoloChapterIds = overrideLongSet("ftbquestssync.policy.repeatableSoloChapterIds", repeatableSoloChapterIds);
        soloQuestIds = overrideLongSet("ftbquestssync.policy.soloQuestIds", soloQuestIds);
        soloTaskIds = overrideLongSet("ftbquestssync.policy.soloTaskIds", soloTaskIds);
        teamClaimChapterIds = overrideLongSet("ftbquestssync.policy.teamClaimChapterIds", teamClaimChapterIds);
        teamSharedChapterIds = overrideLongSet("ftbquestssync.policy.teamSharedChapterIds", teamSharedChapterIds);
        syncSoloProgressPerPlayer = boolProp("ftbquestssync.policy.syncSoloProgressPerPlayer", syncSoloProgressPerPlayer);
        soloRewardsPerPlayer = boolProp("ftbquestssync.policy.soloRewardsPerPlayer", soloRewardsPerPlayer);
        teamRewardsDedupGlobal = boolProp("ftbquestssync.policy.teamRewardsDedupGlobal", teamRewardsDedupGlobal);
        rewardFailClosed = boolProp("ftbquestssync.policy.rewardFailClosed", rewardFailClosed);
        rankBonuses = overrideRankBonusMap("ftbquestssync.policy.rankBonuses", rankBonuses);

        migrationRunOnBoot = boolProp("ftbquestssync.migration.runOnBoot", migrationRunOnBoot);
        migrationRedisKeyPrefix = prop("ftbquestssync.migration.redisKeyPrefix", migrationRedisKeyPrefix);
        migrationRedisDb = intProp("ftbquestssync.migration.redisDb", migrationRedisDb);
        migrationSourceMysqlHost = prop("ftbquestssync.migration.sourceMysqlHost", migrationSourceMysqlHost);
        migrationSourceMysqlPort = intProp("ftbquestssync.migration.sourceMysqlPort", migrationSourceMysqlPort);
        migrationSourceMysqlDatabase = prop("ftbquestssync.migration.sourceMysqlDatabase", migrationSourceMysqlDatabase);
        migrationSourceMysqlUsername = prop("ftbquestssync.migration.sourceMysqlUsername", migrationSourceMysqlUsername);
        migrationSourceMysqlPassword = prop("ftbquestssync.migration.sourceMysqlPassword", migrationSourceMysqlPassword);
        migrationSourceMysqlPlayersTable = prop("ftbquestssync.migration.sourcePlayersTable", migrationSourceMysqlPlayersTable);
        migrationSourceMysqlDataTable = prop("ftbquestssync.migration.sourceDataTable", migrationSourceMysqlDataTable);
        migrationSourceMysqlCreatedAtColumn = prop("ftbquestssync.migration.sourceCreatedAtColumn", migrationSourceMysqlCreatedAtColumn);
        migrationSourceMysqlIdPlayerColumn = prop("ftbquestssync.migration.sourceIdPlayerColumn", migrationSourceMysqlIdPlayerColumn);
        migrationSourceMysqlDataColumn = prop("ftbquestssync.migration.sourceDataColumn", migrationSourceMysqlDataColumn);
        migrationSourceMysqlUuidColumn = prop("ftbquestssync.migration.sourceUuidColumn", migrationSourceMysqlUuidColumn);
        migrationSourceMysqlIdColumn = prop("ftbquestssync.migration.sourceIdColumn", migrationSourceMysqlIdColumn);
        migrationServerIdTag = prop("ftbquestssync.migration.serverIdTag", migrationServerIdTag);
        migrationMaxPlayers = intProp("ftbquestssync.migration.maxPlayers", migrationMaxPlayers);
        migrationDryRun = boolProp("ftbquestssync.migration.dryRun", migrationDryRun);
        migrationMaxBlobBytes = intProp("ftbquestssync.migration.maxBlobBytes", migrationMaxBlobBytes);
        migrationMaxSnbtBytes = intProp("ftbquestssync.migration.maxSnbtBytes", migrationMaxSnbtBytes);
        migrationOverwriteExisting = boolProp("ftbquestssync.migration.overwriteExisting", migrationOverwriteExisting);
        migrationRunOnServerId = prop("ftbquestssync.migration.runOnServerId", migrationRunOnServerId).trim();
        migrationMarkerDir = prop("ftbquestssync.migration.markerDir", migrationMarkerDir).trim();
        migrationRemapUuids = boolProp("ftbquestssync.migration.remapUuids", migrationRemapUuids);
        migrationUsercachePath = prop("ftbquestssync.migration.usercachePath", migrationUsercachePath).trim();
    }

    private static void resolveServerId() {
        String tomlServerId = serverId;
        String resolved = prop("ftbquestssync.server.id", tomlServerId);
        if (resolved.equals(tomlServerId) && resolved.isBlank()) {
            resolved = System.getProperty("ftbquestssync.serverId", "");
        }
        if (resolved.isBlank()) {
            resolved = System.getProperty("luckperms.server", "");
        }
        if (resolved.isBlank()) {
            resolved = "unknown-" + UUID.randomUUID().toString().substring(0, 8);
        }
        serverId = resolved;
    }

    private static void validate() {
        if (mysqlPassword == null || mysqlPassword.isBlank()) {
            throw new IllegalStateException(
                    "MySQL password is not configured. " +
                            "Set it in the [mysql] section of the Forge config file, " +
                            "or pass -Dftbquestssync.mysql.password=<password> on the JVM command line.");
        }
    }

    private static void log() {
        FTBQuestsSync.LOGGER.info(
                "Config loaded: mysql={}:{} db={} user={} passwordSet={} mysqlSsl={} redis={}:{} redisPasswordSet={} serverId={} "
                        + "syncQuests={} syncTeams={} syncChunks={} chunkSeedOnStart={} chunkCanonicalServerId={} chunkForceLoadSync={} "
                        + "sendFullTeamData={}",
                mysqlHost, mysqlPort, mysqlDatabase, mysqlUsername,
                mysqlPassword != null && !mysqlPassword.isBlank(), mysqlUseSsl, redisHost, redisPort,
                redisPassword != null && !redisPassword.isBlank(), serverId,
                syncQuests, syncTeams, syncChunks, chunkSeedOnStart, chunkCanonicalServerId, chunkForceLoadSync,
                sendFullTeamData);
        FTBQuestsSync.LOGGER.info(
                "Policy loaded: mode={} soloChapterIds={} repeatableSoloChapterIds={} soloQuestIds={} soloTaskIds={} "
                        + "syncSoloProgressPerPlayer={} soloRewardsPerPlayer={} teamRewardsDedupGlobal={} rewardFailClosed={} rankBonuses={}",
                policyMode, soloChapterIds, repeatableSoloChapterIds, soloQuestIds, soloTaskIds,
                syncSoloProgressPerPlayer, soloRewardsPerPlayer, teamRewardsDedupGlobal, rewardFailClosed, rankBonuses);

        if (serverId != null && serverId.startsWith("unknown-")) {
            FTBQuestsSync.LOGGER.warn(
                    "serverId is unstable (random per restart). "
                            + "Set TOML [server] serverId = \"agrN\", JVM arg -Dftbquestssync.server.id=agrN, "
                            + "or AT-style -Dluckperms.server=agrN.");
        }
        warnIfLocalhostBackend("MySQL", mysqlHost);
        warnIfLocalhostBackend("Redis", redisHost);
    }

    private static void warnIfLocalhostBackend(String backend, String host) {
        if (host == null) return;
        String normalized = host.trim().toLowerCase(Locale.ROOT);
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

    // -------------------------------------------------------------------------
    // System-property helpers
    // -------------------------------------------------------------------------
    private static String prop(String key, String fallback) {
        return System.getProperty(key, fallback);
    }

    private static int intProp(String key, int fallback) {
        String value = System.getProperty(key);
        if (value == null) return fallback;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static boolean boolProp(String key, boolean fallback) {
        String value = System.getProperty(key);
        if (value == null) return fallback;
        if ("true".equalsIgnoreCase(value) || "yes".equalsIgnoreCase(value) || "1".equals(value)) return true;
        if ("false".equalsIgnoreCase(value) || "no".equalsIgnoreCase(value) || "0".equals(value)) return false;
        return fallback;
    }

    private static Set<Long> overrideLongSet(String key, Set<Long> fallback) {
        String raw = System.getProperty(key);
        if (raw == null || raw.isBlank()) return fallback;
        return parseLongSet(raw);
    }

    private static Set<Long> parseLongSet(List<? extends String> values) {
        if (values == null || values.isEmpty()) return Set.of();
        Set<Long> parsed = new HashSet<>();
        for (String token : values) {
            if (token == null || token.isBlank()) continue;
            try {
                parsed.add(parseLongId(token));
            } catch (NumberFormatException e) {
                FTBQuestsSync.LOGGER.warn("Ignoring invalid id '{}' in config list", token);
            }
        }
        return Collections.unmodifiableSet(parsed);
    }

    private static Set<Long> parseLongSet(String raw) {
        if (raw == null || raw.isBlank()) return Set.of();
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
                FTBQuestsSync.LOGGER.warn("Ignoring invalid id '{}' in {}", token, "system property");
            }
        }
        return Collections.unmodifiableSet(parsed);
    }

    private static long parseLongId(String token) {
        String normalized = token.trim();
        if (normalized.startsWith("0x") || normalized.startsWith("0X")) {
            return Long.parseUnsignedLong(normalized.substring(2), 16);
        }
        boolean hex = normalized.matches("(?i)[0-9a-f]{16}");
        return hex ? Long.parseUnsignedLong(normalized, 16) : Long.parseLong(normalized);
    }

    private static Map<String, Integer> overrideRankBonusMap(String key, Map<String, Integer> fallback) {
        String raw = System.getProperty(key);
        if (raw == null || raw.isBlank()) return fallback;
        return RankBonusResolver.parseConfig(raw);
    }

    // -------------------------------------------------------------------------
    // Legacy flat TOML migration
    // -------------------------------------------------------------------------
    private static final String LEGACY_FILE_NAME = "ftbquestssync-server.toml";

    private static void maybeMigrateLegacy() {
        if (currentConfigPath == null) return;
        Path legacyPath = currentConfigPath.getParent().resolve(LEGACY_FILE_NAME);
        if (!Files.isRegularFile(legacyPath)) return;

        boolean appearsDefault = mysqlPassword.isBlank() && (serverId == null || serverId.isBlank());
        if (!appearsDefault) return;

        FTBQuestsSync.LOGGER.info(
                "New Forge config appears to have defaults and legacy config {} exists. Migrating values.",
                legacyPath);
        Map<String, String> legacy = readLegacyFlatToml(legacyPath);
        if (legacy.isEmpty()) {
            FTBQuestsSync.LOGGER.warn("Legacy config {} was empty; nothing to migrate.", legacyPath);
            return;
        }

        boolean migrated = false;
        for (Map.Entry<String, String> entry : legacy.entrySet()) {
            if (applyLegacyValue(entry.getKey(), entry.getValue())) {
                migrated = true;
            }
        }

        if (migrated) {
            try {
                SPEC.save();
                FTBQuestsSync.LOGGER.info(
                        "Legacy config migration complete. New config saved to {}. Legacy file left at {}.",
                        currentConfigPath, legacyPath);
            } catch (Exception e) {
                FTBQuestsSync.LOGGER.error("Failed to save migrated config", e);
            }
        }
    }

    private static boolean applyLegacyValue(String key, String raw) {
        if (raw == null) return false;
        try {
            String value = raw.trim();
            switch (key) {
                case "host" -> MYSQL_HOST.set(value);
                case "port" -> MYSQL_PORT.set(Integer.parseInt(value));
                case "database" -> MYSQL_DATABASE.set(value);
                case "username" -> MYSQL_USERNAME.set(value);
                case "password" -> MYSQL_PASSWORD.set(value);
                case "maxPoolSize" -> MYSQL_MAX_POOL.set(Integer.parseInt(value));
                case "minIdle" -> MYSQL_MIN_IDLE.set(Integer.parseInt(value));
                case "useSsl" -> MYSQL_USE_SSL.set(parseBoolean(value));
                case "allowPublicKeyRetrieval" -> MYSQL_ALLOW_PUBLIC_KEY_RETRIEVAL.set(parseBoolean(value));

                case "redisHost" -> REDIS_HOST.set(value);
                case "redisPort" -> REDIS_PORT.set(Integer.parseInt(value));
                case "redisPassword" -> REDIS_PASSWORD.set(value);

                case "serverId" -> SERVER_ID.set(value);

                case "syncQuests" -> SYNC_QUESTS.set(parseBoolean(value));
                case "syncTeams" -> SYNC_TEAMS.set(parseBoolean(value));
                case "syncChunks" -> SYNC_CHUNKS.set(parseBoolean(value));
                case "chunkSeedOnStart" -> CHUNK_SEED_ON_START.set(parseBoolean(value));
                case "chunkCanonicalServerId" -> CHUNK_CANONICAL_SERVER_ID.set(value);
                case "chunkForceLoadSync" -> CHUNK_FORCE_LOAD_SYNC.set(parseBoolean(value));
                case "sendFullTeamData" -> SEND_FULL_TEAM_DATA.set(parseBoolean(value));

                case "mode" -> POLICY_MODE.set(value.toLowerCase(Locale.ROOT));
                case "soloChapterIds" -> SOLO_CHAPTER_IDS.set(parseLegacyStringList(value));
                case "repeatableSoloChapterIds" -> REPEATABLE_SOLO_CHAPTER_IDS.set(parseLegacyStringList(value));
                case "soloQuestIds" -> SOLO_QUEST_IDS.set(parseLegacyStringList(value));
                case "soloTaskIds" -> SOLO_TASK_IDS.set(parseLegacyStringList(value));
                case "teamClaimChapterIds" -> TEAM_CLAIM_CHAPTER_IDS.set(parseLegacyStringList(value));
                case "teamSharedChapterIds" -> TEAM_SHARED_CHAPTER_IDS.set(parseLegacyStringList(value));
                case "syncSoloProgressPerPlayer" -> SYNC_SOLO_PROGRESS_PER_PLAYER.set(parseBoolean(value));
                case "soloRewardsPerPlayer" -> SOLO_REWARDS_PER_PLAYER.set(parseBoolean(value));
                case "teamRewardsDedupGlobal" -> TEAM_REWARDS_DEDUP_GLOBAL.set(parseBoolean(value));
                case "rewardFailClosed" -> REWARD_FAIL_CLOSED.set(parseBoolean(value));
                case "rankBonuses" -> RANK_BONUSES.set(parseLegacyStringList(value));

                case "runOnBoot" -> MIGRATION_RUN_ON_BOOT.set(parseBoolean(value));
                case "redisKeyPrefix" -> MIGRATION_REDIS_KEY_PREFIX.set(value);
                case "redisDb" -> MIGRATION_REDIS_DB.set(Integer.parseInt(value));
                case "sourceMysqlHost" -> MIGRATION_SOURCE_MYSQL_HOST.set(value);
                case "sourceMysqlPort" -> MIGRATION_SOURCE_MYSQL_PORT.set(Integer.parseInt(value));
                case "sourceMysqlDatabase" -> MIGRATION_SOURCE_MYSQL_DATABASE.set(value);
                case "sourceMysqlUsername" -> MIGRATION_SOURCE_MYSQL_USERNAME.set(value);
                case "sourceMysqlPassword" -> MIGRATION_SOURCE_MYSQL_PASSWORD.set(value);
                case "sourcePlayersTable" -> MIGRATION_SOURCE_MYSQL_PLAYERS_TABLE.set(value);
                case "sourceDataTable" -> MIGRATION_SOURCE_MYSQL_DATA_TABLE.set(value);
                case "sourceCreatedAtColumn" -> MIGRATION_SOURCE_MYSQL_CREATED_AT_COLUMN.set(value);
                case "sourceIdPlayerColumn" -> MIGRATION_SOURCE_MYSQL_ID_PLAYER_COLUMN.set(value);
                case "sourceDataColumn" -> MIGRATION_SOURCE_MYSQL_DATA_COLUMN.set(value);
                case "sourceUuidColumn" -> MIGRATION_SOURCE_MYSQL_UUID_COLUMN.set(value);
                case "sourceIdColumn" -> MIGRATION_SOURCE_MYSQL_ID_COLUMN.set(value);
                case "serverIdTag" -> MIGRATION_SERVER_ID_TAG.set(value);
                case "maxPlayers" -> MIGRATION_MAX_PLAYERS.set(Integer.parseInt(value));
                case "dryRun" -> MIGRATION_DRY_RUN.set(parseBoolean(value));
                case "maxBlobBytes" -> MIGRATION_MAX_BLOB_BYTES.set(Integer.parseInt(value));
                case "maxSnbtBytes" -> MIGRATION_MAX_SNBT_BYTES.set(Integer.parseInt(value));
                case "overwriteExisting" -> MIGRATION_OVERWRITE_EXISTING.set(parseBoolean(value));
                case "runOnServerId" -> MIGRATION_RUN_ON_SERVER_ID.set(value);
                case "markerDir" -> MIGRATION_MARKER_DIR.set(value);
                case "remapUuids" -> MIGRATION_REMAP_UUIDS.set(parseBoolean(value));
                case "usercachePath" -> MIGRATION_USERCACHE_PATH.set(value);
                default -> {
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.warn("Failed to migrate legacy config key '{}' with value '{}'", key, raw, e);
            return false;
        }
    }

    private static boolean parseBoolean(String value) {
        return "true".equalsIgnoreCase(value) || "yes".equalsIgnoreCase(value) || "1".equals(value);
    }

    private static List<String> parseLegacyStringList(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        String value = raw.trim();
        if (value.startsWith("[") && value.endsWith("]")) {
            value = value.substring(1, value.length() - 1);
        }
        if (value.isBlank()) return List.of();
        List<String> result = new ArrayList<>();
        for (String part : value.split(",")) {
            String token = part.trim();
            if (token.isEmpty()) continue;
            if (token.length() >= 2 && token.startsWith("\"") && token.endsWith("\"")) {
                token = token.substring(1, token.length() - 1);
            }
            result.add(token);
        }
        return result;
    }

    private static Map<String, String> readLegacyFlatToml(Path path) {
        Map<String, String> map = new HashMap<>();
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
            FTBQuestsSync.LOGGER.error("Failed to read legacy config {}", path, e);
        }
        return map;
    }
}
