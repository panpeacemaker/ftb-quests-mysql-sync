package net.agrarius.ftbquestssync.migration;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class MigrationRealDataTest {

    private static final UUID SYNTHETIC_UUID =
            UUID.nameUUIDFromBytes("OfflinePlayer:TestUser".getBytes(StandardCharsets.UTF_8));

    private static String syntheticQuestsSnbt() {
        String dashless = SYNTHETIC_UUID.toString().replace("-", "");
        return "{\n"
                + "\tuuid: \"" + dashless + "\"\n"
                + "\tname: \"TestUser#" + dashless + "\"\n"
                + "\tcompleted: {\n"
                + "\t\t6D16FFA71ED97139: 1L\n"
                + "\t}\n"
                + "\tclaimed_rewards: {\n"
                + "\t\t\"" + dashless + ":11CA9F1518999F28\": 1L\n"
                + "\t\t\"" + dashless + ":186506C6CE8506C4\": 2L\n"
                + "\t}\n"
                + "}\n";
    }

    private static byte[] syntheticPlayerZip() throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            zos.putNextEntry(new ZipEntry("quests.snbt"));
            zos.write(syntheticQuestsSnbt().getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        return bos.toByteArray();
    }

    @Test
    void zipExtractorReadsQuestsSnbtFromPlayerZip() throws Exception {
        byte[] zip = syntheticPlayerZip();
        byte[] snbt = ZipSnbtExtractor.extractQuestsSnbt(zip, 8 * 1024 * 1024);
        assertNotNull(snbt, "quests.snbt must be extracted from a per-player zip");
        assertTrue(snbt.length > 0);
        assertTrue(new String(snbt, StandardCharsets.UTF_8).startsWith("{"));
    }

    @Test
    void zipExtractorRejectsNonZip() throws Exception {
        assertNull(ZipSnbtExtractor.extractQuestsSnbt("not a zip".getBytes(StandardCharsets.UTF_8), 1024));
        assertNull(ZipSnbtExtractor.extractQuestsSnbt(new byte[]{1, 2, 3}, 1024));
        assertNull(ZipSnbtExtractor.extractQuestsSnbt(null, 1024));
    }

    @Test
    void redisKeyPrefixYieldsParseableUuidSuffix() {
        String prefix = "stratos:data:player:blob:";
        String uuid = SYNTHETIC_UUID.toString();
        String suffix = (prefix + uuid).substring(prefix.length());
        assertEquals(uuid, suffix);
        assertDoesNotThrow(() -> UUID.fromString(suffix));
    }

    @Test
    void shortRedisPrefixWouldBreakUuidParsing() {
        String badPrefix = "stratos:";
        String key = "stratos:data:player:blob:" + SYNTHETIC_UUID;
        String suffix = key.substring(badPrefix.length());
        assertEquals("data:player:blob:" + SYNTHETIC_UUID, suffix);
        assertThrows(IllegalArgumentException.class, () -> UUID.fromString(suffix));
    }
}
