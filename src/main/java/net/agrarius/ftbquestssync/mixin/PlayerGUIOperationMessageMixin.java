package net.agrarius.ftbquestssync.mixin;

import com.mojang.authlib.GameProfile;
import dev.ftb.mods.ftbteams.api.Team;
import dev.ftb.mods.ftbteams.api.TeamRank;
import dev.ftb.mods.ftbteams.data.PartyTeam;
import dev.ftb.mods.ftbteams.net.PlayerGUIOperationMessage;
import net.agrarius.ftbquestssync.Config;
import net.agrarius.ftbquestssync.FTBQuestsSync;
import net.agrarius.ftbquestssync.FtbSyncTeamCommand;
import net.agrarius.ftbquestssync.MySQLBackend;
import net.agrarius.ftbquestssync.RedisSync;
import net.agrarius.ftbquestssync.TeamMaterializer;
import net.agrarius.ftbquestssync.TeamSync;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.UUID;

/**
 * Intercept FTB Teams GUI operations (promote, demote, kick, transfer-owner)
 * and replace them with sync-aware handling that works for offline / peer-server
 * targets.  Cancels the native path only when the sync-aware handler actually
 * took action; otherwise falls through to native behaviour.
 */
@Mixin(value = PlayerGUIOperationMessage.class, remap = false)
public class PlayerGUIOperationMessageMixin {

    @Shadow
    private PlayerGUIOperationMessage.Operation op;

    @Inject(
            method = "processTarget(Lnet/minecraft/server/level/ServerPlayer;Ldev/ftb/mods/ftbteams/api/TeamRank;Ldev/ftb/mods/ftbteams/data/PartyTeam;Ljava/util/UUID;)V",
            at = @At("HEAD"),
            cancellable = true)
    private void ftbQuestsSync$onProcessTarget(ServerPlayer player, TeamRank senderRank,
                                               PartyTeam partyTeam, UUID targetId, CallbackInfo ci) {
        if (!Config.syncTeams) return;
        if (op != PlayerGUIOperationMessage.Operation.KICK
                && op != PlayerGUIOperationMessage.Operation.PROMOTE
                && op != PlayerGUIOperationMessage.Operation.DEMOTE
                && op != PlayerGUIOperationMessage.Operation.TRANSFER_OWNER
                && op != PlayerGUIOperationMessage.Operation.INVITE) {
            return;
        }

        if (op == PlayerGUIOperationMessage.Operation.INVITE) {
            if (!senderRank.isAtLeast(TeamRank.OWNER)) return;
            ServerPlayer targetPlayer = player.getServer().getPlayerList().getPlayer(targetId);
            if (targetPlayer != null) return;
            GameProfile profile = player.getServer().getProfileCache().get(targetId).orElse(null);
            if (profile == null) {
                String dbName = MySQLBackend.getInstance().selectPlayerNameByUuid(targetId).orElse(null);
                if (dbName != null && !dbName.isBlank()) {
                    profile = new GameProfile(targetId, dbName);
                }
            }
            if (profile == null) return;
            TeamSync.getInstance().queueInvite(player, partyTeam, profile);
            ci.cancel();
            return;
        }

        TeamRank targetRank = partyTeam.getRankForPlayer(targetId);
        if (targetRank == null || !targetRank.isMemberOrBetter()) {
            return; // Let native reject non-members
        }

        if (op == PlayerGUIOperationMessage.Operation.KICK) {
            if (!senderRank.isAtLeast(TeamRank.OWNER)) return;
            if (targetRank == TeamRank.OWNER) return;
            handleKick(player, partyTeam, targetId);
            ci.cancel();
        } else if (op == PlayerGUIOperationMessage.Operation.PROMOTE) {
            if (!senderRank.isAtLeast(TeamRank.OWNER)) return;
            if (targetRank != TeamRank.MEMBER) return;
            handlePromote(player, partyTeam, targetId);
            ci.cancel();
        } else if (op == PlayerGUIOperationMessage.Operation.DEMOTE) {
            if (!senderRank.isAtLeast(TeamRank.OWNER)) return;
            if (targetRank != TeamRank.OFFICER) return;
            handleDemote(player, partyTeam, targetId);
            ci.cancel();
        } else if (op == PlayerGUIOperationMessage.Operation.TRANSFER_OWNER) {
            if (!senderRank.isAtLeast(TeamRank.OWNER)) return;
            if (targetRank == TeamRank.OWNER) return;
            handleTransferOwner(player, partyTeam, targetId);
            ci.cancel();
        }
    }

