package net.agrarius.ftbquestssync;

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
        persistTeam(team);
        publish("created", team.getId());
    }

    private void onDeleted(TeamEvent ev) {
        if (!Config.syncTeams) return;
        Team team = ev.getTeam();
        MySQLBackend.getInstance().markTeamDeletedAsync(team.getId());
        publish("deleted", team.getId());
    }

    private void onJoinedParty(PlayerJoinedPartyTeamEvent ev) {
        if (!Config.syncTeams) return;
        Team team = ev.getTeam();
        UUID playerId = ev.getPlayer().getUUID();
        persistTeam(team);
        persistMembership(playerId, team);
        publish("joined", team.getId(), playerId);
    }

    private void onLeftParty(PlayerLeftPartyTeamEvent ev) {
        if (!Config.syncTeams) return;
        Team partyTeam = ev.getTeam();
        UUID playerId = ev.getPlayer().getUUID();
        TeamManager mgr = FTBTeamsAPI.api().getManager();
        mgr.getPlayerTeamForPlayerID(playerId).ifPresent(playerTeam ->
                persistMembership(playerId, playerTeam));
        publish("left", partyTeam.getId(), playerId);
    }

    private void onPlayerChanged(PlayerChangedTeamEvent ev) {
        if (!Config.syncTeams) return;
        Team team = ev.getTeam();
        UUID playerId = ev.getPlayerId();
        persistTeam(team);
        persistMembership(playerId, team);
        publish("changed", team.getId(), playerId);
    }

    private void onOwnershipTransferred(PlayerTransferredTeamOwnershipEvent ev) {
        if (!Config.syncTeams) return;
        Team team = ev.getTeam();
        persistTeam(team);
        publish("ownership", team.getId());
    }

    private void onPlayerLoggedIn(PlayerLoggedInAfterTeamEvent ev) {
        reconcileOnLogin(ev.getPlayer());
    }

    public void reconcileOnLogin(net.minecraft.server.level.ServerPlayer player) {
        if (!Config.syncTeams) return;
        UUID playerId = player.getUUID();

        TeamMaterializer.materializeOnLogin(player);

        TeamManager mgr = FTBTeamsAPI.api().getManager();
        Team current = mgr.getTeamForPlayer(player).orElse(null);
        if (current != null) {
            RedisSync.getInstance().forceReloadAndPushTo(current.getId(), player);
            try {
                dev.ftb.mods.ftbquests.quest.TeamData data = dev.ftb.mods.ftbquests.quest.TeamData.get(player);
                RankSoloProgress.pushToPlayerAsync(data, player);
            } catch (Exception e) {
                FTBQuestsSync.LOGGER.warn("Rank solo push on login failed for {}", player.getUUID(), e);
            }
        }
    }

    public void forceFullSyncToPlayer(ServerPlayer player) {
        if (!Config.syncTeams || player == null) return;
        player.getServer().execute(() -> {
            try {
                TeamManager mgr = FTBTeamsAPI.api().getManager();
                Team team = mgr.getTeamForPlayer(player)
                        .or(() -> mgr.getPlayerTeamForPlayerID(player.getUUID()))
                        .orElse(null);
                if (team == null) {
                    FTBQuestsSync.LOGGER.warn("FTB Teams full sync skipped: no team for player={}", player.getUUID());
                    return;
                }

                Class<?> abstractTeamClass = Class.forName("dev.ftb.mods.ftbteams.data.AbstractTeam");
                java.lang.reflect.Method syncAllToPlayer = mgr.getClass().getMethod(
                        "syncAllToPlayer", ServerPlayer.class, abstractTeamClass);
                syncAllToPlayer.invoke(mgr, player, team);
                FTBQuestsSync.LOGGER.info("Forced FTB Teams full sync to player={} team={}", player.getUUID(), team.getId());
            } catch (Exception e) {
                FTBQuestsSync.LOGGER.warn("Forced FTB Teams full sync failed for player={}", player.getUUID(), e);
            }
        });
    }

    private void persistTeam(Team team) {
        if (team == null) return;
        String type;
        if (team.isPartyTeam()) type = "PARTY";
        else if (team.isServerTeam()) type = "SERVER";
        else if (team.isPlayerTeam()) type = "PLAYER";
        else type = "UNKNOWN";

        String name;
        try {
            name = team.getProperty(TeamProperties.DISPLAY_NAME);
        } catch (Exception e) {
            name = team.getShortName();
        }
        UUID owner = team.getOwner();
        MySQLBackend.getInstance().upsertTeamInfoAsync(team.getId(), type, name, owner);
    }

    private void persistMembership(UUID playerId, Team team) {
        if (team == null) return;
        TeamRank rank = team.getRankForPlayer(playerId);
        String rankName = rank == null ? "MEMBER" : rank.name();
        MySQLBackend.getInstance().upsertMembershipAsync(playerId, team.getId(), rankName);
    }

    private void publish(String reason, UUID teamId) {
        publish(reason, teamId, null);
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

    private static void auth(Jedis jedis) {
        if (!Config.redisPassword.isBlank()) {
            jedis.auth(Config.redisPassword);
        }
    }
}
