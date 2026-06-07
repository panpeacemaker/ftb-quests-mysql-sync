package net.agrarius.ftbquestssync;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import dev.ftb.mods.ftbteams.api.FTBTeamsAPI;
import dev.ftb.mods.ftbteams.api.Team;
import dev.ftb.mods.ftbteams.api.TeamManager;
import dev.ftb.mods.ftbteams.data.PartyTeam;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class FtbSyncTeamCommand {

    private static final SimpleCommandExceptionType NO_PARTY =
            new SimpleCommandExceptionType(Component.literal("You must be in a party team."));
    private static final SimpleCommandExceptionType NO_PERMISSION =
            new SimpleCommandExceptionType(Component.literal("Only the team owner or an operator can use this."));
    private static final SimpleCommandExceptionType DB_UNAVAILABLE =
            new SimpleCommandExceptionType(Component.literal("FTB Sync MySQL is unavailable."));
    private static final SimpleCommandExceptionType UNKNOWN_PLAYER =
            new SimpleCommandExceptionType(Component.literal(
                    "Unknown player profile. Player must have joined this network before or be a premium online-mode account."));
    private static final SimpleCommandExceptionType ALREADY_MEMBER =
            new SimpleCommandExceptionType(Component.literal("Player is already in this team."));
    private static final SimpleCommandExceptionType NOT_MEMBER =
            new SimpleCommandExceptionType(Component.literal("Player is not in this team."));
    private static final SimpleCommandExceptionType OWNER_KICK =
            new SimpleCommandExceptionType(Component.literal("Cannot kick the team owner."));
    private static final SimpleCommandExceptionType TRANSFER_NEEDS_MEMBER =
            new SimpleCommandExceptionType(Component.literal("New owner is not a member; invite/add first."));
    private static final Duration PROFILE_LOOKUP_TIMEOUT = Duration.ofSeconds(5);
    private static final HttpClient PROFILE_HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    private FtbSyncTeamCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("ftbsync")
                .then(Commands.literal("team")
                        .then(Commands.literal("invite")
                                .then(Commands.argument("player", StringArgumentType.word())
                                        .executes(ctx -> invite(ctx.getSource(), StringArgumentType.getString(ctx, "player")))))
                        .then(Commands.literal("kick")
                                .then(Commands.argument("player", StringArgumentType.word())
                                        .executes(ctx -> kick(ctx.getSource(), StringArgumentType.getString(ctx, "player")))))
                        .then(Commands.literal("transfer")
                                .then(Commands.argument("player", StringArgumentType.word())
                                        .executes(ctx -> transfer(ctx.getSource(), StringArgumentType.getString(ctx, "player"))))))
                .then(Commands.literal("chunks")
                        .requires(src -> src.hasPermission(2))
                        .then(Commands.literal("setbonus")
                                .then(Commands.argument("player", StringArgumentType.word())
                                        .then(Commands.argument("amount", com.mojang.brigadier.arguments.IntegerArgumentType.integer(0, 4096))
                                                .executes(ctx -> setChunkBonus(
                                                        ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "player"),
                                                        com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "amount"))))))));
    }

    private static int setChunkBonus(CommandSourceStack source, String targetName, int bonusForceLoad) throws CommandSyntaxException {
        MinecraftServer server = source.getServer();
        UUID targetId = resolvePlayerUuid(server, targetName);
        Team team = FTBTeamsAPI.api().getManager().getTeamForPlayerID(targetId).orElse(null);
        if (team == null) {
            source.sendFailure(Component.literal("Player has no team yet: " + targetName));
            return 0;
        }
        if (!dev.ftb.mods.ftbchunks.api.FTBChunksAPI.api().isManagerLoaded()) {
            source.sendFailure(Component.literal("FTB Chunks manager not loaded yet."));
            return 0;
        }
        try {
            dev.ftb.mods.ftbchunks.api.ChunkTeamData data =
                    dev.ftb.mods.ftbchunks.api.FTBChunksAPI.api().getManager().getOrCreateData(team);
            data.setExtraForceLoadChunks(bonusForceLoad);
            source.sendSuccess(() -> Component.literal(
                    "Set bonus force-load chunks for " + targetName + " (team " + team.getId() + ") to " + bonusForceLoad), true);
            FTBQuestsSync.LOGGER.info("Chunk bonus set via command: player={} team={} bonusForceLoad={}",
                    targetId, team.getId(), bonusForceLoad);
            return Command.SINGLE_SUCCESS;
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.error("setChunkBonus failed player={} team={}", targetId, team.getId(), e);
            source.sendFailure(Component.literal("Failed to set chunk bonus: " + e.getMessage()));
            return 0;
        }
    }

    private static int invite(CommandSourceStack source, String targetName) throws CommandSyntaxException {
        TeamContext ctx = requireTeamContext(source);
        UUID targetId = resolvePlayerUuid(ctx.server(), targetName);
        GameProfile target = new GameProfile(targetId, targetName);
        MySQLBackend db = MySQLBackend.getInstance();
        requireDb(db);
        MySQLBackend.TeamMembershipRow current = db.selectMembership(targetId).orElse(null);
        if (current != null && ctx.team().getId().equals(current.teamId())) throw ALREADY_MEMBER.create();

        TeamSync.getInstance().persistTeamNow(ctx.team());
        db.upsertMembership(targetId, ctx.team().getId(), "MEMBER");
        TeamSync.getInstance().publishMemberAdd(ctx.team().getId(), targetId);
        ctx.server().execute(() -> applyMemberAddLocal(ctx.server(), ctx.team().getId(), targetId, "MEMBER"));
        source.sendSuccess(() -> Component.literal("FTB Sync invite/add queued for " + target.getName()), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int kick(CommandSourceStack source, String targetName) throws CommandSyntaxException {
        TeamContext ctx = requireTeamContext(source);
        UUID targetId = resolvePlayerUuid(ctx.server(), targetName);
        GameProfile target = new GameProfile(targetId, targetName);
        MySQLBackend db = MySQLBackend.getInstance();
        requireDb(db);
        UUID partyTeamId = ctx.team().getId();
        MySQLBackend.TeamInfoRow info = db.selectTeamInfo(partyTeamId).orElse(null);
        UUID owner = info != null && info.owner() != null ? info.owner() : ctx.team().getOwner();
        if (targetId.equals(owner)) throw OWNER_KICK.create();
        MySQLBackend.TeamMembershipRow membership = db.selectMembership(targetId).orElse(null);
        if (membership == null || !partyTeamId.equals(membership.teamId())) throw NOT_MEMBER.create();

        db.upsertMembership(targetId, targetId, "OWNER");
        db.migratePartyMemberToSoloAsync(partyTeamId, targetId).whenComplete((result, err) -> {
            if (err != null) {
                FTBQuestsSync.LOGGER.error(
                        "Party-to-solo quest migration failed party={} player={}",
                        partyTeamId, targetId, err);
                return;
            }
            if (result != null) {
                RedisSync.getInstance().publishTeamUpdate(targetId, result.revision, result.hashHex);
            }
        });
        TeamSync.getInstance().publishMemberKick(partyTeamId, targetId);
        db.loadTeamMaterializationAsync(targetId).whenComplete((row, err) -> {
            if (err != null) {
                FTBQuestsSync.LOGGER.warn("Local kick reconcile DB reload failed player={}", targetId, err);
                return;
            }
            ctx.server().execute(() -> applyMemberKickLocal(ctx.server(), partyTeamId, targetId, row));
        });
        source.sendSuccess(() -> Component.literal("FTB Sync kick queued for " + target.getName()), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int transfer(CommandSourceStack source, String targetName) throws CommandSyntaxException {
        TeamContext ctx = requireTeamContext(source);
        UUID targetId = resolvePlayerUuid(ctx.server(), targetName);
        GameProfile target = new GameProfile(targetId, targetName);
        MySQLBackend db = MySQLBackend.getInstance();
        requireDb(db);
        MySQLBackend.TeamMembershipRow membership = db.selectMembership(targetId).orElse(null);
        if (membership == null || !ctx.team().getId().equals(membership.teamId())) throw TRANSFER_NEEDS_MEMBER.create();

        TeamSync.getInstance().persistTeamNow(ctx.team());
        if (!db.updateTeamOwner(ctx.team().getId(), targetId)) throw DB_UNAVAILABLE.create();
        TeamSync.getInstance().publishOwnerTransfer(ctx.team().getId(), targetId);
        db.loadTeamStateAsync(ctx.team().getId()).whenComplete((state, err) -> {
            if (err != null) {
                FTBQuestsSync.LOGGER.warn("Local owner_transfer reconcile DB reload failed team={}", ctx.team().getId(), err);
                return;
            }
            List<MySQLBackend.TeamMemberRow> members = state == null ? List.of() : state.members();
            ctx.server().execute(() -> applyOwnerTransferLocal(ctx.server(), source, ctx.team().getId(), target, members));
        });
        source.sendSuccess(() -> Component.literal("FTB Sync ownership transfer queued for " + target.getName()), false);
        return Command.SINGLE_SUCCESS;
    }

    static void applyMemberAddLocal(MinecraftServer server, UUID teamId, UUID playerId, String rank) {
        TeamManager mgr = FTBTeamsAPI.api().getManager();
        if (!TeamMaterializer.ensureTeamMaterialized(teamId)) return;
        Team team = mgr.getTeamByID(teamId).orElse(null);
        if (team == null) return;
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);

        try (TeamMutationGuard.Scope teamScope = TeamMutationGuard.suppressTeam(teamId);
             TeamMutationGuard.Scope playerScope = TeamMutationGuard.suppressPlayer(playerId)) {
            TeamMaterializer.addPlayerToTeamReflective(team, playerId, rank == null ? "MEMBER" : rank);
            if (player != null) TeamMaterializer.forcePlayerToDbTeam(mgr, playerId, team);
        }
        TeamMaterializer.markTeamDirtyAndSync(mgr, team);
        if (player != null) TeamSync.getInstance().forceFullSyncToPlayer(player, teamId);
    }

    static void applyMemberKickLocal(MinecraftServer server, UUID teamId, UUID playerId,
                                     MySQLBackend.TeamMaterializationRow row) {
        TeamManager mgr = FTBTeamsAPI.api().getManager();
        Team oldTeam = mgr.getTeamByID(teamId).orElse(null);
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        boolean oldTeamContainsPlayer = oldTeam != null && oldTeam.getMembers().contains(playerId);
        if (player == null && !oldTeamContainsPlayer) return;
        UUID targetId = row == null || row.membership() == null || teamId.equals(row.membership().teamId())
                ? playerId
                : row.membership().teamId();
        Team dbTeam = null;

        try (TeamMutationGuard.Scope teamScope = TeamMutationGuard.suppressTeam(teamId);
             TeamMutationGuard.Scope dbTeamScope = teamId.equals(targetId)
                     ? null : TeamMutationGuard.suppressTeam(targetId);
             TeamMutationGuard.Scope playerScope = TeamMutationGuard.suppressPlayer(playerId)) {
            dbTeam = targetId.equals(playerId)
                    ? player != null ? mgr.getPlayerTeamForPlayerID(playerId).orElse(null) : null
                    : resolveDbTeam(mgr, row, playerId, player != null);
            if (oldTeam != null) TeamMaterializer.removePlayerFromTeamReflective(oldTeam, playerId);
            if (player != null) {
                if (targetId.equals(playerId)) {
                    Team solo = mgr.getPlayerTeamForPlayerID(playerId).orElse(null);
                    if (solo != null) {
                        dbTeam = solo;
                        TeamMaterializer.forcePlayerToDbTeam(mgr, playerId, solo);
                    } else if (oldTeam != null) {
                        TeamMaterializer.removePlayerFromTeamReflective(oldTeam, playerId);
                    }
                } else if (dbTeam != null) {
                    TeamMaterializer.addPlayerToTeamReflective(dbTeam, playerId, row.membership().rank());
                    TeamMaterializer.forcePlayerToDbTeam(mgr, playerId, dbTeam);
                }
            } else if (dbTeam != null && !targetId.equals(playerId)) {
                TeamMaterializer.addPlayerToTeamReflective(dbTeam, playerId, row.membership().rank());
            }
        }
        if (oldTeam != null) TeamMaterializer.markTeamDirtyAndSync(mgr, oldTeam);
        if (dbTeam != null && !dbTeam.getId().equals(teamId)) {
            TeamMaterializer.markTeamDirtyAndSync(mgr, dbTeam);
        }
        if (player != null) {
            UUID syncTeam = dbTeam != null ? dbTeam.getId() : playerId;
            TeamSync.getInstance().forceFullSyncToPlayer(player, syncTeam);
        }
    }

    static void applyOwnerTransferLocal(MinecraftServer server, CommandSourceStack source, UUID teamId,
                                        GameProfile newOwner, List<MySQLBackend.TeamMemberRow> members) {
        TeamManager mgr = FTBTeamsAPI.api().getManager();
        if (!TeamMaterializer.ensureTeamMaterialized(teamId)) return;
        Team team = mgr.getTeamByID(teamId).orElse(null);
        if (team == null) return;
        ServerPlayer player = server.getPlayerList().getPlayer(newOwner.getId());
        boolean changed;

        try (TeamMutationGuard.Scope teamScope = TeamMutationGuard.suppressTeam(teamId);
             TeamMutationGuard.Scope playerScope = TeamMutationGuard.suppressPlayer(newOwner.getId())) {
            TeamMaterializer.applyMembershipSnapshot(team, members, newOwner.getId());
            changed = TeamMaterializer.transferPartyOwnership(team, source, newOwner);
        }
        if (changed) TeamMaterializer.markTeamDirtyAndSync(mgr, team);
        if (player != null) TeamSync.getInstance().forceFullSyncToPlayer(player, teamId);
    }

    private static Team resolveDbTeam(TeamManager mgr, MySQLBackend.TeamMaterializationRow row,
                                      UUID playerId, boolean playerOnline) {
        if (row == null || row.membership() == null) return null;
        UUID dbTeamId = row.membership().teamId();
        if (dbTeamId.equals(playerId)) {
            return playerOnline ? mgr.getPlayerTeamForPlayerID(playerId).orElse(null) : null;
        }
        if (row.info() == null || row.info().deleted()) return null;
        if (!TeamMaterializer.ensureTeamMaterialized(dbTeamId)) return null;
        Team dbTeam = mgr.getTeamByID(dbTeamId).orElse(null);
        if (dbTeam != null) {
            for (MySQLBackend.TeamMemberRow member : row.members()) {
                TeamMaterializer.addPlayerToTeamReflective(dbTeam, member.playerUuid(), member.rank());
            }
        }
        return dbTeam;
    }

    private static TeamContext requireTeamContext(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        Team team = FTBTeamsAPI.api().getManager().getTeamForPlayer(player).orElseThrow(NO_PARTY::create);
        if (!team.isPartyTeam()) throw NO_PARTY.create();
        boolean owner = team instanceof PartyTeam partyTeam && partyTeam.isOwner(player.getUUID());
        if (!owner && !source.hasPermission(2)) {
            throw NO_PERMISSION.create();
        }
        return new TeamContext(source.getServer(), player, team);
    }

    private static UUID resolvePlayerUuid(MinecraftServer server, String name) throws CommandSyntaxException {
        GameProfile profile = server.getProfileCache().get(name).orElse(null);
        if (profile != null && profile.getId() != null) return profile.getId();

        Optional<UUID> dbUuid = MySQLBackend.getInstance().selectPlayerUuidByName(name);
        if (dbUuid.isPresent()) return dbUuid.get();

        Optional<UUID> mojangUuid = resolveMojangUuid(name);
        if (mojangUuid.isPresent()) return mojangUuid.get();

        throw UNKNOWN_PLAYER.create();
    }

    private static Optional<UUID> resolveMojangUuid(String name) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.mojang.com/users/profiles/minecraft/" + name))
                    .timeout(PROFILE_LOOKUP_TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<String> response = PROFILE_HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return Optional.empty();
            String id = jsonString(response.body(), "id");
            return id == null ? Optional.empty() : Optional.of(uuidFromUndashed(id));
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.warn("Mojang profile lookup failed for {}", name, e);
            return Optional.empty();
        }
    }

    private static String jsonString(String json, String key) {
        String needle = "\"" + key + "\"";
        int keyIndex = json.indexOf(needle);
        if (keyIndex < 0) return null;
        int colon = json.indexOf(':', keyIndex + needle.length());
        if (colon < 0) return null;
        int firstQuote = json.indexOf('"', colon + 1);
        if (firstQuote < 0) return null;
        int secondQuote = json.indexOf('"', firstQuote + 1);
        if (secondQuote < 0) return null;
        return json.substring(firstQuote + 1, secondQuote);
    }

    private static UUID uuidFromUndashed(String id) {
        String raw = id.replace("-", "");
        if (raw.length() != 32) throw new IllegalArgumentException("Bad Mojang UUID length");
        return UUID.fromString(raw.substring(0, 8) + "-"
                + raw.substring(8, 12) + "-"
                + raw.substring(12, 16) + "-"
                + raw.substring(16, 20) + "-"
                + raw.substring(20));
    }

    private static void requireDb(MySQLBackend db) throws CommandSyntaxException {
        if (!db.isAvailable()) throw DB_UNAVAILABLE.create();
    }

    private record TeamContext(MinecraftServer server, ServerPlayer player, Team team) {
    }
}
