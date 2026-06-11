package net.agrarius.ftbquestssync.teams.model;

import java.util.UUID;

public record TeamInfoRow(String type, String name, UUID owner, boolean deleted, String color) {
}
