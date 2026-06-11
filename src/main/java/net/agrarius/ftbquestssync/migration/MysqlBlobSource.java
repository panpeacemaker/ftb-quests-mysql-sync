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
 *   <dataTable>(<idPlayerColumn>, <idColumn>, <dataColumn> LONGBLOB, <createdAtColumn>, ...)
 *
 * For each player the row with the latest {@code created_at} is used,
 * breaking ties on the highest {@code id} so the selection is
 * deterministic when two snapshots share a timestamp (no
 * backup_type/server filter is applied). The actual quest export inside
 * the ZIP is identified by the {@code quests.snbt} entry (see
 * {@link ZipSnbtExtractor}).
 */
public final class MysqlBlobSource implements PlayerBlobSource {

    /**
     * Allow-list for SQL identifiers (table and column names) supplied via
     * configuration. The migrator concatenates them into the SELECT, so a
     * permissive value (whitespace, quotes, semicolons) would let a bad
     * config string alter the query. A bad value is rejected at construction
     * time so the operator sees the failure at startup, not on first use.
     */
    private static final java.util.regex.Pattern SQL_IDENT =
            java.util.regex.Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");

    private static String requireIdent(String value, String label) {
        if (value == null || !SQL_IDENT.matcher(value).matches()) {
            throw new IllegalArgumentException("Migration source MySQL " + label
                    + " is not a valid SQL identifier: " + value);
        }
        return value;
    }

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
    private final int maxBlobBytes;

    public MysqlBlobSource() {
        this(
                Config.migrationSourceMysqlHost, Config.migrationSourceMysqlPort,
                Config.migrationSourceMysqlDatabase, Config.migrationSourceMysqlUsername,
                Config.migrationSourceMysqlPassword,
                Config.migrationSourceMysqlPlayersTable, Config.migrationSourceMysqlDataTable,
                Config.migrationSourceMysqlUuidColumn, Config.migrationSourceMysqlIdColumn,
                Config.migrationSourceMysqlIdPlayerColumn, Config.migrationSourceMysqlDataColumn,
                Config.migrationSourceMysqlCreatedAtColumn,
                Config.migrationMaxBlobBytes);
    }

    public MysqlBlobSource(String host, int port, String db, String user, String password,
                           String playersTable, String dataTable,
                           String uuidColumn, String idColumn, String idPlayerColumn,
                           String dataColumn, String createdAtColumn,
                           int maxBlobBytes) {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("Migration source MySQL host is blank");
        }
        this.jdbcUrl = "jdbc:mysql://" + host + ":" + port + "/" + db
                + "?useUnicode=true&characterEncoding=utf8";
        this.user = user;
        this.password = password;
        this.playersTable = requireIdent(playersTable, "playersTable");
        this.dataTable = requireIdent(dataTable, "dataTable");
        this.uuidColumn = requireIdent(uuidColumn, "uuidColumn");
        this.idColumn = requireIdent(idColumn, "idColumn");
        this.idPlayerColumn = requireIdent(idPlayerColumn, "idPlayerColumn");
        this.dataColumn = requireIdent(dataColumn, "dataColumn");
        this.createdAtColumn = requireIdent(createdAtColumn, "createdAtColumn");
        this.maxBlobBytes = maxBlobBytes;
    }

    @Override
    public Map<UUID, byte[]> loadAll() throws Exception {
        Map<UUID, byte[]> out = new HashMap<>();
        forEach((player, blob) -> {
            out.putIfAbsent(player, blob);
            return true;
        });
        return out;
    }

    @Override
    public int forEach(BlobConsumer consumer) throws Exception {
        int skippedOversized = 0;
        String query = "SELECT p." + uuidColumn + " AS uuid, d." + dataColumn + " AS data "
                + "FROM " + playersTable + " p "
                + "JOIN " + dataTable + " d ON d." + idPlayerColumn + " = p." + idColumn + " "
                + "INNER JOIN ("
                + "  SELECT " + idPlayerColumn + ", MAX(" + idColumn + ") AS max_id FROM " + dataTable + " t "
                + "  JOIN ("
                + "    SELECT " + idPlayerColumn + " AS pid, MAX(" + createdAtColumn + ") AS max_created_at "
                + "    FROM " + dataTable + " GROUP BY " + idPlayerColumn
                + "  ) mc ON mc.pid = t." + idPlayerColumn + " AND mc.max_created_at = t." + createdAtColumn
                + "  GROUP BY " + idPlayerColumn
                + ") latest ON latest." + idPlayerColumn + " = d." + idPlayerColumn
                + "       AND latest.max_id = d." + idColumn;
        FTBQuestsSync.LOGGER.info("Migration source MariaDB query: {}", query);
        try (Connection conn = DriverManager.getConnection(jdbcUrl, user, password);
             PreparedStatement ps = conn.prepareStatement(query);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String raw = rs.getString("uuid");
                byte[] data = rs.getBytes("data");
                if (raw == null || data == null) continue;
                if (data.length > maxBlobBytes) {
                    skippedOversized++;
                    FTBQuestsSync.LOGGER.warn(
                            "Migration: skipping oversize MariaDB blob uuid={} bytes={} (cap={})",
                            raw, data.length, maxBlobBytes);
                    continue;
                }
                UUID uuid = parseUuid(raw);
                if (uuid == null) continue;
                if (!consumer.accept(uuid, data)) {
                    return skippedOversized;
                }
            }
        }
        return skippedOversized;
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
