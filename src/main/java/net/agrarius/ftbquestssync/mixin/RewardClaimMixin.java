package net.agrarius.ftbquestssync.mixin;

import net.agrarius.ftbquestssync.FTBQuestsSync;
import net.agrarius.ftbquestssync.Config;
import net.agrarius.ftbquestssync.MySQLBackend;
import dev.ftb.mods.ftbquests.quest.BaseQuestFile;
import dev.ftb.mods.ftbquests.quest.TeamData;
import dev.ftb.mods.ftbquests.quest.reward.Reward;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.UUID;
import dev.ftb.mods.ftbquests.net.SyncTeamDataMessage;
import net.agrarius.ftbquestssync.MySQLBackend.SaveResult;
import net.agrarius.ftbquestssync.RedisSync;
import net.agrarius.ftbquestssync.RankSoloProgress;
import dev.ftb.mods.ftbquests.quest.reward.RewardClaimType;
import net.minecraft.nbt.CompoundTag;
import dev.ftb.mods.ftblibrary.snbt.SNBTCompoundTag;

/**
 * Cross-server reward claim idempotency.
 *
 * Race we are closing: agr1 player X claims team reward R → grants items.
 * Before MySQL/Redis propagates the new {@code claimedRewards} state to agr2,
 * a teammate Y on agr2 clicks the same reward → vanilla FTB Quests sees its
 * local (stale) {@code teamDataMap} entry without R → grants the items AGAIN
 * → duplicate.
 *
 * Guard: async atomic INSERT IGNORE into scoped reward table. Shared rewards
 * use TEAM scope; solo-policy rewards use PLAYER scope so rank/shop rewards
 * are not accidentally consumed by teammates.
 *
 * DB outages follow {@code rewardFailClosed}. Production should fail closed for
 * economy-sensitive rewards.
 */
@Mixin(value = TeamData.class, remap = false)
public abstract class RewardClaimMixin {

    @Unique
    private static final ThreadLocal<Boolean> ftbQuestsSync$bypassClaimGuard = ThreadLocal.withInitial(() -> false);

    @Shadow
    private UUID teamId;

    @Shadow
    private BaseQuestFile file;

    @Shadow
    public abstract SNBTCompoundTag serializeNBT();

    @Shadow
    public abstract void claimReward(ServerPlayer player, Reward reward, boolean notify, long now);

    @Shadow
    public abstract RewardClaimType getClaimType(UUID playerUuid, Reward reward);

    @Inject(
            method = "claimReward(Lnet/minecraft/server/level/ServerPlayer;Ldev/ftb/mods/ftbquests/quest/reward/Reward;ZJ)V",
            at = @At("HEAD"),
            cancellable = true)
    private void ftbQuestsSync$onClaimReward(ServerPlayer player, Reward reward, boolean notify, long now, CallbackInfo ci) {
        if (file == null || !file.isServerSide()) return;
        if (reward == null) return;
        if (ftbQuestsSync$bypassClaimGuard.get()) return;

        boolean rankQuestReward = RankSoloProgress.isRankQuest(reward.getQuest().id);

        RewardClaimType claimType = getClaimType(player.getUUID(), reward);
        if (claimType == RewardClaimType.CANT_CLAIM || claimType == RewardClaimType.CLAIMED) {
            ci.cancel();
            return;
        }

        boolean soloScopedReward = rankQuestReward && Config.soloRewardsPerPlayer;
        if (soloScopedReward && RankSoloProgress.isRepeatableSoloQuest(reward.getQuest().id)) {
            FTBQuestsSync.LOGGER.info(
                    "Bypassing DB reward dedupe for repeatable solo reward: player={} team={} quest={} reward={}",
                    player.getUUID(), teamId, reward.getQuest().id, reward.id);
            return;
        }
        String scopeType = (!soloScopedReward && reward.isTeamReward() && Config.teamRewardsDedupGlobal) ? "TEAM" : "PLAYER";
        UUID claimUuid = "TEAM".equals(scopeType) ? teamId : player.getUUID();
        long rewardId = reward.id;

        ci.cancel();
        UUID tid = teamId;
        MySQLBackend.getInstance().tryClaimRewardScopedAsync(tid, rewardId, scopeType, claimUuid, now).whenComplete((firstClaim, error) -> {
            player.getServer().execute(() -> {
                if (error != null || !Boolean.TRUE.equals(firstClaim)) {
                    player.sendSystemMessage(Component
                            .literal("Reward already claimed on another server.")
                            .withStyle(ChatFormatting.RED));
                    FTBQuestsSync.LOGGER.info(
                            "Cancelled duplicate claimReward: player={} team={} reward={} scopeType={} error={}",
                            player.getUUID(), tid, rewardId, scopeType, error == null ? "none" : error.getClass().getSimpleName());
                    RedisSync.getInstance().forceReloadAndPushTo(tid, player);
                    return;
                }

                try {
                    ftbQuestsSync$bypassClaimGuard.set(true);
                    claimReward(player, reward, notify, now);
                } finally {
                    ftbQuestsSync$bypassClaimGuard.set(false);
                }
            });
        });
    }

