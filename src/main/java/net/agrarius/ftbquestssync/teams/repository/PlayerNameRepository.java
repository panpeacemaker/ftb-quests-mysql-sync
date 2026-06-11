package net.agrarius.ftbquestssync.teams.repository;

import net.agrarius.ftbquestssync.FTBQuestsSync;
import net.agrarius.ftbquestssync.persistence.ConnectionProvider;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class PlayerNameRepository {

    private final ConnectionProvider connectionProvider;

    public PlayerNameRepository(ConnectionProvider connectionProvider) {
        this.connectionProvider = connectionProvider;
    }

    private static final String SQL_UPSERT_PLAYER_NAME =
            "INSERT INTO ftbquests_player_names (player_uuid, player_name) "
            + "VALUES (?, ?) ON DUPLICATE KEY UPDATE player_name=VALUES(player_name)";

    private static final String SQL_SELECT_PLAYER_UUID_BY_NAME =
            "SELECT player_uuid FROM ftbquests_player_names "
            + "WHERE LOWER(player_name) = LOWER(?) ORDER BY updated_at DESC LIMIT 1";

    private static final String SQL_SELECT_PLAYER_NAME_BY_UUID =
            "SELECT player_name FROM ftbquests_player_names WHERE player_uuid = ?";

    public void upsertPlayerName(UUID playerUuid, String playerName) {
        if (playerName == null || playerName.isBlank()) return;
        try (Connection conn = connectionProvider.getConnection();
             PreparedStatement ps = conn.prepareStatement(SQL_UPSERT_PLAYER_NAME)) {
            ps.setString(1, playerUuid.toString());
            ps.setString(2, playerName);
            ps.executeUpdate();
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.error("upsertPlayerName failed player={} name={}", playerUuid, playerName, e);
        }
    }

    public Optional<UUID> selectUuidByName(String playerName) {
        if (playerName == null || playerName.isBlank()) return Optional.empty();
        try (Connection conn = connectionProvider.getConnection();
             PreparedStatement ps = conn.prepareStatement(SQL_SELECT_PLAYER_UUID_BY_NAME)) {
            ps.setString(1, playerName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(UUID.fromString(rs.getString(1))) : Optional.empty();
            }
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.error("selectPlayerUuidByName failed name={}", playerName, e);
            return Optional.empty();
        }
    }

    public Optional<String> selectNameByUuid(UUID playerUuid) {
        try (Connection conn = connectionProvider.getConnection();
             PreparedStatement ps = conn.prepareStatement(SQL_SELECT_PLAYER_NAME_BY_UUID)) {
            ps.setString(1, playerUuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.ofNullable(rs.getString(1)) : Optional.empty();
            }
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.error("selectPlayerNameByUuid failed for player={}", playerUuid, e);
            return Optional.empty();
        }
    }

    public Map<UUID, String> selectNames(Collection<UUID> playerUuids) {
        Map<UUID, String> result = new HashMap<>();
        if (playerUuids == null || playerUuids.isEmpty()) return result;
        List<UUID> uuids = new ArrayList<>();
        for (UUID uuid : playerUuids) {
            if (uuid != null) uuids.add(uuid);
        }
        if (uuids.isEmpty()) return result;

        StringBuilder sql = new StringBuilder(
                "SELECT player_uuid, player_name FROM ftbquests_player_names WHERE player_uuid IN (");
        for (int i = 0; i < uuids.size(); i++) {
            if (i > 0) sql.append(',');
            sql.append('?');
        }
        sql.append(')');

        try (Connection conn = connectionProvider.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < uuids.size(); i++) {
                ps.setString(i + 1, uuids.get(i).toString());
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    try {
                        UUID uuid = UUID.fromString(rs.getString(1));
                        String name = rs.getString(2);
                        if (name != null && !name.isBlank()) {
                            result.put(uuid, name);
                        }
                    } catch (IllegalArgumentException ignored) {
                    }
                }
            }
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.error("selectPlayerNames failed for {} uuid(s)", uuids.size(), e);
        }
        return result;
    }

    public List<UUID> selectMissingNameUuids() {
        String sql = "SELECT m.player_uuid FROM ftbquests_team_membership m "
                + "LEFT JOIN ftbquests_player_names n ON n.player_uuid = m.player_uuid "
                + "WHERE n.player_uuid IS NULL OR n.player_name IS NULL OR n.player_name = ''";
        List<UUID> out = new ArrayList<>();
        try (Connection conn = connectionProvider.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                try {
                    out.add(UUID.fromString(rs.getString(1)));
                } catch (IllegalArgumentException ignored) {
                }
            }
        } catch (Exception e) {
            FTBQuestsSync.LOGGER.error("selectMembershipUuidsMissingName failed", e);
        }
        return out;
    }
}
