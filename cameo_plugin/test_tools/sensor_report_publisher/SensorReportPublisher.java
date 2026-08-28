/*
 * SensorReportPublisher.java — standalone external DDS publisher for the
 * "SensorReport" topic, used to test the CAMEO plugin's inbound (subscribe)
 * pipeline against a real external publisher (this project has never run
 * such a test — the DDSTopicSubscriber/queue/poll-loop mechanics were all
 * confirmed live, but always against an empty queue, since nothing external
 * has ever actually published to a topic this plugin subscribes to).
 *
 * Modeled directly on an RTI engineer's own reference example
 * (MessageSubscriber.java/MessagePublisher.java, briefly added to this repo
 * under listeners_java/ and since removed) -- same structure, same
 * create_participant_from_config()/lookup_datawriter_by_name() shape,
 * shaped for SensorReport_data's real fields instead of a toy id+text type.
 *
 * ENUM MEMBERS — set_int() with the ordinal, NOT set_string() with the
 * name. Confirmed live the hard way: an earlier version of this file used
 * set_string() on measurement_frame/sensor_modality (the "documented RTI
 * convention" that turned out to be wrong, or at least wrong for this API
 * version) and got RETCODE_ILLEGAL_OPERATION — "Invalid API to set an
 * enumeration. Only setters for DDS_TK_LONG and DDS_TK_ULONG are valid" /
 * "This operation cannot be used for members with TypeCode kind
 * DDS_TK_ENUM." Every DynamicData method used below (set_string/set_int/
 * set_double/set_boolean) is also independently verified against the real
 * nddsjava.jar (javap -p against DynamicData.class).
 *
 * NOTE ON INBOUND WIRING: publishing here only proves DDSTopicSubscriber
 * actually receives and queues a real external SensorReport sample. Nothing
 * currently converts a queued SensorReport sample back into a live signal
 * inside the simulation (unlike Health, which has PollDdsSubscriptionAndInject.
 * groovy + the ReceiveHealth Operation) -- that's separate, not-yet-started
 * work.
 *
 * Relevant Connext Java API:
 *   - DomainParticipantFactory.get_instance().create_participant_from_config()
 *   - DomainParticipant.lookup_datawriter_by_name()
 *   - DynamicDataWriter.create_data() / write()
 *   - DynamicData.set_string() / set_int() / set_double() / set_boolean()
 */

import com.rti.dds.domain.DomainParticipant;
import com.rti.dds.domain.DomainParticipantFactory;
import com.rti.dds.domain.DomainParticipantFactoryQos;
import com.rti.dds.dynamicdata.DynamicData;
import com.rti.dds.dynamicdata.DynamicDataWriter;
import com.rti.dds.infrastructure.InstanceHandle_t;

public class SensorReportPublisher {

    private static volatile boolean running = true;

