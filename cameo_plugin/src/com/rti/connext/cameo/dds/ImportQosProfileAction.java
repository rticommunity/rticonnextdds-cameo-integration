/*
 * ImportQosProfileAction.java — Tools-menu action that imports an RTI
 * Connext DDS QoS profile XML file into the model as a «DDS_QosProfile»
 * Class inside a «DDS_QosLibrary» package.
 *
 * This is the plugin's FIRST WRITE operation against the model — every
 * other action so far (ScanModelForTopicsAction, GenerateDdsXmlAction) only
 * reads. All model mutation here happens inside a SessionManager session,
 * per NoMagic's OpenAPI contract — see createOrGetQosLibrary()/
 * createQosProfileElement() below.
 *
 * ============================== CONFIDENCE NOTE ==============================
 * The write-path API calls below (SessionManager.createSession/closeSession/
 * cancelSession/isSessionCreated(Project,...), Project.getElementsFactory(),
 * ElementsFactory.createPackageInstance()/createClassInstance(),
 * ModelElementsManager.getInstance().addElement(Element,Element),
 * StereotypesHelper.addStereotype(Element,Stereotype),
 * StereotypesHelper.setStereotypePropertyValue(Element,Stereotype,String,Object),
 * NamedElement.setName(String)) were individually confirmed against the
 * local CAMEO 2024.3 javadoc before writing this file — not guessed.
 *
 * TWO DELIBERATE DEVIATIONS from how this was originally specified, both
 * because the javadoc surfaced something the spec didn't account for:
 *
 * 1. ModelElementsManager.addElement(element, parent) is used to attach a
 *    newly created element to its owner, NOT a raw Element.setOwner() call.
 *    Both exist; addElement() is the session-aware, purpose-built API for
 *    exactly this (its own javadoc cross-references SessionManager) and
 *    throws proper exceptions (ReadOnlyElementException, etc.) — the safer
 *    choice for a model-mutating action.
 *
 * 2. On any failure INSIDE a session this class opened, it calls
 *    SessionManager.cancelSession(project) (rolls back everything done in
 *    that session), not closeSession(project) — the javadoc for
 *    closeSession() says explicitly "All changes made ... will be recorded
 *    in the command history", i.e. COMMITTED, while cancelSession() says
 *    "will be rolled back". Always closing on error would commit a
 *    half-created Package/Class into the user's real model on any failure
 *    partway through — cancelSession() is what actually undoes it.
 *
 * The "Use Existing Package" picker uses ElementPickerUtil (CAMEO's native
 * ElementSelectionDlg) instead of a plain JOptionPane dropdown — see that
 * file for how it's restricted to an exact candidate list. The "Create New
 * Package" name-input dialog is unrelated (plain text input, not an
 * element-selection concern) and is still a plain JOptionPane.
 * ===============================================================================
 */
package com.rti.connext.cameo.dds;

import com.nomagic.magicdraw.actions.MDAction;
import com.nomagic.magicdraw.core.Application;
import com.nomagic.magicdraw.core.Project;
import com.nomagic.magicdraw.openapi.uml.ModelElementsManager;
import com.nomagic.magicdraw.openapi.uml.SessionManager;
import com.nomagic.uml2.ext.jmi.helpers.StereotypesHelper;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Class;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Package;
import com.nomagic.uml2.ext.magicdraw.mdprofiles.Stereotype;
import com.nomagic.uml2.impl.ElementsFactory;

import com.rti.connext.cameo.core.ModelTopicScanner;

import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import java.awt.event.ActionEvent;
import java.io.File;
import java.util.List;

public class ImportQosProfileAction extends MDAction {

    private static final String ID   = "RTI_IMPORT_QOS_PROFILE";
    private static final String NAME = "Import QoS Profile";

    private static final String USE_EXISTING = "Use Existing Package";
    private static final String CREATE_NEW = "Create New Package";

    public ImportQosProfileAction() {
        super(ID, NAME, null, null);
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        try {
            importProfile();
        } catch (Exception ex) {
            log("FAILED: " + ex);
        }
    }

