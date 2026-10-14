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
 * DDSTopicSubscriber — attaches a listener to the DataReaders defined in the
 * active DDS XML config and hands each received sample, as JSON, to
 * DdsSubscriptionQueue, from which DdsInboundInjector injects it into the
 * simulation.
 *
 * Participants come from DDSTopicPublisher.getOrCreateParticipant(), so a
 * Block's writers and readers share one participant.
 *
 * on_data_available() is a "data is waiting" notification, not one call per
 * sample: RTI may coalesce several samples into one callback. Each callback
 * therefore drains the reader with a single batch take() and returns the
 * loan, so no sample is left behind. Samples without valid data
 * (dispose/unregister notifications) are skipped.
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

    // Readers with a listener attached, keyed by participant + reader name,
    // so subscribing again never adds a second listener. The participant is
    // part of the key because reader names are only unique per participant:
    // two Blocks receiving the same topic both have
    // "Subscriber::<Topic>Reader".
    private static final ConcurrentHashMap<String, DynamicDataReader> READERS =
            new ConcurrentHashMap<>();

    private DDSTopicSubscriber() {
    }

    /**
     * Attaches a listener to the named DataReader that queues each received
     * sample, as JSON, under {@code queueKey}. Calling it again for the same
     * participant and reader does nothing.
     *
     * @param participantConfigName e.g. "DomainParticipantLibrary::SAGE"
     * @param dataReaderName        e.g. "Subscriber::SensorReportReader" — must exist under that participant in the XML
     * @param topicName             e.g. "SensorReport" — used in log messages
     * @param queueKey              e.g. "SAGE::SensorReport" — see DdsSubscriptionQueue.key()
     * @throws IOException if the XML config can't be resolved
     * @throws RuntimeException if participant/reader creation fails, or the named DataReader isn't found
     */
    public static void subscribeOnce(String participantConfigName,
                                      String dataReaderName,
                                      String topicName,
                                      String queueKey) throws IOException {
        String readerKey = participantConfigName + "/" + dataReaderName;
        if (READERS.containsKey(readerKey)) {
            // Expected on every poll cycle after the first.
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

        // putIfAbsent settles a race between two concurrent calls; the
        // containsKey() check above is only a cheap early exit.
        DynamicDataReader existing = READERS.putIfAbsent(readerKey, dynamicReader);
        if (existing != null) {
            log("[RTI Connext] Subscription already active for '" + dataReaderName + "' — skipping re-registration.");
            return;
        }

        dynamicReader.set_listener(new SampleListener(topicName, queueKey),
                StatusKind.DATA_AVAILABLE_STATUS | StatusKind.SUBSCRIPTION_MATCHED_STATUS);
        log("[RTI Connext] Subscribed to '" + dataReaderName + "' under " + participantConfigName
                + " — inbound '" + topicName + "' samples will be queued under '" + queueKey + "'.");
    }

    /**
     * Forgets the readers. The readers themselves are deleted with their
     * participants by DDSTopicPublisher.shutdown(), which is always called
     * alongside this.
     */
    public static void shutdown() {
        READERS.clear();
    }

    private static final class SampleListener extends DataReaderAdapter {
        private final String topicName;
        private final String queueKey;
        private final PrintFormatProperty jsonFormat = new PrintFormatProperty(PrintFormatKind.JSON_PRINT_FORMAT);

        SampleListener(String topicName, String queueKey) {
            this.topicName = topicName;
            this.queueKey = queueKey;
        }

        // Runs on RTI's callback thread, not the simulation's;
        // DdsSubscriptionQueue is the hand-off. Drains everything available
        // (see the class comment).
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
                        // dispose/unregister notification, no data
                        continue;
                    }
                    try {
                        DynamicData sample = (DynamicData) dataSeq.get(i);
                        String json = sample.to_string(jsonFormat);
                        DdsSubscriptionQueue.push(queueKey, json);
                        log("[RTI Connext] Received '" + topicName + "' for " + queueKey + ": " + json);
                    } catch (Exception ex) {
                        log("[RTI Connext] Failed to convert/queue a sample for topic '" + topicName + "': " + ex);
                    }
                }
            } catch (RETCODE_NO_DATA noData) {
                // nothing available
            } finally {
                try {
                    dynamicReader.return_loan(dataSeq, infoSeq);
                } catch (Exception ignored) {
                    // best-effort cleanup only
                }
            }
        }

        // Logged so "nothing arrived" can be told apart from "no publisher is
        // connected".
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