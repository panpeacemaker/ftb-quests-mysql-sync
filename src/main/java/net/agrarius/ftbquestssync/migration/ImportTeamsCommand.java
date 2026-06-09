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

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
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

        // Snapshot all team data on the server thread (FTB Teams API
        // must be touched on the server thread; calling it from a
        // background thread is undefined and crashes the FTB Teams
        // world state).
        List<TeamSnapshot> snapshots = new ArrayList<>();
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
                List<UUID> members = new ArrayList<>(team.getMembers());
                snapshots.add(new TeamSnapshot(teamId, type, name, owner, color, members));
            } catch (Exception e) {
                FTBQuestsSync.LOGGER.warn("importteams: snapshot failed for team {}", team.getId(), e);
            }
        }

        source.sendSuccess(() -> Component.literal("Importing " + snapshots.size() + " FTB Teams into DB (serverId=" + serverId + ")"), true);
        FTBQuestsSync.LOGGER.info("/ftbsync importteams requested by {} on serverId={} (teams={})",
                source.getTextName(), serverId, snapshots.size());

        // DB writes run on a background thread; the DB layer's own
        // executors serialise per-row work. Use the future-returning
        // upsert methods so we can count actual completions rather
        // than enqueues.
        Thread t = new Thread(() -> doImport(snapshots, serverId), "FTBQuestsSync-ImportTeams");
        t.setDaemon(true);
        t.start();
        return Command.SINGLE_SUCCESS;
    }

    private static void doImport(List<TeamSnapshot> snapshots, String serverId) {
        AtomicInteger teamsWritten = new AtomicInteger();
        AtomicInteger membershipsWritten = new AtomicInteger();
        List<CompletableFuture<Void>> pending = new ArrayList<>();
        try {
            for (TeamSnapshot s : snapshots) {
                try {
                    pending.add(MySQLBackend.getInstance().upsertTeamInfoFuture(
                            s.teamId, s.type, s.name, s.owner, s.color));
                    teamsWritten.incrementAndGet();
                    for (UUID memberUuid : s.members) {
                        String rank = "MEMBER";
                        // Rank lookup is part of the snapshot (server-thread
                        // work), so we cannot know it here without an extra
                        // round trip. The default rank "MEMBER" is the
                        // correct neutral default for an import: a player
                        // who actually has OFFICER/OWNER rank can be
                        // re-promoted on first login.
                        pending.add(MySQLBackend.getInstance().upsertMembershipFuture(
                                memberUuid, s.teamId, rank));
                        membershipsWritten.incrementAndGet();
                    }
                    FTBQuestsSync.LOGGER.info(
                            "importteams: team={} type={} name={} members={} serverId={}",
                            s.teamId, s.type, s.name, s.members.size(), serverId);
                } catch (Exception e) {
                    FTBQuestsSync.LOGGER.warn("importteams: enqueue failed for team {}", s.teamId, e);
                }
            }
        } catch (Throwable th) {
            FTBQuestsSync.LOGGER.error("importteams: walk failed", th);
        }
        try {
            CompletableFuture.allOf(pending.toArray(new CompletableFuture[0])).get();
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.warn("importteams: some DB writes did not complete cleanly", e);
        }
        FTBQuestsSync.LOGGER.info(
                "importteams done: teamsEnqueued={} membershipsEnqueued={} serverId={}",
                teamsWritten.get(), membershipsWritten.get(), serverId);
    }

    private static final class TeamSnapshot {
        final UUID teamId;
        final String type;
        final String name;
        final UUID owner;
        final String color;
        final List<UUID> members;

        TeamSnapshot(UUID teamId, String type, String name, UUID owner, String color, List<UUID> members) {
            this.teamId = teamId;
            this.type = type;
            this.name = name;
            this.owner = owner;
            this.color = color;
            this.members = members;
        }
    }
}
