/*
 * DDSTopicPublisher.java — reusable, topic-agnostic DDS publish helper.
 *
 * This generalizes the participant/writer creation pattern already used in
 * DDSRunner (which is hardcoded to "DomainParticipantLibrary::SquareParticipant"
 * / "Publisher::SquareWriter") so it can publish a JSON payload to ANY
 * participant/writer defined in the resolved XML Application Creation config,
 * on demand, from a modeling Action rather than a long-running background thread.
 *
 * Unlike DDSRunner's publisher (which loops forever writing samples every
 * 500ms), this is a single-shot "publish one sample and return" call — the
 * right shape for something triggered from an Activity diagram Action.
 *
 * TODO: decide whether the DomainParticipant should be created fresh per
 * publish call (safe, simple, slightly slower — what's implemented below)
 * or cached/reused across calls (faster for repeated firing, but needs
 * lifecycle management tied into RTIConnextPlugin.close()/DDSRunner.stopAll()
 * so it's cleaned up when CAMEO shuts down or the plugin unloads).
 */
package com.rti.connext.cameo.actions;

import com.rti.connext.cameo.DDSRunner;
import com.rti.dds.domain.DomainParticipant;
import com.rti.dds.domain.DomainParticipantFactory;
import com.rti.dds.domain.DomainParticipantFactoryQos;
import com.rti.dds.dynamicdata.DynamicData;
import com.rti.dds.dynamicdata.DynamicDataWriter;
import com.rti.dds.infrastructure.InstanceHandle_t;
import com.rti.dds.topic.PrintFormatKind;

import java.io.IOException;

public final class DDSTopicPublisher {

    private DDSTopicPublisher() {
    }

    /**
     * Publishes a single JSON-encoded sample to the given data writer, under
     * the given participant config entry, resolved via DDSRunner's own
     * resolveXmlUrl() (now public — see DDSRunner.java) so both the existing
     * publisher/subscriber and these new actions share one resolution path
     * instead of two copies drifting apart.
     *
     * @param participantConfigName e.g. "DomainParticipantLibrary::SquareParticipant"
     * @param dataWriterName        e.g. "Publisher::SquareWriter" — must exist
     *                               under the given participant in the XML config
     * @param jsonPayload            e.g. {"color":"BLUE","x":100,"y":200,"shapesize":30}
     * @throws IOException if the XML config can't be resolved
     * @throws RuntimeException if participant/writer creation fails, or the
     *                          JSON payload doesn't match the writer's registered type
     */
    public static void publishOnce(String participantConfigName,
                                    String dataWriterName,
                                    String jsonPayload) throws IOException {
        String xmlUrl = DDSRunner.resolveXmlUrl();

        DomainParticipant participant = null;
        try {
            DomainParticipantFactoryQos factoryQos = new DomainParticipantFactoryQos();
            DomainParticipantFactory.get_instance().get_qos(factoryQos);
            factoryQos.profile.url_profile.clear();
            factoryQos.profile.url_profile.add(xmlUrl);
            DomainParticipantFactory.get_instance().set_qos(factoryQos);

            participant = DomainParticipantFactory.get_instance()
                    .create_participant_from_config(participantConfigName);
            if (participant == null) {
                throw new RuntimeException(
                        "Failed to create DomainParticipant from '" + participantConfigName + "'.");
            }

            DynamicDataWriter writer =
                    (DynamicDataWriter) participant.lookup_datawriter_by_name(dataWriterName);
            if (writer == null) {
                throw new RuntimeException(
                        "DataWriter '" + dataWriterName + "' not found under " + participantConfigName + ".");
            }

            DynamicData sample = writer.create_data(DynamicData.PROPERTY_DEFAULT);
            if (sample == null) {
                throw new RuntimeException("Failed to create DynamicData sample for " + dataWriterName + ".");
            }

            sample.from_string(jsonPayload, PrintFormatKind.JSON_PRINT_FORMAT);
            writer.write(sample, InstanceHandle_t.HANDLE_NIL);

        } finally {
            if (participant != null) {
                participant.delete_contained_entities();
                DomainParticipantFactory.get_instance().delete_participant(participant);
            }
        }
    }
}
