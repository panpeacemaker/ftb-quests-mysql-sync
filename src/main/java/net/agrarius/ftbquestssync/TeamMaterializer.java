package net.agrarius.ftbquestssync;

import dev.ftb.mods.ftbteams.api.FTBTeamsAPI;
import dev.ftb.mods.ftbteams.api.Team;
import dev.ftb.mods.ftbteams.api.TeamManager;
import dev.ftb.mods.ftbteams.api.TeamRank;
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

        if (existing != null) {
            if (!existing.getMembers().contains(playerUuid)) {
                addPlayerToTeam(existing, playerUuid, membership.rank());
            }
            TeamSync.getInstance().forceFullSyncToPlayer(player);
            return;
        }

        MySQLBackend.TeamInfoRow info = row.info();
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
        FTBQuestsSync.LOGGER.info("Materialized team {} ({}) locally from DB for player {}",
                dbTeamId, info.name(), playerUuid);
        TeamSync.getInstance().forceFullSyncToPlayer(player);
    }

    private static Object createTeamWithUuid(TeamManager mgr, UUID teamId, String type, String name, UUID owner) {
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
    private static void addPlayerToTeamReflective(Object team, UUID playerUuid, String rank) {
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
