package net.agrarius.ftbquestssync;

import dev.ftb.mods.ftbteams.api.FTBTeamsAPI;
import dev.ftb.mods.ftbteams.api.Team;
import dev.ftb.mods.ftbteams.api.TeamManager;
import dev.ftb.mods.ftbteams.api.TeamRank;
import dev.ftb.mods.ftbteams.data.PartyTeam;
import com.mojang.authlib.GameProfile;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;

/**
 * Reflection bridge for materializing FTB Teams across servers.
 *
 * FTB Teams does not expose a public API to:
 *   - create a team with a CALLER-CHOSEN UUID (only random UUIDs via the
 *     public {@link TeamManager#createPartyTeam} entry point)
 *   - add an arbitrary (possibly offline) player to an existing team
 *
 * Both are required for cross-server propagation. We use reflection against
 * the internal classes verified at runtime:
 *   - {@code dev.ftb.mods.ftbteams.data.TeamManagerImpl} (INSTANCE singleton,
 *     {@code teamMap}, {@code markDirty}, {@code saveNow}, {@code syncToAll})
 *   - {@code dev.ftb.mods.ftbteams.data.TeamType.PARTY.createTeam(manager,uuid)}
 *     factory builds an AbstractTeam with the chosen id
 *   - {@code dev.ftb.mods.ftbteams.data.PartyTeam.owner} package-private field
 *   - {@code dev.ftb.mods.ftbteams.data.AbstractTeamBase.ranks} protected map
 *
 * If FTB Teams ever changes these private surfaces, the materializer logs and
 * fails open (no crash; cross-server team sync regresses to log-only).
 */
public final class TeamMaterializer {

    private TeamMaterializer() {
    }

    public static void materializeOnLogin(ServerPlayer player) {
        if (!Config.syncTeams) return;
        UUID playerUuid = player.getUUID();
        MySQLBackend.getInstance().loadTeamMaterializationAsync(playerUuid).whenComplete((row, error) -> {
            if (error != null) {
                FTBQuestsSync.LOGGER.warn("Team materialization DB load failed for player={}", playerUuid, error);
                return;
            }
            if (row == null) return;
            player.getServer().execute(() -> materializeOnServerThread(player, row));
        });
    }

    private static void materializeOnServerThread(ServerPlayer player, MySQLBackend.TeamMaterializationRow row) {
        UUID playerUuid = player.getUUID();
        MySQLBackend.TeamMembershipRow membership = row.membership();
        UUID dbTeamId = membership.teamId();
        TeamManager mgr = FTBTeamsAPI.api().getManager();
        Team existing = mgr.getTeamByID(dbTeamId).orElse(null);

        // Reflective create/add below can fire synthetic TeamCreated/Joined events;
        // pin LOADING so onCreated does not flip this DB-owned team to NEW and let
        // empty local state overwrite the authoritative MySQL blob before reload.
        MySQLBackend.setTeamLoadState(dbTeamId, MySQLBackend.TeamLoadState.LOADING);

        if (dbTeamId.equals(playerUuid)) {
            Team current = mgr.getTeamForPlayerID(playerUuid).orElse(null);
            if (current != null && current.isPartyTeam() && !current.getId().equals(playerUuid)) {
                try (TeamMutationGuard.Scope ts = TeamMutationGuard.suppressTeam(current.getId());
                     TeamMutationGuard.Scope ps = TeamMutationGuard.suppressPlayer(playerUuid)) {
                    Team soloTeam = mgr.getPlayerTeamForPlayerID(playerUuid).orElse(null);
                    if (soloTeam != null) {
                        forcePlayerToDbTeam(mgr, playerUuid, soloTeam);
                    } else {
                        removePlayerFromTeamReflective(current, playerUuid);
                    }
                    markTeamDirtyAndSync(mgr, current);
                }
                TeamSync.getInstance().forceFullSyncToPlayer(player, playerUuid);
            }
            return;
        }

        MySQLBackend.TeamInfoRow infoRow = row.info();
        if (existing != null) {
            addPlayerToTeam(existing, playerUuid, membership.rank());
            if (!forcePlayerToDbTeam(mgr, playerUuid, existing)) {
                FTBQuestsSync.LOGGER.warn(
                        "DB-team convergence failed for player={} team={} — skipping sync to avoid wrong-team state",
                        playerUuid, dbTeamId);
                return;
            }
            if (infoRow != null) applyColor(existing, infoRow.color());
            markTeamDirtyAndSync(mgr, existing);
            TeamSync.getInstance().forceFullSyncToPlayer(player, dbTeamId);
            RedisSync.getInstance().forceReloadAndPushTo(dbTeamId, player);
            ChunkMaterializer.materializeOnLogin(player);
            return;
        }

        MySQLBackend.TeamInfoRow info = infoRow;
        if (info == null || info.deleted()) return;
        if (!"PARTY".equals(info.type())) return;

        Object created = createTeamWithUuid(mgr, dbTeamId, "PARTY", info.name(), info.owner());
        if (created == null) {
            FTBQuestsSync.LOGGER.warn("Could not materialize team {} on this server (reflection failed)", dbTeamId);
            return;
        }

        for (MySQLBackend.TeamMemberRow m : row.members()) {
            addPlayerToTeamReflective(created, m.playerUuid(), m.rank());
        }

        finalizeTeamCreation(mgr, created);
        Team createdTeam = mgr.getTeamByID(dbTeamId).orElse(null);
        if (createdTeam == null || !forcePlayerToDbTeam(mgr, playerUuid, createdTeam)) {
            FTBQuestsSync.LOGGER.warn(
                    "DB-team convergence failed after create for player={} team={} — skipping sync",
                    playerUuid, dbTeamId);
            return;
        }
        applyColor(createdTeam, info.color());
        FTBQuestsSync.LOGGER.info("Materialized team {} ({}) locally from DB for player {}",
                dbTeamId, info.name(), playerUuid);
        TeamSync.getInstance().forceFullSyncToPlayer(player, dbTeamId);
        // Newly created party team — send its quest data to the player now.
        RedisSync.getInstance().forceReloadAndPushTo(dbTeamId, player);
        ChunkMaterializer.materializeOnLogin(player);
    }

