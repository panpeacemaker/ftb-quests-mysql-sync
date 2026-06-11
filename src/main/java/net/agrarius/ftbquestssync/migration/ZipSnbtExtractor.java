package net.agrarius.ftbquestssync.migration;

import net.agrarius.ftbquestssync.config.Config;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Extracts the {@code quests.snbt} entry from a legacy per-player zip blob.
 *
 * The legacy export stores the FTB Quests progress as a single SNBT text
 * entry inside a zip alongside other per-player files (player data, stats,
 * etc.). Only the SNBT text is needed; the rest is discarded.
 *
 * Per-entry decompressed size is bounded by
 * {@code Config.migrationMaxSnbtBytes} so a malformed legacy export cannot
 * decompress into hundreds of MB and exhaust heap.
 */
public final class ZipSnbtExtractor {

    private static final String ENTRY_NAME = "quests.snbt";
    private static final int COPY_BUFFER_BYTES = 8192;

    private ZipSnbtExtractor() {
    }

    public static byte[] extractQuestsSnbt(byte[] zipBytes) throws IOException {
        return extractQuestsSnbt(zipBytes, Config.migrationMaxSnbtBytes);
    }

    /**
     * @return SNBT text bytes (UTF-8), or null if the zip has no {@code quests.snbt}.
     */
    public static byte[] extractQuestsSnbt(byte[] zipBytes, int maxSnbtBytes) throws IOException {
        if (zipBytes == null || zipBytes.length < 4) return null;
        if (zipBytes[0] != 'P' || zipBytes[1] != 'K') return null;
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                if (!ENTRY_NAME.equals(entry.getName())) {
                    continue;
                }
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[COPY_BUFFER_BYTES];
                int total = 0;
                int n;
                while ((n = zin.read(buf)) > 0) {
                    total += n;
                    if (total > maxSnbtBytes) {
                        throw new IOException(
                                "SNBT entry exceeds maxSnbtBytes=" + maxSnbtBytes + " (decompressed)");
                    }
                    out.write(buf, 0, n);
                }
                return out.toByteArray();
            }
        }
        return null;
    }
}
