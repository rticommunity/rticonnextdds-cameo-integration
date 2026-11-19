package com.rti.connext.cameo.dds;

import com.google.gson.JsonParser;
import com.rti.dds.domain.DomainParticipant;
import com.rti.dds.domain.DomainParticipantFactory;
import com.rti.dds.domain.DomainParticipantFactoryQos;
import com.rti.dds.dynamicdata.DynamicData;
import com.rti.dds.dynamicdata.DynamicDataReader;
import com.rti.dds.dynamicdata.DynamicDataWriter;
import com.rti.dds.infrastructure.ConditionSeq;
import com.rti.dds.infrastructure.Duration_t;
import com.rti.dds.infrastructure.InstanceHandle_t;
import com.rti.dds.infrastructure.StatusKind;
import com.rti.dds.infrastructure.WaitSet;
import com.rti.dds.topic.PrintFormatKind;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Standalone native DDS regression test; no external transport or running CAMEO required. */
public final class DirectDdsIoTest {
    private static final String PARTICIPANT = "TestParticipants::Runtime";
    private static final String WRITER = "Publisher::SampleWriter";
    private static final String READER_A = "Subscriber::ReaderA";
    private static final String READER_B = "Subscriber::ReaderB";
    private static final String XML = """
            <dds>
              <qos_library name="TestQos">
                <qos_profile name="Polling">
                  <domain_participant_qos>
                    <transport_builtin><mask>SHMEM</mask></transport_builtin>
                    <discovery><initial_peers/><multicast_receive_addresses/></discovery>
                  </domain_participant_qos>
                  <datawriter_qos>
                    <reliability><kind>RELIABLE_RELIABILITY_QOS</kind></reliability>
                    <history><kind>KEEP_LAST_HISTORY_QOS</kind><depth>16</depth></history>
                  </datawriter_qos>
                  <datareader_qos>
                    <reliability><kind>RELIABLE_RELIABILITY_QOS</kind></reliability>
                    <history><kind>KEEP_LAST_HISTORY_QOS</kind><depth>16</depth></history>
                    <reader_resource_limits>
                      <initial_outstanding_reads>1</initial_outstanding_reads>
                      <max_outstanding_reads>1</max_outstanding_reads>
                    </reader_resource_limits>
                  </datareader_qos>
                </qos_profile>
              </qos_library>
              <types><struct name="Sample">
                <member name="id" type="int32" key="true"/>
                <member name="value" type="int32"/>
              </struct></types>
              <domain_library name="TestDomains">
                <domain name="Local" domain_id="219">
                  <register_type name="SampleType" type_ref="Sample"/>
                  <topic name="%s" register_type_ref="SampleType"/>
                </domain>
              </domain_library>
              <domain_participant_library name="TestParticipants">
                <domain_participant name="Runtime" domain_ref="TestDomains::Local">
                  <domain_participant_qos base_name="TestQos::Polling"/>
                  <publisher name="Publisher">
                    <data_writer name="SampleWriter" topic_ref="%s">
                      <datawriter_qos base_name="TestQos::Polling"/>
                    </data_writer>
                  </publisher>
                  <subscriber name="Subscriber">
                    <data_reader name="ReaderA" topic_ref="%s">
                      <datareader_qos base_name="TestQos::Polling"/>
                    </data_reader>
                    <data_reader name="ReaderB" topic_ref="%s">
                      <datareader_qos base_name="TestQos::Polling"/>
                    </data_reader>
                  </subscriber>
                </domain_participant>
              </domain_participant_library>
            </dds>
            """;

    public static void main(String[] args) throws Exception {
        Path config = Files.createTempFile("cameo-direct-io-", ".xml");
        try {
            DomainParticipantFactory factory = DomainParticipantFactory.get_instance();
            DomainParticipantFactoryQos qos = new DomainParticipantFactoryQos();
            factory.get_qos(qos);
            qos.profile.ignore_user_profile = true;
            qos.profile.ignore_environment_profile = true;
            qos.profile.ignore_resource_profile = true;
            factory.set_qos(qos);
            String topic = "DirectIo_" + UUID.randomUUID().toString().replace("-", "");
            Files.writeString(config, XML.formatted(topic, topic, topic, topic));
            DDSTopicPublisher.useGeneratedConfig(config.toFile());
            DDSTopicSubscriber.subscribeOnce(PARTICIPANT, READER_A);
            DDSTopicSubscriber.subscribeOnce(PARTICIPANT, READER_B);
            DomainParticipant participant = DDSTopicPublisher.getOrCreateParticipant(PARTICIPANT);
            DynamicDataReader reader = (DynamicDataReader) participant.lookup_datareader_by_name(READER_A);

            verifyBatchAndReaderIsolation(reader);
            verifyConsumerFailureReturnsLoan(reader);
            verifyInvalidDataAndRestart(participant, reader);
            System.out.println("PASS: 3 direct DDS I/O cases (batch/isolation, loan cleanup, invalid data/restart)");
        } finally {
            DDSTopicSubscriber.shutdown();
            DDSTopicPublisher.shutdown();
            DDSTopicPublisher.useGeneratedConfig(null);
            DomainParticipantFactory.finalize_instance();
            Files.deleteIfExists(config);
        }

    }

