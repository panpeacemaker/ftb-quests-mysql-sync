package net.agrarius.ftbquestssync.mixin;

import net.agrarius.ftbquestssync.FTBQuestsSync;
import net.agrarius.ftbquestssync.config.Config;
import net.agrarius.ftbquestssync.quests.rank.RankSoloProgress;
import dev.ftb.mods.ftbquests.quest.BaseQuestFile;
import dev.ftb.mods.ftbquests.quest.Quest;
import dev.ftb.mods.ftbquests.quest.QuestObject;
import dev.ftb.mods.ftbquests.quest.ServerQuestFile;
import dev.ftb.mods.ftbquests.quest.TeamData;
import dev.ftb.mods.ftbquests.quest.task.Task;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.UUID;

@Mixin(value = dev.ftb.mods.ftbquests.quest.TeamData.class, remap = false)
public abstract class RankSoloTeamDataMixin {
    @Shadow private UUID teamId;
    @Shadow private BaseQuestFile file;

    @Inject(method = "setProgress(Ldev/ftb/mods/ftbquests/quest/task/Task;J)V", at = @At("HEAD"), cancellable = true)
    private void ftbQuestsSync$rankSoloSetProgress(Task task, long progress, CallbackInfo ci) {
        if (file == null || !file.isServerSide() || task == null || !RankSoloProgress.isRankTask(task.id)) return;
        ServerPlayer player = null;
        try {
            player = ServerQuestFile.INSTANCE == null ? null : ServerQuestFile.INSTANCE.getCurrentPlayer();
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.debug("Could not resolve current player for solo quest progress", e);
        }
        if (player == null) return;
        RankSoloProgress.handleRankTaskProgress((TeamData) (Object) this, player, task, progress);
        if (task.getQuest() != null
                && task.getQuest().getChapter() != null
                && Config.teamClaimChapterIds.contains(task.getQuest().getChapter().getId())) {
            FTBQuestsSync.LOGGER.info(
                    "Allowing vanilla FTB repeatable solo progress: player={} quest={} task={} progress={}",
                    player.getUUID(), task.getQuest().id, task.id, progress);
            return;
        }
        ci.cancel();
    }

    @Inject(
            method = "isCompleted(Ldev/ftb/mods/ftbquests/quest/QuestObject;)Z",
            at = @At("RETURN"),
            cancellable = true)
    private void ftbQuestsSync$soloRankIsCompleted(QuestObject obj, CallbackInfoReturnable<Boolean> cir) {
        if (file == null || !file.isServerSide()) return;

        ServerPlayer player = ftbQuestsSync$currentPlayer();
        if (player == null) return;
        UUID playerUuid = player.getUUID();

        if (obj instanceof Task task && RankSoloProgress.isRankTask(task.id)) {
            cir.setReturnValue(RankSoloProgress.isPlayerSoloTaskComplete(playerUuid, task));
            return;
        }
        if (obj instanceof Quest quest && RankSoloProgress.isRankQuest(quest.id)) {
            cir.setReturnValue(RankSoloProgress.isPlayerSoloQuestComplete(playerUuid, quest));
        }
    }

    private static ServerPlayer ftbQuestsSync$currentPlayer() {
        try {
            return ServerQuestFile.INSTANCE == null ? null : ServerQuestFile.INSTANCE.getCurrentPlayer();
        } catch (Exception e) {
            return null;
        }
    }
}
