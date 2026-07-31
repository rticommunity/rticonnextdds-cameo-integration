/*
 * ScanModelForTopicsAction.java — Tools-menu action that runs
 * ModelDdsScanner against the currently open project and logs every
 * discovered Topic and StructType to CAMEO's notification log.
 *
 * This is the fastest way to see the scanner's output against a real model
 * without yet building DdsXmlGenerator or wiring anything into simulation —
 * pure "does the scan find what I expect" feedback.
 *
 * CONFIDENCE NOTE: Application.getInstance().getProject() and
 * Project.getPrimaryModel() are very standard, widely-used MagicDraw OpenAPI
 * calls (Project itself is already used in a real, working NoMagic example
 * we saw earlier — ExampleAction.java calls Project.getProject(element)) —
 * high confidence, but getPrimaryModel() specifically wasn't individually
 * re-verified against your jars the way the model interfaces were. If build
 * fails on that one line, that's the line to check against your local
 * <CAMEO_HOME>\openapi\docs.
 */
package com.rti.connext.cameo.model;

import com.nomagic.uml2.ext.jmi.helpers.StereotypesHelper;
import com.nomagic.magicdraw.actions.MDAction;
import com.nomagic.magicdraw.core.Application;
import com.nomagic.magicdraw.core.Project;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Element;

import java.awt.event.ActionEvent;
import java.util.Map;

public class ScanModelForTopicsAction extends MDAction {

    private static final String ID   = "RTI_SCAN_MODEL_DDS_TOPICS";
    private static final String NAME = "Scan Model for DDS Topics";

    public ScanModelForTopicsAction() {
        super(ID, NAME, null, null);
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        Project project = Application.getInstance().getProject();
        if (project == null) {
            log("[DDS Scan] No project is open.");
            return;
        }

        Element root = project.getPrimaryModel();
        if (root == null) {
            log("[DDS Scan] Could not find the project's primary model.");
            return;
        }

        TopicModel result;
        try {
            result = ModelDdsScanner.scan(root);
        } catch (Exception ex) {
            log("[DDS Scan] FAILED: " + ex);
            return;
        }

        log("[DDS Scan] Found " + result.topics.size() + " topic(s), "
                + result.types.size() + " type(s).");

        for (TopicModel.Topic topic : result.topics.values()) {
            log("[DDS Scan] Topic '" + topic.topicName + "' (type: " + topic.typeName + ")"
                    + " — publishers: " + topic.publisherBlockNames
                    + ", subscribers: " + topic.subscriberBlockNames);
        }

        for (Map.Entry<String, TopicModel.StructType> entry : result.types.entrySet()) {
            TopicModel.StructType struct = entry.getValue();
            StringBuilder fieldsDesc = new StringBuilder();
            for (TopicModel.Field field : struct.fields) {
                if (fieldsDesc.length() > 0) fieldsDesc.append(", ");
                if (field.isNested()) {
                    fieldsDesc.append(field.name).append(": ").append(field.nestedStructName).append(" (nested)");
                } else {
                    fieldsDesc.append(field.name).append(": ").append(field.primitiveType);
                }
            }
            log("[DDS Scan] Type '" + struct.name + "' fields: [" + fieldsDesc + "]");
        }

        if (result.topics.isEmpty()) {
            log("[DDS Scan] No topics found — check that your Signal has the "
                    + "\u00abTopic\u00bb stereotype applied, and its 'data' "
                    + "attribute is typed by a \u00abBlock\u00bb.");
        }
    }

    private static void log(String msg) {
        try {
            Application.getInstance().getGUILog().log(msg);
        } catch (Exception ignored) {
            System.out.println(msg);
        }
    }
}
