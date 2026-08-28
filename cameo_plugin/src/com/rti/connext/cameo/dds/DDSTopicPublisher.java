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
import java.net.URI;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;

public final class DDSTopicPublisher {

    // Cached DomainParticipants, keyed by participant config name — created
    // lazily on first use, reused across all subsequent publishOnce() calls.
    private static final ConcurrentHashMap<String, DomainParticipant> PARTICIPANTS =
            new ConcurrentHashMap<>();

    // Cached file:/// URL for the DDS XML config — resolved on first use.
    private static volatile String cachedXmlUrl = null;

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

        sample.from_string(jsonPayload, PrintFormatKind.JSON_PRINT_FORMAT);
        writer.write(sample, InstanceHandle_t.HANDLE_NIL);
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
    // XML config resolution (moved here from the now-deleted DDSRunner.java)
    // -------------------------------------------------------------------------

    /**
     * Resolves the DDS XML Application Creation config URL.
     *
     * Search order:
     *  1. System property  -Dcom.rti.connext.cameo.xmlConfig=&lt;path&gt;
     *  2. First *.xml file (alphabetically) in &lt;plugin-dir&gt;/resources/,
     *     where plugin-dir is the parent of the lib/ folder that contains
     *     this JAR.  This lets users drop in any XML config without rebuilding.
     *
     * Returns a file:///... URL string accepted by DomainParticipantFactory.
     */
    private static synchronized String resolveXmlUrl() throws IOException {
        if (cachedXmlUrl != null) return cachedXmlUrl;

        // --- 1. System property override ---
        String sysProp = System.getProperty("com.rti.connext.cameo.xmlConfig");
        if (sysProp != null) {
            File f = new File(sysProp);
            if (!f.isFile()) {
                throw new IOException(
                        "XML config not found at -Dcom.rti.connext.cameo.xmlConfig: " + sysProp);
            }
            cachedXmlUrl = toFileUrl(f);
            log("[RTI Connext] Using XML config (property): " + f.getAbsolutePath());
            return cachedXmlUrl;
        }

        // --- 2. <plugin-dir>/resources/*.xml ---
        try {
            java.security.CodeSource cs =
                    DDSTopicPublisher.class.getProtectionDomain().getCodeSource();
            if (cs != null) {
                URI jarUri   = cs.getLocation().toURI();
                File libDir  = new File(jarUri).getParentFile();          // …/lib/
                File resDir  = new File(libDir.getParentFile(), "resources"); // …/resources/
                File[] xmlFiles = resDir.listFiles(
                        f -> f.isFile() && f.getName().endsWith(".xml"));
                if (xmlFiles != null && xmlFiles.length > 0) {
                    Arrays.sort(xmlFiles);
                    cachedXmlUrl = toFileUrl(xmlFiles[0]);
                    log("[RTI Connext] Using XML config: " + xmlFiles[0].getAbsolutePath());
                    return cachedXmlUrl;
                }
                throw new IOException(
                        "No *.xml file found in " + resDir.getAbsolutePath());
            }
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(
                    "Failed to locate plugin resources directory: " + e.getMessage(), e);
        }

        throw new IOException(
                "No DDS XML config found. Place your XML Application Creation file in "
                + "<plugin-dir>/resources/ or set "
                + "-Dcom.rti.connext.cameo.xmlConfig=<path> in CAMEO's JVM options.");
    }

    private static String toFileUrl(File f) {
        return "file:///" + f.getAbsolutePath().replace('\\', '/');
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
