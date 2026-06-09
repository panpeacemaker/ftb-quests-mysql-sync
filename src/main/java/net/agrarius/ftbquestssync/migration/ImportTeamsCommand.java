package net.agrarius.ftbquestssync.migration;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import dev.ftb.mods.ftbteams.api.FTBTeamsAPI;
import dev.ftb.mods.ftbteams.api.Team;
import dev.ftb.mods.ftbteams.api.TeamManager;
import net.agrarius.ftbquestssync.FTBQuestsSync;
import net.agrarius.ftbquestssync.MySQLBackend;
import net.agrarius.ftbquestssync.RedisSync;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Operator command: walks every FTB Teams party / solo team that
 * currently exists on the running server and upserts its row into
 * {@code ftbquests_team_info} and {@code ftbquests_team_membership}
 * with the real {@code serverId} (so it tags the rows as
 * "agr1" or "agr2" depending on which peer this ran on).
 *
 * Use on both agr1 and agr2 when the two backends are still hosting
 * separate per-server teams; the rows reconcile in the database by
 * {@code team_id} primary key.
 */
public final class ImportTeamsCommand {

    private static final SimpleCommandExceptionType NOT_OP =
            new SimpleCommandExceptionType(Component.literal("Only operators can run this command."));
    private static final SimpleCommandExceptionType NO_DB =
            new SimpleCommandExceptionType(Component.literal("FTB Sync MySQL is unavailable."));
    private static final SimpleCommandExceptionType NO_TEAMS_API =
            new SimpleCommandExceptionType(Component.literal("FTB Teams API is not loaded yet."));

    private ImportTeamsCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("ftbsync")
                .then(Commands.literal("importteams")
                        .requires(src -> src.hasPermission(2))
                        .executes(ctx -> runImportTeams(ctx.getSource()))));
    }

    private static int runImportTeams(CommandSourceStack source) throws CommandSyntaxException {
        if (!source.hasPermission(2)) throw NOT_OP.create();
        MySQLBackend db = MySQLBackend.getInstance();
        if (!db.isAvailable()) throw NO_DB.create();

        TeamManager mgr;
        try {
            mgr = FTBTeamsAPI.api().getManager();
        } catch (Throwable t) {
            throw NO_TEAMS_API.create();
        }
        if (mgr == null) throw NO_TEAMS_API.create();

        String serverId = RedisSync.getInstance().getServerId();
        MinecraftServer server = source.getServer();
        source.sendSuccess(() -> Component.literal("Importing FTB Teams into DB (serverId=" + serverId + ")"), true);
        FTBQuestsSync.LOGGER.info("/ftbsync importteams requested by {} on serverId={}",
                source.getTextName(), serverId);

        Thread t = new Thread(() -> doImport(server, mgr, serverId), "FTBQuestsSync-ImportTeams");
        t.setDaemon(true);
        t.start();
        return Command.SINGLE_SUCCESS;
    }

    private static void doImport(MinecraftServer server, TeamManager mgr, String serverId) {
        AtomicInteger teamsWritten = new AtomicInteger();
        AtomicInteger membershipsWritten = new AtomicInteger();
        try {
            for (Team team : mgr.getTeams()) {
                try {
                    UUID teamId = team.getId();
                    String type;
                    if (team.isPartyTeam()) type = "PARTY";
                    else if (team.isServerTeam()) type = "SERVER";
                    else if (team.isPlayerTeam()) type = "PLAYER";
                    else type = "UNKNOWN";
                    String name = team.getName().getString();
                    UUID owner = team.getOwner();
                    String color = "#000000";
                    try {
                        var colorProp = team.getProperty(dev.ftb.mods.ftbteams.api.property.TeamProperties.COLOR);
                        if (colorProp != null) color = colorProp.toString();
                    } catch (Throwable ignored) { }
                    MySQLBackend.getInstance().upsertTeamInfoAsync(teamId, type, name, owner, color);
                    teamsWritten.incrementAndGet();
                    for (UUID memberUuid : team.getMembers()) {
                        String rank = team.getRankForPlayer(memberUuid).name();
                        MySQLBackend.getInstance().upsertMembershipAsync(memberUuid, teamId, rank);
                        membershipsWritten.incrementAndGet();
                    }
                    FTBQuestsSync.LOGGER.info(
                            "importteams: team={} type={} name={} members={} serverId={}",
                            teamId, type, name, team.getMembers().size(), serverId);
                } catch (Exception e) {
                    FTBQuestsSync.LOGGER.warn("importteams: failed for team {}", team.getId(), e);
                }
            }
        } catch (Throwable th) {
            FTBQuestsSync.LOGGER.error("importteams: walk failed", th);
        }
        FTBQuestsSync.LOGGER.info(
                "importteams done: teamsWritten={} membershipsWritten={} serverId={}",
                teamsWritten.get(), membershipsWritten.get(), serverId);
    }
}
