package net.agrarius.ftbquestssync;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RedisEventParserTest {

    private static final UUID EVENT_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
    private static final UUID TEAM_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440001");
    private static final String SOURCE_SERVER = "server1";

    @Test
    void questEvent_roundTrip() {
        QuestTeamUpdateEvent original = new QuestTeamUpdateEvent(
                EVENT_ID,
                SOURCE_SERVER,
                "quest_team",
                TEAM_ID,
                42L,
                "abc123",
                "saveIfChanged",
                false
        );
        String json = RedisEventParser.GSON.toJson(original);
        QuestTeamUpdateEvent parsed = RedisEventParser.parseQuestEvent(json);

        assertNotNull(parsed);
        assertEquals(EVENT_ID, parsed.eventId());
        assertEquals(SOURCE_SERVER, parsed.sourceServer());
        assertEquals("quest_team", parsed.entityType());
        assertEquals(TEAM_ID, parsed.teamId());
        assertEquals(42L, parsed.revision());
        assertEquals("abc123", parsed.hashHex());
        assertEquals("saveIfChanged", parsed.reason());
        assertFalse(parsed.forceReplace());
    }

    @Test
    void questEvent_parseLegacyConcatJson() {
        // Captured sample matching the exact hand-rolled format from publishTeamUpdate
        String legacyJson = "{"
                + "\"eventId\":\"" + EVENT_ID + "\","
                + "\"sourceServer\":\"" + SOURCE_SERVER + "\","
                + "\"entityType\":\"quest_team\","
                + "\"entityId\":\"" + TEAM_ID + "\","
                + "\"revision\":42,"
                + "\"hash\":\"abc123\","
                + "\"reason\":\"saveIfChanged\""
                + "}";

        QuestTeamUpdateEvent parsed = RedisEventParser.parseQuestEvent(legacyJson);
        assertNotNull(parsed);
        assertEquals(EVENT_ID, parsed.eventId());
        assertEquals(SOURCE_SERVER, parsed.sourceServer());
        assertEquals(TEAM_ID, parsed.teamId());
        assertEquals(42L, parsed.revision());
        assertEquals("abc123", parsed.hashHex());
        assertFalse(parsed.forceReplace());
    }

    @Test
    void chunkEvent_parseLegacyPipeDelimited() {
        String payload = SOURCE_SERVER + "|chunks_claimed|" + TEAM_ID + "|-";

        assertTrue(RedisEventParser.isLegacyChunkPayload(payload));
        ChunkClaimsUpdateEvent parsed = RedisEventParser.parseLegacyChunkEvent(payload);

        assertNotNull(parsed);
        assertEquals(SOURCE_SERVER, parsed.serverId());
        assertEquals("chunks_claimed", parsed.reason());
        assertEquals(TEAM_ID, parsed.teamId());
    }

    @Test
    void chunkEvent_parseNewJson() {
        ChunkClaimsUpdateEvent original = new ChunkClaimsUpdateEvent(
                SOURCE_SERVER,
                "chunks_claimed",
                TEAM_ID
        );
        String json = RedisEventParser.GSON.toJson(original);
        ChunkClaimsUpdateEvent parsed = RedisEventParser.parseChunkEvent(json);

        assertNotNull(parsed);
        assertEquals(SOURCE_SERVER, parsed.serverId());
        assertEquals("chunks_claimed", parsed.reason());
        assertEquals(TEAM_ID, parsed.teamId());
    }

    @Test
    void dedupDeterminism_forIdenticalLegacyPayloads() {
        String legacyPayload = SOURCE_SERVER + ":" + TEAM_ID;

        QuestTeamUpdateEvent first = RedisEventParser.parseLegacyQuestEvent(legacyPayload);
        QuestTeamUpdateEvent second = RedisEventParser.parseLegacyQuestEvent(legacyPayload);

        assertNotNull(first);
        assertNotNull(second);
        assertEquals(first.eventId(), second.eventId(),
                "Identical legacy payloads must produce the same deterministic eventId");
        assertEquals(SOURCE_SERVER, first.sourceServer());
        assertEquals(TEAM_ID, first.teamId());
        assertEquals(-1L, first.revision());
        assertFalse(first.forceReplace());
    }

    @Test
    void deterministicEventId_isActuallyDeterministic() {
        String payload = "server-east-1:chunks_snapshot:" + TEAM_ID;
        UUID id1 = RedisEventParser.deterministicEventId(payload);
        UUID id2 = RedisEventParser.deterministicEventId(payload);
        assertEquals(id1, id2);
    }

    @Test
    void isLegacyChunkPayload_returnsFalseForJson() {
        assertFalse(RedisEventParser.isLegacyChunkPayload("{\"foo\":\"bar\"}"));
    }

    @Test
    void isLegacyChunkPayload_returnsFalseForShortString() {
        assertFalse(RedisEventParser.isLegacyChunkPayload("hello"));
    }
}
