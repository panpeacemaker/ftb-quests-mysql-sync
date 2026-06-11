package net.agrarius.ftbquestssync.migration;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.agrarius.ftbquestssync.FTBQuestsSync;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Resolves a legacy player display name to the player's CURRENT UUID using the
 * server's vanilla {@code usercache.json}.
 *
 * <p>Legacy quest exports are keyed by whatever UUID the player had when the
 * data was written. A server that switched authentication (offline-mode
 * name-based UUID -> online/premium UUID forwarded by a proxy) ends up with the
 * same player owning two UUIDs across the run's history; the live login path
 * uses the newest one. This remapper picks the entry with the latest
 * {@code expiresOn} per (lowercased) name so migration writes land under the
 * UUID the player actually logs in with.
 */
public final class UuidRemapper {

    private static final DateTimeFormatter EXPIRES_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss Z", Locale.ROOT);

    private final Map<String, UUID> nameToCurrentUuid;

    private UuidRemapper(Map<String, UUID> nameToCurrentUuid) {
        this.nameToCurrentUuid = nameToCurrentUuid;
    }

    public static UuidRemapper empty() {
        return new UuidRemapper(Map.of());
    }

    public int size() {
        return nameToCurrentUuid.size();
    }

    /**
     * Returns the current UUID for the given display name, or empty if the name
     * is unknown. Lookup is case-insensitive because Minecraft names are
     * case-insensitive for authentication.
     */
    public Optional<UUID> currentUuidFor(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(nameToCurrentUuid.get(name.toLowerCase(Locale.ROOT)));
    }

    public static UuidRemapper load(Path usercachePath) {
        if (usercachePath == null) {
            return empty();
        }
        if (!Files.isReadable(usercachePath)) {
            FTBQuestsSync.LOGGER.warn(
                    "UUID remap enabled but usercache not readable at {}; remap will be a no-op", usercachePath);
            return empty();
        }
        try {
            String json = Files.readString(usercachePath, StandardCharsets.UTF_8);
            JsonElement root = JsonParser.parseString(json);
            if (!root.isJsonArray()) {
                FTBQuestsSync.LOGGER.warn("usercache.json at {} is not a JSON array; remap will be a no-op", usercachePath);
                return empty();
            }
            JsonArray arr = root.getAsJsonArray();
            Map<String, UUID> latestByName = new HashMap<>();
            Map<String, Long> latestExpiryByName = new HashMap<>();
            for (JsonElement el : arr) {
                if (!el.isJsonObject()) {
                    continue;
                }
                JsonObject obj = el.getAsJsonObject();
                if (!obj.has("name") || !obj.has("uuid")) {
                    continue;
                }
                String name = obj.get("name").getAsString();
                if (name == null || name.isBlank()) {
                    continue;
                }
                UUID uuid;
                try {
                    uuid = UUID.fromString(obj.get("uuid").getAsString());
                } catch (IllegalArgumentException badUuid) {
                    continue;
                }
                long expiry = parseExpiry(obj);
                String key = name.toLowerCase(Locale.ROOT);
                Long prev = latestExpiryByName.get(key);
                if (prev == null || expiry >= prev) {
                    latestExpiryByName.put(key, expiry);
                    latestByName.put(key, uuid);
                }
            }
            FTBQuestsSync.LOGGER.info("UUID remap loaded {} name->uuid mapping(s) from {}", latestByName.size(), usercachePath);
            return new UuidRemapper(latestByName);
        } catch (Throwable ex) {
            FTBQuestsSync.LOGGER.warn("Failed to read usercache at {}; remap will be a no-op", usercachePath, ex);
            return empty();
        }
    }

    private static long parseExpiry(JsonObject obj) {
        if (!obj.has("expiresOn")) {
            return Long.MIN_VALUE;
        }
        try {
            String raw = obj.get("expiresOn").getAsString();
            return OffsetDateTime.parse(raw, EXPIRES_FORMAT).toInstant().toEpochMilli();
        } catch (Throwable ignored) {
            return Long.MIN_VALUE;
        }
    }
}
