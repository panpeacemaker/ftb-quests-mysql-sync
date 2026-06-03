package net.agrarius.ftbquestssync.mixin;

import net.agrarius.ftbquestssync.Config;
import net.agrarius.ftbquestssync.MySQLBackend;
import dev.ftb.mods.ftbquests.quest.BaseQuestFile;
import dev.ftb.mods.ftbquests.quest.TeamData;
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
        TeamData existing = teamDataMap.get(teamId);
        if (!Config.syncQuests || existing != null || !isServerSide() || !MySQLBackend.getInstance().isAvailable()) {
            return;
        }

        // Do not block the Minecraft thread in getOrCreateTeamData().
        // Cross-server catch-up happens through Redis remote apply and login-time reload.
    }
}
