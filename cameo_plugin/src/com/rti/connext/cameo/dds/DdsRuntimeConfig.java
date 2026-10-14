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
 * DdsRuntimeConfig — generates the DDS XML configuration from the model
 * every time a simulation starts, so the configuration RTI runs can never be
 * out of date with the model and there is no hand-managed XML file to pick.
 *
 * Called once per run from RegisterDdsEngineListener.groovy. Produces:
 *   - the TopicModel for this run (scan of the project's «DDS_Domain»),
 *     shared with DdsEngineListener and DdsInboundInjector so the XML and the
 *     runtime pub/sub always come from the same scan;
 *   - <java.io.tmpdir>/rti_cameo/<Project>_dds_generated.xml, handed to
 *     DDSTopicPublisher as the config to load.
 *
 * An explicit -Dcom.rti.connext.cameo.xmlConfig=<path> still takes
 * precedence (see DDSTopicPublisher.resolveXmlUrl()), for a hand-tuned XML.
 * The "Generate DDS XML" menu action remains as an export.
 *
 * v1 limitation: exactly one «DDS_Domain» package per project.
 */
package com.rti.connext.cameo.dds;

import com.nomagic.magicdraw.core.Application;
import com.nomagic.magicdraw.core.Project;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Class;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Element;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Package;

import com.rti.connext.cameo.core.ModelTopicScanner;
import com.rti.connext.cameo.core.TopicModel;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

public final class DdsRuntimeConfig {

    private static volatile TopicModel currentModel;

    private DdsRuntimeConfig() {
    }

    /**
     * Scans the project's «DDS_Domain», generates the DDS XML from it and
     * makes it the config DDSTopicPublisher loads. Logs and returns false
     * (leaving no generated config) if the model can't produce one.
     */
    public static boolean prepare() {
        currentModel = null;
        Project project = Application.getInstance().getProject();
        Element root = project == null ? null : project.getPrimaryModel();
        if (root == null) {
            log("[RTI Connext] ERROR: no open project -- cannot generate the DDS config.");
            return false;
        }

        List<Package> domains = ModelTopicScanner.findDomainPackages(root);
        if (domains.size() != 1) {
            List<String> names = new ArrayList<>();
            for (Package domain : domains) {
                names.add(domain.getName());
            }
            log("[RTI Connext] ERROR: found " + domains.size() + " «DDS_Domain» packages " + names
                    + " -- exactly one is supported when generating the DDS config at simulation start.");
            return false;
        }
        Package domain = domains.get(0);

        Integer domainId = ModelTopicScanner.getDomainId(domain);
        if (domainId == null) {
            log("[RTI Connext] ERROR: «DDS_Domain» '" + domain.getName()
                    + "' has no Integer 'domain_id' tagged value -- cannot generate the DDS config.");
            return false;
        }

        TopicModel model = ModelTopicScanner.scan(domain);
        String xml = DdsXmlGenerator.generate(model, domainId, chooseQosProfile(root));

        File file = new File(new File(System.getProperty("java.io.tmpdir"), "rti_cameo"),
                safeFileName(project.getName()) + "_dds_generated.xml");
        try {
            Files.createDirectories(file.getParentFile().toPath());
            Files.write(file.toPath(), xml.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            log("[RTI Connext] ERROR: could not write the generated DDS config to "
                    + file.getAbsolutePath() + " -- " + ex);
            return false;
        }

        currentModel = model;
        DDSTopicPublisher.useGeneratedConfig(file);
        log("[RTI Connext] Generated DDS config from the model (domain '" + domain.getName()
                + "', id " + domainId + "): " + file.getAbsolutePath());
        return true;
    }

    /** The scan the current run's config was generated from, or null if
     *  prepare() hasn't succeeded this run. */
    public static TopicModel currentModel() {
        return currentModel;
    }

    /**
     * No dialog in the middle of a simulation start: the «DDS_QosProfile»
     * marked is_default, else the only one, else none.
     */
    private static ModelTopicScanner.QosProfileInfo chooseQosProfile(Element root) {
        List<Class> profiles = ModelTopicScanner.findQosProfiles(root);
        for (Class profile : profiles) {
            ModelTopicScanner.QosProfileInfo info = ModelTopicScanner.readQosProfileInfo(profile);
            if (info.isDefault) {
                return info;
            }
        }
        if (profiles.size() == 1) {
            return ModelTopicScanner.readQosProfileInfo(profiles.get(0));
        }
        if (profiles.size() > 1) {
            log("[RTI Connext] WARNING: " + profiles.size() + " «DDS_QosProfile» elements and none marked"
                    + " is_default -- generating the DDS config without a QoS profile.");
        }
        return null;
    }

    private static String safeFileName(String name) {
        return name == null || name.isEmpty() ? "project" : name.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static void log(String msg) {
        try {
            Application.getInstance().getGUILog().log(msg);
        } catch (Exception ignored) {
            System.out.println(msg);
        }
    }
}
