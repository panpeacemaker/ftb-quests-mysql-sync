package net.agrarius.ftbquestssync.quests.sync;

import dev.ftb.mods.ftblibrary.snbt.SNBTCompoundTag;
import dev.ftb.mods.ftbquests.api.FTBQuestsAPI;
import dev.ftb.mods.ftbquests.net.SyncTeamDataMessage;
import dev.ftb.mods.ftbquests.quest.BaseQuestFile;
import dev.ftb.mods.ftbquests.quest.TeamData;
import net.agrarius.ftbquestssync.Config;
import net.agrarius.ftbquestssync.FTBQuestsSync;
import net.agrarius.ftbquestssync.MySQLBackend;
import net.agrarius.ftbquestssync.QuestTeamUpdateEvent;
import net.agrarius.ftbquestssync.RankSoloProgress;
import net.agrarius.ftbquestssync.RedisEventParser;
import net.agrarius.ftbquestssync.RedisSync;
import net.agrarius.ftbquestssync.TeamLoadStateRegistry;
import net.agrarius.ftbquestssync.messaging.RedisBus;
import net.agrarius.ftbquestssync.messaging.RedisChannels;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.lang.reflect.Method;
import java.util.UUID;

/**
 * Quest sync orchestrator: publishes team-data updates and forces remote state
 * onto a specific player on login.
 */
public class QuestSyncService {

    private final RedisBus bus;
    private final MinecraftServer server;

    public QuestSyncService(RedisBus bus, MinecraftServer server) {
        this.bus = bus;
        this.server = server;
    }

    public void publishTeamUpdate(UUID teamId, long revision, String hashHex) {
        if (bus == null || !bus.isEnabled() || !Config.syncQuests) return;

        try {
            String eventId = UUID.randomUUID().toString();
            QuestTeamUpdateEvent event = new QuestTeamUpdateEvent(
                    UUID.fromString(eventId),
                    RedisSync.getInstance().getServerId(),
                    "quest_team",
                    teamId,
                    revision,
                    hashHex,
                    "saveIfChanged",
                    false
            );
            String payload = RedisEventParser.GSON.toJson(event);
            bus.publish(RedisChannels.QUEST_CHANNEL, payload);
            FTBQuestsSync.LOGGER.info("Published Redis team update: {}", payload);
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.warn("Redis publish failed for {}", teamId, e);
        }
    }

    public void forceReloadAndPushTo(UUID teamId, ServerPlayer recipient) {
        if (server == null) return;
        MySQLBackend.getInstance().loadTeamDataAsync(teamId).whenComplete((fresh, error) -> {
            if (error != null) {
                FTBQuestsSync.LOGGER.error(
                        "Force reload async DB load failed for team={} player={}",
                        teamId, recipient.getUUID(), error);
                return;
            }
            if (fresh == null) {
                FTBQuestsSync.LOGGER.info(
                        "Force reload on login: no DB row for team={} (player={}) - keeping local state",
                        teamId, recipient.getUUID());
                TeamLoadStateRegistry.setTeamLoadState(teamId, TeamLoadStateRegistry.TeamLoadState.NEW);
                return;
            }
            server.execute(() -> {
                try {
                    if (!Config.syncQuests) return;
                    if (recipient.connection == null) return;
                    BaseQuestFile file = FTBQuestsAPI.api().getQuestFile(false);
                    if (file == null) return;

                    TeamData staging = new TeamData(teamId, file);
                    staging.deserializeNBT(SNBTCompoundTag.of(fresh));
                    clearCachedProgress(staging);

                    TeamData live = file.getOrCreateTeamData(teamId);
                    try {
                        live.deserializeNBT(SNBTCompoundTag.of(staging.serializeNBT()));
                    } catch (Exception rollbackEx) {
                        FTBQuestsSync.LOGGER.error(
                                "Rollback: could not write staging into live TeamData for team={} — local state preserved",
                                teamId, rollbackEx);
                        return;
                    }
                    clearCachedProgress(live);
                    clearRewardsBlocked(live);
                    TeamLoadStateRegistry.setTeamLoadState(teamId, TeamLoadStateRegistry.TeamLoadState.LOADED);

                    new SyncTeamDataMessage(live, true).sendTo(java.util.List.of(recipient));
                    RankSoloProgress.pushToPlayerAsync(live, recipient);
                    server.tell(new net.minecraft.server.TickTask(server.getTickCount() + 40, () -> RankSoloProgress.pushToPlayerAsync(live, recipient)));
                    FTBQuestsSync.LOGGER.info(
                            "Force reload on login: team={} pushed SyncTeamDataMessage to player={}",
                            teamId, recipient.getUUID());
                } catch (Exception e) {
                    FTBQuestsSync.LOGGER.error(
                            "Force reload on login failed for team={} player={}",
                            teamId, recipient.getUUID(), e);
                }
            });
        });
    }

    private static volatile Method clearCachedProgressMethod;
    private static volatile boolean clearCachedProgressChecked = false;

    static void clearCachedProgress(TeamData td) {
        if (!clearCachedProgressChecked) {
            synchronized (QuestSyncService.class) {
                if (!clearCachedProgressChecked) {
                    try {
                        clearCachedProgressMethod = TeamData.class.getMethod("clearCachedProgress");
                    } catch (NoSuchMethodException e) {
                        FTBQuestsSync.LOGGER.warn(
                                "TeamData.clearCachedProgress() not found — stale quest caches will not be"
                                + " invalidated after reload (FTB Quests version mismatch?)");
                    }
                    clearCachedProgressChecked = true;
                }
            }
        }
        if (clearCachedProgressMethod == null) return;
        try {
            clearCachedProgressMethod.invoke(td);
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.debug("clearCachedProgress invocation failed", e);
        }
    }

    private static volatile java.lang.reflect.Field rewardsBlockedField;
    private static volatile boolean rewardsBlockedFieldChecked = false;

    static void clearRewardsBlocked(TeamData td) {
        if (!rewardsBlockedFieldChecked) {
            synchronized (QuestSyncService.class) {
                if (!rewardsBlockedFieldChecked) {
                    try {
                        rewardsBlockedField = TeamData.class.getDeclaredField("rewardsBlocked");
                        rewardsBlockedField.setAccessible(true);
                    } catch (NoSuchFieldException e) {
                        FTBQuestsSync.LOGGER.warn("TeamData.rewardsBlocked field not found");
                    }
                    rewardsBlockedFieldChecked = true;
                }
            }
        }
        if (rewardsBlockedField == null) return;
        try {
            rewardsBlockedField.setBoolean(td, false);
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.debug("clearRewardsBlocked failed", e);
        }
    }
}
