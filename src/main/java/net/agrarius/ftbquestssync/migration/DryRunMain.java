package net.agrarius.ftbquestssync.migration;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Standalone dry-run utility. Reads every {@code .zip} from a directory tree
 * (or a single file), extracts the {@code quests.snbt} entry, gzip-compresses
 * it the same way the mod's NbtIo.writeCompressed path would, and reports
 * the SHA-256 + UUID field. Does NOT write to MySQL.
 *
 * Usage:
 *   java -cp ftb-quests-mysql-sync-1.2.0.jar net.agrarius.ftbquestssync.migration.DryRunMain <dir|file>
 */
public final class DryRunMain {

    private static final Pattern UUID_FIELD = Pattern.compile("\"?uuid\"?\\s*:\\s*\"([0-9a-fA-F]{32})\"");
    private static final Pattern NAME_FIELD = Pattern.compile("\"?name\"?\\s*:\\s*\"([^\"]*)\"");

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: DryRunMain <dir-or-zip>");
            System.exit(2);
        }
        Path target = Path.of(args[0]);
        if (!Files.exists(target)) {
            System.err.println("Not found: " + target);
            System.exit(2);
        }

        int total = 0, okSnbt = 0, missingEntry = 0, badZip = 0, missingUuid = 0;
        long totalSnbtBytes = 0, totalGzipBytes = 0;

        if (Files.isRegularFile(target)) {
            DryRunReport r = process(target);
            System.out.println(r);
            return;
        }

        java.util.List<Path> zips = new java.util.ArrayList<>();
        try (var stream = Files.walk(target)) {
            stream.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip"))
                    .sorted().forEach(zips::add);
        }
        System.out.println("Found " + zips.size() + " zip(s) under " + target);
        for (Path p : zips) {
            total++;
            try {
                DryRunReport r = process(p);
                if (r.snbtBytes == 0) {
                    missingEntry++;
                    System.out.println("  [no-quests.snbt] " + p.getFileName());
                } else if (r.uuid == null) {
                    missingUuid++;
                    System.out.println("  [no-uuid-field ] " + p.getFileName() + "  (snbt=" + r.snbtBytes + ")");
                } else {
                    okSnbt++;
                    totalSnbtBytes += r.snbtBytes;
                    totalGzipBytes += r.gzipBytes;
                    System.out.printf("  [OK %s]  %s  snbt=%dB  gzip=%dB  hash=%s%n",
                            r.uuid, p.getFileName(), r.snbtBytes, r.gzipBytes, r.hashHex.substring(0, 16));
                }
            } catch (Exception e) {
                badZip++;
                System.out.println("  [BAD ] " + p.getFileName() + " : " + e);
            }
        }
        System.out.println();
        System.out.println("Summary:");
        System.out.println("  total zips          : " + total);
        System.out.println("  ok with snbt+uuid   : " + okSnbt);
        System.out.println("  no quests.snbt entry: " + missingEntry);
        System.out.println("  no uuid field       : " + missingUuid);
        System.out.println("  bad zip/parse       : " + badZip);
        System.out.println("  total SNBT bytes    : " + totalSnbtBytes);
        System.out.println("  total gzip bytes    : " + totalGzipBytes);
    }

    private static DryRunReport process(Path zipPath) throws IOException {
        DryRunReport r = new DryRunReport();
        r.zipName = zipPath.getFileName().toString();
        try (var in = Files.newInputStream(zipPath); var zin = new ZipInputStream(in)) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                if (!"quests.snbt".equals(entry.getName())) continue;
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = zin.read(buf)) > 0) out.write(buf, 0, n);
                r.snbtBytes = out.size();
                r.snbtText = out.toString(StandardCharsets.UTF_8);
                r.gzipBytes = gzip(r.snbtText.getBytes(StandardCharsets.UTF_8), r);
                Matcher m = UUID_FIELD.matcher(r.snbtText);
                if (m.find()) {
                    String u = m.group(1);
                    try {
                        r.uuid = UUID.fromString(u.substring(0, 8) + "-" + u.substring(8, 12) + "-"
                                + u.substring(12, 16) + "-" + u.substring(16, 20) + "-" + u.substring(20));
                    } catch (Exception ignored) { }
                }
                Matcher nm = NAME_FIELD.matcher(r.snbtText);
                if (nm.find()) r.name = nm.group(1);
                break;
            }
        }
        return r;
    }

    private static int gzip(byte[] in, DryRunReport r) throws IOException {
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

    private static final class DryRunReport {
        String zipName;
        String name;
        UUID uuid;
        int snbtBytes;
        int gzipBytes;
        String hashHex = "";
        String snbtText = "";

        @Override
        public String toString() {
            return String.format("%s  name=%s  uuid=%s  snbt=%dB  gzip=%dB  hash=%s",
                    zipName, name, uuid, snbtBytes, gzipBytes,
                    hashHex.isEmpty() ? "?" : hashHex.substring(0, Math.min(16, hashHex.length())));
        }
    }

    private DryRunMain() { }
}
