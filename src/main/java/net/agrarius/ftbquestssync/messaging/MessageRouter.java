package net.agrarius.ftbquestssync.messaging;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Fan-out router for inbound Redis messages.
 *
 * <p>Listeners register themselves by channel; the {@link RedisBus} subscriber
 * thread dispatches every incoming message to all listeners for that channel.</p>
 */
public class MessageRouter {

    private final ConcurrentHashMap<String, CopyOnWriteArrayList<MessageListener>> listeners = new ConcurrentHashMap<>();

    /**
     * Registers a listener for the given channel.
     *
     * @param channel  the Redis channel name
     * @param listener the callback to invoke for matching messages
     */
    public void register(String channel, MessageListener listener) {
        listeners.computeIfAbsent(channel, k -> new CopyOnWriteArrayList<>()).add(listener);
    }

    /**
     * Returns the channels that currently have at least one listener.
     *
     * @return array of channel names suitable for Jedis subscribe
     */
    public String[] getChannels() {
        return listeners.keySet().toArray(new String[0]);
    }

    /**
     * Dispatches a message to all listeners registered for the channel.
     *
     * @param channel the Redis channel name
     * @param message the raw payload
     */
    public void dispatch(String channel, String message) {
        List<MessageListener> channelListeners = listeners.get(channel);
        if (channelListeners == null) return;
        for (MessageListener listener : channelListeners) {
            listener.onMessage(channel, message);
        }
    }
}
