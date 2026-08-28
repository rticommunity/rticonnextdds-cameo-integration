/*
 * DDSTopicSubscriber.java — reusable, topic-agnostic DDS subscribe helper.
 * Mirrors DDSTopicPublisher.java's structure (lazy/cached DomainParticipant,
 * plain-String public API, same shutdown()-on-simulation-end pattern) for
 * the opposite direction: external DDS samples -> DdsSubscriptionQueue,
 * where a Groovy polling script (see scripts/PollDdsSubscriptionAndInject.
 * groovy) picks them up and injects them into the live simulation.
 *
 * DomainParticipant creation/caching is delegated to DDSTopicPublisher.
 * getOrCreateParticipant() (package-private) rather than duplicated here —
 * a single DomainParticipant can hold both writers and readers, so a
 * participant an outbound topic already created is reused as-is when an
 * inbound topic on the same participant config subscribes too.
 *
 * Verified against the real nddsjava API (javap -v -p against
 * build\nddsjava.jar, same decompile discipline as everywhere else in this
 * project) rather than assumed:
 *   - DomainParticipant.lookup_datareader_by_name(String) : DataReader
 *   - com.rti.dds.subscription.DataReaderAdapter — abstract no-op base
 *     implementing DataReaderListener, overriding only on_data_available()
 *     and on_subscription_matched() the same way a Swing MouseAdapter is
 *     used to avoid implementing every interface method.
 *   - DataReaderImpl.set_listener(DataReaderListener, int) — the mask arg
 *     is a StatusKind bitmask; DATA_AVAILABLE_STATUS | SUBSCRIPTION_MATCHED_STATUS
 *     covers both overridden callbacks.
 *   - DynamicDataReader.take(DynamicDataSeq, SampleInfoSeq, int, int, int, int)
 *     — the batch drain used in on_data_available() below, matching the
 *     pattern in an RTI engineer's own reference example
 *     (listeners_java/MessageSubscriber.java, added to this repo): one call
 *     returns every currently-available sample as a loaned sequence,
 *     cleaned up with a single return_loan() — replaces an earlier version
 *     of this method that looped individual take_next_sample() calls (each
 *     needing its own create_data()/delete_data()); the batch form is RTI's
 *     own idiom and avoids the extra per-sample allocation.
 *   - DynamicData.to_string(PrintFormatProperty) — the read-side mirror of
 *     DDSTopicPublisher's sample.from_string(json, PrintFormatKind.
 *     JSON_PRINT_FORMAT); PrintFormatProperty(PrintFormatKind) is the
 *     matching constructor for JSON_PRINT_FORMAT.
 *   - SampleInfo.valid_data — false for instance-state-change samples
 *     (dispose/unregister) carrying no real data; skipped rather than
 *     converted to JSON.
 *   - SubscriptionMatchedStatus.current_count_change — > 0 means a new
 *     publisher just matched; logged as a diagnostic (also borrowed from
 *     the same reference example) so "the queue is empty" during testing
 *     can be told apart from "no publisher is even connected."
 */
package com.rti.connext.cameo.dds;

import com.nomagic.magicdraw.core.Application;

import com.rti.dds.domain.DomainParticipant;
import com.rti.dds.dynamicdata.DynamicData;
import com.rti.dds.dynamicdata.DynamicDataReader;
import com.rti.dds.dynamicdata.DynamicDataSeq;
import com.rti.dds.infrastructure.RETCODE_NO_DATA;
import com.rti.dds.infrastructure.ResourceLimitsQosPolicy;
import com.rti.dds.infrastructure.StatusKind;
import com.rti.dds.subscription.DataReader;
import com.rti.dds.subscription.DataReaderAdapter;
import com.rti.dds.subscription.InstanceStateKind;
import com.rti.dds.subscription.SampleInfo;
import com.rti.dds.subscription.SampleInfoSeq;
import com.rti.dds.subscription.SampleStateKind;
import com.rti.dds.subscription.SubscriptionMatchedStatus;
import com.rti.dds.subscription.ViewStateKind;
import com.rti.dds.topic.PrintFormatKind;
import com.rti.dds.topic.PrintFormatProperty;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;

public final class DDSTopicSubscriber {

    // Cached DynamicDataReaders, keyed by dataReaderName — created lazily on
    // first subscribeOnce() call for a given reader, so a repeated call
    // (e.g. the bootstrap script running again) doesn't register a second
    // listener on the same reader.
    private static final ConcurrentHashMap<String, DynamicDataReader> READERS =
            new ConcurrentHashMap<>();

    private DDSTopicSubscriber() {
    }

