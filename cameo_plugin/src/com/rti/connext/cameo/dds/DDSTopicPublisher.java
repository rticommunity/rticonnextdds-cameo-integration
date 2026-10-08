/*
 * DDSTopicPublisher.java — reusable, topic-agnostic DDS publish helper.
 *
 * Publishes a JSON payload to any participant/writer defined in the resolved
 * XML Application Creation config, on demand — the right shape for something
 * triggered from a simulation event (see DdsEngineListener) rather than a
 * long-running background thread.
 *
 * DomainParticipant creation is expensive (it triggers discovery/matching
 * with every other participant on the domain), so participants are created
 * lazily on first use and cached per participantConfigName for the life of
 * the plugin. Call shutdown() (wired into RTIConnextPlugin.close()) to tear
 * the cached participants down.
 */
package com.rti.connext.cameo.dds;

import com.nomagic.magicdraw.core.Application;

import com.rti.dds.domain.DomainParticipant;
import com.rti.dds.domain.DomainParticipantFactory;
import com.rti.dds.domain.DomainParticipantFactoryQos;
import com.rti.dds.dynamicdata.DynamicData;
import com.rti.dds.dynamicdata.DynamicDataWriter;
import com.rti.dds.infrastructure.InstanceHandle_t;
import com.rti.dds.topic.PrintFormatKind;
import com.rti.ndds.config.LogMessage;
import com.rti.ndds.config.LogVerbosity;
import com.rti.ndds.config.Logger;
import com.rti.ndds.config.LoggerDevice;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;

public final class DDSTopicPublisher {

    // Cached DomainParticipants, keyed by participant config name — created
    // lazily on first use, reused across all subsequent publishOnce() calls.
    private static final ConcurrentHashMap<String, DomainParticipant> PARTICIPANTS =
            new ConcurrentHashMap<>();

    // file:// URL of the config DdsRuntimeConfig generated from the model for
    // the current simulation run — null until it has run.
    private static volatile String generatedXmlUrl = null;

    // True when the generated file was rewritten since RTI last loaded it.
    private static volatile boolean profilesStale = false;

    // Set once, on first publishOnce() call — routes NDDS's own internal
    // diagnostic logging into this GUI log. Added specifically because
    // RETCODE_ERROR on write() was carrying no message (confirmed via
    // decompiling RETCODE_ERROR.check_return_codeI(): it only attaches a
    // message when the native get_last_error_messages_and_clearI() call
    // returns non-null, which it wasn't for these failures) — the actual
    // reason lives in NDDS's own Logger output, which defaults to going
    // nowhere this plugin could see it.
    private static volatile boolean loggingConfigured = false;

    private DDSTopicPublisher() {
    }

    private static synchronized void ensureLoggingConfigured() {
        if (loggingConfigured) {
            return;
        }
        try {
            Logger.get_instance().set_output_device(new LoggerDevice() {
                @Override
                public void write(LogMessage msg) {
                    log("[RTI Connext NDDS] " + (msg.level == null ? "" : msg.level + " ") + msg.text);
                }

                @Override
                public void close() {
                    // no-op — nothing to release on our side.
                }
            });
            Logger.get_instance().set_verbosity(LogVerbosity.NDDS_CONFIG_LOG_VERBOSITY_WARNING);
            log("[RTI Connext] NDDS internal logging routed to this GUI log at WARNING verbosity.");
        } catch (Exception ex) {
            log("[RTI Connext] Failed to configure NDDS internal logging: " + ex);
        }
        loggingConfigured = true;
    }

    /**
     * Publishes a single JSON-encoded sample to the given data writer, under
     * the given participant config entry.
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
        DomainParticipant participant = getOrCreateParticipant(participantConfigName);

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

        try {
            sample.from_string(jsonPayload, PrintFormatKind.JSON_PRINT_FORMAT);
            writer.write(sample, InstanceHandle_t.HANDLE_NIL);
        } finally {
            // The sample lives in native (C) memory that the Java garbage
            // collector can't see, so it must be freed explicitly — on every
            // path, including when from_string() rejects the payload.
            writer.delete_data(sample);
        }
    }

    /**
     * Returns the cached DomainParticipant for the given config name,
     * creating it (and configuring NDDS logging/resolving the XML config, on
     * first use) if it doesn't exist yet. Package-private — shared with
     * DDSTopicSubscriber so a participant that already exists here (because
     * something in this same participant already publishes) isn't recreated
     * a second time just to add a reader; a single DomainParticipant can
     * hold both writers and readers.
     */
    static DomainParticipant getOrCreateParticipant(String participantConfigName) throws IOException {
        ensureLoggingConfigured();
        String xmlUrl = resolveXmlUrl();
        return PARTICIPANTS.computeIfAbsent(participantConfigName,
                key -> createParticipant(key, xmlUrl));
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
        if (profilesStale) {
            // The URL can be identical to last run's while the file's content
            // changed, which set_qos() alone doesn't detect.
            DomainParticipantFactory.get_instance().reload_profiles();
            profilesStale = false;
        }

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

    // -------------------------------------------------------------------------
    // XML config resolution
    // -------------------------------------------------------------------------

    /**
     * Makes {@code file} — just generated from the model by DdsRuntimeConfig
     * — the config for this simulation run, and has RTI re-read its profiles
     * so a model changed since the previous run takes effect without
     * restarting CAMEO.
     */
    static void useGeneratedConfig(File file) {
        generatedXmlUrl = toFileUrl(file);
        profilesStale = true; // same path every run, new content: see createParticipant()
    }

    /**
     * Resolves the DDS XML Application Creation config URL, in order:
     *  1. -Dcom.rti.connext.cameo.xmlConfig=&lt;path&gt; — an explicit,
     *     hand-maintained XML always wins.
     *  2. The config DdsRuntimeConfig generated from the model at the start of
     *     this simulation run.
     * There is deliberately no "look for any *.xml in a folder" fallback: it
     * silently picked whichever file sorted first, including stale ones.
     *
     * Returns a file:// URL string accepted by DomainParticipantFactory.
     */
    private static String resolveXmlUrl() throws IOException {
        String sysProp = System.getProperty("com.rti.connext.cameo.xmlConfig");
        if (sysProp != null) {
            File f = new File(sysProp);
            if (!f.isFile()) {
                throw new IOException(
                        "XML config not found at -Dcom.rti.connext.cameo.xmlConfig: " + sysProp);
            }
            return toFileUrl(f);
        }
        String generated = generatedXmlUrl;
        if (generated != null) {
            return generated;
        }
        throw new IOException(
                "No DDS XML config: start the simulation with RegisterDdsEngineListener.groovy, which"
                + " generates it from the model, or set -Dcom.rti.connext.cameo.xmlConfig=<path>"
                + " in CAMEO's JVM options.");
    }

    /** file:// URL for {@code f} on any OS: file:///C:/dir/x.xml on Windows,
     *  file:///tmp/x.xml on Linux. */
    private static String toFileUrl(File f) {
        String path = f.getAbsolutePath().replace('\\', '/');
        return "file://" + (path.startsWith("/") ? "" : "/") + path;
    }

    /**
     * Pushes a message to the CAMEO notification log, falling back to stdout
     * when the CAMEO Application is not available (e.g. during unit tests).
     */
    private static void log(String msg) {
        try {
            Application.getInstance().getGUILog().log(msg);
        } catch (Exception ignored) {
            System.out.println(msg);
        }
    }
}