    private static void applyColor(Team team, String colorStr) {
        if (team == null || colorStr == null || colorStr.isBlank()) return;
        try {
            dev.ftb.mods.ftbteams.api.property.TeamProperties.COLOR.fromString(colorStr).ifPresent(color -> {
                try {
                    team.setProperty(dev.ftb.mods.ftbteams.api.property.TeamProperties.COLOR, color);
                } catch (Exception e) {
                    FTBQuestsSync.LOGGER.warn("Could not set team color for {}", team.getId(), e);
                }
            });
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.debug("Could not parse team color '{}' for {}", colorStr, team.getId(), e);
        }
    }

    public static boolean ensureTeamMaterialized(UUID teamId) {
        TeamManager mgr = FTBTeamsAPI.api().getManager();
        if (mgr.getTeamByID(teamId).isPresent()) return true;
        MySQLBackend.TeamInfoRow info = MySQLBackend.getInstance().selectTeamInfo(teamId).orElse(null);
        if (info == null || info.deleted()) {
            FTBQuestsSync.LOGGER.warn("Cannot materialize missing team {} for chunk sync: no team_info row", teamId);
            return false;
        }
        Object created = createTeamWithUuid(mgr, teamId, info.type(), info.name(), info.owner());
        if (created == null) return false;
        for (MySQLBackend.TeamMemberRow member : MySQLBackend.getInstance().selectTeamMembers(teamId)) {
            addPlayerToTeamReflective(created, member.playerUuid(), member.rank());
        }
        finalizeTeamCreation(mgr, created);
        boolean ok = mgr.getTeamByID(teamId).isPresent();
        FTBQuestsSync.LOGGER.info("ensureTeamMaterialized for chunks: team={} type={} ok={}", teamId, info.type(), ok);
        return ok;
    }