    /**
     * Looks up the named DataReader under the given participant config
     * (creating/reusing the DomainParticipant via DDSTopicPublisher's shared
     * cache) and registers a listener that converts each arriving sample to
     * JSON and pushes it onto DdsSubscriptionQueue under {@code topicName}.
     * Safe to call more than once for the same dataReaderName — subsequent
     * calls are no-ops.
     *
     * @param participantConfigName e.g. "DomainParticipantLibrary::SensorAlignmentAndGatingService_Definition"
     * @param dataReaderName        e.g. "Subscriber::HealthReader" — must exist under the given participant in the XML config
     * @param topicName             e.g. "Health" — the key PollDdsSubscriptionAndInject.groovy polls for
     * @throws IOException if the XML config can't be resolved
     * @throws RuntimeException if participant/reader creation fails, or the named DataReader isn't found
     */
    public static void subscribeOnce(String participantConfigName,
                                      String dataReaderName,
                                      String topicName) throws IOException {
        if (READERS.containsKey(dataReaderName)) {
            // Silent no-op — this is the expected common case once a caller
            // (e.g. PollDdsSubscriptionAndInject.groovy, which calls this at
            // the top of every poll cycle to self-heal from a premature
            // executionTerminated() teardown) starts calling subscribeOnce()
            // on every cycle rather than once at bootstrap.
            return;
        }

        DomainParticipant participant = DDSTopicPublisher.getOrCreateParticipant(participantConfigName);

        DataReader reader = participant.lookup_datareader_by_name(dataReaderName);
        if (reader == null) {
            throw new RuntimeException(
                    "DataReader '" + dataReaderName + "' not found under " + participantConfigName + ".");
        }
        if (!(reader instanceof DynamicDataReader)) {
            throw new RuntimeException(
                    "DataReader '" + dataReaderName + "' is not a DynamicDataReader (found "
                            + reader.getClass().getName() + ").");
        }
        DynamicDataReader dynamicReader = (DynamicDataReader) reader;

        // computeIfAbsent guards against a race between two concurrent
        // subscribeOnce() calls for the same name; the containsKey() check
        // above is just a cheap early-out for the common (already-subscribed,
        // single-threaded bootstrap) case.
        DynamicDataReader existing = READERS.putIfAbsent(dataReaderName, dynamicReader);
        if (existing != null) {
            log("[RTI Connext] Subscription already active for '" + dataReaderName + "' — skipping re-registration.");
            return;
        }

        dynamicReader.set_listener(new SampleListener(topicName),
                StatusKind.DATA_AVAILABLE_STATUS | StatusKind.SUBSCRIPTION_MATCHED_STATUS);
        log("[RTI Connext] Subscribed to '" + dataReaderName + "' under " + participantConfigName
                + " — inbound samples will be queued under topic '" + topicName + "'.");
    }

    /**
     * Clears the cached-reader registry. Doesn't explicitly delete the
     * DataReaders themselves — they're contained entities of whatever
     * DomainParticipant owns them, and DDSTopicPublisher.shutdown() already
     * calls participant.delete_contained_entities() on every cached
     * participant, which deletes their readers too. Called alongside
     * DDSTopicPublisher.shutdown() from RTIConnextPlugin.close() and
     * DdsEngineListener's executionTerminated() hooks.
     */
    public static void shutdown() {
        READERS.clear();
    }

    private static final class SampleListener extends DataReaderAdapter {
        private final String topicName;
        private final PrintFormatProperty jsonFormat = new PrintFormatProperty(PrintFormatKind.JSON_PRINT_FORMAT);

        SampleListener(String topicName) {
            this.topicName = topicName;
        }

        // Runs on RTI's own native callback thread, not CAMEO's UI/
        // simulation thread — DdsSubscriptionQueue.push() is the hand-off
        // point back to the simulation's own execution (see
        // PollDdsSubscriptionAndInject.groovy).
        //
        // on_data_available is an edge-triggered "go check the reader"
        // notification, not "here is exactly one sample" -- if multiple
        // samples arrive before this callback gets to run, RTI can coalesce
        // them into a single invocation. Drains the reader via the batch
        // take() overload (matching an RTI engineer's own reference example,
        // listeners_java/MessageSubscriber.java, added to this repo) rather
        // than looping single-sample take_next_sample() calls -- one call
        // returns every currently-available sample as a loaned sequence,
        // freed with a single return_loan(), so nothing is lost at this
        // hand-off regardless of how many samples arrived between callback
        // firings.
        @Override
        public void on_data_available(DataReader reader) {
            DynamicDataReader dynamicReader = (DynamicDataReader) reader;
            DynamicDataSeq dataSeq = new DynamicDataSeq();
            SampleInfoSeq infoSeq = new SampleInfoSeq();
            try {
                dynamicReader.take(dataSeq, infoSeq, ResourceLimitsQosPolicy.LENGTH_UNLIMITED,
                        SampleStateKind.ANY_SAMPLE_STATE, ViewStateKind.ANY_VIEW_STATE,
                        InstanceStateKind.ANY_INSTANCE_STATE);

                for (int i = 0; i < dataSeq.size(); i++) {
                    SampleInfo info = (SampleInfo) infoSeq.get(i);
                    if (!info.valid_data) {
                        // Instance-state-change (dispose/unregister) sample,
                        // no real data to forward.
                        continue;
                    }
                    try {
                        DynamicData sample = (DynamicData) dataSeq.get(i);
                        String json = sample.to_string(jsonFormat);
                        DdsSubscriptionQueue.push(topicName, json);
                        log("[RTI Connext] Received sample for topic '" + topicName + "' -> queued: " + json);
                    } catch (Exception ex) {
                        log("[RTI Connext] Failed to convert/queue a sample for topic '" + topicName + "': " + ex);
                    }
                }
            } catch (RETCODE_NO_DATA noData) {
                // Nothing to process -- not a problem.
            } finally {
                try {
                    dynamicReader.return_loan(dataSeq, infoSeq);
                } catch (Exception ignored) {
                    // best-effort cleanup only
                }
            }
        }

        // Diagnostic-only: lets "queue stayed empty" be told apart from "no
        // publisher ever connected" during testing -- borrowed from the same
        // reference example.
        @Override
        public void on_subscription_matched(DataReader reader, SubscriptionMatchedStatus status) {
            if (status.current_count_change > 0) {
                log("[RTI Connext] Topic '" + topicName + "' matched with a publisher.");
            } else if (status.current_count_change < 0) {
                log("[RTI Connext] Topic '" + topicName + "' — a matched publisher went away.");
            }
        }
    }

    private static void log(String msg) {
        try {
            Application.getInstance().getGUILog().log(msg);
        } catch (Exception ignored) {
            System.out.println(msg);
        }
    }
}