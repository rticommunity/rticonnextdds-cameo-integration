/*
 * (c) Copyright, Real-Time Innovations, 2026.  All rights reserved.
 * RTI grants Licensee a license to use, modify, compile, and create derivative
 * works of the software solely for use with RTI Connext DDS. Licensee may
 * redistribute copies of the software provided that all such copies are subject
 * to this license. The software is provided "as is", with no warranty of any
 * type, including any warranty for fitness for any purpose. RTI is under no
 * obligation to maintain or support the software. RTI shall not be liable for
 * any incidental or consequential damages arising out of the use or inability
 * to use the software.
 */

/*
 * DdsSubscriptionQueue — hands received samples (as JSON) from RTI's callback
 * thread, where DDSTopicSubscriber pushes them, to the simulation's thread,
 * where DdsInboundInjector polls them.
 *
 * One queue per (subscribing Block, topic) — see key() — so two Blocks
 * receiving the same topic each get exactly one copy of every sample, as two
 * separate DDS applications would.
 */
package com.rti.connext.cameo.dds;

import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

public final class DdsSubscriptionQueue {

    /** One received sample, as JSON. */
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

    // One queue per key(), created on first use.
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

    /** Removes and returns the oldest sample under {@code queueKey}, or null
     *  if there is none. */
    public static PendingMessage pollNext(String queueKey) {
        Queue<PendingMessage> queue = QUEUES.get(queueKey);
        return queue == null ? null : queue.poll();
    }

    /** Discards everything queued under {@code queueKey}. The queues live as
     *  long as CAMEO and keep filling whenever a reader exists, so each run
     *  clears them once at start, before injecting anything. */
    public static void clear(String queueKey) {
        Queue<PendingMessage> queue = QUEUES.get(queueKey);
        if (queue != null) {
            queue.clear();
        }
    }
}