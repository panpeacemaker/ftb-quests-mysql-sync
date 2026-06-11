package net.agrarius.ftbquestssync.teams.model;

import java.util.List;

public record TeamStateRow(TeamInfoRow info, List<TeamMemberRow> members, List<TeamInviteRow> invites) {
}