    public static Object createTeamWithUuid(TeamManager mgr, UUID teamId, String type, String name, UUID owner) {
        try {
            Class<?> mgrImplCls = Class.forName("dev.ftb.mods.ftbteams.data.TeamManagerImpl");
            Class<?> typeCls = Class.forName("dev.ftb.mods.ftbteams.data.TeamType");
            Object partyType = typeCls.getField(type).get(null);
            Method createTeam = typeCls.getMethod("createTeam", mgrImplCls, UUID.class);
            Object team = createTeam.invoke(partyType, mgr, teamId);

            if (owner != null && "PARTY".equals(type)) {
                Class<?> partyTeamCls = Class.forName("dev.ftb.mods.ftbteams.data.PartyTeam");
                Field ownerField = partyTeamCls.getDeclaredField("owner");
                ownerField.setAccessible(true);
                ownerField.set(team, owner);
            }

            if (name != null && !name.isBlank()) {
                try {
                    Class<?> propsCls = Class.forName("dev.ftb.mods.ftbteams.api.property.TeamProperties");
                    Object displayNameProp = propsCls.getField("DISPLAY_NAME").get(null);
                    Method setProperty = team.getClass().getMethod("setProperty",
                            Class.forName("dev.ftb.mods.ftbteams.api.property.TeamProperty"), Object.class);
                    setProperty.invoke(team, displayNameProp, name);
                } catch (Exception e) {
                    FTBQuestsSync.LOGGER.debug("Could not set materialized team display name for {}", teamId, e);
                }
            }
            return team;
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.error("createTeamWithUuid reflection failed for team {} type {}", teamId, type, e);
            return null;
        }
    }

    private static void addPlayerToTeam(Team team, UUID playerUuid, String rank) {
        addPlayerToTeamReflective(team, playerUuid, rank);
    }

    @SuppressWarnings("unchecked")
    public static void addPlayerToTeamReflective(Object team, UUID playerUuid, String rank) {
        try {
            Class<?> baseCls = Class.forName("dev.ftb.mods.ftbteams.data.AbstractTeamBase");
            Field ranksField = baseCls.getDeclaredField("ranks");
            ranksField.setAccessible(true);
            Map<UUID, TeamRank> ranks = (Map<UUID, TeamRank>) ranksField.get(team);

            TeamRank parsed;
            try {
                parsed = TeamRank.valueOf(rank);
            } catch (Exception e) {
                parsed = TeamRank.MEMBER;
            }
            ranks.put(playerUuid, parsed);
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.error("addPlayerToTeam reflection failed player={} rank={}", playerUuid, rank, e);
        }
    }

