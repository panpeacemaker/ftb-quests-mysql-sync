package net.agrarius.ftbquestssync.mixin;

import net.agrarius.ftbquestssync.FTBQuestsSync;
import net.agrarius.ftbquestssync.MySQLBackend;
import net.agrarius.ftbquestssync.config.Config;
import net.agrarius.ftbquestssync.quests.rank.ShopCycleReset;
import net.agrarius.ftbquestssync.quests.rank.ShopRepeatableSync;
import dev.ftb.mods.ftbquests.quest.BaseQuestFile;
import dev.ftb.mods.ftbquests.quest.Quest;
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
import net.agrarius.ftbquestssync.RedisSync;
import net.agrarius.ftbquestssync.quests.rank.RankSoloProgress;
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
 * Scope policy (as of 1.0.6):
 *   PLAYER scope — only when the chapter is in soloChapterIds AND is NOT
 *                  overridden by teamClaimChapterIds (e.g. ranks, QoL).
 *   TEAM scope   — everything else, including all regular quest chapters
 *                  (early_game, mid_game, …). One reward per team cross-server.
 *
 * teamClaimChapterIds acts as an explicit TEAM-override: a chapter listed there
 * is always TEAM-scoped even if it also appears in soloChapterIds (e.g. shop).
 *
 * DB outages follow {@code rewardFailClosed}. Production should fail closed for
 * economy-sensitive rewards.
 */
@Mixin(value = TeamData.class, remap = false)
public abstract class RewardClaimMixin {

    @Unique
    private static final ThreadLocal<Boolean> ftbQuestsSync$bypassClaimGuard = ThreadLocal.withInitial(() -> false);

    @Unique
    private static final ThreadLocal<ShopCycleReset> ftbQuestsSync$pendingShopReset = new ThreadLocal<>();

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

        long chapterId = reward.getQuest().getChapter() != null ? reward.getQuest().getChapter().getId() : 0L;
        boolean teamClaimOverride = Config.teamClaimChapterIds.contains(chapterId);
        boolean teamShared = Config.teamSharedChapterIds.contains(chapterId) && !teamClaimOverride;
        boolean rankQuestReward = RankSoloProgress.isRankQuest(reward.getQuest().id);
        boolean soloChapter = Config.soloChapterIds.contains(chapterId) && !teamClaimOverride && !teamShared;
        boolean soloScopedReward = (rankQuestReward || soloChapter) && Config.soloRewardsPerPlayer;
        boolean effectiveTeamReward = reward.isTeamReward();

        // Effective team rewards outside policy sets still need MySQL dedupe to close #10.
        if (!soloScopedReward && !teamClaimOverride && !teamShared && !effectiveTeamReward) {
            FTBQuestsSync.LOGGER.info(
                    "claimReward pass-through (vanilla): player={} quest={} reward={} chapter={}",
                    player.getUUID(), reward.getQuest().id, reward.id, String.format("%016X", chapterId));
            return;
        }

        RewardClaimType claimType = getClaimType(player.getUUID(), reward);
        FTBQuestsSync.LOGGER.info(
                "claimReward: player={} quest={} reward={} claimType={} rankQuest={} solo={} teamOverride={}",
                player.getUUID(), reward.getQuest().id, reward.id, claimType, rankQuestReward, soloScopedReward, teamClaimOverride);
        if (claimType == RewardClaimType.CANT_CLAIM || claimType == RewardClaimType.CLAIMED) {
            ci.cancel();
            return;
        }

        if (rankQuestReward && !RankSoloProgress.isPlayerSoloQuestComplete(player.getUUID(), reward.getQuest())) {
            ci.cancel();
            return;
        }

        if (soloScopedReward && !teamClaimOverride && RankSoloProgress.isRepeatableSoloQuest(reward.getQuest().id)) {
            FTBQuestsSync.LOGGER.info(
                    "Bypassing DB reward dedupe for repeatable solo reward: player={} team={} quest={} reward={}",
                    player.getUUID(), teamId, reward.getQuest().id, reward.id);
            return;
        }

        // TEAM scope by default; PLAYER only for solo chapters (ranks/QoL) not overridden by teamClaimChapterIds.
        // Resolve the TEAM dedup key from the canonical DB membership cache (fallback: local teamId)
        // so two servers with a momentarily split local team still key the claim on the same team (#10).
        String scopeType = (soloScopedReward && Config.teamRewardsDedupGlobal) ? "PLAYER" : "TEAM";
        UUID canonicalTeamId = net.agrarius.ftbquestssync.teams.MembershipCache.resolveTeam(player.getUUID(), teamId);
        UUID claimUuid = "TEAM".equals(scopeType) ? canonicalTeamId : player.getUUID();
        long rewardId = reward.id;
        long cycle = (teamClaimOverride || teamShared) ? ((TeamData)(Object)this).getCompletionCount(reward.getQuest()) : 0L;
        long[] questRewardIds = (teamClaimOverride || teamShared) ? ShopRepeatableSync.rewardIds(reward.getQuest()) : new long[]{rewardId};

