package net.agrarius.ftbquestssync.mixin;

import dev.architectury.networking.NetworkManager;
import net.agrarius.ftbquestssync.FTBQuestsSync;
import net.agrarius.ftbquestssync.RankSoloProgress;
import dev.ftb.mods.ftbquests.net.ClaimRewardMessage;
import dev.ftb.mods.ftbquests.quest.ServerQuestFile;
import dev.ftb.mods.ftbquests.quest.TeamData;
import dev.ftb.mods.ftbquests.quest.reward.Reward;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * FTB Quests validates reward claim packets against shared TeamData before it
 * calls TeamData.claimReward. Solo rank/shop progress is stored per player, so
 * the client can correctly show CAN_CLAIM while shared TeamData still says the
 * quest is not completed. Without this bridge the server drops the packet and
 * clicking the shop/rank reward appears to do nothing.
 */
@Mixin(value = ClaimRewardMessage.class, remap = false)
public abstract class ClaimRewardMessageMixin {

    @Shadow
    @Final
    private long id;

    @Shadow
    @Final
    private boolean notify;

    @Inject(method = "handle", at = @At("HEAD"), cancellable = true)
    private void ftbQuestsSync$handleSoloRewardClaim(NetworkManager.PacketContext context, CallbackInfo ci) {
        Reward reward = ServerQuestFile.INSTANCE.getReward(id);
        if (reward == null || reward.getQuest() == null || !RankSoloProgress.isRankQuest(reward.getQuest().id)) return;

        ServerPlayer player = (ServerPlayer) context.getPlayer();
        TeamData teamData = ServerQuestFile.INSTANCE.getOrCreateTeamData(player);
        if (teamData.isCompleted(reward.getQuest())) return;
        if (!RankSoloProgress.isPlayerSoloQuestComplete(player.getUUID(), reward.getQuest().id)) return;

        FTBQuestsSync.LOGGER.info(
                "Allowing solo reward claim from per-player progress: player={} quest={} reward={}",
                player.getUUID(), reward.getQuest().id, reward.id);
        teamData.claimReward(player, reward, notify);
        ci.cancel();
    }
}
