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
 * DDSTopicSubscriber — polls XML-defined DataReaders on the calling simulation
 * thread and passes each valid sample directly to the injector as JSON.
 *
 * Participants come from DDSTopicPublisher.getOrCreateParticipant(), so a
 * Block's writers and readers share one participant.
 *
 * Samples remain in DDS history until polled, subject to reader QoS limits.
 * Each poll takes one available batch and returns its loan after consumption.
 */
package com.rti.connext.cameo.dds;

import com.rti.dds.domain.DomainParticipant;
import com.rti.dds.dynamicdata.DynamicData;
import com.rti.dds.dynamicdata.DynamicDataReader;
import com.rti.dds.dynamicdata.DynamicDataSeq;
import com.rti.dds.infrastructure.RETCODE_NO_DATA;
import com.rti.dds.infrastructure.ResourceLimitsQosPolicy;
import com.rti.dds.subscription.DataReader;
import com.rti.dds.subscription.InstanceStateKind;
import com.rti.dds.subscription.SampleInfo;
import com.rti.dds.subscription.SampleInfoSeq;
import com.rti.dds.subscription.SampleStateKind;
import com.rti.dds.subscription.ViewStateKind;
import com.rti.dds.topic.PrintFormatKind;
import com.rti.dds.topic.PrintFormatProperty;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public final class DDSTopicSubscriber {

    // Reader names are only unique within a participant.
    private static final ConcurrentHashMap<String, DynamicDataReader> READERS =
            new ConcurrentHashMap<>();

    private DDSTopicSubscriber() {
    }

    /**
     * Resolves the named reader without attaching a reception listener.
     * Calling it again for the same participant and reader reuses the reader.
     *
     * @param participantConfigName e.g. "DomainParticipantLibrary::SAGE"
     * @param dataReaderName        e.g. "Subscriber::SensorReportReader" — must exist under that participant in the XML
     * @throws IOException if the XML config can't be resolved
     * @throws RuntimeException if participant/reader creation fails, or the named DataReader isn't found
     */
    public static void subscribeOnce(String participantConfigName,
                                     String dataReaderName) throws IOException {
        getOrCreateReader(participantConfigName, dataReaderName);
    }

    private static DynamicDataReader getOrCreateReader(String participantConfigName,
                                                       String dataReaderName) throws IOException {
        String readerKey = participantConfigName + "/" + dataReaderName;
        DynamicDataReader cached = READERS.get(readerKey);
        if (cached != null) {
            return cached;
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

        DynamicDataReader existing = READERS.putIfAbsent(readerKey, dynamicReader);
        if (existing != null) {
            return existing;
        }

        log("[RTI Connext] Subscribed to '" + dataReaderName + "' under '" + participantConfigName
                + "' for direct polling.");
        return dynamicReader;
    }

    /**
     * Forgets the readers. The readers themselves are deleted with their
     * participants by DDSTopicPublisher.shutdown(), which is always called
     * alongside this.
     */
    public static void shutdown() {
        READERS.clear();
    }

    /**
     * Takes available samples and consumes them synchronously on the caller's
     * thread. No application queue or DDS data-available callback is involved.
     * The loan is returned even if conversion or consumption fails.
     */
    public static void poll(String participantConfigName, String dataReaderName,
                            Consumer<String> consume) throws IOException {
        DynamicDataReader reader = getOrCreateReader(participantConfigName, dataReaderName);
        DynamicDataSeq data = new DynamicDataSeq();
        SampleInfoSeq info = new SampleInfoSeq();
        try {
            reader.take(data, info, ResourceLimitsQosPolicy.LENGTH_UNLIMITED,
                    SampleStateKind.ANY_SAMPLE_STATE, ViewStateKind.ANY_VIEW_STATE,
                    InstanceStateKind.ANY_INSTANCE_STATE);
        } catch (RETCODE_NO_DATA noData) {
            return;
        }
        try {
            PrintFormatProperty format = new PrintFormatProperty(PrintFormatKind.JSON_PRINT_FORMAT);
            for (int i = 0; i < data.size(); i++) {
                if (((SampleInfo) info.get(i)).valid_data) {
                    String json = ((DynamicData) data.get(i)).to_string(format);
                    consume.accept(json);
                }
            }
        } finally {
            reader.return_loan(data, info);
        }
    }

    private static void log(String msg) {
        CameoLog.log(msg);
    }
}