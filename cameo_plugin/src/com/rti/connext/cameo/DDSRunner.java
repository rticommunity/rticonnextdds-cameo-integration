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
 * DDSRunner.java — thread-safe DDS publisher and subscriber adapted from
 * java_dynamicdata/ShapeTypePublisher.java and ShapeTypeSubscriber.java.
 *
 * Key design decisions vs. the standalone examples:
 *   - No System.exit() or shutdown hooks; CAMEO owns the JVM lifecycle.
 *   - ShapeType.xml is extracted from the plugin JAR (resources/) to a temp
 *     file at first use, so the plugin is self-contained.
 *   - Publisher and subscriber each run in a dedicated daemon thread; they are
 *     stopped cleanly when the plugin closes (Plugin.close() → DDSRunner.stopAll()).
 *   - The DomainParticipantFactory QoS profile list is set fresh for each
 *     start/stop cycle to avoid stale state between runs.
 */
package com.rti.connext.cameo;

import com.nomagic.magicdraw.core.Application;

import com.rti.dds.domain.DomainParticipant;
import com.rti.dds.domain.DomainParticipantFactory;
import com.rti.dds.domain.DomainParticipantFactoryQos;
import com.rti.dds.dynamicdata.DynamicData;
import com.rti.dds.dynamicdata.DynamicDataReader;
import com.rti.dds.dynamicdata.DynamicDataSeq;
import com.rti.dds.dynamicdata.DynamicDataWriter;
import com.rti.dds.infrastructure.ConditionSeq;
import com.rti.dds.infrastructure.Duration_t;
import com.rti.dds.infrastructure.GuardCondition;
import com.rti.dds.infrastructure.InstanceHandle_t;
import com.rti.dds.infrastructure.ResourceLimitsQosPolicy;
import com.rti.dds.infrastructure.RETCODE_TIMEOUT;
import com.rti.dds.infrastructure.StatusCondition;
import com.rti.dds.infrastructure.StatusKind;
import com.rti.dds.infrastructure.WaitSet;
import com.rti.dds.subscription.InstanceStateKind;
import com.rti.dds.subscription.SampleInfo;
import com.rti.dds.subscription.SampleInfoSeq;
import com.rti.dds.subscription.SampleStateKind;
import com.rti.dds.subscription.ViewStateKind;
import com.rti.dds.topic.PrintFormatKind;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.Arrays;

public class DDSRunner {

    private static volatile Thread  publisherThread   = null;
    private static volatile Thread  subscriberThread  = null;
    private static volatile boolean publisherRunning  = false;
    private static volatile boolean subscriberRunning = false;

    // Cached file:/// URL for the DDS XML config — resolved on first use.
    private static volatile String cachedXmlUrl = null;

    // -------------------------------------------------------------------------
    // Shared helpers
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
                    DDSRunner.class.getProtectionDomain().getCodeSource();
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
    static void log(String msg) {
        try {
            Application.getInstance().getGUILog().log(msg);
        } catch (Exception ignored) {
            System.out.println(msg);
        }
    }

    // -------------------------------------------------------------------------
    // Publisher
    // -------------------------------------------------------------------------

    public static boolean isPublisherRunning() {
        return publisherRunning
                && publisherThread != null
                && publisherThread.isAlive();
    }

    public static void startPublisher() throws IOException {
        if (isPublisherRunning()) return;
        final String xmlUrl = resolveXmlUrl();
        publisherRunning  = true;
        publisherThread   = new Thread(() -> runPublisher(xmlUrl),
                                       "RTIConnext-Publisher");
        publisherThread.setDaemon(true);
        publisherThread.start();
    }

    public static void stopPublisher() {
        publisherRunning = false;
        if (publisherThread != null) {
            publisherThread.interrupt();
        }
    }

