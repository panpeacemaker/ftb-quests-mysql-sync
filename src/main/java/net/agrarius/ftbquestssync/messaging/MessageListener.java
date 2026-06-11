package net.agrarius.ftbquestssync.messaging;

/**
 * Feature-side callback for inbound Redis messages.
 *
 * <p>Implementations are registered with {@link MessageRouter} and must be
 * leaf-level: the messaging package must not depend on quests, chunks, or
 * teams.</p>
 */
@FunctionalInterface
public interface MessageListener {

    /**
     * Called for each message received on a subscribed channel.
     *
     * @param channel the Redis channel name
     * @param message the raw payload
     */
    void onMessage(String channel, String message);
}
