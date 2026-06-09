package net.agrarius.ftbquestssync.migration;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Standalone dry-run that connects to a SOURCE MariaDB and exercises
 * the migration code path without writing to the TARGET MySQL or
 * requiring a Forge runtime. Reads per-player blobs the same way
 * {@link MysqlBlobSource} does, extracts and parses the
 * {@code quests.snbt} entry, and prints a per-player report.
 *
 * Two modes:
 *
 *   --src file &lt;dir-or-zip&gt;   (default if --src omitted)
 *       Walks every .zip under the path; same as the original
 *       DryRunMain. UUID is inferred from the SNBT file name or
 *       the SNBT uuid field.
 *
 *   --src maria --db-host HOST --db-port PORT --db-name DB \
 *              --db-user USER --db-pass PASS \
 *              --players-table core_players --data-table core_player_data \
 *              [--limit N] [--dry-run]
 *       Runs the same MysqlBlobSource query the live migrator uses.
 *       For each player: prints uuid, blob bytes, SNBT bytes, parsed
 *       uuid / teamId / partyName, gzip+SHA256 of the canonical NBT.
 *       With --dry-run, also goes through the SNBT->NBT path and prints
 *       the result; without, skips NBT encoding to keep the test fast.
 */
public final class MigrateDryRunMain {

    private static final Pattern UUID_FIELD = Pattern.compile("\"?uuid\"?\\s*:\\s*\"([0-9a-fA-F]{32})\"");
    private static final Pattern NAME_FIELD = Pattern.compile("\"?name\"?\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern SQL_IDENT = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");

