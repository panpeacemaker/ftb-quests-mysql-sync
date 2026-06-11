package net.agrarius.ftbquestssync.teams.model;

import java.util.UUID;

public record TeamInviteRow(UUID teamId, UUID invitedUuid, UUID inviterUuid) {
}