    /**
     * Publisher loop: writes RED squares on the Square topic every 500 ms.
     * Logic mirrors java_dynamicdata/ShapeTypePublisher.java, adapted for
     * in-process threading (no shutdown hooks, no System.exit).
     */
    private static void runPublisher(String xmlUrl) {
        DomainParticipant  participant = null;
        DynamicDataWriter  writer      = null;
        DynamicData        sample      = null;

        try {
            // ---- 1. Set the XML profile URL --------------------------------
            DomainParticipantFactoryQos factoryQos = new DomainParticipantFactoryQos();
            DomainParticipantFactory.get_instance().get_qos(factoryQos);
            factoryQos.profile.url_profile.clear();
            factoryQos.profile.url_profile.add(xmlUrl);
            DomainParticipantFactory.get_instance().set_qos(factoryQos);

            // ---- 2. Create participant from XML config ----------------------
            participant = DomainParticipantFactory.get_instance()
                    .create_participant_from_config(
                            "DomainParticipantLibrary::SquareParticipant");
            if (participant == null) {
                log("[RTI Connext] Publisher: failed to create DomainParticipant "
                        + "from 'DomainParticipantLibrary::SquareParticipant'.");
                return;
            }

            // ---- 3. Look up the DataWriter ---------------------------------
            writer = (DynamicDataWriter) participant
                    .lookup_datawriter_by_name("Publisher::SquareWriter");
            if (writer == null) {
                log("[RTI Connext] Publisher: DataWriter 'Publisher::SquareWriter' not found.");
                return;
            }

            // ---- 4. Allocate a DynamicData sample --------------------------
            sample = writer.create_data(DynamicData.PROPERTY_DEFAULT);
            if (sample == null) {
                log("[RTI Connext] Publisher: failed to create DynamicData sample.");
                return;
            }

            // ---- 5. Write loop (500 ms period) -----------------------------
            int x = 0, y = 0;
            while (publisherRunning && !Thread.currentThread().isInterrupted()) {
                x = (x + 5) % 250;
                y = (y + 5) % 250;

                String json = String.format(
                        "{\"color\": \"RED\", \"x\": %d, \"y\": %d, \"shapesize\": 30}",
                        x, y);

                // Populate DynamicData from the JSON string (same approach as
                // the standalone example in java_dynamicdata/).
                sample.from_string(json, PrintFormatKind.JSON_PRINT_FORMAT);
                writer.write(sample, InstanceHandle_t.HANDLE_NIL);

                log("[RTI Connext] Published: " + json);
                Thread.sleep(500);
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log("[RTI Connext] Publisher error: " + e.getMessage());
        } finally {
            if (participant != null) {
                participant.delete_contained_entities();
                DomainParticipantFactory.get_instance().delete_participant(participant);
            }
            publisherRunning = false;
            log("[RTI Connext] Publisher stopped.");
        }
    }

    // -------------------------------------------------------------------------
    // Subscriber
    // -------------------------------------------------------------------------

    public static boolean isSubscriberRunning() {
        return subscriberRunning
                && subscriberThread != null
                && subscriberThread.isAlive();
    }

    public static void startSubscriber() throws IOException {
        if (isSubscriberRunning()) return;
        final String xmlUrl = resolveXmlUrl();
        subscriberRunning = true;
        subscriberThread  = new Thread(() -> runSubscriber(xmlUrl),
                                       "RTIConnext-Subscriber");
        subscriberThread.setDaemon(true);
        subscriberThread.start();
    }

    public static void stopSubscriber() {
        subscriberRunning = false;
        if (subscriberThread != null) {
            subscriberThread.interrupt();
        }
    }

    /**
     * Subscriber loop: receives samples on the Square topic and prints them
     * to the CAMEO notification log.
     * Logic mirrors java_dynamicdata/ShapeTypeSubscriber.java, adapted for
     * in-process threading (WaitSet with 1-second timeout so the loop can
     * check the {@code subscriberRunning} flag without blocking indefinitely).
     */
    private static void runSubscriber(String xmlUrl) {
        DomainParticipant participant = null;

        try {
            // ---- 1. Set the XML profile URL --------------------------------
            DomainParticipantFactoryQos factoryQos = new DomainParticipantFactoryQos();
            DomainParticipantFactory.get_instance().get_qos(factoryQos);
            factoryQos.profile.url_profile.clear();
            factoryQos.profile.url_profile.add(xmlUrl);
            DomainParticipantFactory.get_instance().set_qos(factoryQos);

            // ---- 2. Create participant from XML config ----------------------
            participant = DomainParticipantFactory.get_instance()
                    .create_participant_from_config(
                            "DomainParticipantLibrary::SquareParticipant");
            if (participant == null) {
                log("[RTI Connext] Subscriber: failed to create DomainParticipant.");
                return;
            }

            // ---- 3. Look up the DataReader ---------------------------------
            DynamicDataReader reader = (DynamicDataReader) participant
                    .lookup_datareader_by_name("Subscriber::SquareReader");
            if (reader == null) {
                log("[RTI Connext] Subscriber: DataReader 'Subscriber::SquareReader' not found.");
                return;
            }

            // ---- 4. Set up WaitSet with StatusCondition + GuardCondition ---
            WaitSet       waitSet        = new WaitSet();
            GuardCondition stopGuard     = new GuardCondition();
            StatusCondition statusCond   = reader.get_statuscondition();
            statusCond.set_enabled_statuses(StatusKind.DATA_AVAILABLE_STATUS);
            waitSet.attach_condition(statusCond);
            waitSet.attach_condition(stopGuard);

            DynamicDataSeq dataSeq        = new DynamicDataSeq();
            SampleInfoSeq  infoSeq        = new SampleInfoSeq();
            ConditionSeq   activeConds    = new ConditionSeq();
            Duration_t     waitTimeout    = new Duration_t(1, 0); // 1 s

            // ---- 5. Receive loop -------------------------------------------
            while (subscriberRunning && !Thread.currentThread().isInterrupted()) {
                try {
                    waitSet.wait(activeConds, waitTimeout);
                } catch (RETCODE_TIMEOUT ignored) {
                    // Normal timeout — check subscriberRunning and loop again
                    continue;
                }

                if (!activeConds.contains(statusCond)) {
                    continue; // Woken by stopGuard or spuriously
                }

                try {
                    reader.take(
                            dataSeq, infoSeq,
                            ResourceLimitsQosPolicy.LENGTH_UNLIMITED,
                            SampleStateKind.ANY_SAMPLE_STATE,
                            ViewStateKind.ANY_VIEW_STATE,
                            InstanceStateKind.ANY_INSTANCE_STATE);

                    for (int i = 0; i < dataSeq.size(); i++) {
                        SampleInfo info = (SampleInfo) infoSeq.get(i);
                        if (!info.valid_data) continue;

                        DynamicData s = (DynamicData) dataSeq.get(i);
                        String color   = s.get_string("color",     DynamicData.MEMBER_ID_UNSPECIFIED);
                        int    x       = s.get_int("x",            DynamicData.MEMBER_ID_UNSPECIFIED);
                        int    y       = s.get_int("y",            DynamicData.MEMBER_ID_UNSPECIFIED);
                        int    size    = s.get_int("shapesize",     DynamicData.MEMBER_ID_UNSPECIFIED);
                        log(String.format(
                                "[RTI Connext] Received: color=%s  x=%3d  y=%3d  shapesize=%d",
                                color, x, y, size));
                    }
                } finally {
                    reader.return_loan(dataSeq, infoSeq);
                }
            }

        } catch (Exception e) {
            log("[RTI Connext] Subscriber error: " + e.getMessage());
        } finally {
            if (participant != null) {
                participant.delete_contained_entities();
                DomainParticipantFactory.get_instance().delete_participant(participant);
            }
            subscriberRunning = false;
            log("[RTI Connext] Subscriber stopped.");
        }
    }

    // -------------------------------------------------------------------------
    // Combined stop (called from RTIConnextPlugin.close())
    // -------------------------------------------------------------------------

    public static void stopAll() {
        stopPublisher();
        stopSubscriber();
    }
}
