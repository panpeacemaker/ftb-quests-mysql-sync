package net.agrarius.ftbquestssync.messaging;

/**
 * Redis pub/sub channel name constants.
 *
 * <p>Channel names are duplicated by design: they are referenced from both the
 * transport layer (messaging) and from feature code that has not yet been moved
 * onto the shared bus (teams, presence). Keeping the constants here avoids
 * drift while the migration to {@link RedisBus} proceeds slice-by-slice.</p>
 */
public final class RedisChannels {

    public static final String QUEST_CHANNEL = "agrarius:quests:team-updated";
    public static final String CHUNK_CHANNEL = "agrarius:chunks:claims-updated";
    public static final String TEAM_CHANNEL = "ftbquests:team:membership";
    public static final String PRESENCE_CHANNEL = "ftbquests:presence";

    private RedisChannels() {
    }
}
