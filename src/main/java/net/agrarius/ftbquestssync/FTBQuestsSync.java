package net.agrarius.ftbquestssync;

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

@Mod(FTBQuestsSync.MOD_ID)
public class FTBQuestsSync {

    public static final String MOD_ID = "ftbquestssync";
    public static final Logger LOGGER = LoggerFactory.getLogger("FTBQuestsSync");

    public FTBQuestsSync() {
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::commonSetup);
        MinecraftForge.EVENT_BUS.register(this);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        LOGGER.info("FTB Quests Sync 1.0.33 booting");
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
        }
        RankSoloProgress.init();
        ChunkSeeder.runIfConfigured(event.getServer());
        ChunkMaterializer.materializeAllLoaded(event.getServer());
        LOGGER.info("FTB Quests Sync 1.0.33 ready (mysqlAvailable={}, redisEnabled={}, teamsRedisEnabled={}, serverId={})",
                MySQLBackend.getInstance().isAvailable(),
                RedisSync.getInstance().isEnabled(),
                TeamSync.getInstance().isEnabled(),
                RedisSync.getInstance().getServerId());
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        FtbSyncTeamCommand.register(event.getDispatcher());
    }

    @SubscribeEvent
    public void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        MySQLBackend.getInstance().upsertPlayerNameAsync(player.getUUID(), player.getGameProfile().getName());
        // When syncTeams=true, TeamSync.reconcileOnLogin fires via PlayerLoggedInAfterTeamEvent
        // AFTER FTB Teams has materialized the correct effective team. Running both produces a
        // double async reload race: two DB loads race to win; loser's SyncTeamDataMessage
        // overwrites the winner's fresher state. Confirmed in logs (5-second gap).
        // When syncTeams=false there is no Teams event, so we handle it here directly.
        if (Config.syncTeams) return;
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
    public void onServerStopping(ServerStoppingEvent event) {
        ChunkSync.getInstance().shutdown();
        TeamSync.getInstance().shutdown();
        RedisSync.getInstance().shutdown();
        MySQLBackend.getInstance().shutdown();
        LOGGER.info("FTB Quests Sync stopped");
    }
}
