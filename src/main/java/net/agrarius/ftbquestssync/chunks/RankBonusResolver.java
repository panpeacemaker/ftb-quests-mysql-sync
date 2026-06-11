package net.agrarius.ftbquestssync.chunks;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Pure helper for resolving a comma-separated rank list into a total chunk bonus.
 *
 * <p>The config map keys are stored lower-case; rank matching is case-insensitive.
 */
public final class RankBonusResolver {

    private RankBonusResolver() {
    }

    /**
     * Result of resolving a rank list.
     *
     * @param total   summed bonus value for all known ranks
     * @param resolved known ranks as "name=value" strings, in input order
     * @param unknown  rank names that were not present in the config map
     */
    public record Result(int total, List<String> resolved, List<String> unknown) {
    }

    /**
     * Parses the TOML value for {@code rankBonuses}.
     *
     * <p>Expected format: {@code ["rank=count", ...]}, e.g.
     * {@code ["ev=1", "premium=1", "sponzor=3", "donator=5"]}. Invalid entries
     * are silently ignored so the mod remains usable when the config is edited
     * by hand.
     *
     * @param tomlValue raw TOML value, may be null
     * @return unmodifiable map from lower-case rank name to bonus count
     */
    public static Map<String, Integer> parseConfig(String tomlValue) {
        if (tomlValue == null || tomlValue.isBlank()) {
            return Map.of();
        }
        String value = tomlValue.trim();
        if (value.startsWith("[") && value.endsWith("]")) {
            value = value.substring(1, value.length() - 1);
        }
        if (value.isBlank()) {
            return Map.of();
        }

        Map<String, Integer> map = new HashMap<>();
        for (String part : value.split(",")) {
            String token = part.trim();
            if (token.isEmpty()) {
                continue;
            }
            if (token.length() >= 2 && token.startsWith("\"") && token.endsWith("\"")) {
                token = token.substring(1, token.length() - 1);
            }

            String[] kv = token.split("=", 2);
            if (kv.length != 2) {
                continue;
            }
            String rank = kv[0].trim();
            String count = kv[1].trim();
            if (rank.isEmpty()) {
                continue;
            }
            try {
                map.put(rank.toLowerCase(Locale.ROOT), Integer.parseInt(count));
            } catch (NumberFormatException ignored) {
            }
        }
        return Collections.unmodifiableMap(map);
    }

    /**
     * Resolves a comma-separated rank list against the configured rank bonuses.
     *
     * <p>The special value {@code "none"} (case-insensitive) or an empty/blank
     * list produces a total of {@code 0}. Unknown ranks are collected in
     * {@link Result#unknown()} so callers can fail closed without applying a
     * partial bonus.
     *
     * @param rankList comma-separated rank names, e.g. {@code "premium,donator"}
     * @param bonuses  map from lower-case rank name to bonus count
     * @return resolved result; apply only when {@code unknown()} is empty
     */
    public static Result resolve(String rankList, Map<String, Integer> bonuses) {
        if (rankList == null || rankList.isBlank() || "none".equalsIgnoreCase(rankList.trim())) {
            return new Result(0, List.of(), List.of());
        }

        List<String> resolved = new ArrayList<>();
        List<String> unknown = new ArrayList<>();
        int total = 0;

        for (String part : rankList.split(",")) {
            String rank = part.trim();
            if (rank.isEmpty()) {
                continue;
            }
            String key = rank.toLowerCase(Locale.ROOT);
            Integer bonus = bonuses.get(key);
            if (bonus == null) {
                unknown.add(rank);
            } else {
                total += bonus;
                resolved.add(key + "=" + bonus);
            }
        }
        if (!unknown.isEmpty()) {
            return new Result(0, List.of(), unknown);
        }
        return new Result(total, resolved, unknown);
    }
}
