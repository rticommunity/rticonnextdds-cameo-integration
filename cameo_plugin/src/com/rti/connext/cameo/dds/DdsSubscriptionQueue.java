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
 */
package com.rti.connext.cameo.dds;

import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

public final class DdsSubscriptionQueue {

    /** One pending inbound sample, already converted to a JSON string by
     *  DDSTopicSubscriber — this class never touches DynamicData/DDS types. */
    public static final class PendingMessage {
        public final String topicName;
        public final String jsonPayload;
        public final long receivedAtMillis;

        PendingMessage(String topicName, String jsonPayload, long receivedAtMillis) {
            this.topicName = topicName;
            this.jsonPayload = jsonPayload;
            this.receivedAtMillis = receivedAtMillis;
        }
    }

    // One queue per topic name, created lazily — keeps pollNext(topicName)
    // from having to scan/filter a single shared queue on every poll.
    private static final ConcurrentHashMap<String, Queue<PendingMessage>> QUEUES =
            new ConcurrentHashMap<>();

    private DdsSubscriptionQueue() {
    }

    /** Called from DDSTopicSubscriber's DataReaderListener callback. */
    public static void push(String topicName, String jsonPayload) {
        QUEUES.computeIfAbsent(topicName, key -> new ConcurrentLinkedQueue<>())
                .add(new PendingMessage(topicName, jsonPayload, System.currentTimeMillis()));
    }

    /** Called from the Groovy polling script. Returns null (cheap, no
     *  allocation) if nothing is pending for this topic; otherwise removes
     *  and returns the oldest pending message for it. Never returns a
     *  message belonging to a different topic. */
    public static PendingMessage pollNext(String topicName) {
        Queue<PendingMessage> queue = QUEUES.get(topicName);
        return queue == null ? null : queue.poll();
    }

    /** Discards any backlog queued for this topic. This queue is a static,
     *  process-lifetime map with no simulation-run scoping of its own —
     *  push() keeps happening on RTI's callback thread any time a live
     *  reader exists, whether or not a simulation is currently running, and
     *  nothing else in this class ever removes a message except pollNext().
     *  Call this once at simulation bootstrap (see
     *  RegisterDdsEngineListener.groovy) so a fresh run doesn't immediately
     *  inject leftover samples queued before it started. */
    public static void clear(String topicName) {
        Queue<PendingMessage> queue = QUEUES.get(topicName);
        if (queue != null) {
            queue.clear();
        }
    }
}