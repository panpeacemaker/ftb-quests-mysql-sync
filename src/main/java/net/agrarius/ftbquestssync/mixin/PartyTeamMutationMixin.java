package net.agrarius.ftbquestssync.mixin;

import com.mojang.authlib.GameProfile;
import dev.ftb.mods.ftbteams.api.Team;
import dev.ftb.mods.ftbteams.data.PartyTeam;
import net.agrarius.ftbquestssync.Config;
import net.agrarius.ftbquestssync.teams.TeamMutationGuard;
import net.agrarius.ftbquestssync.teams.TeamSync;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Collection;
import java.util.UUID;

/**
 * Fallback sync hooks for native PartyTeam mutations that are triggered by
 * slash-commands (non-GUI path).
 *
 * The GUI path is intercepted earlier by {@link PlayerGUIOperationMessageMixin}
 * and cancelled before these methods are reached.  This mixin therefore only
 * fires for the command fallback.
 */
@Mixin(value = PartyTeam.class, remap = false)
public class PartyTeamMutationMixin {

    @Inject(method = "kick(Lnet/minecraft/commands/CommandSourceStack;Ljava/util/Collection;)I",
            at = @At("HEAD"))
    private void ftbQuestsSync$onKickHead(CommandSourceStack source, Collection<GameProfile> targets,
                                          CallbackInfoReturnable<Integer> cir) {
        if (!Config.syncTeams) return;
        if (targets == null || targets.isEmpty()) return;
        UUID teamId = ((Team) (Object) this).getId();
        if (TeamMutationGuard.isTeamSuppressed(teamId)) return;
        for (GameProfile target : targets) {
            TeamSync.getInstance().markExplicitKick(teamId, target.getId());
        }
    }

    @Inject(method = "promote(Lnet/minecraft/server/level/ServerPlayer;Ljava/util/Collection;)I",
            at = @At("RETURN"))
    private void ftbQuestsSync$onPromoteReturn(ServerPlayer sender, Collection<GameProfile> targets,
                                               CallbackInfoReturnable<Integer> cir) {
        if (!Config.syncTeams) return;
        UUID teamId = ((Team) (Object) this).getId();
        if (TeamMutationGuard.isTeamSuppressed(teamId)) return;
        for (GameProfile target : targets) {
            TeamSync.getInstance().syncRankChange(sender.getServer(), teamId, target.getId(), "OFFICER");
        }
    }

    @Inject(method = "demote(Lnet/minecraft/server/level/ServerPlayer;Ljava/util/Collection;)I",
            at = @At("RETURN"))
    private void ftbQuestsSync$onDemoteReturn(ServerPlayer sender, Collection<GameProfile> targets,
                                              CallbackInfoReturnable<Integer> cir) {
        if (!Config.syncTeams) return;
        UUID teamId = ((Team) (Object) this).getId();
        if (TeamMutationGuard.isTeamSuppressed(teamId)) return;
        for (GameProfile target : targets) {
            TeamSync.getInstance().syncRankChange(sender.getServer(), teamId, target.getId(), "MEMBER");
        }
    }

    @Inject(method = "transferOwnership(Lnet/minecraft/commands/CommandSourceStack;Lcom/mojang/authlib/GameProfile;)I",
            at = @At("RETURN"))
    private void ftbQuestsSync$onTransferOwnerReturn(CommandSourceStack source, GameProfile target,
                                                     CallbackInfoReturnable<Integer> cir) {
        if (!Config.syncTeams) return;
        UUID teamId = ((Team) (Object) this).getId();
        if (TeamMutationGuard.isTeamSuppressed(teamId)) return;
        // Native transferOwnership fires PlayerTransferredTeamOwnershipEvent;
        // TeamSync.onOwnershipTransferred catches it and handles DB+Redis sync.
        // This mixin is a no-op safeguard for the slash-command fallback path.
    }

    @Inject(method = "invite(Lnet/minecraft/server/level/ServerPlayer;Ljava/util/Collection;)I",
            at = @At("RETURN"))
    private void ftbQuestsSync$onInviteReturn(ServerPlayer sender, Collection<GameProfile> targets,
                                              CallbackInfoReturnable<Integer> cir) {
        if (!Config.syncTeams) return;
        UUID teamId = ((Team) (Object) this).getId();
        if (TeamMutationGuard.isTeamSuppressed(teamId)) return;
        PartyTeam partyTeam = (PartyTeam) (Object) this;
        for (GameProfile target : targets) {
            if (target == null || target.getId() == null) continue;
            TeamSync.getInstance().queueInvite(sender, partyTeam, target);
        }
    }
}