    private static void verifyBatchAndReaderIsolation(DynamicDataReader reader) throws Exception {
        require(takeValues(READER_A).isEmpty(), "Empty polling must return without invoking the consumer");
        for (int value = 1; value <= 3; value++) {
            DDSTopicPublisher.publishOnce(PARTICIPANT, WRITER, "{\"value\":" + value + "}");
        }
        awaitData(reader);
        Thread caller = Thread.currentThread();
        List<Integer> values = new ArrayList<>();
        DDSTopicSubscriber.poll(PARTICIPANT, READER_A, json -> {
            require(Thread.currentThread() == caller, "Consumer must run synchronously on the polling thread");
            values.add(valueOf(json));
        });
        require(values.equals(List.of(1, 2, 3)), "One poll must consume the available batch in order: " + values);
        require(takeValues(READER_A).isEmpty(), "A taken batch must not be delivered twice");
        require(takeValues(READER_B).equals(List.of(1, 2, 3)), "Each reader must retain its own samples");
    }

    private static void verifyConsumerFailureReturnsLoan(DynamicDataReader reader) throws Exception {
        DDSTopicPublisher.publishOnce(PARTICIPANT, WRITER, "{\"value\":4}");
        awaitData(reader);
        RuntimeException failure = new IllegalStateException("Expected consumer failure");
        try {
            DDSTopicSubscriber.poll(PARTICIPANT, READER_A, json -> { throw failure; });
            throw new AssertionError("Consumer failures must propagate");
        } catch (RuntimeException ex) {
            require(ex == failure, "Preserve the consumer failure");
        }
        DDSTopicPublisher.publishOnce(PARTICIPANT, WRITER, "{\"value\":5}");
        awaitData(reader);
        require(takeValues(READER_A).equals(List.of(5)),
                "The next take must succeed with max_outstanding_reads=1 after a consumer failure");
    }

    private static void verifyInvalidDataAndRestart(DomainParticipant participant,
                                                   DynamicDataReader reader) throws Exception {
        DynamicDataWriter writer = (DynamicDataWriter) participant.lookup_datawriter_by_name(WRITER);
        DynamicData sample = writer.create_data(DynamicData.PROPERTY_DEFAULT);
        try {
            sample.from_string("{\"value\":5}", PrintFormatKind.JSON_PRINT_FORMAT);
            writer.dispose(sample, InstanceHandle_t.HANDLE_NIL);
        } finally {
            writer.delete_data(sample);
        }
        awaitData(reader);
        require(takeValues(READER_A).isEmpty(), "Dispose notifications must not become model samples");
        DDSTopicPublisher.publishOnce(PARTICIPANT, WRITER, "{\"value\":6}");
        awaitData(reader);
        DDSTopicSubscriber.shutdown();
        DDSTopicPublisher.shutdown();
        DDSTopicSubscriber.subscribeOnce(PARTICIPANT, READER_A);
        require(takeValues(READER_A).isEmpty(), "A new reader must not retain previous-run volatile samples");
        DDSTopicPublisher.publishOnce(PARTICIPANT, WRITER, "{\"value\":7}");
        DynamicDataReader fresh = (DynamicDataReader) DDSTopicPublisher.getOrCreateParticipant(PARTICIPANT)
                .lookup_datareader_by_name(READER_A);
        awaitData(fresh);
        require(takeValues(READER_A).equals(List.of(7)), "Direct I/O must work after recreation");
    }

    private static List<Integer> takeValues(String readerName) throws Exception {
        List<Integer> values = new ArrayList<>();
        DDSTopicSubscriber.poll(PARTICIPANT, readerName, json -> values.add(valueOf(json)));
        return values;
    }

    private static int valueOf(String json) {
        return JsonParser.parseString(json).getAsJsonObject().get("value").getAsInt();
    }

    private static void awaitData(DynamicDataReader reader) {
        var condition = reader.get_statuscondition();
        condition.set_enabled_statuses(StatusKind.DATA_AVAILABLE_STATUS);
        WaitSet waitSet = new WaitSet();
        waitSet.attach_condition(condition);
        try {
            waitSet.wait(new ConditionSeq(), new Duration_t(5, 0));
        } finally {
            waitSet.detach_condition(condition);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}

// Test-only logging adapter: keep the native DDS path real without booting CAMEO's GUI.
// Put the test output directory before plugin classes on the test classpath.
final class CameoLog {
    public static void log(String message) {
        System.out.println(message);
    }

    public static void trace(java.util.function.Supplier<String> message) {
    }
}
