package net.agrarius.ftbquestssync;

import net.agrarius.ftbquestssync.command.FtbSyncTeamCommand;
import net.agrarius.ftbquestssync.config.Config;
import net.agrarius.ftbquestssync.quests.rank.RankSoloProgress;

import dev.architectury.event.events.common.LifecycleEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.minecraft.server.level.ServerPlayer;

import dev.ftb.mods.ftbquests.quest.TeamData;
import net.agrarius.ftbquestssync.access.TeamDataAccess;
import net.agrarius.ftbquestssync.migration.ImportTeamsCommand;
import net.agrarius.ftbquestssync.migration.LegacyQuestMigrator;
import net.agrarius.ftbquestssync.migration.MigrationCommand;
import net.agrarius.ftbquestssync.chunks.ChunkSync;
import net.agrarius.ftbquestssync.chunks.ChunkMaterializer;
import net.agrarius.ftbquestssync.chunks.ChunkSeeder;
import net.agrarius.ftbquestssync.teams.MembershipCache;
import net.agrarius.ftbquestssync.teams.PresenceSync;
import net.agrarius.ftbquestssync.teams.TeamSync;

@Mod(FTBQuestsSync.MOD_ID)
public class FTBQuestsSync {

    public static final String MOD_ID = "ftbquestssync";
    public static final Logger LOGGER = LoggerFactory.getLogger("FTBQuestsSync");
    public static volatile boolean serverStarted = false;

    public FTBQuestsSync() {
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::commonSetup);
        MinecraftForge.EVENT_BUS.register(this);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        LOGGER.info("FTB Quests Sync 1.2.0 booting");
        Config.reload();
        MySQLBackend.getInstance().initialize();
        if (Config.syncTeams) {
            TeamSync.getInstance().registerEventListeners();
        } else {
            LOGGER.info("TeamSync disabled by config: no FTB Teams listeners will be registered");
        }
        ChunkSync.getInstance().registerEventListeners();
        LifecycleEvent.SERVER_LEVEL_LOAD.register(level -> {
            ChunkMaterializer.onLevelLoad(level);
            ChunkSeeder.runIfConfigured(level.getServer());
        });
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        ChunkMaterializer.initialize(event.getServer());
        RedisSync.getInstance().initialize(event.getServer());
        if (Config.syncTeams) {
            TeamSync.getInstance().initializeRedis(event.getServer());
            PresenceSync.getInstance().initialize(event.getServer());
        }
        RankSoloProgress.init();
        ChunkSeeder.runIfConfigured(event.getServer());
        ChunkMaterializer.materializeAllLoaded(event.getServer());
        LOGGER.info("FTB Quests Sync 1.2.0 ready (mysqlAvailable={}, redisEnabled={}, teamsRedisEnabled={}, serverId={})",
                MySQLBackend.getInstance().isAvailable(),
                RedisSync.getInstance().isEnabled(),
                TeamSync.getInstance().isEnabled(),
                RedisSync.getInstance().getServerId());
        serverStarted = true;
        backfillMissingPlayerNames(event.getServer());
        // Auto-migration runs on a background daemon so a slow source (large
        // Redis dump, slow MariaDB, huge ZIP) never stalls server start.
        LegacyQuestMigrator.runIfNeededAsync();
    }

    private void backfillMissingPlayerNames(net.minecraft.server.MinecraftServer server) {
        if (!MySQLBackend.getInstance().isAvailable()) return;
        try {
            java.util.List<java.util.UUID> missing = MySQLBackend.getInstance().selectMembershipUuidsMissingName();
            if (missing.isEmpty()) return;
            java.util.Map<java.util.UUID, String> resolved = new java.util.HashMap<>();
            for (java.util.UUID uuid : missing) {
                server.getProfileCache()
                        .get(uuid)
                        .map(com.mojang.authlib.GameProfile::getName)
                        .filter(n -> n != null && !n.isBlank())
                        .ifPresent(name -> resolved.put(uuid, name));
            }
            LOGGER.info("Player-name backfill: {} membership uuid(s) missing a name, {} resolved from UserCache",
                    missing.size(), resolved.size());
            MySQLBackend.getInstance().backfillPlayerNamesAsync(resolved);
        } catch (Exception e) {
            LOGGER.warn("Player-name backfill failed", e);
        }
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        FtbSyncTeamCommand.register(event.getDispatcher());
        MigrationCommand.register(event.getDispatcher());
        ImportTeamsCommand.register(event.getDispatcher());
    }

    @SubscribeEvent
    public void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        MySQLBackend.getInstance().upsertPlayerNameAsync(player.getUUID(), player.getGameProfile().getName());
        java.util.UUID loginUuid = player.getUUID();
        MySQLBackend.getInstance().loadTeamMaterializationAsync(loginUuid).whenComplete((row, err) -> {
            if (err != null || row == null || row.membership() == null) return;
            MembershipCache.put(loginUuid, row.membership().teamId());
        });
        // When syncTeams=true, TeamSync.reconcileOnLogin fires via PlayerLoggedInAfterTeamEvent
        // AFTER FTB Teams has materialized the correct effective team. Running both produces a
        // double async reload race: two DB loads race to win; loser's SyncTeamDataMessage
        // overwrites the winner's fresher state. Confirmed in logs (5-second gap).
        // When syncTeams=false there is no Teams event, so we handle it here directly.
        if (Config.syncTeams) {
            PresenceSync.getInstance().onPlayerLogin(player.getUUID());
            return;
        }
        try {
            dev.ftb.mods.ftbquests.quest.TeamData data = dev.ftb.mods.ftbquests.quest.TeamData.get(player);
            RedisSync.getInstance().forceReloadAndPushTo(data.getTeamId(), player);
            RankSoloProgress.pushToPlayerAsync(data, player);
            ChunkMaterializer.materializeOnLogin(player);
        } catch (Exception e) {
            LOGGER.warn("Login handler failed for {}", player.getUUID(), e);
        }
    }

    @SubscribeEvent
    public void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (Config.syncTeams) {
            TeamSync.getInstance().markPlayerDisconnecting(player.getUUID());
            PresenceSync.getInstance().onPlayerLogout(player.getUUID());
        }
        if (player.isRemoved()) return;
        if (!Config.syncQuests) return;
        try {
            TeamData data = TeamData.get(player);
            if (data == null) return;
            if (!(data instanceof TeamDataAccess access)) {
                LOGGER.warn("Logout force-save skipped: TeamDataAccess mixin bridge is unavailable for player={}", player.getUUID());
                return;
            }

            LOGGER.info("Player logout: player={} team={} — force-saving team data", player.getUUID(), data.getTeamId());
            access.ftbQuestsSync$requestForceSave();
            data.markDirty();
        } catch (Exception e) {
            LOGGER.warn("Player logout force-save failed for player={}", player.getUUID(), e);
        }
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        PresenceSync.getInstance().markAllLocalOffline();
        PresenceSync.getInstance().shutdown();
        ChunkSync.getInstance().shutdown();
        TeamSync.getInstance().shutdown();
        RedisSync.getInstance().shutdown();
        MySQLBackend.getInstance().shutdown();
        LOGGER.info("FTB Quests Sync stopped");
    }
}