    private static String requireIdent(String value, String label) {
        if (value == null || !SQL_IDENT.matcher(value).matches()) {
            throw new IllegalArgumentException("Migration dry-run SQL " + label
                    + " is not a valid SQL identifier: " + value);
        }
        return value;
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> opts = parseArgs(args);
        String src = opts.getOrDefault("src", "file");
        if (src.equals("maria")) {
            runMaria(opts);
        } else {
            String target = opts.get("target");
            if (target == null) {
                System.err.println("Usage: MigrateDryRunMain --src maria [db options] | --src file <dir-or-zip>");
                System.exit(2);
            }
            runFile(new File(target));
        }
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (!a.startsWith("--")) continue;
            String key = a.substring(2);
            String val = (i + 1 < args.length && !args[i + 1].startsWith("--")) ? args[++i] : "true";
            out.put(key, val);
        }
        return out;
    }

    private static void runFile(File root) throws Exception {
        if (!root.exists()) {
            System.err.println("Not found: " + root);
            System.exit(2);
        }
        if (root.isFile()) {
            processZip(root.toPath(), "n/a", "<file>");
            return;
        }
        List<Path> zips = new ArrayList<>();
        try (var stream = Files.walk(root.toPath())) {
            stream.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip"))
                    .sorted().forEach(zips::add);
        }
        int ok = 0, bad = 0, noSnbt = 0, noUuid = 0;
        for (Path p : zips) {
            Report r = processZip(p, "n/a", p.getFileName().toString());
            if (r.snbtBytes == 0) noSnbt++;
            else if (r.uuid == null) noUuid++;
            else ok++;
            if (r.parseError != null) bad++;
        }
        System.out.println("file mode done: total=" + zips.size() + " ok=" + ok + " noSnbt=" + noSnbt + " noUuid=" + noUuid + " bad=" + bad);
    }

    private static void runMaria(Map<String, String> opts) throws Exception {
        String host = opts.getOrDefault("db-host", "127.0.0.1");
        int port = Integer.parseInt(opts.getOrDefault("db-port", "3306"));
        String db = opts.getOrDefault("db-name", "CHANGEME");
        String user = opts.getOrDefault("db-user", "root");
        String pass = opts.getOrDefault("db-pass", "");
        String playersTable = requireIdent(opts.getOrDefault("players-table", "core_players"), "players-table");
        String dataTable = requireIdent(opts.getOrDefault("data-table", "core_player_data"), "data-table");
        String uuidCol = requireIdent(opts.getOrDefault("uuid-column", "uuid"), "uuid-column");
        String idCol = requireIdent(opts.getOrDefault("id-column", "id"), "id-column");
        String idPlayerCol = requireIdent(opts.getOrDefault("id-player-column", "id_player"), "id-player-column");
        String dataCol = requireIdent(opts.getOrDefault("data-column", "data"), "data-column");
        String createdAtCol = requireIdent(opts.getOrDefault("created-at-column", "created_at"), "created-at-column");
        int limit = Integer.parseInt(opts.getOrDefault("limit", "0"));

        String jdbcUrl = "jdbc:mysql://" + host + ":" + port + "/" + db + "?useUnicode=true&characterEncoding=utf8";
        String query = "SELECT p." + uuidCol + " AS uuid, d." + dataCol + " AS data "
                + "FROM " + playersTable + " p "
                + "JOIN " + dataTable + " d ON d." + idPlayerCol + " = p." + idCol + " "
                + "INNER JOIN ("
                + "  SELECT " + idPlayerCol + ", MAX(" + createdAtCol + ") AS max_created_at "
                + "  FROM " + dataTable + " GROUP BY " + idPlayerCol
                + ") latest ON latest." + idPlayerCol + " = d." + idPlayerCol
                + "       AND latest.max_created_at = d." + createdAtCol
                + (limit > 0 ? " LIMIT " + limit : "");

        System.out.println("maria mode: jdbc=" + jdbcUrl);
        System.out.println("maria mode: query=" + query);
        int ok = 0, noSnbt = 0, noUuid = 0, bad = 0;
        try (Connection conn = DriverManager.getConnection(jdbcUrl, user, pass);
             PreparedStatement ps = conn.prepareStatement(query);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String rawUuid = rs.getString("uuid");
                byte[] blob = rs.getBytes("data");
                if (rawUuid == null || blob == null) continue;
                UUID uuid;
                try {
                    uuid = parseUuid(rawUuid);
                } catch (Exception e) {
                    System.out.println("  [BAD uuid] " + rawUuid);
                    bad++;
                    continue;
                }
                Report r = processBlob(uuid, blob);
                if (r.snbtBytes == 0) noSnbt++;
                else if (r.uuid == null) noUuid++;
                else ok++;
                if (r.parseError != null) bad++;
            }
        }
        System.out.println("maria mode done: ok=" + ok + " noSnbt=" + noSnbt + " noUuid=" + noUuid + " bad=" + bad);
    }

    private static Report processZip(Path zip, String playerUuid, String label) throws Exception {
        byte[] data = Files.readAllBytes(zip);
        Report r = processBytes(playerUuid, data);
        r.label = label;
        System.out.println(r);
        return r;
    }

    private static Report processBlob(UUID playerUuid, byte[] data) throws Exception {
        Report r = processBytes(playerUuid.toString(), data);
        System.out.println(r);
        return r;
    }

    private static Report processBytes(String playerUuid, byte[] data) throws Exception {
        Report r = new Report();
        r.label = playerUuid;
        r.blobBytes = data == null ? 0 : data.length;
        if (data == null) return r;
        byte[] snbt = ZipSnbtExtractor.extractQuestsSnbt(data);
        r.snbtBytes = snbt == null ? 0 : snbt.length;
        if (snbt == null || snbt.length == 0) return r;
        String text = new String(snbt, StandardCharsets.UTF_8);
        Matcher m = UUID_FIELD.matcher(text);
        if (m.find()) {
            String u = m.group(1);
            try {
                r.uuid = parseUuid(u);
            } catch (Exception ignored) { }
        }
        Matcher nm = NAME_FIELD.matcher(text);
        if (nm.find()) {
            r.name = nm.group(1);
            int hashIdx = r.name.indexOf('#');
            if (hashIdx >= 0) {
                String suffix = r.name.substring(hashIdx + 1);
                try {
                    r.teamId = parseUuid(suffix.replace("-", ""));
                    r.isParty = !r.teamId.equals(r.uuid);
                    r.partyName = r.name.substring(0, hashIdx);
                } catch (Exception ignored) { }
            }
        }
        if (r.uuid != null) {
            r.gzipBytes = gzip(snbt, r);
        }
        return r;
    }

    private static int gzip(byte[] in, Report r) throws IOException {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (var gz = new java.util.zip.GZIPOutputStream(raw)) {
            gz.write(in);
        }
        byte[] out = raw.toByteArray();
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(out);
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            r.hashHex = sb.toString();
        } catch (Exception e) {
            r.hashHex = "<no-sha256>";
        }
        return out.length;
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

    private static final class Report {
        String label;
        int blobBytes;
        int snbtBytes;
        int gzipBytes;
        UUID uuid;
        UUID teamId;
        boolean isParty;
        String name;
        String partyName;
        String hashHex = "";
        String parseError;

        @Override
        public String toString() {
            return String.format(
                    "player=%s uuid=%s teamId=%s party=%s partyName=%s name=%s snbt=%dB gzip=%dB hash=%s",
                    label, uuid, teamId, isParty, partyName, name, snbtBytes, gzipBytes,
                    hashHex.isEmpty() ? "?" : hashHex.substring(0, Math.min(16, hashHex.length())));
        }
    }

    private MigrateDryRunMain() { }
}
