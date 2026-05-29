package net.agrarius.ftbquestssync.mixin;

import dev.architectury.networking.NetworkManager;
import net.agrarius.ftbquestssync.Config;
import net.agrarius.ftbquestssync.FTBQuestsSync;
import net.agrarius.ftbquestssync.RankSoloProgress;
import dev.ftb.mods.ftbquests.net.RequestTeamDataMessage;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * After vanilla FTB Quests responds to RequestTeamDataMessage with a stripped
 * SyncTeamDataMessage (no solo chapter data), replay the player's solo progress
 * so that QoL/rank completions remain visible on the client.
 *
 * Root cause: SyncQuestsMessage.handle() on the client sends RequestTeamDataMessage
 * back to the server, which replies with another SyncTeamDataMessage. This message
 * overwrites any solo-progress packets we already sent during forceReloadAndPushTo,
 * causing QoL quest progress to disappear after every reconnect or server switch.
 */
@Mixin(value = RequestTeamDataMessage.class, remap = false)
public abstract class RequestTeamDataMessageMixin {

    @Inject(method = "handle", at = @At("TAIL"))
    private void ftbQuestsSync$replaySoloAfterVanillaTeamData(NetworkManager.PacketContext ctx, CallbackInfo ci) {
        if (!Config.syncSoloProgressPerPlayer) return;
        if (!(ctx.getPlayer() instanceof ServerPlayer player)) return;

        net.minecraft.server.MinecraftServer server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server == null || !server.isRunning()) return;

        RankSoloProgress.pushToPlayerAsync(player.getUUID(), server);
        FTBQuestsSync.LOGGER.debug(
                "Queued solo progress replay after vanilla RequestTeamData for player={}",
                player.getUUID());
    }
}
