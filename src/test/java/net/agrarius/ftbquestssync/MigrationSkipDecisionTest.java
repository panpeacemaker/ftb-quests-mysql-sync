package net.agrarius.ftbquestssync;

import org.junit.jupiter.api.Test;

import java.util.HexFormat;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class MigrationSkipDecisionTest {

    private static final UUID TEAM = UUID.randomUUID();
    private static final byte[] HASH_A = HexFormat.of().parseHex("deadbeef");
    private static final byte[] HASH_B = HexFormat.of().parseHex("cafebabe");
    private static final String HEX_A = "deadbeef";
    private static final String HEX_B = "cafebabe";

    @Test
    void noExistingRowNeverSkipped() {
        MySQLBackend.SaveResult existing = new MySQLBackend.SaveResult(TEAM, 0L, HEX_A);
        assertFalse(MySQLBackend.shouldSkipMigrationWrite(existing, HASH_A, false));
        assertFalse(MySQLBackend.shouldSkipMigrationWrite(existing, HASH_A, true));
    }

    @Test
    void sameHashAlwaysSkipped() {
        MySQLBackend.SaveResult existing = new MySQLBackend.SaveResult(TEAM, 5L, HEX_A);
        assertTrue(MySQLBackend.shouldSkipMigrationWrite(existing, HASH_A, false));
        assertTrue(MySQLBackend.shouldSkipMigrationWrite(existing, HASH_A, true));
    }

    @Test
    void differentHashSkippedWhenOverwriteFalse() {
        MySQLBackend.SaveResult existing = new MySQLBackend.SaveResult(TEAM, 5L, HEX_A);
        assertTrue(MySQLBackend.shouldSkipMigrationWrite(existing, HASH_B, false));
    }

    @Test
    void differentHashNotSkippedWhenOverwriteTrue() {
        MySQLBackend.SaveResult existing = new MySQLBackend.SaveResult(TEAM, 5L, HEX_A);
        assertFalse(MySQLBackend.shouldSkipMigrationWrite(existing, HASH_B, true));
    }

    @Test
    void nullExistingNeverSkipped() {
        assertFalse(MySQLBackend.shouldSkipMigrationWrite(null, HASH_A, false));
    }
}
