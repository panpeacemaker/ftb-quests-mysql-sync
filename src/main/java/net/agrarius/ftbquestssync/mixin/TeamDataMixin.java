package net.agrarius.ftbquestssync.mixin;

import net.agrarius.ftbquestssync.FTBQuestsSync;
import net.agrarius.ftbquestssync.Config;
import net.agrarius.ftbquestssync.MySQLBackend;
import net.agrarius.ftbquestssync.RedisSync;
import net.agrarius.ftbquestssync.access.TeamDataAccess;
import dev.ftb.mods.ftblibrary.snbt.SNBTCompoundTag;
import dev.ftb.mods.ftbquests.quest.BaseQuestFile;
import dev.ftb.mods.ftbquests.quest.TeamData;
import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

@Mixin(value = TeamData.class, remap = false)
public abstract class TeamDataMixin implements TeamDataAccess {

    @Shadow
    private UUID teamId;

    @Shadow
    private boolean shouldSave;

    @Shadow
    private BaseQuestFile file;

    @Shadow
    public abstract SNBTCompoundTag serializeNBT();

    /**
     * Monotonic revision counter. Each markDirty() increments it.
     * The async MySQL save callback checks if its revision is still
     * current; if a newer save already started, we skip the Redis
     * publish — preventing stale intermediate states from propagating.
     */
    @Unique
    private final AtomicLong ftbQuestsSync$syncRevision = new AtomicLong(0);

    /**
     * Timestamp of the last queued MySQL save for this team.
     * Guards against bursts of markDirty() calls queuing redundant async writes.
     */
    @Unique
    private long ftbQuestsSync$lastSaveMs = 0;

    /**
     * Minimum interval between two non-forced MySQL saves for the same team.
     */
    @Unique
    private static final long SAVE_DEBOUNCE_MS = 3000L;

    /**
     * Set to true to bypass debounce on next save. Reset after save is queued.
     */
    @Unique
    private volatile boolean ftbQuestsSync$forceSaveRequested = false;

    /**
     * Hook on markDirty — called by FTB Quests on every state change.
     *
     * We save to MySQL asynchronously on debounced markDirty events, and logout
     * can request one force-save that bypasses the debounce. Only the latest
     * queued revision publishes to Redis. This ensures:
     *   - DB persistence happens promptly without redundant write bursts
     *   - Redis never receives stale intermediate state
     *   - Concurrent quest completions across servers converge safely
     */
    @Inject(method = "markDirty", at = @At("TAIL"))
    private void ftbQuestsSync$onMarkDirty(CallbackInfo ci) {
        if (!Config.syncQuests || file == null || !file.isServerSide()) return;
        if (!MySQLBackend.getInstance().isAvailable()) return;

        long now = System.currentTimeMillis();
        boolean force = ftbQuestsSync$forceSaveRequested;
        ftbQuestsSync$forceSaveRequested = false;

        if (!force && now - ftbQuestsSync$lastSaveMs < SAVE_DEBOUNCE_MS) {
            FTBQuestsSync.LOGGER.debug("markDirty debounced for team={} (lastSaveMs={})", teamId, ftbQuestsSync$lastSaveMs);
            return;
        }
        ftbQuestsSync$lastSaveMs = now;

        long myRev = ftbQuestsSync$syncRevision.incrementAndGet();

        SNBTCompoundTag serialized;
        try {
            serialized = serializeNBT();
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.error("Serialize failed for team {} on markDirty", teamId, e);
            return;
        }

        CompoundTag snapshot = ((CompoundTag) serialized).copy();
        UUID tid = teamId;

        if (force) {
            FTBQuestsSync.LOGGER.info("Force save triggered for team={} (player leaving)", tid);
        }

        MySQLBackend.getInstance().saveTeamDataAsync(tid, snapshot).whenComplete((result, error) -> {
            if (error != null) {
                FTBQuestsSync.LOGGER.warn(
                        "Async MySQL save failed for team {} (rev {})", tid, myRev, error);
                return;
            }
            if (result == null) {
                FTBQuestsSync.LOGGER.warn(
                        "Async MySQL save did not complete for team {} (rev {})", tid, myRev);
                return;
            }
            if (myRev == ftbQuestsSync$syncRevision.get()) {
                RedisSync.getInstance().publishTeamUpdate(result.teamId, result.revision, result.hashHex);
            } else {
                FTBQuestsSync.LOGGER.debug(
                        "Suppressed stale Redis publish for team {} rev {} (superseded by rev {})",
                        tid, myRev, ftbQuestsSync$syncRevision.get());
            }
        });
    }

    @Unique
    @Override
    public void ftbQuestsSync$requestForceSave() {
        this.ftbQuestsSync$forceSaveRequested = true;
    }
}
