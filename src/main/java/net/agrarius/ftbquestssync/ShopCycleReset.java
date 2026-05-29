package net.agrarius.ftbquestssync;

import dev.ftb.mods.ftbquests.quest.Quest;

import java.util.UUID;

/**
 * Pending shop repeat-reset marker, captured at claim guard time and consumed
 * at claimReward RETURN by {@link ShopRepeatableSync#resetIfCycleComplete}.
 *
 * MUST live OUTSIDE the {@code net.agrarius.ftbquestssync.mixin} package:
 * Mixin treats every class in a configured mixin package as a mixin and throws
 * IllegalClassLoadError on any direct reference (the RewardClaimMixin ThreadLocal
 * references this type directly). Public fields so the mixin can read them
 * across the package boundary.
 */
public final class ShopCycleReset {

    public final Quest quest;
    public final UUID playerUuid;
    public final long now;
    public final long cycle;

    public ShopCycleReset(Quest quest, UUID playerUuid, long now, long cycle) {
        this.quest = quest;
        this.playerUuid = playerUuid;
        this.now = now;
        this.cycle = cycle;
    }
}
