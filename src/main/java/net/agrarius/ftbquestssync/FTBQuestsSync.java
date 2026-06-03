package net.agrarius.ftbquestssync;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import dev.ftb.mods.ftbquests.quest.TeamData;

@Mod(FTBQuestsSync.MOD_ID)
public class FTBQuestsSync {

    public static final String MOD_ID = "ftbquestssync";
    public static final Logger LOGGER = LoggerFactory.getLogger("FTBQuestsSync");

    public FTBQuestsSync() {
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::commonSetup);
        MinecraftForge.EVENT_BUS.register(this);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        LOGGER.info("FTB Quests Sync 1.0.3 booting");
        Config.reload();
        MySQLBackend.getInstance().initialize();
        if (Config.syncTeams) {
            TeamSync.getInstance().registerEventListeners();
        } else {
            LOGGER.info("TeamSync disabled by config: no FTB Teams listeners will be registered");
        }
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        RedisSync.getInstance().initialize(event.getServer());
        if (Config.syncTeams) {
            TeamSync.getInstance().initializeRedis(event.getServer());
        }
        RankSoloProgress.init();
        LOGGER.info("FTB Quests Sync ready (mysqlAvailable={}, redisEnabled={}, teamsRedisEnabled={}, serverId={})",
                MySQLBackend.getInstance().isAvailable(),
                RedisSync.getInstance().isEnabled(),
                TeamSync.getInstance().isEnabled(),
                RedisSync.getInstance().getServerId());
    }

    private static final java.util.concurrent.ScheduledExecutorService LOGIN_SCHEDULER =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "FTBQuestsSync-Login-Delay");
                t.setDaemon(true);
                return t;
            });

    @SubscribeEvent
    public void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (player.isRemoved()) return;
        MinecraftServer server = player.getServer();
        if (server == null) return;
        LOGIN_SCHEDULER.schedule(() -> {
            if (player.isRemoved()) return;
            server.execute(() -> {
                try {
                    TeamData data = TeamData.get(player);
                    RedisSync.getInstance().forceReloadAndPushTo(data.getTeamId(), player);
                    RankSoloProgress.pushToPlayerAsync(data, player);
                    TeamSync.getInstance().forceFullSyncToPlayer(player);
                } catch (Exception e) {
                    FTBQuestsSync.LOGGER.warn("Solo progress preload on login failed for {}", player.getUUID(), e);
                }
            });
        }, 5, java.util.concurrent.TimeUnit.SECONDS);
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        LOGIN_SCHEDULER.shutdown();
        try {
            if (!LOGIN_SCHEDULER.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                LOGIN_SCHEDULER.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGIN_SCHEDULER.shutdownNow();
        }
        TeamSync.getInstance().shutdown();
        RedisSync.getInstance().shutdown();
        MySQLBackend.getInstance().shutdown();
        LOGGER.info("FTB Quests Sync stopped");
    }
}
