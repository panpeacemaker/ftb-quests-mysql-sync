package net.agrarius.ftbquestssync.migration;

import net.agrarius.ftbquestssync.Config;
import net.agrarius.ftbquestssync.FTBQuestsSync;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Reads legacy player blobs from a source MariaDB holding per-player ZIP
 * exports. Schema (default):
 *
 *   <playersTable>(<idColumn>, <uuidColumn>, username, ...)
 *   <dataTable>(<idPlayerColumn>, <idColumn>, <dataColumn> LONGBLOB,
 *               <createdAtColumn>, backup_type, server)
 *
 * For each player the row with the latest {@code created_at} is used. The
 * actual quest export inside the ZIP is identified by the {@code quests.snbt}
 * entry (see {@link ZipSnbtExtractor}).
 */
public final class MysqlBlobSource implements PlayerBlobSource {

    private final String jdbcUrl;
    private final String user;
    private final String password;
    private final String playersTable;
    private final String dataTable;
    private final String uuidColumn;
    private final String idColumn;
    private final String idPlayerColumn;
    private final String dataColumn;
    private final String createdAtColumn;

    public MysqlBlobSource() {
        this(
                Config.migrationSourceMysqlHost, Config.migrationSourceMysqlPort,
                Config.migrationSourceMysqlDatabase, Config.migrationSourceMysqlUsername,
                Config.migrationSourceMysqlPassword,
                Config.migrationSourceMysqlPlayersTable, Config.migrationSourceMysqlDataTable,
                Config.migrationSourceMysqlUuidColumn, Config.migrationSourceMysqlIdColumn,
                Config.migrationSourceMysqlIdPlayerColumn, Config.migrationSourceMysqlDataColumn,
                Config.migrationSourceMysqlCreatedAtColumn);
    }

    public MysqlBlobSource(String host, int port, String db, String user, String password,
                           String playersTable, String dataTable,
                           String uuidColumn, String idColumn, String idPlayerColumn,
                           String dataColumn, String createdAtColumn) {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("Migration source MySQL host is blank");
        }
        this.jdbcUrl = "jdbc:mysql://" + host + ":" + port + "/" + db
                + "?useUnicode=true&characterEncoding=utf8";
        this.user = user;
        this.password = password;
        this.playersTable = playersTable;
        this.dataTable = dataTable;
        this.uuidColumn = uuidColumn;
        this.idColumn = idColumn;
        this.idPlayerColumn = idPlayerColumn;
        this.dataColumn = dataColumn;
        this.createdAtColumn = createdAtColumn;
    }

    @Override
    public Map<UUID, byte[]> loadAll() throws SQLException {
        Map<UUID, byte[]> out = new HashMap<>();
        String query = "SELECT p." + uuidColumn + " AS uuid, d." + dataColumn + " AS data "
                + "FROM " + playersTable + " p "
                + "JOIN " + dataTable + " d ON d." + idPlayerColumn + " = p." + idColumn + " "
                + "INNER JOIN ("
                + "  SELECT " + idPlayerColumn + ", MAX(" + createdAtColumn + ") AS max_created_at "
                + "  FROM " + dataTable + " GROUP BY " + idPlayerColumn
                + ") latest ON latest." + idPlayerColumn + " = d." + idPlayerColumn
                + "       AND latest.max_created_at = d." + createdAtColumn;
        FTBQuestsSync.LOGGER.info("Migration source MariaDB query: {}", query);
        try (Connection conn = DriverManager.getConnection(jdbcUrl, user, password);
             PreparedStatement ps = conn.prepareStatement(query);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String raw = rs.getString("uuid");
                byte[] data = rs.getBytes("data");
                if (raw == null || data == null) continue;
                UUID uuid = parseUuid(raw);
                if (uuid == null) continue;
                out.putIfAbsent(uuid, data);
            }
        }
        return out;
    }

    @Override
    public String describe() {
        int portIdx = jdbcUrl.indexOf(':', "jdbc:mysql://".length());
        String hostPart = portIdx >= 0 ? jdbcUrl.substring("jdbc:mysql://".length(), portIdx)
                : "unknown";
        return "mysql://" + user + "@" + hostPart;
    }

    private static UUID parseUuid(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (t.isEmpty()) return null;
        try {
            return t.contains("-") ? UUID.fromString(t) : UUID.fromString(
                    t.substring(0, 8) + "-" + t.substring(8, 12) + "-" + t.substring(12, 16) + "-"
                            + t.substring(16, 20) + "-" + t.substring(20));
        } catch (IndexOutOfBoundsException | IllegalArgumentException e) {
            return null;
        }
    }
}
