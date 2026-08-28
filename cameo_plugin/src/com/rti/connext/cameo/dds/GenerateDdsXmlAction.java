/*
 * GenerateDdsXmlAction.java — Tools-menu action that finds the model's
 * «DDS_Domain» package(s), scans the chosen one for «DDS_Topic» Signals via
 * ModelTopicScanner, generates the RTI Connext DDS XML Application Creation
 * config via DdsXmlGenerator, and writes it to a file the user picks.
 *
 * Also offers an optional «DDS_QosProfile» to apply (see chooseQosProfile())
 * — imported separately via ImportQosProfileAction, which is this plugin's
 * only WRITE path; this action stays read-only, same as before.
 *
 * Both pickers below use ElementPickerUtil (CAMEO's native
 * ElementSelectionDlg) instead of a plain JOptionPane dropdown — see that
 * file for how it's restricted to an exact candidate list.
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
     * Picker shown only when at least one «DDS_QosProfile» exists anywhere
     * in the project (zero found = silent, no dialog — must stay the
     * default zero-friction path). Uses ElementPickerUtil's native "None"
     * support (allowNone=true) rather than a synthetic list entry;
     * pre-selects whichever profile has is_default=true if exactly one is
     * found that way, otherwise nothing is pre-selected.
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
