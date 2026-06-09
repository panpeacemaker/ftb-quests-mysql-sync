package net.agrarius.ftbquestssync.migration;

import net.agrarius.ftbquestssync.Config;

/**
 * Immutable snapshot of migration settings taken at the moment a run is
 * scheduled. Passing an explicit value through {@link LegacyQuestMigrator#runNow}
 * avoids the operator-command-mutates-global-Config race the original
 * implementation exposed (a second invocation could change the first run's
 * options before its thread entered the work loop).
 */
public final class MigrationOptions {

    public final boolean dryRun;
    public final int maxPlayers;
    public final String serverIdTag;
    public final String mysqlHost;
    public final boolean remapUuids;
    public final String usercachePath;

    public MigrationOptions(boolean dryRun, int maxPlayers, String serverIdTag, String mysqlHost,
                            boolean remapUuids, String usercachePath) {
        this.dryRun = dryRun;
        this.maxPlayers = maxPlayers;
        this.serverIdTag = serverIdTag == null || serverIdTag.isBlank() ? "migrator" : serverIdTag;
        this.mysqlHost = mysqlHost;
        this.remapUuids = remapUuids;
        this.usercachePath = usercachePath;
    }

    public static MigrationOptions fromConfig() {
        return new MigrationOptions(
                Config.migrationDryRun,
                Config.migrationMaxPlayers,
                Config.migrationServerIdTag,
                Config.migrationSourceMysqlHost,
                Config.migrationRemapUuids,
                Config.migrationUsercachePath);
    }
}
