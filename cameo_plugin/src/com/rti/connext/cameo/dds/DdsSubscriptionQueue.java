/*
 * DdsSubscriptionQueue.java — thread hand-off point between DDSTopicSubscriber
 * (STEP 2 of the inbound pipeline) and the Groovy polling script (STEP 3).
 *
 * push() runs on RTI's own native callback thread (DataReaderListener.
 * on_data_available fires there, not on CAMEO's UI/simulation thread).
 * pollNext() runs on the simulation's own execution thread, inside a Groovy
 * Opaque Behavior polling on a Time Event. Deliberately no internal fUML
 * types anywhere in this file — mirrors DDSTopicPublisher/DdsXmlGenerator's
 * discipline of keeping the DDS-facing plumbing plain-typed.
 *
 * One queue per (subscriber Block, topic) — see key(). Each modeled Block
 * that subscribes to a topic has its own DataReader feeding its own queue,
 * so two Blocks receiving the same topic each get exactly one copy of every
 * sample, just as two separate DDS applications would.
 */
package com.rti.connext.cameo.dds;

import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

public final class DdsSubscriptionQueue {

    /** One pending inbound sample, already converted to a JSON string by
     *  DDSTopicSubscriber — this class never touches DynamicData/DDS types. */
    public static final class PendingMessage {
        public final String queueKey;
        public final String jsonPayload;
        public final long receivedAtMillis;

        PendingMessage(String queueKey, String jsonPayload, long receivedAtMillis) {
            this.queueKey = queueKey;
            this.jsonPayload = jsonPayload;
            this.receivedAtMillis = receivedAtMillis;
        }
    }

    // One queue per key(), created lazily — keeps pollNext() from having to
    // scan/filter a single shared queue on every poll.
    private static final ConcurrentHashMap<String, Queue<PendingMessage>> QUEUES =
            new ConcurrentHashMap<>();

    private DdsSubscriptionQueue() {
    }

    /** The queue key for samples delivered to {@code subscriberBlockName} on
     *  {@code topicName}, e.g. "SAGE::SensorReport". */
    public static String key(String subscriberBlockName, String topicName) {
        return subscriberBlockName + "::" + topicName;
    }

    /** Called from DDSTopicSubscriber's DataReaderListener callback. */
    public static void push(String queueKey, String jsonPayload) {
        QUEUES.computeIfAbsent(queueKey, k -> new ConcurrentLinkedQueue<>())
                .add(new PendingMessage(queueKey, jsonPayload, System.currentTimeMillis()));
    }

    /** Called (via DdsInboundInjector) from the Groovy polling script.
     *  Returns null (cheap, no allocation) if nothing is pending under this
     *  key; otherwise removes and returns the oldest pending message for it. */
    public static PendingMessage pollNext(String queueKey) {
        Queue<PendingMessage> queue = QUEUES.get(queueKey);
        return queue == null ? null : queue.poll();
    }

    /** Discards any backlog queued under this key. This queue is a static,
     *  process-lifetime map with no simulation-run scoping of its own —
     *  push() keeps happening on RTI's callback thread any time a live
     *  reader exists, whether or not a simulation is currently running, and
     *  nothing else in this class ever removes a message except pollNext().
     *  Call this once at simulation bootstrap (see
     *  RegisterDdsEngineListener.groovy) so a fresh run doesn't immediately
     *  inject leftover samples queued before it started. */
    public static void clear(String queueKey) {
        Queue<PendingMessage> queue = QUEUES.get(queueKey);
        if (queue != null) {
            queue.clear();
        }
    }
}