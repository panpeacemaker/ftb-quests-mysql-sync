package net.agrarius.ftbquestssync.teams;

import com.mojang.authlib.GameProfile;
import dev.ftb.mods.ftbteams.api.FTBTeamsAPI;
import dev.ftb.mods.ftbteams.api.Team;
import dev.ftb.mods.ftbteams.api.TeamManager;
import dev.ftb.mods.ftbteams.api.TeamRank;
import dev.ftb.mods.ftbteams.data.PartyTeam;
import dev.ftb.mods.ftbteams.api.event.PlayerChangedTeamEvent;
import dev.ftb.mods.ftbteams.api.event.PlayerJoinedPartyTeamEvent;
import dev.ftb.mods.ftbteams.api.event.PlayerLeftPartyTeamEvent;
import dev.ftb.mods.ftbteams.api.event.PlayerLoggedInAfterTeamEvent;
import dev.ftb.mods.ftbteams.api.event.PlayerTransferredTeamOwnershipEvent;
import dev.ftb.mods.ftbteams.api.event.TeamCreatedEvent;
import dev.ftb.mods.ftbteams.api.event.TeamEvent;
import dev.ftb.mods.ftbteams.api.property.TeamProperties;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPubSub;
import redis.clients.jedis.Protocol;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import net.agrarius.ftbquestssync.FTBQuestsSync;
import net.agrarius.ftbquestssync.MySQLBackend;
import net.agrarius.ftbquestssync.command.FtbSyncTeamCommand;
import net.agrarius.ftbquestssync.config.Config;
import net.agrarius.ftbquestssync.RedisSync;
import net.agrarius.ftbquestssync.chunks.ChunkMaterializer;
import net.agrarius.ftbquestssync.teams.model.TeamInfoRow;
import net.agrarius.ftbquestssync.teams.model.TeamInviteRow;
import net.agrarius.ftbquestssync.teams.model.TeamMemberRow;
import net.agrarius.ftbquestssync.teams.model.TeamMembershipRow;

/**
 * Bidirectional FTB Teams membership/lifecycle sync.
 *
 * Symmetric design — each server is both producer and consumer:
 *   1. Local event (architectury TeamEvent.*) →
 *      persist to MySQL (ftbquests_team_info + ftbquests_team_membership) →
 *      publish on Redis "{@value #CHANNEL}".
 *   2. Remote Redis message →
 *      ignore own server's messages →
 *      log + queue reconciliation. Actual local team mutation happens on the
 *      next player login via {@link #reconcileOnLogin(net.minecraft.server.level.ServerPlayer)}
 *      because FTB Teams has no public API to forcibly add an arbitrary
 *      player to an arbitrary existing team — but the server-thread login
 *      handler can use {@link TeamManager#createPartyTeam} or move a player
 *      between teams once the player is online.
 *
 * Out of scope of v1 (documented for follow-up): forcibly creating a remote
 * team on the local server before its owner logs in. For now we rely on the
 * login-time hook — the same player that created the team on the other
 * server triggers its materialization here when they connect.
 */
public final class TeamSync {

    private static final TeamSync INSTANCE = new TeamSync();
    private static final String CHANNEL = "ftbquests:team:membership";
    private static final long DISCONNECTING_TTL_MS = 10_000L;

    private final ConcurrentHashMap<String, Long> recentEmits = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, String[]> lastSyncedProps = new ConcurrentHashMap<>();
    private final Map<UUID, Long> disconnecting = new ConcurrentHashMap<>();
    private final Map<String, Long> explicitKicks = new ConcurrentHashMap<>();
    private static final long EXPLICIT_KICK_TTL_MS = 30_000L;

    private JedisPool pool;
    private ExecutorService subscriberExec;
    private Jedis subscriberConn;
    private MinecraftServer server;
    private volatile boolean enabled;

    private TeamSync() {
    }