    public static void main(String[] args) throws InterruptedException {

        DomainParticipant participant = null;
        DynamicDataWriter writer = null;
        DynamicData sample = null;

        // Graceful shutdown on Ctrl+C
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\nClosing application...");
            running = false;
        }));

        try {
            // ----------------------------------------------------------------
            // 1. Load SensorReport.xml into the factory's URL profile.
            //    This must be done before creating any participant.
            // ----------------------------------------------------------------
            DomainParticipantFactoryQos factoryQos = new DomainParticipantFactoryQos();
            DomainParticipantFactory.get_instance().get_qos(factoryQos);
            factoryQos.profile.url_profile.add("SensorReport.xml");
            DomainParticipantFactory.get_instance().set_qos(factoryQos);

            // ----------------------------------------------------------------
            // 2. Create the DomainParticipant from the XML configuration.
            //    The topic, publisher, and writer defined inside
            //    ExternalSensorReportPublisherParticipant are created
            //    automatically.
            // ----------------------------------------------------------------
            participant = DomainParticipantFactory.get_instance()
                    .create_participant_from_config(
                            "DomainParticipantLibrary::ExternalSensorReportPublisherParticipant");
            if (participant == null) {
                System.err.println("Unable to create DDS domain participant");
                return;
            }

            // ----------------------------------------------------------------
            // 3. Look up the DataWriter by the hierarchical name
            //    "<publisher-name>::<writer-name>" defined in the XML.
            // ----------------------------------------------------------------
            writer = (DynamicDataWriter) participant
                    .lookup_datawriter_by_name("Publisher::SensorReportWriter");
            if (writer == null) {
                System.err.println("Unable to find DataWriter 'Publisher::SensorReportWriter'");
                return;
            }

            // ----------------------------------------------------------------
            // 4. Create a DynamicData instance whose type matches the writer.
            // ----------------------------------------------------------------
            sample = writer.create_data(DynamicData.PROPERTY_DEFAULT);
            if (sample == null) {
                System.err.println("Unable to create DynamicData sample");
                return;
            }

            // ----------------------------------------------------------------
            // 5. Write loop: populate every SensorReport_data field and
            //    publish once every 30 seconds (slowed down for demo
            //    pacing -- was 1s). Field values below are simple,
            //    deterministic placeholders (varying only where it helps
            //    confirm distinct samples are actually arriving) -- adjust
            //    freely, this is a test tool, not production data.
            // ----------------------------------------------------------------
            int declaredId = 0;

            while (running) {
                sample.set_string("declared_id", DynamicData.MEMBER_ID_UNSPECIFIED, "EXT-" + declaredId);
                // Must match a sensor actually registered in the model's
                // Registration Map (SENSOR_DATA[*].sensor_id) -- confirmed
                // live via a [DDS Inject] test run that the model's own
                // ValidateSensorReport/Object_Contains logic rejects any
                // sensor_id not in that list (registered test sensors were
                // CAM-01/CAM-02; the placeholder "external-test-sensor-1"
                // used here originally got a real, correct "No match found"
                // rejection from that logic, not a bug).
                sample.set_string("sensor_id", DynamicData.MEMBER_ID_UNSPECIFIED, "CAM-01");
                sample.set_int("measurement_time", DynamicData.MEMBER_ID_UNSPECIFIED,
                        (int) (System.currentTimeMillis() / 1000L));
                // CONFIRMED LIVE (this file's earlier set_string() attempt
                // threw RETCODE_ILLEGAL_OPERATION: "Invalid API to set an
                // enumeration. Only setters for DDS_TK_LONG and DDS_TK_ULONG
                // are valid" / "This operation cannot be used for members
                // with TypeCode kind DDS_TK_ENUM") -- enum members must be
                // set via set_int() with the literal's ordinal, not
                // set_string() with its name. 4 = BEARING_RANGE_ESTIMATE,
                // per MeasurementFrameEnum's real declaration order
                // (confirmed via a live [DDS Scan] log against the real
                // model -- see SensorReport.xml).
                sample.set_int("measurement_frame", DynamicData.MEMBER_ID_UNSPECIFIED, 4);
                sample.set_double("range_m", DynamicData.MEMBER_ID_UNSPECIFIED, 1500.0);
                sample.set_double("bearing_rad", DynamicData.MEMBER_ID_UNSPECIFIED, 0.7854); // ~45 degrees
                sample.set_double("elevation_rad", DynamicData.MEMBER_ID_UNSPECIFIED, 0.1745); // ~10 degrees
                sample.set_double("doppler_mps", DynamicData.MEMBER_ID_UNSPECIFIED, 12.5);
                sample.set_double("snr_db", DynamicData.MEMBER_ID_UNSPECIFIED, 28.0);
                sample.set_boolean("quality_flag", DynamicData.MEMBER_ID_UNSPECIFIED, true);
                // Same fix as measurement_frame above. 0 = RADAR (confirmed
                // real via the same live [DDS Scan] log -- SensorModalityEnum's
                // order/literals were already right, only the setter API was
                // wrong).
                sample.set_int("sensor_modality", DynamicData.MEMBER_ID_UNSPECIFIED, 0);
                sample.set_string("object_class_hint", DynamicData.MEMBER_ID_UNSPECIFIED, "UNKNOWN");
                sample.set_string("reference_point", DynamicData.MEMBER_ID_UNSPECIFIED, "ORIGIN");

                System.out.println("Writing SensorReport, declared_id=EXT-" + declaredId);
                writer.write(sample, InstanceHandle_t.HANDLE_NIL);

                declaredId++;
                Thread.sleep(30000);
            }

        } finally {
            if (sample != null && writer != null) {
                writer.delete_data(sample);
            }
            if (participant != null) {
                participant.delete_contained_entities();
                DomainParticipantFactory.get_instance().delete_participant(participant);
            }
            // Deliberately NOT calling DomainParticipantFactory.finalize_instance()
            // here even though it's a fine thing to do in a standalone CLI
            // tool like this one about to exit -- omitted on purpose so this
            // file can't be copy-pasted into the plugin later without
            // carrying that footgun along (see DDSTopicSubscriber.java's own
            // header comment on why finalize_instance() must never appear
            // there: it would tear down the shared factory for the entire
            // long-lived CAMEO process, not just this plugin's participants).
        }
    }
}