    private void importProfile() {
        Project project = Application.getInstance().getProject();
        if (project == null) {
            log("No project is open.");
            return;
        }

        Package qosLibrary = chooseOrCreateQosLibrary(project);
        if (qosLibrary == null) {
            log("Cancelled.");
            return;
        }

        File xmlFile = chooseXmlFile();
        if (xmlFile == null) {
            log("Cancelled.");
            return;
        }

        ParsedQosProfile parsed;
        try {
            parsed = parseQosProfile(xmlFile);
        } catch (Exception ex) {
            log("FAILED to parse '" + xmlFile.getAbsolutePath() + "': " + ex);
            return;
        }
        if (parsed == null) {
            log("No <qos_profile> element found in '" + xmlFile.getAbsolutePath() + "'.");
            return;
        }
        if (parsed.profileName == null || parsed.profileName.isEmpty()) {
            String fileName = xmlFile.getName();
            int dot = fileName.lastIndexOf('.');
            parsed.profileName = dot > 0 ? fileName.substring(0, dot) : fileName;
            log("<qos_profile> had no 'name' attribute — using filename '" + parsed.profileName + "' instead.");
        }

        createQosProfileElement(project, qosLibrary, parsed);
    }

    // -------------------------------------------------------------------------
    // «DDS_QosLibrary» package: choose existing or create new
    // -------------------------------------------------------------------------

    private Package chooseOrCreateQosLibrary(Project project) {
        List<Package> existing = ModelTopicScanner.findQosLibraryPackages(project.getPrimaryModel());

        String[] options = existing.isEmpty()
                ? new String[]{CREATE_NEW}
                : new String[]{USE_EXISTING, CREATE_NEW};
        int choiceIndex = JOptionPane.showOptionDialog(null,
                "Choose where to import this QoS profile:", "Import QoS Profile",
                JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, options, options[0]);
        if (choiceIndex < 0) {
            return null; // dialog dismissed
        }

        if (USE_EXISTING.equals(options[choiceIndex])) {
            return choosePackage(project, existing);
        }
        return createQosLibraryPackage(project);
    }

    /** Native ElementSelectionDlg picker (see ElementPickerUtil) instead of a plain JOptionPane dropdown. */
    private Package choosePackage(Project project, List<Package> packages) {
        return ElementPickerUtil.pickOne(project.getPrimaryModel(), packages,
                "Choose a «DDS_QosLibrary» package:", false, null);
    }

    private Package createQosLibraryPackage(Project project) {
        String name = JOptionPane.showInputDialog(null,
                "Name for the new «DDS_QosLibrary» package:", "Import QoS Profile",
                JOptionPane.QUESTION_MESSAGE);
        if (name == null || name.trim().isEmpty()) {
            return null;
        }
        String trimmedName = name.trim();

        Stereotype qosLibraryStereotype = ModelTopicScanner.resolveStereotype(
                project.getPrimaryModel(), ModelTopicScanner.DDS_PROFILE_NAME, ModelTopicScanner.QOS_LIBRARY_STEREOTYPE_NAME);
        if (qosLibraryStereotype == null) {
            log("Could not resolve «DDS_QosLibrary» stereotype — has it been added to DDS_Profile yet?");
            return null;
        }

        SessionManager sessionManager = SessionManager.getInstance();
        boolean ownsSession = !sessionManager.isSessionCreated(project);
        if (ownsSession) {
            sessionManager.createSession(project, "Create DDS_QosLibrary Package");
        }
        try {
            ElementsFactory factory = project.getElementsFactory();
            Package qosLibraryPackage = factory.createPackageInstance();
            qosLibraryPackage.setName(trimmedName);
            ModelElementsManager.getInstance().addElement(qosLibraryPackage, project.getPrimaryModel());
            StereotypesHelper.addStereotype(qosLibraryPackage, qosLibraryStereotype);

            if (ownsSession) {
                sessionManager.closeSession(project);
            }
            log("Created «DDS_QosLibrary» package '" + trimmedName + "'.");
            return qosLibraryPackage;
        } catch (Exception ex) {
            if (ownsSession && sessionManager.isSessionCreated(project)) {
                sessionManager.cancelSession(project);
            }
            log("FAILED to create «DDS_QosLibrary» package: " + ex);
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // QoS profile XML file selection + parsing
    // -------------------------------------------------------------------------

    private File chooseXmlFile() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Select QoS Profile XML");
        chooser.setFileFilter(new FileNameExtensionFilter("XML files", "xml"));
        int result = chooser.showOpenDialog(null);
        return result == JFileChooser.APPROVE_OPTION ? chooser.getSelectedFile() : null;
    }

    /** Plain holder for what was pulled out of the <qos_profile> element — any field may be null/unset except profileName (backfilled from the filename if missing). */
    private static final class ParsedQosProfile {
        String profileName;
        Boolean isDefault;
        String participantName;
        Integer historyDepth;
    }

    private ParsedQosProfile parseQosProfile(File file) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        // No external entities in a QoS profile file — disallow DOCTYPE
        // declarations outright rather than trying to selectively permit them.
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        DocumentBuilder builder = factory.newDocumentBuilder();
        org.w3c.dom.Document doc = builder.parse(file);

        org.w3c.dom.NodeList profiles = doc.getElementsByTagName("qos_profile");
        if (profiles.getLength() == 0) {
            return null;
        }
        org.w3c.dom.Element profileEl = (org.w3c.dom.Element) profiles.item(0);

        ParsedQosProfile parsed = new ParsedQosProfile();

        String nameAttr = profileEl.getAttribute("name");
        parsed.profileName = nameAttr.isEmpty() ? null : nameAttr;

        String isDefaultAttr = profileEl.getAttribute("is_default_qos");
        parsed.isDefault = isDefaultAttr.isEmpty() ? null : "true".equalsIgnoreCase(isDefaultAttr.trim());

        parsed.participantName = firstNestedText(profileEl, "participant_name", "name");

        String depthText = firstNestedText(profileEl, "history", "depth");
        if (depthText != null) {
            try {
                parsed.historyDepth = Integer.valueOf(depthText.trim());
            } catch (NumberFormatException ignored) {
                parsed.historyDepth = null;
            }
        }

        log("Parsed from '" + file.getName() + "': name='" + nameAttr + "' is_default_qos='" + isDefaultAttr
                + "' participant_name='" + parsed.participantName + "' history_depth='" + depthText + "'");
        return parsed;
    }