    public static TeamSync getInstance() {
        return INSTANCE;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void registerEventListeners() {
        if (!Config.syncTeams) {
            FTBQuestsSync.LOGGER.info("TeamSync listener registration skipped: syncTeams=false");
            return;
        }
        TeamEvent.CREATED.register(this::onCreated);
        TeamEvent.DELETED.register(this::onDeleted);
        TeamEvent.PLAYER_JOINED_PARTY.register(this::onJoinedParty);
        TeamEvent.PLAYER_LEFT_PARTY.register(this::onLeftParty);
        TeamEvent.PLAYER_CHANGED.register(this::onPlayerChanged);
        TeamEvent.OWNERSHIP_TRANSFERRED.register(this::onOwnershipTransferred);
        TeamEvent.PLAYER_LOGGED_IN.register(this::onPlayerLoggedIn);
        TeamEvent.PROPERTIES_CHANGED.register(this::onPropertiesChanged);
        FTBQuestsSync.LOGGER.info("TeamSync event listeners registered");
    }

    public void initializeRedis(MinecraftServer server) {
        if (!Config.syncTeams) {
            FTBQuestsSync.LOGGER.info("TeamSync Redis init skipped: syncTeams=false");
            return;
        }
        this.server = server;
        try {
            String password = Config.redisPassword.isBlank() ? null : Config.redisPassword;
            pool = new JedisPool(new GenericObjectPoolConfig<>(), Config.redisHost, Config.redisPort,
                    Protocol.DEFAULT_TIMEOUT, password);
            try (Jedis jedis = pool.getResource()) {
                String pong = jedis.ping();
                if (!"PONG".equals(pong)) throw new IllegalStateException("PING got " + pong);
            }
            enabled = true;
            subscriberExec = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "FTBQuestsSync-Teams-Sub");
                t.setDaemon(true);
                return t;
            });
            subscriberExec.submit(this::subscribeLoop);
            FTBQuestsSync.LOGGER.info("TeamSync Redis ready: channel={}", CHANNEL);
        } catch (Exception e) {
            enabled = false;
            FTBQuestsSync.LOGGER.error("TeamSync Redis init failed - team membership cross-server live invalidation disabled", e);
        }
    }

    public void shutdown() {
        enabled = false;
        try {
            if (subscriberConn != null) subscriberConn.disconnect();
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.debug("TeamSync subscriber disconnect during shutdown failed", e);
        }
        if (subscriberExec != null) subscriberExec.shutdownNow();
        if (pool != null) pool.close();
    }

    private void onCreated(TeamCreatedEvent ev) {
        if (!Config.syncTeams) return;
        Team team = ev.getTeam();
        if (TeamMutationGuard.isTeamSuppressed(team.getId())) return;
        persistTeam(team);
        publish("created", team.getId());
    }

    private void onDeleted(TeamEvent ev) {
        if (!Config.syncTeams) return;
        Team team = ev.getTeam();
        UUID teamId = team.getId();
        if (TeamMutationGuard.isTeamSuppressed(teamId)) return;
        List<UUID> memberUuids = MySQLBackend.getInstance().selectTeamMembers(teamId).stream()
                .map(TeamMemberRow::playerUuid)
                .toList();
        MySQLBackend.getInstance().migratePartyToSoloMembers(teamId, memberUuids);
        MySQLBackend.getInstance().markTeamDeletedAsync(teamId);
        MySQLBackend.getInstance().deleteTeamInvitesForTeamAsync(teamId);
        publish("deleted", teamId);
        publishInviteCancel(teamId, null);
    }

    private void onJoinedParty(PlayerJoinedPartyTeamEvent ev) {
        if (!Config.syncTeams) return;
        Team team = ev.getTeam();
        UUID playerId = ev.getPlayer().getUUID();
        if (TeamMutationGuard.isTeamSuppressed(team.getId())) return;
        persistTeam(team);
        MySQLBackend.getInstance().acceptMembershipFuture(playerId, team.getId(), rankName(team, playerId))
                .whenComplete((v, e) -> {
                    if (e != null) {
                        FTBQuestsSync.LOGGER.error("member add persist failed player={} team={}", playerId, team.getId(), e);
                        return;
                    }
                    publish("member_add", team.getId(), playerId);
                });
    }

    private void onLeftParty(PlayerLeftPartyTeamEvent ev) {
        if (!Config.syncTeams) return;
        Team partyTeam = ev.getTeam();
        UUID partyTeamId = partyTeam.getId();
        UUID playerId = ev.getPlayer().getUUID();
        if (TeamMutationGuard.isTeamSuppressed(partyTeamId)) return;
        scheduleConfirmedPartyExit(ev.getPlayer(), playerId, partyTeamId);
    }

    private void onPlayerChanged(PlayerChangedTeamEvent ev) {
        if (!Config.syncTeams) return;
        Team team = ev.getTeam();
        UUID playerId = ev.getPlayerId();
        if (TeamMutationGuard.isTeamSuppressed(team.getId())) return;
        if (TeamMutationGuard.isPlayerSuppressed(playerId)) return;
        persistTeam(team);
        // A change INTO the player's own solo team is non-authoritative: it fires
        // during FTB Teams' login auto-creation, BEFORE the async login materialize
        // can read the DB. An unconditional upsert here would clobber a cross-server
        // party membership (player_uuid PK) before convergence runs, stranding the
        // invitee solo. Use the guarded upsert that only writes when no row exists
        // or it already points at this same solo team. A real party exit has a
        // previous party team, so persist that solo membership authoritatively.
        boolean ownSolo = team.isPlayerTeam() && team.getId().equals(playerId);
        boolean realPartyExit = ownSolo && ev.getPreviousTeam().isPresent() && ev.getPreviousTeam().get().isPartyTeam();
        if (realPartyExit) {
            UUID previousPartyId = ev.getPreviousTeam().get().getId();
            scheduleConfirmedPartyExit(null, playerId, previousPartyId);
            return;
        }
        if (ownSolo) {
            MySQLBackend.getInstance().upsertOwnPlayerMembershipIfAbsentOrSelfAsync(
                    playerId, rankName(team, playerId));
        } else {
            persistMembership(playerId, team);
        }
        publish("changed", team.getId(), playerId);
    }

    public void markPlayerDisconnecting(UUID id) {
        if (id == null) return;
        disconnecting.put(id, System.currentTimeMillis() + DISCONNECTING_TTL_MS);
    }

    private boolean isPlayerDisconnecting(UUID id) {
        long now = System.currentTimeMillis();
        disconnecting.entrySet().removeIf(entry -> entry.getValue() < now);
        Long expiresAt = disconnecting.get(id);
        return expiresAt != null && expiresAt >= now;
    }

    public void markExplicitKick(UUID teamId, UUID playerId) {
        if (teamId == null || playerId == null) return;
        explicitKicks.put(teamId + "|" + playerId, System.currentTimeMillis() + EXPLICIT_KICK_TTL_MS);
    }

    private boolean consumeExplicitKick(UUID teamId, UUID playerId) {
        long now = System.currentTimeMillis();
        explicitKicks.entrySet().removeIf(entry -> entry.getValue() < now);
        return explicitKicks.remove(teamId + "|" + playerId) != null;
    }

    private void scheduleConfirmedPartyExit(ServerPlayer player, UUID playerId, UUID previousPartyId) {
        MinecraftServer srv = ServerLifecycleHooks.getCurrentServer();
        if (srv == null) {
            FTBQuestsSync.LOGGER.warn("Party exit confirm skipped: server unavailable player={} prevParty={}", playerId, previousPartyId);
            return;
        }
        srv.tell(new TickTask(srv.getTickCount() + 5, () -> confirmPartyExit(player, playerId, previousPartyId)));
    }

    private void confirmPartyExit(ServerPlayer originalPlayer, UUID playerId, UUID previousPartyId) {
        MinecraftServer srv = ServerLifecycleHooks.getCurrentServer();
        if (srv == null) {
            FTBQuestsSync.LOGGER.warn("Party exit confirm skipped: server unavailable player={} prevParty={}", playerId, previousPartyId);
            return;
        }

        boolean explicitKick = consumeExplicitKick(previousPartyId, playerId);
        ServerPlayer currentPlayer = srv.getPlayerList().getPlayer(playerId);
        if (!explicitKick && (currentPlayer == null || currentPlayer.connection == null || currentPlayer.isRemoved() || isPlayerDisconnecting(playerId))) {
            FTBQuestsSync.LOGGER.info("Suppressed transient party exit (server switch/disconnect): player={} prevParty={}", playerId, previousPartyId);
            return;
        }

        if (!explicitKick) {
            TeamManager mgr = FTBTeamsAPI.api().getManager();
            Team currentTeam = mgr.getTeamForPlayer(currentPlayer)
                    .or(() -> mgr.getPlayerTeamForPlayerID(playerId))
                    .orElse(null);
            if (currentTeam == null || previousPartyId.equals(currentTeam.getId())
                    || !(currentTeam.isPlayerTeam() && currentTeam.getId().equals(playerId))) {
                FTBQuestsSync.LOGGER.info("Suppressed transient party exit (server switch/disconnect): player={} prevParty={}", playerId, previousPartyId);
                return;
            }
        }

        FTBQuestsSync.LOGGER.info("Confirmed party leave: player={} prevParty={} explicitKick={}", playerId, previousPartyId, explicitKick);
        MySQLBackend.getInstance().upsertMembershipFuture(playerId, playerId, "OWNER")
                .whenComplete((v, e) -> {
                    if (e != null) {
                        FTBQuestsSync.LOGGER.error("kick solo-persist failed player={}", playerId, e);
                        return;
                    }
                    migrateMemberQuestStateToSolo(previousPartyId, playerId);
                    publish("member_kick", previousPartyId, playerId);
                });
    }

    private void onOwnershipTransferred(PlayerTransferredTeamOwnershipEvent ev) {
        if (!Config.syncTeams) return;
        Team team = ev.getTeam();
        if (TeamMutationGuard.isTeamSuppressed(team.getId())) return;
        UUID newOwner = ev.getToProfile() == null ? null : ev.getToProfile().getId();
        if (newOwner == null) newOwner = team.getOwner();
        if (newOwner == null) {
            FTBQuestsSync.LOGGER.warn("owner_transfer skipped: team={} has no new owner", team.getId());
            return;
        }
        UUID owner = newOwner;
        persistTeamFuture(team).thenApply(ignored -> MySQLBackend.getInstance().updateTeamOwner(team.getId(), owner))
                .whenComplete((updated, e) -> {
                    if (e != null) {
                        FTBQuestsSync.LOGGER.error("owner_transfer persist failed team={} owner={}", team.getId(), owner, e);
                        return;
                    }
                    if (!Boolean.TRUE.equals(updated)) {
                        FTBQuestsSync.LOGGER.warn("owner_transfer not published: DB owner update failed team={} owner={}",
                                team.getId(), owner);
                        return;
                    }
                    publish("owner_transfer", team.getId());
                });
    }

    private void onPlayerLoggedIn(PlayerLoggedInAfterTeamEvent ev) {
        reconcileOnLogin(ev.getPlayer());
    }

    private void onPropertiesChanged(dev.ftb.mods.ftbteams.api.event.TeamPropertiesChangedEvent ev) {
        Team team = ev.getTeam();
        if (TeamMutationGuard.isTeamSuppressed(team.getId())) return;
        if (!Config.syncTeams) return;
        String name = readName(team);
        String color = readColor(team);
        String oldColor = cachedColor(team.getId());
        persistTeamFuture(team).whenComplete((ignored, e) -> {
            if (e != null) {
                FTBQuestsSync.LOGGER.error("props persist failed team={}", team.getId(), e);
                return;
            }
            if (updatePropsCache(team.getId(), name, color)) {
                publish("props", team.getId());
            }
            if (!Objects.equals(oldColor, color)) {
                ChunkMaterializer.refreshTeamClaims(team.getId());
            }
        });
    }

    public void reconcileOnLogin(net.minecraft.server.level.ServerPlayer player) {
        if (!Config.syncTeams) return;
        UUID playerId = player.getUUID();

        // The DB (via the materializer) is the sole authority on login. The old
        // code also force-reloaded mgr.getTeamForPlayer(player) here, but on a
        // stale login that resolves to the player's pre-login (possibly deleted)
        // team and pushed it alongside the correct one, leaving the client on the
        // wrong team. Only the materializer resolves + pushes the DB team now.
        TeamMaterializer.materializeOnLogin(player);
        TeamMaterializer.materializePendingInvitesOnLogin(player);

        // Fallback only: when the player has NO DB membership row, persist their
        // current local team so a peer can materialize it. Resolve current INSIDE
        // this callback (after the DB confirmed no row) so we never act on a stale
        // pre-materialize team.
        MySQLBackend.getInstance().loadTeamMaterializationAsync(playerId).whenComplete((row, err) -> {
            if (err != null || row != null) return;
            player.getServer().execute(() -> {
                try {
                    TeamManager mgr = FTBTeamsAPI.api().getManager();
                    Team current = mgr.getTeamForPlayer(player)
                            .or(() -> mgr.getPlayerTeamForPlayerID(playerId))
                            .orElse(null);
                    if (current == null) return;
                    persistTeam(current);
                    // A player's auto-created solo team must never clobber a party
                    // membership another server may have written (membership PK is
                    // player_uuid). Use the guarded upsert that only touches the row
                    // when it is absent or already points to this same solo team.
                    if (current.isPlayerTeam() && current.getId().equals(playerId)) {
                        MySQLBackend.getInstance().upsertOwnPlayerMembershipIfAbsentOrSelfAsync(
                                playerId, rankName(current, playerId));
                    } else {
                        persistMembership(playerId, current);
                    }
                    FTBQuestsSync.LOGGER.info("Persisted missing team membership on login: player={} team={}",
                            playerId, current.getId());
                } catch (Exception e) {
                    FTBQuestsSync.LOGGER.debug("Could not persist team membership on login for player={}", playerId, e);
                }
            });
        });
    }

    public void forceFullSyncToPlayer(ServerPlayer player, UUID teamId) {
        if (!Config.syncTeams || player == null || teamId == null) return;
        player.getServer().execute(() -> {
            try {
                TeamManager mgr = FTBTeamsAPI.api().getManager();
                // syncAllToPlayer's selfTeam argument is what becomes the client's
                // self-team. Resolve the EXPLICIT teamId (the DB-authoritative team),
                // never mgr.getTeamForPlayer(player): on a stale login that still
                // returns the old/deleted team and would push it to the client,
                // overwriting the correct party (the divergence bug).
                Team team = mgr.getTeamByID(teamId).orElse(null);
                if (team == null) {
                    FTBQuestsSync.LOGGER.warn("FTB Teams full sync skipped: team {} not present for player={}",
                            teamId, player.getUUID());
                    return;
                }

                Class<?> abstractTeamClass = Class.forName("dev.ftb.mods.ftbteams.data.AbstractTeam");
                java.lang.reflect.Method syncAllToPlayer = mgr.getClass().getMethod(
                        "syncAllToPlayer", ServerPlayer.class, abstractTeamClass);
                syncAllToPlayer.invoke(mgr, player, team);
                FTBQuestsSync.LOGGER.info("Forced FTB Teams full sync to player={} team={}", player.getUUID(), team.getId());
            } catch (Exception e) {
                FTBQuestsSync.LOGGER.warn("Forced FTB Teams full sync failed for player={} team={}",
                        player.getUUID(), teamId, e);
            }
        });
    }

    public void persistTeamNow(Team team) {
        persistTeam(team, false);
    }

    private void persistTeam(Team team) {
        persistTeam(team, true);
    }

    private CompletableFuture<Void> persistTeamFuture(Team team) {
        if (team == null) return CompletableFuture.completedFuture(null);
        String type = teamType(team);
        String name = readName(team);
        String color = readColor(team);
        UUID owner = team.getOwner();
        return MySQLBackend.getInstance().upsertTeamInfoNoOwnerFuture(team.getId(), type, name, owner, color);
    }

    private void persistTeam(Team team, boolean async) {
        if (team == null) return;
        String type = teamType(team);
        String name = readName(team);
        String color = readColor(team);
        UUID owner = team.getOwner();
        if (async) MySQLBackend.getInstance().upsertTeamInfoNoOwnerAsync(team.getId(), type, name, owner, color);
        else MySQLBackend.getInstance().upsertTeamInfoNoOwner(team.getId(), type, name, owner, color);
    }

    private static String teamType(Team team) {
        if (team.isPartyTeam()) return "PARTY";
        if (team.isServerTeam()) return "SERVER";
        if (team.isPlayerTeam()) return "PLAYER";
        return "UNKNOWN";
    }

    private static String readName(Team team) {
        try {
            return team.getProperty(TeamProperties.DISPLAY_NAME);
        } catch (Exception e) {
            return team.getShortName();
        }
    }

    private static String readColor(Team team) {
        try {
            dev.ftb.mods.ftblibrary.icon.Color4I color = team.getProperty(TeamProperties.COLOR);
            return color == null ? null : TeamProperties.COLOR.toString(color);
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.debug("Could not read team color for {}", team.getId(), e);
            return null;
        }
    }

    private void persistMembership(UUID playerId, Team team) {
        if (team == null) return;
        MySQLBackend.getInstance().upsertMembershipAsync(playerId, team.getId(), rankName(team, playerId));
    }

    private void migrateMemberQuestStateToSolo(UUID partyTeamId, UUID playerId) {
        MySQLBackend.getInstance().migratePartyMemberToSoloAsync(partyTeamId, playerId)
                .whenComplete((result, error) -> {
                    if (error != null) {
                        FTBQuestsSync.LOGGER.error(
                                "Party-to-solo quest migration failed party={} player={}",
                                partyTeamId, playerId, error);
                        return;
                    }
                    if (result != null) {
                        RedisSync.getInstance().publishTeamUpdate(playerId, result.revision, result.hashHex);
                    }
                });
    }

    private static String rankName(Team team, UUID playerId) {
        TeamRank rank = team.getRankForPlayer(playerId);
        return rank == null ? "MEMBER" : rank.name();
    }

    private void publish(String reason, UUID teamId) {
        publish(reason, teamId, null);
    }

    public void publishMemberAdd(UUID teamId, UUID playerId) {
        publish("member_add", teamId, playerId);
    }

    public void publishInvite(UUID teamId, UUID playerId) {
        publish("invite", teamId, playerId);
    }

    public void publishInviteCancel(UUID teamId, UUID playerId) {
        publish("invite_cancel", teamId, playerId);
    }

    public void syncRankChange(MinecraftServer server, UUID teamId, UUID playerId, String rank) {
        if (!Config.syncTeams || server == null || teamId == null || playerId == null || rank == null) return;
        MySQLBackend.getInstance().upsertMembershipFuture(playerId, teamId, rank)
                .whenComplete((v, e) -> {
                    if (e != null) {
                        FTBQuestsSync.LOGGER.error("syncRankChange persist failed player={} team={} rank={}", playerId, teamId, rank, e);
                        return;
                    }
                    publish("member_add", teamId, playerId);
                });
    }

    public void queueInvite(ServerPlayer inviter, PartyTeam team, GameProfile target) {
        if (!Config.syncTeams || inviter == null || team == null || target == null || target.getId() == null) return;
        UUID teamId = team.getId();
        UUID targetId = target.getId();
        UUID inviterId = inviter.getUUID();
        MySQLBackend db = MySQLBackend.getInstance();

        TeamMembershipRow current = db.selectMembership(targetId).orElse(null);
        if (current != null && !targetId.equals(current.teamId())) {
            FTBQuestsSync.LOGGER.warn("Invite blocked: target={} already in party team={}", targetId, current.teamId());
            return;
        }
        TeamManager mgr = FTBTeamsAPI.api().getManager();
        Team localTeam = mgr.getTeamForPlayerID(targetId).orElse(null);
        if (localTeam != null && localTeam.isPartyTeam() && !localTeam.getId().equals(targetId)) {
            FTBQuestsSync.LOGGER.warn("Invite blocked: target={} already in local party team={}", targetId, localTeam.getId());
            return;
        }

        persistTeamNow(team);
        db.upsertTeamInvite(teamId, targetId, inviterId);
        try (TeamMutationGuard.Scope ignored = TeamMutationGuard.suppressTeam(teamId)) {
            TeamMaterializer.addPlayerToTeamReflective(team, targetId, "INVITED");
        }
        TeamMaterializer.markTeamDirtyAndSync(mgr, team);
        publishInvite(teamId, targetId);
        FTBQuestsSync.LOGGER.info("Queued cross-server invite: inviter={} team={} target={}", inviterId, teamId, targetId);
    }

    public void publishMemberKick(UUID teamId, UUID playerId) {
        publish("member_kick", teamId, playerId);
    }

    public void publishOwnerTransfer(UUID teamId, UUID playerId) {
        publish("owner_transfer", teamId, playerId);
    }

    private void publish(String reason, UUID teamId, UUID playerId) {
        if (!enabled) return;
        String payload = RedisSync.getInstance().getServerId() + "|" + reason + "|" + teamId
                + "|" + (playerId == null ? "-" : playerId);
        long now = System.currentTimeMillis();
        Long prev = recentEmits.put(payload, now);
        if (prev != null && now - prev < 50L) return;

        try (Jedis jedis = pool.getResource()) {
            jedis.publish(CHANNEL, payload);
            FTBQuestsSync.LOGGER.info("Published team event: {}", payload);
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.warn("Team Redis publish failed: {}", payload, e);
        }
    }

    private void subscribeLoop() {
        while (enabled && !Thread.currentThread().isInterrupted()) {
            try {
                subscriberConn = pool.getResource();
                subscriberConn.subscribe(new JedisPubSub() {
                    @Override
                    public void onMessage(String channel, String message) {
                        handleRemote(message);
                    }
                }, CHANNEL);
            } catch (Exception e) {
                if (!enabled) continue;
                FTBQuestsSync.LOGGER.warn("Team Redis subscriber error, reconnecting in 5s", e);
                try {
                    Thread.sleep(5_000L);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            } finally {
                try {
                    if (subscriberConn != null) subscriberConn.close();
                } catch (Exception e) {
                    FTBQuestsSync.LOGGER.debug("TeamSync subscriber close failed", e);
                }
                subscriberConn = null;
            }
        }
    }

    private void handleRemote(String message) {
        if (!Config.syncTeams) return;
        try {
            String[] parts = message.split("\\|", 4);
            if (parts.length < 4) return;
            String sourceServer = parts[0];
            String reason = parts[1];
            UUID teamId = UUID.fromString(parts[2]);
            String playerIdStr = parts[3];
            if (RedisSync.getInstance().getServerId().equals(sourceServer)) return;

            FTBQuestsSync.LOGGER.info("Received remote team event: source={} reason={} team={} player={}",
                    sourceServer, reason, teamId, playerIdStr);

            if ("props".equals(reason)) {
                handleRemoteProps(teamId);
                return;
            }
            if ("member_add".equals(reason)) {
                handleRemoteMemberAdd(teamId, UUID.fromString(playerIdStr));
                return;
            }
            if ("member_kick".equals(reason)) {
                handleRemoteMemberKick(teamId, UUID.fromString(playerIdStr));
                return;
            }
            if ("owner_transfer".equals(reason)) {
                handleRemoteOwnerTransfer(teamId);
                return;
            }
            if ("invite".equals(reason)) {
                handleRemoteInvite(teamId, UUID.fromString(playerIdStr));
                return;
            }
            if ("invite_cancel".equals(reason)) {
                handleRemoteInviteCancel(teamId, playerIdStr);
                return;
            }

            // If a specific player is mentioned and they are online on this
            // server, reconcile their team membership + quest data immediately.
            // This handles e.g. one player adding another to the party while
            // they are split across servers.
            if (!"-".equals(playerIdStr)) {
                UUID playerId = UUID.fromString(playerIdStr);
                server.execute(() -> {
                    net.minecraft.server.level.ServerPlayer p = server.getPlayerList().getPlayer(playerId);
                    if (p != null) {
                        reconcileOnLogin(p);
                        FTBQuestsSync.LOGGER.info(
                                "Remote team event triggered reconcile for player={} reason={}",
                                playerId, reason);
                    }
                });
            } else {
                // No specific player - reconcile all online members of the team
                server.execute(() -> {
                    Team team = FTBTeamsAPI.api().getManager().getTeamByID(teamId).orElse(null);
                    if (team == null) return;
                    for (net.minecraft.server.level.ServerPlayer p : team.getOnlineMembers()) {
                        reconcileOnLogin(p);
                    }
                    FTBQuestsSync.LOGGER.info(
                            "Remote team event triggered reconcile for all online members of team={} reason={}",
                            teamId, reason);
                });
            }
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.warn("Bad team Redis message: {}", message, e);
        }
    }

    private void handleRemoteProps(UUID teamId) {
        MySQLBackend.getInstance().selectTeamInfoAsync(teamId).whenComplete((row, err) -> {
            if (err != null) {
                FTBQuestsSync.LOGGER.warn("Remote props DB load failed team={}", teamId, err);
                return;
            }
            TeamInfoRow info = row.orElse(null);
            if (info == null || info.deleted()) return;
            server.execute(() -> applyRemoteProps(teamId, info));
        });
    }

    private void applyRemoteProps(UUID teamId, TeamInfoRow info) {
        if (!TeamMaterializer.ensureTeamMaterialized(teamId)) return;
        TeamManager mgr = FTBTeamsAPI.api().getManager();
        Team team = mgr.getTeamByID(teamId).orElse(null);
        if (team == null) return;

        boolean changed = false;
        boolean colorChanged = false;
        try (TeamMutationGuard.Scope ignored = TeamMutationGuard.suppressTeam(teamId)) {
            if (info.name() != null && !Objects.equals(readName(team), info.name())) {
                team.setProperty(TeamProperties.DISPLAY_NAME, info.name());
                changed = true;
            }
            String color = info.color();
            if (color != null && !color.isBlank() && !Objects.equals(readColor(team), color)) {
                var parsedColor = TeamProperties.COLOR.fromString(color);
                if (parsedColor.isPresent()) {
                    team.setProperty(TeamProperties.COLOR, parsedColor.get());
                    changed = true;
                    colorChanged = true;
                }
            }
        }
        if (changed) TeamMaterializer.markTeamDirtyAndSync(mgr, team);
        if (colorChanged) ChunkMaterializer.refreshTeamClaims(teamId);
        updatePropsCache(teamId, info.name(), info.color());
    }

    private void handleRemoteMemberAdd(UUID teamId, UUID playerId) {
        MySQLBackend.getInstance().loadTeamMaterializationAsync(playerId).whenComplete((row, err) -> {
            if (err != null) {
                FTBQuestsSync.LOGGER.warn("Remote member_add DB load failed team={} player={}", teamId, playerId, err);
                return;
            }
            if (row == null || row.info() == null || row.info().deleted()) return;
            if (!teamId.equals(row.membership().teamId())) return;
            server.execute(() -> {
                if (TeamMutationGuard.isTeamSuppressed(teamId)) {
                    FTBQuestsSync.LOGGER.debug("Remote member_add suppressed: team={} player={} (already mutating)", teamId, playerId);
                    return;
                }
                FtbSyncTeamCommand.applyMemberAddLocal(server, teamId, playerId, row.membership().rank());
            });
        });
    }

    private void handleRemoteMemberKick(UUID teamId, UUID playerId) {
        MySQLBackend.getInstance().loadTeamMaterializationAsync(playerId).whenComplete((row, err) -> {
            if (err != null) {
                FTBQuestsSync.LOGGER.warn("Remote member_kick DB load failed team={} player={}", teamId, playerId, err);
                return;
            }
            server.execute(() -> {
                if (TeamMutationGuard.isTeamSuppressed(teamId)) {
                    FTBQuestsSync.LOGGER.debug("Remote member_kick suppressed: team={} player={} (already mutating)", teamId, playerId);
                    return;
                }
                FtbSyncTeamCommand.applyMemberKickLocal(server, teamId, playerId, row);
            });
        });
    }

    private void handleRemoteOwnerTransfer(UUID teamId) {
        MySQLBackend.getInstance().loadTeamStateAsync(teamId).whenComplete((state, err) -> {
            if (err != null) {
                FTBQuestsSync.LOGGER.warn("Remote owner_transfer DB load failed team={}", teamId, err);
                return;
            }
            if (state == null || state.info() == null || state.info().deleted() || state.info().owner() == null) return;
            server.execute(() -> {
                if (TeamMutationGuard.isTeamSuppressed(teamId)) {
                    FTBQuestsSync.LOGGER.debug("Remote owner_transfer suppressed: team={} (already mutating)", teamId);
                    return;
                }
                FtbSyncTeamCommand.applyOwnerTransferLocal(server, server.createCommandSourceStack(),
                        teamId, profileFor(server, state.info().owner()), state.members());
            });
        });
    }

    private void handleRemoteInvite(UUID teamId, UUID playerId) {
        java.util.Optional<TeamInviteRow> inviteOpt = MySQLBackend.getInstance().selectTeamInvite(teamId, playerId);
        if (inviteOpt.isEmpty()) {
            FTBQuestsSync.LOGGER.debug("Remote invite ignored: no DB invite row team={} player={}", teamId, playerId);
            return;
        }
        UUID inviterUuid = inviteOpt.get().inviterUuid();
        server.execute(() -> {
            if (TeamMutationGuard.isTeamSuppressed(teamId)) {
                FTBQuestsSync.LOGGER.debug("Remote invite suppressed: team={} player={} (already mutating)", teamId, playerId);
                return;
            }
            TeamManager mgr = FTBTeamsAPI.api().getManager();
            Team playerTeam = mgr.getTeamForPlayerID(playerId).orElse(null);
            if (playerTeam != null && playerTeam.isPartyTeam() && !playerTeam.getId().equals(playerId)) {
                FTBQuestsSync.LOGGER.debug("Remote invite ignored: player={} already in non-solo party team={}", playerId, playerTeam.getId());
                return;
            }
            if (!TeamMaterializer.ensureTeamMaterialized(teamId)) {
                FTBQuestsSync.LOGGER.warn("Remote invite failed to materialize team={}", teamId);
                return;
            }
            Team team = mgr.getTeamByID(teamId).orElse(null);
            if (team == null) return;
            try (TeamMutationGuard.Scope ignored = TeamMutationGuard.suppressTeam(teamId)) {
                TeamMaterializer.addPlayerToTeamReflective(team, playerId, "INVITED");
            }
            TeamMaterializer.markTeamDirtyAndSync(mgr, team);
            FTBQuestsSync.LOGGER.info("Remote invite applied: team={} player={}", teamId, playerId);
            notifyInviteeChat(server, team, playerId, inviterUuid);
        });
    }

    private void handleRemoteInviteCancel(UUID teamId, String playerIdStr) {
        server.execute(() -> {
            if (TeamMutationGuard.isTeamSuppressed(teamId)) {
                FTBQuestsSync.LOGGER.debug("Remote invite_cancel suppressed: team={} (already mutating)", teamId);
                return;
            }
            TeamManager mgr = FTBTeamsAPI.api().getManager();
            Team team = mgr.getTeamByID(teamId).orElse(null);
            if (team == null) return;
            try (TeamMutationGuard.Scope ignored = TeamMutationGuard.suppressTeam(teamId)) {
                if ("-".equals(playerIdStr)) {
                    java.util.List<UUID> toRemove = new java.util.ArrayList<>();
                    for (UUID playerId : team.getMembers()) {
                        if (team.getRankForPlayer(playerId) == TeamRank.INVITED) {
                            toRemove.add(playerId);
                        }
                    }
                    for (UUID playerId : toRemove) {
                        TeamMaterializer.removePlayerFromTeamReflective(team, playerId);
                    }
                    FTBQuestsSync.LOGGER.info("Remote invite_cancel applied (all invites): team={} removed={}", teamId, toRemove.size());
                } else {
                    UUID playerId = UUID.fromString(playerIdStr);
                    if (team.getRankForPlayer(playerId) == TeamRank.INVITED) {
                        TeamMaterializer.removePlayerFromTeamReflective(team, playerId);
                        FTBQuestsSync.LOGGER.info("Remote invite_cancel applied: team={} player={}", teamId, playerId);
                    }
                }
            }
            TeamMaterializer.markTeamDirtyAndSync(mgr, team);
        });
    }

    private boolean updatePropsCache(UUID teamId, String name, String color) {
        String[] previous = lastSyncedProps.put(teamId, new String[]{name, color});
        return previous == null || !Objects.equals(previous[0], name) || !Objects.equals(previous[1], color);
    }

    private String cachedColor(UUID teamId) {
        String[] cached = lastSyncedProps.get(teamId);
        return cached != null ? cached[1] : null;
    }

    public static void notifyInviteeChat(MinecraftServer server, Team team, UUID inviteeUuid, UUID inviterUuid) {
        try {
            ServerPlayer invitee = server.getPlayerList().getPlayer(inviteeUuid);
            if (invitee == null) return;
            String inviterName = inviterUuid != null ? profileFor(server, inviterUuid).getName() : null;
            if (inviterName == null || inviterName.isBlank()) {
                inviterName = "A player";
            }
            Component inviterNameComponent = Component.literal(inviterName).withStyle(ChatFormatting.YELLOW);
            invitee.displayClientMessage(Component.translatable("ftbteams.message.invite_sent", inviterNameComponent), false);
            String shortName = team.getShortName();
            Component accept = Component.translatable("ftbteams.accept")
                    .withStyle(Style.EMPTY.withColor(ChatFormatting.GREEN)
                            .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/ftbteams party join " + shortName)));
            Component decline = Component.translatable("ftbteams.decline")
                    .withStyle(Style.EMPTY.withColor(ChatFormatting.RED)
                            .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/ftbteams party decline " + shortName)));
            invitee.displayClientMessage(Component.literal("[").append(accept).append("] [").append(decline).append("]"), false);
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.warn("Failed to send invite chat notification to invitee={} for team={}", inviteeUuid, team.getId(), e);
        }
    }

    public static GameProfile profileFor(MinecraftServer server, UUID playerId) {
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (player != null) return player.getGameProfile();
        String dbName = MySQLBackend.getInstance().selectPlayerNameByUuid(playerId).orElse(null);
        if (dbName != null && !dbName.isBlank()) {
            return new GameProfile(playerId, dbName);
        }
        return new GameProfile(playerId, playerId.toString());
    }
}