    private static boolean setEffectiveTeam(TeamManager mgr, UUID playerUuid, Team partyTeam) {
        try {
            Object playerTeam = mgr.getPlayerTeamForPlayerID(playerUuid).orElse(null);
            if (playerTeam == null) return false;
            Class<?> playerTeamCls = Class.forName("dev.ftb.mods.ftbteams.data.PlayerTeam");
            Field effectiveTeamField = playerTeamCls.getDeclaredField("effectiveTeam");
            effectiveTeamField.setAccessible(true);
            effectiveTeamField.set(playerTeam, partyTeam);
            FTBQuestsSync.LOGGER.info("Set effectiveTeam={} for player={}", partyTeam.getId(), playerUuid);
            return true;
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.error("setEffectiveTeam failed for player={} team={}", playerUuid, partyTeam.getId(), e);
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    public static boolean forcePlayerToDbTeam(TeamManager mgr, UUID playerUuid, Team dbTeam) {
        UUID dbTeamId = dbTeam.getId();
        try {
            Team stale = mgr.getTeamForPlayerID(playerUuid).orElse(null);
            // Detach from a stale DIFFERENT party first. FTB Teams resolves a
            // player's team as PlayerTeam.effectiveTeam, so a lingering party (e.g.
            // one deleted on a peer while this player was offline) must be removed
            // from that party's 'ranks' map or it keeps resolving here. Mirrors
            // FTB's own kick/leave: drop from party ranks, restore solo OWNER.
            if (stale != null && !stale.getId().equals(dbTeamId) && stale.isPartyTeam()) {
                Class<?> baseCls = Class.forName("dev.ftb.mods.ftbteams.data.AbstractTeamBase");
                Field ranksField = baseCls.getDeclaredField("ranks");
                ranksField.setAccessible(true);
                ((Map<UUID, TeamRank>) ranksField.get(stale)).remove(playerUuid);
                Object playerTeam = mgr.getPlayerTeamForPlayerID(playerUuid).orElse(null);
                if (playerTeam != null) {
                    ((Map<UUID, TeamRank>) ranksField.get(playerTeam)).put(playerUuid, TeamRank.OWNER);
                }
                FTBQuestsSync.LOGGER.info("Detached player={} from stale party={} before DB team={}",
                        playerUuid, stale.getId(), dbTeamId);
            }

            if (!setEffectiveTeam(mgr, playerUuid, dbTeam)) return false;

            Team after = mgr.getTeamForPlayerID(playerUuid).orElse(null);
            boolean converged = after != null && after.getId().equals(dbTeamId);
            if (!converged) {
                FTBQuestsSync.LOGGER.warn("Convergence check failed player={} expected={} actual={}",
                        playerUuid, dbTeamId, after == null ? null : after.getId());
            }
            return converged;
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.error("forcePlayerToDbTeam failed player={} team={}", playerUuid, dbTeamId, e);
            return false;
        }
    }

    public static void markTeamDirtyAndSync(TeamManager mgr, Team team) {
        try {
            Class<?> baseCls = Class.forName("dev.ftb.mods.ftbteams.data.AbstractTeamBase");
            Method markDirty = baseCls.getDeclaredMethod("markDirty");
            markDirty.setAccessible(true);
            markDirty.invoke(team);
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.warn("markDirty on team={} failed", team.getId(), e);
        }
        try {
            Class<?> mgrImplCls = Class.forName("dev.ftb.mods.ftbteams.data.TeamManagerImpl");
            Method syncToAll = mgrImplCls.getMethod("syncToAll", Team[].class);
            syncToAll.invoke(mgr, (Object) new Team[]{team});
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.warn("syncToAll on team={} failed", team.getId(), e);
        }
    }

    public static boolean removePlayerFromTeamReflective(Object team, UUID playerUuid) {
        try {
            Class<?> baseCls = Class.forName("dev.ftb.mods.ftbteams.data.AbstractTeamBase");
            Field ranksField = baseCls.getDeclaredField("ranks");
            ranksField.setAccessible(true);
            Object ranksObj = ranksField.get(team);
            if (ranksObj instanceof Map<?, ?> ranks) {
                ranks.remove(playerUuid);
                return true;
            }
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.error("removePlayerFromTeam reflection failed player={}", playerUuid, e);
        }
        return false;
    }

    public static boolean transferPartyOwnership(Team team, CommandSourceStack source, GameProfile newOwner) {
        try {
            if (team instanceof PartyTeam partyTeam) {
                partyTeam.transferOwnership(source, newOwner);
                return true;
            }
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.warn("Native owner transfer failed team={} owner={}, trying reflection",
                    team.getId(), newOwner.getId(), e);
        }
        return setPartyOwnerReflective(team, newOwner.getId());
    }

    private static boolean setPartyOwnerReflective(Team team, UUID owner) {
        try {
            Class<?> partyTeamCls = Class.forName("dev.ftb.mods.ftbteams.data.PartyTeam");
            Field ownerField = partyTeamCls.getDeclaredField("owner");
            ownerField.setAccessible(true);
            ownerField.set(team, owner);
            return true;
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.error("Owner reflection failed team={} owner={}", team.getId(), owner, e);
            return false;
        }
    }

    private static void finalizeTeamCreation(TeamManager mgr, Object team) {
        try {
            Class<?> mgrImplCls = Class.forName("dev.ftb.mods.ftbteams.data.TeamManagerImpl");
            Field teamMapField = mgrImplCls.getDeclaredField("teamMap");
            teamMapField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<UUID, Object> teamMap = (Map<UUID, Object>) teamMapField.get(mgr);

            Class<?> baseCls = Class.forName("dev.ftb.mods.ftbteams.data.AbstractTeamBase");
            Field idField = baseCls.getDeclaredField("id");
            idField.setAccessible(true);
            UUID id = (UUID) idField.get(team);
            teamMap.put(id, team);

            Field validField = baseCls.getDeclaredField("valid");
            validField.setAccessible(true);
            validField.setBoolean(team, true);

            Method markDirty = mgrImplCls.getMethod("markDirty");
            markDirty.invoke(mgr);

            try {
                Method syncToAll = mgrImplCls.getMethod("syncToAll", Team[].class);
                syncToAll.invoke(mgr, (Object) new Team[]{(Team) team});
            } catch (Exception e) {
                FTBQuestsSync.LOGGER.debug("FTB Teams syncToAll reflection unavailable for materialized team", e);
            }
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.error("finalizeTeamCreation reflection failed", e);
        }
    }
}
