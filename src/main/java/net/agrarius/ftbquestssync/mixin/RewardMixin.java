package net.agrarius.ftbquestssync.mixin;

import net.agrarius.ftbquestssync.Config;
import dev.ftb.mods.ftbquests.quest.Quest;
import dev.ftb.mods.ftbquests.quest.reward.Reward;
import dev.ftb.mods.ftblibrary.config.Tristate;
import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = Reward.class, remap = false)
public abstract class RewardMixin {

    /**
     * MUST be @Shadow. Without it, Mixin treats this unannotated abstract method
     * as an overwrite and replaces the concrete {@code Reward.getQuest()} with an
     * abstract one. Reward is already abstract so no apply-time crash, but EVERY
     * call to {@code Reward.getQuest()} (canClaim, onButtonClicked, the whole
     * claim path) then throws AbstractMethodError -> all reward claims silently
     * die. This single missing annotation broke reward claiming server-wide.
     */
    @Shadow
    public abstract Quest getQuest();

    @Shadow
    private Tristate team;

    @Inject(method = "readData", at = @At("RETURN"))
    private void ftbQuestsSync$forceShopTeamOnLoad(CompoundTag tag, CallbackInfo ci) {
        // Javap on FTB Quests 2001.4.18 confirmed Reward.writeNetData writes
        // this private team Tristate directly via Tristate.NAME_MAP.write(...).
        // Setting it at readData reaches vanilla clients as team_reward=true.
        ftbQuestsSync$forceShopTeamFlag();
    }

    @Inject(method = "isTeamReward()Z", at = @At("RETURN"), cancellable = true)
    private void ftbQuestsSync$teamRewardOverride(CallbackInfoReturnable<Boolean> cir) {
        long chapterId = getQuest().getChapter() != null ? getQuest().getChapter().getId() : 0L;
        boolean teamClaimChapter = Config.teamClaimChapterIds.contains(chapterId);
        boolean teamSharedChapter = Config.teamSharedChapterIds.contains(chapterId) && !teamClaimChapter;
        boolean soloChapter = Config.soloChapterIds.contains(chapterId) && !teamClaimChapter && !teamSharedChapter;
        if (teamClaimChapter || teamSharedChapter) {
            cir.setReturnValue(true);
            return;
        }
        // Rank/QoL chapters: force solo reward (each player claims their own).
        if (soloChapter && Config.soloRewardsPerPlayer) {
            cir.setReturnValue(false);
        }
    }

    private void ftbQuestsSync$forceShopTeamFlag() {
        Quest quest = getQuest();
        long chapterId = quest != null && quest.getChapter() != null ? quest.getChapter().getId() : 0L;
        if (Config.teamClaimChapterIds.contains(chapterId) || Config.teamSharedChapterIds.contains(chapterId)) {
            team = Tristate.TRUE;
        }
    }
}
