package net.agrarius.ftbquestssync.mixin;

import dev.ftb.mods.ftbteams.api.client.KnownClientPlayer;
import dev.ftb.mods.ftbteams.data.PlayerTeam;
import net.agrarius.ftbquestssync.config.Config;
import net.agrarius.ftbquestssync.teams.PresenceSync;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Override the client-facing {@link KnownClientPlayer#online()} flag so that
 * the FTB Teams GUI member-list dot reflects cross-server presence.
 *
 * <p>This only affects the <em>client</em> representation; the server's
 * {@code getOnlineMembers()} still returns real local {@link ServerPlayer}
 * instances and must not be modified.</p>
 */
@Mixin(value = PlayerTeam.class, remap = false)
public class PlayerTeamPresenceMixin {

    @Inject(method = "createClientPlayer", at = @At("RETURN"), cancellable = true)
    private void ftbQuestsSync$onCreateClientPlayer(CallbackInfoReturnable<KnownClientPlayer> cir) {
        if (!Config.syncTeams) return;
        KnownClientPlayer original = cir.getReturnValue();
        if (original == null) return;
        boolean newOnline = original.online() || PresenceSync.isOnlineAnywhere(original.id());
        if (newOnline == original.online()) return;
        cir.setReturnValue(new KnownClientPlayer(
                original.id(),
                original.name(),
                newOnline,
                original.teamId(),
                original.profile(),
                original.extraData()
        ));
    }
}