    /** Walks a fixed nested-tag path (e.g. "history", "depth") via getElementsByTagName() at each level, returning trimmed text content, or null the moment any level is missing. */
    private static String firstNestedText(org.w3c.dom.Element root, String... tagPath) {
        org.w3c.dom.Element current = root;
        for (String tag : tagPath) {
            org.w3c.dom.NodeList matches = current.getElementsByTagName(tag);
            if (matches.getLength() == 0) {
                return null;
            }
            current = (org.w3c.dom.Element) matches.item(0);
        }
        String text = current.getTextContent();
        return text != null ? text.trim() : null;
    }

    // -------------------------------------------------------------------------
    // «DDS_QosProfile» element creation
    // -------------------------------------------------------------------------

    private void createQosProfileElement(Project project, Package qosLibrary, ParsedQosProfile parsed) {
        Stereotype qosProfileStereotype = ModelTopicScanner.resolveStereotype(
                project.getPrimaryModel(), ModelTopicScanner.DDS_PROFILE_NAME, ModelTopicScanner.QOS_PROFILE_STEREOTYPE_NAME);
        if (qosProfileStereotype == null) {
            log("Could not resolve «DDS_QosProfile» stereotype — has it been added to DDS_Profile yet?");
            return;
        }

        SessionManager sessionManager = SessionManager.getInstance();
        boolean ownsSession = !sessionManager.isSessionCreated(project);
        if (ownsSession) {
            sessionManager.createSession(project, "Import QoS Profile");
        }
        try {
            ElementsFactory factory = project.getElementsFactory();
            Class qosProfileElement = factory.createClassInstance();
            qosProfileElement.setName(parsed.profileName);
            ModelElementsManager.getInstance().addElement(qosProfileElement, qosLibrary);
            StereotypesHelper.addStereotype(qosProfileElement, qosProfileStereotype);

            if (parsed.participantName != null) {
                StereotypesHelper.setStereotypePropertyValue(qosProfileElement, qosProfileStereotype,
                        ModelTopicScanner.QOS_PARTICIPANT_NAME_TAG, parsed.participantName);
            }
            if (parsed.historyDepth != null) {
                StereotypesHelper.setStereotypePropertyValue(qosProfileElement, qosProfileStereotype,
                        ModelTopicScanner.QOS_HISTORY_DEPTH_TAG, parsed.historyDepth);
            }
            if (parsed.isDefault != null) {
                StereotypesHelper.setStereotypePropertyValue(qosProfileElement, qosProfileStereotype,
                        ModelTopicScanner.QOS_IS_DEFAULT_TAG, parsed.isDefault);
            }

            if (ownsSession) {
                sessionManager.closeSession(project);
            }
            log("Created «DDS_QosProfile» element '" + parsed.profileName + "' in package '" + qosLibrary.getName() + "'.");
        } catch (Exception ex) {
            if (ownsSession && sessionManager.isSessionCreated(project)) {
                sessionManager.cancelSession(project);
            }
            log("FAILED to create «DDS_QosProfile» element: " + ex);
        }
    }

    private static void log(String msg) {
        try {
            Application.getInstance().getGUILog().log("[Import QoS Profile] " + msg);
        } catch (Exception ignored) {
            System.out.println("[Import QoS Profile] " + msg);
        }
    }
}
