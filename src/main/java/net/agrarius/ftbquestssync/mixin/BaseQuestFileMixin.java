package net.agrarius.ftbquestssync.mixin;

import net.agrarius.ftbquestssync.FTBQuestsSync;
import net.agrarius.ftbquestssync.MySQLBackend;
import net.agrarius.ftbquestssync.config.Config;
import net.agrarius.ftbquestssync.quests.TeamLoadStateRegistry;
import net.agrarius.ftbquestssync.quests.TeamLoadStateRegistry.TeamLoadState;
import dev.ftb.mods.ftblibrary.snbt.SNBTCompoundTag;
import dev.ftb.mods.ftbquests.quest.BaseQuestFile;
import dev.ftb.mods.ftbquests.quest.TeamData;
import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;
import java.util.UUID;

@Mixin(value = BaseQuestFile.class, remap = false)
public abstract class BaseQuestFileMixin {

    @Shadow
    protected Map<UUID, TeamData> teamDataMap;

    @Shadow
    public abstract boolean isServerSide();

    @Inject(
            method = "getOrCreateTeamData(Ljava/util/UUID;)Ldev/ftb/mods/ftbquests/quest/TeamData;",
            at = @At("HEAD"),
            cancellable = true)
    private void ftbQuestsSync$onGetOrCreate(UUID teamId, CallbackInfoReturnable<TeamData> cir) {
        if (!Config.syncQuests || !isServerSide() || !MySQLBackend.getInstance().isAvailable()) return;

        TeamData existing = teamDataMap.get(teamId);
        if (existing != null) return;

        TeamLoadState state = TeamLoadStateRegistry.getTeamLoadState(teamId);
        if (state == TeamLoadState.LOADING || state == TeamLoadState.LOADED || state == TeamLoadState.NEW) return;

        // Put a placeholder so FTB doesn't create its own empty TeamData yet,
        // and mark LOADING so concurrent calls don't double-fire the async load.
        BaseQuestFile file = (BaseQuestFile) (Object) this;
        TeamData placeholder = new TeamData(teamId, file);
        teamDataMap.put(teamId, placeholder);
        TeamLoadStateRegistry.setTeamLoadState(teamId, TeamLoadState.LOADING);
        cir.setReturnValue(placeholder);

        MySQLBackend.getInstance().loadTeamDataAsync(teamId).whenComplete((tag, err) -> {
            if (err != null) {
                FTBQuestsSync.LOGGER.error("Async MySQL load failed for team {} - saves blocked", teamId, err);
                TeamLoadStateRegistry.setTeamLoadState(teamId, TeamLoadState.FAILED);
                return;
            }
            if (tag == null) {
                FTBQuestsSync.LOGGER.debug("No MySQL data for team {} - treating as new", teamId);
                TeamLoadStateRegistry.setTeamLoadState(teamId, TeamLoadState.NEW);
                return;
            }
            // Hydrate on server thread — teamDataMap must only be mutated there.
            net.minecraft.server.MinecraftServer srv = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
            if (srv == null) {
                TeamLoadStateRegistry.setTeamLoadState(teamId, TeamLoadState.FAILED);
                return;
            }
            srv.execute(() -> {
                try {
                    TeamData td = teamDataMap.get(teamId);
                    if (td == null) {
                        td = new TeamData(teamId, file);
                        teamDataMap.put(teamId, td);
                    }
                    td.deserializeNBT(SNBTCompoundTag.of(tag));
                    TeamLoadStateRegistry.setTeamLoadState(teamId, TeamLoadState.LOADED);
                    FTBQuestsSync.LOGGER.info("Hydrated team {} from MySQL async (getOrCreate)", teamId);
                } catch (Exception e) {
                    FTBQuestsSync.LOGGER.error("Failed to hydrate team {} from MySQL", teamId, e);
                    TeamLoadStateRegistry.setTeamLoadState(teamId, TeamLoadState.FAILED);
                }
            });
        });
    }
}
