package net.agrarius.ftbquestssync.chunks;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RankBonusResolverTest {

    private static final Map<String, Integer> BONUSES = Map.of(
            "ev", 1,
            "premium", 1,
            "sponzor", 3,
            "donator", 5
    );

    @Test
    void parseConfig_readsQuotedListOfRankCountPairs() {
        Map<String, Integer> parsed = RankBonusResolver.parseConfig("[\"ev=1\", \"premium=1\", \"sponzor=3\", \"donator=5\"]");

        assertEquals(Map.of("ev", 1, "premium", 1, "sponzor", 3, "donator", 5), parsed);
    }

    @Test
    void parseConfig_keysAreLowerCased() {
        Map<String, Integer> parsed = RankBonusResolver.parseConfig("[\"Premium=2\", \"DONATOR=7\"]");

        assertEquals(Map.of("premium", 2, "donator", 7), parsed);
    }

    @Test
    void parseConfig_returnsEmptyForNullOrBlank() {
        assertTrue(RankBonusResolver.parseConfig(null).isEmpty());
        assertTrue(RankBonusResolver.parseConfig("").isEmpty());
        assertTrue(RankBonusResolver.parseConfig("   ").isEmpty());
        assertTrue(RankBonusResolver.parseConfig("[]").isEmpty());
    }

    @Test
    void resolve_sumsKnownRanksAndReportsResolvedValues() {
        RankBonusResolver.Result result = RankBonusResolver.resolve("premium,donator", BONUSES);

        assertEquals(0, result.unknown().size());
        assertEquals(6, result.total());
        assertEquals("premium=1", result.resolved().get(0));
        assertEquals("donator=5", result.resolved().get(1));
    }

    @Test
    void resolve_isCaseInsensitive() {
        RankBonusResolver.Result result = RankBonusResolver.resolve("Premium,SPONZOR", BONUSES);

        assertTrue(result.unknown().isEmpty());
        assertEquals(4, result.total());
    }

    @Test
    void resolve_noneYieldsZeroTotal() {
        RankBonusResolver.Result result = RankBonusResolver.resolve("none", BONUSES);

        assertTrue(result.unknown().isEmpty());
        assertTrue(result.resolved().isEmpty());
        assertEquals(0, result.total());
    }

    @Test
    void resolve_emptyInputYieldsZeroTotal() {
        RankBonusResolver.Result result = RankBonusResolver.resolve("", BONUSES);

        assertTrue(result.unknown().isEmpty());
        assertEquals(0, result.total());
    }

    @Test
    void resolve_unknownRanksAreCollectedAndTotalIsZero() {
        RankBonusResolver.Result result = RankBonusResolver.resolve("premium,UNKNOWN,donator,alsoBad", BONUSES);

        assertEquals(2, result.unknown().size());
        assertTrue(result.unknown().contains("UNKNOWN"));
        assertTrue(result.unknown().contains("alsoBad"));
        // No partial application: total ignores known ranks when any are unknown.
        assertEquals(0, result.total());
        assertTrue(result.resolved().isEmpty());
    }
}
