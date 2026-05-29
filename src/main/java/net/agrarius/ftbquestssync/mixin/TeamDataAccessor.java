package net.agrarius.ftbquestssync.mixin;

import dev.ftb.mods.ftbquests.quest.TeamData;
import dev.ftb.mods.ftbquests.util.QuestKey;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = TeamData.class, remap = false)
public interface TeamDataAccessor {

    @Accessor("claimedRewards")
    Object2LongMap<QuestKey> ftbQuestsSync$getClaimedRewards();

    @Accessor("completionCount")
    Long2IntMap ftbQuestsSync$getCompletionCount();

    @Accessor("questRepeatableTime")
    Long2LongMap ftbQuestsSync$getQuestRepeatableTime();
}
