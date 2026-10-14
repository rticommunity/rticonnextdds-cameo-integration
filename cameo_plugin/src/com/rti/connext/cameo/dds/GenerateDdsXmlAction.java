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
 * GenerateDdsXmlAction — Tools-menu action that exports the DDS XML
 * configuration for a «DDS_Domain» to a file of your choice, optionally with
 * a «DDS_QosProfile» (e.g. to hand the interface to another team). Not needed
 * for simulation: DdsRuntimeConfig generates the same XML at every run.
 */
package com.rti.connext.cameo.dds;

import com.nomagic.magicdraw.actions.MDAction;
import com.nomagic.magicdraw.core.Application;
import com.nomagic.magicdraw.core.Project;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Class;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Element;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Package;

import com.rti.connext.cameo.core.ModelTopicScanner;
import com.rti.connext.cameo.core.TopicModel;

import javax.swing.JFileChooser;
import java.awt.event.ActionEvent;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class GenerateDdsXmlAction extends MDAction {

    private static final String ID   = "RTI_GENERATE_DDS_XML";
    private static final String NAME = "Generate DDS XML";

    public GenerateDdsXmlAction() {
        super(ID, NAME, null, null);
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        try {
            generate();
        } catch (Exception ex) {
            log("[Generate DDS XML] FAILED: " + ex);
        }
    }

    private void generate() {
        Project project = Application.getInstance().getProject();
        if (project == null) {
            log("[Generate DDS XML] No project is open.");
            return;
        }

        Element root = project.getPrimaryModel();
        if (root == null) {
            log("[Generate DDS XML] Could not find the project's primary model.");
            return;
        }

        List<Package> domainPackages = ModelTopicScanner.findDomainPackages(root);
        if (domainPackages.isEmpty()) {
            log("[Generate DDS XML] No package stereotyped «DDS_Domain» was found in the model.");
            return;
        }

        Package domainPackage = domainPackages.size() == 1
                ? domainPackages.get(0)
                : chooseDomainPackage(root, domainPackages);
        if (domainPackage == null) {
            log("[Generate DDS XML] Cancelled.");
            return;
        }

        Integer domainId = ModelTopicScanner.getDomainId(domainPackage);
        if (domainId == null) {
            log("[Generate DDS XML] Package '" + domainPackage.getName()
                    + "' has no Integer 'domain_id' tagged value set — cannot generate XML.");
            return;
        }

        List<Class> qosProfiles = ModelTopicScanner.findQosProfiles(root);
        ModelTopicScanner.QosProfileInfo qosProfile = qosProfiles.isEmpty() ? null : chooseQosProfile(root, qosProfiles);

        TopicModel model = ModelTopicScanner.scan(domainPackage);
        String xml = DdsXmlGenerator.generate(model, domainId, qosProfile);

        File file = chooseSaveFile(domainPackage.getName());
        if (file == null) {
            log("[Generate DDS XML] Cancelled.");
            return;
        }

        try (Writer writer = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)) {
            writer.write(xml);
        } catch (Exception ioEx) {
            log("[Generate DDS XML] FAILED to write '" + file.getAbsolutePath() + "': " + ioEx);
            return;
        }

        log("[Generate DDS XML] Wrote " + file.getAbsolutePath());
    }

    private Package chooseDomainPackage(Element root, List<Package> domainPackages) {
        return ElementPickerUtil.pickOne(root, domainPackages,
                "Choose a «DDS_Domain» package:", false, null);
    }

    /**
     * Asks which «DDS_QosProfile» to apply (or none); only called when at
     * least one exists. Pre-selects the profile marked is_default.
     */
    private ModelTopicScanner.QosProfileInfo chooseQosProfile(Element root, List<Class> qosProfiles) {
        Class defaultProfile = null;
        for (Class profile : qosProfiles) {
            if (ModelTopicScanner.readQosProfileInfo(profile).isDefault) {
                defaultProfile = profile;
                break;
            }
        }

        Class chosen = ElementPickerUtil.pickOne(root, qosProfiles,
                "Choose a QoS profile to apply (or None):", true, defaultProfile);
        return chosen == null ? null : ModelTopicScanner.readQosProfileInfo(chosen);
    }

    private File chooseSaveFile(String domainPackageName) {
        JFileChooser chooser = new JFileChooser();
        chooser.setSelectedFile(new File(domainPackageName + ".xml"));
        int result = chooser.showSaveDialog(null);
        return result == JFileChooser.APPROVE_OPTION ? chooser.getSelectedFile() : null;
    }

    private static void log(String msg) {
        try {
            Application.getInstance().getGUILog().log(msg);
        } catch (Exception ignored) {
            System.out.println(msg);
        }
    }
}
