package net.agrarius.ftbquestssync.mixin;

import net.agrarius.ftbquestssync.config.Config;
import dev.ftb.mods.ftblibrary.config.Tristate;
import dev.ftb.mods.ftbquests.quest.Chapter;
import dev.ftb.mods.ftbquests.quest.Quest;
import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = Quest.class, remap = false)
public abstract class QuestMixin {

    @Shadow
    private Tristate canRepeat;

    @Shadow
    public abstract Chapter getChapter();

    @Inject(method = "readData", at = @At("RETURN"))
    private void ftbQuestsSync$forceShopRepeatOnLoad(CompoundTag tag, CallbackInfo ci) {
        // Javap on FTB Quests 2001.4.18 confirmed Quest.writeNetData emits
        // canRepeat from this private Tristate as flags 8192/16384. Setting it
        // at readData reaches vanilla clients as can_repeat=true.
        ftbQuestsSync$forceShopRepeatFlag();
    }

    @Inject(method = "canBeRepeated()Z", at = @At("RETURN"), cancellable = true)
    private void ftbQuestsSync$repeatableOverride(CallbackInfoReturnable<Boolean> cir) {
        if (ftbQuestsSync$isShopQuest()) {
            cir.setReturnValue(true);
        }
    }

    private void ftbQuestsSync$forceShopRepeatFlag() {
        if (ftbQuestsSync$isShopQuest()) {
            canRepeat = Tristate.TRUE;
        }
    }

    private boolean ftbQuestsSync$isShopQuest() {
        Chapter chapter = getChapter();
        long chapterId = chapter != null ? chapter.getId() : 0L;
        return Config.teamClaimChapterIds.contains(chapterId);
    }
}