    private void handleKick(ServerPlayer player, PartyTeam partyTeam, UUID targetId) {
        UUID partyTeamId = partyTeam.getId();
        TeamSync.getInstance().markExplicitKick(partyTeamId, targetId);
        MySQLBackend db = MySQLBackend.getInstance();
        db.upsertMembership(targetId, targetId, "OWNER");
        db.migratePartyMemberToSoloAsync(partyTeamId, targetId).whenComplete((result, err) -> {
            if (err != null) {
                FTBQuestsSync.LOGGER.error("GUI kick quest migration failed party={} player={}", partyTeamId, targetId, err);
                return;
            }
            if (result != null) {
                RedisSync.getInstance().publishTeamUpdate(targetId, result.revision, result.hashHex);
            }
        });
        TeamSync.getInstance().publishMemberKick(partyTeamId, targetId);
        db.loadTeamMaterializationAsync(targetId).whenComplete((row, err) -> {
            if (err != null) {
                FTBQuestsSync.LOGGER.warn("GUI kick local apply DB load failed player={}", targetId, err);
                return;
            }
            player.getServer().execute(() -> FtbSyncTeamCommand.applyMemberKickLocal(
                    player.getServer(), partyTeamId, targetId, row));
        });
    }

    private void handlePromote(ServerPlayer player, PartyTeam partyTeam, UUID targetId) {
        applyLocalRankChange(partyTeam, targetId, "OFFICER");
        TeamSync.getInstance().syncRankChange(player.getServer(), partyTeam.getId(), targetId, "OFFICER");
    }

    private void handleDemote(ServerPlayer player, PartyTeam partyTeam, UUID targetId) {
        applyLocalRankChange(partyTeam, targetId, "MEMBER");
        TeamSync.getInstance().syncRankChange(player.getServer(), partyTeam.getId(), targetId, "MEMBER");
    }

    private void handleTransferOwner(ServerPlayer player, PartyTeam partyTeam, UUID targetId) {
        MySQLBackend db = MySQLBackend.getInstance();
        TeamSync.getInstance().persistTeamNow(partyTeam);
        if (!db.updateTeamOwner(partyTeam.getId(), targetId)) {
            FTBQuestsSync.LOGGER.warn("GUI owner transfer DB update failed team={} owner={}",
                    partyTeam.getId(), targetId);
            return;
        }
        TeamSync.getInstance().publishOwnerTransfer(partyTeam.getId(), targetId);
        db.loadTeamStateAsync(partyTeam.getId()).whenComplete((state, err) -> {
            if (err != null) {
                FTBQuestsSync.LOGGER.warn("GUI owner transfer local apply DB load failed team={}",
                        partyTeam.getId(), err);
                return;
            }
            List<MySQLBackend.TeamMemberRow> members = state == null ? List.of() : state.members();
            player.getServer().execute(() -> FtbSyncTeamCommand.applyOwnerTransferLocal(
                    player.getServer(), player.createCommandSourceStack(),
                    partyTeam.getId(), TeamSync.profileFor(player.getServer(), targetId), members));
        });
    }

    private void applyLocalRankChange(PartyTeam partyTeam, UUID targetId, String rank) {
        TeamMaterializer.addPlayerToTeamReflective(partyTeam, targetId, rank);
        TeamMaterializer.markTeamDirtyAndSync(
                dev.ftb.mods.ftbteams.api.FTBTeamsAPI.api().getManager(), partyTeam);
    }
}
