package net.agrarius.ftbquestssync.teams.model;

import java.util.List;

public record TeamMaterializationRow(TeamMembershipRow membership, TeamInfoRow info, List<TeamMemberRow> members, List<TeamInviteRow> invites) {
}
