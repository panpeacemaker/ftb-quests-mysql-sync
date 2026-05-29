package net.agrarius.ftbquestssync;

import com.mojang.authlib.GameProfile;
import dev.ftb.mods.ftbteams.api.FTBTeamsAPI;
import dev.ftb.mods.ftbteams.api.Team;
import dev.ftb.mods.ftbteams.api.TeamManager;
import dev.ftb.mods.ftbteams.api.TeamRank;
import dev.ftb.mods.ftbteams.api.event.PlayerChangedTeamEvent;
import dev.ftb.mods.ftbteams.api.event.PlayerJoinedPartyTeamEvent;
import dev.ftb.mods.ftbteams.api.event.PlayerLeftPartyTeamEvent;
import dev.ftb.mods.ftbteams.api.event.PlayerLoggedInAfterTeamEvent;
import dev.ftb.mods.ftbteams.api.event.PlayerTransferredTeamOwnershipEvent;
import dev.ftb.mods.ftbteams.api.event.TeamCreatedEvent;
import dev.ftb.mods.ftbteams.api.event.TeamEvent;
import dev.ftb.mods.ftbteams.api.property.TeamProperties;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPubSub;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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

    private final ConcurrentHashMap<String, Long> recentEmits = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, String[]> lastSyncedProps = new ConcurrentHashMap<>();

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
            pool = new JedisPool(Config.redisHost, Config.redisPort);
            try (Jedis jedis = pool.getResource()) {
                auth(jedis);
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
        if (TeamMutationGuard.isTeamSuppressed(team.getId())) return;
        MySQLBackend.getInstance().markTeamDeletedAsync(team.getId());
        publish("deleted", team.getId());
    }

    private void onJoinedParty(PlayerJoinedPartyTeamEvent ev) {
        if (!Config.syncTeams) return;
        Team team = ev.getTeam();
        UUID playerId = ev.getPlayer().getUUID();
        if (TeamMutationGuard.isTeamSuppressed(team.getId())) return;
        persistTeam(team);
        MySQLBackend.getInstance().upsertMembershipFuture(playerId, team.getId(), rankName(team, playerId))
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
        UUID playerId = ev.getPlayer().getUUID();
        if (TeamMutationGuard.isTeamSuppressed(partyTeam.getId())) return;
        TeamManager mgr = FTBTeamsAPI.api().getManager();
        Team playerTeam = mgr.getPlayerTeamForPlayerID(playerId).orElse(null);
        UUID targetTeamId = playerTeam == null ? playerId : playerTeam.getId();
        String rank = playerTeam == null ? "OWNER" : rankName(playerTeam, playerId);
        MySQLBackend.getInstance().upsertMembershipFuture(playerId, targetTeamId, rank)
                .whenComplete((v, e) -> {
                    if (e != null) {
                        FTBQuestsSync.LOGGER.error("kick solo-persist failed player={}", playerId, e);
                        return;
                    }
                    publish("member_kick", partyTeam.getId(), playerId);
                });
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
            MySQLBackend.getInstance().upsertMembershipFuture(playerId, team.getId(), rankName(team, playerId))
                    .whenComplete((v, e) -> {
                        if (e != null) {
                            FTBQuestsSync.LOGGER.error("kick solo-persist failed player={}", playerId, e);
                            return;
                        }
                        TeamSync.getInstance().publishMemberKick(previousPartyId, playerId);
                    });
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

    private void onOwnershipTransferred(PlayerTransferredTeamOwnershipEvent ev) {
        if (!Config.syncTeams) return;
        Team team = ev.getTeam();
        if (TeamMutationGuard.isTeamSuppressed(team.getId())) return;
        persistTeam(team);
        publish("owner_transfer", team.getId());
    }

    private void onPlayerLoggedIn(PlayerLoggedInAfterTeamEvent ev) {
        reconcileOnLogin(ev.getPlayer());
    }

    private void onPropertiesChanged(dev.ftb.mods.ftbteams.api.event.TeamPropertiesChangedEvent ev) {
        Team team = ev.getTeam();
        if (TeamMutationGuard.isTeamSuppressed(team.getId())) return;
        if (!Config.syncTeams) return;
        persistTeam(team);
        String name = readName(team);
        String color = readColor(team);
        if (updatePropsCache(team.getId(), name, color)) {
            publish("props", team.getId());
        }
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

    private void persistTeam(Team team, boolean async) {
        if (team == null) return;
        String type;
        if (team.isPartyTeam()) type = "PARTY";
        else if (team.isServerTeam()) type = "SERVER";
        else if (team.isPlayerTeam()) type = "PLAYER";
        else type = "UNKNOWN";

        String name = readName(team);
        String color = readColor(team);
        UUID owner = team.getOwner();
        if (async) MySQLBackend.getInstance().upsertTeamInfoAsync(team.getId(), type, name, owner, color);
        else MySQLBackend.getInstance().upsertTeamInfo(team.getId(), type, name, owner, color);
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
            auth(jedis);
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
                auth(subscriberConn);
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
            MySQLBackend.TeamInfoRow info = row.orElse(null);
            if (info == null || info.deleted()) return;
            server.execute(() -> applyRemoteProps(teamId, info));
        });
    }

    private void applyRemoteProps(UUID teamId, MySQLBackend.TeamInfoRow info) {
        if (!TeamMaterializer.ensureTeamMaterialized(teamId)) return;
        TeamManager mgr = FTBTeamsAPI.api().getManager();
        Team team = mgr.getTeamByID(teamId).orElse(null);
        if (team == null) return;

        boolean changed = false;
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
                }
            }
        }
        if (changed) TeamMaterializer.markTeamDirtyAndSync(mgr, team);
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
            server.execute(() -> FtbSyncTeamCommand.applyMemberAddLocal(server, teamId, playerId, row.membership().rank()));
        });
    }

    private void handleRemoteMemberKick(UUID teamId, UUID playerId) {
        MySQLBackend.getInstance().loadTeamMaterializationAsync(playerId).whenComplete((row, err) -> {
            if (err != null) {
                FTBQuestsSync.LOGGER.warn("Remote member_kick DB load failed team={} player={}", teamId, playerId, err);
                return;
            }
            server.execute(() -> FtbSyncTeamCommand.applyMemberKickLocal(server, teamId, playerId, row));
        });
    }

    private void handleRemoteOwnerTransfer(UUID teamId) {
        MySQLBackend.getInstance().loadTeamStateAsync(teamId).whenComplete((state, err) -> {
            if (err != null) {
                FTBQuestsSync.LOGGER.warn("Remote owner_transfer DB load failed team={}", teamId, err);
                return;
            }
            if (state == null || state.info() == null || state.info().deleted() || state.info().owner() == null) return;
            server.execute(() -> FtbSyncTeamCommand.applyOwnerTransferLocal(server, server.createCommandSourceStack(),
                    teamId, profileFor(server, state.info().owner()), state.members()));
        });
    }

    private boolean updatePropsCache(UUID teamId, String name, String color) {
        String[] previous = lastSyncedProps.put(teamId, new String[]{name, color});
        return previous == null || !Objects.equals(previous[0], name) || !Objects.equals(previous[1], color);
    }

    private static GameProfile profileFor(MinecraftServer server, UUID playerId) {
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (player != null) return player.getGameProfile();
        return new GameProfile(playerId, playerId.toString());
    }

    private static void auth(Jedis jedis) {
        if (!Config.redisPassword.isBlank()) {
            jedis.auth(Config.redisPassword);
        }
    }
}
