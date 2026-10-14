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
 * DDSTopicPublisher — writes JSON-encoded samples to the DataWriters defined
 * in the active DDS XML config, and owns the DomainParticipants (shared with
 * DDSTopicSubscriber).
 *
 * Participants are created from the XML on first use, one per participant
 * config name, and cached until the simulation run ends (shutdown()).
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

    // DomainParticipants by participant config name, created on first use.
    private static final ConcurrentHashMap<String, DomainParticipant> PARTICIPANTS =
            new ConcurrentHashMap<>();

    // file:// URL of the config DdsRuntimeConfig generated from the model for
    // the current simulation run — null until it has run.
    private static volatile String generatedXmlUrl = null;

    // True when the generated file was rewritten since RTI last loaded it.
    private static volatile boolean profilesStale = false;

    // RTI's own log messages are routed to CAMEO's Notification window
    // (once). Without this, a failing write() raises RETCODE_ERROR with no
    // explanation: the reason only appears in RTI's log.
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
     * @param participantConfigName e.g. "DomainParticipantLibrary::SAGE"
     * @param dataWriterName        e.g. "Publisher::GatedReportWriter" — must
     *                               exist under that participant in the XML
     * @param jsonPayload            the sample as JSON, matching the writer's type
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
     * The participant for {@code participantConfigName}, created on first
     * use. Shared with DDSTopicSubscriber: one participant holds a Block's
     * writers and readers.
     */
    static DomainParticipant getOrCreateParticipant(String participantConfigName) throws IOException {
        ensureLoggingConfigured();
        String xmlUrl = resolveXmlUrl();
        return PARTICIPANTS.computeIfAbsent(participantConfigName,
                key -> createParticipant(key, xmlUrl));
    }

    /**
     * Creates a participant from the XML config; called at most once per
     * name (via computeIfAbsent).
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
                    "Failed to create DomainParticipant from '" + participantConfigName + "'. "
                            + "Check that a valid RTI Connext DDS license is installed.");
        }
        return participant;
    }

    /**
     * Deletes every participant and its entities. Called when a simulation
     * run ends and when the plugin closes.
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
     * Logs to CAMEO's Notification window, or stdout outside CAMEO.
     */
    private static void log(String msg) {
        try {
            Application.getInstance().getGUILog().log(msg);
        } catch (Exception ignored) {
            System.out.println(msg);
        }
    }
}