        ci.cancel();
        UUID tid = teamId;
        MySQLBackend.getInstance()
                .tryClaimRewardScopedAsync(tid, rewardId, scopeType, claimUuid, cycle, now, questRewardIds)
                .whenComplete((claimResult, error) -> {
            player.getServer().execute(() -> {
                if (error != null || claimResult == null || !claimResult.firstClaim) {
                    player.sendSystemMessage(Component
                            .literal("Reward already claimed on another server.")
                            .withStyle(ChatFormatting.RED));
                    FTBQuestsSync.LOGGER.info(
                            "Cancelled duplicate claimReward: player={} team={} reward={} scopeType={} cycle={} error={}",
                            player.getUUID(), tid, rewardId, scopeType, cycle,
                            error == null ? "none" : error.getClass().getSimpleName());
                    RedisSync.getInstance().forceReloadAndPushTo(tid, player);
                    return;
                }

                try {
                    if (teamClaimOverride && claimResult.cycleComplete) {
                        ftbQuestsSync$pendingShopReset.set(new ShopCycleReset(reward.getQuest(), player.getUUID(), now, cycle));
                    }
                    ftbQuestsSync$bypassClaimGuard.set(true);
                    claimReward(player, reward, notify, now);
                } finally {
                    ftbQuestsSync$bypassClaimGuard.set(false);
                    ftbQuestsSync$pendingShopReset.remove();
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
        boolean playerComplete = RankSoloProgress.isPlayerSoloQuestComplete(playerUuid, reward.getQuest());
        if (!playerComplete) {
            cir.setReturnValue(RewardClaimType.CANT_CLAIM);
            return;
        }
        if (cir.getReturnValue() == RewardClaimType.CANT_CLAIM) {
            cir.setReturnValue(RewardClaimType.CAN_CLAIM);
        }
    }

    @Inject(method = "resetReward(Ljava/util/UUID;Ldev/ftb/mods/ftbquests/quest/reward/Reward;)Z", at = @At("RETURN"))
    private void ftbQuestsSync$onResetReward(UUID claimUuid, Reward reward, CallbackInfoReturnable<Boolean> cir) {
        if (!FTBQuestsSync.serverStarted) return;
        if (file == null || !file.isServerSide() || reward == null || !Boolean.TRUE.equals(cir.getReturnValue())) return;
        long chapterId = reward.getQuest().getChapter() != null ? reward.getQuest().getChapter().getId() : 0L;
        boolean teamClaimOverride = Config.teamClaimChapterIds.contains(chapterId);
        if (teamClaimOverride) return;
        boolean teamShared = Config.teamSharedChapterIds.contains(chapterId) && !teamClaimOverride;
        boolean soloChapter = Config.soloChapterIds.contains(chapterId) && !teamClaimOverride && !teamShared;
        boolean soloScopedReward = (RankSoloProgress.isRankQuest(reward.getQuest().id) || soloChapter) && Config.soloRewardsPerPlayer;
        boolean effectiveTeamReward = reward.isTeamReward();
        if (!soloScopedReward && !teamShared && !effectiveTeamReward) return;
        String scopeType = (soloScopedReward && Config.teamRewardsDedupGlobal) ? "PLAYER" : "TEAM";
        UUID scopeUuid = "TEAM".equals(scopeType) ? teamId : claimUuid;
        MySQLBackend.getInstance().deleteRewardClaimScopedAsync(scopeType, scopeUuid, reward.id);
    }

    @Inject(method = "deleteReward(Ldev/ftb/mods/ftbquests/quest/reward/Reward;)V", at = @At("RETURN"))
    private void ftbQuestsSync$onDeleteReward(Reward reward, CallbackInfo ci) {
        if (!FTBQuestsSync.serverStarted) return;
        if (file == null || !file.isServerSide() || reward == null) return;
        MySQLBackend.getInstance().deleteAllClaimsForRewardAsync(teamId, reward.id);
    }

    @Inject(
            method = "claimReward(Lnet/minecraft/server/level/ServerPlayer;Ldev/ftb/mods/ftbquests/quest/reward/Reward;ZJ)V",
            at = @At("RETURN"))
    private void ftbQuestsSync$forceSaveAfterClaim(ServerPlayer player, Reward reward, boolean notify, long now, CallbackInfo ci) {
        if (file == null || !file.isServerSide() || !MySQLBackend.getInstance().isAvailable()) return;
        if (!ftbQuestsSync$bypassClaimGuard.get()) return;
        ShopCycleReset reset = ftbQuestsSync$pendingShopReset.get();
        if (reset != null && reward != null && reset.quest == reward.getQuest()) {
            ShopRepeatableSync.resetIfCycleComplete((TeamData)(Object)this, reset.quest, reset.playerUuid, reset.now, reset.cycle);
        }
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