    @Inject(
            method = "getClaimType(Ljava/util/UUID;Ldev/ftb/mods/ftbquests/quest/reward/Reward;)Ldev/ftb/mods/ftbquests/quest/reward/RewardClaimType;",
            at = @At("RETURN"),
            cancellable = true)
    private void ftbQuestsSync$rankSoloClaimType(UUID playerUuid, Reward reward, CallbackInfoReturnable<RewardClaimType> cir) {
        if (file == null || !file.isServerSide() || reward == null || !RankSoloProgress.isRankQuest(reward.getQuest().id)) return;
        if (cir.getReturnValue() == RewardClaimType.CANT_CLAIM
                && RankSoloProgress.isPlayerSoloQuestComplete(playerUuid, reward.getQuest().id)) {
            cir.setReturnValue(RewardClaimType.CAN_CLAIM);
        }
    }

    @Inject(method = "resetReward(Ljava/util/UUID;Ldev/ftb/mods/ftbquests/quest/reward/Reward;)Z", at = @At("RETURN"))
    private void ftbQuestsSync$onResetReward(UUID claimUuid, Reward reward, CallbackInfoReturnable<Boolean> cir) {
        if (file == null || !file.isServerSide() || reward == null || !Boolean.TRUE.equals(cir.getReturnValue())) return;
        boolean soloScopedReward = RankSoloProgress.isRankQuest(reward.getQuest().id) && Config.soloRewardsPerPlayer;
        String scopeType = (!soloScopedReward && reward.isTeamReward() && Config.teamRewardsDedupGlobal) ? "TEAM" : "PLAYER";
        UUID scopeUuid = "TEAM".equals(scopeType) ? teamId : claimUuid;
        MySQLBackend.getInstance().deleteRewardClaimScopedAsync(scopeType, scopeUuid, reward.id);
    }

    @Inject(method = "deleteReward(Ldev/ftb/mods/ftbquests/quest/reward/Reward;)V", at = @At("RETURN"))
    private void ftbQuestsSync$onDeleteReward(Reward reward, CallbackInfo ci) {
        if (file == null || !file.isServerSide() || reward == null) return;
        MySQLBackend.getInstance().deleteAllClaimsForRewardAsync(teamId, reward.id);
    }

    @Inject(
            method = "claimReward(Lnet/minecraft/server/level/ServerPlayer;Ldev/ftb/mods/ftbquests/quest/reward/Reward;ZJ)V",
            at = @At("RETURN"))
    private void ftbQuestsSync$forceSaveAfterClaim(ServerPlayer player, Reward reward, boolean notify, long now, CallbackInfo ci) {
        if (file == null || !file.isServerSide() || !MySQLBackend.getInstance().isAvailable()) return;
        if (!ftbQuestsSync$bypassClaimGuard.get()) return;
        try {
            CompoundTag snapshot = ((CompoundTag) serializeNBT()).copy();
            UUID tid = teamId;
            MySQLBackend.getInstance().saveTeamDataAsync(tid, snapshot).whenComplete((result, err) -> {
                if (err != null || result == null) {
                    FTBQuestsSync.LOGGER.warn("Immediate post-claim save failed for team {}", tid, err);
                    return;
                }
                RedisSync.getInstance().publishTeamUpdate(result.teamId, result.revision, result.hashHex);
                FTBQuestsSync.LOGGER.info("Immediate post-claim sync triggered: team={} rev={}", tid, result.revision);
            });
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.warn("Post-claim snapshot failed for team {}", teamId, e);
        }
    }
}
