package net.agrarius.ftbquestssync.migration;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import net.agrarius.ftbquestssync.Config;
import net.agrarius.ftbquestssync.FTBQuestsSync;
import net.agrarius.ftbquestssync.MySQLBackend;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

public final class MigrationCommand {

    private static final SimpleCommandExceptionType NOT_OP =
            new SimpleCommandExceptionType(Component.literal("Only operators can run migration."));
    private static final SimpleCommandExceptionType NOT_ENABLED =
            new SimpleCommandExceptionType(Component.literal(
                    "Migration is not enabled. Set migrationRunOnBoot=true (or pass -Dftbquestssync.migration.runOnBoot=true) in the config."));
    private static final SimpleCommandExceptionType NO_DB =
            new SimpleCommandExceptionType(Component.literal("FTB Sync MySQL is unavailable."));

    private MigrationCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("ftbsync")
                .then(Commands.literal("migrate")
                        .requires(src -> src.hasPermission(2))
                        .executes(ctx -> runMigrate(ctx.getSource(), null, null))
                        .then(Commands.argument("dryRun", BoolArgumentType.bool())
                                .executes(ctx -> runMigrate(ctx.getSource(),
                                        BoolArgumentType.getBool(ctx, "dryRun"), null)))
                        .then(Commands.argument("maxPlayers", com.mojang.brigadier.arguments.IntegerArgumentType.integer(0))
                                .executes(ctx -> runMigrate(ctx.getSource(), null,
                                        com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "maxPlayers"))))
                        .then(Commands.argument("dryRun", BoolArgumentType.bool())
                                .then(Commands.argument("maxPlayers",
                                        com.mojang.brigadier.arguments.IntegerArgumentType.integer(0))
                                        .executes(ctx -> runMigrate(ctx.getSource(),
                                                BoolArgumentType.getBool(ctx, "dryRun"),
                                                com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "maxPlayers")))))));
    }

    private static int runMigrate(CommandSourceStack source, Boolean dryRunOverride, Integer maxOverride) throws CommandSyntaxException {
        if (!source.hasPermission(2)) throw NOT_OP.create();
        if (!Config.migrationRunOnBoot) {
            throw NOT_ENABLED.create();
        }
        MySQLBackend db = MySQLBackend.getInstance();
        if (!db.isAvailable()) throw NO_DB.create();

        if (dryRunOverride != null) {
            Config.migrationDryRun = dryRunOverride;
        }
        if (maxOverride != null) {
            Config.migrationMaxPlayers = maxOverride;
        }

        final String dryLabel = String.valueOf(Config.migrationDryRun);
        final int maxLabel = Config.migrationMaxPlayers;
        source.sendSuccess(() -> Component.literal(
                "Starting legacy quest migration: dryRun=" + dryLabel + " maxPlayers=" + maxLabel), true);
        FTBQuestsSync.LOGGER.info("Manual migration requested by {}: dryRun={} maxPlayers={}",
                source.getTextName(), dryLabel, maxLabel);

        Thread t = new Thread(LegacyQuestMigrator::runNow, "FTBQuestsSync-Migration-Manual");
        t.setDaemon(true);
        t.start();
        return Command.SINGLE_SUCCESS;
    }
}
