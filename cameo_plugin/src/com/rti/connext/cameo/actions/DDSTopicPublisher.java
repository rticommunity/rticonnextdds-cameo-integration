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
 * DomainParticipant creation is expensive (it triggers discovery/matching
 * with every other participant on the domain), so participants are created
 * lazily on first use and cached per participantConfigName for the life of
 * the plugin — the same create-once-hold-reference pattern DDSRunner already
 * uses for its long-running publisher/subscriber threads, just applied here
 * to the single-shot-publish case. Call shutdown() (wired into
 * RTIConnextPlugin.close()) to tear the cached participants down.
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
import java.util.concurrent.ConcurrentHashMap;

public final class DDSTopicPublisher {

    // Cached DomainParticipants, keyed by participant config name — created
    // lazily on first use, reused across all subsequent publishOnce() calls.
    private static final ConcurrentHashMap<String, DomainParticipant> PARTICIPANTS =
            new ConcurrentHashMap<>();

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
        DomainParticipant participant =
                PARTICIPANTS.computeIfAbsent(participantConfigName,
                        key -> createParticipant(key, xmlUrl));

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
    }

    /**
     * Creates a new DomainParticipant from the given config name. Only called
     * by PARTICIPANTS.computeIfAbsent() above, which guarantees this runs at
     * most once per participantConfigName even under concurrent callers.
     */
    private static DomainParticipant createParticipant(String participantConfigName, String xmlUrl) {
        DomainParticipantFactoryQos factoryQos = new DomainParticipantFactoryQos();
        DomainParticipantFactory.get_instance().get_qos(factoryQos);
        factoryQos.profile.url_profile.clear();
        factoryQos.profile.url_profile.add(xmlUrl);
        DomainParticipantFactory.get_instance().set_qos(factoryQos);

        DomainParticipant participant = DomainParticipantFactory.get_instance()
                .create_participant_from_config(participantConfigName);
        if (participant == null) {
            throw new RuntimeException(
                    "Failed to create DomainParticipant from '" + participantConfigName + "'.");
        }
        return participant;
    }

    /**
     * Deletes and forgets every cached DomainParticipant. Called from
     * RTIConnextPlugin.close() so cached participants don't outlive the
     * plugin.
     */
    public static synchronized void shutdown() {
        for (DomainParticipant participant : PARTICIPANTS.values()) {
            participant.delete_contained_entities();
            DomainParticipantFactory.get_instance().delete_participant(participant);
        }
        PARTICIPANTS.clear();
    }
}
