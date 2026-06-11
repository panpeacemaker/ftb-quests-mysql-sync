package net.agrarius.ftbquestssync.mixin;

import dev.ftb.mods.ftbteams.data.AbstractTeam;
import net.agrarius.ftbquestssync.Config;
import net.agrarius.ftbquestssync.MySQLBackend;
import net.agrarius.ftbquestssync.teams.TeamSync;
import net.minecraft.commands.CommandSourceStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.UUID;

@Mixin(value = AbstractTeam.class, remap = false)
public class AbstractTeamInviteMixin {

    @Inject(method = "declineInvitation(Lnet/minecraft/commands/CommandSourceStack;)I",
            at = @At("RETURN"))
    private void ftbQuestsSync$onDeclineInvitationReturn(CommandSourceStack source,
                                                          CallbackInfoReturnable<Integer> cir) {
        if (!Config.syncTeams) return;
        AbstractTeam self = (AbstractTeam) (Object) this;
        UUID teamId = self.getId();
        try {
            net.minecraft.server.level.ServerPlayer player = source.getPlayerOrException();
            UUID playerId = player.getUUID();
            MySQLBackend.getInstance().deleteTeamInviteAsync(teamId, playerId);
            TeamSync.getInstance().publishInviteCancel(teamId, playerId);
        } catch (Exception e) {
            // Player may be non-player source; skip async cleanup
        }
    }
}
