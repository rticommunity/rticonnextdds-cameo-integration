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
 * ScanModelForTopicsAction — Tools-menu action that scans the open project
 * and logs every topic and type found: a quick way to check that a model is
 * read the way you expect.
 */
package com.rti.connext.cameo.core;

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
            result = ModelTopicScanner.scan(root);
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
