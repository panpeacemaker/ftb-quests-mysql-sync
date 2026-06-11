package net.agrarius.ftbquestssync.quests.sync;

import dev.ftb.mods.ftbquests.quest.BaseQuestFile;
import net.minecraft.nbt.CompoundTag;

/**
 * Field-level union of TeamData NBT for cross-server quest sync.
 *
 * <p>Concurrent quest completions on two servers BOTH need to survive after
 * Redis sync; replacing the local snapshot with the remote one (vanilla
 * behaviour) would erase whichever side wrote second. So we union the
 * progress maps key-by-key:</p>
 *
 * <ul>
 *   <li>started, completed             - keep the earliest timestamp</li>
 *   <li>task_progress, completion_count - keep the highest count</li>
 *   <li>claimed_rewards, repeatable    - union, prefer local on key clash</li>
 *   <li>player_data                    - union per-player compound tags</li>
 * </ul>
 *
 * <p>Top-level scalars (uuid, version, name, lock, rewards_blocked) come
 * from the remote snapshot when present so server-driven config edits
 * still propagate.</p>
 */
public final class TeamDataMerger {

    private TeamDataMerger() {
    }

    public static CompoundTag mergeTeamDataNbt(CompoundTag local, CompoundTag remote, BaseQuestFile file) {
        CompoundTag out = local.copy();
        CompoundTag remoteForMerge = remote.copy();
        for (String key : remote.getAllKeys()) {
            if (!out.contains(key)) {
                out.put(key, remote.get(key).copy());
            }
        }
        ShopCycleMergePolicy.applyShopCycleMergePolicy(out, local, remoteForMerge, file);
        mergeMapMinLong(out, remoteForMerge, "started");
        mergeMapMinLong(out, remoteForMerge, "completed");
        mergeMapMaxLong(out, remoteForMerge, "task_progress");
        mergeMapMaxLong(out, remoteForMerge, "completion_count");
        mergeCompoundUnion(out, remoteForMerge, "claimed_rewards");
        mergeCompoundUnion(out, remoteForMerge, "repeatable");
        mergeCompoundUnion(out, remoteForMerge, "player_data");
        // Scalars: prefer remote (server-driven) where it actually has the key.
        for (String scalar : new String[]{"name", "lock", "rewards_blocked"}) {
            if (remoteForMerge.contains(scalar)) {
                out.put(scalar, remoteForMerge.get(scalar).copy());
            }
        }
        return out;
    }

    private static void mergeMapMinLong(CompoundTag out, CompoundTag remote, String key) {
        CompoundTag remoteMap = remote.getCompound(key);
        if (remoteMap.isEmpty()) return;
        CompoundTag merged = out.getCompound(key).copy();
        for (String k : remoteMap.getAllKeys()) {
            long remoteVal = remoteMap.getLong(k);
            if (!merged.contains(k)) {
                merged.putLong(k, remoteVal);
            } else {
                long localVal = merged.getLong(k);
                merged.putLong(k, Math.min(localVal, remoteVal));
            }
        }
        out.put(key, merged);
    }

    private static void mergeMapMaxLong(CompoundTag out, CompoundTag remote, String key) {
        CompoundTag remoteMap = remote.getCompound(key);
        if (remoteMap.isEmpty()) return;
        CompoundTag merged = out.getCompound(key).copy();
        for (String k : remoteMap.getAllKeys()) {
            long remoteVal = remoteMap.getLong(k);
            if (!merged.contains(k)) {
                merged.putLong(k, remoteVal);
            } else {
                long localVal = merged.getLong(k);
                merged.putLong(k, Math.max(localVal, remoteVal));
            }
        }
        out.put(key, merged);
    }

    private static void mergeCompoundUnion(CompoundTag out, CompoundTag remote, String key) {
        CompoundTag remoteMap = remote.getCompound(key);
        if (remoteMap.isEmpty()) return;
        CompoundTag merged = out.getCompound(key).copy();
        for (String k : remoteMap.getAllKeys()) {
            if (!merged.contains(k)) {
                merged.put(k, remoteMap.get(k).copy());
            }
        }
        out.put(key, merged);
    }

    static boolean nbtEquals(CompoundTag a, CompoundTag b) {
        try {
            return a.equals(b);
        } catch (Exception e) {
            return false;
        }
    }
}
